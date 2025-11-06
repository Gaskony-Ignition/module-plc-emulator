package com.inductiveautomation.plcsimulator.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
// Script annotations - these may not resolve in all SDK versions
// import com.inductiveautomation.ignition.common.script.hints.ScriptArg;
// import com.inductiveautomation.ignition.common.script.hints.ScriptFunction;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Gateway implementation of PLC Simulator scripting functions.
 * These functions are available in Ignition as system.plcsim.*
 */
public class PLCSimScriptFunctionsImpl {

    public static final String SCRIPT_MODULE_NAME = "system.plcsim";

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final GatewayHook gatewayHook;

    public PLCSimScriptFunctionsImpl(GatewayHook gatewayHook) {
        this.gatewayHook = gatewayHook;
    }

    /**
     * Load and parse a Rockwell L5K file, creating tags in the simulator.
     *
     * @param filePath Absolute path to the .L5K file
     * @return Status message indicating success or failure
     */
    public String loadL5K(String filePath) {
        // Input validation
        if (filePath == null || filePath.trim().isEmpty()) {
            logger.warn("loadL5K called with null or empty filePath");
            return "Error: File path cannot be null or empty";
        }

        logger.info("Loading L5K file: {}", filePath);

        try {
            // Read file content
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                logger.warn("L5K file not found: {}", filePath);
                return "Error: File not found: " + filePath;
            }

            if (!Files.isReadable(path)) {
                logger.warn("L5K file not readable: {}", filePath);
                return "Error: File is not readable: " + filePath;
            }

            String content = Files.readString(path);

            if (content == null || content.trim().isEmpty()) {
                logger.warn("L5K file is empty: {}", filePath);
                return "Error: File is empty: " + filePath;
            }

            // Parse via parser service
            ParserService parserService = gatewayHook.getParserService();
            if (parserService == null || !parserService.isRunning()) {
                logger.error("Parser service is not available or not running");
                return "Error: Parser service is not running";
            }

            String parseResult = parserService.parseL5K(content);

            // Create tags
            int tagsCreated = createTagsFromParseResult(parseResult);

            String successMsg = String.format("Success: Loaded %s and created %d tags",
                path.getFileName(), tagsCreated);
            logger.info(successMsg);

            // Update settings with last loaded file
            SettingsManager settingsManager = gatewayHook.getSettingsManager();
            if (settingsManager != null) {
                settingsManager.updateLastLoadedFile(filePath);
            }

            return successMsg;

        } catch (IOException e) {
            logger.error("IO error loading L5K file: {}", filePath, e);
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            logger.error("Unexpected error loading L5K file: {}", filePath, e);
            return "Error: Unexpected error - " + e.getMessage();
        }
    }

    /**
     * Load and parse a JSON PLC configuration file, creating tags in the simulator.
     *
     * @param filePath Absolute path to the .json file
     * @return Status message indicating success or failure
     */

    public String loadJSON(String filePath) {
        // Input validation
        if (filePath == null || filePath.trim().isEmpty()) {
            logger.warn("loadJSON called with null or empty filePath");
            return "Error: File path cannot be null or empty";
        }

        logger.info("Loading JSON file: {}", filePath);

        try {
            // Read file content
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                logger.warn("JSON file not found: {}", filePath);
                return "Error: File not found: " + filePath;
            }

            if (!Files.isReadable(path)) {
                logger.warn("JSON file not readable: {}", filePath);
                return "Error: File is not readable: " + filePath;
            }

            String content = Files.readString(path);

            if (content == null || content.trim().isEmpty()) {
                logger.warn("JSON file is empty: {}", filePath);
                return "Error: File is empty: " + filePath;
            }

            // Parse via parser service
            ParserService parserService = gatewayHook.getParserService();
            if (parserService == null || !parserService.isRunning()) {
                logger.error("Parser service is not available or not running");
                return "Error: Parser service is not running";
            }

            String parseResult = parserService.parseJSON(content);

            // Create tags
            int tagsCreated = createTagsFromParseResult(parseResult);

            String successMsg = String.format("Success: Loaded %s and created %d tags",
                path.getFileName(), tagsCreated);
            logger.info(successMsg);

            // Update settings with last loaded file
            SettingsManager settingsManager = gatewayHook.getSettingsManager();
            if (settingsManager != null) {
                settingsManager.updateLastLoadedFile(filePath);
            }

            return successMsg;

        } catch (IOException e) {
            logger.error("IO error loading JSON file: {}", filePath, e);
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            logger.error("Unexpected error loading JSON file: {}", filePath, e);
            return "Error: Unexpected error - " + e.getMessage();
        }
    }

    /**
     * Get the current status of the PLC Simulator module.
     *
     * @return Status information (parser running, tag count, etc.)
     */
    
    public String getStatus() {
        StringBuilder status = new StringBuilder();

        status.append("PLC Simulator Status:\n");
        status.append("  Tag Provider: [PLCSimulator]\n");
        status.append("  Tag Count: ").append(getTagCount()).append("\n");
        status.append("  Parser Running: ").append(isParserRunning()).append("\n");

        return status.toString();
    }

    /**
     * Get the number of tags currently in the simulator.
     *
     * @return Number of tags
     */
    
    public int getTagCount() {
        // TODO: Implement actual tag counting
        // For now, return placeholder
        return 0;
    }

    /**
     * Check if the parser service is running.
     *
     * @return True if parser service is running
     */

    public boolean isParserRunning() {
        ParserService parserService = gatewayHook.getParserService();
        return parserService != null && parserService.isRunning();
    }

    /**
     * Remove simulation from a tag to allow manual control.
     *
     * @param tagPath Tag path (e.g., "Controller/Global/Motor1_Speed")
     * @return Status message
     */

    public String removeSimulation(String tagPath) {
        // Input validation
        if (tagPath == null || tagPath.trim().isEmpty()) {
            logger.warn("removeSimulation called with null or empty tagPath");
            return "Error: Tag path cannot be null or empty";
        }

        logger.info("Removing simulation for tag: {}", tagPath);

        try {
            SimulationEngine simEngine = gatewayHook.getSimulationEngine();
            if (simEngine == null || !simEngine.isRunning()) {
                logger.warn("Simulation engine is not available");
                return "Error: Simulation engine is not running";
            }

            if (!simEngine.hasSimulation(tagPath)) {
                logger.info("Tag '{}' does not have an active simulation", tagPath);
                return "Tag '" + tagPath + "' does not have an active simulation";
            }

            simEngine.removeSimulation(tagPath);
            logger.info("Successfully removed simulation from tag '{}'", tagPath);
            return "Success: Removed simulation from tag '" + tagPath + "'";

        } catch (Exception e) {
            logger.error("Error removing simulation from tag: {}", tagPath, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Add a sine wave simulation to a tag.
     *
     * @param tagPath Tag path
     * @param min     Minimum value
     * @param max     Maximum value
     * @param period  Period in seconds
     * @return Status message
     */

    public String addSineSimulation(String tagPath, double min, double max, double period) {
        // Input validation
        if (tagPath == null || tagPath.trim().isEmpty()) {
            logger.warn("addSineSimulation called with null or empty tagPath");
            return "Error: Tag path cannot be null or empty";
        }

        if (min >= max) {
            logger.warn("addSineSimulation called with invalid range: min={}, max={}", min, max);
            return "Error: Minimum value must be less than maximum value";
        }

        if (period <= 0) {
            logger.warn("addSineSimulation called with invalid period: {}", period);
            return "Error: Period must be greater than 0";
        }

        logger.info("Adding sine simulation to tag: {} (min={}, max={}, period={}s)", tagPath, min, max, period);

        try {
            SimulationEngine simEngine = gatewayHook.getSimulationEngine();
            if (simEngine == null || !simEngine.isRunning()) {
                logger.warn("Simulation engine is not available");
                return "Error: Simulation engine is not running";
            }

            simEngine.addSimulation(
                tagPath,
                SimulationEngine.SimulationType.SINE,
                SimulationEngine.SimulationConfig.sine(min, max, period)
            );

            String successMsg = String.format("Success: Added sine simulation to '%s' (%.1f to %.1f, %.1fs period)",
                tagPath, min, max, period);
            logger.info(successMsg);
            return successMsg;

        } catch (Exception e) {
            logger.error("Error adding sine simulation to tag: {}", tagPath, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Add a ramp simulation to a tag.
     *
     * @param tagPath Tag path
     * @param min     Minimum value
     * @param max     Maximum value
     * @param period  Period in seconds
     * @return Status message
     */

    public String addRampSimulation(String tagPath, double min, double max, double period) {
        // Input validation
        if (tagPath == null || tagPath.trim().isEmpty()) {
            logger.warn("addRampSimulation called with null or empty tagPath");
            return "Error: Tag path cannot be null or empty";
        }

        if (min >= max) {
            logger.warn("addRampSimulation called with invalid range: min={}, max={}", min, max);
            return "Error: Minimum value must be less than maximum value";
        }

        if (period <= 0) {
            logger.warn("addRampSimulation called with invalid period: {}", period);
            return "Error: Period must be greater than 0";
        }

        logger.info("Adding ramp simulation to tag: {} (min={}, max={}, period={}s)", tagPath, min, max, period);

        try {
            SimulationEngine simEngine = gatewayHook.getSimulationEngine();
            if (simEngine == null || !simEngine.isRunning()) {
                logger.warn("Simulation engine is not available");
                return "Error: Simulation engine is not running";
            }

            simEngine.addSimulation(
                tagPath,
                SimulationEngine.SimulationType.RAMP,
                SimulationEngine.SimulationConfig.ramp(min, max, period)
            );

            String successMsg = String.format("Success: Added ramp simulation to '%s' (%.1f to %.1f, %.1fs period)",
                tagPath, min, max, period);
            logger.info(successMsg);
            return successMsg;

        } catch (Exception e) {
            logger.error("Error adding ramp simulation to tag: {}", tagPath, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Add a toggle simulation to a boolean tag.
     *
     * @param tagPath Tag path
     * @param period  Period in seconds
     * @return Status message
     */

    public String addToggleSimulation(String tagPath, double period) {
        // Input validation
        if (tagPath == null || tagPath.trim().isEmpty()) {
            logger.warn("addToggleSimulation called with null or empty tagPath");
            return "Error: Tag path cannot be null or empty";
        }

        if (period <= 0) {
            logger.warn("addToggleSimulation called with invalid period: {}", period);
            return "Error: Period must be greater than 0";
        }

        logger.info("Adding toggle simulation to tag: {} (period={}s)", tagPath, period);

        try {
            SimulationEngine simEngine = gatewayHook.getSimulationEngine();
            if (simEngine == null || !simEngine.isRunning()) {
                logger.warn("Simulation engine is not available");
                return "Error: Simulation engine is not running";
            }

            simEngine.addSimulation(
                tagPath,
                SimulationEngine.SimulationType.TOGGLE,
                SimulationEngine.SimulationConfig.toggle(period)
            );

            String successMsg = String.format("Success: Added toggle simulation to '%s' (%.1fs period)",
                tagPath, period);
            logger.info(successMsg);
            return successMsg;

        } catch (Exception e) {
            logger.error("Error adding toggle simulation to tag: {}", tagPath, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Clear all simulations from all tags.
     *
     * @return Status message
     */

    public String clearAllSimulations() {
        logger.info("Clearing all simulations");

        SimulationEngine simEngine = gatewayHook.getSimulationEngine();
        if (simEngine == null || !simEngine.isRunning()) {
            return "Error: Simulation engine is not running";
        }

        int count = simEngine.getSimulationCount();
        simEngine.clearSimulations();

        return String.format("Success: Cleared %d simulations", count);
    }

    /**
     * Create tags from parser JSON result.
     */
    private int createTagsFromParseResult(String parseResultJson) {
        PLCTagManager tagManager = gatewayHook.getTagManager();
        if (tagManager == null) {
            logger.error("Tag manager is null");
            return 0;
        }

        int tagCount = 0;

        try {
            JsonObject result = JsonParser.parseString(parseResultJson).getAsJsonObject();

            // Check for error
            if (result.has("error")) {
                logger.error("Parse error: {}", result.get("error").getAsString());
                return 0;
            }

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

            logger.info("Created {} tags", tagCount);

        } catch (Exception e) {
            logger.error("Error creating tags from parse result", e);
        }

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
}
