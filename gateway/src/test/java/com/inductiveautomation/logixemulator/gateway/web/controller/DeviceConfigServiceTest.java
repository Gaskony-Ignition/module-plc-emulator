package com.inductiveautomation.logixemulator.gateway.web.controller;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link DeviceConfigService#validateDeviceName}. The same
 * validation logic was historically duplicated across three REST controllers;
 * after the Sprint 3 P6 refactor each controller delegates here, so this is
 * the canonical home for these assertions.
 *
 * <p>The duplicated controller-level tests in {@code DeviceControllerTest}
 * are retained because they cover the controller method's exposed signature.
 * These tests cover the service directly (including the
 * {@link #devicNameRegexAllowsSpaces} edge cases that the controller tests
 * never exercised in either of the previous duplicates).</p>
 */
@ExtendWith(MockitoExtension.class)
class DeviceConfigServiceTest {

    @Mock HttpServletResponse resp;

    @Test
    @DisplayName("validateDeviceName() returns null for a valid alphanumeric name")
    void validNameReturnsNull() throws Exception {
        assertThat(DeviceConfigService.validateDeviceName("MyPLC1", resp)).isNull();
        verify(resp, never()).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    @DisplayName("validateDeviceName() accepts hyphens and underscores")
    void allowsHyphensAndUnderscores() throws Exception {
        assertThat(DeviceConfigService.validateDeviceName("My-PLC_1", resp)).isNull();
    }

    @Test
    @DisplayName("validateDeviceName() accepts spaces (legacy rule)")
    void devicNameRegexAllowsSpaces() throws Exception {
        assertThat(DeviceConfigService.validateDeviceName("My PLC 1", resp)).isNull();
    }

    @Test
    @DisplayName("validateDeviceName() rejects null and sets 400")
    void nullRejected() throws Exception {
        JSONObject error = DeviceConfigService.validateDeviceName(null, resp);
        assertThat(error).isNotNull();
        assertThat(error.getBoolean("success")).isFalse();
        assertThat(error.getString("error")).contains("required");
        verify(resp).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    @DisplayName("validateDeviceName() rejects empty string and sets 400")
    void emptyRejected() throws Exception {
        JSONObject error = DeviceConfigService.validateDeviceName("", resp);
        assertThat(error).isNotNull();
        verify(resp).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    @DisplayName("validateDeviceName() rejects whitespace-only and sets 400")
    void whitespaceOnlyRejected() throws Exception {
        JSONObject error = DeviceConfigService.validateDeviceName("   ", resp);
        assertThat(error).isNotNull();
        assertThat(error.getString("error")).contains("required");
        verify(resp).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    @DisplayName("validateDeviceName() rejects path traversal (../evil)")
    void pathTraversalRejected() throws Exception {
        JSONObject error = DeviceConfigService.validateDeviceName("../evil", resp);
        assertThat(error).isNotNull();
        assertThat(error.getString("error")).contains("Invalid");
        verify(resp).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    @DisplayName("validateDeviceName() rejects shell metacharacters")
    void shellMetacharsRejected() throws Exception {
        JSONObject error = DeviceConfigService.validateDeviceName("foo;rm -rf /", resp);
        assertThat(error).isNotNull();
        verify(resp).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    @DisplayName("validateDeviceName() rejects names exceeding the 100-char limit")
    void overlyLongRejected() throws Exception {
        String tooLong = "a".repeat(DeviceConfigService.MAX_DEVICE_NAME_LENGTH + 1);
        JSONObject error = DeviceConfigService.validateDeviceName(tooLong, resp);
        assertThat(error).isNotNull();
        verify(resp).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    @DisplayName("validateDeviceName() accepts names exactly at the 100-char limit")
    void atLimitAccepted() throws Exception {
        String atLimit = "a".repeat(DeviceConfigService.MAX_DEVICE_NAME_LENGTH);
        assertThat(DeviceConfigService.validateDeviceName(atLimit, resp)).isNull();
    }

    @Test
    @DisplayName("DEVICE_NAME_REGEX is a publicly inspectable constant")
    void regexExposed() {
        assertThat(DeviceConfigService.DEVICE_NAME_REGEX).contains("a-zA-Z0-9");
        assertThat(DeviceConfigService.MAX_DEVICE_NAME_LENGTH).isEqualTo(100);
    }

    // -------------------------------------------------------------------------
    // DoD FIX-7: sanitizeStatusForResponse()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("DoD FIX-7: sanitizeStatusForResponse() strips an absolute Unix path embedded "
        + "in an exception-derived status, so a REST 422 body never leaks server filesystem "
        + "layout")
    void sanitizeStatusStripsAbsoluteUnixPath() {
        String raw = "Error: Hot reload failed - "
            + "/home/nigel/data/logix-emulator/plc1.l5x (No such file or directory)";

        String sanitized = DeviceConfigService.sanitizeStatusForResponse(raw);

        assertThat(sanitized)
            .as("the absolute path must not appear verbatim")
            .doesNotContain("/home/nigel")
            .contains("<path>")
            .contains("Error: Hot reload failed");
    }

    @Test
    @DisplayName("DoD FIX-7: sanitizeStatusForResponse() strips an absolute Windows path")
    void sanitizeStatusStripsAbsoluteWindowsPath() {
        String raw = "Error: L5X parser failed to parse file. See C:\\ProgramData\\Ignition\\data\\logix-emulator\\plc1.l5x";

        String sanitized = DeviceConfigService.sanitizeStatusForResponse(raw);

        assertThat(sanitized)
            .as("the absolute Windows path must not appear verbatim")
            .doesNotContain("ProgramData")
            .contains("<path>");
    }

    @Test
    @DisplayName("sanitizeStatusForResponse() leaves a path-free status unchanged")
    void sanitizeStatusLeavesOrdinaryStatusUnchanged() {
        assertThat(DeviceConfigService.sanitizeStatusForResponse("Running")).isEqualTo("Running");
        assertThat(DeviceConfigService.sanitizeStatusForResponse(
            "Error: Rockwell L5K/L5X (Allen-Bradley) parser failed to parse file 'garbage.l5x'. "
                + "Check gateway logs for details."))
            .contains("garbage.l5x")
            .doesNotContain("<path>");
    }

    @Test
    @DisplayName("sanitizeStatusForResponse() returns null for null input")
    void sanitizeStatusHandlesNull() {
        assertThat(DeviceConfigService.sanitizeStatusForResponse(null)).isNull();
    }
}
