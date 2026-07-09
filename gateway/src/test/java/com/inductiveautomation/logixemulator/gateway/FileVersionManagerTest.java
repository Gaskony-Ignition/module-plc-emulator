package com.inductiveautomation.logixemulator.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FileVersionManager} (defect B5).
 *
 * <p>Previously this class had zero test coverage despite implementing the charter's "retain
 * last 5 uploads and can revert" requirement (§2.7) — nothing on the REST surface called
 * {@code saveVersion}/{@code getVersions}/{@code restoreVersion}, so nothing exercised them
 * either (see {@code plc-dod/item7-versioning-FAIL.txt}). It is now wired into the REST upload
 * path via {@code DeviceFileManager.getVersionManager} and
 * {@code DeviceController.processDeviceUpload}, and into revert via
 * {@code VersionController.handleRevertVersion}.</p>
 */
class FileVersionManagerTest {

    @TempDir Path tempDir;

    FileVersionManager manager;
    File sourceFile;

    @BeforeEach
    void setUp() throws IOException {
        manager = new FileVersionManager(tempDir.toFile(), "TestDevice");
        sourceFile = tempDir.resolve("TestDevice_program.l5x").toFile();
        Files.writeString(sourceFile.toPath(), "v1 content");
    }

    // -------------------------------------------------------------------------
    // saveVersion
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("saveVersion() creates the versions/<device>/ directory and copies the file")
    void saveVersionCreatesVersionsDirectory() {
        boolean result = manager.saveVersion(sourceFile, "program.l5x");

        assertThat(result).isTrue();
        File versionsDir = versionsDirFor("TestDevice");
        assertThat(versionsDir).exists().isDirectory();
        assertThat(versionsDir.listFiles()).hasSize(1);
    }

    @Test
    @DisplayName("saveVersion() returns false for a non-existent source file")
    void saveVersionRejectsMissingSource() {
        File missing = new File(tempDir.toFile(), "does-not-exist.l5x");
        assertThat(manager.saveVersion(missing, "program.l5x")).isFalse();
    }

    @Test
    @DisplayName("saveVersion() prunes to the last 5 versions when a 6th is saved (charter retention)")
    void saveVersionPrunesBeyondFiveVersions() throws IOException {
        File versionsDir = versionsDirFor("TestDevice");
        Files.createDirectories(versionsDir.toPath());

        // Pre-populate 5 versions with controlled, well-separated modification times so ordering
        // is deterministic without depending on real-clock/filesystem mtime resolution.
        for (int i = 1; i <= 5; i++) {
            writeVersionFile(versionsDir, String.format("program_2026010%d_010000.l5x", i), "v" + i, i);
        }
        assertThat(manager.getVersionCount("program.l5x")).isEqualTo(5);

        // The 6th save (the live file, always newer on disk than the pre-populated ones) should
        // push the count to 6 momentarily, then prune back down to the retention limit of 5.
        boolean result = manager.saveVersion(sourceFile, "program.l5x");

        assertThat(result).isTrue();
        assertThat(manager.getVersionCount("program.l5x")).isEqualTo(FileVersionManager.getMaxVersions());
    }

    // -------------------------------------------------------------------------
    // getVersions / getMostRecentVersion
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getVersions() returns an empty list when no versions directory exists")
    void getVersionsEmptyWhenNoneSaved() {
        assertThat(manager.getVersions("program.l5x")).isEmpty();
    }

    @Test
    @DisplayName("getVersions() returns saved versions newest-first")
    void getVersionsOrdersNewestFirst() throws IOException {
        File versionsDir = versionsDirFor("TestDevice");
        Files.createDirectories(versionsDir.toPath());

        File older = writeVersionFile(versionsDir, "program_20260101_010000.l5x", "old", 1);
        File newer = writeVersionFile(versionsDir, "program_20260102_010000.l5x", "new", 2);

        List<File> versions = manager.getVersions("program.l5x");

        assertThat(versions).containsExactly(newer, older);
    }

    @Test
    @DisplayName("getMostRecentVersion() returns null when no versions exist, else the newest")
    void getMostRecentVersionReturnsNewest() throws IOException {
        assertThat(manager.getMostRecentVersion("program.l5x")).isNull();

        File versionsDir = versionsDirFor("TestDevice");
        Files.createDirectories(versionsDir.toPath());
        writeVersionFile(versionsDir, "program_20260101_010000.l5x", "old", 1);
        File newer = writeVersionFile(versionsDir, "program_20260102_010000.l5x", "new", 2);

        assertThat(manager.getMostRecentVersion("program.l5x")).isEqualTo(newer);
    }

    // -------------------------------------------------------------------------
    // restoreVersion
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("restoreVersion() copies the version's content back onto the target file")
    void restoreVersionCopiesContentBack() throws IOException {
        File versionsDir = versionsDirFor("TestDevice");
        Files.createDirectories(versionsDir.toPath());
        File version = writeVersionFile(versionsDir, "program_20260101_010000.l5x", "restored content", 1);

        boolean result = manager.restoreVersion(version, sourceFile);

        assertThat(result).isTrue();
        assertThat(Files.readString(sourceFile.toPath())).isEqualTo("restored content");
    }

    @Test
    @DisplayName("restoreVersion() returns false when the version file does not exist")
    void restoreVersionRejectsMissingVersion() {
        File missing = new File(tempDir.toFile(), "does-not-exist.l5x");
        assertThat(manager.restoreVersion(missing, sourceFile)).isFalse();
    }

    // -------------------------------------------------------------------------
    // Misc
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getMaxVersions() is the charter-mandated retention of 5 (§2.7)")
    void maxVersionsIsFive() {
        assertThat(FileVersionManager.getMaxVersions()).isEqualTo(5);
    }

    @Test
    @DisplayName("deleteAllVersions() removes the device's versions directory")
    void deleteAllVersionsRemovesDirectory() throws IOException {
        File versionsDir = versionsDirFor("TestDevice");
        Files.createDirectories(versionsDir.toPath());
        writeVersionFile(versionsDir, "program_20260101_010000.l5x", "v1", 1);

        manager.deleteAllVersions();

        assertThat(versionsDir).doesNotExist();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private File versionsDirFor(String deviceName) {
        return new File(new File(tempDir.toFile(), "versions"), deviceName);
    }

    /**
     * Writes a version file with a modification time spread {@code daysAgoOffset} apart from
     * "now" so newest-first ordering assertions never depend on real-clock or filesystem mtime
     * resolution (the production code's own second-resolution timestamp suffix would otherwise
     * make consecutive same-second saves in a fast test run indistinguishable).
     */
    private File writeVersionFile(File dir, String name, String content, int daysAgoOffset) throws IOException {
        File file = new File(dir, name);
        Files.writeString(file.toPath(), content);
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(
            System.currentTimeMillis() - (10L - daysAgoOffset) * 24 * 60 * 60 * 1000));
        return file;
    }
}
