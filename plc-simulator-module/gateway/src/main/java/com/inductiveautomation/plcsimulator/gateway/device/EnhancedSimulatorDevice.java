package com.inductiveautomation.plcsimulator.gateway.device;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.plcsimulator.gateway.FileVersionManager;
import com.inductiveautomation.plcsimulator.gateway.FileWatcher;
import com.inductiveautomation.plcsimulator.gateway.OpcUaSimulationEngine;
import com.inductiveautomation.plcsimulator.gateway.ParserService;
import com.inductiveautomation.plcsimulator.gateway.SimulatorModuleHook;
import com.inductiveautomation.plcsimulator.gateway.parser.ParserFactory;
import com.inductiveautomation.plcsimulator.gateway.parser.PLCParser;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.ManagedAddressSpaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Enhanced PLC Simulator Device implementation.
 *
 * This device:
 * 1. Parses PLC files (L5K, JSON, etc.) using multi-vendor parsers
 * 2. Creates hierarchical OPC-UA address space
 * 3. Simulates dynamic tag values
 * 4. Supports hot-reload when file changes
 */
public class EnhancedSimulatorDevice extends ManagedAddressSpaceWithLifecycle implements Device {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Gson gson = new Gson();

    private final DeviceContext context;
    private final EnhancedSimulatorConfig config;
    private final SubscriptionModel subscriptionModel;

    private UaFolderNode rootNode;
    private JsonObject parsedData;
    private String deviceStatus = "Initializing";
    private OpcUaSimulationEngine simulationEngine;
    private FileWatcher fileWatcher;
    private FileVersionManager versionManager;
    private String currentFilePath;

    /**
     * Creates a new Enhanced Simulator Device.
     *
     * @param context Device context provided by Ignition
     * @param config Device configuration from user
     */
    public EnhancedSimulatorDevice(DeviceContext context, EnhancedSimulatorConfig config) {
        super(context.getServer());

        this.context = context;
        this.config = config;

        subscriptionModel = new SubscriptionModel(context.getServer(), this);

        getLifecycleManager().addLifecycle(subscriptionModel);
        getLifecycleManager().addStartupTask(this::onStartup);
        getLifecycleManager().addShutdownTask(this::onShutdown);
    }

    @Override
    public String getStatus() {
        return deviceStatus;
    }

    /**
     * Get the device name.
     */
    public String getName() {
        return context.getName();
    }

    /**
     * Get the device configuration.
     */
    public EnhancedSimulatorConfig getConfiguration() {
        return config;
    }

    /**
     * Get the device context.
     */
    public DeviceContext getDeviceContext() {
        return context;
    }

    /**
     * Called when device starts up.
     * Parses PLC file and builds address space.
     */
    private void onStartup() {
        try {
            logger.info("Starting Enhanced PLC Simulator device: {}", context.getName());
            deviceStatus = "Starting";

            // Register device with module hook so FileUploadRoutes can find it
            // This must happen BEFORE any early returns to ensure all devices are discoverable
            SimulatorModuleHook.registerDevice(context.getName(), this);

            // Save uploaded file content if provided
            currentFilePath = prepareFile();
            if (currentFilePath == null) {
                // No file provided yet - device is ready but waiting for configuration
                deviceStatus = "Ready - Waiting for file upload";
                logger.info("Device started without file configuration. Use file upload to add PLC file.");

                // Create empty root folder so device appears in OPC-UA browser
                createRootNode();
                return;
            }

            // Parse the PLC file
            parsedData = parseFile(currentFilePath);

            if (parsedData == null) {
                deviceStatus = "Error: Failed to parse file";
                logger.error("Failed to parse PLC file");
                return;
            }

            // Create root folder node for this device
            createRootNode();

            // Build address space from parsed data
            buildAddressSpace();

            // Initialize simulation engine if enabled
            if (config.simulation().enabled()) {
                initializeSimulation();
            }

            // Setup file watcher if hot reload is enabled
            if (config.parser().hotReload()) {
                setupFileWatcher();
            }

            deviceStatus = "Running";
            logger.info("Enhanced PLC Simulator device started successfully: {}", context.getName());

            // Fire initial subscription creation
            onDataItemsCreated(
                context.getSubscriptionModel()
                    .getDataItems(context.getName())
            );

        } catch (Exception e) {
            deviceStatus = "Error: " + e.getMessage();
            logger.error("Error starting device: {}", context.getName(), e);
        }
    }

    /**
     * Called when device shuts down.
     */
    private void onShutdown() {
        logger.info("Shutting down Enhanced PLC Simulator device: {}", context.getName());

        // Unregister device from module hook
        SimulatorModuleHook.unregisterDevice(context.getName());

        // Stop file watcher
        if (fileWatcher != null && fileWatcher.isRunning()) {
            fileWatcher.stop();
            logger.info("File watcher stopped");
        }

        // Stop simulation engine
        if (simulationEngine != null && simulationEngine.isRunning()) {
            simulationEngine.stop();
            logger.info("Simulation engine stopped");
        }

        // Set all values to uncertain
        context.getSubscriptionModel()
            .getDataItems(context.getName())
            .forEach(item -> item.setQuality(new StatusCode(StatusCodes.Uncertain_LastUsableValue)));

        deviceStatus = "Stopped";
        logger.info("Device shutdown complete: {}", context.getName());
    }

    /**
     * Sanitizes a filename to prevent path traversal attacks.
     * Removes path separators, parent directory references, and special characters.
     *
     * @param fileName User-provided filename
     * @return Sanitized filename safe for file system operations
     */
    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "uploaded-" + context.getName() + ".L5K";
        }

        // Remove path separators and parent directory references
        String sanitized = fileName.replaceAll("[/\\\\]", "_")
                                   .replaceAll("\\.\\.", "_")
                                   .replaceAll("[^a-zA-Z0-9._-]", "_");

        // Ensure filename isn't empty after sanitization
        if (sanitized.trim().isEmpty()) {
            sanitized = "uploaded-" + context.getName() + ".L5K";
        }

        logger.debug("Sanitized filename: {} -> {}", fileName, sanitized);
        return sanitized;
    }

    /**
     * Find an existing file in the storage directory that was previously uploaded.
     * This is a fallback for when device config doesn't have filename but file exists on disk.
     *
     * @param storageDir Directory to search for files
     * @return First matching PLC file found, or null if none exist
     */
    private File findExistingFileForDevice(File storageDir) {
        if (storageDir == null || !storageDir.exists()) {
            return null;
        }

        // Look for common PLC file extensions
        String[] extensions = {".L5K", ".l5k", ".L5X", ".l5x", ".json", ".JSON", ".csv", ".CSV"};

        File[] files = storageDir.listFiles();
        if (files == null) {
            return null;
        }

        // First, try to find files that match common upload patterns
        for (File file : files) {
            String name = file.getName();
            // Skip hidden files and backup files
            if (name.startsWith(".") || name.endsWith(".bak") || name.contains("~")) {
                continue;
            }
            // Check if it has a supported extension
            for (String ext : extensions) {
                if (name.endsWith(ext)) {
                    logger.debug("Found candidate file: {}", file.getName());
                    return file;
                }
            }
        }

        return null;
    }

    /**
     * Prepares the PLC file for parsing.
     * If file content was uploaded, saves it to the Gateway filesystem.
     *
     * @return Path to the prepared file, or null if preparation failed
     */
    private String prepareFile() {
        try {
            String fileContent = config.parser().fileContent();
            String fileName = config.parser().fileName();

            // Use Ignition's data directory API for proper cross-platform support
            File dataDir = context.getGatewayContext().getSystemManager().getDataDir();
            File storageDir = new File(dataDir, "plc-simulator");

            if (!storageDir.exists()) {
                storageDir.mkdirs();
                logger.info("Created PLC file storage directory: {}", storageDir.getAbsolutePath());
            }

            // Initialize version manager
            versionManager = new FileVersionManager(storageDir, context.getName());

            // If file content was uploaded, save it
            if (fileContent != null && !fileContent.trim().isEmpty()) {
                // Sanitize filename to prevent path traversal attacks
                String sanitizedFileName = sanitizeFileName(fileName);

                File targetFile = new File(storageDir, sanitizedFileName);

                // Save version of existing file before overwriting
                if (targetFile.exists()) {
                    versionManager.saveVersion(targetFile, sanitizedFileName);
                }

                java.nio.file.Files.writeString(targetFile.toPath(), fileContent);
                logger.info("Saved uploaded file to: {}", targetFile.getAbsolutePath());

                return targetFile.getAbsolutePath();
            }

            // Otherwise, use existing file
            if (fileName != null && !fileName.trim().isEmpty()) {
                // Sanitize filename to prevent path traversal attacks
                String sanitizedFileName = sanitizeFileName(fileName);
                File targetFile = new File(storageDir, sanitizedFileName);
                if (targetFile.exists()) {
                    logger.info("Using existing file: {}", targetFile.getAbsolutePath());
                    return targetFile.getAbsolutePath();
                } else {
                    logger.error("File not found: {}", targetFile.getAbsolutePath());
                    return null;
                }
            }

            // Last resort: Check if a file exists on disk for this device
            // This handles cases where file was uploaded via web UI but device config wasn't updated
            File deviceFile = findExistingFileForDevice(storageDir);
            if (deviceFile != null && deviceFile.exists()) {
                logger.info("Found existing uploaded file for device: {}", deviceFile.getAbsolutePath());
                logger.info("To make this permanent, re-save device configuration with this filename");
                return deviceFile.getAbsolutePath();
            }

            logger.info("No file content or file name provided - device will wait for upload");
            return null;

        } catch (Exception e) {
            logger.error("Error preparing file", e);
            return null;
        }
    }

    /**
     * Parses the PLC file using the parser service.
     * Falls back to built-in parser if service is unavailable.
     *
     * @param filePath Path to the PLC file
     * @return Parsed PLC data as JSON object
     */
    private JsonObject parseFile(String filePath) {
        try {
            EnhancedSimulatorConfig.ParserType parserType = config.parser().parserType();
            String parserKey = parserType.getKey();

            logger.info("Parsing file: {} with parser: {}", filePath, parserType.getDisplayName());

            // First, try to use parser service if available
            JsonObject result = tryParserService(filePath, parserKey);
            if (result != null) {
                logger.info("File parsed successfully using parser service");
                return result;
            }

            // Fallback to built-in parser
            logger.warn("Parser service unavailable, using built-in fallback parser");
            return parseFileBuiltIn(filePath, parserType);

        } catch (Exception e) {
            logger.error("Error parsing file", e);
            return null;
        }
    }

    /**
     * Attempts to parse using the external parser service.
     * Properly handles HTTP connection cleanup to prevent resource leaks.
     */
    private JsonObject tryParserService(String filePath, String parserKey) {
        HttpURLConnection conn = null;
        BufferedReader reader = null;

        try {
            // Determine parser endpoint based on type
            String endpoint = switch (parserKey) {
                case "rockwell" -> "l5k";
                case "json" -> "json";
                case "siemens" -> "siemens";
                case "schneider" -> "schneider";
                case "beckhoff" -> "beckhoff";
                default -> "json";
            };

            // Call parser service REST API
            // Use Docker bridge gateway IP to access host from container
            String parserUrl = String.format("http://172.17.0.1:5000/parse/%s?file=%s",
                endpoint, filePath);

            URL url = new URL(parserUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000); // Shorter timeout for fallback
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String inputLine;

                while ((inputLine = reader.readLine()) != null) {
                    response.append(inputLine);
                }

                JsonObject result = gson.fromJson(response.toString(), JsonObject.class);

                if (result.has("error")) {
                    logger.error("Parser error: {}", result.get("error").getAsString());
                    return null;
                }

                return result;

            } else {
                logger.warn("Parser service returned error code: {}", responseCode);
                return null;
            }

        } catch (Exception e) {
            logger.debug("Parser service not available: {}", e.getMessage());
            return null;
        } finally {
            // Ensure resources are closed even in error paths
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception e) {
                    logger.debug("Error closing reader: {}", e.getMessage());
                }
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception e) {
                    logger.debug("Error disconnecting HTTP connection: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Built-in Java parser using ParserFactory.
     * Selects appropriate parser based on file type and parses the PLC file.
     */
    private JsonObject parseFileBuiltIn(String filePath, EnhancedSimulatorConfig.ParserType parserType) {
        logger.info("Using built-in Java parser for: {}", filePath);

        File file = new File(filePath);
        if (!file.exists()) {
            logger.error("File does not exist: {}", filePath);
            // Return a demo structure to allow device to start
            return createDemoStructure();
        }

        try {
            // Get appropriate parser from factory
            PLCParser parser = ParserFactory.getParserByType(parserType.getKey());

            if (parser == null) {
                logger.warn("No parser available for type: {}, trying file extension detection", parserType);
                parser = ParserFactory.getParser(file.getName());
            }

            if (parser == null) {
                logger.error("No parser found for file: {}", file.getName());
                return createDemoStructure();
            }

            // Parse the file
            JsonObject result = parser.parse(filePath);

            if (result != null) {
                logger.info("Successfully parsed file using {} parser", parser.getParserType());
                return result;
            } else {
                logger.error("Parser returned null - file may be invalid or corrupted");
                return createDemoStructure();
            }

        } catch (Exception e) {
            logger.error("Error in built-in parser", e);
            return createDemoStructure();
        }
    }

    /**
     * Creates a demo tag structure for testing.
     */
    private JsonObject createDemoStructure() {
        String json = """
            {
                "global_tags": [
                    {
                        "name": "DemoTag1",
                        "dataType": "DINT",
                        "value": 0,
                        "description": "Demo tag - Parser service unavailable"
                    },
                    {
                        "name": "DemoTag2",
                        "dataType": "REAL",
                        "value": 0.0,
                        "description": "Demo tag - Install Python parser for full functionality"
                    },
                    {
                        "name": "DemoStatus",
                        "dataType": "STRING",
                        "value": "Parser service unavailable - showing demo tags",
                        "description": "Status indicator"
                    }
                ]
            }
            """;
        return gson.fromJson(json, JsonObject.class);
    }

    /**
     * Creates the root folder node for this device.
     */
    private void createRootNode() {
        String deviceName = config.general().deviceName();

        rootNode = new UaFolderNode(
            getNodeContext(),
            context.nodeId(deviceName),
            context.qualifiedName(String.format("[%s]", deviceName)),
            new LocalizedText(String.format("[%s]", deviceName))
        );

        // Add the folder node to the server
        getNodeManager().addNode(rootNode);

        // Add a reference to the root "Devices" folder node
        rootNode.addReference(new Reference(
            rootNode.getNodeId(),
            NodeIds.Organizes,
            context.getRootNodeId().expanded(),
            Reference.Direction.INVERSE
        ));

        logger.info("Created root node: [{}]", deviceName);
    }

    /**
     * Builds the OPC-UA address space from parsed PLC data.
     */
    private void buildAddressSpace() {
        if (parsedData == null || rootNode == null) {
            logger.error("Cannot build address space: missing parsed data or root node");
            return;
        }

        AddressSpaceBuilder builder = new AddressSpaceBuilder(
            getNodeManager()::addNode,
            config.general().deviceName(),
            logger
        );

        AddressSpaceBuilder.NodeContext nodeContext = new AddressSpaceBuilder.NodeContext(
            getNodeContext(),
            context
        );

        builder.buildAddressSpace(parsedData, rootNode, nodeContext);

        logger.info("Address space built successfully");
    }

    /**
     * Initialize and start the simulation engine.
     */
    private void initializeSimulation() {
        try {
            // Get all data items for this device
            List<DataItem> dataItems = context.getSubscriptionModel()
                .getDataItems(context.getName());

            if (dataItems.isEmpty()) {
                logger.warn("No data items found for simulation - skipping simulation engine startup");
                return;
            }

            // Create simulation engine with configured settings
            EnhancedSimulatorConfig.SimulationPattern pattern = config.simulation().defaultPattern();
            int updateInterval = config.simulation().updateInterval();

            simulationEngine = new OpcUaSimulationEngine(pattern, updateInterval);

            // Start the engine
            simulationEngine.start(dataItems);

            logger.info("Simulation engine started: {} pattern, {}ms interval, {} tags",
                       pattern, updateInterval, dataItems.size());

        } catch (Exception e) {
            logger.error("Failed to initialize simulation engine", e);
            simulationEngine = null;
        }
    }

    /**
     * Setup file watcher for hot reload.
     */
    private void setupFileWatcher() {
        if (currentFilePath == null) {
            logger.warn("Cannot setup file watcher - no file path available");
            return;
        }

        try {
            int reloadInterval = config.parser().reloadInterval();

            fileWatcher = new FileWatcher(
                currentFilePath,
                this::handleFileChange,
                reloadInterval
            );

            fileWatcher.start();

            logger.info("File watcher started for: {} ({}s interval)",
                       currentFilePath, reloadInterval);

        } catch (Exception e) {
            logger.error("Failed to setup file watcher", e);
            fileWatcher = null;
        }
    }

    /**
     * Handle file change event from file watcher.
     */
    private void handleFileChange(File changedFile) {
        logger.info("File change detected, reloading device: {}", context.getName());

        try {
            deviceStatus = "Reloading";

            // Stop simulation engine temporarily
            if (simulationEngine != null && simulationEngine.isRunning()) {
                simulationEngine.stop();
            }

            // Re-parse the file
            parsedData = parseFile(currentFilePath);

            if (parsedData == null) {
                deviceStatus = "Error: Failed to parse file after reload";
                logger.error("Failed to parse file during hot reload");
                return;
            }

            // Rebuild address space
            // Note: Full rebuild - incremental updates would be better but more complex
            getNodeManager().removeNode(rootNode.getNodeId());
            createRootNode();
            buildAddressSpace();

            // Restart simulation if it was running
            if (config.simulation().enabled()) {
                initializeSimulation();
            }

            deviceStatus = "Running";
            logger.info("Device successfully reloaded from file change");

        } catch (Exception e) {
            logger.error("Error handling file change", e);
            deviceStatus = "Error: Hot reload failed - " + e.getMessage();
        }
    }

    @Override
    public void onDataItemsCreated(List<DataItem> dataItems) {
        subscriptionModel.onDataItemsCreated(dataItems);
    }

    @Override
    public void onDataItemsModified(List<DataItem> dataItems) {
        subscriptionModel.onDataItemsModified(dataItems);
    }

    @Override
    public void onDataItemsDeleted(List<DataItem> dataItems) {
        subscriptionModel.onDataItemsDeleted(dataItems);
    }

    @Override
    public void onMonitoringModeChanged(List<MonitoredItem> monitoredItems) {
        subscriptionModel.onMonitoringModeChanged(monitoredItems);
    }
}
