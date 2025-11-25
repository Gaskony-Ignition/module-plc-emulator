package com.inductiveautomation.plcsimulator.gateway.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * Security utilities for path validation and sanitization.
 * Prevents path traversal attacks and ensures files stay within allowed directories.
 */
public final class PathSecurity {

    private static final Logger logger = LoggerFactory.getLogger(PathSecurity.class);

    private PathSecurity() {
        // Utility class
    }

    /**
     * Sanitize filename to prevent directory traversal attacks.
     * @throws SecurityException if filename contains dangerous patterns
     */
    public static String sanitizeFileName(String filename) {
        if (filename == null || filename.isEmpty()) {
            throw new IllegalArgumentException("File name cannot be empty");
        }

        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            throw new SecurityException("Invalid filename: path traversal attempt detected");
        }

        if (filename.contains("\0")) {
            throw new SecurityException("Invalid filename: null byte detected");
        }

        if (filename.length() > 255) {
            throw new SecurityException("Invalid filename: too long (max 255 characters)");
        }

        String sanitized = java.nio.file.Paths.get(filename).getFileName().toString();

        if (sanitized.contains("/") || sanitized.contains("\\")) {
            throw new SecurityException("Invalid filename: contains path separators");
        }

        return sanitized;
    }

    /**
     * Sanitize device name for use in file paths.
     * @throws SecurityException if device name contains dangerous patterns
     */
    public static String sanitizeDeviceName(String deviceName) {
        if (deviceName == null || deviceName.isEmpty()) {
            throw new IllegalArgumentException("Device name cannot be empty");
        }

        if (deviceName.contains("..") || deviceName.contains("/") ||
            deviceName.contains("\\") || deviceName.contains("\0")) {
            throw new SecurityException("Invalid device name: contains illegal characters");
        }

        if (!deviceName.matches("^[a-zA-Z0-9_-]+$")) {
            throw new SecurityException("Invalid device name: must contain only letters, numbers, underscores, and hyphens");
        }

        if (deviceName.length() > 100) {
            throw new SecurityException("Invalid device name: too long (max 100 characters)");
        }

        return deviceName;
    }

    /**
     * Validate that a file path is within the allowed storage directory.
     * @throws SecurityException if path would escape the storage directory
     */
    public static File validateFilePath(File storageDir, String deviceName, String fileName) {
        try {
            String safeDeviceName = sanitizeDeviceName(deviceName);
            String safeFileName = sanitizeFileName(fileName);

            File deviceFile = new File(storageDir, safeDeviceName + "_" + safeFileName);

            String canonicalFilePath = deviceFile.getCanonicalPath();
            String canonicalStorageDir = storageDir.getCanonicalPath();

            if (!canonicalFilePath.startsWith(canonicalStorageDir + File.separator)) {
                logger.error("Path traversal attempt: device={}, file={}, resolved={}",
                    deviceName, fileName, canonicalFilePath);
                throw new SecurityException("Invalid file path: outside storage directory");
            }

            return deviceFile;

        } catch (IOException e) {
            logger.error("Error validating file path", e);
            throw new SecurityException("Invalid file path", e);
        }
    }
}
