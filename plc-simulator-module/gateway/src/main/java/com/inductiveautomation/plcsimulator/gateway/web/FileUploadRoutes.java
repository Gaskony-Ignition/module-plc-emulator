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
        mountProtectedRoute("/device/:name/tags/children", this::handleGetTagChildren, null);
        mountProtectedRoute("/device/:name/tags/live", this::handleGetLiveTags, null);
        mountProtectedRoute("/device/:name/tags/simulated", this::handleGetSimulatedTags, null);
        mountProtectedRoute("/device/:name/tag/write", this::handleWriteTag, HttpMethod.POST);
        mountProtectedRoute("/device/:name/tag/simulate", this::handleToggleTagSimulation, HttpMethod.POST);
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

        Optional<EnhancedSimulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
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

        EnhancedSimulatorDevice device = deviceOpt.get();

        try {
            var parsedDataField = EnhancedSimulatorDevice.class.getDeclaredField("parsedData");
            parsedDataField.setAccessible(true);
            var parsedData = (com.google.gson.JsonObject) parsedDataField.get(device);

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
            return result.put("success", false).put("error", "Failed to read tag data: " + e.getMessage());
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

        Optional<EnhancedSimulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
        if (deviceOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Device not found: " + deviceName);
        }

        int offset = parseIntParam(ctx, "offset", 0);
        int limit = Math.min(parseIntParam(ctx, "limit", 100), 500);

        EnhancedSimulatorDevice device = deviceOpt.get();

        try {
            var parsedDataField = EnhancedSimulatorDevice.class.getDeclaredField("parsedData");
            parsedDataField.setAccessible(true);
            var parsedData = (com.google.gson.JsonObject) parsedDataField.get(device);

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
            return result.put("success", false).put("error", "Failed to read children: " + e.getMessage());
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
     * Helper class to build and navigate the tag tree structure efficiently.
     * Supports lazy loading and pagination.
     */
    private static class TagTreeBuilder {
        private final java.util.Map<String, java.util.List<JSONObject>> childrenByPath = new java.util.HashMap<>();
        private final java.util.Map<String, JSONObject> nodesByPath = new java.util.HashMap<>();
        private final TagStats stats = new TagStats();

        static class TagStats {
            int totalTags = 0;
            int folderCount = 0;
            int udtCount = 0;
        }

        TagTreeBuilder(com.google.gson.JsonObject parsedData) throws JSONException {
            // Build index of all tags organized by parent path
            if (parsedData.has("global_tags")) {
                var globalTags = parsedData.getAsJsonArray("global_tags");

                // Add Controller:Global as a root folder
                addFolderNode("", "Controller:Global", "Folder", false);
                stats.folderCount++;

                for (var elem : globalTags) {
                    var tag = elem.getAsJsonObject();
                    indexTag(tag, "Controller:Global");
                }
            }

            if (parsedData.has("programs")) {
                var programs = parsedData.getAsJsonArray("programs");

                // Add Programs as a root folder if there are programs
                if (programs.size() > 0) {
                    addFolderNode("", "Programs", "Folder", false);
                    stats.folderCount++;
                }

                for (var progElem : programs) {
                    var prog = progElem.getAsJsonObject();
                    String progName = prog.has("name") ? prog.get("name").getAsString() : "Program";

                    // Add program as a folder under Programs
                    addFolderNode("Programs", progName, "Program", false);
                    stats.folderCount++;

                    if (prog.has("tags")) {
                        var progTags = prog.getAsJsonArray("tags");
                        for (var tagElem : progTags) {
                            var tag = tagElem.getAsJsonObject();
                            indexTag(tag, "Programs/" + progName);
                        }
                    }
                }
            }
        }

        private void indexTag(com.google.gson.JsonObject tag, String parentPath) throws JSONException {
            String tagName = tag.has("name") ? tag.get("name").getAsString() : "unknown";
            String dataType = tag.has("data_type") ? tag.get("data_type").getAsString() : "STRING";
            String tagPath = parentPath + "/" + tagName;

            // Check if this is a UDT instance with members
            if (tag.has("udt_members")) {
                var members = tag.getAsJsonArray("udt_members");
                if (members.size() > 0) {
                    stats.udtCount++;

                    // Add UDT instance as a folder
                    addFolderNode(parentPath, tagName, dataType, true);
                    stats.folderCount++;

                    // Recursively index members
                    for (var memberElem : members) {
                        var member = memberElem.getAsJsonObject();
                        indexTag(member, tagPath);
                    }
                    return;
                }
            }

            // Atomic tag - add as leaf
            addLeafNode(parentPath, tagName, dataType,
                tag.has("initial_value") ? tag.get("initial_value").toString() : "", tagPath);
            stats.totalTags++;
        }

        private void addFolderNode(String parentPath, String name, String dataType, boolean isUdt) throws JSONException {
            String path = parentPath.isEmpty() ? name : parentPath + "/" + name;

            JSONObject node = new JSONObject();
            node.put("name", name);
            node.put("path", path);
            node.put("data_type", dataType);
            node.put("isFolder", true);
            node.put("isUdt", isUdt);

            childrenByPath.computeIfAbsent(parentPath, k -> new java.util.ArrayList<>()).add(node);
            nodesByPath.put(path, node);
        }

        private void addLeafNode(String parentPath, String name, String dataType, String value, String path) throws JSONException {
            JSONObject node = new JSONObject();
            node.put("name", name);
            node.put("path", path);
            node.put("data_type", dataType);
            node.put("value", value);
            node.put("isFolder", false);

            childrenByPath.computeIfAbsent(parentPath, k -> new java.util.ArrayList<>()).add(node);
            nodesByPath.put(path, node);
        }

        TagStats getStats() {
            return stats;
        }

        int getChildCount(String parentPath) {
            java.util.List<JSONObject> children = childrenByPath.get(parentPath);
            return children != null ? children.size() : 0;
        }

        /**
         * Get children of a path with pagination.
         * @param parentPath Parent path (empty string for root)
         * @param depth How many levels to include (1 = direct children only)
         * @param offset Pagination offset
         * @param limit Max items to return
         */
        JSONArray getChildrenOf(String parentPath, int depth, int offset, int limit) throws JSONException {
            JSONArray result = new JSONArray();
            java.util.List<JSONObject> children = childrenByPath.get(parentPath);

            if (children == null || children.isEmpty()) {
                return result;
            }

            // Sort children: folders first, then alphabetically
            children.sort((a, b) -> {
                try {
                    boolean aFolder = a.optBoolean("isFolder", false);
                    boolean bFolder = b.optBoolean("isFolder", false);
                    if (aFolder != bFolder) {
                        return aFolder ? -1 : 1;
                    }
                    return a.optString("name", "").compareToIgnoreCase(b.optString("name", ""));
                } catch (Exception e) {
                    return 0;
                }
            });

            // Apply pagination
            int end = Math.min(offset + limit, children.size());
            for (int i = offset; i < end; i++) {
                JSONObject child = children.get(i);
                JSONObject copy = new JSONObject(child.toString());

                // Add child count for folders (for UI to show expand arrow)
                if (child.optBoolean("isFolder", false)) {
                    String childPath = child.optString("path", "");
                    int childCount = getChildCount(childPath);
                    copy.put("childCount", childCount);
                    copy.put("hasChildren", childCount > 0);

                    // If depth > 1, include nested children
                    if (depth > 1 || depth == -1) {
                        JSONArray nested = getChildrenOf(childPath, depth == -1 ? -1 : depth - 1, 0, 500);
                        if (nested.length() > 0) {
                            copy.put("children", nested);
                        }
                    }
                }

                result.put(copy);
            }

            return result;
        }

        /**
         * Get flattened list of all visible tags for virtual scrolling.
         * Only returns tags (not folders) for simpler virtual scroll implementation.
         */
        JSONArray getFlatTags(String parentPath, int offset, int limit) throws JSONException {
            JSONArray result = new JSONArray();
            java.util.List<JSONObject> allTags = new java.util.ArrayList<>();

            // Collect all leaf tags under the given path
            collectLeafTags(parentPath.isEmpty() ? null : parentPath, allTags);

            // Sort alphabetically by path
            allTags.sort((a, b) -> a.optString("path", "").compareToIgnoreCase(b.optString("path", "")));

            // Apply pagination
            int end = Math.min(offset + limit, allTags.size());
            for (int i = offset; i < end; i++) {
                result.put(allTags.get(i));
            }

            return result;
        }

        private void collectLeafTags(String parentPath, java.util.List<JSONObject> tags) {
            for (var entry : childrenByPath.entrySet()) {
                String path = entry.getKey();
                // If parentPath is null, collect everything; otherwise filter by prefix
                if (parentPath == null || path.equals(parentPath) || path.startsWith(parentPath + "/")) {
                    for (JSONObject node : entry.getValue()) {
                        if (!node.optBoolean("isFolder", false)) {
                            tags.add(node);
                        }
                    }
                }
            }
        }

        int getTotalFlatCount(String parentPath) {
            int count = 0;
            for (var entry : childrenByPath.entrySet()) {
                String path = entry.getKey();
                if (parentPath.isEmpty() || path.equals(parentPath) || path.startsWith(parentPath + "/")) {
                    for (JSONObject node : entry.getValue()) {
                        if (!node.optBoolean("isFolder", false)) {
                            count++;
                        }
                    }
                }
            }
            return count;
        }
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
     * Toggle simulation for a specific tag.
     * Request body: { "tagPath": "Controller:Global/MyTag", "enabled": true, "pattern": "sine" }
     * If enabled is not specified, it toggles the current state.
     * Pattern is optional: sine, ramp, random, toggle, static
     */
    private JSONObject handleToggleTagSimulation(RequestContext ctx, HttpServletResponse resp) throws JSONException {
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
            return result.put("success", false).put("error", e.getMessage());
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

        Optional<EnhancedSimulatorDevice> deviceOpt = deviceManager.findDeviceByName(deviceName);
        if (deviceOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return result.put("success", false).put("error", "Device not found: " + deviceName);
        }

        EnhancedSimulatorDevice device = deviceOpt.get();

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
