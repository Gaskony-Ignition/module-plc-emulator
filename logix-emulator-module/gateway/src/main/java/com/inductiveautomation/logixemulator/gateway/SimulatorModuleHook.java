package com.inductiveautomation.logixemulator.gateway;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.config.migration.IdbMigrationStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.AbstractDeviceModuleHook;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.inductiveautomation.ignition.gateway.web.systemjs.SystemJsModule;
import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorExtensionPoint;
import com.inductiveautomation.logixemulator.gateway.web.FileUploadRoutes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorDevice;

/**
 * Module hook for the Logix PLC Emulator.
 * This registers the device driver with Ignition's device connection system.
 */
public class SimulatorModuleHook extends AbstractDeviceModuleHook {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private GatewayContext context;

    // Device registry for file upload routes to access devices
    private static final Map<String, LogixEmulatorDevice> deviceRegistry = new ConcurrentHashMap<>();

    @Override
    public void setup(GatewayContext context) {
        this.context = context;
        logger.info("Logix PLC Emulator module setup - GatewayContext initialized: {}", (context != null));

        // Register WebUI component for Connection Browser (combined tag browser + file upload)
        try {
            SystemJsModule connectionBrowserModule = new SystemJsModule(
                "LogixConnectionBrowser",
                "/res/logixemulator/LogixConnectionBrowser.js"
            );

            // Add navigation menu item in the Connections section
            context.getWebResourceManager().getNavigationModel().getConnections()
                .addCategory("logixemulator", cat -> cat
                    .label("Logix PLC Emulator")
                    .addPage("Connection Browser", page -> page
                        .position(10)
                        .mount("/logix-connection-browser", "LogixConnectionBrowser", connectionBrowserModule)
                    )
                );

            logger.info("Added 'Logix PLC Emulator' menu item to Gateway Config:");
            logger.info("  - Connection Browser: /app/logix-connection-browser");
        } catch (Exception e) {
            logger.error("Failed to add WebUI navigation menu item", e);
        }
    }

    @Override
    public void startup(LicenseState licenseState) {
        logger.info("Logix PLC Emulator module starting...");

        // Register resource bundle for i18n support
        BundleUtil.get().addBundle(
            "LogixEmulator",
            LogixEmulatorExtensionPoint.class,
            "LogixEmulator"
        );
        logger.info("Registered LogixEmulator resource bundle");

        logger.info("Logix PLC Emulator module started successfully (using built-in Java parsers)");
    }

    @Override
    public void shutdown() {
        logger.info("Logix PLC Emulator module shutting down");
        logger.info("Logix PLC Emulator module shutdown complete");
    }

    /**
     * Returns the list of device extension points provided by this module.
     * This makes "Logix PLC Emulator" appear in the device type dropdown.
     */
    @Override
    protected List<DeviceExtensionPoint<?>> getDeviceExtensionPoints() {
        return List.of(new LogixEmulatorExtensionPoint());
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
     * Routes will be available at /main/data/logixemulator/*
     */
    @Override
    public void mountRouteHandlers(RouteGroup routes) {
        try {
            FileUploadRoutes uploadRoutes = new FileUploadRoutes(context, routes);
            uploadRoutes.mountRoutes();
            logger.info("File upload routes mounted at /data/logixemulator/*");
        } catch (Exception e) {
            logger.error("Failed to mount file upload routes", e);
        }
    }

    /**
     * Mount web resources from the "mounted" folder.
     * Files in the mounted/ directory will be accessible at /res/logixemulator/*
     *
     * IMPORTANT: Ignition automatically adds the /res/logixemulator prefix based on
     * getMountPathAlias(). Do NOT replicate this path structure in your filesystem.
     *
     * Example mapping:
     *   Filesystem: gateway/src/main/resources/mounted/simple-upload.html
     *   URL:        /res/logixemulator/simple-upload.html
     */
    @Override
    public Optional<String> getMountedResourceFolder() {
        return Optional.of("mounted");
    }

    /**
     * Return the mount path alias for web resources.
     * This alias determines the URL prefix for BOTH resources and data routes.
     *
     * Public resources (from getMountedResourceFolder) at /res/logixemulator/*:
     * - /res/logixemulator/index.html - Redirect page to authenticated upload
     * - /res/logixemulator/plc-file-upload.js - Form enhancement script
     *
     * Authenticated data routes (from mountRouteHandlers) at /data/logixemulator/*:
     * - /data/logixemulator/connection-browser - Connection Browser page (requires login)
     * - /data/logixemulator/edit-program - Edit program page (requires login)
     * - /data/logixemulator/upload - File upload endpoint (requires login)
     * - /data/logixemulator/devices - List devices (requires login)
     * - /data/logixemulator/health - Health check (public)
     *
     * Legacy routes (redirect to Connection Browser):
     * - /data/logixemulator/page - Redirects to connection-browser
     * - /data/logixemulator/tag-browser - Redirects to connection-browser
     *
     * SECURITY: HTML pages are served through authenticated routes (/data/*)
     * to ensure only logged-in users can access the upload functionality.
     */
    @Override
    public Optional<String> getMountPathAlias() {
        return Optional.of("logixemulator");
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
    public static void registerDevice(String deviceName, LogixEmulatorDevice device) {
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
    public static Collection<LogixEmulatorDevice> getRegisteredDevices() {
        return deviceRegistry.values();
    }

    /**
     * Find a device by name.
     */
    public static Optional<LogixEmulatorDevice> findDeviceByName(String deviceName) {
        return Optional.ofNullable(deviceRegistry.get(deviceName));
    }

}
