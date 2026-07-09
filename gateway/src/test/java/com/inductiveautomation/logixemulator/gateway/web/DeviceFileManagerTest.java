package com.inductiveautomation.logixemulator.gateway.web;

import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.system.SystemManager;
import com.inductiveautomation.logixemulator.gateway.DeviceRegistry;
import com.inductiveautomation.logixemulator.gateway.FileVersionManager;
import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DeviceFileManager.
 * Covers storage directory resolution, file saving, device reload, and file clearing.
 */
@ExtendWith(MockitoExtension.class)
class DeviceFileManagerTest {

    @TempDir
    Path tempDir;

    @Mock
    GatewayContext context;

    @Mock
    DeviceRegistry registry;

    SystemManager systemManager;

    @Mock
    LogixEmulatorDevice device;

    DeviceFileManager manager;

    @BeforeEach
    void setUp() {
        systemManager = mock(SystemManager.class);
        when(context.getSystemManager()).thenReturn(systemManager);
        when(systemManager.getDataDir()).thenReturn(tempDir.toFile());

        // Constructor calls migrateStorageDirectory() — safe because neither
        // "plc-simulator" nor "logix-emulator" dirs exist yet in tempDir
        manager = new DeviceFileManager(context, registry);
    }

    // ========== getStorageDirectory() ==========

    @Test
    @DisplayName("getStorageDirectory() returns a File named 'logix-emulator' under tempDir")
    void testGetStorageDirectoryReturnsCorrectPath() {
        File storageDir = manager.getStorageDirectory();

        assertThat(storageDir).isNotNull();
        assertThat(storageDir.getName()).isEqualTo("logix-emulator");
        assertThat(storageDir.getParentFile()).isEqualTo(tempDir.toFile());
    }

    // ========== saveFileToDevice() — happy path ==========

    @Test
    @DisplayName("saveFileToDevice() creates storage dir, writes file, calls setCurrentFilePath")
    void testSaveFileToDeviceHappyPath() throws Exception {
        when(device.getName()).thenReturn("TestDevice");
        String content = "L5K file content here";
        String filename = "program.l5k";

        boolean result = manager.saveFileToDevice(device, content, filename);

        assertThat(result).isTrue();

        File storageDir = manager.getStorageDirectory();
        assertThat(storageDir).exists().isDirectory();

        verify(device).setCurrentFilePath(argThat(path ->
            path != null && path.contains("TestDevice_program.l5k")
        ));
    }

    // ========== saveFileToDevice() — sanitization ==========

    @Test
    @DisplayName("saveFileToDevice() sanitizes safe filename and writes it normally")
    void testSaveFileToDeviceSanitizesFilename() throws Exception {
        when(device.getName()).thenReturn("TestDevice");
        String content = "file content";
        String filename = "my-program.l5k";

        boolean result = manager.saveFileToDevice(device, content, filename);

        assertThat(result).isTrue();
        verify(device).setCurrentFilePath(argThat(path ->
            path != null && path.endsWith("TestDevice_my-program.l5k")
        ));
    }

    @Test
    @DisplayName("saveFileToDevice() returns false when filename contains path traversal (../../evil.l5k)")
    void testSaveFileToDeviceRejectsPathTraversalFilename() {
        // PathSecurity.sanitizeFileName throws SecurityException for ".."
        // DeviceFileManager catches Exception and returns false
        boolean result = manager.saveFileToDevice(device, "content", "../../evil.l5k");

        assertThat(result).isFalse();
        verify(device, never()).setCurrentFilePath(any());
    }

    @Test
    @DisplayName("saveFileToDevice() returns false when filename is null (IllegalArgumentException from PathSecurity)")
    void testSaveFileToDeviceRejectsNullFilename() {
        // PathSecurity.sanitizeFileName throws IllegalArgumentException for null
        boolean result = manager.saveFileToDevice(device, "content", null);

        assertThat(result).isFalse();
        verify(device, never()).setCurrentFilePath(any());
    }

    @Test
    @DisplayName("saveFileToDevice() returns false when filename is empty (IllegalArgumentException from PathSecurity)")
    void testSaveFileToDeviceRejectsEmptyFilename() {
        boolean result = manager.saveFileToDevice(device, "content", "");

        assertThat(result).isFalse();
        verify(device, never()).setCurrentFilePath(any());
    }

    // ========== saveFileToDevice() — file content on disk ==========

    @Test
    @DisplayName("saveFileToDevice() writes the exact file content to disk")
    void testSaveFileToDeviceWritesCorrectContent() throws Exception {
        when(device.getName()).thenReturn("TestDevice");
        String content = "CONTROLLER MyPLC (\n  PROGRAMS: 1\n)\n";
        String filename = "test.l5k";

        boolean result = manager.saveFileToDevice(device, content, filename);

        assertThat(result).isTrue();

        File storageDir = manager.getStorageDirectory();
        File writtenFile = new File(storageDir, "TestDevice_test.l5k");
        assertThat(writtenFile).exists();
        assertThat(Files.readString(writtenFile.toPath())).isEqualTo(content);
    }

    // ========== reloadDevice() ==========

    @Test
    @DisplayName("reloadDevice() does not call reloadFromFile when getCurrentFilePath returns null")
    void testReloadDeviceSkipsWhenFilePathNull() {
        when(device.getCurrentFilePath()).thenReturn(null);
        // getName() is called by logger.warn when filePath is null/empty
        when(device.getName()).thenReturn("TestDevice");

        manager.reloadDevice(device);

        verify(device, never()).reloadFromFile(any(File.class));
    }

    @Test
    @DisplayName("reloadDevice() does not call reloadFromFile when getCurrentFilePath returns empty string")
    void testReloadDeviceSkipsWhenFilePathEmpty() {
        when(device.getCurrentFilePath()).thenReturn("");
        // getName() is called by logger.warn when filePath is null/empty
        when(device.getName()).thenReturn("TestDevice");

        manager.reloadDevice(device);

        verify(device, never()).reloadFromFile(any(File.class));
    }

    @Test
    @DisplayName("reloadDevice() does not call reloadFromFile when file does not exist on disk")
    void testReloadDeviceSkipsWhenFileDoesNotExist() {
        String nonExistentPath = tempDir.resolve("does-not-exist.l5k").toString();
        when(device.getCurrentFilePath()).thenReturn(nonExistentPath);
        // NOTE: when file doesn't exist, reloadDevice() logs using filePath (not device.getName()),
        // so no getName() stub is needed here

        manager.reloadDevice(device);

        verify(device, never()).reloadFromFile(any(File.class));
    }

    @Test
    @DisplayName("reloadDevice() calls reloadFromFile(File) when file exists on disk")
    void testReloadDeviceCallsReloadWhenFileExists() throws Exception {
        Path actualFile = tempDir.resolve("TestDevice_program.l5k");
        Files.writeString(actualFile, "some content");

        when(device.getCurrentFilePath()).thenReturn(actualFile.toString());
        when(device.getName()).thenReturn("TestDevice");

        manager.reloadDevice(device);

        verify(device).reloadFromFile(eq(actualFile.toFile()));
    }

    // ========== clearDeviceFile() ==========

    @Test
    @DisplayName("clearDeviceFile() calls setCurrentFilePath(null) and clearAndReset()")
    void testClearDeviceFileCallsBothMethods() {
        when(device.getName()).thenReturn("TestDevice");

        manager.clearDeviceFile(device);

        verify(device).setCurrentFilePath(null);
        verify(device).clearAndReset();
    }

    // ========== getVersionManager() — defect B5 ==========

    @Test
    @DisplayName("getVersionManager() returns a FileVersionManager scoped to the device's storage directory and name")
    void testGetVersionManagerScopedToDevice() throws Exception {
        when(device.getName()).thenReturn("TestDevice");

        FileVersionManager versionManager = manager.getVersionManager(device);

        assertThat(versionManager).isNotNull();
        // Behavioural check rather than reflection: a version saved through this instance must
        // land under versions/TestDevice/ inside this manager's storage directory.
        Files.createDirectories(manager.getStorageDirectory().toPath());
        File toVersion = new File(manager.getStorageDirectory(), "TestDevice_program.l5x");
        Files.writeString(toVersion.toPath(), "content");

        assertThat(versionManager.saveVersion(toVersion, "program.l5x")).isTrue();
        File expectedVersionsDir = new File(new File(manager.getStorageDirectory(), "versions"), "TestDevice");
        assertThat(expectedVersionsDir).exists().isDirectory();
        assertThat(expectedVersionsDir.listFiles()).hasSize(1);
    }

    @Test
    @DisplayName("getVersionManager() returns a fresh instance each call (stateless wrapper)")
    void testGetVersionManagerIsStateless() {
        when(device.getName()).thenReturn("TestDevice");

        FileVersionManager first = manager.getVersionManager(device);
        FileVersionManager second = manager.getVersionManager(device);

        assertThat(first).isNotSameAs(second);
    }

    // ========== Constructor / migration ==========

    @Test
    @DisplayName("Constructor does not throw when neither old nor new storage dir exists")
    void testConstructorDoesNotThrowWhenNoDirsExist() {
        // Neither "plc-simulator" nor "logix-emulator" exist in tempDir — normal first-run case
        assertThatCode(() -> new DeviceFileManager(context, registry))
            .doesNotThrowAnyException();
    }
}
