package com.inductiveautomation.plcsimulator.gateway.web;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.Method;

/**
 * Helper for authentication checks in web routes.
 * Uses Ignition's SecurityContext to verify proper authentication.
 */
public final class AuthenticationHelper {

    private static final Logger logger = LoggerFactory.getLogger(AuthenticationHelper.class);

    private AuthenticationHelper() {
        // Utility class
    }

    /**
     * Check if the request is authenticated using Ignition's security mechanisms.
     */
    public static RouteAccess checkAuthenticated(RequestContext req) {
        try {
            HttpServletRequest httpRequest = req.getRequest();

            // Method 1: Check Ignition SecurityContext
            Object securityContext = httpRequest.getAttribute(
                "com.inductiveautomation.ignition.gateway.security.SecurityContext");
            if (securityContext != null) {
                RouteAccess access = checkSecurityContext(securityContext);
                if (access != null) return access;
            }

            // Method 2: Check standard servlet authentication
            if (httpRequest.getRemoteUser() != null || httpRequest.getUserPrincipal() != null) {
                logger.debug("Authentication granted via servlet principal");
                return RouteAccess.GRANTED;
            }

            // Method 3: Check session for authenticated user marker
            HttpSession session = httpRequest.getSession(false);
            if (session != null) {
                RouteAccess access = checkSessionAuth(session);
                if (access != null) return access;
            }

            logger.debug("Authentication denied - no valid authentication found");
            return RouteAccess.UNAUTHORIZED;

        } catch (Exception e) {
            logger.error("Error checking authentication", e);
            return RouteAccess.UNAUTHORIZED;
        }
    }

    private static RouteAccess checkSecurityContext(Object securityContext) {
        try {
            Class<?> secContextClass = securityContext.getClass();

            // Try isAuthenticated method
            try {
                Method isAuthMethod = secContextClass.getMethod("isAuthenticated");
                Boolean isAuth = (Boolean) isAuthMethod.invoke(securityContext);
                if (Boolean.TRUE.equals(isAuth)) {
                    logger.debug("Authentication granted via SecurityContext");
                    return RouteAccess.GRANTED;
                } else {
                    return RouteAccess.UNAUTHORIZED;
                }
            } catch (NoSuchMethodException e) {
                // Try getUser method
                Method getUserMethod = secContextClass.getMethod("getUser");
                Object user = getUserMethod.invoke(securityContext);
                if (user != null) {
                    logger.debug("Authentication granted - user exists");
                    return RouteAccess.GRANTED;
                }
            }
        } catch (Exception e) {
            logger.debug("Could not determine authentication from SecurityContext", e);
        }
        return null;
    }

    private static RouteAccess checkSessionAuth(HttpSession session) {
        Object authMarker = session.getAttribute("web-auth-request-collection");
        if (authMarker != null) {
            try {
                Method isAuthMethod = authMarker.getClass().getMethod("isAuthenticated");
                Boolean isAuth = (Boolean) isAuthMethod.invoke(authMarker);
                if (Boolean.TRUE.equals(isAuth)) {
                    logger.debug("Authentication granted via session auth collection");
                    return RouteAccess.GRANTED;
                }
            } catch (Exception e) {
                logger.debug("Could not check session auth collection", e);
            }
        }
        return null;
    }

    /**
     * Get user identifier for rate limiting.
     */
    public static String getUserIdentifier(RequestContext context) {
        HttpServletRequest request = context.getRequest();

        String username = request.getRemoteUser();
        if (username != null && !username.isEmpty()) {
            return username;
        }

        if (request.getUserPrincipal() != null) {
            username = request.getUserPrincipal().getName();
            if (username != null && !username.isEmpty()) {
                return username;
            }
        }

        HttpSession session = request.getSession(false);
        if (session != null) {
            return "session:" + session.getId();
        }

        return "ip:" + getClientIP(context);
    }

    /**
     * Get client IP address, accounting for proxies.
     */
    public static String getClientIP(RequestContext context) {
        HttpServletRequest request = context.getRequest();

        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            String[] ips = xForwardedFor.split(",");
            if (ips.length > 0) {
                return ips[0].trim();
            }
        }

        String xRealIP = request.getHeader("X-Real-IP");
        if (xRealIP != null && !xRealIP.isEmpty()) {
            return xRealIP;
        }

        String remoteAddr = request.getRemoteAddr();
        return remoteAddr != null ? remoteAddr : "unknown";
    }
}
