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
}
