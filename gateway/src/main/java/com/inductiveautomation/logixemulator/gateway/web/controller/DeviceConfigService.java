package com.inductiveautomation.logixemulator.gateway.web.controller;

import org.json.JSONException;
import org.json.JSONObject;

import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;

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

    /**
     * Matches an absolute Unix path ({@code /foo/bar/baz}) or an absolute Windows path
     * ({@code C:\foo\bar}) of at least two path segments, so a bare leading slash in ordinary
     * prose isn't mistaken for a path.
     */
    private static final Pattern ABSOLUTE_PATH_PATTERN = Pattern.compile(
        "(?:[A-Za-z]:\\\\(?:[^\\s\\\\]+\\\\)+[^\\s\\\\]*)"
            + "|(?:/(?:[^\\s/]+/)+[^\\s/]*)");

    /**
     * Sanitises a device status string before it is echoed in a REST response body (FIX-7,
     * 11/07/2026). {@code LogixEmulatorDevice.onStartup} / {@code HotReloadCoordinator} set the
     * device status directly from a caught exception's message (e.g.
     * {@code "Error: " + e.getMessage()}), and exception messages from file I/O failures
     * routinely embed the full absolute filesystem path of the file involved
     * ({@code FileNotFoundException}'s message IS the path). {@link DeviceController} and
     * {@link VersionController} both echo that status verbatim in their 422 bodies
     * ({@code error} and {@code status} fields), which would otherwise leak server filesystem
     * layout to any REST caller.
     *
     * <p>This is the single point both controllers call before including a device status in a
     * response - callers should never format the raw status into a response body directly.</p>
     *
     * @param status a raw device status (may be {@code null})
     * @return {@code status} with any absolute path replaced by {@code "<path>"}, or {@code null}
     *     if {@code status} was {@code null}
     */
    public static String sanitizeStatusForResponse(String status) {
        if (status == null) {
            return null;
        }
        return ABSOLUTE_PATH_PATTERN.matcher(status).replaceAll("<path>");
    }
}
