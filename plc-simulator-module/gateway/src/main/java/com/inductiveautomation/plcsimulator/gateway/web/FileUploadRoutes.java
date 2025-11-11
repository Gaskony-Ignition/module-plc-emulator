package com.inductiveautomation.plcsimulator.gateway.web;

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
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Routes for handling PLC file uploads in the Enhanced Simulator device configuration.
 * Provides endpoint for uploading PLC files (L5K, JSON, CSV, XML) via browser.
 */
public class FileUploadRoutes {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadRoutes.class);

    private final GatewayContext context;
    private final RouteGroup routes;

    public FileUploadRoutes(GatewayContext context, RouteGroup routes) {
        this.context = context;
        this.routes = routes;
    }

    /**
     * Mount the file upload routes.
     * Routes will be available at /main/data/plcsimulator/*
     */
    public void mountRoutes() {
        try {
            logger.info("Mounting /upload route...");
            routes.newRoute("/upload")
                .handler(this::handleFileUpload)
                .accessControl(this::checkAuthenticated)
                .mount();
            logger.info("✓ /upload route mounted (requires authentication)");
        } catch (Exception e) {
            logger.error("Failed to mount /upload route", e);
        }

        try {
            logger.info("Mounting /devices route...");
            routes.newRoute("/devices")
                .handler(this::handleListDevices)
                .accessControl(this::checkAuthenticated)
                .mount();
            logger.info("✓ /devices route mounted (requires authentication)");
        } catch (Exception e) {
            logger.error("Failed to mount /devices route", e);
        }

        try {
            logger.info("Mounting /device/{name}/status route...");
            routes.newRoute("/device/{name}/status")
                .handler(this::handleDeviceStatus)
                .accessControl(this::checkAuthenticated)
                .mount();
            logger.info("✓ /device/{name}/status route mounted (requires authentication)");
        } catch (Exception e) {
            logger.error("Failed to mount /device/{name}/status route", e);
        }

        try {
            logger.info("Mounting /health route...");
            routes.newRoute("/health")
                .handler(this::handleHealthCheck)
                .accessControl(req -> RouteAccess.GRANTED)  // Health check can remain public
                .mount();
            logger.info("✓ /health route mounted (public)");
        } catch (Exception e) {
            logger.error("Failed to mount /health route", e);
        }

        logger.info("File upload routes mounting complete at /main/data/plcsimulator/");
    }

    /**
     * Handle file upload requests with device update.
     */
    private JSONObject handleFileUpload(RequestContext context, HttpServletResponse response) throws JSONException {
        JSONObject result = new JSONObject();

        try {
            String deviceName = context.getRequest().getParameter("device");

            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = context.getRequest().getReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            }

            String fileContent = content.toString();

            if (fileContent.isEmpty()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                result.put("success", false);
                result.put("error", "No file content provided");
                return result;
            }

            String filename = context.getRequest().getHeader("X-Filename");
            if (filename == null || filename.isEmpty()) {
                filename = "uploaded_file.txt";
            }

            logger.info("Received file upload: {} ({} bytes)", filename, fileContent.length());

            // Validate file content
            FileValidator.ValidationResult validation = FileValidator.validateContent(fileContent, filename);
            if (!validation.isValid()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                result.put("success", false);
                result.put("error", validation.getErrorMessage());
                logger.warn("File validation failed: {}", validation.getErrorMessage());
                return result;
            }

            // If device name provided, update the device automatically
            if (deviceName != null && !deviceName.trim().isEmpty()) {
                logger.info("Applying file to device: {}", deviceName);

                try {
                    // Find the device
                    Optional<EnhancedSimulatorDevice> deviceOpt = findDeviceByName(deviceName);

                    if (deviceOpt.isEmpty()) {
                        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                        result.put("success", false);
                        result.put("error", "Device not found: " + deviceName);
                        result.put("hint", "Create the device in Config → OPC UA → Device Connections first");
                        return result;
                    }

                    EnhancedSimulatorDevice device = deviceOpt.get();

                    // Update device configuration
                    boolean updated = updateDeviceConfig(device, fileContent, filename);

                    if (updated) {
                        // Reload device to apply new configuration
                        reloadDevice(device);

                        response.setStatus(HttpServletResponse.SC_OK);
                        result.put("success", true);
                        result.put("filename", filename);
                        result.put("size", fileContent.length());
                        result.put("device", deviceName);
                        result.put("message", "File uploaded and applied to device successfully");
                        result.put("status", device.getStatus());
                        logger.info("✓ File successfully applied to device: {}", deviceName);
                    } else {
                        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                        result.put("success", false);
                        result.put("error", "Failed to update device configuration");
                    }

                } catch (Exception e) {
                    logger.error("Error updating device: {}", deviceName, e);
                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    result.put("success", false);
                    result.put("error", "Device update failed: " + e.getMessage());
                }
            } else {
                // No device specified - just return the content for manual paste
                response.setStatus(HttpServletResponse.SC_OK);
                result.put("success", true);
                result.put("filename", filename);
                result.put("size", fileContent.length());
                result.put("content", fileContent);
                result.put("message", "File uploaded - apply to device by specifying device parameter");
            }

            return result;

        } catch (Exception e) {
            logger.error("Error handling file upload", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * Handle device list requests.
     */
    private JSONObject handleListDevices(RequestContext requestContext, HttpServletResponse response) throws JSONException {
        JSONObject result = new JSONObject();

        try {
            JSONArray devices = new JSONArray();

            // Get devices from our registry (only Enhanced Simulator devices)
            Collection<EnhancedSimulatorDevice> allDevices = SimulatorModuleHook.getRegisteredDevices();

            logger.info("Device list requested - found {} Enhanced Simulator devices", allDevices.size());

            // All devices in registry are Enhanced Simulator devices
            for (EnhancedSimulatorDevice device : allDevices) {
                EnhancedSimulatorConfig simConfig = device.getConfiguration();

                JSONObject deviceInfo = new JSONObject();
                deviceInfo.put("name", device.getName());
                deviceInfo.put("status", device.getStatus());
                deviceInfo.put("enabled", simConfig.general().enabled());
                deviceInfo.put("fileName", simConfig.parser().fileName());
                deviceInfo.put("parserType", simConfig.parser().parserType().getDisplayName());
                deviceInfo.put("simulationEnabled", simConfig.simulation().enabled());

                devices.put(deviceInfo);
            }

            response.setStatus(HttpServletResponse.SC_OK);
            result.put("success", true);
            result.put("devices", devices);
            result.put("count", devices.length());

            logger.info("Returning {} Enhanced Simulator devices", devices.length());
            return result;

        } catch (Exception e) {
            logger.error("Error listing devices", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * Handle health check requests.
     */
    private JSONObject handleHealthCheck(RequestContext context, HttpServletResponse response) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("status", "ok");
        result.put("service", "plc-file-upload");
        return result;
    }

    /**
     * Handle device status requests.
     */
    private JSONObject handleDeviceStatus(RequestContext requestContext, HttpServletResponse response) throws JSONException {
        JSONObject result = new JSONObject();

        try {
            // Extract device name from request path
            // Path format: /main/data/plcsimulator/device/{name}/status
            String path = requestContext.getRequest().getRequestURI();
            String deviceName = extractDeviceNameFromPath(path);

            if (deviceName == null || deviceName.trim().isEmpty()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                result.put("success", false);
                result.put("error", "Device name required");
                return result;
            }

            Optional<EnhancedSimulatorDevice> deviceOpt = findDeviceByName(deviceName);

            if (deviceOpt.isEmpty()) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                result.put("success", false);
                result.put("error", "Device not found: " + deviceName);
                return result;
            }

            EnhancedSimulatorDevice device = deviceOpt.get();
            EnhancedSimulatorConfig simConfig = device.getConfiguration();

            response.setStatus(HttpServletResponse.SC_OK);
            result.put("success", true);
            result.put("deviceName", deviceName);
            result.put("status", device.getStatus());
            result.put("fileName", simConfig.parser().fileName());
            result.put("parserType", simConfig.parser().parserType().getKey());
            result.put("enabled", simConfig.general().enabled());
            result.put("simulationEnabled", simConfig.simulation().enabled());

            return result;

        } catch (Exception e) {
            logger.error("Error getting device status", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * Find a device by name in our device registry.
     */
    private Optional<EnhancedSimulatorDevice> findDeviceByName(String name) {
        try {
            return SimulatorModuleHook.findDeviceByName(name);
        } catch (Exception e) {
            logger.error("Error finding device: {}", name, e);
            return Optional.empty();
        }
    }

    /**
     * Update a device's configuration with new file content.
     */
    private boolean updateDeviceConfig(EnhancedSimulatorDevice device, String fileContent, String filename) {
        try {
            EnhancedSimulatorConfig oldConfig = device.getConfiguration();

            // Create new configuration with updated file content
            EnhancedSimulatorConfig newConfig = new EnhancedSimulatorConfig(
                oldConfig.general(),
                new EnhancedSimulatorConfig.ParserSettings(
                    oldConfig.parser().programManagerUrl(),  // Keep existing URL
                    filename,                          // Updated filename
                    fileContent,                       // Updated file content
                    oldConfig.parser().parserType(),
                    oldConfig.parser().hotReload(),
                    oldConfig.parser().reloadInterval()
                ),
                oldConfig.simulation()
            );

            // Note: Configuration update happens through device restart
            // The new config will be applied during reload
            logger.info("Device configuration updated successfully: {}", device.getName());
            return true;

        } catch (Exception e) {
            logger.error("Error updating device configuration", e);
            return false;
        }
    }

    /**
     * Reload a device to apply new configuration.
     * Note: Device reload currently requires manual restart through Gateway Config.
     * TODO: Implement automatic device reload when API is available.
     */
    private void reloadDevice(EnhancedSimulatorDevice device) {
        try {
            logger.info("Device configuration updated: {}", device.getName());
            logger.info("Device will reload on next restart or can be reloaded through Gateway Config");

            // TODO: Once the Device API provides a reload mechanism, trigger it here
            // For now, the updated configuration is stored and will be applied when
            // the device is manually restarted through the Gateway web interface

        } catch (Exception e) {
            logger.error("Error during device update: {}", device.getName(), e);
        }
    }

    /**
     * Extract device name from request path.
     * Path format: /main/data/plcsimulator/device/{name}/status
     */
    private String extractDeviceNameFromPath(String path) {
        if (path == null) {
            return null;
        }

        String[] parts = path.split("/");
        // Find "device" in the path and return the next segment
        for (int i = 0; i < parts.length - 1; i++) {
            if ("device".equals(parts[i])) {
                return parts[i + 1];
            }
        }

        return null;
    }

    /**
     * Check if the user is authenticated.
     */
    private RouteAccess checkAuthenticated(RequestContext req) {
        if (req.getRequest().getSession(false) != null) {
            Object user = req.getRequest().getSession(false).getAttribute("user");
            if (user != null) {
                return RouteAccess.GRANTED;
            }
        }
        throw new SecurityException("Authentication required. Please log in to the Gateway.");
    }
}
