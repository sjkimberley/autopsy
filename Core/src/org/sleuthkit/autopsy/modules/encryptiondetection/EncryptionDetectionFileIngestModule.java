/*
 * Autopsy Forensic Browser
 *
 * Copyright 2017 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.modules.encryptiondetection;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.logging.Level;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.casemodule.services.Blackboard;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.ingest.FileIngestModuleAdapter;
import org.sleuthkit.autopsy.ingest.IngestJobContext;
import org.sleuthkit.autopsy.ingest.IngestMessage;
import org.sleuthkit.autopsy.ingest.IngestModule;
import org.sleuthkit.autopsy.ingest.IngestServices;
import org.sleuthkit.autopsy.ingest.ModuleDataEvent;
import org.sleuthkit.autopsy.modules.filetypeid.FileTypeDetector;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.BlackboardArtifact;
import org.sleuthkit.datamodel.ReadContentInputStream;
import org.sleuthkit.datamodel.TskCoreException;
import org.sleuthkit.datamodel.TskData;

/**
 * File ingest module to detect encryption.
 */
final class EncryptionDetectionFileIngestModule extends FileIngestModuleAdapter {

    static final double DEFAULT_CONFIG_MINIMUM_ENTROPY = 7.5;
    static final int DEFAULT_CONFIG_MINIMUM_FILE_SIZE = 5242880; // 5MB;
    static final boolean DEFAULT_CONFIG_FILE_SIZE_MULTIPLE_ENFORCED = true;
    static final boolean DEFAULT_CONFIG_SLACK_FILES_ALLOWED = true;

    private static final int FILE_SIZE_MODULUS = 512;
    private static final double ONE_OVER_LOG2 = 1.4426950408889634073599246810019; // (1 / log(2))
    private static final int BYTE_OCCURENCES_BUFFER_SIZE = 256;

    private final IngestServices SERVICES = IngestServices.getInstance();
    private final Logger LOGGER = SERVICES.getLogger(EncryptionDetectionModuleFactory.getModuleName());
    private FileTypeDetector fileTypeDetector;
    private Blackboard blackboard;
    private double calculatedEntropy;

    private final double minimumEntropy;
    private final int minimumFileSize;
    private final boolean fileSizeMultipleEnforced;
    private final boolean slackFilesAllowed;

    /**
     * Create a EncryptionDetectionFileIngestModule object that will detect
     * files that are encrypted and create blackboard artifacts as appropriate.
     * The supplied EncryptionDetectionIngestJobSettings object is used to
     * configure the module.
     */
    EncryptionDetectionFileIngestModule(EncryptionDetectionIngestJobSettings settings) {
        minimumEntropy = settings.getMinimumEntropy();
        minimumFileSize = settings.getMinimumFileSize();
        fileSizeMultipleEnforced = settings.isFileSizeMultipleEnforced();
        slackFilesAllowed = settings.isSlackFilesAllowed();
    }

    @Override
    public void startUp(IngestJobContext context) throws IngestModule.IngestModuleException {
        blackboard = Case.getCurrentCase().getServices().getBlackboard();
        try {
            fileTypeDetector = new FileTypeDetector();
        } catch (FileTypeDetector.FileTypeDetectorInitException ex) {
            throw new IngestModule.IngestModuleException("Failed to create file type detector", ex);
        }
    }

    @Override
    public IngestModule.ProcessResult process(AbstractFile file) {

        try {
            if (isFileEncrypted(file)) {
                return flagFile(file);
            }
        } catch (IOException | TskCoreException ex) {
            LOGGER.log(Level.SEVERE, String.format("Unable to process file '%s'", Paths.get(file.getParentPath(), file.getName())), ex);
            return IngestModule.ProcessResult.ERROR;
        }

        return IngestModule.ProcessResult.OK;
    }

    /**
     * Create a blackboard artifact.
     *
     * @param The file to be processed.
     *
     * @return 'OK' if the file was processed successfully, or 'ERROR' if there
     *         was a problem.
     */
    private IngestModule.ProcessResult flagFile(AbstractFile file) {
        try {
            BlackboardArtifact artifact = file.newArtifact(BlackboardArtifact.ARTIFACT_TYPE.TSK_ENCRYPTION_SUSPECTED);

            try {
                /*
                 * Index the artifact for keyword search.
                 */
                blackboard.indexArtifact(artifact);
            } catch (Blackboard.BlackboardException ex) {
                LOGGER.log(Level.SEVERE, "Unable to index blackboard artifact " + artifact.getArtifactID(), ex); //NON-NLS
            }

            /*
             * Send an event to update the view with the new result.
             */
            SERVICES.fireModuleDataEvent(new ModuleDataEvent(EncryptionDetectionModuleFactory.getModuleName(), BlackboardArtifact.ARTIFACT_TYPE.TSK_ENCRYPTION_SUSPECTED, Collections.singletonList(artifact)));

            /*
             * Make an ingest inbox message.
             */
            StringBuilder detailsSb = new StringBuilder();
            detailsSb.append("File: ").append(file.getParentPath()).append(file.getName()).append("<br/>\n");
            detailsSb.append("Entropy: ").append(calculatedEntropy);

            SERVICES.postMessage(IngestMessage.createDataMessage(EncryptionDetectionModuleFactory.getModuleName(),
                    "Encryption Detected Match: " + file.getName(),
                    detailsSb.toString(),
                    file.getName(),
                    artifact));

            return IngestModule.ProcessResult.OK;
        } catch (TskCoreException ex) {
            LOGGER.log(Level.SEVERE, String.format("Failed to create blackboard artifact for '%s'.", Paths.get(file.getParentPath(), file.getName())), ex); //NON-NLS
            return IngestModule.ProcessResult.ERROR;
        }
    }

    /**
     * This method checks if the AbstractFile input is encrypted. Initial
     * qualifications require that it be an actual file that is not known, meets
     * file size requirements, and has a MIME type of
     * 'application/octet-stream'.
     *
     * @param file AbstractFile to be checked.
     *
     * @return True if the AbstractFile is encrypted.
     */
    private boolean isFileEncrypted(AbstractFile file) throws IOException, TskCoreException {
        /*
         * Criteria for the checks in this method are partially based on
         * http://www.forensicswiki.org/wiki/TrueCrypt#Detection
         */

        boolean possiblyEncrypted = false;

        /*
         * Qualify the file type.
         */
        if (!file.getType().equals(TskData.TSK_DB_FILES_TYPE_ENUM.UNALLOC_BLOCKS)
                && !file.getType().equals(TskData.TSK_DB_FILES_TYPE_ENUM.UNUSED_BLOCKS)
                && !file.getType().equals(TskData.TSK_DB_FILES_TYPE_ENUM.VIRTUAL_DIR)
                && !file.getType().equals(TskData.TSK_DB_FILES_TYPE_ENUM.LOCAL_DIR)
                && (!file.getType().equals(TskData.TSK_DB_FILES_TYPE_ENUM.SLACK) || slackFilesAllowed)) {
            /*
             * Qualify the file against hash databases.
             */
            if (!file.getKnown().equals(TskData.FileKnown.KNOWN)) {
                /*
                 * Qualify the size.
                 */
                long contentSize = file.getSize();
                if (contentSize >= minimumFileSize) {
                    if (!fileSizeMultipleEnforced || (contentSize % FILE_SIZE_MODULUS) == 0) {
                        /*
                         * Qualify the MIME type.
                         */
                        try {
                            String mimeType = fileTypeDetector.getFileType(file);
                            if (mimeType != null && mimeType.equals("application/octet-stream")) {
                                possiblyEncrypted = true;
                            }
                        } catch (TskCoreException ex) {
                            throw new TskCoreException("Failed to detect the file type.", ex);
                        }
                    }
                }
            }
        }

        if (possiblyEncrypted) {
            try {
                calculatedEntropy = calculateEntropy(file);
                if (calculatedEntropy >= minimumEntropy) {
                    return true;
                }
            } catch (IOException ex) {
                throw new IOException("Unable to calculate the entropy.", ex);
            }
        }

        return false;
    }

    /**
     * Calculate the entropy of the file. The result is used to qualify the file
     * as an encrypted file.
     *
     * @param file The file to be calculated against.
     *
     * @return The entropy of the file.
     *
     * @throws IOException If there is a failure closing or reading from the
     *                     InputStream.
     */
    private double calculateEntropy(AbstractFile file) throws IOException {
        /*
         * Logic in this method is based on
         * https://github.com/willjasen/entropy/blob/master/entropy.java
         */

        InputStream in = null;
        BufferedInputStream bin = null;

        try {
            in = new ReadContentInputStream(file);
            bin = new BufferedInputStream(in);

            /*
             * Determine the number of times each byte value appears.
             */
            int[] byteOccurences = new int[BYTE_OCCURENCES_BUFFER_SIZE];
            int readByte;
            while ((readByte = bin.read()) != -1) {
                byteOccurences[readByte]++;
            }

            /*
             * Calculate the entropy based on the byte occurence counts.
             */
            long dataLength = file.getSize() - 1;
            double entropyAccumulator = 0;
            for (int i = 0; i < BYTE_OCCURENCES_BUFFER_SIZE; i++) {
                if (byteOccurences[i] > 0) {
                    double byteProbability = (double) byteOccurences[i] / (double) dataLength;
                    entropyAccumulator += (byteProbability * Math.log(byteProbability) * ONE_OVER_LOG2);
                }
            }

            return -entropyAccumulator;

        } catch (IOException ex) {
            throw new IOException("IOException occurred while trying to read data from InputStream.", ex);
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
                if (bin != null) {
                    bin.close();
                }
            } catch (IOException ex) {
                throw new IOException("Failed to close InputStream.", ex);
            }
        }
    }
}
