package com.inductiveautomation.logixemulator.gateway.web.controller;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.logixemulator.gateway.FileVersionManager;
import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorDevice;
import com.inductiveautomation.logixemulator.gateway.web.DeviceFileManager;
import com.inductiveautomation.logixemulator.gateway.web.GatewayAuthHelper;
import com.inductiveautomation.logixemulator.gateway.web.RateLimiter;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.util.Optional;

/**
 * Handles the file version history surface for a device (defect B5): listing the retained
 * uploads and reverting to one of them.
 *
 * <p>{@link FileVersionManager} already implemented {@code saveVersion}/{@code getVersions}/
 * {@code restoreVersion}/{@code getMostRecentVersion} with a 5-version retention policy, but
 * nothing on the REST surface ever called {@code getVersions}/{@code restoreVersion} — this
 * controller is that missing caller. See {@code plc-dod/item7-versioning-FAIL.txt}.</p>
 */
public class VersionController {

    private static final Logger logger = LoggerFactory.getLogger(VersionController.class);

    /** 422 Unprocessable Entity — same B4 convention {@link DeviceController} uses: the request
     * was well-formed and the version file was restored to disk, but the reload that followed
     * could not apply it to the device (parse/address-space build failure). */
    private static final int SC_UNPROCESSABLE_ENTITY = 422;

    private final DeviceFileManager deviceManager;
    private final RateLimiter readRateLimiter;
    private final RateLimiter writeRateLimiter;

    public VersionController(DeviceFileManager deviceManager, RateLimiter readRateLimiter,
                              RateLimiter writeRateLimiter) {
        this.deviceManager = deviceManager;
        this.readRateLimiter = readRateLimiter;
        this.writeRateLimiter = writeRateLimiter;
    }

    // -------------------------------------------------------------------------
    // Validation helper
    // -------------------------------------------------------------------------

    JSONObject validateDeviceName(String deviceName, HttpServletResponse resp) throws JSONException {
        return DeviceConfigService.validateDeviceName(deviceName, resp);
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    /**
     * List the retained file versions for a device, newest first, with the live/current file
     * (if any) flagged. Read endpoint — subject to the read rate limiter.
     */
    public JSONObject handleListVersions(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        try { if (!GatewayAuthHelper.requireAuthentication(ctx, resp)) return null; }
        catch (Exception e) { resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); return null; }

        JSONObject result = new JSONObject();

        RateLimiter.RateLimitResult readRate = readRateLimiter.checkRequest(
            GatewayAuthHelper.getUsername(ctx), GatewayAuthHelper.getClientIP(ctx));
        if (!readRate.isAllowed()) { resp.setStatus(429); return result.put("success", false).put("error", "Rate limit exceeded"); }

        String deviceName = ctx.getParameter("name");
        JSONObject nameError = validateDeviceName(deviceName, resp);
        if (nameError != null) return nameError;

        Optional<LogixEmulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
        if (deviceOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Device not found: " + deviceName);
        }

        LogixEmulatorDevice device = deviceOpt.get();
        String filePath = deviceManager.getDeviceFilePath(device);

        JSONArray versionsArray = new JSONArray();

        if (filePath != null && !filePath.isEmpty()) {
            File currentFile = new File(filePath);

            if (currentFile.exists()) {
                versionsArray.put(new JSONObject()
                    .put("filename", currentFile.getName())
                    .put("size", currentFile.length())
                    .put("timestamp", currentFile.lastModified())
                    .put("current", true));
            }

            FileVersionManager versionManager = deviceManager.getVersionManager(device);
            for (File version : versionManager.getVersions(currentFile.getName())) {
                versionsArray.put(new JSONObject()
                    .put("filename", version.getName())
                    .put("size", version.length())
                    .put("timestamp", version.lastModified())
                    .put("current", false));
            }
        }

        return result.put("success", true)
            .put("deviceName", deviceName)
            .put("versions", versionsArray)
            .put("count", versionsArray.length())
            .put("maxVersions", FileVersionManager.getMaxVersions());
    }

    /**
     * Revert a device's current file to a previously retained version, then reload the device
     * through the same {@code reloadDevice} path a REST upload uses. Write endpoint — subject
     * to CSRF + the write rate limiter.
     *
     * <p>Request body: {@code { "filename": "<version filename from handleListVersions>" } }</p>
     */
    public JSONObject handleRevertVersion(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        try { if (!GatewayAuthHelper.requireAuthentication(ctx, resp)) return null; }
        catch (Exception e) { resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); return null; }

        JSONObject result = new JSONObject();

        try { if (!GatewayAuthHelper.requireCSRFToken(ctx, resp)) return null; }
        catch (Exception e) { resp.setStatus(HttpServletResponse.SC_FORBIDDEN); return result.put("success", false).put("error", "CSRF validation failed"); }

        RateLimiter.RateLimitResult writeRate = writeRateLimiter.checkRequest(
            GatewayAuthHelper.getUsername(ctx), GatewayAuthHelper.getClientIP(ctx));
        if (!writeRate.isAllowed()) { resp.setStatus(429); return result.put("success", false).put("error", "Rate limit exceeded"); }

        String deviceName = ctx.getParameter("name");
        JSONObject nameError = validateDeviceName(deviceName, resp);
        if (nameError != null) return nameError;

        Optional<LogixEmulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
        if (deviceOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Device not found: " + deviceName);
        }

        LogixEmulatorDevice device = deviceOpt.get();
        String filePath = deviceManager.getDeviceFilePath(device);
        if (filePath == null || filePath.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_CONFLICT);
            return result.put("success", false)
                .put("error", "Device has no current file to revert - upload a file first");
        }

        String versionFileName;
        try {
            String body = GatewayAuthHelper.readRequestContent(ctx, 4 * 1024);
            JSONObject requestJson = new JSONObject(body);
            versionFileName = requestJson.optString("filename", null);
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return result.put("success", false).put("error", "Invalid request body");
        }

        if (versionFileName == null || versionFileName.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return result.put("success", false).put("error", "filename required");
        }

        File currentFile = new File(filePath);
        FileVersionManager versionManager = deviceManager.getVersionManager(device);

        // Only ever restore a file this exact FileVersionManager already knows about — the
        // requested name is matched against its own listing rather than used to build a path
        // directly, so this can't be turned into an arbitrary-file read/restore.
        File matchedVersion = versionManager.getVersions(currentFile.getName()).stream()
            .filter(f -> f.getName().equals(versionFileName))
            .findFirst()
            .orElse(null);

        if (matchedVersion == null) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Unknown version: " + versionFileName);
        }

        if (!versionManager.restoreVersion(matchedVersion, currentFile)) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return result.put("success", false).put("error", "Failed to restore version to disk");
        }

        // Same code path a REST upload uses to apply a file to the device — and the same B4
        // honesty convention: a restore that lands on disk but then fails to parse/build is
        // reported as a failure, not silently swallowed.
        deviceManager.reloadDevice(device);
        String status = device.getStatus();

        if (DeviceController.isBuildFailureStatus(status)) {
            resp.setStatus(SC_UNPROCESSABLE_ENTITY);
            // FIX-7 (11/07/2026): same exception-message-leaking-a-path risk as DeviceController's
            // upload path - sanitise the status before it's echoed to a REST caller.
            String safeStatus = DeviceConfigService.sanitizeStatusForResponse(status);
            return result.put("success", false)
                .put("deviceName", deviceName)
                .put("restoredFrom", versionFileName)
                .put("error", "Version restored to disk but failed to apply to device: " + safeStatus)
                .put("status", safeStatus);
        }

        // A successful revert is itself treated as a new upload for versioning purposes, so it
        // becomes the newest snapshot and the version just reverted-from isn't immediately
        // pushed out of the retention window by its own restore.
        if (!versionManager.saveVersion(currentFile, currentFile.getName())) {
            logger.warn("Failed to snapshot post-revert file for device {}", GatewayAuthHelper.sanitizeForLog(deviceName));
        }

        return result.put("success", true)
            .put("deviceName", deviceName)
            .put("restoredFrom", versionFileName)
            .put("status", status);
    }
}
