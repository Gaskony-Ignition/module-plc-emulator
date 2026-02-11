package com.inductiveautomation.logixemulator.gateway.device;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaObjectNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.nodes.filters.AttributeFilters;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.OpcUaDataType;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.slf4j.Logger;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds hierarchical OPC-UA address space from parsed PLC data.
 *
 * Creates structure matching real Rockwell PLCs:
 * [DeviceName]/
 *   ├── Controller:Global/
 *   │   ├── Motor1/                 (UDT instance - Object node, NodeId uses dots)
 *   │   │   ├── Speed               (member variable, BrowseName="Speed", NodeId="Controller:Global.Motor1.Speed")
 *   │   │   └── Running             (member variable, BrowseName="Running")
 *   │   └── Tank1_Level             (atomic tag)
 *   ├── Motor1/                     (alias - enables [Device]Motor1.Speed short path)
 *   ├── Tank1_Level                 (alias - enables [Device]Tank1_Level short path)
 *   └── Programs/
 *       └── MainProgram/
 *           └── Counter
 *
 * KEY DESIGN PRINCIPLES:
 * 1. Browse Hierarchy: UDT instances appear as browsable Object nodes with member children
 * 2. NodeId Format: Uses DOT notation (e.g., "Controller:Global.Motor1.Speed")
 * 3. BrowseName Format: Uses simple names (e.g., "Speed" not "Motor1.Speed")
 * 4. Reference Type: Uses HasComponent for UDT structure (not Organizes)
 * 5. Aliasing: UDT instances aliased at root to enable short paths like [Device]Motor1.Speed
 */
public class AddressSpaceBuilder {

    private final Logger logger;
    private final Consumer<UaNode> nodeAdder;
    private final String deviceName;

    // ThreadLocal to prevent infinite recursion in synchronized writes
    private static final ThreadLocal<NodeId> currentlyWritingNode = new ThreadLocal<>();

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
                    addTag(tag, controllerFolder, context, "Controller:Global", rootNode);
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
                            addTag(tag, programFolder, context, "Programs/" + programName, null);
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
     * If tag is a UDT instance, creates a hierarchical Object node with member variables.
     * If tag is an array, creates individual array element nodes.
     * If tag is atomic, creates a single variable node.
     *
     * @param rootNode Root node for creating aliases (pass null for program tags to skip aliasing)
     */
    private void addTag(
        JsonObject tag,
        UaFolderNode parentFolder,
        NodeContext context,
        String pathPrefix,
        UaFolderNode rootNode) {

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
                // HIERARCHICAL STRUCTURE: Create UDT instance as Object node with member children
                // This matches real Rockwell PLC behavior:
                // - Browse hierarchy: Motor1 (Object) → Speed, Running (Variables)
                // - NodeId format: Uses DOTS → "Controller:Global.Motor1.Speed"
                // - BrowseName: Simple names → "Speed" (not "Motor1.Speed")
                // - Short path: [Device]Motor1.Speed (via duplicate nodes)
                // - Long path: [Device]Controller:Global.Motor1.Speed (via dot resolution)

                // Create Object node for UDT instance (using DOT notation in NodeId)
                String udtNodeId = pathPrefix.replace("/", ".") + "." + tagName;
                UaObjectNode udtObject = UaObjectNode.build(context.nodeContext, b ->
                    b.setNodeId(context.nodeId(udtNodeId))
                        .setBrowseName(context.qualifiedName(tagName))  // Simple name
                        .setDisplayName(new LocalizedText(tagName))
                        .setTypeDefinition(NodeIds.BaseObjectType)
                        .build()
                );

                nodeAdder.accept(udtObject);
                parentFolder.addComponent(udtObject);  // Use HasComponent reference

                // For Controller:Global tags, create DUPLICATE nodes with short NodeIds
                // This is how real Rockwell PLCs work - not aliases, but duplicate nodes
                UaObjectNode shortPathObject = null;
                if (rootNode != null && pathPrefix.equals("Controller:Global")) {
                    // Create a DUPLICATE Object node with SHORT NodeId at device root
                    shortPathObject = UaObjectNode.build(context.nodeContext, b ->
                        b.setNodeId(context.nodeId(tagName))  // SHORT NodeId: just "Motor1"
                            .setBrowseName(context.qualifiedName(tagName))
                            .setDisplayName(new LocalizedText(tagName))
                            .setTypeDefinition(NodeIds.BaseObjectType)
                            .build()
                    );

                    nodeAdder.accept(shortPathObject);
                    rootNode.addComponent(shortPathObject);  // Add to device root
                    logger.debug("Created duplicate UDT instance '{}' with short NodeId at device root", tagName);
                }

                // Add UDT member variables as children of the Object node
                for (JsonElement memberElement : members) {
                    JsonObject member = memberElement.getAsJsonObject();

                    // Create the member in the main UDT object
                    UaVariableNode longPathMember = addUdtMember(member, udtObject, context, udtNodeId, tagName);

                    // Also add member to the short path object if it exists, with synchronized writes
                    if (shortPathObject != null && longPathMember != null) {
                        // Create duplicate member with short NodeId path
                        String memberName = member.get("name").getAsString();
                        String memberDataType = member.get("data_type").getAsString();
                        OpcUaDataType opcType = mapDataType(memberDataType);
                        Object initialValue = getInitialValue(member, memberDataType);

                        // Short NodeId: just "Motor1.ENABLE" instead of "Controller:Global.Motor1.ENABLE"
                        String shortMemberNodeId = tagName + "." + memberName;

                        UaVariableNode shortMemberVariable = UaVariableNode.build(context.nodeContext, b ->
                            b.setNodeId(context.nodeId(shortMemberNodeId))
                                .setBrowseName(context.qualifiedName(memberName))
                                .setDisplayName(new LocalizedText(memberName))
                                .setDataType(opcType.getNodeId())
                                .setTypeDefinition(NodeIds.BaseDataVariableType)
                                .setAccessLevel(AccessLevel.READ_WRITE)
                                .setUserAccessLevel(AccessLevel.READ_WRITE)
                                .build()
                        );

                        // Set same initial value
                        shortMemberVariable.setValue(new DataValue(new Variant(initialValue)));

                        // Enable synchronized writes between both nodes
                        enableSynchronizedWrites(longPathMember, shortMemberVariable);

                        nodeAdder.accept(shortMemberVariable);
                        shortPathObject.addComponent(shortMemberVariable);

                        logger.trace("Created duplicate UDT member with synchronized writes: {}", shortMemberNodeId);
                    }
                }

                logger.debug("Created UDT instance '{}' with {} hierarchical members", tagName, members.size());
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

                        addAtomicTag(arrayElement, parentFolder, context, pathPrefix, rootNode);
                    }

                    logger.debug("Created array: {} with {} elements", tagName, arraySize);
                    return;

                } catch (NumberFormatException e) {
                    logger.warn("Could not parse array dimensions: {} - creating single node", dimensions);
                }
            }
        }

        // Atomic tag - create single variable
        addAtomicTag(tag, parentFolder, context, pathPrefix, rootNode);
    }

    /**
     * Adds a UDT member as a child of a UDT Object node.
     * If the member is itself a nested UDT (has udt_members), creates a nested Object node.
     * Otherwise creates a variable node.
     * Uses DOT notation in NodeId and simple BrowseName to match real Rockwell PLC behavior.
     * Returns the created variable node so it can be synchronized with duplicate nodes.
     *
     * @param member Member tag data
     * @param udtObject Parent UDT Object node
     * @param context Device context
     * @param udtNodeId NodeId of parent UDT (e.g., "Controller:Global.Motor1")
     * @param udtName Name of parent UDT (e.g., "Motor1") for logging
     * @return The UaVariableNode created for this member (for synchronizing with duplicate nodes), or null for nested UDTs
     */
    private UaVariableNode addUdtMember(
        JsonObject member,
        UaObjectNode udtObject,
        NodeContext context,
        String udtNodeId,
        String udtName) {

        // Defensive null checks
        if (member.get("name") == null) {
            logger.warn("Skipping UDT member without 'name' field in UDT '{}'", udtName);
            return null;
        }
        if (member.get("data_type") == null) {
            logger.warn("Skipping UDT member '{}' without 'data_type' field in UDT '{}'",
                member.get("name").getAsString(), udtName);
            return null;
        }

        String memberName = member.get("name").getAsString();
        String dataType = member.get("data_type").getAsString();
        String memberNodeId = udtNodeId + "." + memberName;

        // Check if this member is itself a nested UDT (has udt_members)
        if (member.has("udt_members")) {
            JsonArray nestedMembers = member.getAsJsonArray("udt_members");
            if (nestedMembers.size() > 0) {
                // Create nested Object node for the nested UDT
                UaObjectNode nestedObject = UaObjectNode.build(context.nodeContext, b ->
                    b.setNodeId(context.nodeId(memberNodeId))
                        .setBrowseName(context.qualifiedName(memberName))
                        .setDisplayName(new LocalizedText(memberName))
                        .setTypeDefinition(NodeIds.BaseObjectType)
                        .build()
                );

                nodeAdder.accept(nestedObject);
                udtObject.addComponent(nestedObject);

                // Recursively add nested UDT members
                for (JsonElement nestedMemberElement : nestedMembers) {
                    JsonObject nestedMember = nestedMemberElement.getAsJsonObject();
                    addUdtMember(nestedMember, nestedObject, context, memberNodeId, memberName);
                }

                logger.debug("Created nested UDT: {}.{} of type {} with {} members",
                    udtName, memberName, dataType, nestedMembers.size());

                // Return null for nested UDTs (they're Object nodes, not Variable nodes)
                return null;
            }
        }

        // Atomic member - create variable node
        OpcUaDataType opcType = mapDataType(dataType);
        Object initialValue = getInitialValue(member, dataType);

        UaVariableNode memberVariable = UaVariableNode.build(context.nodeContext, b ->
            b.setNodeId(context.nodeId(memberNodeId))
                .setBrowseName(context.qualifiedName(memberName))  // Simple name, NOT "Motor1.Speed"
                .setDisplayName(new LocalizedText(memberName))
                .setDataType(opcType.getNodeId())
                .setTypeDefinition(NodeIds.BaseDataVariableType)
                .setAccessLevel(AccessLevel.READ_WRITE)
                .setUserAccessLevel(AccessLevel.READ_WRITE)
                .build()
        );

        // Create DataValue and set initial value
        DataValue dataValue = new DataValue(new Variant(initialValue));
        memberVariable.setValue(dataValue);

        // Note: Write handling is configured later via enableSynchronizedWrites()
        // to coordinate updates with duplicate short-path nodes

        // Add to node manager
        nodeAdder.accept(memberVariable);

        // Add as component of UDT Object (creates HasComponent reference)
        udtObject.addComponent(memberVariable);

        logger.trace("Created UDT member: {}.{} (NodeId={}, Type={})",
            udtName, memberName, memberNodeId, dataType);

        return memberVariable;  // Return for synchronizing with duplicate nodes
    }

    /**
     * Adds an atomic (non-UDT) tag as a variable node.
     *
     * @param rootNode Root node for creating aliases (pass null for nested tags to skip aliasing)
     */
    private void addAtomicTag(
        JsonObject tag,
        UaFolderNode parentFolder,
        NodeContext context,
        String pathPrefix,
        UaFolderNode rootNode) {

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

        // Build NodeId with DOT notation for consistency (Controller:Global.TagName)
        String nodeIdPath = pathPrefix.replace("/", ".") + "." + tagName;

        // Create variable node
        UaVariableNode variableNode = UaVariableNode.build(context.nodeContext, b ->
            b.setNodeId(context.nodeId(nodeIdPath))
                .setBrowseName(context.qualifiedName(tagName))
                .setDisplayName(new LocalizedText(tagName))
                .setDataType(opcType.getNodeId())
                .setTypeDefinition(NodeIds.BaseDataVariableType)
                .setAccessLevel(AccessLevel.READ_WRITE)
                .setUserAccessLevel(AccessLevel.READ_WRITE)
                .build()
        );

        // Set initial value
        DataValue dataValue = new DataValue(new Variant(initialValue));
        variableNode.setValue(dataValue);

        // Add to node manager and parent folder
        nodeAdder.accept(variableNode);
        parentFolder.addOrganizes(variableNode);

        // For Controller:Global atomic tags, create DUPLICATE node with short NodeId
        // This matches real Rockwell PLC behavior - not an alias, but a duplicate node
        if (rootNode != null && pathPrefix.equals("Controller:Global")) {
            // Create duplicate variable with SHORT NodeId (just tagName, no prefix)
            UaVariableNode shortPathVariable = UaVariableNode.build(context.nodeContext, b ->
                b.setNodeId(context.nodeId(tagName))  // SHORT NodeId: just "TagName"
                    .setBrowseName(context.qualifiedName(tagName))
                    .setDisplayName(new LocalizedText(tagName))
                    .setDataType(opcType.getNodeId())
                    .setTypeDefinition(NodeIds.BaseDataVariableType)
                    .setAccessLevel(AccessLevel.READ_WRITE)
                    .setUserAccessLevel(AccessLevel.READ_WRITE)
                    .build()
            );

            // Set same initial value
            shortPathVariable.setValue(new DataValue(new Variant(initialValue)));

            // Enable synchronized writes between both nodes
            enableSynchronizedWrites(variableNode, shortPathVariable);

            nodeAdder.accept(shortPathVariable);
            rootNode.addOrganizes(shortPathVariable);
            logger.debug("Created duplicate atomic tag '{}' with synchronized writes at device root", tagName);
        } else {
            // For non-duplicated tags (e.g., program tags), enable regular writes
            enableWrites(variableNode);
        }

        logger.debug("Created atomic variable: {} ({})", tagName, dataType);
    }

    /**
     * Creates a folder node.
     * Note: Folders still use slash notation in paths for hierarchical structure,
     * while tags/UDTs use dot notation in their NodeIds for tag path resolution.
     */
    private UaFolderNode createFolder(NodeContext context, String path, String displayName) {
        return new UaFolderNode(
            context.nodeContext,
            context.nodeId(path),  // Keep slash notation for folders
            context.qualifiedName(displayName),
            new LocalizedText(displayName)
        );
    }

    /**
     * Enable write operations for a variable node.
     * Adds a filter to handle incoming OPC-UA write requests.
     */
    private void enableWrites(UaVariableNode variableNode) {
        variableNode.getFilterChain().addLast(
            AttributeFilters.setValue(
                (ctx, value) -> {
                    variableNode.setValue(value);
                    ctx.setAttribute(AttributeId.Value, value);  // Signal write completion
                }
            )
        );
    }

    /**
     * Enable synchronized writes for a pair of duplicate nodes (long path + short path).
     * When either node is written to, both nodes are updated to keep them in sync.
     * Uses ThreadLocal to prevent infinite recursion.
     */
    private void enableSynchronizedWrites(UaVariableNode node1, UaVariableNode node2) {
        node1.getFilterChain().addLast(
            AttributeFilters.setValue(
                (ctx, value) -> {
                    // Prevent recursion: only update the other node if we're not already in a write operation
                    NodeId currentlyWriting = currentlyWritingNode.get();
                    if (currentlyWriting == null) {
                        currentlyWritingNode.set(node1.getNodeId());
                        try {
                            // Update the synchronized node
                            node2.setValue(value);
                        } finally {
                            currentlyWritingNode.remove();
                        }
                    }
                    // Complete this node's write
                    ctx.setAttribute(AttributeId.Value, value);
                }
            )
        );

        node2.getFilterChain().addLast(
            AttributeFilters.setValue(
                (ctx, value) -> {
                    // Prevent recursion: only update the other node if we're not already in a write operation
                    NodeId currentlyWriting = currentlyWritingNode.get();
                    if (currentlyWriting == null) {
                        currentlyWritingNode.set(node2.getNodeId());
                        try {
                            // Update the synchronized node
                            node1.setValue(value);
                        } finally {
                            currentlyWritingNode.remove();
                        }
                    }
                    // Complete this node's write
                    ctx.setAttribute(AttributeId.Value, value);
                }
            )
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
