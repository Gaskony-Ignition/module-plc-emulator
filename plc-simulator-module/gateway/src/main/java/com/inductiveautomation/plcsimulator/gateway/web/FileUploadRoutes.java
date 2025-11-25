package com.inductiveautomation.plcsimulator.gateway.web;

import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteAccess;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.plcsimulator.gateway.SimulatorModuleHook;
import com.inductiveautomation.plcsimulator.gateway.device.EnhancedSimulatorConfig;
import com.inductiveautomation.plcsimulator.gateway.device.EnhancedSimulatorDevice;
import com.inductiveautomation.plcsimulator.gateway.validation.FileValidator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Routes for handling PLC file uploads in the Enhanced Simulator device configuration.
 * Provides endpoints for uploading PLC files (L5K, JSON, CSV, XML) via browser.
 */
public class FileUploadRoutes {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadRoutes.class);

    private final GatewayContext context;
    private final RouteGroup routes;
    private final RateLimiter rateLimiter;
    private final DeviceFileManager deviceManager;

    public FileUploadRoutes(GatewayContext context, RouteGroup routes) {
        this.context = context;
        this.routes = routes;
        this.rateLimiter = new RateLimiter();
        this.deviceManager = new DeviceFileManager(context);
    }

    public void mountRoutes() {
        mountRoute("/upload", this::handleFileUpload, HttpMethod.POST, true);
        mountRoute("/devices", this::handleListDevices, null, true);
        mountRoute("/device/:name/status", this::handleDeviceStatus, null, true);
        mountRoute("/device/:name/tags", this::handleGetTags, null, true);
        mountRoute("/device/:name/delete", this::handleDeleteFile, HttpMethod.DELETE, true);
        mountRoute("/health", this::handleHealthCheck, null, false);
        mountRoute("/auth/status", this::handleAuthStatus, null, false);
        mountRoute("/page", this::handleUploadPage, null, true);
        logger.info("File upload routes mounted at /data/plcsimulator/");
    }

    private void mountRoute(String path, RouteHandler handler, HttpMethod method, boolean requiresAuth) {
        try {
            var builder = routes.newRoute(path).handler(handler::handle);
            if (method != null) builder.method(method);
            builder.accessControl(requiresAuth ? AuthenticationHelper::checkAuthenticated : req -> RouteAccess.GRANTED);
            builder.mount();
            logger.debug("Mounted route: {}", path);
        } catch (Exception e) {
            logger.error("Failed to mount route: {}", path, e);
        }
    }

    @FunctionalInterface
    private interface RouteHandler {
        Object handle(RequestContext ctx, HttpServletResponse resp) throws JSONException;
    }

    private JSONObject handleFileUpload(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        JSONObject result = new JSONObject();

        // Rate limiting
        String username = AuthenticationHelper.getUserIdentifier(ctx);
        String ip = AuthenticationHelper.getClientIP(ctx);
        RateLimiter.RateLimitResult rateResult = rateLimiter.checkRequest(username, ip);

        if (!rateResult.isAllowed()) {
            return rateLimitResponse(resp, result, rateResult, username, ip);
        }

        // Content size check
        long contentLength = ctx.getRequest().getContentLengthLong();
        long maxSize = FileValidator.getMaxFileSizeMB() * 1024 * 1024;

        if (contentLength > maxSize) {
            resp.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return result.put("success", false)
                .put("error", String.format("File too large: %d MB exceeds max %d MB",
                    contentLength / (1024 * 1024), FileValidator.getMaxFileSizeMB()));
        }

        try {
            String fileContent = readRequestContent(ctx, maxSize);
            if (fileContent.isEmpty()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return result.put("success", false).put("error", "No file content provided");
            }

            String filename = Optional.ofNullable(ctx.getRequest().getHeader("X-Filename"))
                .filter(s -> !s.isEmpty()).orElse("uploaded_file.txt");

            FileValidator.ValidationResult validation = FileValidator.validateContent(fileContent, filename);
            if (!validation.isValid()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return result.put("success", false).put("error", validation.getErrorMessage());
            }

            String deviceName = ctx.getRequest().getParameter("device");
            if (deviceName != null && !deviceName.trim().isEmpty()) {
                return processDeviceUpload(resp, result, deviceName, fileContent, filename);
            }

            return result.put("success", true).put("filename", filename)
                .put("size", fileContent.length()).put("content", fileContent)
                .put("message", "File uploaded - apply to device by specifying device parameter");

        } catch (Exception e) {
            logger.error("Error handling file upload", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return result.put("success", false).put("error", e.getMessage());
        }
    }

    private JSONObject processDeviceUpload(HttpServletResponse resp, JSONObject result,
                                            String deviceName, String fileContent, String filename) throws JSONException {
        Optional<EnhancedSimulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
        if (deviceOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Device not found: " + deviceName)
                .put("hint", "Create the device in Config → OPC UA → Device Connections first");
        }

        EnhancedSimulatorDevice device = deviceOpt.get();
        if (deviceManager.saveFileToDevice(device, fileContent, filename)) {
            deviceManager.reloadDevice(device);
            return result.put("success", true).put("filename", filename)
                .put("size", fileContent.length()).put("device", deviceName)
                .put("message", "File uploaded and applied to device successfully")
                .put("status", device.getStatus());
        }

        resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        return result.put("success", false).put("error", "Failed to update device configuration");
    }

    private JSONObject handleListDevices(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        JSONObject result = new JSONObject();
        JSONArray devices = new JSONArray();

        for (EnhancedSimulatorDevice device : SimulatorModuleHook.getRegisteredDevices()) {
            EnhancedSimulatorConfig config = device.getConfiguration();
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

    private JSONObject handleDeviceStatus(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        JSONObject result = new JSONObject();
        String deviceName = ctx.getParameter("name");

        if (deviceName == null || deviceName.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return result.put("success", false).put("error", "Device name required");
        }

        Optional<EnhancedSimulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
        if (deviceOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Device not found: " + deviceName);
        }

        EnhancedSimulatorDevice device = deviceOpt.get();
        EnhancedSimulatorConfig config = device.getConfiguration();

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
            .put("filePath", filePath)
            .put("parserType", config.parser().parserType().getDisplayName())
            .put("enabled", config.general().enabled())
            .put("simulationEnabled", config.simulation().enabled());
    }

    private JSONObject handleGetTags(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        JSONObject result = new JSONObject();
        String deviceName = ctx.getParameter("name");

        if (deviceName == null || deviceName.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return result.put("success", false).put("error", "Device name required");
        }

        Optional<EnhancedSimulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
        if (deviceOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Device not found: " + deviceName);
        }

        // Get tags from the device's parsed data
        EnhancedSimulatorDevice device = deviceOpt.get();
        JSONArray tags = new JSONArray();
        int folderCount = 0;
        int udtCount = 0;

        try {
            var parsedDataField = EnhancedSimulatorDevice.class.getDeclaredField("parsedData");
            parsedDataField.setAccessible(true);
            var parsedData = (com.google.gson.JsonObject) parsedDataField.get(device);

            if (parsedData != null) {
                // Extract global tags
                if (parsedData.has("global_tags")) {
                    var globalTags = parsedData.getAsJsonArray("global_tags");
                    for (var elem : globalTags) {
                        var tag = elem.getAsJsonObject();
                        JSONObject tagJson = new JSONObject();
                        tagJson.put("name", tag.has("name") ? tag.get("name").getAsString() : "unknown");
                        tagJson.put("path", "Controller:Global/" + (tag.has("name") ? tag.get("name").getAsString() : "unknown"));
                        tagJson.put("data_type", tag.has("data_type") ? tag.get("data_type").getAsString() : "STRING");
                        tagJson.put("value", tag.has("value") ? tag.get("value").toString() : "");

                        if (tag.has("udt_members")) {
                            tagJson.put("isUdt", true);
                            udtCount++;
                        }

                        tags.put(tagJson);
                    }
                    folderCount++; // Controller:Global folder
                }

                // Extract program tags
                if (parsedData.has("programs")) {
                    var programs = parsedData.getAsJsonArray("programs");
                    for (var progElem : programs) {
                        var prog = progElem.getAsJsonObject();
                        String progName = prog.has("name") ? prog.get("name").getAsString() : "Program";
                        folderCount++; // Program folder

                        if (prog.has("tags")) {
                            var progTags = prog.getAsJsonArray("tags");
                            for (var tagElem : progTags) {
                                var tag = tagElem.getAsJsonObject();
                                JSONObject tagJson = new JSONObject();
                                tagJson.put("name", tag.has("name") ? tag.get("name").getAsString() : "unknown");
                                tagJson.put("path", "Programs/" + progName + "/" + (tag.has("name") ? tag.get("name").getAsString() : "unknown"));
                                tagJson.put("data_type", tag.has("data_type") ? tag.get("data_type").getAsString() : "STRING");
                                tagJson.put("value", tag.has("value") ? tag.get("value").toString() : "");
                                tags.put(tagJson);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not access parsed data for device: {}", deviceName, e);
        }

        return result.put("success", true)
            .put("deviceName", deviceName)
            .put("tags", tags)
            .put("totalTags", tags.length())
            .put("folders", folderCount)
            .put("udtInstances", udtCount);
    }

    private JSONObject handleDeleteFile(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        JSONObject result = new JSONObject();
        String deviceName = ctx.getParameter("name");

        if (deviceName == null || deviceName.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return result.put("success", false).put("error", "Device name required");
        }

        Optional<EnhancedSimulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
        if (deviceOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Device not found: " + deviceName);
        }

        String fileName = deviceOpt.get().getConfiguration().parser().fileName();
        if (fileName == null || fileName.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "No file configured for device: " + deviceName);
        }

        try {
            File deviceFile = PathSecurity.validateFilePath(deviceManager.getStorageDirectory(), deviceName, fileName);
            if (deviceFile.exists() && deviceFile.delete()) {
                logger.info("Deleted file for device {}: {}", deviceName, deviceFile.getAbsolutePath());
                return result.put("success", true).put("message", "File deleted successfully").put("fileName", fileName);
            }
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "File not found on disk: " + fileName);
        } catch (SecurityException se) {
            logger.error("Security violation: attempt to delete file outside allowed directory", se);
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return result.put("success", false).put("error", "Invalid file path");
        }
    }

    private JSONObject handleHealthCheck(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        return new JSONObject().put("status", "ok").put("service", "plc-file-upload");
    }

    private JSONObject handleAuthStatus(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        RouteAccess access = AuthenticationHelper.checkAuthenticated(ctx);
        boolean isAuth = (access == RouteAccess.GRANTED);
        String username = isAuth ? getUsername(ctx) : "";
        return new JSONObject().put("authenticated", isAuth).put("username", username).put("loginUrl", "/app/home");
    }

    private Object handleUploadPage(RequestContext ctx, HttpServletResponse resp) {
        try {
            var stream = getClass().getResourceAsStream("/mounted/simple-upload.html");
            if (stream == null) {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                resp.getWriter().write("Upload page not found");
                return null;
            }
            String html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(html);
        } catch (Exception e) {
            logger.error("Error serving upload page", e);
        }
        return null;
    }

    private String readRequestContent(RequestContext ctx, long maxSize) throws Exception {
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

    private JSONObject rateLimitResponse(HttpServletResponse resp, JSONObject result,
                                          RateLimiter.RateLimitResult rateResult, String user, String ip) throws JSONException {
        resp.setStatus(429);
        resp.setHeader("X-RateLimit-Limit", String.valueOf(rateResult.getLimit()));
        resp.setHeader("X-RateLimit-Remaining", "0");
        resp.setHeader("X-RateLimit-Reset", String.valueOf(rateResult.getResetTimeMs()));
        long retryAfter = (rateResult.getResetTimeMs() - System.currentTimeMillis()) / 1000;
        resp.setHeader("Retry-After", String.valueOf(retryAfter));
        logger.warn("Rate limit exceeded for user {} from IP {}", user, ip);
        return result.put("success", false)
            .put("error", String.format("Rate limit exceeded: %s limit of %d uploads per hour",
                rateResult.getLimitType(), rateResult.getLimit()))
            .put("retryAfter", retryAfter);
    }

    private String getUsername(RequestContext ctx) {
        var req = ctx.getRequest();
        if (req.getRemoteUser() != null) return req.getRemoteUser();
        if (req.getUserPrincipal() != null) return req.getUserPrincipal().getName();
        return "gateway-user";
    }
}
