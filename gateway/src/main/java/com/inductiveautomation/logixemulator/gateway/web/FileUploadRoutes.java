package com.inductiveautomation.logixemulator.gateway.web;

import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.logixemulator.gateway.DeviceRegistry;
import com.inductiveautomation.logixemulator.gateway.web.controller.DeviceController;
import com.inductiveautomation.logixemulator.gateway.web.controller.SimulationController;
import com.inductiveautomation.logixemulator.gateway.web.controller.SystemController;
import com.inductiveautomation.logixemulator.gateway.web.controller.TagController;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Thin router: mounts all 18 API routes and delegates each to the appropriate controller.
 *
 * Extracted controllers handle the actual request logic:
 * <ul>
 *   <li>{@link DeviceController}    — upload, list, status, delete</li>
 *   <li>{@link TagController}       — tags, children, live values</li>
 *   <li>{@link SimulationController}— write, toggle, bulk simulation</li>
 *   <li>{@link SystemController}    — health, stats, logs, auth</li>
 * </ul>
 */
public class FileUploadRoutes {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadRoutes.class);

    private final RouteGroup routes;
    private final DeviceController deviceController;
    private final TagController tagController;
    private final SimulationController simulationController;
    private final SystemController systemController;

    public FileUploadRoutes(GatewayContext context, RouteGroup routes, DeviceRegistry registry) {
        this.routes = routes;

        DeviceFileManager deviceManager = new DeviceFileManager(context, registry);

        RateLimiter rateLimiter      = new RateLimiter();
        RateLimiter readRateLimiter  = new RateLimiter(300, 3000, java.util.concurrent.TimeUnit.HOURS.toMillis(1));
        RateLimiter writeRateLimiter = new RateLimiter(60,  600,  java.util.concurrent.TimeUnit.HOURS.toMillis(1));

        this.deviceController     = new DeviceController(deviceManager, registry, rateLimiter, writeRateLimiter);
        this.tagController        = new TagController(deviceManager, readRateLimiter);
        this.simulationController = new SimulationController(deviceManager, writeRateLimiter);
        this.systemController     = new SystemController(context, registry, readRateLimiter);
    }

    public void mountRoutes() {
        // Device routes
        mountRoute(Routes.UPLOAD,                  deviceController::handleFileUpload,            HttpMethod.POST);
        mountRoute(Routes.DEVICES,                 deviceController::handleListDevices,           null);
        mountRoute(Routes.DEVICE_STATUS,           deviceController::handleDeviceStatus,          null);
        mountRoute(Routes.DEVICE_DELETE,           deviceController::handleDeleteFile,            HttpMethod.DELETE);

        // Tag routes
        mountRoute(Routes.DEVICE_TAGS,             tagController::handleGetTags,               null);
        mountRoute(Routes.DEVICE_TAGS_CHILDREN,    tagController::handleGetTagChildren,        null);
        mountRoute(Routes.DEVICE_TAGS_LIVE,        tagController::handleGetLiveTags,           null);

        // Simulation routes
        mountRoute(Routes.DEVICE_TAGS_SIMULATED,   simulationController::handleGetSimulatedTags,      null);
        mountRoute(Routes.DEVICE_TAG_WRITE,        simulationController::handleWriteTag,              HttpMethod.POST);
        mountRoute(Routes.DEVICE_TAG_SIMULATE,     simulationController::handleToggleTagSimulation,   HttpMethod.POST);
        mountRoute(Routes.DEVICE_SIMULATION_SCOPE, simulationController::handleBulkSimulationByScope, HttpMethod.POST);
        mountRoute(Routes.DEVICE_SIMULATION_ALL,   simulationController::handleBulkSimulationAll,     HttpMethod.POST);

        // System routes
        mountRoute(Routes.SYSTEM_STATS, systemController::handleSystemStats, null);
        mountRoute(Routes.SYSTEM_LOGS,  systemController::handleSystemLogs,  null);

        // Public routes — no authentication required
        mountPublicRoute(Routes.HEALTH,      systemController::handleHealthCheck);
        mountPublicRoute(Routes.AUTH_STATUS, systemController::handleAuthStatus);
        mountPublicRoute(Routes.AUTH_CHECK,  systemController::handleAuthCheck);

        logger.info("File upload routes mounted at /data/logixemulator/");
        logger.info("  - Live tags API: /data/logixemulator/device/:name/tags/live");
        logger.info("  - Write tag API: /data/logixemulator/device/:name/tag/write");
    }

    // -------------------------------------------------------------------------
    // Route mounting utilities
    // -------------------------------------------------------------------------

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
    public interface RouteHandler {
        Object handle(RequestContext ctx, HttpServletResponse resp) throws JSONException;
    }
}
