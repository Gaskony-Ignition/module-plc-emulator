package com.inductiveautomation.logixemulator.gateway.web;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GatewayAuthHelper static utilities.
 */
@ExtendWith(MockitoExtension.class)
class GatewayAuthHelperTest {

    @Mock
    RequestContext ctx;

    @Mock
    HttpServletRequest request;

    // -------------------------------------------------------------------------
    // sanitizeForLog
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sanitizeForLog() escapes CR characters to \\\\r")
    void testSanitizeForLogEscapesCR() {
        assertThat(GatewayAuthHelper.sanitizeForLog("hello\rworld")).isEqualTo("hello\\rworld");
    }

    @Test
    @DisplayName("sanitizeForLog() escapes LF characters to \\\\n")
    void testSanitizeForLogEscapesLF() {
        assertThat(GatewayAuthHelper.sanitizeForLog("line1\nline2")).isEqualTo("line1\\nline2");
    }

    @Test
    @DisplayName("sanitizeForLog() escapes both CR and LF")
    void testSanitizeForLogEscapesBoth() {
        assertThat(GatewayAuthHelper.sanitizeForLog("a\r\nb")).isEqualTo("a\\r\\nb");
    }

    @Test
    @DisplayName("sanitizeForLog() returns 'null' for null input")
    void testSanitizeForLogNull() {
        assertThat(GatewayAuthHelper.sanitizeForLog(null)).isEqualTo("null");
    }

    @Test
    @DisplayName("sanitizeForLog() returns string unchanged when no CR/LF present")
    void testSanitizeForLogCleanString() {
        assertThat(GatewayAuthHelper.sanitizeForLog("normal text")).isEqualTo("normal text");
    }

    // -------------------------------------------------------------------------
    // isValidIPAddress
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("isValidIPAddress() accepts valid IPv4 addresses")
    void testIsValidIPAddressAcceptsIPv4() {
        assertThat(GatewayAuthHelper.isValidIPAddress("192.168.1.1")).isTrue();
        assertThat(GatewayAuthHelper.isValidIPAddress("10.0.0.1")).isTrue();
        assertThat(GatewayAuthHelper.isValidIPAddress("255.255.255.0")).isTrue();
        assertThat(GatewayAuthHelper.isValidIPAddress("0.0.0.0")).isTrue();
    }

    @Test
    @DisplayName("isValidIPAddress() accepts valid IPv6 addresses")
    void testIsValidIPAddressAcceptsIPv6() {
        assertThat(GatewayAuthHelper.isValidIPAddress("::1")).isTrue();
        assertThat(GatewayAuthHelper.isValidIPAddress("fe80::1")).isTrue();
        assertThat(GatewayAuthHelper.isValidIPAddress("2001:db8::1")).isTrue();
    }

    @Test
    @DisplayName("isValidIPAddress() rejects garbage strings")
    void testIsValidIPAddressRejectsGarbage() {
        assertThat(GatewayAuthHelper.isValidIPAddress("not-an-ip")).isFalse();
        assertThat(GatewayAuthHelper.isValidIPAddress("hostname.local")).isFalse();
        assertThat(GatewayAuthHelper.isValidIPAddress("999.999.999.999")).isFalse();
        assertThat(GatewayAuthHelper.isValidIPAddress("")).isFalse();
        assertThat(GatewayAuthHelper.isValidIPAddress(null)).isFalse();
    }

    @Test
    @DisplayName("isValidIPAddress() rejects strings over 45 characters")
    void testIsValidIPAddressRejectsTooLong() {
        String tooLong = "1".repeat(46);
        assertThat(GatewayAuthHelper.isValidIPAddress(tooLong)).isFalse();
    }

    // -------------------------------------------------------------------------
    // getClientIP — X-Forwarded-For trusted proxy
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getClientIP() returns remoteAddr directly when not from a trusted proxy")
    void testGetClientIPReturnsRemoteAddrForUntrustedSource() {
        when(ctx.getRequest()).thenReturn(request);
        when(request.getRemoteAddr()).thenReturn("203.0.113.50");
        // Not in TRUSTED_PROXIES, so X-Forwarded-For must be ignored

        String ip = GatewayAuthHelper.getClientIP(ctx);
        assertThat(ip).isEqualTo("203.0.113.50");
        verify(request, never()).getHeader("X-Forwarded-For");
    }

    @Test
    @DisplayName("getClientIP() uses X-Forwarded-For when request comes from localhost (trusted proxy)")
    void testGetClientIPUsesFwdForFromTrustedProxy() {
        when(ctx.getRequest()).thenReturn(request);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1"); // trusted proxy
        when(request.getHeader("X-Forwarded-For")).thenReturn("10.1.2.3");

        String ip = GatewayAuthHelper.getClientIP(ctx);
        assertThat(ip).isEqualTo("10.1.2.3");
    }

    @Test
    @DisplayName("getClientIP() ignores invalid X-Forwarded-For and falls back to remoteAddr")
    void testGetClientIPFallsBackForInvalidForwardedIP() {
        when(ctx.getRequest()).thenReturn(request);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1"); // trusted proxy
        when(request.getHeader("X-Forwarded-For")).thenReturn("not-an-ip");
        when(request.getHeader("X-Real-IP")).thenReturn(null);

        String ip = GatewayAuthHelper.getClientIP(ctx);
        assertThat(ip).isEqualTo("127.0.0.1");
    }

    // -------------------------------------------------------------------------
    // isGatewayAuthenticated — actor check
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("isGatewayAuthenticated() returns false when actor is 'unknown' and no session")
    void testIsGatewayAuthenticatedFalseForUnknownActor() {
        when(ctx.getRequest()).thenReturn(request);
        when(request.getSession(false)).thenReturn(null);
        when(ctx.getActor()).thenReturn("unknown");

        assertThat(GatewayAuthHelper.isGatewayAuthenticated(ctx)).isFalse();
    }

    @Test
    @DisplayName("isGatewayAuthenticated() returns true when actor is a real username")
    void testIsGatewayAuthenticatedTrueForRealActor() {
        when(ctx.getRequest()).thenReturn(request);
        when(request.getSession(false)).thenReturn(null);
        when(ctx.getActor()).thenReturn("admin");

        assertThat(GatewayAuthHelper.isGatewayAuthenticated(ctx)).isTrue();
    }

    @Test
    @DisplayName("isGatewayAuthenticated() returns true when session has 'user' attribute")
    void testIsGatewayAuthenticatedTrueForSessionUser() {
        HttpSession session = mock(HttpSession.class);
        when(ctx.getRequest()).thenReturn(request);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("user")).thenReturn("admin");

        assertThat(GatewayAuthHelper.isGatewayAuthenticated(ctx)).isTrue();
    }
}
