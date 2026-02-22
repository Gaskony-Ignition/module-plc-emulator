package com.inductiveautomation.logixemulator.gateway.web;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Static utility class for gateway authentication, CSRF validation, IP resolution,
 * log sanitization, rate-limit responses, and request-body reading.
 *
 * Extracted from FileUploadRoutes to allow reuse across all controller classes.
 */
public final class GatewayAuthHelper {

    private static final Logger logger = LoggerFactory.getLogger(GatewayAuthHelper.class);

    /** Trusted proxy IP addresses. Only trust X-Forwarded-For from these sources. */
    static final Set<String> TRUSTED_PROXIES = Set.of(
        "127.0.0.1",
        "::1",
        "0:0:0:0:0:0:0:1"  // IPv6 localhost
    );

    static final Pattern IPV4_PATTERN = Pattern.compile(
        "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$");
    static final Pattern IPV6_PATTERN = Pattern.compile(
        "^([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}$");

    private GatewayAuthHelper() {}

    // -------------------------------------------------------------------------
    // Authentication helpers
    // -------------------------------------------------------------------------

    /**
     * Check if the current request is authenticated via Gateway session.
     * Checks HTTP session "user" attribute and request actor.
     */
    public static boolean isGatewayAuthenticated(RequestContext req) {
        try {
            // 1. Check HTTP session for "user" attribute (set by Gateway on login)
            var httpSession = req.getRequest().getSession(false);
            if (httpSession != null) {
                var authUser = httpSession.getAttribute("user");
                if (authUser != null) {
                    return true;
                }
            }

            // 2. Check actor from request context (filtering "unknown" default)
            var actor = req.getActor();
            if (actor != null && !actor.isEmpty() && !"unknown".equalsIgnoreCase(actor)) {
                return true;
            }
        } catch (Exception e) {
            logger.debug("Error checking authentication", e);
        }
        return false;
    }

    /**
     * Validates that the request comes from an authenticated gateway user.
     * On failure, writes a 401 JSON response and returns false.
     */
    public static boolean requireAuthentication(RequestContext ctx, HttpServletResponse resp)
            throws JSONException, java.io.IOException {
        if (!isGatewayAuthenticated(ctx)) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            new JSONObject().put("success", false)
                .put("error", "Authentication required")
                .write(resp.getWriter());
            return false;
        }
        return true;
    }

    /**
     * Validates CSRF protection on state-changing requests.
     * Requires X-Requested-With header to prevent cross-origin form submissions.
     */
    public static boolean requireCSRFToken(RequestContext ctx, HttpServletResponse resp)
            throws JSONException, java.io.IOException {
        String xRequestedWith = ctx.getRequest().getHeader("X-Requested-With");
        if (!"XMLHttpRequest".equals(xRequestedWith)) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            new JSONObject().put("success", false)
                .put("error", "CSRF validation failed — X-Requested-With header required")
                .write(resp.getWriter());
            return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // User / IP helpers
    // -------------------------------------------------------------------------

    /**
     * Get username from request.
     * Falls back to "anon-{ip}" since Ignition data routes don't populate remoteUser/userPrincipal.
     */
    public static String getUsername(RequestContext ctx) {
        var req = ctx.getRequest();
        if (req.getRemoteUser() != null) return req.getRemoteUser();
        if (req.getUserPrincipal() != null) return req.getUserPrincipal().getName();
        return "anon-" + getClientIP(ctx);
    }

    /**
     * Get client IP address, accounting for proxies.
     * Only trusts X-Forwarded-For header from known trusted proxy IPs
     * to prevent IP spoofing attacks on rate limiting.
     */
    public static String getClientIP(RequestContext ctx) {
        var request = ctx.getRequest();
        String remoteAddr = request.getRemoteAddr();

        if (remoteAddr != null && TRUSTED_PROXIES.contains(remoteAddr)) {
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
                String[] ips = xForwardedFor.split(",");
                if (ips.length > 0) {
                    String clientIP = ips[0].trim();
                    if (isValidIPAddress(clientIP)) {
                        return clientIP;
                    }
                }
            }

            String xRealIP = request.getHeader("X-Real-IP");
            if (xRealIP != null && !xRealIP.isEmpty() && isValidIPAddress(xRealIP)) {
                return xRealIP;
            }
        }

        return remoteAddr != null ? remoteAddr : "unknown";
    }

    /**
     * Validates that the string is a literal IP address (not a hostname).
     * Uses regex instead of InetAddress.getByName() to avoid DNS resolution.
     */
    public static boolean isValidIPAddress(String ip) {
        if (ip == null || ip.isEmpty() || ip.length() > 45) {
            return false;
        }
        return IPV4_PATTERN.matcher(ip).matches() || IPV6_PATTERN.matcher(ip).matches();
    }

    // -------------------------------------------------------------------------
    // Log sanitization
    // -------------------------------------------------------------------------

    /** Removes CR/LF from user-controlled strings to prevent log injection. */
    public static String sanitizeForLog(String value) {
        if (value == null) return "null";
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }

    // -------------------------------------------------------------------------
    // Request body / rate-limit helpers
    // -------------------------------------------------------------------------

    /**
     * Read the full request body up to maxSize bytes. Throws IllegalStateException if exceeded.
     */
    public static String readRequestContent(RequestContext ctx, long maxSize) throws Exception {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(ctx.getRequest().getInputStream(), StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            long total = 0;
            while ((read = reader.read(buffer)) != -1) {
                total += read;
                if (total > maxSize) throw new IllegalStateException("File size exceeds maximum");
                content.append(buffer, 0, read);
            }
        }
        return content.toString();
    }

    /**
     * Write a 429 rate-limit response with appropriate headers.
     */
    public static JSONObject rateLimitResponse(HttpServletResponse resp, JSONObject result,
                                               RateLimiter.RateLimitResult rateResult,
                                               String user, String ip) throws JSONException {
        resp.setStatus(429);
        resp.setHeader("X-RateLimit-Limit", String.valueOf(rateResult.getLimit()));
        resp.setHeader("X-RateLimit-Remaining", "0");
        resp.setHeader("X-RateLimit-Reset", String.valueOf(rateResult.getResetTimeMs()));
        long retryAfter = (rateResult.getResetTimeMs() - System.currentTimeMillis()) / 1000;
        resp.setHeader("Retry-After", String.valueOf(retryAfter));
        logger.warn("Rate limit exceeded for user {} from IP {}", sanitizeForLog(user), sanitizeForLog(ip));
        return result.put("success", false)
            .put("error", String.format("Rate limit exceeded: %s limit of %d uploads per hour",
                rateResult.getLimitType(), rateResult.getLimit()))
            .put("retryAfter", retryAfter);
    }
}
