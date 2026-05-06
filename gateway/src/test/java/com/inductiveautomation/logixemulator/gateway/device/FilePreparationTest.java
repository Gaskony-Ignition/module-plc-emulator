package com.inductiveautomation.logixemulator.gateway.device;

import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FilePreparation} — the file-system / parser dispatch
 * collaborator extracted from {@code LogixEmulatorDevice} during the Sprint 3
 * P6 god-class refactor.
 */
@ExtendWith(MockitoExtension.class)
class FilePreparationTest {

    @Mock DeviceContext context;
    @Mock LogixEmulatorConfig config;
    @Mock LogixEmulatorConfig.ParserSettings parserConfig;

    @TempDir Path tempDir;

    FilePreparation prep;

    @BeforeEach
    void setUp() {
        // Most tests don't exercise prepareFile() so mock leniently to keep
        // setUp fast and avoid stubbing-strictness errors.
        lenient().when(context.getName()).thenReturn("MyDevice");
        lenient().when(config.parser()).thenReturn(parserConfig);
        prep = new FilePreparation(context, config);
    }

    @Test
    @DisplayName("constructor rejects null context")
    void constructorRejectsNullContext() {
        assertThatThrownBy(() -> new FilePreparation(null, config))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constructor rejects null config")
    void constructorRejectsNullConfig() {
        assertThatThrownBy(() -> new FilePreparation(context, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // sanitizeFileName
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sanitizeFileName() returns default for null input")
    void sanitizeNullReturnsDefault() {
        assertThat(prep.sanitizeFileName(null)).isEqualTo("uploaded-MyDevice.L5K");
    }

    @Test
    @DisplayName("sanitizeFileName() returns default for empty input")
    void sanitizeEmptyReturnsDefault() {
        assertThat(prep.sanitizeFileName("")).isEqualTo("uploaded-MyDevice.L5K");
        assertThat(prep.sanitizeFileName("   ")).isEqualTo("uploaded-MyDevice.L5K");
    }

    @Test
    @DisplayName("sanitizeFileName() returns the sanitised name for a valid input")
    void sanitizeValidName() {
        assertThat(prep.sanitizeFileName("program.l5k")).isEqualTo("program.l5k");
    }

    @Test
    @DisplayName("sanitizeFileName() falls back to default when PathSecurity rejects the name")
    void sanitizePathTraversalRejected() {
        // Path traversal triggers SecurityException inside PathSecurity which
        // FilePreparation catches and falls back to the default name.
        assertThat(prep.sanitizeFileName("../../etc/passwd")).isEqualTo("uploaded-MyDevice.L5K");
    }

    // -------------------------------------------------------------------------
    // findExistingFileForDevice
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findExistingFileForDevice() returns null for null directory")
    void findInNullDirectoryReturnsNull() {
        assertThat(prep.findExistingFileForDevice(null)).isNull();
    }

    @Test
    @DisplayName("findExistingFileForDevice() returns null for non-existent directory")
    void findInMissingDirectoryReturnsNull() {
        assertThat(prep.findExistingFileForDevice(new File("/this/path/does/not/exist"))).isNull();
    }

    @Test
    @DisplayName("findExistingFileForDevice() returns null for empty directory")
    void findInEmptyDirectoryReturnsNull() {
        assertThat(prep.findExistingFileForDevice(tempDir.toFile())).isNull();
    }

    @Test
    @DisplayName("findExistingFileForDevice() finds a device-prefixed L5K file")
    void findsDevicePrefixedL5K() throws Exception {
        Path file = tempDir.resolve("MyDevice_program.L5K");
        Files.writeString(file, "dummy");

        File found = prep.findExistingFileForDevice(tempDir.toFile());

        assertThat(found).isNotNull();
        assertThat(found.getName()).isEqualTo("MyDevice_program.L5K");
    }

    @Test
    @DisplayName("findExistingFileForDevice() ignores hidden, backup, and tilde files")
    void ignoresHiddenAndBackupFiles() throws Exception {
        Files.writeString(tempDir.resolve(".MyDevice_program.l5x"), "x");
        Files.writeString(tempDir.resolve("MyDevice_program.bak"), "x");
        Files.writeString(tempDir.resolve("MyDevice_~program.l5x"), "x");

        assertThat(prep.findExistingFileForDevice(tempDir.toFile())).isNull();
    }

    @Test
    @DisplayName("findExistingFileForDevice() ignores files belonging to other devices")
    void ignoresOtherDeviceFiles() throws Exception {
        Files.writeString(tempDir.resolve("OtherDevice_program.l5k"), "x");

        assertThat(prep.findExistingFileForDevice(tempDir.toFile())).isNull();
    }

    @Test
    @DisplayName("findExistingFileForDevice() requires the underscore separator after the device name")
    void requiresUnderscoreSeparator() throws Exception {
        Files.writeString(tempDir.resolve("MyDevice2_program.l5k"), "x");

        assertThat(prep.findExistingFileForDevice(tempDir.toFile())).isNull();
    }

    @Test
    @DisplayName("findExistingFileForDevice() recognises every supported extension")
    void allExtensionsRecognised() throws Exception {
        for (String ext : new String[] { ".L5K", ".l5k", ".L5X", ".l5x", ".json", ".csv" }) {
            // Each iteration creates a single-file directory in a sub-folder.
            Path subdir = tempDir.resolve(ext.substring(1));
            Files.createDirectories(subdir);
            Files.writeString(subdir.resolve("MyDevice_x" + ext), "x");

            File found = prep.findExistingFileForDevice(subdir.toFile());

            assertThat(found).as("extension %s", ext).isNotNull();
        }
    }

    // -------------------------------------------------------------------------
    // createDemoStructure
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createDemoStructure() returns a JSON object with exactly three demo tags")
    void createDemoStructureShape() {
        JsonObject demo = prep.createDemoStructure();

        assertThat(demo).isNotNull();
        assertThat(demo.has("global_tags")).isTrue();
        assertThat(demo.getAsJsonArray("global_tags")).hasSize(3);
    }

    @Test
    @DisplayName("createDemoStructure() includes a STRING status indicator")
    void demoIncludesStatusIndicator() {
        JsonObject demo = prep.createDemoStructure();

        boolean hasStatus = demo.getAsJsonArray("global_tags").asList().stream()
            .map(e -> e.getAsJsonObject().get("name").getAsString())
            .anyMatch("DemoStatus"::equals);

        assertThat(hasStatus).isTrue();
    }

    // -------------------------------------------------------------------------
    // parseFile / parseFileBuiltIn
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("parseFile() returns demo structure when the file does not exist")
    void parseMissingFileReturnsDemo() {
        when(parserConfig.parserType()).thenReturn(LogixEmulatorConfig.ParserType.JSON);

        JsonObject result = prep.parseFile("/no/such/file.json");

        // Parser never reached → built-in falls back to demo.
        assertThat(result).isNotNull();
        assertThat(result.has("global_tags")).isTrue();
    }

    @Test
    @DisplayName("getVersionManager() returns null until prepareFile() runs")
    void versionManagerInitialNull() {
        assertThat(prep.getVersionManager()).isNull();
    }
}
