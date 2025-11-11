package com.inductiveautomation.plcsimulator.gateway.parser;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Parser for JSON PLC definitions.
 *
 * Expected JSON format:
 * {
 *   "controller": "ControllerName",
 *   "tags": [
 *     {
 *       "name": "Tag1",
 *       "type": "DINT",
 *       "initialValue": 0,
 *       "description": "Tag description"
 *     },
 *     {
 *       "name": "Tag2",
 *       "type": "REAL",
 *       "initialValue": 0.0
 *     }
 *   ],
 *   "programs": [
 *     {
 *       "name": "MainProgram",
 *       "tags": [...]
 *     }
 *   ]
 * }
 */
public class JsonPLCParser implements PLCParser {

    private static final Logger logger = LoggerFactory.getLogger(JsonPLCParser.class);
    private final Gson gson = new Gson();

    @Override
    public JsonObject parse(String filePath) {
        try {
            String content = Files.readString(Paths.get(filePath));
            return parseContent(content, filePath);
        } catch (Exception e) {
            logger.error("Error reading JSON file: {}", filePath, e);
            return null;
        }
    }

    @Override
    public JsonObject parseContent(String fileContent, String fileName) {
        try {
            // Parse JSON content
            JsonObject json = JsonParser.parseString(fileContent).getAsJsonObject();

            // Validate required fields
            if (!json.has("tags") && !json.has("programs")) {
                logger.error("JSON file must contain 'tags' or 'programs' array");
                return null;
            }

            // Add metadata
            if (!json.has("controller")) {
                json.addProperty("controller", "JSONController");
            }

            if (!json.has("vendor")) {
                json.addProperty("vendor", "json");
            }

            logger.info("Successfully parsed JSON PLC file: {}", fileName);
            return json;

        } catch (Exception e) {
            logger.error("Error parsing JSON content from: {}", fileName, e);
            return null;
        }
    }

    @Override
    public boolean canHandle(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".json");
    }

    @Override
    public String getParserType() {
        return "json";
    }
}
