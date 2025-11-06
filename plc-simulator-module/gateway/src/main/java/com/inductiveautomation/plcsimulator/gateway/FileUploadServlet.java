package com.inductiveautomation.plcsimulator.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Servlet for handling PLC file uploads (L5K, JSON, etc.)
 * Provides a web UI at /plc-simulator/upload
 */
@MultipartConfig(
    maxFileSize = 50 * 1024 * 1024,      // 50 MB max file size
    maxRequestSize = 60 * 1024 * 1024,   // 60 MB max request size
    fileSizeThreshold = 1024 * 1024      // 1 MB threshold for writing to disk
)
public class FileUploadServlet extends HttpServlet {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final GatewayHook gatewayHook;

    public FileUploadServlet(GatewayHook gatewayHook) {
        this.gatewayHook = gatewayHook;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // Serve the upload form
        resp.setContentType("text/html");
        resp.setCharacterEncoding("UTF-8");

        PrintWriter out = resp.getWriter();
        out.println(getUploadFormHtml());
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        logger.info("Received file upload request");

        try {
            // Get uploaded file
            Part filePart = req.getPart("file");
            if (filePart == null) {
                sendError(resp, "No file uploaded");
                return;
            }

            String fileName = getFileName(filePart);
            logger.info("Processing file: {}", fileName);

            // Read file content
            String fileContent;
            try (InputStream is = filePart.getInputStream()) {
                fileContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            if (fileContent.isEmpty()) {
                sendError(resp, "Uploaded file is empty");
                return;
            }

            // Determine file type and parse
            String parseResult;
            if (fileName.toLowerCase().endsWith(".l5k")) {
                parseResult = gatewayHook.getParserService().parseL5K(fileContent);
            } else if (fileName.toLowerCase().endsWith(".json")) {
                parseResult = gatewayHook.getParserService().parseJSON(fileContent);
            } else {
                sendError(resp, "Unsupported file type. Only .L5K and .json files are supported.");
                return;
            }

            // Parse JSON response
            JsonObject result = JsonParser.parseString(parseResult).getAsJsonObject();

            // Check for error
            if (result.has("error")) {
                sendError(resp, "Parse error: " + result.get("error").getAsString());
                return;
            }

            // Create tags from parsed result
            int tagsCreated = createTagsFromParseResult(result);

            // Send success response
            sendSuccess(resp, result, tagsCreated);

        } catch (Exception e) {
            logger.error("Error processing file upload", e);
            sendError(resp, "Error: " + e.getMessage());
        }
    }

    /**
     * Create Ignition tags from the parsed result.
     */
    private int createTagsFromParseResult(JsonObject result) {
        PLCTagManager tagManager = gatewayHook.getTagManager();
        int tagCount = 0;

        String projectName = result.get("name").getAsString();
        logger.info("Creating tags for project: {}", projectName);

        // Create controller-scoped tags
        if (result.has("controller_tags")) {
            JsonArray controllerTags = result.getAsJsonArray("controller_tags");
            String controllerPath = "Controller/Global";

            for (JsonElement tagElement : controllerTags) {
                JsonObject tag = tagElement.getAsJsonObject();
                String tagName = tag.get("name").getAsString();
                String dataType = tag.get("data_type").getAsString();

                tagManager.createAtomicTag(
                    controllerPath + "/" + tagName,
                    mapDataType(dataType),
                    null
                );
                tagCount++;
            }
        }

        // Create program-scoped tags
        if (result.has("programs")) {
            JsonObject programs = result.getAsJsonObject("programs");

            for (String programName : programs.keySet()) {
                JsonArray programTags = programs.getAsJsonArray(programName);
                String programPath = "Program/" + programName;

                for (JsonElement tagElement : programTags) {
                    JsonObject tag = tagElement.getAsJsonObject();
                    String tagName = tag.get("name").getAsString();
                    String dataType = tag.get("data_type").getAsString();

                    tagManager.createAtomicTag(
                        programPath + "/" + tagName,
                        mapDataType(dataType),
                        null
                    );
                    tagCount++;
                }
            }
        }

        logger.info("Created {} tags from parsed file", tagCount);
        return tagCount;
    }

    /**
     * Map string data type to Ignition DataType.
     */
    private DataType mapDataType(String dataTypeStr) {
        if (dataTypeStr == null) return DataType.Int4;

        switch (dataTypeStr.toUpperCase()) {
            case "BOOL":
            case "BOOLEAN":
                return DataType.Boolean;
            case "SINT":
                return DataType.Int1;
            case "INT":
                return DataType.Int2;
            case "DINT":
            case "INT4":
                return DataType.Int4;
            case "LINT":
            case "INT8":
                return DataType.Int8;
            case "REAL":
            case "FLOAT":
                return DataType.Float4;
            case "LREAL":
            case "DOUBLE":
                return DataType.Float8;
            case "STRING":
                return DataType.String;
            default:
                return DataType.Int4;
        }
    }

    /**
     * Extract filename from Part header.
     */
    private String getFileName(Part part) {
        String contentDisposition = part.getHeader("content-disposition");
        for (String token : contentDisposition.split(";")) {
            if (token.trim().startsWith("filename")) {
                return token.substring(token.indexOf('=') + 1).trim().replace("\"", "");
            }
        }
        return "unknown";
    }

    /**
     * Send error response.
     */
    private void sendError(HttpServletResponse resp, String message) throws IOException {
        resp.setContentType("text/html");
        resp.setCharacterEncoding("UTF-8");

        PrintWriter out = resp.getWriter();
        out.println(getErrorHtml(message));
    }

    /**
     * Send success response.
     */
    private void sendSuccess(HttpServletResponse resp, JsonObject result, int tagsCreated) throws IOException {
        resp.setContentType("text/html");
        resp.setCharacterEncoding("UTF-8");

        String projectName = result.get("name").getAsString();
        int totalTags = result.get("tag_count").getAsInt();

        PrintWriter out = resp.getWriter();
        out.println(getSuccessHtml(projectName, totalTags, tagsCreated));
    }

    /**
     * Generate upload form HTML.
     */
    private String getUploadFormHtml() {
        return "<!DOCTYPE html>\n" +
            "<html>\n" +
            "<head>\n" +
            "    <title>PLC Simulator - File Upload</title>\n" +
            "    <style>\n" +
            "        body { font-family: Arial, sans-serif; max-width: 800px; margin: 50px auto; padding: 20px; }\n" +
            "        h1 { color: #333; }\n" +
            "        .upload-form { background: #f5f5f5; padding: 30px; border-radius: 8px; }\n" +
            "        input[type=\"file\"] { margin: 20px 0; }\n" +
            "        button { background: #007bff; color: white; padding: 10px 30px; border: none; border-radius: 4px; cursor: pointer; font-size: 16px; }\n" +
            "        button:hover { background: #0056b3; }\n" +
            "        .info { background: #e7f3ff; padding: 15px; border-left: 4px solid #007bff; margin: 20px 0; }\n" +
            "        .supported-formats { color: #666; font-size: 14px; margin-top: 10px; }\n" +
            "    </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "    <h1>PLC Simulator - Upload PLC File</h1>\n" +
            "    <div class=\"info\">\n" +
            "        <strong>Instructions:</strong>\n" +
            "        <ul>\n" +
            "            <li>Upload a Rockwell L5K file or JSON configuration file</li>\n" +
            "            <li>The parser will extract tag definitions and UDT structures</li>\n" +
            "            <li>Tags will be automatically created in the [PLCSimulator] tag provider</li>\n" +
            "            <li>Browse tags in Designer Tag Browser or via OPC-UA</li>\n" +
            "        </ul>\n" +
            "    </div>\n" +
            "    <div class=\"upload-form\">\n" +
            "        <form method=\"post\" enctype=\"multipart/form-data\">\n" +
            "            <label for=\"file\"><strong>Select PLC File:</strong></label><br>\n" +
            "            <input type=\"file\" id=\"file\" name=\"file\" accept=\".l5k,.L5K,.json,.JSON\" required>\n" +
            "            <div class=\"supported-formats\">Supported formats: .L5K, .json</div>\n" +
            "            <br><br>\n" +
            "            <button type=\"submit\">Upload and Parse</button>\n" +
            "        </form>\n" +
            "    </div>\n" +
            "</body>\n" +
            "</html>";
    }

    /**
     * Generate success response HTML.
     */
    private String getSuccessHtml(String projectName, int totalTags, int tagsCreated) {
        return "<!DOCTYPE html>\n" +
            "<html>\n" +
            "<head>\n" +
            "    <title>PLC Simulator - Upload Success</title>\n" +
            "    <style>\n" +
            "        body { font-family: Arial, sans-serif; max-width: 800px; margin: 50px auto; padding: 20px; }\n" +
            "        h1 { color: #28a745; }\n" +
            "        .success { background: #d4edda; padding: 20px; border-radius: 8px; border-left: 4px solid #28a745; }\n" +
            "        .stats { background: #f5f5f5; padding: 15px; border-radius: 4px; margin: 20px 0; }\n" +
            "        .stat-item { margin: 10px 0; }\n" +
            "        .stat-label { font-weight: bold; }\n" +
            "        a { color: #007bff; text-decoration: none; }\n" +
            "        a:hover { text-decoration: underline; }\n" +
            "        .actions { margin-top: 30px; }\n" +
            "        button { background: #007bff; color: white; padding: 10px 30px; border: none; border-radius: 4px; cursor: pointer; font-size: 16px; margin-right: 10px; }\n" +
            "        button:hover { background: #0056b3; }\n" +
            "    </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "    <h1>✓ Upload Successful</h1>\n" +
            "    <div class=\"success\">\n" +
            "        <p><strong>PLC file parsed and tags created successfully!</strong></p>\n" +
            "    </div>\n" +
            "    <div class=\"stats\">\n" +
            "        <div class=\"stat-item\"><span class=\"stat-label\">Project Name:</span> " + projectName + "</div>\n" +
            "        <div class=\"stat-item\"><span class=\"stat-label\">Total Tags Found:</span> " + totalTags + "</div>\n" +
            "        <div class=\"stat-item\"><span class=\"stat-label\">Tags Created:</span> " + tagsCreated + "</div>\n" +
            "        <div class=\"stat-item\"><span class=\"stat-label\">Tag Provider:</span> [PLCSimulator]</div>\n" +
            "    </div>\n" +
            "    <div class=\"actions\">\n" +
            "        <a href=\"/plc-simulator/upload\"><button>Upload Another File</button></a>\n" +
            "    </div>\n" +
            "    <p style=\"margin-top: 30px; color: #666;\">\n" +
            "        <strong>Next Steps:</strong><br>\n" +
            "        • Open Designer and browse the [PLCSimulator] tag provider<br>\n" +
            "        • Connect an OPC-UA client to browse the hierarchical structure<br>\n" +
            "        • Use scripting functions to control simulation<br>\n" +
            "    </p>\n" +
            "</body>\n" +
            "</html>";
    }

    /**
     * Generate error response HTML.
     */
    private String getErrorHtml(String message) {
        return "<!DOCTYPE html>\n" +
            "<html>\n" +
            "<head>\n" +
            "    <title>PLC Simulator - Upload Error</title>\n" +
            "    <style>\n" +
            "        body { font-family: Arial, sans-serif; max-width: 800px; margin: 50px auto; padding: 20px; }\n" +
            "        h1 { color: #dc3545; }\n" +
            "        .error { background: #f8d7da; padding: 20px; border-radius: 8px; border-left: 4px solid #dc3545; }\n" +
            "        a { color: #007bff; text-decoration: none; }\n" +
            "        a:hover { text-decoration: underline; }\n" +
            "        .actions { margin-top: 30px; }\n" +
            "        button { background: #007bff; color: white; padding: 10px 30px; border: none; border-radius: 4px; cursor: pointer; font-size: 16px; }\n" +
            "        button:hover { background: #0056b3; }\n" +
            "    </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "    <h1>✗ Upload Error</h1>\n" +
            "    <div class=\"error\">\n" +
            "        <p><strong>An error occurred while processing your file:</strong></p>\n" +
            "        <p>" + message + "</p>\n" +
            "    </div>\n" +
            "    <div class=\"actions\">\n" +
            "        <a href=\"/plc-simulator/upload\"><button>Try Again</button></a>\n" +
            "    </div>\n" +
            "</body>\n" +
            "</html>";
    }
}
