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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
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
     * Routes will be available at /data/plcsimulator/*
     */
    public void mountRoutes() {
        try {
            logger.info("Mounting /upload route...");
            routes.newRoute("/upload")
                .handler(this::handleFileUpload)
                .method(HttpMethod.POST)
                .accessControl(this::checkAuthenticated)
                .mount();
            logger.info("✓ /upload route mounted (POST, requires authentication)");
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
            logger.info("Mounting /device/:name/status route...");
            routes.newRoute("/device/:name/status")
                .handler(this::handleDeviceStatus)
                .accessControl(this::checkAuthenticated)
                .mount();
            logger.info("✓ /device/:name/status route mounted (requires authentication)");
        } catch (Exception e) {
            logger.error("Failed to mount /device/:name/status route", e);
        }

        try {
            logger.info("Mounting /device/:name/delete route...");
            routes.newRoute("/device/:name/delete")
                .handler(this::handleDeleteFile)
                .method(HttpMethod.DELETE)
                .accessControl(this::checkAuthenticated)
                .mount();
            logger.info("✓ /device/:name/delete route mounted (DELETE, requires authentication)");
        } catch (Exception e) {
            logger.error("Failed to mount /device/:name/delete route", e);
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

        try {
            logger.info("Mounting /auth/status route...");
            routes.newRoute("/auth/status")
                .handler(this::handleAuthStatus)
                .accessControl(req -> RouteAccess.GRANTED)  // Public endpoint - returns auth status
                .mount();
            logger.info("✓ /auth/status route mounted (public)");
        } catch (Exception e) {
            logger.error("Failed to mount /auth/status route", e);
        }

        try {
            logger.info("Mounting /page route (authenticated HTML page)...");
            routes.newRoute("/page")
                .handler(this::handleUploadPage)
                .accessControl(this::checkAuthenticated)  // Requires authentication
                .mount();
            logger.info("✓ /page route mounted (requires authentication) - accessible at /data/plcsimulator/page");
        } catch (Exception e) {
            logger.error("Failed to mount /page route", e);
        }

        logger.info("File upload routes mounting complete at /data/plcsimulator/");
    }

    /**
     * Handle file upload requests with device update.
     */
    private JSONObject handleFileUpload(RequestContext context, HttpServletResponse response) throws JSONException {
        JSONObject result = new JSONObject();

        try {
            String deviceName = context.getRequest().getParameter("device");

            // SECURITY: Check Content-Length BEFORE reading to prevent DoS
            long contentLength = context.getRequest().getContentLengthLong();
            long maxSize = FileValidator.getMaxFileSizeMB() * 1024 * 1024; // Convert MB to bytes

            if (contentLength > maxSize) {
                response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                result.put("success", false);
                result.put("error", String.format(
                    "File too large: %d MB exceeds maximum %d MB",
                    contentLength / (1024 * 1024),
                    FileValidator.getMaxFileSizeMB()
                ));
                logger.warn("Rejected oversized upload: {} bytes (max {} bytes)",
                    contentLength, maxSize);
                return result;
            }

            if (contentLength < 0) {
                // Content-Length header not provided - will check during read
                logger.debug("Content-Length not provided, will enforce limit during read");
            }

            // Read file content from request body with size enforcement
            String fileContent;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(context.getRequest().getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder content = new StringBuilder();
                char[] buffer = new char[8192];
                int charsRead;
                long totalRead = 0;

                while ((charsRead = reader.read(buffer)) != -1) {
                    totalRead += charsRead;

                    // Enforce limit during read (in case Content-Length was not provided)
                    if (totalRead > maxSize) {
                        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                        result.put("success", false);
                        result.put("error", String.format(
                            "File size exceeds maximum allowed: %d MB",
                            FileValidator.getMaxFileSizeMB()
                        ));
                        logger.warn("Upload exceeded size limit during read: {} bytes", totalRead);
                        return result;
                    }

                    content.append(buffer, 0, charsRead);
                }
                fileContent = content.toString();
            }

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

            // Validate file content (additional validation beyond size)
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

            // Debug logging to help troubleshoot empty device lists
            if (allDevices.isEmpty()) {
                logger.warn("No Enhanced PLC Simulator devices found in registry!");
                logger.warn("Devices must call SimulatorModuleHook.registerDevice() during onStartup()");
                logger.warn("Check that devices are configured at: Config → OPC UA → Device Connections");
            } else {
                logger.debug("Registered device names: {}",
                    allDevices.stream()
                        .map(EnhancedSimulatorDevice::getName)
                        .collect(java.util.stream.Collectors.toList()));
            }

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
     * Handle authentication status check.
     * Returns whether the current user is authenticated.
     * This is a public endpoint so JavaScript can check auth status.
     *
     * IMPROVED: Instead of just checking session existence, we verify the session
     * is valid by checking if the REQUEST would pass our authentication check.
     */
    private JSONObject handleAuthStatus(RequestContext context, HttpServletResponse response) throws JSONException {
        JSONObject result = new JSONObject();

        try {
            // Use the ACTUAL checkAuthenticated logic to see if they're really authenticated
            RouteAccess access = checkAuthenticated(context);
            boolean isAuthenticated = (access == RouteAccess.GRANTED);

            String username = "";
            if (isAuthenticated) {
                jakarta.servlet.http.HttpServletRequest httpRequest = context.getRequest();
                // Try to get username from various sources
                if (httpRequest.getRemoteUser() != null) {
                    username = httpRequest.getRemoteUser();
                } else if (httpRequest.getUserPrincipal() != null) {
                    username = httpRequest.getUserPrincipal().getName();
                } else {
                    username = "gateway-user";
                }
                logger.debug("Auth status: authenticated, user: {}", username);
            } else {
                logger.debug("Auth status: not authenticated");
            }

            response.setStatus(HttpServletResponse.SC_OK);
            result.put("authenticated", isAuthenticated);
            result.put("username", username);
            result.put("loginUrl", "/app/home");

        } catch (Exception e) {
            logger.error("Error checking auth status", e);
            response.setStatus(HttpServletResponse.SC_OK);
            result.put("authenticated", false);
            result.put("username", "");
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Serve the upload HTML page with authentication.
     * This replaces the public /res/plcsimulator/simple-upload.html resource.
     * Accessible at /data/plcsimulator/page (requires Gateway login).
     */
    private Object handleUploadPage(RequestContext context, HttpServletResponse response) {
        try {
            // Read the HTML file from resources
            String htmlContent = readResourceFile("/mounted/simple-upload.html");

            if (htmlContent == null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                response.setContentType("text/plain");
                response.getWriter().write("Upload page not found");
                return null;
            }

            // Update the HTML to work from this new path
            // Replace API endpoint references to use correct paths
            htmlContent = htmlContent
                .replace("/data/plcsimulator/devices", "/data/plcsimulator/devices")
                .replace("/data/plcsimulator/upload", "/data/plcsimulator/upload");

            // Serve the HTML
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/html; charset=UTF-8");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(htmlContent);

            logger.debug("Served upload page to authenticated user");

        } catch (Exception e) {
            logger.error("Error serving upload page", e);
            try {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.setContentType("text/plain");
                response.getWriter().write("Error loading upload page: " + e.getMessage());
            } catch (Exception ex) {
                logger.error("Error writing error response", ex);
            }
        }
        return null;
    }

    /**
     * Read a resource file from the classpath.
     */
    private String readResourceFile(String resourcePath) {
        try {
            var inputStream = getClass().getResourceAsStream(resourcePath);
            if (inputStream == null) {
                logger.error("Resource not found: {}", resourcePath);
                return null;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
                return content.toString();
            }
        } catch (Exception e) {
            logger.error("Error reading resource file: {}", resourcePath, e);
            return null;
        }
    }

    /**
     * Handle device status requests.
     */
    private JSONObject handleDeviceStatus(RequestContext requestContext, HttpServletResponse response) throws JSONException {
        JSONObject result = new JSONObject();

        try {
            // Get device name from route path parameter
            // Route: /device/:name/status
            String deviceName = requestContext.getParameter("name");

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

            // Check if file exists on disk
            // IMPORTANT: Use the device's internal currentFilePath instead of config fileName
            // because the fileName config field may not be populated after file upload
            String fileName = simConfig.parser().fileName();
            boolean hasFile = false;
            long fileSize = 0;
            long lastModified = 0;
            String filePath = null;

            try {
                // Access the device's currentFilePath field via reflection
                Field filePathField = EnhancedSimulatorDevice.class.getDeclaredField("currentFilePath");
                filePathField.setAccessible(true);
                String currentFilePath = (String) filePathField.get(device);

                if (currentFilePath != null && !currentFilePath.isEmpty()) {
                    File currentFile = new File(currentFilePath);
                    if (currentFile.exists()) {
                        hasFile = true;
                        fileSize = currentFile.length();
                        lastModified = currentFile.lastModified();
                        filePath = currentFile.getAbsolutePath();

                        // Extract actual filename from path if config fileName is not set
                        if (fileName == null || fileName.isEmpty()) {
                            fileName = currentFile.getName();
                            // Remove device-specific prefix if present (format: DeviceName_filename)
                            String prefix = deviceName + "_";
                            if (fileName.startsWith(prefix)) {
                                fileName = fileName.substring(prefix.length());
                            }
                        }
                    }
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                logger.warn("Could not access currentFilePath field, falling back to config fileName", e);

                // Fall back to old logic using config fileName
                if (fileName != null && !fileName.isEmpty()) {
                    try {
                        File dataDir = context.getSystemManager().getDataDir();
                        File storageDir = new File(dataDir, "plc-simulator");

                        // Use secure path validation
                        File deviceFile = validateFilePath(storageDir, deviceName, fileName);

                        if (deviceFile.exists()) {
                            hasFile = true;
                            fileSize = deviceFile.length();
                            lastModified = deviceFile.lastModified();
                            filePath = deviceFile.getAbsolutePath();
                        }
                    } catch (SecurityException secEx) {
                        logger.error("Invalid file path for device {}: {}", deviceName, secEx.getMessage());
                    }
                }
            }

            response.setStatus(HttpServletResponse.SC_OK);
            result.put("success", true);
            result.put("deviceName", deviceName);
            result.put("status", device.getStatus());
            result.put("fileName", fileName != null ? fileName : "");
            result.put("hasFile", hasFile);
            result.put("fileSize", fileSize);
            result.put("lastModified", lastModified);
            result.put("filePath", filePath);
            result.put("parserType", simConfig.parser().parserType().getDisplayName());
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
     * Handle delete file requests.
     */
    private JSONObject handleDeleteFile(RequestContext requestContext, HttpServletResponse response) throws JSONException {
        JSONObject result = new JSONObject();

        try {
            // Get device name from route path parameter
            // Route: /device/:name/delete
            String deviceName = requestContext.getParameter("name");

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
            String fileName = simConfig.parser().fileName();

            if (fileName == null || fileName.isEmpty()) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                result.put("success", false);
                result.put("error", "No file configured for device: " + deviceName);
                return result;
            }

            // Find and delete the file with secure path validation
            File dataDir = context.getSystemManager().getDataDir();
            File storageDir = new File(dataDir, "plc-simulator");

            try {
                // Use secure path validation to prevent directory traversal
                File deviceFile = validateFilePath(storageDir, deviceName, fileName);

                if (deviceFile.exists()) {
                    boolean deleted = deviceFile.delete();
                    if (deleted) {
                    logger.info("Deleted file for device {}: {}", deviceName, deviceFile.getAbsolutePath());

                    response.setStatus(HttpServletResponse.SC_OK);
                    result.put("success", true);
                    result.put("message", "File deleted successfully");
                    result.put("fileName", fileName);
                } else {
                        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                        result.put("success", false);
                        result.put("error", "Failed to delete file");
                    }
                } else {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    result.put("success", false);
                    result.put("error", "File not found on disk: " + fileName);
                }
            } catch (SecurityException se) {
                logger.error("Security violation: attempt to delete file outside allowed directory", se);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                result.put("success", false);
                result.put("error", "Invalid file path");
            }

            return result;

        } catch (Exception e) {
            logger.error("Error deleting file", e);
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
     * Saves file to disk and updates device's internal file path.
     */
    private boolean updateDeviceConfig(EnhancedSimulatorDevice device, String fileContent, String filename) {
        try {
            // Get the storage directory (same location as EnhancedSimulatorDevice.prepareFile() uses)
            File dataDir = context.getSystemManager().getDataDir();
            File storageDir = new File(dataDir, "plc-simulator");

            if (!storageDir.exists()) {
                boolean created = storageDir.mkdirs();
                if (!created) {
                    logger.error("Failed to create storage directory: {}", storageDir.getAbsolutePath());
                    return false;
                }
                logger.info("Created storage directory: {}", storageDir.getAbsolutePath());
            }

            // Sanitize filename to prevent directory traversal attacks
            String sanitizedFileName = sanitizeFileName(filename);

            // Save with device-specific name to prevent conflicts between devices
            // Format: {DeviceName}_{originalFilename}
            String deviceSpecificName = device.getName() + "_" + sanitizedFileName;
            File targetFile = new File(storageDir, deviceSpecificName);

            // Write file content to disk
            Files.writeString(
                targetFile.toPath(),
                fileContent,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );

            logger.info("Saved file to disk: {} ({} bytes)", targetFile.getAbsolutePath(), fileContent.length());

            // Update device's internal file path using reflection
            updateDeviceFilePath(device, targetFile.getAbsolutePath());

            logger.info("Device configuration updated successfully: {}", device.getName());
            return true;

        } catch (Exception e) {
            logger.error("Error updating device configuration", e);
            return false;
        }
    }

    /**
     * Reload a device to apply new configuration.
     * Uses reflection to trigger the existing handleFileChange() method.
     */
    private void reloadDevice(EnhancedSimulatorDevice device) {
        try {
            logger.info("Triggering device reload: {}", device.getName());

            // Get the device's current file path (we just updated it via reflection)
            Field filePathField = EnhancedSimulatorDevice.class.getDeclaredField("currentFilePath");
            filePathField.setAccessible(true);
            String filePath = (String) filePathField.get(device);

            if (filePath == null || filePath.isEmpty()) {
                logger.warn("No file path set for device: {}", device.getName());
                return;
            }

            File deviceFile = new File(filePath);
            if (!deviceFile.exists()) {
                logger.error("Device file does not exist: {}", filePath);
                return;
            }

            // Trigger hot-reload using the existing handleFileChange() method
            Method handleFileChangeMethod = EnhancedSimulatorDevice.class.getDeclaredMethod("handleFileChange", File.class);
            handleFileChangeMethod.setAccessible(true);
            handleFileChangeMethod.invoke(device, deviceFile);

            logger.info("✓ Device reloaded successfully: {}", device.getName());

        } catch (NoSuchFieldException | NoSuchMethodException e) {
            logger.error("Reflection error - device class structure may have changed", e);
        } catch (Exception e) {
            logger.error("Error during device reload: {}", device.getName(), e);
        }
    }

    /**
     * Check if the user is authenticated.
     * Uses Ignition's SecurityContext to verify proper authentication.
     *
     * SECURITY: This method implements proper authentication checking.
     * It does NOT rely on session age or other insecure fallbacks.
     */
    private RouteAccess checkAuthenticated(RequestContext req) {
        try {
            jakarta.servlet.http.HttpServletRequest httpRequest = req.getRequest();

            if (logger.isDebugEnabled()) {
                logger.debug("Authentication check for URI: {}", httpRequest.getRequestURI());
            }

            // Method 1: Check for Ignition SecurityContext (preferred)
            Object securityContext = httpRequest.getAttribute("com.inductiveautomation.ignition.gateway.security.SecurityContext");
            if (securityContext != null) {
                // Use reflection to check authentication (SecurityContext is internal API)
                Class<?> secContextClass = securityContext.getClass();
                try {
                    java.lang.reflect.Method isAuthMethod = secContextClass.getMethod("isAuthenticated");
                    Boolean isAuth = (Boolean) isAuthMethod.invoke(securityContext);

                    if (Boolean.TRUE.equals(isAuth)) {
                        logger.debug("Authentication granted via SecurityContext");
                        return RouteAccess.GRANTED;
                    } else {
                        logger.debug("Authentication denied - not authenticated");
                        return RouteAccess.UNAUTHORIZED;
                    }
                } catch (NoSuchMethodException e) {
                    // Try alternate method: check for user object
                    try {
                        java.lang.reflect.Method getUserMethod = secContextClass.getMethod("getUser");
                        Object user = getUserMethod.invoke(securityContext);

                        if (user != null) {
                            logger.debug("Authentication granted - user exists");
                            return RouteAccess.GRANTED;
                        }
                    } catch (Exception getUserEx) {
                        logger.warn("Could not determine authentication from SecurityContext", getUserEx);
                    }
                }
            }

            // Method 2: Check standard servlet authentication
            String remoteUser = httpRequest.getRemoteUser();
            java.security.Principal userPrincipal = httpRequest.getUserPrincipal();

            if (remoteUser != null || userPrincipal != null) {
                logger.debug("Authentication granted via servlet principal");
                return RouteAccess.GRANTED;
            }

            // Method 3: Check session for authenticated user marker
            jakarta.servlet.http.HttpSession session = httpRequest.getSession(false);
            if (session != null) {
                // Check for Ignition's authentication marker in session
                Object authMarker = session.getAttribute("web-auth-request-collection");
                if (authMarker != null) {
                    try {
                        // Check if authenticated via session attribute
                        Class<?> collectionClass = authMarker.getClass();
                        java.lang.reflect.Method isAuthMethod = collectionClass.getMethod("isAuthenticated");
                        Boolean isAuth = (Boolean) isAuthMethod.invoke(authMarker);

                        if (Boolean.TRUE.equals(isAuth)) {
                            logger.debug("Authentication granted via session auth collection");
                            return RouteAccess.GRANTED;
                        }
                    } catch (Exception e) {
                        logger.debug("Could not check session auth collection", e);
                    }
                }
            }

            // No authentication found
            logger.debug("Authentication denied - no valid authentication found");
            return RouteAccess.UNAUTHORIZED;

        } catch (Exception e) {
            logger.error("Error checking authentication", e);
            // Fail closed - deny access on error
            return RouteAccess.UNAUTHORIZED;
        }
    }

    /**
     * Sanitize filename to prevent directory traversal attacks.
     * Removes path separators and keeps only safe characters.
     *
     * SECURITY: Prevents path traversal attacks like "../../../etc/passwd"
     */
    private String sanitizeFileName(String filename) {
        if (filename == null || filename.isEmpty()) {
            throw new IllegalArgumentException("File name cannot be empty");
        }

        // Reject path traversal attempts
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            throw new SecurityException("Invalid filename: path traversal attempt detected");
        }

        // Reject null bytes (null byte injection attack)
        if (filename.contains("\0")) {
            throw new SecurityException("Invalid filename: null byte detected");
        }

        // Enforce length limit (255 is typical filesystem limit)
        if (filename.length() > 255) {
            throw new SecurityException("Invalid filename: too long (max 255 characters)");
        }

        // Extract just the filename (remove any path components using Paths API)
        String sanitized = java.nio.file.Paths.get(filename).getFileName().toString();

        // Verify no path separators remain after extraction
        if (sanitized.contains("/") || sanitized.contains("\\")) {
            throw new SecurityException("Invalid filename: contains path separators");
        }

        return sanitized;
    }

    /**
     * Sanitize device name to prevent directory traversal attacks.
     * Only allows alphanumeric characters, underscores, and hyphens.
     *
     * SECURITY: Device names are used in file paths and must be strictly validated.
     */
    private String sanitizeDeviceName(String deviceName) {
        if (deviceName == null || deviceName.isEmpty()) {
            throw new IllegalArgumentException("Device name cannot be empty");
        }

        // Reject path traversal attempts
        if (deviceName.contains("..") || deviceName.contains("/") ||
            deviceName.contains("\\") || deviceName.contains("\0")) {
            throw new SecurityException("Invalid device name: contains illegal characters");
        }

        // Only allow alphanumeric, underscore, hyphen
        if (!deviceName.matches("^[a-zA-Z0-9_-]+$")) {
            throw new SecurityException("Invalid device name: must contain only letters, numbers, underscores, and hyphens");
        }

        // Length limit
        if (deviceName.length() > 100) {
            throw new SecurityException("Invalid device name: too long (max 100 characters)");
        }

        return deviceName;
    }

    /**
     * Validate that a file path is within the allowed storage directory.
     * Prevents path traversal attacks by checking canonical paths.
     *
     * SECURITY: Critical check to prevent accessing files outside storage directory.
     */
    private File validateFilePath(File storageDir, String deviceName, String fileName) throws SecurityException {
        try {
            // Sanitize inputs
            String safeDeviceName = sanitizeDeviceName(deviceName);
            String safeFileName = sanitizeFileName(fileName);

            // Construct file path
            File deviceFile = new File(storageDir, safeDeviceName + "_" + safeFileName);

            // Get canonical paths to resolve any symlinks or relative paths
            String canonicalFilePath = deviceFile.getCanonicalPath();
            String canonicalStorageDir = storageDir.getCanonicalPath();

            // Verify the file is within the storage directory
            if (!canonicalFilePath.startsWith(canonicalStorageDir + File.separator)) {
                logger.error("Path traversal attempt detected: device={}, file={}, resolved={}",
                    deviceName, fileName, canonicalFilePath);
                throw new SecurityException("Invalid file path: outside storage directory");
            }

            return deviceFile;

        } catch (java.io.IOException e) {
            logger.error("Error validating file path", e);
            throw new SecurityException("Invalid file path", e);
        }
    }

    /**
     * Update a device's internal currentFilePath field using reflection.
     * This allows the device to know where the uploaded file is stored.
     */
    private void updateDeviceFilePath(EnhancedSimulatorDevice device, String filePath) throws Exception {
        try {
            Field filePathField = EnhancedSimulatorDevice.class.getDeclaredField("currentFilePath");
            filePathField.setAccessible(true);
            filePathField.set(device, filePath);
            logger.info("Updated device file path to: {}", filePath);
        } catch (NoSuchFieldException e) {
            logger.error("Failed to find currentFilePath field in EnhancedSimulatorDevice", e);
            throw e;
        } catch (IllegalAccessException e) {
            logger.error("Failed to access currentFilePath field", e);
            throw e;
        }
    }
}
