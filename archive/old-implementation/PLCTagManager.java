package com.inductiveautomation.plcsimulator.gateway;

import com.inductiveautomation.ignition.common.TypeUtilities;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataType;
import com.inductiveautomation.ignition.gateway.tags.managed.ManagedTagProvider;
import com.inductiveautomation.ignition.gateway.tags.managed.WriteHandler;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the creation and configuration of PLC simulator tags.
 * Creates hierarchical tag structures that match PLC organization.
 */
public class PLCTagManager {

    private final ManagedTagProvider provider;
    private final Logger logger;
    private final Map<String, WriteHandler> writeHandlers;
    private SimulationEngine simulationEngine;

    public PLCTagManager(ManagedTagProvider provider, Logger logger) {
        this.provider = provider;
        this.logger = logger;
        this.writeHandlers = new ConcurrentHashMap<>();
    }

    /**
     * Set the simulation engine reference for write handler interactions.
     *
     * @param simulationEngine The simulation engine instance
     */
    public void setSimulationEngine(SimulationEngine simulationEngine) {
        this.simulationEngine = simulationEngine;
    }

    /**
     * Create a folder in the tag hierarchy.
     * Folders in ManagedTagProvider are implicit - they're created automatically
     * when you create tags with paths containing slashes.
     * This method is kept for API compatibility but doesn't need to do anything.
     *
     * @param path The full path to the folder (e.g., "PLC1/Inputs")
     */
    public void createFolder(String path) {
        // Folders are created implicitly when tags are added with paths
        // No explicit folder creation needed in ManagedTagProvider
        logger.debug("Folder path noted: [PLCSimulator]{}", path);
    }

    /**
     * Create an atomic tag (Boolean, Int, Float, String, etc.).
     *
     * @param path      The full path to the tag (e.g., "PLC1/Inputs/DI_0")
     * @param dataType  The Ignition DataType
     * @param initialValue Optional initial value for the tag
     */
    public void createAtomicTag(String path, DataType dataType, Object initialValue) {
        try {
            provider.configureTag(path, dataType);

            if (initialValue != null) {
                provider.updateValue(path, initialValue, QualityCode.Good);
            }

            logger.debug("Created tag: [PLCSimulator]{} ({})", path, dataType);
        } catch (Exception e) {
            logger.error("Error creating tag: {}", path, e);
        }
    }

    /**
     * Create a hierarchical structure from PLC program data.
     * Typical structure: Controller:Global/TagName or Program:ProgramName/TagName
     *
     * @param controllerTags Map of controller-scoped tags (tag name -> data type)
     * @param programTags    Map of program-scoped tags (program name -> (tag name -> data type))
     */
    public void createPLCStructure(
            Map<String, String> controllerTags,
            Map<String, Map<String, String>> programTags) {

        logger.info("Creating PLC tag structure");

        // Create Controller:Global folder
        if (controllerTags != null && !controllerTags.isEmpty()) {
            String controllerPath = "Controller/Global";
            createFolder(controllerPath);

            for (Map.Entry<String, String> tag : controllerTags.entrySet()) {
                String tagName = tag.getKey();
                String dataTypeStr = tag.getValue();
                DataType dataType = mapDataType(dataTypeStr);

                createAtomicTag(controllerPath + "/" + tagName, dataType, getDefaultValue(dataType));
            }

            logger.info("Created {} controller-scoped tags", controllerTags.size());
        }

        // Create Program:ProgramName folders
        if (programTags != null && !programTags.isEmpty()) {
            for (Map.Entry<String, Map<String, String>> program : programTags.entrySet()) {
                String programName = program.getKey();
                Map<String, String> tags = program.getValue();

                String programPath = "Program/" + programName;
                createFolder(programPath);

                for (Map.Entry<String, String> tag : tags.entrySet()) {
                    String tagName = tag.getKey();
                    String dataTypeStr = tag.getValue();
                    DataType dataType = mapDataType(dataTypeStr);

                    createAtomicTag(programPath + "/" + tagName, dataType, getDefaultValue(dataType));
                }

                logger.info("Created {} tags in program '{}'", tags.size(), programName);
            }
        }
    }

    /**
     * Update a tag's value.
     *
     * @param path  The tag path
     * @param value The new value
     */
    public void updateTagValue(String path, Object value) {
        try {
            provider.updateValue(path, value, QualityCode.Good);
        } catch (Exception e) {
            logger.error("Error updating tag value: {}", path, e);
        }
    }

    /**
     * Remove all tags from the provider (useful for reloading configuration).
     */
    public void removeAllTags() {
        try {
            // This will remove all tags we've created
            logger.info("Removing all tags from PLCSimulator provider");
            // Note: ManagedTagProvider doesn't have a direct removeAll method
            // We'd need to track created tags and remove them individually
            // For now, this is a placeholder
        } catch (Exception e) {
            logger.error("Error removing tags", e);
        }
    }

    /**
     * Map PLC data type strings to Ignition DataType enum.
     *
     * @param plcDataType PLC data type string (e.g., "BOOL", "INT", "REAL")
     * @return Ignition DataType
     */
    private DataType mapDataType(String plcDataType) {
        if (plcDataType == null) {
            return DataType.Int4;
        }

        switch (plcDataType.toUpperCase()) {
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
                logger.warn("Unknown data type '{}', defaulting to Int4", plcDataType);
                return DataType.Int4;
        }
    }

    /**
     * Get a default value for a data type.
     *
     * @param dataType The Ignition DataType
     * @return A suitable default value
     */
    private Object getDefaultValue(DataType dataType) {
        switch (dataType) {
            case Boolean:
                return false;
            case Int1:
            case Int2:
            case Int4:
                return 0;
            case Int8:
                return 0L;
            case Float4:
                return 0.0f;
            case Float8:
                return 0.0;
            case String:
                return "";
            default:
                return null;
        }
    }

    /**
     * Register a write handler for a specific tag path.
     * When a client writes to this tag, the handler will be called.
     *
     * @param tagPath     The tag path (e.g., "Controller/Global/Motor1_Running")
     * @param handler     The write handler to register
     */
    public void registerWriteHandler(String tagPath, WriteHandler handler) {
        try {
            provider.registerWriteHandler(tagPath, handler);
            writeHandlers.put(tagPath, handler);
            logger.info("Registered write handler for tag: [PLCSimulator]{}", tagPath);
        } catch (Exception e) {
            logger.error("Error registering write handler for tag: {}", tagPath, e);
        }
    }

    /**
     * Register a default write handler that logs all write operations.
     * This handler accepts the write and logs the new value.
     */
    public void registerDefaultWriteHandlers() {
        logger.info("Registering default write handlers for writable tags");

        // Create a logging write handler
        WriteHandler loggingHandler = (tagPath, value) -> {
            String pathStr = tagPath.toString();
            logger.info("Tag write: [PLCSimulator]{} = {} ({})",
                pathStr, value, value != null ? value.getClass().getSimpleName() : "null");

            // If simulation engine is available, check if tag has active simulation
            if (simulationEngine != null && simulationEngine.hasSimulation(pathStr)) {
                logger.debug("Tag {} has active simulation, write will be overridden by simulation", pathStr);
            }

            return QualityCode.Good;
        };

        // Register default handler for all sample tags
        registerWriteHandler("Controller/Global/Motor1_Speed", loggingHandler);
        registerWriteHandler("Controller/Global/Motor1_Running", loggingHandler);
        registerWriteHandler("Controller/Global/Tank1_Level", loggingHandler);
        registerWriteHandler("Controller/Global/Conveyor_Position", loggingHandler);
        registerWriteHandler("Program/MainProgram/Counter", loggingHandler);
        registerWriteHandler("Program/MainProgram/Timer_Elapsed", loggingHandler);
        registerWriteHandler("Program/MainProgram/Alarm_Active", loggingHandler);
        registerWriteHandler("Program/SafetyProgram/EmergencyStop", loggingHandler);
        registerWriteHandler("Program/SafetyProgram/DoorOpen", loggingHandler);

        logger.info("Registered {} write handlers", writeHandlers.size());
    }

    /**
     * Register a write handler that can control simulations.
     * When a tag is written, it pauses the simulation for that tag.
     *
     * @param tagPath The tag path
     */
    public void registerSimulationControlHandler(String tagPath) {
        WriteHandler controlHandler = (path, value) -> {
            String pathStr = path.toString();
            logger.info("Simulation control write: [PLCSimulator]{} = {}", pathStr, value);

            if (simulationEngine != null) {
                // Remove simulation to allow manual control
                boolean hadSimulation = simulationEngine.hasSimulation(pathStr);
                if (hadSimulation) {
                    simulationEngine.removeSimulation(pathStr);
                    logger.info("Removed simulation for {} to allow manual control", pathStr);
                }
            }

            return QualityCode.Good;
        };

        registerWriteHandler(tagPath, controlHandler);
    }

    /**
     * Unregister a write handler for a specific tag.
     * Note: ManagedTagProvider does not support write handler removal in current API.
     * This method only removes tracking but the handler remains registered.
     *
     * @param tagPath The tag path
     */
    public void unregisterWriteHandler(String tagPath) {
        writeHandlers.remove(tagPath);
        logger.debug("Removed write handler tracking for tag: {} (handler remains active)", tagPath);
        // Note: ManagedTagProvider API doesn't currently support unregisterWriteHandler()
        // The handler will remain active until provider shutdown
    }

    /**
     * Get the number of registered write handlers.
     *
     * @return Write handler count
     */
    public int getWriteHandlerCount() {
        return writeHandlers.size();
    }

    /**
     * Get the underlying ManagedTagProvider.
     */
    public ManagedTagProvider getProvider() {
        return provider;
    }
}
