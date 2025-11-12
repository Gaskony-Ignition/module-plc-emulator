package com.inductiveautomation.plcsimulator.gateway.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Validates PLC files before processing.
 * Checks file size, format, and content.
 */
public class FileValidator {

    private static final Logger logger = LoggerFactory.getLogger(FileValidator.class);

    // Default limits
    private static final long DEFAULT_MAX_SIZE_MB = 50;
    private static final long MAX_SIZE_BYTES = DEFAULT_MAX_SIZE_MB * 1024 * 1024;

    // Supported extensions
    private static final List<String> SUPPORTED_EXTENSIONS = Arrays.asList(
        ".l5x", ".l5k", ".json", ".csv", ".txt", ".xml"
    );

    /**
     * Validation result.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;

        private ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult failure(String errorMessage) {
            return new ValidationResult(false, errorMessage);
        }

        public boolean isValid() {
            return valid;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    /**
     * Validate a file for processing.
     */
    public static ValidationResult validateFile(File file) {
        // Check if file exists
        if (!file.exists()) {
            return ValidationResult.failure("File does not exist: " + file.getAbsolutePath());
        }

        // Check if it's a file (not directory)
        if (!file.isFile()) {
            return ValidationResult.failure("Path is not a file: " + file.getAbsolutePath());
        }

        // Check if readable
        if (!file.canRead()) {
            return ValidationResult.failure("File is not readable: " + file.getAbsolutePath());
        }

        // Check file size
        long fileSize = file.length();
        if (fileSize == 0) {
            return ValidationResult.failure("File is empty: " + file.getAbsolutePath());
        }

        if (fileSize > MAX_SIZE_BYTES) {
            return ValidationResult.failure(String.format(
                "File size (%d MB) exceeds maximum allowed size (%d MB)",
                fileSize / (1024 * 1024),
                DEFAULT_MAX_SIZE_MB
            ));
        }

        // Check file extension
        String fileName = file.getName().toLowerCase();
        boolean hasValidExtension = SUPPORTED_EXTENSIONS.stream()
            .anyMatch(fileName::endsWith);

        if (!hasValidExtension) {
            return ValidationResult.failure(String.format(
                "Unsupported file format. Supported formats: %s",
                String.join(", ", SUPPORTED_EXTENSIONS)
            ));
        }

        return ValidationResult.success();
    }

    /**
     * Validate file content (string).
     */
    public static ValidationResult validateContent(String content, String fileName) {
        if (content == null || content.trim().isEmpty()) {
            return ValidationResult.failure("File content is empty");
        }

        // Check content size
        long contentSize = content.getBytes().length;
        if (contentSize > MAX_SIZE_BYTES) {
            return ValidationResult.failure(String.format(
                "Content size (%d MB) exceeds maximum allowed size (%d MB)",
                contentSize / (1024 * 1024),
                DEFAULT_MAX_SIZE_MB
            ));
        }

        // Basic format validation based on extension
        if (fileName != null) {
            String lowerName = fileName.toLowerCase();

            if (lowerName.endsWith(".json")) {
                // Quick JSON validation
                String trimmed = content.trim();
                if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                    return ValidationResult.failure("Invalid JSON format - must start with { or [");
                }
                if (!trimmed.endsWith("}") && !trimmed.endsWith("]")) {
                    return ValidationResult.failure("Invalid JSON format - must end with } or ]");
                }
            }

            if (lowerName.endsWith(".l5x") || lowerName.endsWith(".l5k")) {
                // L5K/L5X files must be valid XML with RSLogix5000Content root element
                if (!content.contains("<RSLogix5000Content")) {
                    return ValidationResult.failure("Invalid L5K/L5X file - missing <RSLogix5000Content> root element. This is not a valid Studio 5000 export file.");
                }
                // L5K/L5X files must have reasonable size (at least 1KB for minimal valid XML)
                if (content.length() < 1000) {
                    return ValidationResult.failure("L5K/L5X file too small (" + content.length() + " bytes). Valid Studio 5000 files are typically 10KB or larger.");
                }
            }

            if (lowerName.endsWith(".csv")) {
                // Check if at least has commas
                if (!content.contains(",") && !content.contains(";")) {
                    return ValidationResult.failure("Invalid CSV format - no delimiters found");
                }
            }
        }

        return ValidationResult.success();
    }

    /**
     * Get maximum allowed file size in MB.
     */
    public static long getMaxFileSizeMB() {
        return DEFAULT_MAX_SIZE_MB;
    }

    /**
     * Get supported file extensions.
     */
    public static List<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }
}
