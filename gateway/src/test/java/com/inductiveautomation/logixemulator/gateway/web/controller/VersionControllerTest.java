package com.inductiveautomation.logixemulator.gateway.web.controller;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.logixemulator.gateway.FileVersionManager;
import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorDevice;
import com.inductiveautomation.logixemulator.gateway.web.DeviceFileManager;
import com.inductiveautomation.logixemulator.gateway.web.RateLimiter;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VersionController} — the REST surface that was entirely missing for
 * defect B5 ({@code plc-dod/item7-versioning-FAIL.txt}: "Routes.java exposes NO version/revert
 * REST route at all, so even if snapshots existed there is no user-reachable surface to list
 * them or revert to one").
 */
@ExtendWith(MockitoExtension.class)
class VersionControllerTest {

    @Mock DeviceFileManager deviceManager;
    @Mock RateLimiter readRateLimiter;
    @Mock RateLimiter writeRateLimiter;
    @Mock RequestContext ctx;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse resp;
    @Mock LogixEmulatorDevice device;
    @Mock FileVersionManager versionManager;

    VersionController controller;

    @BeforeEach
    void setUp() {
        controller = new VersionController(deviceManager, readRateLimiter, writeRateLimiter);
        lenient().when(ctx.getRequest()).thenReturn(request);
    }

    private void authenticate() {
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("user")).thenReturn("admin");
    }

    private void allowReadRate() {
        RateLimiter.RateLimitResult allowed = mock(RateLimiter.RateLimitResult.class);
        lenient().when(allowed.isAllowed()).thenReturn(true);
        when(readRateLimiter.checkRequest(any(), any())).thenReturn(allowed);
    }

    private void allowWriteRate() {
        RateLimiter.RateLimitResult allowed = mock(RateLimiter.RateLimitResult.class);
        lenient().when(allowed.isAllowed()).thenReturn(true);
        when(writeRateLimiter.checkRequest(any(), any())).thenReturn(allowed);
    }

    private void passCsrf() {
        when(request.getHeader("X-Requested-With")).thenReturn("XMLHttpRequest");
    }

    // -------------------------------------------------------------------------
    // handleListVersions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("handleListVersions() returns 404 when the device is not found")
    void listVersionsDeviceNotFound() throws Exception {
        authenticate();
        allowReadRate();
        when(ctx.getParameter("name")).thenReturn("MissingPLC");
        when(deviceManager.findDeviceByName("MissingPLC")).thenReturn(Optional.empty());

        JSONObject result = controller.handleListVersions(ctx, resp);

        assertThat(result.getBoolean("success")).isFalse();
        verify(resp).setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    @DisplayName("handleListVersions() returns 429 when the read rate limiter rejects")
    void listVersionsRateLimited() throws Exception {
        authenticate();
        RateLimiter.RateLimitResult rejected = mock(RateLimiter.RateLimitResult.class);
        when(rejected.isAllowed()).thenReturn(false);
        when(readRateLimiter.checkRequest(any(), any())).thenReturn(rejected);

        JSONObject result = controller.handleListVersions(ctx, resp);

        assertThat(result.getBoolean("success")).isFalse();
        verify(resp).setStatus(429);
        verify(deviceManager, never()).findDeviceByName(any());
    }

    @Test
    @DisplayName("handleListVersions() lists the current file plus historical versions, "
        + "newest-first, flagging which one is current")
    void listVersionsReturnsCurrentAndHistory(@TempDir Path tempDir)
            throws Exception {
        authenticate();
        allowReadRate();
        when(ctx.getParameter("name")).thenReturn("DodPLC1");
        when(deviceManager.findDeviceByName("DodPLC1")).thenReturn(Optional.of(device));

        File currentFile = tempDir.resolve("DodPLC1_ver.csv").toFile();
        Files.writeString(currentFile.toPath(), "current content");
        when(deviceManager.getDeviceFilePath(device)).thenReturn(currentFile.getAbsolutePath());
        when(deviceManager.getVersionManager(device)).thenReturn(versionManager);

        File oldVersion = tempDir.resolve("ver_20260101_010000.csv").toFile();
        Files.writeString(oldVersion.toPath(), "old content");
        when(versionManager.getVersions("DodPLC1_ver.csv")).thenReturn(List.of(oldVersion));

        JSONObject result = controller.handleListVersions(ctx, resp);

        assertThat(result.getBoolean("success")).isTrue();
        assertThat(result.getInt("count")).isEqualTo(2);
        assertThat(result.getInt("maxVersions")).isEqualTo(FileVersionManager.getMaxVersions());
        assertThat(result.getJSONArray("versions").getJSONObject(0).getBoolean("current")).isTrue();
        assertThat(result.getJSONArray("versions").getJSONObject(0).getString("filename")).isEqualTo("DodPLC1_ver.csv");
        assertThat(result.getJSONArray("versions").getJSONObject(1).getBoolean("current")).isFalse();
        assertThat(result.getJSONArray("versions").getJSONObject(1).getString("filename")).isEqualTo(oldVersion.getName());
    }

    @Test
    @DisplayName("handleListVersions() returns an empty list when the device has never had a file")
    void listVersionsEmptyWhenNoFile() throws Exception {
        authenticate();
        allowReadRate();
        when(ctx.getParameter("name")).thenReturn("DodPLC1");
        when(deviceManager.findDeviceByName("DodPLC1")).thenReturn(Optional.of(device));
        when(deviceManager.getDeviceFilePath(device)).thenReturn(null);

        JSONObject result = controller.handleListVersions(ctx, resp);

        assertThat(result.getBoolean("success")).isTrue();
        assertThat(result.getInt("count")).isEqualTo(0);
        verify(deviceManager, never()).getVersionManager(any());
    }

    // -------------------------------------------------------------------------
    // handleRevertVersion
    // -------------------------------------------------------------------------

    private void stubRequestBody(String json) throws Exception {
        when(request.getInputStream()).thenReturn(jsonInputStream(json));
    }

    private static ServletInputStream jsonInputStream(String json) {
        ByteArrayInputStream raw = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return raw.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(jakarta.servlet.ReadListener readListener) {
                // not needed for synchronous test reads
            }

            @Override
            public int read() {
                return raw.read();
            }
        };
    }

    @Test
    @DisplayName("handleRevertVersion() returns 404 when the device is not found")
    void revertDeviceNotFound() throws Exception {
        authenticate();
        passCsrf();
        allowWriteRate();
        when(ctx.getParameter("name")).thenReturn("MissingPLC");
        when(deviceManager.findDeviceByName("MissingPLC")).thenReturn(Optional.empty());

        JSONObject result = controller.handleRevertVersion(ctx, resp);

        assertThat(result.getBoolean("success")).isFalse();
        verify(resp).setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    @DisplayName("handleRevertVersion() returns 409 when the device has no current file to revert")
    void revertConflictWhenNoCurrentFile() throws Exception {
        authenticate();
        passCsrf();
        allowWriteRate();
        when(ctx.getParameter("name")).thenReturn("DodPLC1");
        when(deviceManager.findDeviceByName("DodPLC1")).thenReturn(Optional.of(device));
        when(deviceManager.getDeviceFilePath(device)).thenReturn(null);

        JSONObject result = controller.handleRevertVersion(ctx, resp);

        assertThat(result.getBoolean("success")).isFalse();
        verify(resp).setStatus(HttpServletResponse.SC_CONFLICT);
    }

    @Test
    @DisplayName("handleRevertVersion() returns 400 when the filename field is missing")
    void revertBadRequestWhenFilenameMissing(@TempDir Path tempDir)
            throws Exception {
        authenticate();
        passCsrf();
        allowWriteRate();
        when(ctx.getParameter("name")).thenReturn("DodPLC1");
        when(deviceManager.findDeviceByName("DodPLC1")).thenReturn(Optional.of(device));
        File currentFile = tempDir.resolve("DodPLC1_ver.csv").toFile();
        Files.writeString(currentFile.toPath(), "content");
        when(deviceManager.getDeviceFilePath(device)).thenReturn(currentFile.getAbsolutePath());
        stubRequestBody("{}");

        JSONObject result = controller.handleRevertVersion(ctx, resp);

        assertThat(result.getBoolean("success")).isFalse();
        verify(resp).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    @DisplayName("handleRevertVersion() returns 404 for a version filename that doesn't exist "
        + "(defect B5 — dead code with zero callers is now a reachable, honestly-erroring route)")
    void revertUnknownVersionReturns404(@TempDir Path tempDir)
            throws Exception {
        authenticate();
        passCsrf();
        allowWriteRate();
        when(ctx.getParameter("name")).thenReturn("DodPLC1");
        when(deviceManager.findDeviceByName("DodPLC1")).thenReturn(Optional.of(device));
        File currentFile = tempDir.resolve("DodPLC1_ver.csv").toFile();
        Files.writeString(currentFile.toPath(), "content");
        when(deviceManager.getDeviceFilePath(device)).thenReturn(currentFile.getAbsolutePath());
        when(deviceManager.getVersionManager(device)).thenReturn(versionManager);
        when(versionManager.getVersions("DodPLC1_ver.csv")).thenReturn(List.of());
        stubRequestBody("{\"filename\":\"does-not-exist.csv\"}");

        JSONObject result = controller.handleRevertVersion(ctx, resp);

        assertThat(result.getBoolean("success")).isFalse();
        assertThat(result.getString("error")).contains("Unknown version");
        verify(resp).setStatus(HttpServletResponse.SC_NOT_FOUND);
        verify(versionManager, never()).restoreVersion(any(), any());
    }

    @Test
    @DisplayName("handleRevertVersion() restores the version, reloads the device through the "
        + "upload code path, and reports success")
    void revertSuccessRestoresAndReloads(@TempDir Path tempDir)
            throws Exception {
        authenticate();
        passCsrf();
        allowWriteRate();
        when(ctx.getParameter("name")).thenReturn("DodPLC1");
        when(deviceManager.findDeviceByName("DodPLC1")).thenReturn(Optional.of(device));
        File currentFile = tempDir.resolve("DodPLC1_ver.csv").toFile();
        Files.writeString(currentFile.toPath(), "current");
        when(deviceManager.getDeviceFilePath(device)).thenReturn(currentFile.getAbsolutePath());
        when(deviceManager.getVersionManager(device)).thenReturn(versionManager);

        File oldVersion = tempDir.resolve("ver_20260101_010000.csv").toFile();
        Files.writeString(oldVersion.toPath(), "old");
        when(versionManager.getVersions("DodPLC1_ver.csv")).thenReturn(List.of(oldVersion));
        when(versionManager.restoreVersion(oldVersion, currentFile)).thenReturn(true);
        when(device.getStatus()).thenReturn("Running");
        stubRequestBody("{\"filename\":\"" + oldVersion.getName() + "\"}");

        JSONObject result = controller.handleRevertVersion(ctx, resp);

        assertThat(result.getBoolean("success")).isTrue();
        assertThat(result.getString("restoredFrom")).isEqualTo(oldVersion.getName());
        assertThat(result.getString("status")).isEqualTo("Running");
        verify(versionManager).restoreVersion(oldVersion, currentFile);
        verify(deviceManager).reloadDevice(device);
        // The revert itself becomes a new snapshot (see VersionController javadoc).
        verify(versionManager).saveVersion(eq(currentFile), eq("DodPLC1_ver.csv"));
        verify(resp, never()).setStatus(anyInt());
    }

    @Test
    @DisplayName("handleRevertVersion() returns 500 when the restore itself fails to write to disk")
    void revertRestoreFailureReturns500(@TempDir Path tempDir)
            throws Exception {
        authenticate();
        passCsrf();
        allowWriteRate();
        when(ctx.getParameter("name")).thenReturn("DodPLC1");
        when(deviceManager.findDeviceByName("DodPLC1")).thenReturn(Optional.of(device));
        File currentFile = tempDir.resolve("DodPLC1_ver.csv").toFile();
        Files.writeString(currentFile.toPath(), "current");
        when(deviceManager.getDeviceFilePath(device)).thenReturn(currentFile.getAbsolutePath());
        when(deviceManager.getVersionManager(device)).thenReturn(versionManager);

        File oldVersion = tempDir.resolve("ver_20260101_010000.csv").toFile();
        Files.writeString(oldVersion.toPath(), "old");
        when(versionManager.getVersions("DodPLC1_ver.csv")).thenReturn(List.of(oldVersion));
        when(versionManager.restoreVersion(oldVersion, currentFile)).thenReturn(false);
        stubRequestBody("{\"filename\":\"" + oldVersion.getName() + "\"}");

        JSONObject result = controller.handleRevertVersion(ctx, resp);

        assertThat(result.getBoolean("success")).isFalse();
        verify(resp).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        verify(deviceManager, never()).reloadDevice(any());
    }

    @Test
    @DisplayName("handleRevertVersion() reports 422 (not silent success) when the restored file "
        + "fails to parse/build — same B4 honesty convention as processDeviceUpload")
    void revertBuildFailureReturns422(@TempDir Path tempDir)
            throws Exception {
        authenticate();
        passCsrf();
        allowWriteRate();
        when(ctx.getParameter("name")).thenReturn("DodPLC1");
        when(deviceManager.findDeviceByName("DodPLC1")).thenReturn(Optional.of(device));
        File currentFile = tempDir.resolve("DodPLC1_ver.csv").toFile();
        Files.writeString(currentFile.toPath(), "current");
        when(deviceManager.getDeviceFilePath(device)).thenReturn(currentFile.getAbsolutePath());
        when(deviceManager.getVersionManager(device)).thenReturn(versionManager);

        File oldVersion = tempDir.resolve("ver_20260101_010000.csv").toFile();
        Files.writeString(oldVersion.toPath(), "old");
        when(versionManager.getVersions("DodPLC1_ver.csv")).thenReturn(List.of(oldVersion));
        when(versionManager.restoreVersion(oldVersion, currentFile)).thenReturn(true);
        when(device.getStatus()).thenReturn("Error: Hot reload failed - bad file");
        stubRequestBody("{\"filename\":\"" + oldVersion.getName() + "\"}");

        JSONObject result = controller.handleRevertVersion(ctx, resp);

        assertThat(result.getBoolean("success")).isFalse();
        verify(resp).setStatus(422);
        // A build failure must not be re-snapshotted as if it were a good version.
        verify(versionManager, never()).saveVersion(any(), anyString());
    }

    @Test
    @DisplayName("DoD FIX-7: handleRevertVersion() does not echo an absolute filesystem path "
        + "verbatim when the restored file's build failure status carries one")
    void revertBuildFailureSanitisesPathInStatus(@TempDir Path tempDir)
            throws Exception {
        authenticate();
        passCsrf();
        allowWriteRate();
        when(ctx.getParameter("name")).thenReturn("DodPLC1");
        when(deviceManager.findDeviceByName("DodPLC1")).thenReturn(Optional.of(device));
        File currentFile = tempDir.resolve("DodPLC1_ver.csv").toFile();
        Files.writeString(currentFile.toPath(), "current");
        when(deviceManager.getDeviceFilePath(device)).thenReturn(currentFile.getAbsolutePath());
        when(deviceManager.getVersionManager(device)).thenReturn(versionManager);

        File oldVersion = tempDir.resolve("ver_20260101_010000.csv").toFile();
        Files.writeString(oldVersion.toPath(), "old");
        when(versionManager.getVersions("DodPLC1_ver.csv")).thenReturn(List.of(oldVersion));
        when(versionManager.restoreVersion(oldVersion, currentFile)).thenReturn(true);
        when(device.getStatus()).thenReturn(
            "Error: /home/nigel/ignition-data/logix-emulator/DodPLC1_ver.csv (No such file or directory)");
        stubRequestBody("{\"filename\":\"" + oldVersion.getName() + "\"}");

        JSONObject result = controller.handleRevertVersion(ctx, resp);

        assertThat(result.getBoolean("success")).isFalse();
        assertThat(result.getString("error")).doesNotContain("/home/nigel");
        assertThat(result.getString("status")).doesNotContain("/home/nigel");
    }

    @Test
    @DisplayName("handleRevertVersion() returns 429 when the write rate limiter rejects")
    void revertRateLimited() throws Exception {
        authenticate();
        passCsrf();
        RateLimiter.RateLimitResult rejected = mock(RateLimiter.RateLimitResult.class);
        when(rejected.isAllowed()).thenReturn(false);
        when(writeRateLimiter.checkRequest(any(), any())).thenReturn(rejected);

        JSONObject result = controller.handleRevertVersion(ctx, resp);

        assertThat(result.getBoolean("success")).isFalse();
        verify(resp).setStatus(429);
        verify(deviceManager, never()).findDeviceByName(any());
    }

    @Test
    @DisplayName("handleRevertVersion() returns 403 when the CSRF header is missing")
    void revertForbiddenWithoutCsrf() throws Exception {
        authenticate();
        when(request.getHeader("X-Requested-With")).thenReturn(null);
        // GatewayAuthHelper.requireCSRFToken() writes the 403 JSON body itself via
        // resp.getWriter() before returning false — a real writer is needed so that write
        // succeeds and the controller's own catch block (which would double-set the status)
        // is never reached.
        when(resp.getWriter()).thenReturn(new java.io.PrintWriter(new java.io.StringWriter()));

        JSONObject result = controller.handleRevertVersion(ctx, resp);

        // The response was already fully written by requireCSRFToken(); the controller returns
        // null rather than a second JSON body (same convention as requireAuthentication()).
        assertThat(result).isNull();
        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(deviceManager, never()).findDeviceByName(any());
    }
}
