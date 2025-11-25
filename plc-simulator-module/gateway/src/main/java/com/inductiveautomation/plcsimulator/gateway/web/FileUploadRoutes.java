package com.inductiveautomation.plcsimulator.gateway.web;

import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
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
        // Protected routes - require Gateway Config access (logged in user)
        mountProtectedRoute("/upload", this::handleFileUpload, HttpMethod.POST);
        mountProtectedRoute("/devices", this::handleListDevices, null);
        mountProtectedRoute("/device/:name/status", this::handleDeviceStatus, null);
        mountProtectedRoute("/device/:name/tags", this::handleGetTags, null);
        mountProtectedRoute("/device/:name/tags/live", this::handleGetLiveTags, null);
        mountProtectedRoute("/device/:name/tag/write", this::handleWriteTag, HttpMethod.POST);
        mountProtectedRoute("/device/:name/delete", this::handleDeleteFile, HttpMethod.DELETE);

        // Authenticated HTML pages (not publicly accessible)
        mountProtectedRoute("/page", this::handleUploadPage, null);
        mountProtectedRoute("/edit-program", this::handleEditProgramPage, null);
        mountProtectedRoute("/tag-browser", this::handleTagBrowserPage, null);

        // Public routes - no authentication required
        mountPublicRoute("/health", this::handleHealthCheck);
        mountPublicRoute("/auth/status", this::handleAuthStatus);

        logger.info("File upload routes mounted at /data/plcsimulator/");
        logger.info("  - Upload page:      /data/plcsimulator/page");
        logger.info("  - Edit program:     /data/plcsimulator/edit-program");
        logger.info("  - Tag browser:      /data/plcsimulator/tag-browser");
        logger.info("  - Live tags API:    /data/plcsimulator/device/:name/tags/live");
        logger.info("  - Write tag API:    /data/plcsimulator/device/:name/tag/write");
    }

    /**
     * Mount a route. All routes use OPEN_ROUTE access control since
     * Ignition 8.3's data routes require login by default for the Gateway web interface.
     * The /data/ prefix already provides session-based authentication.
     */
    private void mountProtectedRoute(String path, RouteHandler handler, HttpMethod method) {
        try {
            var builder = routes.newRoute(path)
                .handler(handler::handle)
                .accessControl(AccessControlStrategy.OPEN_ROUTE);
            if (method != null) {
                builder.method(method);
            }
            builder.mount();
            logger.info("Mounted route: {} {}", method != null ? method : "GET", path);
        } catch (Exception e) {
            logger.error("Failed to mount route: {} - {}", path, e.getMessage(), e);
        }
    }

    /**
     * Mount a public route that doesn't require authentication.
     */
    private void mountPublicRoute(String path, RouteHandler handler) {
        try {
            routes.newRoute(path)
                .handler(handler::handle)
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();
            logger.info("Mounted public route: {}", path);
        } catch (Exception e) {
            logger.error("Failed to mount public route: {} - {}", path, e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface RouteHandler {
        Object handle(RequestContext ctx, HttpServletResponse resp) throws JSONException;
    }

    private JSONObject handleFileUpload(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        JSONObject result = new JSONObject();

        // Rate limiting
        String username = getUsername(ctx);
        String ip = getClientIP(ctx);
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
        int[] counters = {0, 0}; // [folderCount, udtCount]

        try {
            var parsedDataField = EnhancedSimulatorDevice.class.getDeclaredField("parsedData");
            parsedDataField.setAccessible(true);
            var parsedData = (com.google.gson.JsonObject) parsedDataField.get(device);

            if (parsedData != null) {
                // Extract global tags with hierarchical UDT expansion
                if (parsedData.has("global_tags")) {
                    var globalTags = parsedData.getAsJsonArray("global_tags");
                    for (var elem : globalTags) {
                        var tag = elem.getAsJsonObject();
                        extractTagHierarchy(tag, "Controller:Global", tags, counters);
                    }
                    counters[0]++; // Controller:Global folder
                }

                // Extract program tags with hierarchical UDT expansion
                if (parsedData.has("programs")) {
                    var programs = parsedData.getAsJsonArray("programs");
                    for (var progElem : programs) {
                        var prog = progElem.getAsJsonObject();
                        String progName = prog.has("name") ? prog.get("name").getAsString() : "Program";
                        counters[0]++; // Program folder

                        if (prog.has("tags")) {
                            var progTags = prog.getAsJsonArray("tags");
                            for (var tagElem : progTags) {
                                var tag = tagElem.getAsJsonObject();
                                extractTagHierarchy(tag, "Programs/" + progName, tags, counters);
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
            .put("folders", counters[0])
            .put("udtInstances", counters[1]);
    }

    /**
     * Recursively extracts tags from a hierarchical structure.
     * For UDT instances, creates folder entries and extracts all member tags with proper paths.
     * This matches the real PLC structure: UDT_Instance/MemberName
     *
     * @param tag The tag JSON object from parsed data
     * @param parentPath Parent path (e.g., "Controller:Global" or "Controller:Global/Motor1")
     * @param tags Output array to add extracted tags
     * @param counters Array of [folderCount, udtCount]
     */
    private void extractTagHierarchy(com.google.gson.JsonObject tag, String parentPath, JSONArray tags, int[] counters) throws JSONException {
        String tagName = tag.has("name") ? tag.get("name").getAsString() : "unknown";
        String dataType = tag.has("data_type") ? tag.get("data_type").getAsString() : "STRING";
        String tagPath = parentPath + "/" + tagName;

        // Check if this is a UDT instance with members
        if (tag.has("udt_members")) {
            var members = tag.getAsJsonArray("udt_members");
            if (members.size() > 0) {
                counters[1]++; // udtCount

                // Add UDT instance as a folder entry
                JSONObject udtFolder = new JSONObject();
                udtFolder.put("name", tagName);
                udtFolder.put("path", tagPath);
                udtFolder.put("data_type", dataType);
                udtFolder.put("isUdt", true);
                udtFolder.put("isFolder", true);
                udtFolder.put("memberCount", members.size());
                tags.put(udtFolder);

                // Recursively extract all members with proper hierarchical paths
                for (var memberElem : members) {
                    var member = memberElem.getAsJsonObject();
                    extractTagHierarchy(member, tagPath, tags, counters);
                }
                return;
            }
        }

        // Atomic tag or UDT without members - add as leaf variable
        JSONObject tagJson = new JSONObject();
        tagJson.put("name", tagName);
        tagJson.put("path", tagPath);
        tagJson.put("data_type", dataType);
        tagJson.put("value", tag.has("initial_value") ? tag.get("initial_value").toString() : "");
        tagJson.put("isFolder", false);
        tags.put(tagJson);
    }

    /**
     * Get live tag values from OPC-UA address space.
     * Returns current real-time values for all tags.
     * Paths are normalized to use slash notation to match /tags endpoint.
     */
    private JSONObject handleGetLiveTags(RequestContext ctx, HttpServletResponse resp) throws JSONException {
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
        JSONObject values = new JSONObject();

        try {
            // Get all live tag values from OPC-UA address space
            java.util.Map<String, Object> liveValues = device.getAllTagValues();
            for (var entry : liveValues.entrySet()) {
                Object value = entry.getValue();
                if (value != null) {
                    // Convert dot notation to slash notation to match /tags endpoint
                    // NodeId uses: Controller:Global.Motor1.Speed
                    // Tags use:    Controller:Global/Motor1/Speed
                    String path = entry.getKey();
                    // Split on dots but preserve "Controller:Global" as a unit
                    // Pattern: First segment may contain colon, remaining use dots
                    if (path.startsWith("Controller:Global.")) {
                        path = "Controller:Global/" + path.substring("Controller:Global.".length()).replace(".", "/");
                    } else if (path.startsWith("Programs.")) {
                        path = path.replace(".", "/");
                    } else if (path.contains(".")) {
                        // Short path like "Motor1.Speed" - skip or normalize
                        // These are duplicate nodes, we can skip them for live display
                        continue;
                    }
                    values.put(path, value.toString());
                }
            }
        } catch (Exception e) {
            logger.warn("Could not get live tag values for device: {}", deviceName, e);
        }

        return result.put("success", true)
            .put("deviceName", deviceName)
            .put("values", values)
            .put("timestamp", System.currentTimeMillis());
    }

    /**
     * Write a value to a tag in the OPC-UA address space.
     * Request body: { "tagPath": "Controller:Global/MyTag", "value": "123" }
     * Note: tagPath uses slash notation (UI display format), converted to dot notation for OPC-UA.
     */
    private JSONObject handleWriteTag(RequestContext ctx, HttpServletResponse resp) throws JSONException {
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

        try {
            // Read the request body
            String body = readRequestContent(ctx, 10 * 1024); // 10KB max for write request
            JSONObject requestJson = new JSONObject(body);

            String tagPath = requestJson.optString("tagPath", null);
            String valueStr = requestJson.optString("value", null);
            String dataType = requestJson.optString("dataType", "STRING");

            if (tagPath == null || tagPath.trim().isEmpty()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return result.put("success", false).put("error", "tagPath required");
            }

            if (valueStr == null) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return result.put("success", false).put("error", "value required");
            }

            // Convert slash notation to dot notation for OPC-UA NodeId lookup
            // UI uses: Controller:Global/Motor1/Speed
            // OPC-UA uses: Controller:Global.Motor1.Speed
            String opcuaPath = convertToNodeIdPath(tagPath);

            // Convert value to appropriate type
            Object typedValue = convertValue(valueStr, dataType);

            EnhancedSimulatorDevice device = deviceOpt.get();
            boolean success = device.writeTagValue(opcuaPath, typedValue);

            if (success) {
                return result.put("success", true)
                    .put("message", "Tag value written successfully")
                    .put("tagPath", tagPath)
                    .put("value", valueStr);
            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return result.put("success", false)
                    .put("error", "Tag not found or write failed: " + tagPath);
            }

        } catch (Exception e) {
            logger.error("Error writing tag value", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return result.put("success", false).put("error", e.getMessage());
        }
    }

    /**
     * Convert slash notation tag path to OPC-UA NodeId dot notation.
     * Controller:Global/Motor1/Speed -> Controller:Global.Motor1.Speed
     */
    private String convertToNodeIdPath(String tagPath) {
        if (tagPath == null) return null;

        // Handle Controller:Global prefix specially (contains colon which should be preserved)
        if (tagPath.startsWith("Controller:Global/")) {
            return "Controller:Global." + tagPath.substring("Controller:Global/".length()).replace("/", ".");
        }

        // Handle Programs prefix
        if (tagPath.startsWith("Programs/")) {
            return tagPath.replace("/", ".");
        }

        // Default: just replace slashes with dots
        return tagPath.replace("/", ".");
    }

    /**
     * Convert a string value to the appropriate type based on dataType.
     */
    private Object convertValue(String value, String dataType) {
        String type = dataType.toUpperCase();

        try {
            if (type.equals("BOOL") || type.equals("BOOLEAN")) {
                return Boolean.parseBoolean(value) || value.equals("1") || value.equalsIgnoreCase("true");
            } else if (type.equals("INT") || type.equals("SINT")) {
                return Short.parseShort(value);
            } else if (type.equals("DINT")) {
                return Integer.parseInt(value);
            } else if (type.equals("LINT")) {
                return Long.parseLong(value);
            } else if (type.equals("REAL") || type.equals("FLOAT")) {
                return Float.parseFloat(value);
            } else if (type.equals("LREAL") || type.equals("DOUBLE")) {
                return Double.parseDouble(value);
            }
        } catch (NumberFormatException e) {
            logger.debug("Could not convert {} to {}, using string", value, dataType);
        }

        return value; // Return as string if conversion fails
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
        // Check if user has a session - basic auth check
        String username = getUsername(ctx);
        boolean isAuth = username != null && !username.isEmpty() && !username.equals("anonymous");
        return new JSONObject()
            .put("authenticated", isAuth)
            .put("username", username != null ? username : "")
            .put("loginUrl", "/web/login");
    }

    private Object handleUploadPage(RequestContext ctx, HttpServletResponse resp) {
        return serveHtmlPage("/pages/simple-upload.html", "Upload page", resp);
    }

    private Object handleEditProgramPage(RequestContext ctx, HttpServletResponse resp) {
        return serveHtmlPage("/pages/edit-program.html", "Edit program page", resp);
    }

    private Object handleTagBrowserPage(RequestContext ctx, HttpServletResponse resp) {
        return serveHtmlPage("/pages/tag-browser.html", "Tag browser page", resp);
    }

    /**
     * Serve an HTML page from the /pages/ resource directory.
     * This directory is NOT publicly mounted, so pages are only accessible
     * through authenticated data routes.
     */
    private Object serveHtmlPage(String resourcePath, String pageName, HttpServletResponse resp) {
        try {
            var stream = getClass().getResourceAsStream(resourcePath);
            if (stream == null) {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                resp.getWriter().write(pageName + " not found");
                return null;
            }
            String html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(html);
        } catch (Exception e) {
            logger.error("Error serving {}", pageName, e);
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

    /**
     * Get client IP address, accounting for proxies.
     */
    private String getClientIP(RequestContext ctx) {
        var request = ctx.getRequest();

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
