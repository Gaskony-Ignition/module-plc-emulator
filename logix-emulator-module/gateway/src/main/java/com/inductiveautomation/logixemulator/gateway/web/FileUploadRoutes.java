package com.inductiveautomation.logixemulator.gateway.web;

import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.logixemulator.gateway.SimulatorModuleHook;
import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorConfig;
import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorDevice;
import com.inductiveautomation.logixemulator.gateway.validation.FileValidator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Routes for handling PLC file uploads in the Logix Emulator device configuration.
 * Provides endpoints for uploading PLC files (L5K, JSON, CSV, XML) via browser.
 */
public class FileUploadRoutes {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadRoutes.class);

    private final GatewayContext context;
    private final RouteGroup routes;
    private final RateLimiter rateLimiter;
    private final RateLimiter readRateLimiter;
    private final DeviceFileManager deviceManager;

    public FileUploadRoutes(GatewayContext context, RouteGroup routes) {
        this.context = context;
        this.routes = routes;
        this.rateLimiter = new RateLimiter();
        this.readRateLimiter = new RateLimiter(300, 3000, java.util.concurrent.TimeUnit.HOURS.toMillis(1));
        this.deviceManager = new DeviceFileManager(context);
    }

    /**
     * Validates CSRF protection on state-changing requests.
     * Requires X-Requested-With header to prevent cross-origin form submissions.
     */
    private boolean requireCSRFToken(RequestContext ctx, HttpServletResponse resp) throws JSONException, IOException {
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

    public void mountRoutes() {
        // API routes — authentication is handled by Ignition's /data/ route infrastructure
        mountRoute("/upload", this::handleFileUpload, HttpMethod.POST);
        mountRoute("/devices", this::handleListDevices, null);
        mountRoute("/device/:name/status", this::handleDeviceStatus, null);
        mountRoute("/device/:name/tags", this::handleGetTags, null);
        mountRoute("/device/:name/tags/children", this::handleGetTagChildren, null);
        mountRoute("/device/:name/tags/live", this::handleGetLiveTags, null);
        mountRoute("/device/:name/tags/simulated", this::handleGetSimulatedTags, null);
        mountRoute("/device/:name/tag/write", this::handleWriteTag, HttpMethod.POST);
        mountRoute("/device/:name/tag/simulate", this::handleToggleTagSimulation, HttpMethod.POST);
        mountRoute("/device/:name/simulation/scope", this::handleBulkSimulationByScope, HttpMethod.POST);
        mountRoute("/device/:name/simulation/all", this::handleBulkSimulationAll, HttpMethod.POST);
        mountRoute("/device/:name/delete", this::handleDeleteFile, HttpMethod.DELETE);

        // HTML page routes
        mountRoute("/connection-browser", this::handleConnectionBrowserPage, null);
        mountRoute("/page", this::handleUploadPage, null);
        mountRoute("/edit-program", this::handleEditProgramPage, null);
        mountRoute("/tag-browser", this::handleTagBrowserPage, null);

        // System routes — authenticated
        mountRoute("/system/stats", this::handleSystemStats, null);
        mountRoute("/system/logs", this::handleSystemLogs, null);

        // Public routes - no authentication required
        mountPublicRoute("/health", this::handleHealthCheck);
        mountPublicRoute("/auth/status", this::handleAuthStatus);
        mountPublicRoute("/auth/check", this::handleAuthCheck);

        logger.info("File upload routes mounted at /data/logixemulator/");
        logger.info("  - Connection browser: /data/logixemulator/connection-browser");
        logger.info("  - Upload page:        /data/logixemulator/page");
        logger.info("  - Edit program:       /data/logixemulator/edit-program");
        logger.info("  - Tag browser:        /data/logixemulator/tag-browser");
        logger.info("  - Live tags API:      /data/logixemulator/device/:name/tags/live");
        logger.info("  - Write tag API:      /data/logixemulator/device/:name/tag/write");
    }

    /**
     * Mount a route. Authentication is handled by Ignition's /data/ route infrastructure.
     */
    private void mountRoute(String path, RouteHandler handler, HttpMethod method) {
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

        // CSRF protection
        try {
            if (!requireCSRFToken(ctx, resp)) return null;
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return result.put("success", false).put("error", "CSRF validation failed");
        }

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
            return result.put("success", false).put("error", "An internal error occurred during file upload");
        }
    }

    private JSONObject processDeviceUpload(HttpServletResponse resp, JSONObject result,
                                            String deviceName, String fileContent, String filename) throws JSONException {
        Optional<LogixEmulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
        if (deviceOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Device not found: " + deviceName)
                .put("hint", "Create the device in Config → OPC UA → Device Connections first");
        }

        LogixEmulatorDevice device = deviceOpt.get();
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

        for (LogixEmulatorDevice device : SimulatorModuleHook.getRegisteredDevices()) {
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

    private JSONObject handleDeviceStatus(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        JSONObject result = new JSONObject();
        String deviceName = ctx.getParameter("name");

        if (deviceName == null || deviceName.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return result.put("success", false).put("error", "Device name required");
        }

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

    /**
     * Get tags from device with pagination support.
     * Query parameters:
     *   - path: Parent path to get children of (default: root)
     *   - depth: How many levels deep to return (default: 1 for lazy loading, -1 for all)
     *   - offset: Pagination offset (default: 0)
     *   - limit: Max items to return (default: 100, max: 500)
     *   - flat: If true, return flat list for virtual scrolling (default: false)
     */
    private JSONObject handleGetTags(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        JSONObject result = new JSONObject();
        String deviceName = ctx.getParameter("name");

        if (deviceName == null || deviceName.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return result.put("success", false).put("error", "Device name required");
        }

        Optional<LogixEmulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
        if (deviceOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Device not found: " + deviceName);
        }

        // Parse pagination parameters
        String parentPath = ctx.getRequest().getParameter("path");
        if (parentPath == null || parentPath.isEmpty()) {
            parentPath = ""; // Root level
        }

        int depth = parseIntParam(ctx, "depth", 1); // Default to 1 level for lazy loading
        int offset = parseIntParam(ctx, "offset", 0);
        int limit = Math.min(parseIntParam(ctx, "limit", 100), 500); // Cap at 500
        boolean flat = "true".equals(ctx.getRequest().getParameter("flat"));

        LogixEmulatorDevice device = deviceOpt.get();

        try {
            var parsedData = device.getParsedData();

            if (parsedData == null) {
                return result.put("success", true)
                    .put("deviceName", deviceName)
                    .put("tags", new JSONArray())
                    .put("totalTags", 0)
                    .put("folders", 0)
                    .put("udtInstances", 0);
            }

            // Build the complete tag structure (cached internally)
            TagTreeBuilder builder = new TagTreeBuilder(parsedData);
            TagTreeBuilder.TagStats stats = builder.getStats();

            JSONArray tags;
            int totalAtLevel;
            boolean hasMore;

            if (flat) {
                // Flat mode for virtual scrolling - return visible items only
                tags = builder.getFlatTags(parentPath, offset, limit);
                totalAtLevel = builder.getTotalFlatCount(parentPath);
                hasMore = (offset + limit) < totalAtLevel;
            } else {
                // Hierarchical mode - return children of path
                tags = builder.getChildrenOf(parentPath, depth, offset, limit);
                totalAtLevel = builder.getChildCount(parentPath);
                hasMore = (offset + limit) < totalAtLevel;
            }

            return result.put("success", true)
                .put("deviceName", deviceName)
                .put("path", parentPath)
                .put("tags", tags)
                .put("offset", offset)
                .put("limit", limit)
                .put("count", tags.length())
                .put("totalAtLevel", totalAtLevel)
                .put("hasMore", hasMore)
                .put("totalTags", stats.totalTags)
                .put("folders", stats.folderCount)
                .put("udtInstances", stats.udtCount);

        } catch (Exception e) {
            logger.warn("Could not access parsed data for device: {}", deviceName, e);
            return result.put("success", false).put("error", "Failed to read tag data");
        }
    }

    /**
     * Get children of a specific folder path (for lazy loading).
     * Query parameters:
     *   - path: Parent path (required)
     *   - offset: Pagination offset (default: 0)
     *   - limit: Max items (default: 100)
     */
    private JSONObject handleGetTagChildren(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        JSONObject result = new JSONObject();
        String deviceName = ctx.getParameter("name");
        String parentPath = ctx.getRequest().getParameter("path");

        if (deviceName == null || deviceName.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return result.put("success", false).put("error", "Device name required");
        }

        if (parentPath == null) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return result.put("success", false).put("error", "Path parameter required");
        }

        Optional<LogixEmulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
        if (deviceOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Device not found: " + deviceName);
        }

        int offset = parseIntParam(ctx, "offset", 0);
        int limit = Math.min(parseIntParam(ctx, "limit", 100), 500);

        LogixEmulatorDevice device = deviceOpt.get();

        try {
            var parsedData = device.getParsedData();

            if (parsedData == null) {
                return result.put("success", true)
                    .put("path", parentPath)
                    .put("children", new JSONArray())
                    .put("totalChildren", 0);
            }

            TagTreeBuilder builder = new TagTreeBuilder(parsedData);
            JSONArray children = builder.getChildrenOf(parentPath, 1, offset, limit);
            int totalChildren = builder.getChildCount(parentPath);

            return result.put("success", true)
                .put("path", parentPath)
                .put("children", children)
                .put("offset", offset)
                .put("limit", limit)
                .put("count", children.length())
                .put("totalChildren", totalChildren)
                .put("hasMore", (offset + limit) < totalChildren);

        } catch (Exception e) {
            logger.warn("Could not get children for path: {} on device: {}", parentPath, deviceName, e);
            return result.put("success", false).put("error", "Failed to read children");
        }
    }

    private int parseIntParam(RequestContext ctx, String name, int defaultValue) {
        String value = ctx.getRequest().getParameter(name);
        if (value != null && !value.isEmpty()) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return defaultValue;
    }

    /**
     * Get live tag values from OPC-UA address space.
     * Returns current real-time values for all tags.
     * Paths are normalized to use slash notation to match /tags endpoint.
     */
    private JSONObject handleGetLiveTags(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        JSONObject result = new JSONObject();

        // Read rate limiting for expensive endpoint
        RateLimiter.RateLimitResult readRate = readRateLimiter.checkRequest(getUsername(ctx), getClientIP(ctx));
        if (!readRate.isAllowed()) { resp.setStatus(429); return result.put("success", false).put("error", "Rate limit exceeded"); }

        String deviceName = ctx.getParameter("name");

        if (deviceName == null || deviceName.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return result.put("success", false).put("error", "Device name required");
        }

        Optional<LogixEmulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
        if (deviceOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Device not found: " + deviceName);
        }

        LogixEmulatorDevice device = deviceOpt.get();
        JSONObject values = new JSONObject();

        try {
            // Get all live tag values from OPC-UA address space
            Map<String, Object> liveValues = device.getAllTagValues();
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

        try { if (!requireCSRFToken(ctx, resp)) return null; }
        catch (Exception e) { resp.setStatus(HttpServletResponse.SC_FORBIDDEN); return result.put("success", false).put("error", "CSRF validation failed"); }

        String deviceName = ctx.getParameter("name");

        if (deviceName == null || deviceName.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return result.put("success", false).put("error", "Device name required");
        }

        Optional<LogixEmulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
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

            LogixEmulatorDevice device = deviceOpt.get();
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
            return result.put("success", false).put("error", "An internal error occurred while writing tag value");
        }
    }

    /**
     * Toggle simulation for a specific tag.
     * Request body: { "tagPath": "Controller:Global/MyTag", "enabled": true, "pattern": "sine" }
     * If enabled is not specified, it toggles the current state.
     * Pattern is optional: sine, ramp, random, toggle, static
     */
    private JSONObject handleToggleTagSimulation(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        JSONObject result = new JSONObject();

        try { if (!requireCSRFToken(ctx, resp)) return null; }
        catch (Exception e) { resp.setStatus(HttpServletResponse.SC_FORBIDDEN); return result.put("success", false).put("error", "CSRF validation failed"); }

        String deviceName = ctx.getParameter("name");

        if (deviceName == null || deviceName.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return result.put("success", false).put("error", "Device name required");
        }

        Optional<LogixEmulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
        if (deviceOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Device not found: " + deviceName);
        }

        LogixEmulatorDevice device = deviceOpt.get();

        // Check if simulation engine is available
        if (!device.isSimulationEngineAvailable()) {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return result.put("success", false)
                .put("error", "Simulation engine not available. Enable simulation in device settings first.");
        }

        try {
            // Read the request body
            String body = readRequestContent(ctx, 10 * 1024); // 10KB max
            JSONObject requestJson = new JSONObject(body);

            String tagPath = requestJson.optString("tagPath", null);
            if (tagPath == null || tagPath.trim().isEmpty()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return result.put("success", false).put("error", "tagPath required");
            }

            String pattern = requestJson.optString("pattern", null);
            boolean enabled;

            // Check if enabled is explicitly set
            if (requestJson.has("enabled")) {
                enabled = requestJson.getBoolean("enabled");
                if (enabled) {
                    if (pattern != null && !pattern.isEmpty()) {
                        device.enableTagSimulation(tagPath, pattern);
                    } else {
                        device.enableTagSimulation(tagPath);
                    }
                } else {
                    device.disableTagSimulation(tagPath);
                }
            } else {
                // Toggle mode
                Boolean newState = device.toggleTagSimulation(tagPath);
                enabled = newState != null && newState;

                // Apply pattern if specified and now enabled
                if (enabled && pattern != null && !pattern.isEmpty()) {
                    device.enableTagSimulation(tagPath, pattern);
                }
            }

            return result.put("success", true)
                .put("tagPath", tagPath)
                .put("simulationEnabled", enabled)
                .put("pattern", device.getTagSimulationPattern(tagPath))
                .put("simulatedTagCount", device.getSimulatedTagCount());

        } catch (Exception e) {
            logger.error("Error toggling tag simulation", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return result.put("success", false).put("error", "An internal error occurred while toggling simulation");
        }
    }

    /**
     * Get list of all tags that have simulation enabled for a device.
     */
    private JSONObject handleGetSimulatedTags(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        JSONObject result = new JSONObject();
        String deviceName = ctx.getParameter("name");

        if (deviceName == null || deviceName.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return result.put("success", false).put("error", "Device name required");
        }

        Optional<LogixEmulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
        if (deviceOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Device not found: " + deviceName);
        }

        LogixEmulatorDevice device = deviceOpt.get();

        JSONArray simulatedTags = new JSONArray();
        for (String tagPath : device.getSimulatedTags()) {
            JSONObject tagInfo = new JSONObject();
            tagInfo.put("path", tagPath);
            tagInfo.put("pattern", device.getTagSimulationPattern(tagPath));
            simulatedTags.put(tagInfo);
        }

        return result.put("success", true)
            .put("deviceName", deviceName)
            .put("simulationEngineAvailable", device.isSimulationEngineAvailable())
            .put("simulatedTags", simulatedTags)
            .put("count", simulatedTags.length());
    }

    /**
     * Enable/disable simulation for all tags matching a scope prefix.
     * Request body: { "scope": "Controller:Global", "enabled": true }
     */
    private JSONObject handleBulkSimulationByScope(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        JSONObject result = new JSONObject();

        try { if (!requireCSRFToken(ctx, resp)) return null; }
        catch (Exception e) { resp.setStatus(HttpServletResponse.SC_FORBIDDEN); return result.put("success", false).put("error", "CSRF validation failed"); }

        String deviceName = ctx.getParameter("name");

        if (deviceName == null || deviceName.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return result.put("success", false).put("error", "Device name required");
        }

        Optional<LogixEmulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
        if (deviceOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Device not found: " + deviceName);
        }

        LogixEmulatorDevice device = deviceOpt.get();
        if (!device.isSimulationEngineAvailable()) {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return result.put("success", false)
                .put("error", "Simulation engine not available. Enable simulation in device settings first.");
        }

        try {
            String body = readRequestContent(ctx, 10 * 1024);
            JSONObject requestJson = new JSONObject(body);
            String scope = requestJson.optString("scope", null);
            boolean enabled = requestJson.optBoolean("enabled", true);

            if (scope == null || scope.trim().isEmpty()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return result.put("success", false).put("error", "scope required");
            }

            if (enabled) {
                device.enableSimulationByScope(scope);
            } else {
                device.disableSimulationByScope(scope);
            }

            return result.put("success", true)
                .put("scope", scope)
                .put("enabled", enabled)
                .put("simulatedTagCount", device.getSimulatedTagCount());

        } catch (Exception e) {
            logger.error("Error in bulk simulation by scope", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return result.put("success", false).put("error", "An internal error occurred during bulk simulation");
        }
    }

    /**
     * Enable/disable simulation for all tags.
     * Request body: { "enabled": true }
     */
    private JSONObject handleBulkSimulationAll(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        JSONObject result = new JSONObject();

        try { if (!requireCSRFToken(ctx, resp)) return null; }
        catch (Exception e) { resp.setStatus(HttpServletResponse.SC_FORBIDDEN); return result.put("success", false).put("error", "CSRF validation failed"); }

        String deviceName = ctx.getParameter("name");

        if (deviceName == null || deviceName.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return result.put("success", false).put("error", "Device name required");
        }

        Optional<LogixEmulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
        if (deviceOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Device not found: " + deviceName);
        }

        LogixEmulatorDevice device = deviceOpt.get();
        if (!device.isSimulationEngineAvailable()) {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return result.put("success", false)
                .put("error", "Simulation engine not available. Enable simulation in device settings first.");
        }

        try {
            String body = readRequestContent(ctx, 10 * 1024);
            JSONObject requestJson = new JSONObject(body);
            boolean enabled = requestJson.optBoolean("enabled", true);

            if (enabled) {
                device.enableAllSimulation();
            } else {
                device.disableAllSimulation();
            }

            return result.put("success", true)
                .put("enabled", enabled)
                .put("simulatedTagCount", device.getSimulatedTagCount());

        } catch (Exception e) {
            logger.error("Error in bulk simulation all", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return result.put("success", false).put("error", "An internal error occurred during bulk simulation");
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

        try { if (!requireCSRFToken(ctx, resp)) return null; }
        catch (Exception e) { resp.setStatus(HttpServletResponse.SC_FORBIDDEN); return result.put("success", false).put("error", "CSRF validation failed"); }

        String deviceName = ctx.getParameter("name");

        if (deviceName == null || deviceName.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return result.put("success", false).put("error", "Device name required");
        }

        Optional<LogixEmulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
        if (deviceOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Device not found: " + deviceName);
        }

        LogixEmulatorDevice device = deviceOpt.get();

        // Get the actual file path from the device (not from config which may not be updated)
        String filePath = deviceManager.getDeviceFilePath(device);
        if (filePath == null || filePath.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "No file configured for device: " + deviceName);
        }

        try {
            File deviceFile = new File(filePath);
            String fileName = deviceFile.getName();

            // Verify file is within storage directory (security check)
            File storageDir = deviceManager.getStorageDirectory();
            String canonicalFilePath = deviceFile.getCanonicalPath();
            String canonicalStorageDir = storageDir.getCanonicalPath();
            if (!canonicalFilePath.startsWith(canonicalStorageDir + File.separator)) {
                logger.error("Security violation: attempt to delete file outside storage directory: {}", filePath);
                resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return result.put("success", false).put("error", "Invalid file path");
            }

            if (deviceFile.exists() && deviceFile.delete()) {
                logger.info("Deleted file for device {}: {}", deviceName, deviceFile.getAbsolutePath());

                // Clear the device's file path and rebuild address space to remove tags
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
            logger.error("Error deleting file for device {}", deviceName, e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return result.put("success", false).put("error", "An internal error occurred while deleting file");
        }
    }

    private JSONObject handleHealthCheck(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        return new JSONObject().put("status", "ok").put("service", "logix-file-upload");
    }

    /**
     * System stats — CPU usage, RAM usage, device count, module version.
     */
    private JSONObject handleSystemStats(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        JSONObject result = new JSONObject();

        // CPU usage
        var osBean = ManagementFactory.getOperatingSystemMXBean();
        double cpuPercent = 0;

        try {
            // Use com.sun API for accurate CPU percentage
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                cpuPercent = sunBean.getCpuLoad() * 100;
                if (cpuPercent < 0) cpuPercent = 0;

                long ramTotal = sunBean.getTotalMemorySize();
                long ramFree = sunBean.getFreeMemorySize();
                result.put("ramUsage", ramTotal - ramFree);
                result.put("ramTotal", ramTotal);
            } else {
                // Fallback to JVM heap
                MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                result.put("ramUsage", heap.getUsed());
                result.put("ramTotal", heap.getMax());

                double loadAvg = osBean.getSystemLoadAverage();
                if (loadAvg >= 0) {
                    cpuPercent = Math.min((loadAvg / osBean.getAvailableProcessors()) * 100, 100);
                }
            }
        } catch (Exception e) {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            result.put("ramUsage", heap.getUsed());
            result.put("ramTotal", heap.getMax());
        }

        return result.put("success", true)
            .put("cpuUsage", Math.round(cpuPercent * 10) / 10.0)
            .put("moduleVersion", "9.0.1")
            .put("deviceCount", SimulatorModuleHook.getRegisteredDevices().size());
    }

    private static final DateTimeFormatter LOG_DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * Gateway logs — reads entries from Ignition's SQLite system_logs.idb database.
     * This works reliably in Docker where wrapper.log is symlinked to /dev/stdout.
     *
     * Query parameters:
     *   - limit: max entries to return (default 100, max 500)
     *   - level: comma-separated log levels to include (ERROR, WARN, INFO, DEBUG)
     *   - after: only return entries after this event ID (for incremental polling)
     *   - filter: keyword filter (case-insensitive)
     */
    private JSONObject handleSystemLogs(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        JSONObject result = new JSONObject();

        // Read rate limiting for expensive SQLite query endpoint
        RateLimiter.RateLimitResult readRate = readRateLimiter.checkRequest(getUsername(ctx), getClientIP(ctx));
        if (!readRate.isAllowed()) { resp.setStatus(429); return result.put("success", false).put("error", "Rate limit exceeded"); }

        int limit = Math.min(parseIntParam(ctx, "limit", 100), 500);
        String levelParam = ctx.getRequest().getParameter("level");
        String afterParam = ctx.getRequest().getParameter("after");
        String filterParam = ctx.getRequest().getParameter("filter");

        // Parse level filter
        Set<String> levelFilter = new HashSet<>();
        if (levelParam != null && !levelParam.isEmpty()) {
            Arrays.stream(levelParam.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .filter(s -> !s.isEmpty())
                .forEach(levelFilter::add);
        }

        // Parse after event ID
        long afterEventId = 0;
        if (afterParam != null && !afterParam.isEmpty()) {
            try {
                afterEventId = Long.parseLong(afterParam);
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        File logDb = findSystemLogsDb();
        if (logDb == null) {
            logger.warn("system_logs.idb not found");
            return result.put("success", false).put("error", "system_logs.idb not found");
        }

        try {
            List<JSONObject> entries = readLogEntriesFromDb(logDb, limit, filterParam, levelFilter, afterEventId);
            JSONArray entriesArray = new JSONArray();
            for (JSONObject entry : entries) {
                entriesArray.put(entry);
            }

            return result.put("success", true)
                .put("entries", entriesArray)
                .put("count", entries.size())
                .put("hasMore", entries.size() >= limit);

        } catch (Exception e) {
            logger.error("Error reading gateway logs from SQLite", e);
            return result.put("success", false).put("error", "Failed to read gateway logs");
        }
    }

    /**
     * Read log entries from Ignition's SQLite system_logs.idb database.
     */
    private List<JSONObject> readLogEntriesFromDb(File logDb, int maxLines, String filter,
                                                   Set<String> levelFilter, long afterEventId) throws JSONException {
        List<JSONObject> entries = new ArrayList<>();
        String url = "jdbc:sqlite:" + logDb.getAbsolutePath();

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT event_id, timestmp, formatted_message, logger_name, level_string ");
        sql.append("FROM logging_event WHERE 1=1 ");

        List<Object> params = new ArrayList<>();

        if (afterEventId > 0) {
            sql.append("AND event_id > ? ");
            params.add(afterEventId);
        }

        if (!levelFilter.isEmpty()) {
            sql.append("AND level_string IN (");
            sql.append(String.join(",", Collections.nCopies(levelFilter.size(), "?")));
            sql.append(") ");
            params.addAll(levelFilter);
        }

        if (filter != null && !filter.isEmpty()) {
            sql.append("AND (formatted_message LIKE ? OR logger_name LIKE ?) ");
            params.add("%" + filter + "%");
            params.add("%" + filter + "%");
        }

        sql.append("ORDER BY event_id DESC LIMIT ?");
        params.add(maxLines);

        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof Long) {
                    stmt.setLong(i + 1, (Long) param);
                } else if (param instanceof Integer) {
                    stmt.setInt(i + 1, (Integer) param);
                } else {
                    stmt.setString(i + 1, param.toString());
                }
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long eventId = rs.getLong("event_id");
                    long timestmp = rs.getLong("timestmp");
                    String message = rs.getString("formatted_message");
                    String loggerName = rs.getString("logger_name");
                    String level = rs.getString("level_string");

                    String formattedTime = LOG_DATE_FORMAT.format(Instant.ofEpochMilli(timestmp));

                    // Shorten logger name for display (e.g. "com.inductiveautomation.logixemulator.gateway.GatewayHook" -> "GatewayHook")
                    String shortSource = loggerName;
                    if (loggerName != null && loggerName.contains(".")) {
                        shortSource = loggerName.substring(loggerName.lastIndexOf('.') + 1);
                    }

                    JSONObject entry = new JSONObject()
                        .put("id", String.valueOf(eventId))
                        .put("timestamp", formattedTime)
                        .put("level", level)
                        .put("source", shortSource)
                        .put("logger", loggerName)
                        .put("message", message != null ? message : "");
                    entries.add(entry);
                }
            }
        } catch (SQLException e) {
            logger.error("Error reading from SQLite database: {}", e.getMessage(), e);
        }

        // Reverse to chronological order (oldest first, newest last)
        Collections.reverse(entries);
        return entries;
    }

    /**
     * Find Ignition's system_logs.idb SQLite database.
     */
    private File findSystemLogsDb() {
        List<File> candidates = new ArrayList<>();

        // Primary: use GatewayContext to get logs directory
        try {
            File logsDir = context.getSystemManager().getLogsDir();
            if (logsDir != null) {
                candidates.add(new File(logsDir, "system_logs.idb"));
            }
        } catch (Exception e) {
            // ignore
        }

        // Fallback paths
        candidates.add(new File("/usr/local/bin/ignition/logs/system_logs.idb"));
        candidates.add(new File("/var/lib/ignition/logs/system_logs.idb"));
        candidates.add(new File("C:/Program Files/Inductive Automation/Ignition/logs/system_logs.idb"));

        for (File candidate : candidates) {
            if (candidate.exists() && candidate.canRead()) {
                logger.debug("Found system_logs.idb at: {}", candidate.getAbsolutePath());
                return candidate;
            }
        }
        return null;
    }

    private JSONObject handleAuthStatus(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        boolean isAuth = isGatewayAuthenticated(ctx);
        return new JSONObject()
            .put("authenticated", isAuth)
            .put("loginUrl", "/web/login");
    }

    /**
     * Auth check endpoint — returns 200 if authenticated, 401 if not.
     * Used by the standalone page to detect whether to show the login overlay.
     */
    private JSONObject handleAuthCheck(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        if (!isGatewayAuthenticated(ctx)) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return new JSONObject().put("authenticated", false);
        }
        return new JSONObject().put("authenticated", true);
    }

    /**
     * Check if the current request is authenticated via Gateway session.
     * Checks HTTP session "user" attribute and request actor.
     */
    private static boolean isGatewayAuthenticated(RequestContext req) {
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

    private Object handleConnectionBrowserPage(RequestContext ctx, HttpServletResponse resp) {
        return serveHtmlPage("/pages/connection-browser.html", "Connection browser page", resp);
    }

    private Object handleUploadPage(RequestContext ctx, HttpServletResponse resp) {
        // Redirect legacy route to the new connection browser
        return serveHtmlPage("/pages/connection-browser.html", "Upload page", resp);
    }

    private Object handleEditProgramPage(RequestContext ctx, HttpServletResponse resp) {
        return serveHtmlPage("/pages/edit-program.html", "Edit program page", resp);
    }

    private Object handleTagBrowserPage(RequestContext ctx, HttpServletResponse resp) {
        // Redirect legacy route to the new connection browser
        return serveHtmlPage("/pages/connection-browser.html", "Tag browser page", resp);
    }

    /**
     * Serve an HTML page from the /pages/ resource directory.
     * This directory is NOT publicly mounted, so pages are only accessible
     * through authenticated data routes.
     */
    private Object serveHtmlPage(String resourcePath, String pageName, HttpServletResponse resp) {
        try (var stream = getClass().getResourceAsStream(resourcePath)) {
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
        // Ignition data routes don't populate remoteUser/userPrincipal —
        // fall back to IP to avoid null keys in ConcurrentHashMap
        return "anon-" + getClientIP(ctx);
    }

    /**
     * Trusted proxy IP addresses. Only trust X-Forwarded-For from these sources.
     * Add your reverse proxy IPs here if needed.
     */
    private static final Set<String> TRUSTED_PROXIES = Set.of(
        "127.0.0.1",
        "::1",
        "0:0:0:0:0:0:0:1"  // IPv6 localhost
    );

    /**
     * Get client IP address, accounting for proxies.
     * Only trusts X-Forwarded-For header from known trusted proxy IPs
     * to prevent IP spoofing attacks on rate limiting.
     */
    private String getClientIP(RequestContext ctx) {
        var request = ctx.getRequest();
        String remoteAddr = request.getRemoteAddr();

        // Only trust X-Forwarded-For if request came from a trusted proxy
        if (remoteAddr != null && TRUSTED_PROXIES.contains(remoteAddr)) {
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
                String[] ips = xForwardedFor.split(",");
                if (ips.length > 0) {
                    String clientIP = ips[0].trim();
                    // Validate IP format to prevent header injection
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

    private static final Pattern IPV4_PATTERN = Pattern.compile(
        "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$");
    private static final Pattern IPV6_PATTERN = Pattern.compile(
        "^[0-9a-fA-F:]+$");

    /**
     * Validates that the string is a literal IP address (not a hostname).
     * Uses regex instead of InetAddress.getByName() to avoid DNS resolution.
     */
    private boolean isValidIPAddress(String ip) {
        if (ip == null || ip.isEmpty() || ip.length() > 45) {
            return false;
        }
        return IPV4_PATTERN.matcher(ip).matches() || IPV6_PATTERN.matcher(ip).matches();
    }
}
