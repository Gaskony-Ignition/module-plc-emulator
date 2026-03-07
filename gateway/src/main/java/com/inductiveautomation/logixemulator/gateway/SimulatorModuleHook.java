package com.inductiveautomation.logixemulator.gateway;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.config.migration.IdbMigrationStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.AbstractDeviceModuleHook;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.inductiveautomation.ignition.gateway.web.systemjs.SystemJsModule;
import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorDevice;
import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorExtensionPoint;
import com.inductiveautomation.logixemulator.gateway.web.FileUploadRoutes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module hook for the Logix PLC Emulator.
 * Registers the device driver with Ignition's device connection system and implements
 * {@link DeviceRegistry} so it can be injected into the web layer without static coupling.
 */
public class SimulatorModuleHook extends AbstractDeviceModuleHook implements DeviceRegistry {

    private static volatile SimulatorModuleHook INSTANCE;

    /** Returns the singleton instance (set during {@link #setup}). May be {@code null} before module startup. */
    public static SimulatorModuleHook getInstance() {
        return INSTANCE;
    }

    private static final Logger logger = LoggerFactory.getLogger(SimulatorModuleHook.class);
    private GatewayContext context;

    // Device registry for file upload routes to access devices
    private static final Map<String, LogixEmulatorDevice> deviceRegistry = new ConcurrentHashMap<>();

    @Override
    public void setup(GatewayContext context) {
        INSTANCE = this;
        this.context = context;
        logger.info("Logix PLC Emulator module setup - GatewayContext initialized: {}", (context != null));

        // Register WebUI component for the Devices page (combined tag browser + file upload)
        try {
            SystemJsModule connectionBrowserModule = new SystemJsModule(
                "LogixConnectionBrowser",
                "/res/logixemulator/LogixConnectionBrowser.js"
            );

            // Add navigation menu item in the Connections section
            context.getWebResourceManager().getNavigationModel().getConnections()
                .addCategory("logixemulator", cat -> cat
                    .label("Logix PLC Emulator")
                    .addPage("Devices", page -> page
                        .position(10)
                        .mount("/logix-connection-browser", "LogixConnectionBrowser", connectionBrowserModule)
                    )
                );

            logger.info("Added 'Logix PLC Emulator' menu item to Gateway Config:");
            logger.info("  - Devices: /app/logix-connection-browser");
        } catch (Exception e) {
            logger.error("Failed to add WebUI navigation menu item", e);
        }
    }

    @Override
    public void startup(LicenseState licenseState) {
        logger.info("Logix PLC Emulator module starting...");

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

    @Override
    protected List<DeviceExtensionPoint<?>> getDeviceExtensionPoints() {
        return List.of(new LogixEmulatorExtensionPoint());
    }

    @Override
    public List<IdbMigrationStrategy> getRecordMigrationStrategies() {
        return List.of();
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }

    /**
     * Mount HTTP routes, passing {@code this} as the {@link DeviceRegistry} so the
     * web layer has no static dependency on {@code SimulatorModuleHook}.
     */
    @Override
    public void mountRouteHandlers(RouteGroup routes) {
        try {
            FileUploadRoutes uploadRoutes = new FileUploadRoutes(context, routes, this);
            uploadRoutes.mountRoutes();
            logger.info("File upload routes mounted at /data/logixemulator/*");
        } catch (Exception e) {
            logger.error("Failed to mount file upload routes", e);
        }
    }

    @Override
    public Optional<String> getMountedResourceFolder() {
        return Optional.of("mounted");
    }

    @Override
    public Optional<String> getMountPathAlias() {
        return Optional.of("logixemulator");
    }

    public GatewayContext getGatewayContext() {
        return context;
    }

    // =========================================================================
    // DeviceRegistry — instance methods (delegate to static map)
    // =========================================================================

    @Override
    public Optional<LogixEmulatorDevice> findDeviceByName(String name) {
        return Optional.ofNullable(deviceRegistry.get(name));
    }

    @Override
    public Collection<LogixEmulatorDevice> getRegisteredDevices() {
        return deviceRegistry.values();
    }

    @Override
    public void registerDevice(String name, LogixEmulatorDevice device) {
        deviceRegistry.put(name, device);
        logger.info("Device registered: {}", name);
    }

    @Override
    public void unregisterDevice(String name) {
        deviceRegistry.remove(name);
        logger.info("Device unregistered: {}", name);
    }

}
