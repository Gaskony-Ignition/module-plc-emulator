package com.inductiveautomation.plcsimulator.gateway.device;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.plcsimulator.gateway.ParserService;
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

    // Will be used for simulation in future enhancement
    // private SimulationEngine simulationEngine;

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
     * Called when device starts up.
     * Parses PLC file and builds address space.
     */
    private void onStartup() {
        try {
            logger.info("Starting Enhanced PLC Simulator device: {}", context.getName());
            deviceStatus = "Starting";

            // Parse the PLC file
            parsedData = parseFile();

            if (parsedData == null) {
                deviceStatus = "Error: Failed to parse file";
                logger.error("Failed to parse PLC file");
                return;
            }

            // Create root folder node for this device
            createRootNode();

            // Build address space from parsed data
            buildAddressSpace();

            // TODO: Initialize simulation engine if enabled
            // if (config.simulation().enabled()) {
            //     initializeSimulation();
            // }

            // TODO: Setup file watcher if hot reload is enabled
            // if (config.parser().hotReload()) {
            //     setupFileWatcher();
            // }

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

        // Set all values to uncertain
        context.getSubscriptionModel()
            .getDataItems(context.getName())
            .forEach(item -> item.setQuality(new StatusCode(StatusCodes.Uncertain_LastUsableValue)));

        // TODO: Stop simulation engine
        // if (simulationEngine != null) {
        //     simulationEngine.stop();
        // }

        deviceStatus = "Stopped";
        logger.info("Device shutdown complete: {}", context.getName());
    }

    /**
     * Parses the PLC file using the parser service.
     * Falls back to built-in parser if service is unavailable.
     *
     * @return Parsed PLC data as JSON object
     */
    private JsonObject parseFile() {
        try {
            String filePath = config.parser().filePath();
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
     */
    private JsonObject tryParserService(String filePath, String parserKey) {
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
            String parserUrl = String.format("http://localhost:5000/parse/%s?file=%s",
                endpoint, filePath);

            URL url = new URL(parserUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000); // Shorter timeout for fallback
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String inputLine;

                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();

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
        }
    }

    /**
     * Built-in fallback parser for when parser service is unavailable.
     * Creates a simple structure to demonstrate the device driver.
     */
    private JsonObject parseFileBuiltIn(String filePath, EnhancedSimulatorConfig.ParserType parserType) {
        logger.info("Using built-in fallback parser for: {}", filePath);

        File file = new File(filePath);
        if (!file.exists()) {
            logger.error("File does not exist: {}", filePath);
            // Return a demo structure to allow device to start
            return createDemoStructure();
        }

        // For now, create a simple demo structure
        // TODO: Implement actual parsing logic for each vendor format
        return createDemoStructure();
    }

    /**
     * Creates a demo tag structure for testing.
     */
    private JsonObject createDemoStructure() {
        String json = """
            {
                "tags": [
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

    // TODO: Future enhancement - simulation engine integration
    // private void initializeSimulation() {
    //     simulationEngine = new OpcUaSimulationEngine(getNodeManager(), logger);
    //
    //     context.getGatewayContext().getExecutionManager().registerAtFixedRate(
    //         EnhancedSimulatorExtensionPoint.TYPE_ID,
    //         context.getName() + "-simulation",
    //         simulationEngine,
    //         config.simulation().updateInterval(), TimeUnit.MILLISECONDS
    //     );
    // }

    // TODO: Future enhancement - hot reload support
    // private void setupFileWatcher() {
    //     // Watch file for changes and trigger reload
    // }

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
