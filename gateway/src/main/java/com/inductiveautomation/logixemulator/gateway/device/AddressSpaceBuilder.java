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

        int skippedTags = 0;

        // Create Controller:Global folder for global tags
        if (plcData.has("global_tags")) {
            JsonArray globalTags = plcData.getAsJsonArray("global_tags");
            if (globalTags.size() > 0) {
                UaFolderNode controllerFolder = context.createFolder(
                    "Controller:Global",
                    "Controller:Global"
                );
                nodeAdder.accept(controllerFolder);
                rootNode.addOrganizes(controllerFolder);

                // Add global tags
                for (JsonElement tagElement : globalTags) {
                    JsonObject tag = tagElement.getAsJsonObject();
                    if (!tryAddTag(tag, controllerFolder, context, "Controller:Global", rootNode)) {
                        skippedTags++;
                    }
                }

                logger.info("Created Controller:Global with {} tags", globalTags.size());
            }
        }

        // Create Programs parent folder if there are any programs
        if (plcData.has("programs")) {
            JsonArray programs = plcData.getAsJsonArray("programs");
            if (programs.size() > 0) {
                // Create "Programs" parent folder to match real PLC structure
                UaFolderNode programsParentFolder = context.createFolder(
                    "Programs",
                    "Programs"
                );
                nodeAdder.accept(programsParentFolder);
                rootNode.addOrganizes(programsParentFolder);

                // Create individual program folders under Programs
                for (JsonElement programElement : programs) {
                    JsonObject program = programElement.getAsJsonObject();
                    String programName = program.get("name").getAsString();

                    UaFolderNode programFolder = context.createFolder(
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
                            if (!tryAddTag(tag, programFolder, context, "Programs/" + programName, null)) {
                                skippedTags++;
                            }
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
            logger.warn("[WARNING] Zero tags created in address space! File may be invalid, empty, or incorrectly formatted.");
            logger.warn("Check that your file contains valid PLC tag definitions.");
        } else {
            logger.info("[OK] Address space building complete - {} total tags created", totalTags);
        }

        if (skippedTags > 0) {
            logger.warn(
                "Address space build for device '{}' completed with {} tag(s) skipped due to errors "
                    + "- see preceding WARN entries for the affected tag names",
                deviceName, skippedTags);
        }
    }

    /**
     * Adds a single tag to the address space, catching and logging any exception so that one
     * malformed tag cannot abort the whole address-space build (defect B1). Returns {@code true}
     * on success, {@code false} if the tag was skipped due to an error.
     */
    private boolean tryAddTag(
        JsonObject tag,
        UaFolderNode parentFolder,
        NodeContext context,
        String pathPrefix,
        UaFolderNode rootNode) {

        String tagName = tag.has("name") ? tag.get("name").getAsString() : "<unnamed>";
        try {
            addTag(tag, parentFolder, context, pathPrefix, rootNode);
            return true;
        } catch (RuntimeException e) {
            logger.warn("Skipping tag '{}' under '{}' - failed to add to address space: {}",
                tagName, pathPrefix, e.toString(), e);
            return false;
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
                String udtNodeId = pathPrefix.replace("/", ".") + "." + tagName;
                UaObjectNode udtObject = context.createObjectNode(udtNodeId, tagName);

                nodeAdder.accept(udtObject);
                parentFolder.addComponent(udtObject);

                // For Controller:Global tags, create DUPLICATE nodes with short NodeIds
                UaObjectNode shortPathObject = null;
                if (rootNode != null && pathPrefix.equals("Controller:Global")) {
                    shortPathObject = context.createObjectNode(tagName, tagName);
                    nodeAdder.accept(shortPathObject);
                    rootNode.addComponent(shortPathObject);
                    logger.debug("Created duplicate UDT instance '{}' with short NodeId at device root", tagName);
                }

                // Add UDT member variables as children of the Object node
                for (JsonElement memberElement : members) {
                    JsonObject member = memberElement.getAsJsonObject();

                    UaVariableNode longPathMember = addUdtMember(member, udtObject, context, udtNodeId, tagName);

                    if (shortPathObject != null && longPathMember != null) {
                        String memberName = member.get("name").getAsString();
                        String memberDataType = member.get("data_type").getAsString();
                        OpcUaDataType opcType = mapDataType(memberDataType);
                        Object initialValue = getInitialValue(member, memberDataType);

                        String shortMemberNodeId = tagName + "." + memberName;

                        UaVariableNode shortMemberVariable = context.createVariableNode(
                            shortMemberNodeId, memberName, opcType.getNodeId());
                        shortMemberVariable.setValue(new DataValue(new Variant(initialValue)));

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
                    String[] dims = dimensions.split(",");
                    int arraySize = Integer.parseInt(dims[0].trim());

                    for (int i = 0; i < arraySize; i++) {
                        JsonObject arrayElement = new JsonObject();
                        arrayElement.addProperty("name", tagName + "[" + i + "]");
                        arrayElement.addProperty("data_type", dataType);

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
     * Returns the created variable node so it can be synchronized with duplicate nodes.
     */
    private UaVariableNode addUdtMember(
        JsonObject member,
        UaObjectNode udtObject,
        NodeContext context,
        String udtNodeId,
        String udtName) {

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

        // Check if this member is itself a nested UDT
        if (member.has("udt_members")) {
            JsonArray nestedMembers = member.getAsJsonArray("udt_members");
            if (nestedMembers.size() > 0) {
                UaObjectNode nestedObject = context.createObjectNode(memberNodeId, memberName);

                nodeAdder.accept(nestedObject);
                udtObject.addComponent(nestedObject);

                for (JsonElement nestedMemberElement : nestedMembers) {
                    JsonObject nestedMember = nestedMemberElement.getAsJsonObject();
                    addUdtMember(nestedMember, nestedObject, context, memberNodeId, memberName);
                }

                logger.debug("Created nested UDT: {}.{} of type {} with {} members",
                    udtName, memberName, dataType, nestedMembers.size());
                return null;
            }
        }

        // Atomic member - create variable node
        OpcUaDataType opcType = mapDataType(dataType);
        Object initialValue = getInitialValue(member, dataType);

        UaVariableNode memberVariable = context.createVariableNode(memberNodeId, memberName, opcType.getNodeId());

        DataValue dataValue = new DataValue(new Variant(initialValue));
        memberVariable.setValue(dataValue);

        nodeAdder.accept(memberVariable);
        udtObject.addComponent(memberVariable);

        logger.trace("Created UDT member: {}.{} (NodeId={}, Type={})",
            udtName, memberName, memberNodeId, dataType);

        return memberVariable;
    }

    /**
     * Adds an atomic (non-UDT) tag as a variable node.
     */
    private void addAtomicTag(
        JsonObject tag,
        UaFolderNode parentFolder,
        NodeContext context,
        String pathPrefix,
        UaFolderNode rootNode) {

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

        OpcUaDataType opcType = mapDataType(dataType);
        Object initialValue = getInitialValue(tag, dataType);

        String nodeIdPath = pathPrefix.replace("/", ".") + "." + tagName;

        UaVariableNode variableNode = context.createVariableNode(nodeIdPath, tagName, opcType.getNodeId());

        DataValue dataValue = new DataValue(new Variant(initialValue));
        variableNode.setValue(dataValue);

        nodeAdder.accept(variableNode);
        parentFolder.addOrganizes(variableNode);

        if (rootNode != null && pathPrefix.equals("Controller:Global")) {
            UaVariableNode shortPathVariable = context.createVariableNode(tagName, tagName, opcType.getNodeId());
            shortPathVariable.setValue(new DataValue(new Variant(initialValue)));

            enableSynchronizedWrites(variableNode, shortPathVariable);

            nodeAdder.accept(shortPathVariable);
            rootNode.addOrganizes(shortPathVariable);
            logger.debug("Created duplicate atomic tag '{}' with synchronized writes at device root", tagName);
        } else {
            enableWrites(variableNode);
        }

        logger.debug("Created atomic variable: {} ({})", tagName, dataType);
    }

    /**
     * Enable write operations for a variable node.
     */
    private void enableWrites(UaVariableNode variableNode) {
        variableNode.getFilterChain().addLast(
            AttributeFilters.setValue(
                (ctx, value) -> {
                    variableNode.setValue(value);
                    ctx.setAttribute(AttributeId.Value, value);
                }
            )
        );
    }

    /**
     * Enable synchronized writes for a pair of duplicate nodes (long path + short path).
     * Re-entrancy is guarded via {@link WriteSyncHelpers}.
     */
    private void enableSynchronizedWrites(UaVariableNode node1, UaVariableNode node2) {
        node1.getFilterChain().addLast(
            AttributeFilters.setValue(
                (ctx, value) -> {
                    if (!WriteSyncHelpers.isReentrant()) {
                        WriteSyncHelpers.runGuarded(node1.getNodeId(), () -> node2.setValue(value));
                    }
                    ctx.setAttribute(AttributeId.Value, value);
                }
            )
        );

        node2.getFilterChain().addLast(
            AttributeFilters.setValue(
                (ctx, value) -> {
                    if (!WriteSyncHelpers.isReentrant()) {
                        WriteSyncHelpers.runGuarded(node2.getNodeId(), () -> node1.setValue(value));
                    }
                    ctx.setAttribute(AttributeId.Value, value);
                }
            )
        );
    }

    /**
     * Maps PLC data type string to OPC-UA data type.
     * Package-private for unit testing.
     */
    OpcUaDataType mapDataType(String dataType) {
        return switch (dataType.toUpperCase()) {
            case "BOOL", "BOOLEAN" -> OpcUaDataType.Boolean;
            case "INT1", "SINT", "BYTE" -> OpcUaDataType.SByte;
            case "INT2", "INT" -> OpcUaDataType.Int16;
            case "INT4", "DINT" -> OpcUaDataType.Int32;
            case "INT8", "LINT" -> OpcUaDataType.Int64;
            case "FLOAT4", "REAL", "FLOAT" -> OpcUaDataType.Float;
            case "FLOAT8", "LREAL", "DOUBLE" -> OpcUaDataType.Double;
            case "STRING" -> OpcUaDataType.String;
            default -> OpcUaDataType.String;
        };
    }

    /**
     * Extracts initial value from tag JSON, with appropriate type.
     * Package-private for unit testing.
     */
    Object getInitialValue(JsonObject tag, String dataType) {
        Object defaultValue = defaultInitialValue(dataType);

        if (!tag.has("initial_value") || tag.get("initial_value").isJsonNull()) {
            return defaultValue;
        }

        JsonElement initialValueElement = tag.get("initial_value");

        try {
            return switch (dataType.toUpperCase()) {
                case "BOOL", "BOOLEAN" -> initialValueElement.getAsBoolean();
                case "INT1", "SINT", "BYTE", "INT2", "INT" -> (short) initialValueElement.getAsInt();
                case "INT4", "DINT" -> initialValueElement.getAsInt();
                case "INT8", "LINT" -> initialValueElement.getAsLong();
                case "FLOAT4", "REAL", "FLOAT" -> initialValueElement.getAsFloat();
                case "FLOAT8", "LREAL", "DOUBLE" -> initialValueElement.getAsDouble();
                case "STRING" -> initialValueElement.getAsString();
                default -> initialValueElement.getAsString();
            };
        } catch (NumberFormatException | UnsupportedOperationException | IllegalStateException e) {
            // Non-numeric/unparseable initial_value (e.g. the "{structure}" sentinel, an empty
            // string, or any other value that doesn't fit the declared data type) - fall back to
            // the type-appropriate default rather than aborting the whole tag/build (defect B1).
            logger.debug(
                "Unparseable initial_value {} for data type {} - using default {}",
                initialValueElement, dataType, defaultValue, e);
            return defaultValue;
        }
    }

    /**
     * Type-appropriate default value used both when a tag has no initial_value and when its
     * initial_value cannot be parsed as the declared data type.
     */
    private static Object defaultInitialValue(String dataType) {
        return switch (dataType.toUpperCase()) {
            case "BOOL", "BOOLEAN" -> false;
            case "INT1", "SINT", "BYTE", "INT2", "INT", "INT4", "DINT" -> 0;
            case "INT8", "LINT" -> 0L;
            case "FLOAT4", "REAL", "FLOAT" -> 0.0f;
            case "FLOAT8", "LREAL", "DOUBLE" -> 0.0;
            case "STRING" -> "";
            default -> "";
        };
    }

    /**
     * Count total tags in the parsed PLC data structure.
     * Package-private for unit testing.
     */
    static int countTotalTags(JsonObject plcData) {
        int count = 0;

        if (plcData.has("global_tags")) {
            JsonArray globalTags = plcData.getAsJsonArray("global_tags");
            count += globalTags.size();
        }

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

    // =========================================================================
    // NodeContext — wraps OPC-UA node construction with overridable factory methods
    // =========================================================================

    /**
     * Helper class to pass device context information.
     *
     * Factory methods ({@link #createFolder}, {@link #createVariableNode}, {@link #createObjectNode})
     * are {@code protected} so test subclasses can return mocked nodes without Ignition/OPC-UA runtime.
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

        /**
         * Protected constructor for test subclasses — allows null internals when factory methods
         * are overridden.
         */
        protected NodeContext() {
            this.nodeContext = null;
            this.deviceContext = null;
        }

        public NodeId nodeId(String identifier) {
            return deviceContext.nodeId(identifier);
        }

        public QualifiedName qualifiedName(String name) {
            return deviceContext.qualifiedName(name);
        }

        // ── Node factory methods (overridable for testing) ─────────────────

        /**
         * Create a folder node. Override in tests to return a mock.
         */
        protected UaFolderNode createFolder(String path, String displayName) {
            return new UaFolderNode(
                nodeContext,
                nodeId(path),
                qualifiedName(displayName),
                new LocalizedText(displayName)
            );
        }

        /**
         * Create a variable node. Override in tests to return a mock.
         */
        protected UaVariableNode createVariableNode(String nodeIdPath, String name, NodeId dataType) {
            return UaVariableNode.build(nodeContext, b ->
                b.setNodeId(nodeId(nodeIdPath))
                    .setBrowseName(qualifiedName(name))
                    .setDisplayName(new LocalizedText(name))
                    .setDataType(dataType)
                    .setTypeDefinition(NodeIds.BaseDataVariableType)
                    .setAccessLevel(AccessLevel.READ_WRITE)
                    .setUserAccessLevel(AccessLevel.READ_WRITE)
                    .build()
            );
        }

        /**
         * Create an object node. Override in tests to return a mock.
         */
        protected UaObjectNode createObjectNode(String nodeIdPath, String name) {
            return UaObjectNode.build(nodeContext, b ->
                b.setNodeId(nodeId(nodeIdPath))
                    .setBrowseName(qualifiedName(name))
                    .setDisplayName(new LocalizedText(name))
                    .setTypeDefinition(NodeIds.BaseObjectType)
                    .build()
            );
        }
    }
}
