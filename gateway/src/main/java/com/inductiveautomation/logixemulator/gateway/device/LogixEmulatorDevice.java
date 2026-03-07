package com.inductiveautomation.logixemulator.gateway.device;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.logixemulator.gateway.FileVersionManager;
import com.inductiveautomation.logixemulator.gateway.FileWatcher;
import com.inductiveautomation.logixemulator.gateway.OpcUaSimulationEngine;
import com.inductiveautomation.logixemulator.gateway.SimulatorModuleHook;
import com.inductiveautomation.logixemulator.gateway.parser.ParserFactory;
import com.inductiveautomation.logixemulator.gateway.parser.PLCParser;
import com.inductiveautomation.logixemulator.gateway.web.PathSecurity;
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

import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Logix PLC Emulator Device implementation.
 *
 * This device:
 * 1. Parses Rockwell Logix PLC files (L5K, L5X, JSON, CSV)
 * 2. Creates hierarchical OPC-UA address space
 * 3. Simulates dynamic tag values
 * 4. Supports hot-reload when file changes
 */
public class LogixEmulatorDevice extends ManagedAddressSpaceWithLifecycle implements Device {

    private static final Logger logger = LoggerFactory.getLogger(LogixEmulatorDevice.class);
    private final Gson gson = new Gson();

    private final DeviceContext context;
    private final LogixEmulatorConfig config;
    private final SubscriptionModel subscriptionModel;

    private UaFolderNode rootNode;
    private volatile JsonObject parsedData;
    private volatile String deviceStatus = "Initializing";
    private volatile OpcUaSimulationEngine simulationEngine;
    private volatile FileWatcher fileWatcher;
    private volatile FileVersionManager versionManager;
    private volatile String currentFilePath;

    /**
     * Creates a new Logix Emulator Device.
     *
     * @param context Device context provided by Ignition
     * @param config Device configuration from user
     */
    public LogixEmulatorDevice(DeviceContext context, LogixEmulatorConfig config) {
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
    public LogixEmulatorConfig getConfiguration() {
        return config;
    }

    /**
     * Get the device context.
     */
    public DeviceContext getDeviceContext() {
        return context;
    }

    /**
     * Get the current file path for this device.
     */
    public String getCurrentFilePath() {
        return currentFilePath;
    }

    /**
     * Set the current file path for this device.
     */
    public void setCurrentFilePath(String path) {
        this.currentFilePath = path;
    }

    /**
     * Get the parsed PLC data for this device.
     */
    public JsonObject getParsedData() {
        return parsedData;
    }

    /**
     * Trigger a reload from the current file on disk.
     */
    public void reloadFromFile(File file) {
        handleFileChange(file);
    }

    /**
     * Clear the device's address space and reset to waiting state.
     */
    public void clearAndReset() {
        parsedData = null;
        clearAddressSpace();
    }

    /**
     * Called when device starts up.
     * Parses PLC file and builds address space.
     */
    private void onStartup() {
        try {
            logger.info("Starting Logix PLC Emulator device: {}", context.getName());
            deviceStatus = "Starting";

            // Register device with module hook so FileUploadRoutes can find it
            // This must happen BEFORE any early returns to ensure all devices are discoverable
            SimulatorModuleHook hook = SimulatorModuleHook.getInstance();
            if (hook != null) {
                hook.registerDevice(context.getName(), this);
            } else {
                logger.warn("SimulatorModuleHook not yet initialized — device '{}' registration skipped", context.getName());
            }

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
                String parserType = config.parser().parserType().getDisplayName();
                deviceStatus = String.format("Error: %s parser failed to parse file. Check gateway logs for details.", parserType);
                logger.error("[{}] Failed to parse PLC file: {} - parser returned null",
                    config.parser().parserType().getKey().toUpperCase(),
                    currentFilePath);
                return;
            }

            // Check if parser encountered errors but returned fallback structure
            if (parsedData.has("parse_error")) {
                String parseError = parsedData.get("parse_error").getAsString();
                deviceStatus = "Warning: Parse errors - " + parseError;
                logger.warn("Parser encountered errors: {}", parseError);
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
            logger.info("Logix PLC Emulator device started successfully: {}", context.getName());

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
        logger.info("Shutting down Logix PLC Emulator device: {}", context.getName());

        // Unregister device from module hook
        SimulatorModuleHook hook = SimulatorModuleHook.getInstance();
        if (hook != null) {
            hook.unregisterDevice(context.getName());
        }

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
     * Delegates to {@link PathSecurity#sanitizeFileName} for consistent validation.
     *
     * @param fileName User-provided filename
     * @return Sanitized filename safe for file system operations
     */
    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "uploaded-" + context.getName() + ".L5K";
        }
        try {
            return PathSecurity.sanitizeFileName(fileName);
        } catch (SecurityException | IllegalArgumentException e) {
            logger.warn("Filename rejected by PathSecurity: {} — using default", fileName);
            return "uploaded-" + context.getName() + ".L5K";
        }
    }

    /**
     * Find an existing file in the storage directory that was previously uploaded for THIS device.
     * Only returns files with the device-specific prefix to prevent cross-device file sharing.
     *
     * @param storageDir Directory to search for files
     * @return Device-specific PLC file found, or null if none exist
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

        String deviceName = context.getName();

        // Only look for device-specific files (e.g., "MyDevice_program.l5k")
        // This prevents new devices from accidentally picking up files from other devices
        for (File file : files) {
            String name = file.getName();
            // Skip hidden files and backup files
            if (name.startsWith(".") || name.endsWith(".bak") || name.contains("~")) {
                continue;
            }
            // Check if file starts with this device's name prefix
            if (name.startsWith(deviceName + "_")) {
                for (String ext : extensions) {
                    if (name.endsWith(ext)) {
                        logger.info("Found device-specific file: {}", file.getName());
                        return file;
                    }
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
            File storageDir = new File(dataDir, "logix-emulator");

            if (!storageDir.exists()) {
                if (!storageDir.mkdirs()) {
                    logger.warn("Failed to create PLC file storage directory: {}", storageDir.getAbsolutePath());
                } else {
                    logger.info("Created PLC file storage directory: {}", storageDir.getAbsolutePath());
                }
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
            LogixEmulatorConfig.ParserType parserType = config.parser().parserType();

            logger.info("Parsing file: {} with parser: {}", filePath, parserType.getDisplayName());

            // Use built-in Java parser
            return parseFileBuiltIn(filePath, parserType);

        } catch (Exception e) {
            logger.error("Error parsing file", e);
            return null;
        }
    }

    /**
     * Built-in Java parser using ParserFactory.
     * Selects appropriate parser based on file type and parses the PLC file.
     */
    private JsonObject parseFileBuiltIn(String filePath, LogixEmulatorConfig.ParserType parserType) {
        logger.info("Using built-in Java parser for: {}", filePath);

        File file = new File(filePath);
        if (!file.exists()) {
            logger.error("File does not exist: {}", filePath);
            // Return a demo structure to allow device to start
            return createDemoStructure();
        }

        try {
            PLCParser parser = null;

            // Special handling for "rockwell" type - determine L5K vs L5X by file extension
            if ("rockwell".equals(parserType.getKey())) {
                String fileName = file.getName().toLowerCase();
                if (fileName.endsWith(".l5k")) {
                    parser = ParserFactory.getParserByType("l5k");
                    logger.info("Selected L5K parser for Rockwell .l5k file");
                } else if (fileName.endsWith(".l5x")) {
                    parser = ParserFactory.getParserByType("l5x");
                    logger.info("Selected L5X parser for Rockwell .l5x file");
                } else {
                    // Try to detect by extension for other Rockwell formats
                    parser = ParserFactory.getParser(file.getName());
                }
            } else {
                // For non-Rockwell types, use the parser type directly
                parser = ParserFactory.getParserByType(parserType.getKey());
            }

            // Fallback to extension-based detection
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
                        "description": "Demo tag — no PLC file loaded"
                    },
                    {
                        "name": "DemoTag2",
                        "dataType": "REAL",
                        "value": 0.0,
                        "description": "Demo tag — no PLC file loaded"
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
        String deviceName = context.getName();

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
            context.getName(),
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
     * Clear the OPC-UA address space, removing all tags.
     * Used when a file is deleted from the device.
     */
    private void clearAddressSpace() {
        logger.info("Clearing address space for device: {}", context.getName());

        // Stop simulation engine if running
        if (simulationEngine != null && simulationEngine.isRunning()) {
            simulationEngine.stop();
            simulationEngine = null;
            logger.debug("Stopped simulation engine");
        }

        // Stop file watcher if running
        if (fileWatcher != null && fileWatcher.isRunning()) {
            fileWatcher.stop();
            fileWatcher = null;
            logger.debug("Stopped file watcher");
        }

        // Remove all nodes belonging to this device
        removeAllDeviceNodes();

        // Recreate empty root node so device still appears in OPC-UA browser
        createRootNode();

        // Update device status
        deviceStatus = "Ready - Waiting for file upload";
        logger.info("Address space cleared - device ready for new file upload");
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
            LogixEmulatorConfig.SimulationPattern pattern = config.simulation().defaultPattern();
            int updateInterval = config.simulation().updateInterval();

            // Pass node lookup function so simulation engine can update node values
            simulationEngine = new OpcUaSimulationEngine(
                pattern,
                updateInterval,
                nodeId -> getNodeManager().get(nodeId)
            );

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
     * Uses incremental updates when possible to minimize OPC-UA disconnection time.
     */
    private void handleFileChange(File changedFile) {
        logger.info("File change detected, reloading device: {}", context.getName());

        try {
            deviceStatus = "Reloading";

            // Re-parse the file
            JsonObject newData = parseFile(currentFilePath);

            if (newData == null) {
                deviceStatus = "Error: Failed to parse file after reload";
                logger.error("Failed to parse file during hot reload");
                return;
            }

            // Try incremental update first (faster, maintains subscriptions)
            if (parsedData != null) {
                IncrementalAddressSpaceUpdater updater = new IncrementalAddressSpaceUpdater(
                    nodeId -> getNodeManager().get(nodeId),
                    context::nodeId,
                    context.getName()
                );

                IncrementalAddressSpaceUpdater.CompareResult changes = updater.compare(parsedData, newData);

                if (updater.canApplyIncrementally(changes)) {
                    // Only value changes - apply incrementally without rebuild
                    logger.info("Applying incremental update ({} value changes)", changes.changedTags.size());
                    updater.applyIncrementalUpdate(changes);
                    parsedData = newData;
                    deviceStatus = "Running";
                    logger.info("Incremental update complete - no rebuild required");
                    return;
                }

                logger.info("Structural changes detected - performing full rebuild");
            }

            // Full rebuild needed (new/removed tags or first load)
            performFullRebuild(newData);

        } catch (Exception e) {
            logger.error("Error handling file change", e);
            deviceStatus = "Error: Hot reload failed - " + e.getMessage();
        }
    }

    /**
     * Perform a full address space rebuild.
     * Used when structural changes are detected (tags added/removed).
     */
    private void performFullRebuild(JsonObject newData) {
        // Stop simulation engine temporarily
        if (simulationEngine != null && simulationEngine.isRunning()) {
            simulationEngine.stop();
        }

        // Update parsed data
        parsedData = newData;

        // Clear all existing nodes for this device before rebuilding
        removeAllDeviceNodes();
        createRootNode();
        buildAddressSpace();

        // Restart simulation if it was running
        if (config.simulation().enabled()) {
            initializeSimulation();
        }

        deviceStatus = "Running";
        logger.info("Device successfully reloaded with full rebuild");
    }

    /**
     * Remove all OPC-UA nodes belonging to this device.
     * This ensures clean rebuilds without lingering nodes from previous configurations.
     */
    private void removeAllDeviceNodes() {
        String deviceName = context.getName();
        logger.debug("Removing all nodes for device: {}", deviceName);

        // Collect all node IDs that belong to this device
        List<NodeId> nodesToRemove = new ArrayList<>();

        getNodeManager().getNodes().forEach(node -> {
            NodeId nodeId = node.getNodeId();
            Object identifier = nodeId.getIdentifier();
            if (identifier instanceof String) {
                String idStr = (String) identifier;
                // Check if this node belongs to our device
                // Device nodes have identifiers like "DeviceName" or "DeviceName.SomePath"
                if (idStr.equals(deviceName) || idStr.startsWith(deviceName + ".")) {
                    nodesToRemove.add(nodeId);
                }
            }
        });

        // Remove all collected nodes
        for (NodeId nodeId : nodesToRemove) {
            getNodeManager().removeNode(nodeId);
        }

        rootNode = null;
        logger.info("Removed {} nodes for device: {}", nodesToRemove.size(), deviceName);
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

    // =====================================================
    // Tag Browser API - Real-time read/write support
    // =====================================================

    /**
     * Get the root NodeId for this device's OPC-UA address space.
     * @return The root folder's NodeId
     */
    public NodeId getRootNodeId() {
        return rootNode != null ? rootNode.getNodeId() : null;
    }

    /**
     * Read the current value of a tag from the OPC-UA address space.
     * @param tagPath The tag path (e.g., "Controller:Global/MyTag")
     * @return The current value, or null if not found
     */
    public Object readTagValue(String tagPath) {
        try {
            // Build the full NodeId for this tag using DeviceContext.nodeId()
            NodeId nodeId =
                context.nodeId(tagPath);

            var node = getNodeManager().get(nodeId);
            if (node instanceof UaVariableNode varNode) {
                var dataValue = varNode.getValue();
                if (dataValue != null && dataValue.getValue() != null) {
                    return dataValue.getValue().getValue();
                }
            }
        } catch (Exception e) {
            logger.debug("Could not read tag value for path: {}", tagPath, e);
        }
        return null;
    }

    /**
     * Write a value to a tag in the OPC-UA address space.
     * @param tagPath The tag path (e.g., "Controller:Global/MyTag")
     * @param value The value to write
     * @return true if successful, false otherwise
     */
    public boolean writeTagValue(String tagPath, Object value) {
        try {
            NodeId nodeId =
                context.nodeId(tagPath);

            var node = getNodeManager().get(nodeId);
            if (node instanceof UaVariableNode varNode) {
                var variant = new Variant(value);
                var dataValue = new DataValue(variant);
                varNode.setValue(dataValue);
                logger.debug("Wrote value {} to tag {}", value, tagPath);
                return true;
            }
        } catch (Exception e) {
            logger.error("Could not write tag value for path: {}", tagPath, e);
        }
        return false;
    }

    /**
     * Get all variable nodes from the address space with their current values.
     * Used by the tag browser API.
     * @return Map of tag path to current value
     */
    public Map<String, Object> getAllTagValues() {
        Map<String, Object> values = new HashMap<>();

        try {
            // Iterate all nodes in the node manager
            getNodeManager().getNodes().forEach(node -> {
                if (node instanceof UaVariableNode varNode) {
                    String browseName = varNode.getBrowseName().getName();
                    try {
                        var dataValue = varNode.getValue();
                        if (dataValue != null && dataValue.getValue() != null) {
                            // Use the node ID as key (relative to device)
                            String nodeIdStr = varNode.getNodeId().getIdentifier().toString();
                            values.put(nodeIdStr, dataValue.getValue().getValue());
                        }
                    } catch (Exception e) {
                        logger.trace("Could not read value for node {}: {}", browseName, e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            logger.error("Error getting all tag values", e);
        }

        return values;
    }

    // =====================================================
    // Per-tag simulation control API
    // =====================================================

    /**
     * Enable simulation for a specific tag.
     * @param tagPath The tag path (e.g., "Controller:Global/MyTag")
     * @return true if successful
     */
    public boolean enableTagSimulation(String tagPath) {
        if (simulationEngine != null) {
            simulationEngine.enableTagSimulation(tagPath);
            return true;
        }
        return false;
    }

    /**
     * Enable simulation for a specific tag with a custom pattern.
     * @param tagPath The tag path
     * @param pattern The simulation pattern (sine, ramp, random, toggle, static)
     * @return true if successful
     */
    public boolean enableTagSimulation(String tagPath, String pattern) {
        if (simulationEngine != null) {
            try {
                LogixEmulatorConfig.SimulationPattern simPattern =
                    LogixEmulatorConfig.SimulationPattern.fromKey(pattern);
                simulationEngine.enableTagSimulation(tagPath, simPattern);
                return true;
            } catch (Exception e) {
                logger.warn("Invalid simulation pattern: {}", pattern);
                simulationEngine.enableTagSimulation(tagPath);
                return true;
            }
        }
        return false;
    }

    /**
     * Disable simulation for a specific tag.
     * @param tagPath The tag path
     * @return true if successful
     */
    public boolean disableTagSimulation(String tagPath) {
        if (simulationEngine != null) {
            simulationEngine.disableTagSimulation(tagPath);
            return true;
        }
        return false;
    }

    /**
     * Toggle simulation for a specific tag.
     * @param tagPath The tag path
     * @return true if simulation is now enabled, false if disabled, null if engine not available
     */
    public Boolean toggleTagSimulation(String tagPath) {
        if (simulationEngine != null) {
            return simulationEngine.toggleTagSimulation(tagPath);
        }
        return Boolean.FALSE;
    }

    /**
     * Check if a specific tag has simulation enabled.
     * @param tagPath The tag path
     * @return true if simulation is enabled for this tag
     */
    public boolean isTagSimulated(String tagPath) {
        return simulationEngine != null && simulationEngine.isTagSimulated(tagPath);
    }

    /**
     * Get all tags that have simulation enabled.
     * @return Set of tag paths with simulation enabled
     */
    public Set<String> getSimulatedTags() {
        if (simulationEngine != null) {
            return simulationEngine.getSimulatedTags();
        }
        return Collections.emptySet();
    }

    /**
     * Get the simulation pattern for a specific tag.
     * @param tagPath The tag path
     * @return The pattern name (e.g., "sine", "ramp")
     */
    public String getTagSimulationPattern(String tagPath) {
        if (simulationEngine != null) {
            return simulationEngine.getTagPattern(tagPath).getKey();
        }
        return "static";
    }

    /**
     * Check if the simulation engine is running and available.
     * @return true if simulation engine is active
     */
    public boolean isSimulationEngineAvailable() {
        return simulationEngine != null && simulationEngine.isRunning();
    }

    /**
     * Get the count of tags with simulation enabled.
     * @return Number of simulated tags
     */
    public int getSimulatedTagCount() {
        return simulationEngine != null ? simulationEngine.getSimulatedTagCount() : 0;
    }

    /**
     * Enable simulation for all tags matching a scope prefix.
     * @param scope The scope prefix (e.g., "Controller:Global", "Programs/MainProgram")
     */
    public void enableSimulationByScope(String scope) {
        if (simulationEngine != null) {
            Set<String> allPaths = getAllTagValues().keySet();
            simulationEngine.enableSimulationByScope(scope, allPaths);
        }
    }

    /**
     * Disable simulation for all tags matching a scope prefix.
     * @param scope The scope prefix
     */
    public void disableSimulationByScope(String scope) {
        if (simulationEngine != null) {
            simulationEngine.disableSimulationByScope(scope);
        }
    }

    /**
     * Enable simulation for all tags in the address space.
     */
    public void enableAllSimulation() {
        if (simulationEngine != null) {
            Set<String> allPaths = getAllTagValues().keySet();
            simulationEngine.enableAllSimulation(allPaths);
        }
    }

    /**
     * Disable simulation for all tags.
     */
    public void disableAllSimulation() {
        if (simulationEngine != null) {
            simulationEngine.disableAllSimulation();
        }
    }
}
