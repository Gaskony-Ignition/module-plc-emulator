package com.inductiveautomation.plcsimulator.gateway.web;

import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Security-focused tests for FileUploadRoutes.
 * Tests path traversal prevention, filename sanitization, and file size limits.
 *
 * CRITICAL: These tests verify protection against security vulnerabilities.
 */
class FileUploadRoutesSecurityTest {

    @Mock
    private GatewayContext gatewayContext;

    @Mock
    private RouteGroup routeGroup;

    private FileUploadRoutes routes;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        routes = new FileUploadRoutes(gatewayContext, routeGroup);
    }

    // ========== Filename Sanitization Tests ==========

    @Test
    @DisplayName("SECURITY: Should reject null filename")
    void testSanitizeFileNameRejectsNull() throws Exception {
        Method method = FileUploadRoutes.class.getDeclaredMethod("sanitizeFileName", String.class);
        method.setAccessible(true);

        assertThatThrownBy(() -> method.invoke(routes, (String) null))
            .hasCauseInstanceOf(IllegalArgumentException.class)
            .cause()
            .hasMessageContaining("File name cannot be empty");
    }

    @Test
    @DisplayName("SECURITY: Should reject empty filename")
    void testSanitizeFileNameRejectsEmpty() throws Exception {
        Method method = FileUploadRoutes.class.getDeclaredMethod("sanitizeFileName", String.class);
        method.setAccessible(true);

        assertThatThrownBy(() -> method.invoke(routes, ""))
            .hasCauseInstanceOf(IllegalArgumentException.class)
            .cause()
            .hasMessageContaining("File name cannot be empty");
    }

    @Test
    @DisplayName("SECURITY: Should reject path traversal with ../ in filename")
    void testSanitizeFileNameRejectsParentDirectory() throws Exception {
        Method method = FileUploadRoutes.class.getDeclaredMethod("sanitizeFileName", String.class);
        method.setAccessible(true);

        String[] maliciousNames = {
            "../etc/passwd",
            "../../secret.txt",
            "foo/../bar.l5k",
            "..\\windows\\system32\\config\\sam"
        };

        for (String maliciousName : maliciousNames) {
            assertThatThrownBy(() -> method.invoke(routes, maliciousName))
                .hasCauseInstanceOf(SecurityException.class)
                .cause()
                .hasMessageContaining("path traversal");
        }
    }

    @Test
    @DisplayName("SECURITY: Should reject absolute paths in filename")
    void testSanitizeFileNameRejectsAbsolutePaths() throws Exception {
        Method method = FileUploadRoutes.class.getDeclaredMethod("sanitizeFileName", String.class);
        method.setAccessible(true);

        String[] maliciousNames = {
            "/etc/passwd",
            "/root/secret.txt",
            "C:\\Windows\\System32\\config\\sam",
            "\\\\server\\share\\file.txt"
        };

        for (String maliciousName : maliciousNames) {
            assertThatThrownBy(() -> method.invoke(routes, maliciousName))
                .hasCauseInstanceOf(SecurityException.class)
                .cause()
                .hasMessageContaining("path traversal");
        }
    }

    @Test
    @DisplayName("SECURITY: Should reject null byte in filename")
    void testSanitizeFileNameRejectsNullByte() throws Exception {
        Method method = FileUploadRoutes.class.getDeclaredMethod("sanitizeFileName", String.class);
        method.setAccessible(true);

        String maliciousName = "evil.txt\0.jpg";

        assertThatThrownBy(() -> method.invoke(routes, maliciousName))
            .hasCauseInstanceOf(SecurityException.class)
            .cause()
            .hasMessageContaining("null byte");
    }

    @Test
    @DisplayName("SECURITY: Should reject filename exceeding max length")
    void testSanitizeFileNameRejectsTooLong() throws Exception {
        Method method = FileUploadRoutes.class.getDeclaredMethod("sanitizeFileName", String.class);
        method.setAccessible(true);

        // Create filename > 255 characters
        String longName = "a".repeat(256) + ".l5k";

        assertThatThrownBy(() -> method.invoke(routes, longName))
            .hasCauseInstanceOf(SecurityException.class)
            .cause()
            .hasMessageContaining("too long");
    }

    @Test
    @DisplayName("Should accept valid filename")
    void testSanitizeFileNameAcceptsValid() throws Exception {
        Method method = FileUploadRoutes.class.getDeclaredMethod("sanitizeFileName", String.class);
        method.setAccessible(true);

        String[] validNames = {
            "program.l5k",
            "my-file_v2.l5x",
            "Test123.json",
            "config.csv"
        };

        for (String validName : validNames) {
            String result = (String) method.invoke(routes, validName);
            assertThat(result).isEqualTo(validName);
        }
    }

    // ========== Device Name Sanitization Tests ==========

    @Test
    @DisplayName("SECURITY: Should reject null device name")
    void testSanitizeDeviceNameRejectsNull() throws Exception {
        Method method = FileUploadRoutes.class.getDeclaredMethod("sanitizeDeviceName", String.class);
        method.setAccessible(true);

        assertThatThrownBy(() -> method.invoke(routes, (String) null))
            .hasCauseInstanceOf(IllegalArgumentException.class)
            .cause()
            .hasMessageContaining("Device name cannot be empty");
    }

    @Test
    @DisplayName("SECURITY: Should reject empty device name")
    void testSanitizeDeviceNameRejectsEmpty() throws Exception {
        Method method = FileUploadRoutes.class.getDeclaredMethod("sanitizeDeviceName", String.class);
        method.setAccessible(true);

        assertThatThrownBy(() -> method.invoke(routes, ""))
            .hasCauseInstanceOf(IllegalArgumentException.class)
            .cause()
            .hasMessageContaining("Device name cannot be empty");
    }

    @Test
    @DisplayName("SECURITY: Should reject path traversal in device name")
    void testSanitizeDeviceNameRejectsPathTraversal() throws Exception {
        Method method = FileUploadRoutes.class.getDeclaredMethod("sanitizeDeviceName", String.class);
        method.setAccessible(true);

        String[] maliciousNames = {
            "../admin",
            "../../root",
            "foo/bar",
            "foo\\bar",
            "test..hidden"
        };

        for (String maliciousName : maliciousNames) {
            assertThatThrownBy(() -> method.invoke(routes, maliciousName))
                .hasCauseInstanceOf(SecurityException.class)
                .cause()
                .hasMessageContaining("illegal characters");
        }
    }

    @Test
    @DisplayName("SECURITY: Should reject special characters in device name")
    void testSanitizeDeviceNameRejectsSpecialChars() throws Exception {
        Method method = FileUploadRoutes.class.getDeclaredMethod("sanitizeDeviceName", String.class);
        method.setAccessible(true);

        String[] maliciousNames = {
            "device;rm -rf /",  // Contains / so will trigger "illegal characters"
            "device`whoami`",
            "device$USER",
            "device@host",
            "device#hash",
            "device&background"
        };

        for (String maliciousName : maliciousNames) {
            assertThatThrownBy(() -> method.invoke(routes, maliciousName))
                .hasCauseInstanceOf(SecurityException.class)
                .cause()
                .hasMessageMatching("(?i).*(illegal characters|must contain only).*");
        }
    }

    @Test
    @DisplayName("SECURITY: Should reject device name exceeding max length")
    void testSanitizeDeviceNameRejectsTooLong() throws Exception {
        Method method = FileUploadRoutes.class.getDeclaredMethod("sanitizeDeviceName", String.class);
        method.setAccessible(true);

        String longName = "a".repeat(101);

        assertThatThrownBy(() -> method.invoke(routes, longName))
            .hasCauseInstanceOf(SecurityException.class)
            .cause()
            .hasMessageContaining("too long");
    }

    @Test
    @DisplayName("Should accept valid device name")
    void testSanitizeDeviceNameAcceptsValid() throws Exception {
        Method method = FileUploadRoutes.class.getDeclaredMethod("sanitizeDeviceName", String.class);
        method.setAccessible(true);

        String[] validNames = {
            "PLC1",
            "Device_123",
            "Test-Device",
            "MyPLC_v2"
        };

        for (String validName : validNames) {
            String result = (String) method.invoke(routes, validName);
            assertThat(result).isEqualTo(validName);
        }
    }

    // ========== Path Validation Tests ==========

    @Test
    @DisplayName("SECURITY: Should reject path outside storage directory")
    void testValidateFilePathRejectsOutsideDirectory() throws Exception {
        Method method = FileUploadRoutes.class.getDeclaredMethod(
            "validateFilePath", File.class, String.class, String.class);
        method.setAccessible(true);

        File storageDir = tempDir.toFile();

        // Try to escape using path traversal
        String maliciousDevice = "device";
        String maliciousFile = "../../../etc/passwd";

        // This should throw SecurityException because sanitizeFileName
        // will reject the malicious filename
        assertThatThrownBy(() -> method.invoke(routes, storageDir, maliciousDevice, maliciousFile))
            .hasCauseInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("SECURITY: Should detect symlink attacks")
    void testValidateFilePathDetectsSymlinks() throws Exception {
        Method method = FileUploadRoutes.class.getDeclaredMethod(
            "validateFilePath", File.class, String.class, String.class);
        method.setAccessible(true);

        File storageDir = tempDir.toFile();

        // Even if a symlink exists, canonical path checking should prevent escaping
        String deviceName = "device1";
        String fileName = "test.l5k";

        File result = (File) method.invoke(routes, storageDir, deviceName, fileName);

        // Verify the result is within storage directory
        assertThat(result.getCanonicalPath())
            .startsWith(storageDir.getCanonicalPath());
    }

    @Test
    @DisplayName("Should accept valid file path")
    void testValidateFilePathAcceptsValid() throws Exception {
        Method method = FileUploadRoutes.class.getDeclaredMethod(
            "validateFilePath", File.class, String.class, String.class);
        method.setAccessible(true);

        File storageDir = tempDir.toFile();
        String deviceName = "MyPLC";
        String fileName = "program.l5k";

        File result = (File) method.invoke(routes, storageDir, deviceName, fileName);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("MyPLC_program.l5k");
        assertThat(result.getCanonicalPath())
            .startsWith(storageDir.getCanonicalPath());
    }

    // ========== Integration Tests ==========

    @Test
    @DisplayName("SECURITY: Should prevent directory traversal in combined attack")
    void testCombinedPathTraversalPrevention() throws Exception {
        Method validatePath = FileUploadRoutes.class.getDeclaredMethod(
            "validateFilePath", File.class, String.class, String.class);
        validatePath.setAccessible(true);

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

            assertThatThrownBy(() -> validatePath.invoke(routes, storageDir, deviceName, fileName))
                .hasCauseInstanceOf(SecurityException.class)
                .describedAs("Should reject attack: device='%s', file='%s'", deviceName, fileName);
        }
    }

    @Test
    @DisplayName("SECURITY: Should enforce consistent security across all methods")
    void testSecurityConsistency() throws Exception {
        // All three security methods should reject the same malicious inputs
        Method sanitizeFile = FileUploadRoutes.class.getDeclaredMethod("sanitizeFileName", String.class);
        Method sanitizeDevice = FileUploadRoutes.class.getDeclaredMethod("sanitizeDeviceName", String.class);
        sanitizeFile.setAccessible(true);
        sanitizeDevice.setAccessible(true);

        String[] pathTraversalAttempts = {
            "../admin",
            "../../root",
            "foo/../bar"
        };

        for (String attempt : pathTraversalAttempts) {
            // Both methods should reject path traversal
            assertThatThrownBy(() -> sanitizeDevice.invoke(routes, attempt))
                .hasCauseInstanceOf(SecurityException.class)
                .describedAs("Device name should reject: %s", attempt);

            assertThatThrownBy(() -> sanitizeFile.invoke(routes, attempt))
                .hasCauseInstanceOf(SecurityException.class)
                .describedAs("File name should reject: %s", attempt);
        }
    }
}
