package com.inductiveautomation.logixemulator.gateway.web.controller;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.logixemulator.gateway.web.DeviceFileManager;
import com.inductiveautomation.logixemulator.gateway.web.RateLimiter;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SimulationController helper methods and rate-limiting behaviour.
 * Handler tests that need Ignition infrastructure (RequestContext, session) are
 * covered by the rate-limiting scenario which only needs a minimal request mock.
 */
@ExtendWith(MockitoExtension.class)
class SimulationControllerTest {

    @Mock
    DeviceFileManager deviceManager;

    @Mock
    RateLimiter writeRateLimiter;

    @Mock
    RequestContext ctx;

    @Mock
    HttpServletResponse resp;

    SimulationController controller;

    @BeforeEach
    void setUp() {
        controller = new SimulationController(deviceManager, writeRateLimiter);
    }

    // -------------------------------------------------------------------------
    // convertToNodeIdPath
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("convertToNodeIdPath() converts Controller:Global/ prefix correctly")
    void testConvertToNodeIdPathGlobal() {
        assertThat(controller.convertToNodeIdPath("Controller:Global/Motor1/Speed"))
            .isEqualTo("Controller:Global.Motor1.Speed");
    }

    @Test
    @DisplayName("convertToNodeIdPath() converts Programs/ prefix correctly")
    void testConvertToNodeIdPathPrograms() {
        assertThat(controller.convertToNodeIdPath("Programs/MainProgram/Counter"))
            .isEqualTo("Programs.MainProgram.Counter");
    }

    @Test
    @DisplayName("convertToNodeIdPath() defaults to replacing slashes with dots")
    void testConvertToNodeIdPathDefault() {
        assertThat(controller.convertToNodeIdPath("SomeOther/Path/Tag"))
            .isEqualTo("SomeOther.Path.Tag");
    }

    @Test
    @DisplayName("convertToNodeIdPath() returns null for null input")
    void testConvertToNodeIdPathNull() {
        assertThat(controller.convertToNodeIdPath(null)).isNull();
    }

    @Test
    @DisplayName("convertToNodeIdPath() handles flat tag name (no slashes)")
    void testConvertToNodeIdPathFlat() {
        assertThat(controller.convertToNodeIdPath("MyTag")).isEqualTo("MyTag");
    }

    // -------------------------------------------------------------------------
    // convertValue
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("convertValue() converts 'true' to Boolean.TRUE for BOOL type")
    void testConvertValueBoolTrue() {
        assertThat(controller.convertValue("true", "BOOL")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("convertValue() converts 'false' to Boolean.FALSE for BOOL type")
    void testConvertValueBoolFalse() {
        // Boolean.parseBoolean("false") = false, "false".equals("1") = false, equalsIgnoreCase("true") = false
        assertThat(controller.convertValue("false", "BOOL")).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("convertValue() converts '42' to Integer for DINT type")
    void testConvertValueDint() {
        assertThat(controller.convertValue("42", "DINT")).isEqualTo(42);
    }

    @Test
    @DisplayName("convertValue() converts '3.14' to Float for REAL type")
    void testConvertValueReal() {
        Object result = controller.convertValue("3.14", "REAL");
        assertThat(result).isInstanceOf(Float.class);
        assertThat((Float) result).isCloseTo(3.14f, within(0.001f));
    }

    @Test
    @DisplayName("convertValue() converts '100' to Long for LINT type")
    void testConvertValueLint() {
        assertThat(controller.convertValue("100", "LINT")).isEqualTo(100L);
    }

    @Test
    @DisplayName("convertValue() returns String for unknown data type")
    void testConvertValueUnknownType() {
        assertThat(controller.convertValue("hello", "UNKNOWN")).isEqualTo("hello");
    }

    @Test
    @DisplayName("convertValue() returns String when number cannot be parsed for typed field")
    void testConvertValueParseFailure() {
        // "abc" cannot be parsed as DINT — should fall through to string
        assertThat(controller.convertValue("abc", "DINT")).isEqualTo("abc");
    }

    // -------------------------------------------------------------------------
    // handleWriteTag — rate-limit rejection
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("handleWriteTag() returns 429 when write rate-limiter rejects the request")
    void testHandleWriteTagRateLimited() throws Exception {
        // Rate-limit — only isAllowed() and checkRequest() are consulted by the inline 429 path
        var rateLimitResult = mock(RateLimiter.RateLimitResult.class);
        when(rateLimitResult.isAllowed()).thenReturn(false);

        var request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(ctx.getRequest()).thenReturn(request);
        // Simulate authenticated session
        var session = mock(jakarta.servlet.http.HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("user")).thenReturn("admin");
        // Pass CSRF check
        when(request.getHeader("X-Requested-With")).thenReturn("XMLHttpRequest");
        // Rate-limit on write
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getRemoteUser()).thenReturn("admin");
        when(writeRateLimiter.checkRequest(any(), any())).thenReturn(rateLimitResult);

        JSONObject result = controller.handleWriteTag(ctx, resp);

        verify(resp).setStatus(429);
        assertThat(result).isNotNull();
        assertThat(result.getBoolean("success")).isFalse();
    }
}
