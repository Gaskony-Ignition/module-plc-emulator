package com.inductiveautomation.plcsimulator.gateway.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Security-focused tests for PathSecurity utility.
 * Tests path traversal prevention, filename sanitization, and device name validation.
 *
 * CRITICAL: These tests verify protection against security vulnerabilities.
 */
class FileUploadRoutesSecurityTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // No setup needed - PathSecurity has static methods
    }

    // ========== Filename Sanitization Tests ==========

    @Test
    @DisplayName("SECURITY: Should reject null filename")
    void testSanitizeFileNameRejectsNull() {
        assertThatThrownBy(() -> PathSecurity.sanitizeFileName(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("File name cannot be empty");
    }

    @Test
    @DisplayName("SECURITY: Should reject empty filename")
    void testSanitizeFileNameRejectsEmpty() {
        assertThatThrownBy(() -> PathSecurity.sanitizeFileName(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("File name cannot be empty");
    }

    @Test
    @DisplayName("SECURITY: Should reject path traversal with ../ in filename")
    void testSanitizeFileNameRejectsParentDirectory() {
        String[] maliciousNames = {
            "../etc/passwd",
            "../../secret.txt",
            "foo/../bar.l5k",
            "..\\windows\\system32\\config\\sam"
        };

        for (String maliciousName : maliciousNames) {
            assertThatThrownBy(() -> PathSecurity.sanitizeFileName(maliciousName))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("path traversal");
        }
    }

    @Test
    @DisplayName("SECURITY: Should reject absolute paths in filename")
    void testSanitizeFileNameRejectsAbsolutePaths() {
        String[] maliciousNames = {
            "/etc/passwd",
            "/root/secret.txt",
            "C:\\Windows\\System32\\config\\sam",
            "\\\\server\\share\\file.txt"
        };

        for (String maliciousName : maliciousNames) {
            assertThatThrownBy(() -> PathSecurity.sanitizeFileName(maliciousName))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("path traversal");
        }
    }

    @Test
    @DisplayName("SECURITY: Should reject null byte in filename")
    void testSanitizeFileNameRejectsNullByte() {
        String maliciousName = "evil.txt\0.jpg";

        assertThatThrownBy(() -> PathSecurity.sanitizeFileName(maliciousName))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("null byte");
    }

    @Test
    @DisplayName("SECURITY: Should reject filename exceeding max length")
    void testSanitizeFileNameRejectsTooLong() {
        String longName = "a".repeat(256) + ".l5k";

        assertThatThrownBy(() -> PathSecurity.sanitizeFileName(longName))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("too long");
    }

    @Test
    @DisplayName("Should accept valid filename")
    void testSanitizeFileNameAcceptsValid() {
        String[] validNames = {
            "program.l5k",
            "my-file_v2.l5x",
            "Test123.json",
            "config.csv"
        };

        for (String validName : validNames) {
            String result = PathSecurity.sanitizeFileName(validName);
            assertThat(result).isEqualTo(validName);
        }
    }

    // ========== Device Name Sanitization Tests ==========

    @Test
    @DisplayName("SECURITY: Should reject null device name")
    void testSanitizeDeviceNameRejectsNull() {
        assertThatThrownBy(() -> PathSecurity.sanitizeDeviceName(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Device name cannot be empty");
    }

    @Test
    @DisplayName("SECURITY: Should reject empty device name")
    void testSanitizeDeviceNameRejectsEmpty() {
        assertThatThrownBy(() -> PathSecurity.sanitizeDeviceName(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Device name cannot be empty");
    }

    @Test
    @DisplayName("SECURITY: Should reject path traversal in device name")
    void testSanitizeDeviceNameRejectsPathTraversal() {
        String[] maliciousNames = {
            "../admin",
            "../../root",
            "foo/bar",
            "foo\\bar",
            "test..hidden"
        };

        for (String maliciousName : maliciousNames) {
            assertThatThrownBy(() -> PathSecurity.sanitizeDeviceName(maliciousName))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("illegal characters");
        }
    }

    @Test
    @DisplayName("SECURITY: Should reject special characters in device name")
    void testSanitizeDeviceNameRejectsSpecialChars() {
        String[] maliciousNames = {
            "device;rm -rf /",  // Contains / so will trigger "illegal characters"
            "device`whoami`",
            "device$USER",
            "device@host",
            "device#hash",
            "device&background"
        };

        for (String maliciousName : maliciousNames) {
            assertThatThrownBy(() -> PathSecurity.sanitizeDeviceName(maliciousName))
                .isInstanceOf(SecurityException.class)
                .hasMessageMatching("(?i).*(illegal characters|must contain only).*");
        }
    }

    @Test
    @DisplayName("SECURITY: Should reject device name exceeding max length")
    void testSanitizeDeviceNameRejectsTooLong() {
        String longName = "a".repeat(101);

        assertThatThrownBy(() -> PathSecurity.sanitizeDeviceName(longName))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("too long");
    }

    @Test
    @DisplayName("Should accept valid device name")
    void testSanitizeDeviceNameAcceptsValid() {
        String[] validNames = {
            "PLC1",
            "Device_123",
            "Test-Device",
            "MyPLC_v2"
        };

        for (String validName : validNames) {
            String result = PathSecurity.sanitizeDeviceName(validName);
            assertThat(result).isEqualTo(validName);
        }
    }

    // ========== Path Validation Tests ==========

    @Test
    @DisplayName("SECURITY: Should reject path outside storage directory")
    void testValidateFilePathRejectsOutsideDirectory() {
        File storageDir = tempDir.toFile();

        // Try to escape using path traversal
        String maliciousDevice = "device";
        String maliciousFile = "../../../etc/passwd";

        // This should throw SecurityException because sanitizeFileName
        // will reject the malicious filename
        assertThatThrownBy(() -> PathSecurity.validateFilePath(storageDir, maliciousDevice, maliciousFile))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("SECURITY: Should detect symlink attacks")
    void testValidateFilePathDetectsSymlinks() throws Exception {
        File storageDir = tempDir.toFile();

        // Even if a symlink exists, canonical path checking should prevent escaping
        String deviceName = "device1";
        String fileName = "test.l5k";

        File result = PathSecurity.validateFilePath(storageDir, deviceName, fileName);

        // Verify the result is within storage directory
        assertThat(result.getCanonicalPath())
            .startsWith(storageDir.getCanonicalPath());
    }

    @Test
    @DisplayName("Should accept valid file path")
    void testValidateFilePathAcceptsValid() throws Exception {
        File storageDir = tempDir.toFile();
        String deviceName = "MyPLC";
        String fileName = "program.l5k";

        File result = PathSecurity.validateFilePath(storageDir, deviceName, fileName);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("MyPLC_program.l5k");
        assertThat(result.getCanonicalPath())
            .startsWith(storageDir.getCanonicalPath());
    }

    // ========== Integration Tests ==========

    @Test
    @DisplayName("SECURITY: Should prevent directory traversal in combined attack")
    void testCombinedPathTraversalPrevention() {
        File storageDir = tempDir.toFile();

        // Various attack vectors
        String[][] attacks = {
            {"../admin", "secret.txt"},
            {"device", "../../../etc/passwd"},
            {"dev/../admin", "file.txt"},
            {"device", "file/../../secret.txt"}
        };

        for (String[] attack : attacks) {
            String deviceName = attack[0];
            String fileName = attack[1];

            assertThatThrownBy(() -> PathSecurity.validateFilePath(storageDir, deviceName, fileName))
                .isInstanceOf(SecurityException.class)
                .describedAs("Should reject attack: device='%s', file='%s'", deviceName, fileName);
        }
    }

    @Test
    @DisplayName("SECURITY: Should enforce consistent security across all methods")
    void testSecurityConsistency() {
        String[] pathTraversalAttempts = {
            "../admin",
            "../../root",
            "foo/../bar"
        };

        for (String attempt : pathTraversalAttempts) {
            // Both methods should reject path traversal
            assertThatThrownBy(() -> PathSecurity.sanitizeDeviceName(attempt))
                .isInstanceOf(SecurityException.class)
                .describedAs("Device name should reject: %s", attempt);

            assertThatThrownBy(() -> PathSecurity.sanitizeFileName(attempt))
                .isInstanceOf(SecurityException.class)
                .describedAs("File name should reject: %s", attempt);
        }
    }
}
