package com.inductiveautomation.plcsimulator.gateway.web;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
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
        // POST endpoint for file upload
        routes.newRoute("/upload")
            .handler(this::handleFileUpload)
            .mount();

        // GET endpoint for health check
        routes.newRoute("/health")
            .handler(this::handleHealthCheck)
            .mount();

        logger.info("File upload routes mounted at /main/data/plcsimulator/");
    }

    /**
     * Handle file upload requests.
     * Expects file content in request body as plain text.
     */
    private JSONObject handleFileUpload(RequestContext context, HttpServletResponse response) throws JSONException {
        try {
            // Read file content from request body
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
                return new JSONObject()
                    .put("success", false)
                    .put("error", "No file content provided");
            }

            // Get filename from header if provided
            String filename = context.getRequest().getHeader("X-Filename");
            if (filename == null || filename.isEmpty()) {
                filename = "uploaded_file.txt";
            }

            logger.info("Received file upload: {} ({} bytes)", filename, fileContent.length());

            // Return success with the content
            response.setStatus(HttpServletResponse.SC_OK);
            return new JSONObject()
                .put("success", true)
                .put("filename", filename)
                .put("size", fileContent.length())
                .put("content", fileContent);

        } catch (Exception e) {
            logger.error("Error handling file upload", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return new JSONObject()
                .put("success", false)
                .put("error", e.getMessage());
        }
    }

    /**
     * Handle health check requests.
     */
    private JSONObject handleHealthCheck(RequestContext context, HttpServletResponse response) throws JSONException {
        return new JSONObject()
            .put("status", "ok")
            .put("service", "plc-file-upload");
    }
}
