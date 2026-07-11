package com.inductiveautomation.logixemulator.gateway.device;

import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.logixemulator.gateway.FileVersionManager;
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
import java.nio.file.attribute.FileTime;

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
    @DisplayName("findExistingFileForDevice() picks the most recently modified file when several "
        + "match (defect B5 — previously the first match from an unordered listFiles() won, so "
        + "a Gateway restart could load a stale or arbitrary file instead of the latest upload)")
    void findsMostRecentlyModifiedAmongMultipleMatches() throws Exception {
        File older = tempDir.resolve("MyDevice_old.l5x").toFile();
        File newer = tempDir.resolve("MyDevice_new.l5x").toFile();
        Files.writeString(older.toPath(), "old");
        Files.writeString(newer.toPath(), "new");
        Files.setLastModifiedTime(older.toPath(), FileTime.fromMillis(System.currentTimeMillis() - 100_000));
        Files.setLastModifiedTime(newer.toPath(), FileTime.fromMillis(System.currentTimeMillis()));

        File found = prep.findExistingFileForDevice(tempDir.toFile());

        assertThat(found).isEqualTo(newer);
    }

    @Test
    @DisplayName("findExistingFileForDevice() prunes matching files beyond the retention limit, "
        + "keeping only the most recent ones (charter §2.7 — files were previously never pruned "
        + "at all, see plc-dod/item7-versioning-FAIL.txt side note)")
    void prunesStaleFilesBeyondRetentionLimit() throws Exception {
        int maxRetained = FileVersionManager.getMaxVersions();
        File[] files = new File[maxRetained + 1];
        for (int i = 0; i < files.length; i++) {
            files[i] = tempDir.resolve("MyDevice_v" + i + ".l5x").toFile();
            Files.writeString(files[i].toPath(), "v" + i);
            // Spread mtimes so ordering is deterministic: v0 is oldest, last index is newest.
            Files.setLastModifiedTime(files[i].toPath(),
                FileTime.fromMillis(System.currentTimeMillis() - (files.length - i) * 100_000L));
        }

        File found = prep.findExistingFileForDevice(tempDir.toFile());

        assertThat(found).isEqualTo(files[files.length - 1]);
        assertThat(tempDir.toFile().listFiles()).hasSize(maxRetained);
        assertThat(files[0]).doesNotExist(); // the oldest one was pruned
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
    @DisplayName("DoD FIX-1: parseFileBuiltIn() returns null (not a demo structure) for an "
        + "existing but malformed/garbage L5X file, so callers can honestly report a build "
        + "failure instead of HTTP 200 success:true on a corrupt upload "
        + "(plc-dod2/item6-verify.txt)")
    void parseGarbageL5xReturnsNullNotDemo() throws Exception {
        Path garbage = tempDir.resolve("garbage.l5x");
        // Well-formed-enough-to-not-crash-the-JVM but semantically not an RSLogix5000Content
        // export — the real L5XParser reads it as valid XML with no recognisable tag data,
        // so it currently returns null rather than throwing.
        Files.writeString(garbage, "<NotRSLogix5000Content><garbage/></NotRSLogix5000Content>");

        JsonObject result = prep.parseFileBuiltIn(
            garbage.toString(), LogixEmulatorConfig.ParserType.ROCKWELL);

        assertThat(result)
            .as("a genuinely unparseable existing file must propagate null, never demo tags")
            .isNull();
    }

    @Test
    @DisplayName("DoD FIX-1: parseFileBuiltIn() returns null for an XXE-bearing L5X upload "
        + "(the DOCTYPE rejection inside L5XParser must reach the caller as a real failure, "
        + "not be masked by a demo-tag fallback)")
    void parseXxeL5xReturnsNullNotDemo() throws Exception {
        String xxeContent = Files.readString(
            Path.of("src/test/resources/test-files/xxe-big.l5x"));
        Path xxeFile = tempDir.resolve("xxe-big.l5x");
        Files.writeString(xxeFile, xxeContent);

        JsonObject result = prep.parseFileBuiltIn(
            xxeFile.toString(), LogixEmulatorConfig.ParserType.ROCKWELL);

        assertThat(result)
            .as("an XXE-rejected file must propagate null, never demo tags")
            .isNull();
    }

    @Test
    @DisplayName("DoD FIX-6: parseFileBuiltIn() returns null (not a demo structure) for a "
        + "garbage .l5k upload with no recognisable TAG/PROGRAM sections, so the upload path "
        + "reports an honest 4xx naming the L5K parser rather than HTTP 200 success:true")
    void parseGarbageL5kReturnsNullNotDemo() throws Exception {
        Path garbage = tempDir.resolve("garbage.l5k");
        Files.writeString(garbage, "this is not an L5K export at all, just some prose\n");

        JsonObject result = prep.parseFileBuiltIn(
            garbage.toString(), LogixEmulatorConfig.ParserType.ROCKWELL);

        assertThat(result)
            .as("a garbage .l5k file must not be silently replaced with an L5K_ParseError demo tag")
            .isNull();
    }

    @Test
    @DisplayName("getVersionManager() returns null until prepareFile() runs")
    void versionManagerInitialNull() {
        assertThat(prep.getVersionManager()).isNull();
    }
}
