package com.inductiveautomation.logixemulator.gateway.device;

import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.logixemulator.gateway.OpcUaSimulationEngine;
import com.inductiveautomation.logixemulator.gateway.SimulatorModuleHook;
import org.eclipse.milo.opcua.sdk.server.ManagedAddressSpaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Logix PLC Emulator Device implementation.
 *
 * This device:
 * 1. Parses Rockwell Logix PLC files (L5K, JSON, CSV)
 * 2. Creates hierarchical OPC-UA address space
 * 3. Simulates dynamic tag values
 * 4. Supports hot-reload when file changes
 */
public class LogixEmulatorDevice extends ManagedAddressSpaceWithLifecycle implements Device {

    private static final Logger logger = LoggerFactory.getLogger(LogixEmulatorDevice.class);

    private final DeviceContext context;
    private final LogixEmulatorConfig config;
    private final SubscriptionModel subscriptionModel;

    private volatile JsonObject parsedData;
    private volatile String deviceStatus = "Initializing";
    private volatile OpcUaSimulationEngine simulationEngine;
    private volatile String currentFilePath;

    private final TagWriteDispatcher tagWriteDispatcher;
    private final TagSimulationFacade tagSimulationFacade;
    private final FilePreparation filePreparation;
    private final AddressSpaceLifecycle addressSpaceLifecycle;
    private final SimulationLifecycle simulationLifecycle;
    private final HotReloadCoordinator hotReloadCoordinator;

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

        // P6: collaborators extracted from the former god class. The dispatcher
        // takes suppliers so it always sees the *current* node manager and
        // simulation engine — the latter is volatile and recreated on
        // performFullRebuild.
        this.tagWriteDispatcher = new TagWriteDispatcher(
            context,
            this::getNodeManager,
            () -> this.simulationEngine
        );
        this.tagSimulationFacade = new TagSimulationFacade(
            () -> this.simulationEngine,
            () -> this.tagWriteDispatcher.getAllTagValues().keySet(),
            this.tagWriteDispatcher::isReadOnly
        );
        this.filePreparation = new FilePreparation(context, config);
        this.addressSpaceLifecycle = new AddressSpaceLifecycle(
            context,
            this::getNodeContext,
            this::getNodeManager
        );
        this.simulationLifecycle = new SimulationLifecycle(
            context,
            config,
            this::getNodeManager,
            engine -> this.simulationEngine = engine
        );
        this.hotReloadCoordinator = new HotReloadCoordinator(
            context,
            config,
            this::getNodeManager,
            status -> this.deviceStatus = status,
            () -> this.parsedData,
            data -> this.parsedData = data,
            this::performFullRebuild,
            this::handleFileChange
        );

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
            currentFilePath = filePreparation.prepareFile();
            if (currentFilePath == null) {
                // No file provided yet - device is ready but waiting for configuration
                deviceStatus = "Ready - Waiting for file upload";
                logger.info("Device started without file configuration. Use file upload to add PLC file.");

                // Create empty root folder so device appears in OPC-UA browser
                addressSpaceLifecycle.createRootNode();
                return;
            }

            // Parse the PLC file
            parsedData = filePreparation.parseFile(currentFilePath);

            if (parsedData == null) {
                String parserType = config.parser().parserType().getDisplayName();
                // Naming the file (not just the parser family) in the status makes the honest
                // 4xx this feeds into (DeviceController's B4 gate) specific enough for a caller
                // to identify which upload failed and with which parser - including for L5K,
                // whose format-specific failure mode (FIX-6) previously never reached here at all
                // because L5KParser silently substituted a demo tag instead of returning null.
                deviceStatus = String.format(
                    "Error: %s parser failed to parse file '%s'. Check gateway logs for details.",
                    parserType, new File(currentFilePath).getName());
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
            addressSpaceLifecycle.createRootNode();

            // Build address space from parsed data
            addressSpaceLifecycle.buildAddressSpace(parsedData);

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
        hotReloadCoordinator.stopFileWatcher();

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
     * Clear the OPC-UA address space, removing all tags.
     * Used when a file is deleted from the device. Stops the simulation
     * engine and file watcher, removes all nodes via
     * {@link AddressSpaceLifecycle}, then recreates an empty root folder so
     * the device still appears in the OPC-UA browser.
     */
    private void clearAddressSpace() {
        logger.info("Clearing address space for device: {}", context.getName());

        if (simulationEngine != null && simulationEngine.isRunning()) {
            simulationEngine.stop();
            simulationEngine = null;
            logger.debug("Stopped simulation engine");
        }

        hotReloadCoordinator.stopFileWatcher();

        addressSpaceLifecycle.removeAllDeviceNodes();
        addressSpaceLifecycle.createRootNode();

        deviceStatus = "Ready - Waiting for file upload";
        logger.info("Address space cleared - device ready for new file upload");
    }

    /** Initialize and start the simulation engine via {@link SimulationLifecycle}. */
    private void initializeSimulation() {
        simulationLifecycle.initialize();
    }

    /** Setup file watcher for hot reload via {@link HotReloadCoordinator}. */
    private void setupFileWatcher() {
        hotReloadCoordinator.setupFileWatcher(currentFilePath);
    }

    /** Handle file change event from file watcher via {@link HotReloadCoordinator}. */
    private void handleFileChange(File changedFile) {
        hotReloadCoordinator.handleFileChange(currentFilePath, filePreparation);
    }

    /**
     * Perform a full address space rebuild. Used when structural changes are
     * detected (tags added/removed). Address-space ops delegate to
     * {@link AddressSpaceLifecycle}; the device still owns the wider
     * orchestration (sim engine restart, status updates).
     */
    private void performFullRebuild(JsonObject newData) {
        if (simulationEngine != null && simulationEngine.isRunning()) {
            simulationEngine.stop();
        }

        parsedData = newData;

        addressSpaceLifecycle.removeAllDeviceNodes();
        addressSpaceLifecycle.createRootNode();
        addressSpaceLifecycle.buildAddressSpace(parsedData);

        if (config.simulation().enabled()) {
            initializeSimulation();
        }

        deviceStatus = "Running";
        logger.info("Device successfully reloaded with full rebuild");
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
     * @return The root folder's NodeId, or null if no root has been created
     */
    public NodeId getRootNodeId() {
        return addressSpaceLifecycle.getRootNodeId();
    }

    /**
     * Read the current value of a tag from the OPC-UA address space.
     * @param tagPath The tag path (e.g., "Controller:Global/MyTag")
     * @return The current value, or null if not found
     */
    public Object readTagValue(String tagPath) {
        return tagWriteDispatcher.readTagValue(tagPath);
    }

    /**
     * Write a value to a tag in the OPC-UA address space.
     * @param tagPath The tag path (e.g., "Controller:Global/MyTag")
     * @param value The value to write
     * @return {@link TagWriteDispatcher.WriteResult#SUCCESS}, {@link
     *     TagWriteDispatcher.WriteResult#NOT_FOUND}, or {@link
     *     TagWriteDispatcher.WriteResult#READ_ONLY} (FIX-2)
     */
    public TagWriteDispatcher.WriteResult writeTagValue(String tagPath, Object value) {
        return tagWriteDispatcher.writeTagValue(tagPath, value);
    }

    /**
     * Report whether a tag is flagged read-only in the OPC-UA address space
     * (FIX-2 part 2) — used to refuse simulation-pattern assignment before
     * the simulation engine ever starts writing to the tag on tick.
     * @param tagPath The tag path (e.g., "Controller:Global/MyTag")
     * @return true if the tag exists and is read-only
     */
    public boolean isTagReadOnly(String tagPath) {
        return tagWriteDispatcher.isReadOnly(tagPath);
    }

    /**
     * Get all variable nodes from the address space with their current values.
     * Used by the tag browser API.
     * @return Map of tag path to current value
     */
    public Map<String, Object> getAllTagValues() {
        return tagWriteDispatcher.getAllTagValues();
    }

    // =====================================================
    // Per-tag simulation control API
    // =====================================================

    /** @see TagSimulationFacade#enableTagSimulation(String) */
    public boolean enableTagSimulation(String tagPath) {
        return tagSimulationFacade.enableTagSimulation(tagPath);
    }

    /** @see TagSimulationFacade#enableTagSimulation(String, String) */
    public boolean enableTagSimulation(String tagPath, String pattern) {
        return tagSimulationFacade.enableTagSimulation(tagPath, pattern);
    }

    /** @see TagSimulationFacade#disableTagSimulation(String) */
    public boolean disableTagSimulation(String tagPath) {
        return tagSimulationFacade.disableTagSimulation(tagPath);
    }

    /** @see TagSimulationFacade#toggleTagSimulation(String) */
    public Boolean toggleTagSimulation(String tagPath) {
        return tagSimulationFacade.toggleTagSimulation(tagPath);
    }

    /** @see TagSimulationFacade#isTagSimulated(String) */
    public boolean isTagSimulated(String tagPath) {
        return tagSimulationFacade.isTagSimulated(tagPath);
    }

    /** @see TagSimulationFacade#getSimulatedTags() */
    public Set<String> getSimulatedTags() {
        return tagSimulationFacade.getSimulatedTags();
    }

    /** @see TagSimulationFacade#getTagSimulationPattern(String) */
    public String getTagSimulationPattern(String tagPath) {
        return tagSimulationFacade.getTagSimulationPattern(tagPath);
    }

    /** @see TagSimulationFacade#isSimulationEngineAvailable() */
    public boolean isSimulationEngineAvailable() {
        return tagSimulationFacade.isSimulationEngineAvailable();
    }

    /** @see TagSimulationFacade#getSimulatedTagCount() */
    public int getSimulatedTagCount() {
        return tagSimulationFacade.getSimulatedTagCount();
    }

    /** @see TagSimulationFacade#enableSimulationByScope(String) */
    public int enableSimulationByScope(String scope) {
        return tagSimulationFacade.enableSimulationByScope(scope);
    }

    /** @see TagSimulationFacade#disableSimulationByScope(String) */
    public void disableSimulationByScope(String scope) {
        tagSimulationFacade.disableSimulationByScope(scope);
    }

    /** @see TagSimulationFacade#enableAllSimulation() */
    public void enableAllSimulation() {
        tagSimulationFacade.enableAllSimulation();
    }

    /** @see TagSimulationFacade#disableAllSimulation() */
    public void disableAllSimulation() {
        tagSimulationFacade.disableAllSimulation();
    }
}
