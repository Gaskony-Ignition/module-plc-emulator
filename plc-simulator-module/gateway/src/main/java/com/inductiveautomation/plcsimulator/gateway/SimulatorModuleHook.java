package com.inductiveautomation.plcsimulator.gateway;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.config.migration.IdbMigrationStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.AbstractDeviceModuleHook;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.inductiveautomation.ignition.gateway.web.systemjs.SystemJsModule;
import com.inductiveautomation.plcsimulator.gateway.device.EnhancedSimulatorExtensionPoint;
import com.inductiveautomation.plcsimulator.gateway.web.FileUploadRoutes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.inductiveautomation.plcsimulator.gateway.device.EnhancedSimulatorDevice;

/**
 * Module hook for the Enhanced PLC Simulator.
 * This registers the device driver with Ignition's device connection system.
 */
public class SimulatorModuleHook extends AbstractDeviceModuleHook {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private GatewayContext context;

    // Device registry for file upload routes to access devices
    private static final Map<String, EnhancedSimulatorDevice> deviceRegistry = new ConcurrentHashMap<>();

    @Override
    public void setup(GatewayContext context) {
        this.context = context;
        logger.info("Enhanced PLC Simulator module setup - GatewayContext initialized: {}", (context != null));

        // Register WebUI component for file upload page
        try {
            // Create SystemJsModule that points to our bundled React component
            SystemJsModule plcUploadModule = new SystemJsModule(
                "com.inductiveautomation.plcsimulator.PLCUpload",
                "/res/plcsimulator/plcUpload.js"
            );

            // Create SystemJsModule for Tag Browser
            SystemJsModule tagBrowserModule = new SystemJsModule(
                "com.inductiveautomation.plcsimulator.TagBrowser",
                "/res/plcsimulator/tagBrowser.js"
            );

            // Add navigation menu items in the Connections section
            context.getWebResourceManager().getNavigationModel().getConnections()
                .addCategory("plcsimulator", cat -> cat
                    .label("PLC Simulator")
                    .addPage("File Upload", page -> page
                        .position(10)
                        .mount("/plc-file-upload", "PLCUpload", plcUploadModule)
                    )
                    .addPage("Tag Browser", page -> page
                        .position(20)
                        .mount("/plc-tag-browser", "TagBrowser", tagBrowserModule)
                    )
                );

            logger.info("Added 'PLC Simulator' menu items to Gateway Config:");
            logger.info("  - File Upload:  /app/plc-file-upload");
            logger.info("  - Tag Browser:  /app/plc-tag-browser");
        } catch (Exception e) {
            logger.error("Failed to add WebUI navigation menu item", e);
        }
    }

    @Override
    public void startup(LicenseState licenseState) {
        logger.info("Enhanced PLC Simulator module starting...");

        // Register resource bundle for i18n support
        BundleUtil.get().addBundle(
            "EnhancedSimulator",
            EnhancedSimulatorExtensionPoint.class,
            "EnhancedSimulator"
        );
        logger.info("Registered EnhancedSimulator resource bundle");

        logger.info("Enhanced PLC Simulator module started successfully (using built-in Java parsers)");
    }

    @Override
    public void shutdown() {
        logger.info("Enhanced PLC Simulator module shutting down");
        logger.info("Enhanced PLC Simulator module shutdown complete");
    }

    /**
     * Returns the list of device extension points provided by this module.
     * This makes "Enhanced PLC Simulator" appear in the device type dropdown.
     */
    @Override
    protected List<DeviceExtensionPoint<?>> getDeviceExtensionPoints() {
        return List.of(new EnhancedSimulatorExtensionPoint());
    }

    /**
     * Database migration strategies.
     * Currently not needed as we use modern DeviceConfig records.
     */
    @Override
    public List<IdbMigrationStrategy> getRecordMigrationStrategies() {
        return List.of();
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }

    /**
     * Mount HTTP routes for file upload functionality.
     * Routes will be available at /main/data/plcsimulator/*
     */
    @Override
    public void mountRouteHandlers(RouteGroup routes) {
        try {
            logger.info("=== ROUTE MOUNTING DEBUG ===");
            logger.info("mountRouteHandlers called");
            logger.info("GatewayContext null? {}", (context == null));
            logger.info("RouteGroup null? {}", (routes == null));

            if (routes != null) {
                logger.info("RouteGroup class: {}", routes.getClass().getName());
                logger.info("RouteGroup toString: {}", routes.toString());

                // Try to extract the base path using reflection
                try {
                    java.lang.reflect.Method getBasePath = routes.getClass().getMethod("getBasePath");
                    getBasePath.setAccessible(true);
                    Object basePath = getBasePath.invoke(routes);
                    logger.info("RouteGroup base path (via reflection): {}", basePath);
                } catch (NoSuchMethodException e) {
                    logger.info("RouteGroup does not have getBasePath() method");
                } catch (Exception e) {
                    logger.warn("Could not extract base path from RouteGroup", e);
                }
            }

            if (context == null) {
                logger.error("GatewayContext is null - cannot mount routes! This should not happen.");
                return;
            }

            if (routes == null) {
                logger.error("RouteGroup is null - cannot mount routes! This should not happen.");
                return;
            }

            FileUploadRoutes uploadRoutes = new FileUploadRoutes(context, routes);
            uploadRoutes.mountRoutes();

            logger.info("File upload routes mounted successfully");
            logger.info("Routes should be accessible at /data/plcsimulator/* (based on getMountPathAlias)");
            logger.info("If routes return 404, check:");
            logger.info("  1. Authentication - routes require authenticated session");
            logger.info("  2. Base path - verify RouteGroup base path above");
            logger.info("  3. Test health endpoint: curl http://localhost:8088/data/plcsimulator/health");
            logger.info("=== END ROUTE MOUNTING DEBUG ===");
        } catch (Exception e) {
            logger.error("CRITICAL: Failed to mount file upload routes", e);
            e.printStackTrace();
            // Don't rethrow - we want the module to continue loading even if routes fail
        }
    }

    /**
     * Mount web resources from the "mounted" folder.
     * Files in the mounted/ directory will be accessible at /res/plcsimulator/*
     *
     * IMPORTANT: Ignition automatically adds the /res/plcsimulator prefix based on
     * getMountPathAlias(). Do NOT replicate this path structure in your filesystem.
     *
     * Example mapping:
     *   Filesystem: gateway/src/main/resources/mounted/simple-upload.html
     *   URL:        /res/plcsimulator/simple-upload.html
     */
    @Override
    public Optional<String> getMountedResourceFolder() {
        return Optional.of("mounted");
    }

    /**
     * Return the mount path alias for web resources.
     * This alias determines the URL prefix for BOTH resources and data routes.
     *
     * Public resources (from getMountedResourceFolder) at /res/plcsimulator/*:
     * - /res/plcsimulator/index.html - Redirect page to authenticated upload
     * - /res/plcsimulator/plc-file-upload.js - Form enhancement script
     *
     * Authenticated data routes (from mountRouteHandlers) at /data/plcsimulator/*:
     * - /data/plcsimulator/page - File upload page (requires login)
     * - /data/plcsimulator/edit-program - Edit program page (requires login)
     * - /data/plcsimulator/tag-browser - Tag browser page (requires login)
     * - /data/plcsimulator/upload - File upload endpoint (requires login)
     * - /data/plcsimulator/devices - List devices (requires login)
     * - /data/plcsimulator/health - Health check (public)
     *
     * SECURITY: HTML pages are served through authenticated routes (/data/*)
     * to ensure only logged-in users can access the upload functionality.
     */
    @Override
    public Optional<String> getMountPathAlias() {
        return Optional.of("plcsimulator");
    }

    /**
     * Get the gateway context for use by other components.
     */
    public GatewayContext getGatewayContext() {
        return context;
    }

    /**
     * Register a device instance when it starts up.
     * This allows FileUploadRoutes to find devices by name.
     */
    public static void registerDevice(String deviceName, EnhancedSimulatorDevice device) {
        deviceRegistry.put(deviceName, device);
        LoggerFactory.getLogger(SimulatorModuleHook.class)
            .info("Device registered: {}", deviceName);
    }

    /**
     * Unregister a device instance when it shuts down.
     */
    public static void unregisterDevice(String deviceName) {
        deviceRegistry.remove(deviceName);
        LoggerFactory.getLogger(SimulatorModuleHook.class)
            .info("Device unregistered: {}", deviceName);
    }

    /**
     * Get all registered devices.
     */
    public static Collection<EnhancedSimulatorDevice> getRegisteredDevices() {
        return deviceRegistry.values();
    }

    /**
     * Find a device by name.
     */
    public static Optional<EnhancedSimulatorDevice> findDeviceByName(String deviceName) {
        return Optional.ofNullable(deviceRegistry.get(deviceName));
    }

}
