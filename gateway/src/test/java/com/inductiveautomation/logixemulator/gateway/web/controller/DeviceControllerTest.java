package com.inductiveautomation.logixemulator.gateway.web.controller;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.logixemulator.gateway.DeviceRegistry;
import com.inductiveautomation.logixemulator.gateway.FileVersionManager;
import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorConfig;
import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorDevice;
import com.inductiveautomation.logixemulator.gateway.web.DeviceFileManager;
import com.inductiveautomation.logixemulator.gateway.web.RateLimiter;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DeviceController.
 */
@ExtendWith(MockitoExtension.class)
class DeviceControllerTest {

    @Mock DeviceFileManager deviceManager;
    @Mock DeviceRegistry registry;
    @Mock RateLimiter rateLimiter;
    @Mock RateLimiter writeRateLimiter;
    @Mock RequestContext ctx;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse resp;

    DeviceController controller;

    @BeforeEach
    void setUp() {
        controller = new DeviceController(deviceManager, registry, rateLimiter, writeRateLimiter);
        // lenient: some tests (validateDeviceName) don't route through ctx.getRequest()
        lenient().when(ctx.getRequest()).thenReturn(request);
    }

    // -------------------------------------------------------------------------
    // validateDeviceName
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("validateDeviceName() returns null for a valid device name")
    void testValidateDeviceNameValid() throws Exception {
        assertThat(controller.validateDeviceName("MyPLC-1", resp)).isNull();
    }

    @Test
    @DisplayName("validateDeviceName() rejects null and sets 400")
    void testValidateDeviceNameNull() throws Exception {
        JSONObject error = controller.validateDeviceName(null, resp);
        assertThat(error).isNotNull();
        assertThat(error.getString("error")).contains("required");
        verify(resp).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    @DisplayName("validateDeviceName() rejects path traversal (../evil)")
    void testValidateDeviceNamePathTraversal() throws Exception {
        JSONObject error = controller.validateDeviceName("../evil", resp);
        assertThat(error).isNotNull();
        assertThat(error.getString("error")).contains("Invalid");
        verify(resp).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    @DisplayName("validateDeviceName() rejects names over 100 characters")
    void testValidateDeviceNameTooLong() throws Exception {
        String tooLong = "a".repeat(101);
        JSONObject error = controller.validateDeviceName(tooLong, resp);
        assertThat(error).isNotNull();
        verify(resp).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    @DisplayName("validateDeviceName() rejects empty string")
    void testValidateDeviceNameEmpty() throws Exception {
        JSONObject error = controller.validateDeviceName("", resp);
        assertThat(error).isNotNull();
        verify(resp).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    // -------------------------------------------------------------------------
    // handleListDevices
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("handleListDevices() returns success with all registered devices")
    void testHandleListDevicesReturnsAllDevices() throws Exception {
        // Authenticate via session
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("user")).thenReturn("admin");

        // Set up a mock device
        LogixEmulatorDevice device = mock(LogixEmulatorDevice.class);
        LogixEmulatorConfig config = mock(LogixEmulatorConfig.class);
        LogixEmulatorConfig.General general = mock(LogixEmulatorConfig.General.class);
        LogixEmulatorConfig.ParserSettings parser = mock(LogixEmulatorConfig.ParserSettings.class);
        LogixEmulatorConfig.SimulationSettings simulation = mock(LogixEmulatorConfig.SimulationSettings.class);

        when(device.getName()).thenReturn("PLC1");
        when(device.getStatus()).thenReturn("Connected");
        when(device.getConfiguration()).thenReturn(config);
        when(config.general()).thenReturn(general);
        when(config.parser()).thenReturn(parser);
        when(config.simulation()).thenReturn(simulation);
        when(general.enabled()).thenReturn(true);
        when(parser.fileName()).thenReturn("program.l5k");
        when(parser.parserType()).thenReturn(LogixEmulatorConfig.ParserType.ROCKWELL);
        when(simulation.enabled()).thenReturn(false);

        when(registry.getRegisteredDevices()).thenReturn(List.of(device));

        JSONObject result = controller.handleListDevices(ctx, resp);

        assertThat(result).isNotNull();
        assertThat(result.getBoolean("success")).isTrue();
        assertThat(result.getJSONArray("devices").length()).isEqualTo(1);
        assertThat(result.getInt("count")).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // handleDeviceStatus — device not found
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("handleDeviceStatus() returns 404 JSON when device is not found")
    void testHandleDeviceStatusNotFound() throws Exception {
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("user")).thenReturn("admin");
        when(ctx.getParameter("name")).thenReturn("MissingPLC");
        when(deviceManager.findDeviceByName("MissingPLC")).thenReturn(Optional.empty());

        JSONObject result = controller.handleDeviceStatus(ctx, resp);

        assertThat(result).isNotNull();
        assertThat(result.getBoolean("success")).isFalse();
        assertThat(result.getString("error")).contains("not found");
        verify(resp).setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    // -------------------------------------------------------------------------
    // handleDeleteFile — rate-limit rejection
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("handleDeleteFile() returns 429 when write rate-limiter rejects")
    void testHandleDeleteFileRateLimited() throws Exception {
        // Authenticate
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("user")).thenReturn("admin");
        // Pass CSRF check
        when(request.getHeader("X-Requested-With")).thenReturn("XMLHttpRequest");
        // Rate-limit — only isAllowed() and checkRequest() are consulted by the inline 429 path
        var rateLimitResult = mock(RateLimiter.RateLimitResult.class);
        when(rateLimitResult.isAllowed()).thenReturn(false);
        when(request.getRemoteUser()).thenReturn("admin");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(writeRateLimiter.checkRequest(any(), any())).thenReturn(rateLimitResult);

        JSONObject result = controller.handleDeleteFile(ctx, resp);

        verify(resp).setStatus(429);
        assertThat(result).isNotNull();
        assertThat(result.getBoolean("success")).isFalse();
    }

    // -------------------------------------------------------------------------
    // processDeviceUpload — defect B4: honest success/failure reporting
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("isBuildFailureStatus() recognises the 'Error' status prefixes the hot-reload "
        + "pipeline actually produces")
    void testIsBuildFailureStatusRecognisesErrorPrefix() {
        assertThat(DeviceController.isBuildFailureStatus("Running")).isFalse();
        assertThat(DeviceController.isBuildFailureStatus("Reloading")).isFalse();
        assertThat(DeviceController.isBuildFailureStatus(null)).isFalse();
        assertThat(DeviceController.isBuildFailureStatus(
            "Error: Hot reload failed - For input string: \"{structure}\"")).isTrue();
        assertThat(DeviceController.isBuildFailureStatus("Error: Failed to parse file after reload")).isTrue();
    }

    @Test
    @DisplayName("processDeviceUpload() reports failure (non-2xx, success=false) when the file "
        + "saves but the reload leaves the device in an error status (defect B4 regression test - "
        + "previously this returned HTTP 200 success=true, see plc-dod/item2-upload-real.txt)")
    void testProcessDeviceUploadReportsFailureWhenBuildFails() throws Exception {
        LogixEmulatorDevice device = mock(LogixEmulatorDevice.class);
        when(deviceManager.findDeviceByName("DodPLC1")).thenReturn(Optional.of(device));
        when(deviceManager.saveFileToDevice(eq(device), anyString(), anyString())).thenReturn(true);
        when(device.getStatus()).thenReturn("Error: Hot reload failed - For input string: \"{structure}\"");

        JSONObject result = controller.processDeviceUpload(
            resp, new JSONObject(), "DodPLC1", "CONTROLLER DodPLC1 (ProcessorType := \"1756-L83E\")", "real-world.l5k");

        verify(resp).setStatus(422);
        verify(deviceManager).reloadDevice(device);
        assertThat(result.getBoolean("success")).isFalse();
        assertThat(result.getString("error")).contains("Error: Hot reload failed");
        assertThat(result.getString("status")).isEqualTo("Error: Hot reload failed - For input string: \"{structure}\"");
        assertThat(result.getString("device")).isEqualTo("DodPLC1");
        assertThat(result.getString("filename")).isEqualTo("real-world.l5k");
    }

    @Test
    @DisplayName("DoD FIX-7: processDeviceUpload() does not echo an absolute filesystem path "
        + "verbatim when the device status carries one from a raw exception message")
    void testProcessDeviceUploadSanitisesPathInStatus() throws Exception {
        LogixEmulatorDevice device = mock(LogixEmulatorDevice.class);
        when(deviceManager.findDeviceByName("DodPLC1")).thenReturn(Optional.of(device));
        when(deviceManager.saveFileToDevice(eq(device), anyString(), anyString())).thenReturn(true);
        when(device.getStatus()).thenReturn(
            "Error: /home/nigel/ignition-data/logix-emulator/DodPLC1.l5k (No such file or directory)");

        JSONObject result = controller.processDeviceUpload(
            resp, new JSONObject(), "DodPLC1", "CONTROLLER DodPLC1 (ProcessorType := \"1756-L83E\")", "real-world.l5k");

        verify(resp).setStatus(422);
        assertThat(result.getBoolean("success")).isFalse();
        assertThat(result.getString("error"))
            .as("the absolute path must not be echoed verbatim in the error field")
            .doesNotContain("/home/nigel");
        assertThat(result.getString("status"))
            .as("the absolute path must not be echoed verbatim in the status field")
            .doesNotContain("/home/nigel");
    }

    @Test
    @DisplayName("processDeviceUpload() reports success only when the device actually reaches a "
        + "non-error status after reload")
    void testProcessDeviceUploadReportsSuccessWhenBuildSucceeds() throws Exception {
        LogixEmulatorDevice device = mock(LogixEmulatorDevice.class);
        when(deviceManager.findDeviceByName("DodPLC1")).thenReturn(Optional.of(device));
        when(deviceManager.saveFileToDevice(eq(device), anyString(), anyString())).thenReturn(true);
        when(device.getStatus()).thenReturn("Running");

        JSONObject result = controller.processDeviceUpload(
            resp, new JSONObject(), "DodPLC1", "TagName,DataType\nTag1,DINT\n", "tags.csv");

        verify(resp, never()).setStatus(anyInt());
        verify(deviceManager).reloadDevice(device);
        assertThat(result.getBoolean("success")).isTrue();
        assertThat(result.getString("status")).isEqualTo("Running");
        assertThat(result.getString("device")).isEqualTo("DodPLC1");
    }

    @Test
    @DisplayName("processDeviceUpload() returns 500 when the file itself cannot be saved to disk")
    void testProcessDeviceUploadReportsFailureWhenSaveFails() throws Exception {
        LogixEmulatorDevice device = mock(LogixEmulatorDevice.class);
        when(deviceManager.findDeviceByName("DodPLC1")).thenReturn(Optional.of(device));
        when(deviceManager.saveFileToDevice(eq(device), anyString(), anyString())).thenReturn(false);

        JSONObject result = controller.processDeviceUpload(
            resp, new JSONObject(), "DodPLC1", "content", "tags.csv");

        verify(resp).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        verify(deviceManager, never()).reloadDevice(any());
        assertThat(result.getBoolean("success")).isFalse();
    }

    // -------------------------------------------------------------------------
    // processDeviceUpload — defect B5: version wiring
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("processDeviceUpload() saves a file version only after a successful reload "
        + "(defect B5 — saveVersion was never called on the REST upload path at all)")
    void testProcessDeviceUploadSavesVersionOnSuccess() throws Exception {
        LogixEmulatorDevice device = mock(LogixEmulatorDevice.class);
        FileVersionManager versionManager = mock(FileVersionManager.class);
        when(deviceManager.findDeviceByName("DodPLC1")).thenReturn(Optional.of(device));
        when(deviceManager.saveFileToDevice(eq(device), anyString(), anyString())).thenReturn(true);
        when(device.getStatus()).thenReturn("Running");
        when(deviceManager.getDeviceFilePath(device)).thenReturn("/data/logix-emulator/DodPLC1_tags.csv");
        when(deviceManager.getVersionManager(device)).thenReturn(versionManager);

        JSONObject result = controller.processDeviceUpload(
            resp, new JSONObject(), "DodPLC1", "TagName,DataType\nTag1,DINT\n", "tags.csv");

        assertThat(result.getBoolean("success")).isTrue();
        verify(versionManager).saveVersion(
            argThat(f -> f.getPath().equals("/data/logix-emulator/DodPLC1_tags.csv")),
            eq("DodPLC1_tags.csv"));
    }

    @Test
    @DisplayName("processDeviceUpload() does NOT save a file version when the reload leaves the "
        + "device in an error status — a failed upload must not consume a retention slot")
    void testProcessDeviceUploadDoesNotSaveVersionOnBuildFailure() throws Exception {
        LogixEmulatorDevice device = mock(LogixEmulatorDevice.class);
        when(deviceManager.findDeviceByName("DodPLC1")).thenReturn(Optional.of(device));
        when(deviceManager.saveFileToDevice(eq(device), anyString(), anyString())).thenReturn(true);
        when(device.getStatus()).thenReturn("Error: Hot reload failed - bad file");

        controller.processDeviceUpload(
            resp, new JSONObject(), "DodPLC1", "<bad/>", "real-world.l5k");

        verify(deviceManager, never()).getVersionManager(any());
    }

    @Test
    @DisplayName("processDeviceUpload() skips versioning gracefully when the device has no "
        + "current file path (defensive guard, should not happen in practice post-save)")
    void testProcessDeviceUploadSkipsVersioningWhenNoFilePath() throws Exception {
        LogixEmulatorDevice device = mock(LogixEmulatorDevice.class);
        when(deviceManager.findDeviceByName("DodPLC1")).thenReturn(Optional.of(device));
        when(deviceManager.saveFileToDevice(eq(device), anyString(), anyString())).thenReturn(true);
        when(device.getStatus()).thenReturn("Running");
        when(deviceManager.getDeviceFilePath(device)).thenReturn(null);

        JSONObject result = controller.processDeviceUpload(
            resp, new JSONObject(), "DodPLC1", "content", "tags.csv");

        assertThat(result.getBoolean("success")).isTrue();
        verify(deviceManager, never()).getVersionManager(any());
    }
}
