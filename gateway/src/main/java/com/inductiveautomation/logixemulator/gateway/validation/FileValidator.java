package com.inductiveautomation.logixemulator.gateway.validation;

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

    // Default limits (can be overridden per-device)
    private static final long DEFAULT_MAX_SIZE_MB = 50;
    private static final long MIN_MAX_SIZE_MB = 1;
    private static final long MAX_MAX_SIZE_MB = 500;

    // Supported file extensions (checked case-insensitively)
    private static final List<String> SUPPORTED_EXTENSIONS = Arrays.asList(
        ".l5k", ".l5x", ".json", ".csv"
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
     * Validate a file for processing using default max size.
     */
    public static ValidationResult validateFile(File file) {
        return validateFile(file, DEFAULT_MAX_SIZE_MB);
    }

    /**
     * Validate a file for processing with configurable max size.
     * @param file The file to validate
     * @param maxSizeMB Maximum allowed file size in MB (clamped to 1-500 range)
     */
    public static ValidationResult validateFile(File file, long maxSizeMB) {
        // Clamp maxSizeMB to valid range
        long effectiveMaxSizeMB = Math.max(MIN_MAX_SIZE_MB, Math.min(MAX_MAX_SIZE_MB, maxSizeMB));
        long maxSizeBytes = effectiveMaxSizeMB * 1024 * 1024;

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

        if (fileSize > maxSizeBytes) {
            return ValidationResult.failure(String.format(
                "File size (%d MB) exceeds maximum allowed size (%d MB)",
                fileSize / (1024 * 1024),
                effectiveMaxSizeMB
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
     * Validate file content (string) using default max size.
     */
    public static ValidationResult validateContent(String content, String fileName) {
        return validateContent(content, fileName, DEFAULT_MAX_SIZE_MB);
    }

    /**
     * Validate file content (string) with configurable max size.
     * @param content The file content to validate
     * @param fileName The filename (used for format detection)
     * @param maxSizeMB Maximum allowed content size in MB (clamped to 1-500 range)
     */
    public static ValidationResult validateContent(String content, String fileName, long maxSizeMB) {
        // Clamp maxSizeMB to valid range
        long effectiveMaxSizeMB = Math.max(MIN_MAX_SIZE_MB, Math.min(MAX_MAX_SIZE_MB, maxSizeMB));
        long maxSizeBytes = effectiveMaxSizeMB * 1024 * 1024;

        if (content == null || content.isEmpty()) {
            return ValidationResult.failure("File content is empty");
        }

        // Check content size
        long contentSize = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

        // Defect B6: String.trim() strips any character <= U+0020 (space) from both ends - that
        // includes NUL (0x00) and other control bytes, not just whitespace. A large file
        // consisting entirely of NUL bytes (e.g. a corrupted/truncated export) is NOT empty - it
        // has real content-size bytes, per contentSize above - but content.trim().isEmpty() would
        // be true, and reporting "File content is empty" for an 11MB upload is actively
        // misleading (item6-validation.txt). Distinguish the two cases with an accurate message.
        if (content.trim().isEmpty()) {
            return ValidationResult.failure(String.format(
                "File content (%d bytes) is not empty but contains no readable data - "
                    + "it is entirely whitespace or null/control bytes",
                contentSize));
        }
        if (contentSize > maxSizeBytes) {
            return ValidationResult.failure(String.format(
                "Content size (%d MB) exceeds maximum allowed size (%d MB)",
                contentSize / (1024 * 1024),
                effectiveMaxSizeMB
            ));
        }

        // Validate file extension
        if (fileName != null) {
            String lowerName = fileName.toLowerCase();
            boolean hasValidExtension = SUPPORTED_EXTENSIONS.stream()
                .anyMatch(lowerName::endsWith);
            if (!hasValidExtension) {
                return ValidationResult.failure(String.format(
                    "Unsupported file format. Supported formats: %s",
                    String.join(", ", SUPPORTED_EXTENSIONS)
                ));
            }

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

            if (lowerName.endsWith(".l5x")) {
                // L5X files are XML format with RSLogix5000Content root element
                if (!content.contains("<RSLogix5000Content")) {
                    return ValidationResult.failure("Invalid L5X file - missing <RSLogix5000Content> root element. This is not a valid Studio 5000 XML export file.");
                }
                // L5X files must have reasonable size (at least 1KB for minimal valid XML)
                if (content.length() < 1000) {
                    return ValidationResult.failure("L5X file too small (" + content.length() + " bytes). Valid Studio 5000 XML files are typically 10KB or larger.");
                }
            }

            if (lowerName.endsWith(".l5k")) {
                // L5K files are plain text format (NOT XML)
                // Look for typical L5K markers like CONTROLLER, DATATYPE, TAG, PROGRAM, etc.
                String upperContent = content.toUpperCase();
                boolean hasL5KMarkers = upperContent.contains("CONTROLLER") ||
                                        upperContent.contains("DATATYPE") ||
                                        upperContent.contains("TAG") ||
                                        upperContent.contains("PROGRAM") ||
                                        upperContent.contains("ROUTINE") ||
                                        upperContent.contains("MODULE");

                if (!hasL5KMarkers) {
                    return ValidationResult.failure("Invalid L5K file - missing expected L5K format markers (CONTROLLER, DATATYPE, TAG, etc). This does not appear to be a valid RSLogix 5000 L5K export file.");
                }
                // L5K files must have reasonable size
                if (content.length() < 100) {
                    return ValidationResult.failure("L5K file too small (" + content.length() + " bytes). Valid L5K files are typically 1KB or larger.");
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
        return java.util.Collections.unmodifiableList(SUPPORTED_EXTENSIONS);
    }
}
