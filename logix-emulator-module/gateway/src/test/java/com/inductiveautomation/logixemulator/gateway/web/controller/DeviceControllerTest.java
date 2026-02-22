package com.inductiveautomation.logixemulator.gateway.web.controller;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.logixemulator.gateway.DeviceRegistry;
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
}
