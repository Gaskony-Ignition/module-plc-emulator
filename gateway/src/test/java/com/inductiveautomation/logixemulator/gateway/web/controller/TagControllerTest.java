package com.inductiveautomation.logixemulator.gateway.web.controller;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TagController helpers and rate-limiting behaviour.
 */
@ExtendWith(MockitoExtension.class)
class TagControllerTest {

    @Mock DeviceFileManager deviceManager;
    @Mock RateLimiter readRateLimiter;
    @Mock RequestContext ctx;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse resp;

    TagController controller;

    @BeforeEach
    void setUp() {
        controller = new TagController(deviceManager, readRateLimiter);
        when(ctx.getRequest()).thenReturn(request);
    }

    // -------------------------------------------------------------------------
    // parseIntParam
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("parseIntParam() returns default when parameter is absent (null)")
    void testParseIntParamMissing() {
        when(request.getParameter("limit")).thenReturn(null);
        assertThat(controller.parseIntParam(ctx, "limit", 100)).isEqualTo(100);
    }

    @Test
    @DisplayName("parseIntParam() returns default when parameter is empty string")
    void testParseIntParamEmpty() {
        when(request.getParameter("offset")).thenReturn("");
        assertThat(controller.parseIntParam(ctx, "offset", 0)).isEqualTo(0);
    }

    @Test
    @DisplayName("parseIntParam() returns default when parameter is non-numeric")
    void testParseIntParamNonNumeric() {
        when(request.getParameter("depth")).thenReturn("abc");
        assertThat(controller.parseIntParam(ctx, "depth", 1)).isEqualTo(1);
    }

    @Test
    @DisplayName("parseIntParam() parses valid integer parameter correctly")
    void testParseIntParamValid() {
        when(request.getParameter("limit")).thenReturn("250");
        assertThat(controller.parseIntParam(ctx, "limit", 100)).isEqualTo(250);
    }

    // -------------------------------------------------------------------------
    // handleGetLiveTags — rate-limit rejection
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("handleGetLiveTags() returns 429 when read rate-limiter rejects the request")
    void testHandleGetLiveTagsRateLimited() throws Exception {
        // Authenticate via session
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("user")).thenReturn("admin");
        // Rate-limit
        var rateLimitResult = mock(RateLimiter.RateLimitResult.class);
        when(rateLimitResult.isAllowed()).thenReturn(false);
        when(request.getRemoteUser()).thenReturn("admin");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(readRateLimiter.checkRequest(any(), any())).thenReturn(rateLimitResult);

        JSONObject result = controller.handleGetLiveTags(ctx, resp);

        verify(resp).setStatus(429);
        assertThat(result).isNotNull();
        assertThat(result.getBoolean("success")).isFalse();
    }
}
