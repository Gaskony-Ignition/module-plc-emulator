package com.inductiveautomation.logixemulator.gateway.web.controller;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorDevice;
import com.inductiveautomation.logixemulator.gateway.web.DeviceFileManager;
import com.inductiveautomation.logixemulator.gateway.web.GatewayAuthHelper;
import com.inductiveautomation.logixemulator.gateway.web.RateLimiter;
import com.inductiveautomation.logixemulator.gateway.web.TagTreeBuilder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;

/**
 * Handles tag browsing: structured hierarchy, lazy-loading children, and live OPC-UA values.
 */
public class TagController {

    private static final Logger logger = LoggerFactory.getLogger(TagController.class);

    private final DeviceFileManager deviceManager;
    private final RateLimiter readRateLimiter;

    public TagController(DeviceFileManager deviceManager, RateLimiter readRateLimiter) {
        this.deviceManager = deviceManager;
        this.readRateLimiter = readRateLimiter;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    int parseIntParam(RequestContext ctx, String name, int defaultValue) {
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

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    /**
     * Get tags from device with pagination support.
     * Query parameters: path, depth, offset, limit, flat
     */
    public JSONObject handleGetTags(RequestContext ctx, HttpServletResponse resp) throws JSONException {
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

        String parentPath = ctx.getRequest().getParameter("path");
        if (parentPath == null || parentPath.isEmpty()) {
            parentPath = "";
        }

        int depth = parseIntParam(ctx, "depth", 1);
        int offset = parseIntParam(ctx, "offset", 0);
        int limit = Math.min(parseIntParam(ctx, "limit", 100), 500);
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

            TagTreeBuilder builder = new TagTreeBuilder(parsedData);
            TagTreeBuilder.TagStats stats = builder.getStats();

            JSONArray tags;
            int totalAtLevel;
            boolean hasMore;

            if (flat) {
                tags = builder.getFlatTags(parentPath, offset, limit);
                totalAtLevel = builder.getTotalFlatCount(parentPath);
                hasMore = (offset + limit) < totalAtLevel;
            } else {
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
                .put("total", flat ? totalAtLevel : stats.totalTags)
                .put("hasMore", hasMore)
                .put("totalTags", stats.totalTags)
                .put("folders", stats.folderCount)
                .put("udtInstances", stats.udtCount)
                .put("udt_instances", stats.udtCount);

        } catch (Exception e) {
            logger.warn("Could not access parsed data for device: {}", GatewayAuthHelper.sanitizeForLog(deviceName), e);
            return result.put("success", false).put("error", "Failed to read tag data");
        }
    }

    /**
     * Get children of a specific folder path (for lazy loading).
     * Query parameters: path (required), offset, limit
     */
    public JSONObject handleGetTagChildren(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        try { if (!GatewayAuthHelper.requireAuthentication(ctx, resp)) return null; }
        catch (Exception e) { resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); return null; }

        JSONObject result = new JSONObject();
        String deviceName = ctx.getParameter("name");
        String parentPath = ctx.getRequest().getParameter("path");

        JSONObject nameError = validateDeviceName(deviceName, resp);
        if (nameError != null) return nameError;

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
                    .put("tags", new JSONArray())
                    .put("total", 0)
                    .put("totalChildren", 0);
            }

            TagTreeBuilder builder = new TagTreeBuilder(parsedData);
            JSONArray children = builder.getChildrenOf(parentPath, 1, offset, limit);
            int totalChildren = builder.getChildCount(parentPath);

            return result.put("success", true)
                .put("path", parentPath)
                .put("tags", children)
                .put("offset", offset)
                .put("limit", limit)
                .put("count", children.length())
                .put("total", totalChildren)
                .put("totalChildren", totalChildren)
                .put("hasMore", (offset + limit) < totalChildren);

        } catch (Exception e) {
            logger.warn("Could not get children for path: {} on device: {}",
                GatewayAuthHelper.sanitizeForLog(parentPath), GatewayAuthHelper.sanitizeForLog(deviceName), e);
            return result.put("success", false).put("error", "Failed to read children");
        }
    }

    /**
     * Get live tag values from OPC-UA address space.
     * Returns current real-time values for all tags.
     */
    public JSONObject handleGetLiveTags(RequestContext ctx, HttpServletResponse resp) throws JSONException {
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
        JSONObject values = new JSONObject();

        try {
            Map<String, Object> liveValues = device.getAllTagValues();
            for (var entry : liveValues.entrySet()) {
                Object value = entry.getValue();
                if (value != null) {
                    String path = entry.getKey();
                    if (path.startsWith("Controller:Global.")) {
                        path = "Controller:Global/" + path.substring("Controller:Global.".length()).replace(".", "/");
                    } else if (path.startsWith("Programs.")) {
                        path = path.replace(".", "/");
                    } else if (path.contains(".")) {
                        continue;
                    }
                    values.put(path, value.toString());
                }
            }
        } catch (Exception e) {
            logger.warn("Could not get live tag values for device: {}", GatewayAuthHelper.sanitizeForLog(deviceName), e);
        }

        return result.put("success", true)
            .put("deviceName", deviceName)
            .put("values", values)
            .put("timestamp", System.currentTimeMillis());
    }

    // -------------------------------------------------------------------------
    // Shared validation (delegates to DeviceController-style check)
    // -------------------------------------------------------------------------

    private JSONObject validateDeviceName(String deviceName, HttpServletResponse resp) throws JSONException {
        return DeviceConfigService.validateDeviceName(deviceName, resp);
    }
}
