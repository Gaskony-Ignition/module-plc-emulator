package com.inductiveautomation.plcsimulator.gateway.web;

import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteAccess;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;

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
                .accessControl(req -> RouteAccess.GRANTED)
                .mount();
            logger.info("✓ /upload route mounted");
        } catch (Exception e) {
            logger.error("Failed to mount /upload route", e);
        }

        try {
            logger.info("Mounting /devices route...");
            routes.newRoute("/devices")
                .handler(this::handleListDevices)
                .accessControl(req -> RouteAccess.GRANTED)
                .mount();
            logger.info("✓ /devices route mounted");
        } catch (Exception e) {
            logger.error("Failed to mount /devices route", e);
        }

        try {
            logger.info("Mounting /health route...");
            routes.newRoute("/health")
                .handler(this::handleHealthCheck)
                .accessControl(req -> RouteAccess.GRANTED)
                .mount();
            logger.info("✓ /health route mounted");
        } catch (Exception e) {
            logger.error("Failed to mount /health route", e);
        }

        logger.info("File upload routes mounting complete at /main/data/plcsimulator/");
    }

    /**
     * Handle file upload requests.
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

            if (deviceName != null && !deviceName.trim().isEmpty()) {
                logger.info("Received device-specific file upload for '{}': {} ({} bytes)",
                           deviceName, filename, fileContent.length());
            } else {
                logger.info("Received file upload: {} ({} bytes)", filename, fileContent.length());
            }

            response.setStatus(HttpServletResponse.SC_OK);
            result.put("success", true);
            result.put("filename", filename);
            result.put("size", fileContent.length());
            result.put("content", fileContent);

            if (deviceName != null && !deviceName.trim().isEmpty()) {
                result.put("device", deviceName);
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

            logger.info("Device list requested");

            response.setStatus(HttpServletResponse.SC_OK);
            result.put("success", true);
            result.put("devices", devices);
            result.put("message", "Device listing requires Gateway context integration");
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
