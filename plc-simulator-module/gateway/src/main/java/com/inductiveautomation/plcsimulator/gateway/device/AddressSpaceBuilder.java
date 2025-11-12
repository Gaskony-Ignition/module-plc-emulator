package com.inductiveautomation.plcsimulator.gateway.device;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.OpcUaDataType;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds hierarchical OPC-UA address space from parsed PLC data.
 *
 * Creates structure like:
 * [DeviceName]/
 *   ├── Controller:Global/
 *   │   ├── Motor1/           (UDT instance as folder)
 *   │   │   ├── Speed
 *   │   │   └── Running
 *   │   └── Tank1_Level        (atomic tag)
 *   └── Program:MainProgram/
 *       └── Counter
 */
public class AddressSpaceBuilder {

    private final Logger logger;
    private final Consumer<UaNode> nodeAdder;
    private final String deviceName;

    // Helper to generate unique node IDs
    private final Map<String, Integer> nodeIdCounter = new HashMap<>();

    /**
     * Creates a new address space builder.
     *
     * @param nodeAdder Function to add nodes (e.g., nodeManager::addNode)
     * @param deviceName Name of the device
     * @param logger Logger for diagnostics
     */
    public AddressSpaceBuilder(
        Consumer<UaNode> nodeAdder,
        String deviceName,
        Logger logger) {

        this.nodeAdder = nodeAdder;
        this.deviceName = deviceName;
        this.logger = logger;
    }

    /**
     * Builds the complete address space from parsed PLC data.
     *
     * @param plcData JSON object containing parsed PLC structure
     * @param rootNode Root folder node for this device
     * @param context Device context for creating nodes
     */
    public void buildAddressSpace(
        JsonObject plcData,
        UaFolderNode rootNode,
        NodeContext context) {

        logger.info("Building address space for device: {}", deviceName);

        // Create Controller:Global folder for global tags
        if (plcData.has("global_tags")) {
            JsonArray globalTags = plcData.getAsJsonArray("global_tags");
            if (globalTags.size() > 0) {
                UaFolderNode controllerFolder = createFolder(
                    context,
                    "Controller:Global",
                    "Controller:Global"
                );
                nodeAdder.accept(controllerFolder);
                rootNode.addOrganizes(controllerFolder);

                // Add global tags
                for (JsonElement tagElement : globalTags) {
                    JsonObject tag = tagElement.getAsJsonObject();
                    addTag(tag, controllerFolder, context, "Controller:Global");
                }

                logger.info("Created Controller:Global with {} tags", globalTags.size());
            }
        }

        // Create Programs parent folder if there are any programs
        if (plcData.has("programs")) {
            JsonArray programs = plcData.getAsJsonArray("programs");
            if (programs.size() > 0) {
                // Create "Programs" parent folder to match real PLC structure
                UaFolderNode programsParentFolder = createFolder(
                    context,
                    "Programs",
                    "Programs"
                );
                nodeAdder.accept(programsParentFolder);
                rootNode.addOrganizes(programsParentFolder);

                // Create individual program folders under Programs
                for (JsonElement programElement : programs) {
                    JsonObject program = programElement.getAsJsonObject();
                    String programName = program.get("name").getAsString();

                    // Use just the program name (not "Program:ProgramName")
                    UaFolderNode programFolder = createFolder(
                        context,
                        "Programs/" + programName,
                        programName
                    );
                    nodeAdder.accept(programFolder);
                    programsParentFolder.addOrganizes(programFolder);

                    // Add program tags
                    if (program.has("tags")) {
                        JsonArray programTags = program.getAsJsonArray("tags");
                        for (JsonElement tagElement : programTags) {
                            JsonObject tag = tagElement.getAsJsonObject();
                            addTag(tag, programFolder, context, "Programs/" + programName);
                        }

                        logger.info("Created Programs/{} with {} tags", programName, programTags.size());
                    }
                }

                logger.info("Created Programs folder with {} program(s)", programs.size());
            }
        }

        // Validate that at least some tags were created
        int totalTags = countTotalTags(plcData);
        if (totalTags == 0) {
            logger.warn("⚠️  WARNING: Zero tags created in address space! File may be invalid, empty, or incorrectly formatted.");
            logger.warn("Check that your file contains valid PLC tag definitions.");
        } else {
            logger.info("✓ Address space building complete - {} total tags created", totalTags);
        }
    }

    /**
     * Adds a tag to the address space.
     * If tag is a UDT instance, creates a folder with member variables.
     * If tag is an array, creates individual array element nodes.
     * If tag is atomic, creates a single variable node.
     */
    private void addTag(
        JsonObject tag,
        UaFolderNode parentFolder,
        NodeContext context,
        String pathPrefix) {

        // Defensive null checks - parser might not provide all fields
        if (tag.get("name") == null) {
            logger.warn("Skipping tag without 'name' field: {}", tag);
            return;
        }
        if (tag.get("data_type") == null) {
            logger.warn("Skipping tag '{}' without 'data_type' field", tag.get("name").getAsString());
            return;
        }

        String tagName = tag.get("name").getAsString();
        String dataType = tag.get("data_type").getAsString();

        // Check if this is a UDT instance (has udt_members)
        if (tag.has("udt_members")) {
            JsonArray members = tag.getAsJsonArray("udt_members");
            if (members.size() > 0) {
                // Create folder for UDT instance
                UaFolderNode udtFolder = createFolder(
                    context,
                    pathPrefix + "/" + tagName,
                    tagName
                );
                nodeAdder.accept(udtFolder);
                parentFolder.addOrganizes(udtFolder);

                // Add UDT member variables
                for (JsonElement memberElement : members) {
                    JsonObject member = memberElement.getAsJsonObject();
                    addAtomicTag(member, udtFolder, context, pathPrefix + "/" + tagName);
                }

                logger.debug("Created UDT folder: {} with {} members", tagName, members.size());
                return;
            }
        }

        // Check if this is an array tag
        if (tag.has("isArray") && tag.get("isArray").getAsBoolean()) {
            if (tag.has("dimensions")) {
                String dimensions = tag.get("dimensions").getAsString();
                try {
                    // Parse dimensions (e.g., "10" for 1D array, "5,3" for 2D array)
                    String[] dims = dimensions.split(",");
                    int arraySize = Integer.parseInt(dims[0].trim());

                    // Create individual array element nodes
                    for (int i = 0; i < arraySize; i++) {
                        JsonObject arrayElement = new JsonObject();
                        arrayElement.addProperty("name", tagName + "[" + i + "]");
                        arrayElement.addProperty("data_type", dataType);

                        // Copy initial value if present
                        if (tag.has("initial_value")) {
                            arrayElement.add("initial_value", tag.get("initial_value"));
                        }

                        addAtomicTag(arrayElement, parentFolder, context, pathPrefix);
                    }

                    logger.debug("Created array: {} with {} elements", tagName, arraySize);
                    return;

                } catch (NumberFormatException e) {
                    logger.warn("Could not parse array dimensions: {} - creating single node", dimensions);
                }
            }
        }

        // Atomic tag - create single variable
        addAtomicTag(tag, parentFolder, context, pathPrefix);
    }

    /**
     * Adds an atomic (non-UDT) tag as a variable node.
     */
    private void addAtomicTag(
        JsonObject tag,
        UaFolderNode parentFolder,
        NodeContext context,
        String pathPrefix) {

        // Defensive null checks - parser might not provide all fields
        if (tag.get("name") == null) {
            logger.warn("Skipping atomic tag without 'name' field: {}", tag);
            return;
        }
        if (tag.get("data_type") == null) {
            logger.warn("Skipping atomic tag '{}' without 'data_type' field", tag.get("name").getAsString());
            return;
        }

        String tagName = tag.get("name").getAsString();
        String dataType = tag.get("data_type").getAsString();

        // Map data type to OPC-UA type
        OpcUaDataType opcType = mapDataType(dataType);

        // Get initial value if present
        Object initialValue = getInitialValue(tag, dataType);

        // Create variable node
        UaVariableNode variableNode = UaVariableNode.build(context.nodeContext, b ->
            b.setNodeId(context.nodeId(pathPrefix + "/" + tagName))
                .setBrowseName(context.qualifiedName(tagName))
                .setDisplayName(new LocalizedText(tagName))
                .setDataType(opcType.getNodeId())
                .setTypeDefinition(NodeIds.BaseDataVariableType)
                .setAccessLevel(AccessLevel.READ_WRITE)
                .setUserAccessLevel(AccessLevel.READ_WRITE)
                .build()
        );

        // Set initial value
        variableNode.setValue(new DataValue(new Variant(initialValue)));

        // Add to node manager and parent folder
        nodeAdder.accept(variableNode);
        parentFolder.addOrganizes(variableNode);

        logger.debug("Created variable: {} ({})", tagName, dataType);
    }

    /**
     * Creates a folder node.
     */
    private UaFolderNode createFolder(NodeContext context, String path, String displayName) {
        return new UaFolderNode(
            context.nodeContext,
            context.nodeId(path),
            context.qualifiedName(displayName),
            new LocalizedText(displayName)
        );
    }

    /**
     * Maps PLC data type string to OPC-UA data type.
     */
    private OpcUaDataType mapDataType(String dataType) {
        return switch (dataType.toUpperCase()) {
            case "BOOL", "BOOLEAN" -> OpcUaDataType.Boolean;
            case "INT1", "SINT", "BYTE" -> OpcUaDataType.SByte;
            case "INT2", "INT" -> OpcUaDataType.Int16;
            case "INT4", "DINT" -> OpcUaDataType.Int32;
            case "FLOAT4", "REAL", "FLOAT" -> OpcUaDataType.Float;
            case "STRING" -> OpcUaDataType.String;
            default -> OpcUaDataType.String;  // Default for unknown types
        };
    }

    /**
     * Extracts initial value from tag JSON, with appropriate type.
     */
    private Object getInitialValue(JsonObject tag, String dataType) {
        if (!tag.has("initial_value") || tag.get("initial_value").isJsonNull()) {
            // Default values
            return switch (dataType.toUpperCase()) {
                case "BOOL", "BOOLEAN" -> false;
                case "INT1", "SINT", "BYTE", "INT2", "INT", "INT4", "DINT" -> 0;
                case "FLOAT4", "REAL", "FLOAT" -> 0.0f;
                case "STRING" -> "";
                default -> "";
            };
        }

        JsonElement initialValueElement = tag.get("initial_value");

        // Extract value based on data type
        return switch (dataType.toUpperCase()) {
            case "BOOL", "BOOLEAN" -> initialValueElement.getAsBoolean();
            case "INT1", "SINT", "BYTE", "INT2", "INT" -> (short) initialValueElement.getAsInt();
            case "INT4", "DINT" -> initialValueElement.getAsInt();
            case "FLOAT4", "REAL", "FLOAT" -> initialValueElement.getAsFloat();
            case "STRING" -> initialValueElement.getAsString();
            default -> initialValueElement.getAsString();
        };
    }

    /**
     * Count total tags in the parsed PLC data structure.
     * Used for validation to detect empty/invalid files.
     */
    private static int countTotalTags(JsonObject plcData) {
        int count = 0;

        // Count global tags
        if (plcData.has("global_tags")) {
            JsonArray globalTags = plcData.getAsJsonArray("global_tags");
            count += globalTags.size();
        }

        // Count program tags
        if (plcData.has("programs")) {
            JsonArray programs = plcData.getAsJsonArray("programs");
            for (JsonElement programElement : programs) {
                JsonObject program = programElement.getAsJsonObject();
                if (program.has("tags")) {
                    JsonArray programTags = program.getAsJsonArray("tags");
                    count += programTags.size();
                }
            }
        }

        return count;
    }

    /**
     * Helper class to pass device context information.
     */
    public static class NodeContext {
        public final org.eclipse.milo.opcua.sdk.server.nodes.UaNodeContext nodeContext;
        private final com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext deviceContext;

        public NodeContext(
            org.eclipse.milo.opcua.sdk.server.nodes.UaNodeContext nodeContext,
            com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext deviceContext) {
            this.nodeContext = nodeContext;
            this.deviceContext = deviceContext;
        }

        public NodeId nodeId(String identifier) {
            return deviceContext.nodeId(identifier);
        }

        public QualifiedName qualifiedName(String name) {
            return deviceContext.qualifiedName(name);
        }
    }
}
