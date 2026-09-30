package com.inductiveautomation.logixemulator.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages file versions for PLC files.
 * Keeps a configurable number of backup versions.
 */
public class FileVersionManager {

    private static final Logger logger = LoggerFactory.getLogger(FileVersionManager.class);
    private static final int MAX_VERSIONS = 5; // Keep last 5 versions
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final File storageDir;
    private final String deviceName;

    /**
     * Create a file version manager.
     *
     * @param storageDir Directory where files are stored
     * @param deviceName Device name (for organizing versions)
     */
    public FileVersionManager(File storageDir, String deviceName) {
        this.storageDir = storageDir;
        this.deviceName = deviceName;
    }

    /**
     * Save a new version of the file.
     *
     * @param sourceFile File to version
     * @param fileName Original file name
     * @return true if version was saved successfully
     */
    public boolean saveVersion(File sourceFile, String fileName) {
        if (!sourceFile.exists()) {
            logger.warn("Cannot version non-existent file: {}", sourceFile.getAbsolutePath());
            return false;
        }

        try {
            // Create versions directory
            File versionsDir = new File(storageDir, "versions");
            if (!versionsDir.exists()) {
                if (!versionsDir.mkdirs() && !versionsDir.exists()) {
                    logger.warn("Failed to create versions directory: {}", versionsDir.getAbsolutePath());
                }
            }

            // Create device-specific version directory
            File deviceVersionsDir = new File(versionsDir, sanitizeFileName(deviceName));
            if (!deviceVersionsDir.exists()) {
                if (!deviceVersionsDir.mkdirs() && !deviceVersionsDir.exists()) {
                    logger.warn("Failed to create versions directory: {}", deviceVersionsDir.getAbsolutePath());
                }
            }

            // Generate version filename with timestamp
            String timestamp = LocalDateTime.now().format(DATE_FORMAT);
            String baseFileName = getBaseFileName(fileName);
            String extension = getFileExtension(fileName);
            String versionFileName = String.format("%s_%s%s", baseFileName, timestamp, extension);

            File versionFile = new File(deviceVersionsDir, versionFileName);

            // Copy file to version
            Files.copy(sourceFile.toPath(), versionFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            logger.info("Saved file version: {}", versionFile.getAbsolutePath());

            // Cleanup old versions
            cleanupOldVersions(deviceVersionsDir, baseFileName);

            return true;

        } catch (IOException e) {
            logger.error("Failed to save file version", e);
            return false;
        }
    }

    /**
     * Get list of available versions for a file.
     *
     * @param fileName File name
     * @return List of version files (newest first)
     */
    public List<File> getVersions(String fileName) {
        File deviceVersionsDir = new File(new File(storageDir, "versions"), sanitizeFileName(deviceName));

        if (!deviceVersionsDir.exists()) {
            return List.of();
        }

        String baseFileName = getBaseFileName(fileName);

        File[] versionFiles = deviceVersionsDir.listFiles((dir, name) ->
            name.startsWith(baseFileName + "_") && !name.equals(fileName)
        );

        if (versionFiles == null || versionFiles.length == 0) {
            return List.of();
        }

        // Sort by last modified (newest first)
        return Arrays.stream(versionFiles)
            .sorted(Comparator.comparingLong(File::lastModified).reversed())
            .collect(Collectors.toList());
    }

    /**
     * Restore a specific version.
     *
     * @param versionFile Version file to restore
     * @param targetFile Target file to restore to
     * @return true if restored successfully
     */
    public boolean restoreVersion(File versionFile, File targetFile) {
        if (!versionFile.exists()) {
            logger.error("Version file does not exist: {}", versionFile.getAbsolutePath());
            return false;
        }

        try {
            Files.copy(versionFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.info("Restored version from: {}", versionFile.getAbsolutePath());
            return true;

        } catch (IOException e) {
            logger.error("Failed to restore version", e);
            return false;
        }
    }

    /**
     * Get the most recent version.
     *
     * @param fileName File name
     * @return Most recent version file, or null if none exist
     */
    public File getMostRecentVersion(String fileName) {
        List<File> versions = getVersions(fileName);
        return versions.isEmpty() ? null : versions.get(0);
    }

    /**
     * Delete all versions for a device.
     */
    public void deleteAllVersions() {
        File deviceVersionsDir = new File(new File(storageDir, "versions"), sanitizeFileName(deviceName));

        if (deviceVersionsDir.exists()) {
            File[] files = deviceVersionsDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.delete()) {
                        logger.debug("Deleted version: {}", file.getName());
                    }
                }
            }

            if (deviceVersionsDir.delete()) {
                logger.info("Deleted all versions for device: {}", deviceName);
            }
        }
    }

    /**
     * Cleanup old versions, keeping only the most recent MAX_VERSIONS.
     */
    private void cleanupOldVersions(File versionsDir, String baseFileName) {
        File[] versionFiles = versionsDir.listFiles((dir, name) ->
            name.startsWith(baseFileName + "_")
        );

        if (versionFiles == null || versionFiles.length <= MAX_VERSIONS) {
            return;
        }

        // Sort by last modified (oldest first)
        List<File> sortedFiles = Arrays.stream(versionFiles)
            .sorted(Comparator.comparingLong(File::lastModified))
            .collect(Collectors.toList());

        // Delete oldest versions
        int toDelete = sortedFiles.size() - MAX_VERSIONS;
        for (int i = 0; i < toDelete; i++) {
            File fileToDelete = sortedFiles.get(i);
            if (fileToDelete.delete()) {
                logger.debug("Deleted old version: {}", fileToDelete.getName());
            }
        }

        logger.info("Cleaned up old versions, kept {} most recent", MAX_VERSIONS);
    }

    /**
     * Get base filename without extension.
     */
    private String getBaseFileName(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }

    /**
     * Get file extension including dot.
     */
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot) : "";
    }

    /**
     * Sanitize filename for use in directory names.
     */
    private String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /**
     * Get total number of versions.
     */
    public int getVersionCount(String fileName) {
        return getVersions(fileName).size();
    }

    /**
     * Get maximum number of versions kept.
     */
    public static int getMaxVersions() {
        return MAX_VERSIONS;
    }
}
