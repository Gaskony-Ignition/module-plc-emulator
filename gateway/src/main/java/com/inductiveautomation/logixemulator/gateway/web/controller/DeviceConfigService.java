package com.inductiveautomation.logixemulator.gateway.web.controller;

import org.json.JSONException;
import org.json.JSONObject;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Centralised configuration / device-name validation for REST controllers.
 *
 * <p>Three controllers (
 * {@link DeviceController},
 * {@link TagController},
 * {@link SimulationController}) historically shipped byte-for-byte copies of
 * {@code validateDeviceName(...)}. The review (SEV-2 in
 * {@code mod-plc-emulator.md}) flagged the duplication as drift-waiting-to-
 * happen and recommended a single canonical implementation. This class is
 * that home. Each controller still exposes its own {@code validateDeviceName}
 * method (preserving its current package-private/private API surface and the
 * thrown checked exception) but delegates here.</p>
 *
 * <p><b>Note on the regex:</b> the existing rule
 * ({@code ^[a-zA-Z0-9_\- ]{1,100}$}) intentionally allows spaces and length
 * up to 100. That disagrees with the stricter
 * {@link com.inductiveautomation.logixemulator.gateway.web.PathSecurity#sanitizeDeviceName}
 * regex ({@code ^[a-zA-Z0-9_-]+$}). Reconciling those two is a behavioural
 * decision out of scope for this structural refactor; the service preserves
 * the existing controller-level behaviour exactly.</p>
 */
public final class DeviceConfigService {

    /** Maximum length of a device name accepted by the REST surface. */
    public static final int MAX_DEVICE_NAME_LENGTH = 100;

    /** Regex matched by {@link #validateDeviceName(String, HttpServletResponse)}. */
    public static final String DEVICE_NAME_REGEX = "^[a-zA-Z0-9_\\- ]{1," + MAX_DEVICE_NAME_LENGTH + "}$";

    private DeviceConfigService() {
        // utility class — not instantiable
    }

    /**
     * Validates that a device name is safe for the REST API surface.
     *
     * <p>Returns {@code null} when the name is valid. Otherwise sets
     * {@code resp} status to {@code 400 Bad Request} and returns a JSON error
     * body shaped {@code { "success": false, "error": "<message>" }} ready to
     * be written to the response stream.</p>
     *
     * @param deviceName candidate name from a query parameter or path
     * @param resp the servlet response — only modified on the failure path
     * @return {@code null} on success, or a populated {@link JSONObject} on
     *     failure
     * @throws JSONException if the JSON error body cannot be assembled (in
     *     practice never — the keys/values are static)
     */
    public static JSONObject validateDeviceName(String deviceName, HttpServletResponse resp) throws JSONException {
        if (deviceName == null || deviceName.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return new JSONObject().put("success", false).put("error", "Device name required");
        }
        if (!deviceName.matches(DEVICE_NAME_REGEX)) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return new JSONObject().put("success", false).put("error", "Invalid device name");
        }
        return null;
    }
}
