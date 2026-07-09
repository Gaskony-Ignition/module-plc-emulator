package com.inductiveautomation.logixemulator.gateway.web.controller;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.logixemulator.gateway.DeviceRegistry;
import com.inductiveautomation.logixemulator.gateway.FileVersionManager;
import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorConfig;
import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorDevice;
import com.inductiveautomation.logixemulator.gateway.validation.FileValidator;
import com.inductiveautomation.logixemulator.gateway.web.DeviceFileManager;
import com.inductiveautomation.logixemulator.gateway.web.GatewayAuthHelper;
import com.inductiveautomation.logixemulator.gateway.web.PathSecurity;
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
 * Handles device CRUD operations: list, status, file upload, and file delete.
 */
public class DeviceController {

    private static final Logger logger = LoggerFactory.getLogger(DeviceController.class);

    /**
     * 422 Unprocessable Entity - not defined as a constant on {@link HttpServletResponse}, used
     * (consistent with the existing literal-429 style in {@code handleDeleteFile}) for an upload
     * whose file was saved but whose parse/address-space build then failed (defect B4): the
     * request itself was well-formed, but the file's content could not be applied to the device.
     */
    private static final int SC_UNPROCESSABLE_ENTITY = 422;

    private final DeviceFileManager deviceManager;
    private final DeviceRegistry registry;
    private final RateLimiter rateLimiter;
    private final RateLimiter writeRateLimiter;

    public DeviceController(DeviceFileManager deviceManager, DeviceRegistry registry,
                            RateLimiter rateLimiter, RateLimiter writeRateLimiter) {
        this.deviceManager = deviceManager;
        this.registry = registry;
        this.rateLimiter = rateLimiter;
        this.writeRateLimiter = writeRateLimiter;
    }

    // -------------------------------------------------------------------------
    // Validation helper
    // -------------------------------------------------------------------------

    /**
     * Validates that a device name is safe — alphanumeric, hyphens, underscores, max 100 chars.
     * Returns null if valid; returns an error JSONObject (with resp status set) if invalid.
     *
     * <p>Delegates to the canonical {@link DeviceConfigService#validateDeviceName} so all three
     * controllers share one regex/error-response implementation.</p>
     */
    JSONObject validateDeviceName(String deviceName, HttpServletResponse resp) throws JSONException {
        return DeviceConfigService.validateDeviceName(deviceName, resp);
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    public JSONObject handleFileUpload(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        try { if (!GatewayAuthHelper.requireAuthentication(ctx, resp)) return null; }
        catch (Exception e) { resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); return null; }

        JSONObject result = new JSONObject();

        try {
            if (!GatewayAuthHelper.requireCSRFToken(ctx, resp)) return null;
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return result.put("success", false).put("error", "CSRF validation failed");
        }

        String username = GatewayAuthHelper.getUsername(ctx);
        String ip = GatewayAuthHelper.getClientIP(ctx);
        RateLimiter.RateLimitResult rateResult = rateLimiter.checkRequest(username, ip);

        if (!rateResult.isAllowed()) {
            return GatewayAuthHelper.rateLimitResponse(resp, result, rateResult, username, ip);
        }

        long contentLength = ctx.getRequest().getContentLengthLong();
        long maxSize = FileValidator.getMaxFileSizeMB() * 1024 * 1024;

        if (contentLength > maxSize) {
            resp.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return result.put("success", false)
                .put("error", String.format("File too large: %d MB exceeds max %d MB",
                    contentLength / (1024 * 1024), FileValidator.getMaxFileSizeMB()));
        }

        try {
            String fileContent = GatewayAuthHelper.readRequestContent(ctx, maxSize);
            if (fileContent.isEmpty()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return result.put("success", false).put("error", "No file content provided");
            }

            String filename = Optional.ofNullable(ctx.getRequest().getHeader("X-Filename"))
                .filter(s -> !s.isEmpty())
                .map(PathSecurity::sanitizeFileName)
                .orElse("uploaded_file.txt");

            FileValidator.ValidationResult validation = FileValidator.validateContent(fileContent, filename);
            if (!validation.isValid()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return result.put("success", false).put("error", validation.getErrorMessage());
            }

            String deviceName = ctx.getRequest().getParameter("device");
            if (deviceName != null && !deviceName.trim().isEmpty()) {
                JSONObject nameError = validateDeviceName(deviceName, resp);
                if (nameError != null) return nameError;
                return processDeviceUpload(resp, result, deviceName, fileContent, filename);
            }

            return result.put("success", true).put("filename", filename)
                .put("size", fileContent.length()).put("content", fileContent)
                .put("message", "File uploaded - apply to device by specifying device parameter");

        } catch (Exception e) {
            logger.error("Error handling file upload", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return result.put("success", false).put("error", "An internal error occurred during file upload");
        }
    }

    JSONObject processDeviceUpload(HttpServletResponse resp, JSONObject result,
                                    String deviceName, String fileContent, String filename)
            throws JSONException {
        Optional<LogixEmulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
        if (deviceOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Device not found: " + deviceName)
                .put("hint", "Create the device in Config → OPC UA → Device Connections first");
        }

        LogixEmulatorDevice device = deviceOpt.get();
        if (!deviceManager.saveFileToDevice(device, fileContent, filename)) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return result.put("success", false).put("error", "Failed to update device configuration");
        }

        // File is saved; now attempt to apply it (parse + address-space build). This can fail
        // even though the file was saved fine, in which case reloadDevice() records the failure
        // on the device's status rather than throwing (defect B4 - a caller must not be told the
        // upload succeeded when the tag tree never actually built).
        deviceManager.reloadDevice(device);
        String status = device.getStatus();

        if (isBuildFailureStatus(status)) {
            resp.setStatus(SC_UNPROCESSABLE_ENTITY);
            return result.put("success", false).put("filename", filename)
                .put("size", fileContent.length()).put("device", deviceName)
                .put("error", "File saved but failed to apply to device: " + status)
                .put("status", status);
        }

        // Defect B5: version the file only now that it is confirmed to have actually parsed and
        // built — a failed upload (the branch above) must not consume one of the 5 retained
        // slots. This is the only place on the REST upload path where success is known; the old
        // FilePreparation.saveVersion() call is config-content-only and is never reached here.
        saveUploadVersion(device, deviceName);

        return result.put("success", true).put("filename", filename)
            .put("size", fileContent.length()).put("device", deviceName)
            .put("message", "File uploaded and applied to device successfully")
            .put("status", status);
    }

    /**
     * Snapshot the device's current on-disk file as a new version (defect B5). Best-effort: a
     * failure here only means the retention history is thinner than it should be, not that the
     * upload itself failed, so it is logged rather than turned into an error response.
     */
    private void saveUploadVersion(LogixEmulatorDevice device, String deviceName) {
        String filePath = deviceManager.getDeviceFilePath(device);
        if (filePath == null || filePath.isEmpty()) {
            return;
        }

        File uploadedFile = new File(filePath);
        FileVersionManager versionManager = deviceManager.getVersionManager(device);
        if (!versionManager.saveVersion(uploadedFile, uploadedFile.getName())) {
            logger.warn("Failed to save file version for device {} after successful upload",
                GatewayAuthHelper.sanitizeForLog(deviceName));
        }
    }

    /**
     * True when {@code status} (the device's {@code getStatus()} immediately after a reload
     * attempt) indicates the parse/address-space build actually failed, rather than completing
     * successfully (defect B4). The device's hot-reload pipeline ({@code HotReloadCoordinator} /
     * {@code LogixEmulatorDevice.onStartup}) always prefixes a build-failure status with
     * {@code "Error"} - that convention is the load-bearing signal checked here.
     */
    static boolean isBuildFailureStatus(String status) {
        return status != null && status.startsWith("Error");
    }

    public JSONObject handleListDevices(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        try { if (!GatewayAuthHelper.requireAuthentication(ctx, resp)) return null; }
        catch (Exception e) { resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); return null; }

        JSONObject result = new JSONObject();
        JSONArray devices = new JSONArray();

        for (LogixEmulatorDevice device : registry.getRegisteredDevices()) {
            LogixEmulatorConfig config = device.getConfiguration();
            devices.put(new JSONObject()
                .put("name", device.getName())
                .put("status", device.getStatus())
                .put("enabled", config.general().enabled())
                .put("fileName", config.parser().fileName())
                .put("parserType", config.parser().parserType().getDisplayName())
                .put("simulationEnabled", config.simulation().enabled()));
        }

        return result.put("success", true).put("devices", devices).put("count", devices.length());
    }

    public JSONObject handleDeviceStatus(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        try { if (!GatewayAuthHelper.requireAuthentication(ctx, resp)) return null; }
        catch (Exception e) { resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); return null; }

        JSONObject result = new JSONObject();
        String deviceName = ctx.getParameter("name");

        JSONObject nameError = validateDeviceName(deviceName, resp);
        if (nameError != null) return nameError;

        Optional<LogixEmulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
        if (deviceOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Device not found: " + deviceName);
        }

        LogixEmulatorDevice device = deviceOpt.get();
        LogixEmulatorConfig config = device.getConfiguration();

        String filePath = deviceManager.getDeviceFilePath(device);
        File file = filePath != null ? new File(filePath) : null;
        boolean hasFile = file != null && file.exists();

        return result.put("success", true)
            .put("deviceName", deviceName)
            .put("status", device.getStatus())
            .put("fileName", config.parser().fileName())
            .put("hasFile", hasFile)
            .put("fileSize", hasFile ? file.length() : 0)
            .put("lastModified", hasFile ? file.lastModified() : 0)
            .put("filePath", hasFile ? file.getName() : null)
            .put("parserType", config.parser().parserType().getDisplayName())
            .put("enabled", config.general().enabled())
            .put("simulationEnabled", config.simulation().enabled());
    }

    public JSONObject handleDeleteFile(RequestContext ctx, HttpServletResponse resp) throws JSONException {
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
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "No file configured for device: " + deviceName);
        }

        try {
            File deviceFile = new File(filePath);
            String fileName = deviceFile.getName();

            File storageDir = deviceManager.getStorageDirectory();
            String canonicalFilePath = deviceFile.getCanonicalPath();
            String canonicalStorageDir = storageDir.getCanonicalPath();
            if (!canonicalFilePath.startsWith(canonicalStorageDir + File.separator)) {
                logger.error("Security violation: attempt to delete file outside storage directory: {}", filePath);
                resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return result.put("success", false).put("error", "Invalid file path");
            }

            if (deviceFile.exists() && deviceFile.delete()) {
                logger.info("Deleted file for device {}: {}", GatewayAuthHelper.sanitizeForLog(deviceName), deviceFile.getAbsolutePath());
                deviceManager.clearDeviceFile(device);
                return result.put("success", true).put("message", "File deleted successfully").put("fileName", fileName);
            }
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "File not found on disk: " + fileName);
        } catch (SecurityException se) {
            logger.error("Security violation: attempt to delete file outside allowed directory", se);
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return result.put("success", false).put("error", "Invalid file path");
        } catch (Exception e) {
            logger.error("Error deleting file for device {}", GatewayAuthHelper.sanitizeForLog(deviceName), e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return result.put("success", false).put("error", "An internal error occurred while deleting file");
        }
    }
}
