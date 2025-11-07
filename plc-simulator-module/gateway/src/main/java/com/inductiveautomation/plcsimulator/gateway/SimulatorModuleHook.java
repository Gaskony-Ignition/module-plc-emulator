package com.inductiveautomation.plcsimulator.gateway;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.config.migration.IdbMigrationStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.AbstractDeviceModuleHook;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.inductiveautomation.plcsimulator.gateway.device.EnhancedSimulatorExtensionPoint;
import com.inductiveautomation.plcsimulator.gateway.web.FileUploadRoutes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Module hook for the Enhanced PLC Simulator.
 * This registers the device driver with Ignition's device connection system.
 */
public class SimulatorModuleHook extends AbstractDeviceModuleHook {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private GatewayContext context;
    private ParserService parserService;

    @Override
    public void setup(GatewayContext context) {
        this.context = context;
        logger.info("Enhanced PLC Simulator module setup");
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

        // Start parser service (shared by all device instances)
        // NOTE: Parser service is optional - devices will use built-in Java parsers if unavailable
        try {
            parserService = new ParserService(logger, "localhost", 5000);
            parserService.start();
            logger.info("Parser service started on localhost:5000");
        } catch (Exception e) {
            logger.warn("Parser service not available (Python executable not bundled). Devices will use built-in parsers.", e);
            parserService = null;
        }

        logger.info("Enhanced PLC Simulator module started successfully");
    }

    @Override
    public void shutdown() {
        logger.info("Enhanced PLC Simulator module shutting down");

        // Stop parser service
        if (parserService != null) {
            try {
                parserService.stop();
                logger.info("Parser service stopped");
            } catch (Exception e) {
                logger.error("Error stopping parser service", e);
            }
        }

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
     * Get the parser service for use by devices.
     */
    public ParserService getParserService() {
        return parserService;
    }

    /**
     * Mount HTTP routes for file upload functionality.
     * Routes will be available at /main/data/plcsimulator/*
     */
    @Override
    public void mountRouteHandlers(RouteGroup routes) {
        logger.info("Mounting file upload routes...");
        new FileUploadRoutes(context, routes).mountRoutes();
    }

    /**
     * Mount web resources from the "mounted" folder.
     * This makes plc-file-upload.js accessible at /res/plcsimulator/plc-file-upload.js
     */
    @Override
    public Optional<String> getMountedResourceFolder() {
        return Optional.of("mounted");
    }

    /**
     * Return the mount path alias for web resources.
     * Resources will be available at /res/plcsimulator/*
     */
    @Override
    public Optional<String> getMountPathAlias() {
        return Optional.of("plcsimulator");
    }

}
