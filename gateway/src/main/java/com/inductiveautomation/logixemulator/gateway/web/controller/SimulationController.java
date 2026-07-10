package com.inductiveautomation.logixemulator.gateway.web.controller;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.logixemulator.gateway.address.AddressPolicy;
import com.inductiveautomation.logixemulator.gateway.address.RockwellLogixPolicy;
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
import java.util.Optional;

/**
 * Handles tag value writes and simulation enable/disable operations.
 */
public class SimulationController {

    private static final Logger logger = LoggerFactory.getLogger(SimulationController.class);

    private final DeviceFileManager deviceManager;
    private final RateLimiter writeRateLimiter;

    /** Vendor addressing policy — maps the browse-tree (slash) path clients send to canonical NodeIds. */
    private final AddressPolicy policy = new RockwellLogixPolicy();

    public SimulationController(DeviceFileManager deviceManager, RateLimiter writeRateLimiter) {
        this.deviceManager = deviceManager;
        this.writeRateLimiter = writeRateLimiter;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Convert a browse-tree (slash-notation) tag path from the web UI into the canonical OPC-UA
     * NodeId identifier the address space assigns (v10 C1 — ADDRESSING.md §2.2). The cosmetic
     * browse folders are dropped: a {@code Controller:Global/} path resolves to the bare identifier
     * and a {@code Programs/<Prog>/} path resolves to the {@code Program:<Prog>.} selector form.
     *
     * <ul>
     *   <li>{@code Controller:Global/Motor1/Speed} -&gt; {@code Motor1.Speed}</li>
     *   <li>{@code Programs/MainProgram/Counter} -&gt; {@code Program:MainProgram.Counter}</li>
     *   <li>{@code Controller:Global} (bulk scope) -&gt; {@code ""} — the controller-scope
     *       selector; scope membership is decided by {@code AddressPolicy.matchesScope}, which
     *       treats the empty selector as "every non-program tag"</li>
     *   <li>{@code Programs} (bulk scope) -&gt; {@code Program:} (every program-scoped tag)</li>
     *   <li>{@code Programs/MainProgram} (bulk scope) -&gt; {@code Program:MainProgram}</li>
     * </ul>
     */
    String convertToNodeIdPath(String tagPath) {
        if (tagPath == null) return null;

        String controllerFolder = policy.controllerBrowseFolder();
        String programsFolder = policy.programsBrowseFolder();

        if (tagPath.equals(controllerFolder)) {
            // Bulk "all controller tags" scope. Controller identifiers are bare, so the scope
            // selector is empty; matchesScope() gives it "not program-scoped" semantics.
            return policy.controllerScopePrefix();
        }
        if (tagPath.startsWith(controllerFolder + "/")) {
            String rest = tagPath.substring((controllerFolder + "/").length());
            return rest.replace("/", ".");
        }

        if (tagPath.equals(programsFolder)) {
            // Bulk "all programs" scope -> the generic Program: selector.
            return policy.allProgramsScopeSelector();
        }
        if (tagPath.startsWith(programsFolder + "/")) {
            String rest = tagPath.substring((programsFolder + "/").length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                // "Programs/<Prog>" bulk scope -> "Program:<Prog>".
                return policy.programScopePrefix(rest);
            }
            String programName = rest.substring(0, slash);
            String remainder = rest.substring(slash + 1).replace("/", ".");
            return policy.join(policy.programScopePrefix(programName), remainder);
        }

        return tagPath.replace("/", ".");
    }

    /**
     * Convert a string value to the appropriate type based on dataType.
     */
    Object convertValue(String value, String dataType) {
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

        return value;
    }

    private JSONObject validateDeviceName(String deviceName, HttpServletResponse resp) throws JSONException {
        return DeviceConfigService.validateDeviceName(deviceName, resp);
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    /**
     * Write a value to a tag in the OPC-UA address space.
     * Request body: { "tagPath": "Controller:Global/MyTag", "value": "123" }
     */
    public JSONObject handleWriteTag(RequestContext ctx, HttpServletResponse resp) throws JSONException {
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

        try {
            String body = GatewayAuthHelper.readRequestContent(ctx, 10 * 1024);
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

            String opcuaPath = convertToNodeIdPath(tagPath);
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
     */
    public JSONObject handleToggleTagSimulation(RequestContext ctx, HttpServletResponse resp) throws JSONException {
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

        if (!device.isSimulationEngineAvailable()) {
            resp.setStatus(HttpServletResponse.SC_CONFLICT);
            return result.put("success", false)
                .put("error", "Simulation is disabled for this device. Enable the "
                    + "\"Enable Simulation\" setting in the device configuration first.");
        }

        try {
            String body = GatewayAuthHelper.readRequestContent(ctx, 10 * 1024);
            JSONObject requestJson = new JSONObject(body);

            String tagPath = requestJson.optString("tagPath", null);
            if (tagPath == null || tagPath.trim().isEmpty()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return result.put("success", false).put("error", "tagPath required");
            }

            // The simulation engine keys its per-tag registry off the *live* OPC-UA
            // NodeId identifier, which AddressSpaceBuilder always constructs in DOT
            // notation (e.g. "Controller:Global.RampInt") — never the slash notation
            // ("Controller:Global/RampInt") this endpoint receives from clients. Without
            // this conversion the registry and the engine's per-tick node lookup use
            // different keys and never match, so a tag can be "registered" (API reports
            // success) while its OPC-UA node value never updates (defect B3).
            String opcuaPath = convertToNodeIdPath(tagPath);

            String pattern = requestJson.optString("pattern", null);
            boolean enabled;

            if (requestJson.has("enabled")) {
                enabled = requestJson.getBoolean("enabled");
                if (enabled) {
                    if (pattern != null && !pattern.isEmpty()) {
                        device.enableTagSimulation(opcuaPath, pattern);
                    } else {
                        device.enableTagSimulation(opcuaPath);
                    }
                } else {
                    device.disableTagSimulation(opcuaPath);
                }
            } else {
                Boolean newState = device.toggleTagSimulation(opcuaPath);
                enabled = newState != null && newState;

                if (enabled && pattern != null && !pattern.isEmpty()) {
                    device.enableTagSimulation(opcuaPath, pattern);
                }
            }

            return result.put("success", true)
                .put("tagPath", tagPath)
                .put("simulationEnabled", enabled)
                .put("pattern", device.getTagSimulationPattern(opcuaPath))
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
    public JSONObject handleGetSimulatedTags(RequestContext ctx, HttpServletResponse resp) throws JSONException {
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
    public JSONObject handleBulkSimulationByScope(RequestContext ctx, HttpServletResponse resp) throws JSONException {
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
        if (!device.isSimulationEngineAvailable()) {
            resp.setStatus(HttpServletResponse.SC_CONFLICT);
            return result.put("success", false)
                .put("error", "Simulation is disabled for this device. Enable the "
                    + "\"Enable Simulation\" setting in the device configuration first.");
        }

        try {
            String body = GatewayAuthHelper.readRequestContent(ctx, 10 * 1024);
            JSONObject requestJson = new JSONObject(body);
            String scope = requestJson.optString("scope", null);
            boolean enabled = requestJson.optBoolean("enabled", true);

            if (scope == null || scope.trim().isEmpty()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return result.put("success", false).put("error", "scope required");
            }

            // Convert the browse-tree scope to a canonical scope selector before handing it to
            // the device (defect B3 lineage): "Controller:Global" -> "" (controller scope),
            // "Programs" -> "Program:", "Programs/MainProgram" -> "Program:MainProgram". Scope
            // membership against canonical NodeIds is decided by AddressPolicy.matchesScope in
            // TagSimulationFacade.
            String opcuaScope = convertToNodeIdPath(scope);

            if (enabled) {
                device.enableSimulationByScope(opcuaScope);
            } else {
                device.disableSimulationByScope(opcuaScope);
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
    public JSONObject handleBulkSimulationAll(RequestContext ctx, HttpServletResponse resp) throws JSONException {
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
        if (!device.isSimulationEngineAvailable()) {
            resp.setStatus(HttpServletResponse.SC_CONFLICT);
            return result.put("success", false)
                .put("error", "Simulation is disabled for this device. Enable the "
                    + "\"Enable Simulation\" setting in the device configuration first.");
        }

        try {
            String body = GatewayAuthHelper.readRequestContent(ctx, 10 * 1024);
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
}
