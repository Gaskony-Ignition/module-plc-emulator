package com.inductiveautomation.logixemulator.gateway.device;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.inductiveautomation.logixemulator.gateway.address.AddressPolicy;
import com.inductiveautomation.logixemulator.gateway.address.RockwellLogixPolicy;
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
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.slf4j.Logger;

import java.util.List;
import java.util.function.Consumer;

/**
 * Builds a hierarchical OPC-UA address space from parsed PLC data.
 *
 * <p><b>Vendor-neutral by design (ADDRESSING.md §1).</b> This class contains no vendor-specific
 * addressing rules — no {@code Program:} prefix, no BOOL-packing, no member-separator or member
 * tables. It walks the vendor-neutral parsed-tag JSON model and asks an {@link AddressPolicy} for
 * every NodeId identifier and browse-folder label. The only Rockwell policy in v10 is
 * {@link RockwellLogixPolicy}.
 *
 * <p><b>One canonical node per tag (ADDRESSING.md §2.2 — C1).</b> Each parsed tag maps to exactly
 * one variable/object node whose NodeId identifier is the driver-canonical form:
 * <ul>
 *   <li>controller-scoped: bare {@code TagName};</li>
 *   <li>program-scoped: {@code Program:<Prog>.TagName}.</li>
 * </ul>
 * The pre-v10 duplicate-node scheme (a {@code Controller:Global.<tag>} long form plus a bare
 * short-alias duplicate kept in step by a synchronised-write filter pair) has been removed
 * entirely. Browse folders ({@code Controller:Global}, {@code Programs/<Name>}) are cosmetic and
 * never leak into a child NodeId (ADDRESSING.md §2.3).
 *
 * <p><b>Full array expansion (ADDRESSING.md §3.4-§3.7 — C2)</b> and <b>DWORD-packed BOOL arrays
 * (ADDRESSING.md §3.8 — C3)</b> are driven through the policy: arrays of UDTs/predefined types
 * expand to {@code Tag[i].Member}, UDT member arrays expand, multi-dimensional arrays use
 * {@code [i,j]}/{@code [i,j,k]}, and BOOL arrays emit only {@code Tag[word].bit} nodes.
 */
public class AddressSpaceBuilder {

    private final Logger logger;
    private final Consumer<UaNode> nodeAdder;
    private final String deviceName;
    private final AddressPolicy policy;

    /**
     * Creates a new address space builder using the default Rockwell Logix addressing policy.
     *
     * @param nodeAdder Function to add nodes (e.g., nodeManager::addNode)
     * @param deviceName Name of the device
     * @param logger Logger for diagnostics
     */
    public AddressSpaceBuilder(
        Consumer<UaNode> nodeAdder,
        String deviceName,
        Logger logger) {
        this(nodeAdder, deviceName, logger, new RockwellLogixPolicy());
    }

    /**
     * Creates a new address space builder with an explicit addressing policy (a future vendor
     * supplies its own without touching this class).
     *
     * @param nodeAdder Function to add nodes (e.g., nodeManager::addNode)
     * @param deviceName Name of the device
     * @param logger Logger for diagnostics
     * @param policy the vendor addressing policy
     */
    public AddressSpaceBuilder(
        Consumer<UaNode> nodeAdder,
        String deviceName,
        Logger logger,
        AddressPolicy policy) {

        this.nodeAdder = nodeAdder;
        this.deviceName = deviceName;
        this.logger = logger;
        this.policy = policy;
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

        // Controller-scoped (global) tags — grouped under a cosmetic browse folder; each tag's
        // NodeId is the bare canonical identifier (ADDRESSING.md §2.2, §3.1).
        if (plcData.has("global_tags")) {
            JsonArray globalTags = plcData.getAsJsonArray("global_tags");
            if (globalTags.size() > 0) {
                String folderLabel = policy.controllerBrowseFolder();
                UaFolderNode controllerFolder = context.createFolder(folderLabel, folderLabel);
                nodeAdder.accept(controllerFolder);
                rootNode.addOrganizes(controllerFolder);

                for (JsonElement tagElement : globalTags) {
                    JsonObject tag = tagElement.getAsJsonObject();
                    if (!tryAddTag(tag, controllerFolder, context, policy.controllerScopePrefix())) {
                        skippedTags++;
                    }
                }

                logger.info("Created {} browse folder with {} tags", folderLabel, globalTags.size());
            }
        }

        // Program-scoped tags — grouped under cosmetic per-program browse folders; each tag's
        // NodeId carries the Program:<Prog> selector (ADDRESSING.md §3.2).
        if (plcData.has("programs")) {
            JsonArray programs = plcData.getAsJsonArray("programs");
            if (programs.size() > 0) {
                String programsLabel = policy.programsBrowseFolder();
                UaFolderNode programsParentFolder = context.createFolder(programsLabel, programsLabel);
                nodeAdder.accept(programsParentFolder);
                rootNode.addOrganizes(programsParentFolder);

                for (JsonElement programElement : programs) {
                    JsonObject program = programElement.getAsJsonObject();
                    String programName = program.get("name").getAsString();

                    UaFolderNode programFolder = context.createFolder(
                        programsLabel + "/" + programName,
                        programName
                    );
                    nodeAdder.accept(programFolder);
                    programsParentFolder.addOrganizes(programFolder);

                    if (program.has("tags")) {
                        JsonArray programTags = program.getAsJsonArray("tags");
                        String scopePrefix = policy.programScopePrefix(programName);
                        for (JsonElement tagElement : programTags) {
                            JsonObject tag = tagElement.getAsJsonObject();
                            if (!tryAddTag(tag, programFolder, context, scopePrefix)) {
                                skippedTags++;
                            }
                        }

                        logger.info("Created program folder {} with {} tags", programName, programTags.size());
                    }
                }

                logger.info("Created {} browse folder with {} program(s)", programsLabel, programs.size());
            }
        }

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
        String scopePrefix) {

        String tagName = tag.has("name") ? tag.get("name").getAsString() : "<unnamed>";
        try {
            addTag(tag, parentFolder, context, scopePrefix);
            return true;
        } catch (RuntimeException e) {
            logger.warn("Skipping tag '{}' under scope '{}' - failed to add to address space: {}",
                tagName, scopePrefix, e.toString(), e);
            return false;
        }
    }

    /**
     * Adds a top-level tag to the address space. Dispatches on the parsed-model shape: an array
     * tag expands to its elements (C2/C3), a UDT/AOI/predefined instance becomes an Object node
     * with member children, and an atomic tag becomes a single variable node — all rooted at the
     * scope's canonical identifier prefix.
     */
    private void addTag(
        JsonObject tag,
        UaFolderNode parentFolder,
        NodeContext context,
        String scopePrefix) {

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
        String baseId = policy.join(scopePrefix, tagName);

        boolean isUdt = hasMembers(tag);
        boolean isArray = tag.has("isArray") && tag.get("isArray").getAsBoolean() && tag.has("dimensions");

        if (isArray) {
            int[] dims = policy.parseDimensions(tag.get("dimensions").getAsString());
            if (dims.length > 0) {
                addArray(tag, tagName, dataType, baseId, dims, isUdt, parentFolder, context);
                return;
            }
            logger.warn("Could not parse array dimensions '{}' for tag '{}' - creating a scalar node",
                tag.get("dimensions").getAsString(), tagName);
        }

        if (isUdt) {
            if (isBaseStringType(dataType)) {
                // ADDRESSING.md §3.10 (FIX-5): a STRING is exposed BOTH as a scalar String value
                // at its own node AND with browsable .LEN/.DATA members - unlike an ordinary
                // UDT/AOI instance, which is Object-node-only. RockwellBuiltInTypes registers a
                // "STRING" entry so the parser expands udt_members (LEN, DATA) for it same as any
                // other UDT; here that dispatches to a variable node (not an Object node) so the
                // parent keeps its scalar value, with the members attached as components.
                UaVariableNode stringVar = createLeafVariable(baseId, tagName, dataType, tag, context);
                parentFolder.addOrganizes(stringVar);
                addMembers(tag.getAsJsonArray("udt_members"), stringVar::addComponent, baseId, context);
                logger.debug("Created STRING instance '{}' (NodeId={}) with LEN/DATA members", tagName, baseId);
                return;
            }
            UaObjectNode udtObject = context.createObjectNode(baseId, tagName);
            nodeAdder.accept(udtObject);
            parentFolder.addComponent(udtObject);
            addMembers(tag.getAsJsonArray("udt_members"), udtObject::addComponent, baseId, context);
            logger.debug("Created UDT instance '{}' (NodeId={})", tagName, baseId);
            return;
        }

        UaVariableNode variable = createLeafVariable(baseId, tagName, dataType, tag, context);
        parentFolder.addOrganizes(variable);
        logger.debug("Created atomic variable '{}' (NodeId={}, Type={})", tagName, baseId, dataType);
    }

    /**
     * Expands an array tag (ADDRESSING.md §3.4-§3.8). A 1-D BOOL array is DWORD-packed into
     * {@code base[word].bit} bit nodes (C3); every other array expands to one node per element,
     * with the element being an Object node (array of UDT/predefined, C2) or an atomic variable.
     */
    private void addArray(
        JsonObject tag,
        String tagName,
        String dataType,
        String baseId,
        int[] dims,
        boolean isUdt,
        UaFolderNode parentFolder,
        NodeContext context) {

        if (policy.isBoolType(dataType) && !isUdt && dims.length == 1) {
            addBoolArray(baseId, tagName, dims[0], context, parentFolder::addOrganizes);
            logger.debug("Created DWORD-packed BOOL array '{}' ({} bits)", tagName, dims[0]);
            return;
        }

        List<int[]> indexTuples = policy.enumerateIndices(dims);
        for (int[] indices : indexTuples) {
            String elemId = policy.arrayElement(baseId, indices);
            String elemName = tagName + bracket(indices);
            if (isUdt) {
                UaObjectNode elemObject = context.createObjectNode(elemId, elemName);
                nodeAdder.accept(elemObject);
                parentFolder.addComponent(elemObject);
                addMembers(tag.getAsJsonArray("udt_members"), elemObject::addComponent, elemId, context);
            } else {
                UaVariableNode variable = createLeafVariable(elemId, elemName, dataType, tag, context);
                parentFolder.addOrganizes(variable);
            }
        }
        logger.debug("Created array '{}' with {} element(s)", tagName, indexTuples.size());
    }

    /**
     * Adds every member of a UDT/AOI/predefined instance, attaching each created member node via
     * {@code attacher} (the parent's {@code addComponent} - an Object node for an ordinary
     * UDT/AOI instance, or a Variable node for the STRING hybrid case, FIX-5).
     */
    private void addMembers(
        JsonArray members, Consumer<UaNode> attacher, String parentId, NodeContext context) {
        if (members == null) {
            return;
        }
        for (JsonElement memberElement : members) {
            addUdtMember(memberElement.getAsJsonObject(), attacher, parentId, context);
        }
    }

    /**
     * Adds a single UDT member, attached to its parent via {@code attacher}. Handles nested UDTs
     * (including the STRING hybrid case, FIX-5), member arrays (C2, including DWORD-packed BOOL
     * member arrays — C3) and atomic members.
     */
    private void addUdtMember(
        JsonObject member,
        Consumer<UaNode> attacher,
        String parentId,
        NodeContext context) {

        if (member.get("name") == null) {
            logger.warn("Skipping UDT member without 'name' field under '{}'", parentId);
            return;
        }
        if (member.get("data_type") == null) {
            logger.warn("Skipping UDT member '{}' without 'data_type' field under '{}'",
                member.get("name").getAsString(), parentId);
            return;
        }

        String memberName = member.get("name").getAsString();
        String memberType = member.get("data_type").getAsString();
        String memberBaseId = policy.join(parentId, memberName);
        boolean memberIsUdt = hasMembers(member);

        if (member.has("dimensions")) {
            int[] dims = policy.parseDimensions(member.get("dimensions").getAsString());
            if (dims.length > 0) {
                if (policy.isBoolType(memberType) && !memberIsUdt && dims.length == 1) {
                    addBoolArray(memberBaseId, memberName, dims[0], context, attacher::accept);
                    return;
                }
                for (int[] indices : policy.enumerateIndices(dims)) {
                    String elemId = policy.arrayElement(memberBaseId, indices);
                    String elemName = memberName + bracket(indices);
                    if (memberIsUdt) {
                        UaObjectNode elemObject = context.createObjectNode(elemId, elemName);
                        nodeAdder.accept(elemObject);
                        attacher.accept(elemObject);
                        addMembers(member.getAsJsonArray("udt_members"), elemObject::addComponent, elemId, context);
                    } else {
                        UaVariableNode variable = createLeafVariable(elemId, elemName, memberType, member, context);
                        attacher.accept(variable);
                    }
                }
                return;
            }
        }

        if (memberIsUdt) {
            if (isBaseStringType(memberType)) {
                // ADDRESSING.md §3.10 (FIX-5): same STRING hybrid as the top-level case - a
                // nested STRING member keeps its own scalar value alongside .LEN/.DATA.
                UaVariableNode stringVar = createLeafVariable(memberBaseId, memberName, memberType, member, context);
                attacher.accept(stringVar);
                addMembers(member.getAsJsonArray("udt_members"), stringVar::addComponent, memberBaseId, context);
                logger.debug("Created STRING member '{}' (NodeId={}) with LEN/DATA members", memberName, memberBaseId);
                return;
            }
            UaObjectNode nestedObject = context.createObjectNode(memberBaseId, memberName);
            nodeAdder.accept(nestedObject);
            attacher.accept(nestedObject);
            addMembers(member.getAsJsonArray("udt_members"), nestedObject::addComponent, memberBaseId, context);
            logger.debug("Created nested UDT member '{}' (NodeId={})", memberName, memberBaseId);
            return;
        }

        UaVariableNode variable = createLeafVariable(memberBaseId, memberName, memberType, member, context);
        attacher.accept(variable);
        logger.trace("Created UDT member '{}' (NodeId={}, Type={})", memberName, memberBaseId, memberType);
    }

    /**
     * @return {@code true} if {@code dataType} is the base Rockwell {@code STRING} type
     *     (ADDRESSING.md §3.10, FIX-5) - which, unlike an ordinary UDT/AOI instance, must keep a
     *     scalar String value at its own node in addition to its {@code .LEN}/{@code .DATA}
     *     members. Custom {@code STRING_n} types are NOT included here - the corpus shows them as
     *     ordinary user-defined {@code <DataType>}s with no confirmed dual-value requirement of
     *     their own, and this predicate stays scoped to what §3.10 DOC-CONFIRMS.
     *     Package-private so {@code IncrementalAddressSpaceUpdater} keys the hot-reload diff on
     *     the same node shape (FIX-4).
     */
    static boolean isBaseStringType(String dataType) {
        return "STRING".equalsIgnoreCase(dataType);
    }

    /**
     * Emits the packed bit nodes for a BOOL array (ADDRESSING.md §3.8). The packing rule (for
     * Rockwell: element {@code N} becomes {@code base[N/32].(N%32)}) lives entirely in the policy.
     * Only these bit nodes are created — the bare {@code base[N]} element node deliberately does
     * not exist, matching the real driver (a read of it fails "node does not exist"). Bits above
     * the declared element count are not emitted even though they physically occupy the final word.
     *
     * @param attacher attaches a created bit node to its browse parent (folder or object)
     */
    private void addBoolArray(
        String baseId,
        String displayBase,
        int elementCount,
        NodeContext context,
        Consumer<UaVariableNode> attacher) {

        for (int n = 0; n < elementCount; n++) {
            String bitId = policy.boolArrayBit(baseId, n);
            // Browse name mirrors the identifier's packed suffix (cosmetic).
            String bitName = displayBase + bitId.substring(baseId.length());

            UaVariableNode bitNode = context.createVariableNode(
                bitId, bitName, OpcUaDataType.Boolean.getNodeId());
            bitNode.setValue(new DataValue(new Variant(false)));
            enableWrites(bitNode);
            nodeAdder.accept(bitNode);
            attacher.accept(bitNode);
        }
    }

    /**
     * Creates an atomic variable node with its initial value and a write filter, and registers it
     * with the node manager. The caller attaches it to its browse parent.
     */
    private UaVariableNode createLeafVariable(
        String nodeIdPath,
        String browseName,
        String dataType,
        JsonObject valueSource,
        NodeContext context) {

        OpcUaDataType opcType = mapDataType(dataType);
        Object initialValue = getInitialValue(valueSource, dataType);
        boolean readOnly = isReadOnly(valueSource);

        UaVariableNode variableNode =
            context.createVariableNode(nodeIdPath, browseName, opcType.getNodeId(), readOnly);
        variableNode.setValue(new DataValue(new Variant(initialValue)));
        if (!readOnly) {
            enableWrites(variableNode);
        }
        nodeAdder.accept(variableNode);
        return variableNode;
    }

    /**
     * @return {@code true} if the parsed tag/member is marked read-only (ADDRESSING.md §3.12:
     *     {@code ExternalAccess="Read Only"}, or a {@code Constant="true"} tag) - the node is
     *     then created with {@code AccessLevel.READ_ONLY} and no write filter (C5a).
     */
    private static boolean isReadOnly(JsonObject valueSource) {
        return valueSource.has("read_only") && valueSource.get("read_only").getAsBoolean();
    }

    /** Browse-name subscript for an array element, e.g. {@code [0]} or {@code [1,3]}. */
    private static String bracket(int[] indices) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < indices.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(indices[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * @return {@code true} if the JSON tag/member carries a non-empty {@code udt_members} array.
     *     Package-private so {@code IncrementalAddressSpaceUpdater} applies the identical
     *     structure test when expanding the hot-reload diff (FIX-4).
     */
    static boolean hasMembers(JsonObject node) {
        return node.has("udt_members") && node.getAsJsonArray("udt_members").size() > 0;
    }

    /**
     * Enable write operations for a variable node: the filter delegates the incoming value down
     * the chain to the node's backing storage via {@code ctx.setAttribute}.
     *
     * <p>Deliberately does NOT call {@code variableNode.setValue()} inside the callback — in Milo,
     * {@code setValue} re-enters the full attribute filter chain (verified against milo-sdk-server
     * 1.0.5 bytecode), so a filter that calls it recurses without bound. The pre-v10 twin-node
     * scheme masked this on engine-written nodes because those carried the re-entrancy-guarded
     * synchronised-write filter instead; with one canonical node per tag (C1) the plain
     * pass-through is both sufficient and safe.</p>
     */
    private void enableWrites(UaVariableNode variableNode) {
        variableNode.getFilterChain().addLast(
            AttributeFilters.setValue(
                (ctx, value) -> ctx.setAttribute(AttributeId.Value, value)
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
            case "USINT" -> OpcUaDataType.Byte;
            case "INT2", "INT" -> OpcUaDataType.Int16;
            case "UINT", "WORD" -> OpcUaDataType.UInt16;
            case "INT4", "DINT" -> OpcUaDataType.Int32;
            case "UDINT", "DWORD" -> OpcUaDataType.UInt32;
            case "INT8", "LINT" -> OpcUaDataType.Int64;
            case "ULINT", "LWORD" -> OpcUaDataType.UInt64;
            // DT/LDT/LTIME/TIME have no dedicated Logix->OPC-UA presentation confirmed by a live
            // driver diff; Int64 (epoch/duration) is ADDRESSING.md §3.14's documented safe
            // default (INFERRED, C6) until a bench diff is available.
            case "DT", "LDT", "LTIME", "TIME" -> OpcUaDataType.Int64;
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
                case "USINT" -> Unsigned.ubyte(initialValueElement.getAsInt());
                case "UINT", "WORD" -> Unsigned.ushort(initialValueElement.getAsInt());
                case "INT4", "DINT" -> initialValueElement.getAsInt();
                case "UDINT", "DWORD" -> Unsigned.uint(initialValueElement.getAsLong());
                case "INT8", "LINT", "DT", "LDT", "LTIME", "TIME" -> initialValueElement.getAsLong();
                // ULINT/LWORD can in principle exceed Long.MAX_VALUE; getAsLong() truncates rather
                // than throwing for such pathological literals - accepted as a conservative
                // limitation (ADDRESSING.md §3.14 is INFERRED for this type; no corpus example
                // approaches the boundary).
                case "ULINT", "LWORD" -> Unsigned.ulong(initialValueElement.getAsLong());
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
            case "USINT" -> Unsigned.ubyte(0);
            case "UINT", "WORD" -> Unsigned.ushort(0);
            case "UDINT", "DWORD" -> Unsigned.uint(0);
            case "INT8", "LINT", "DT", "LDT", "LTIME", "TIME" -> 0L;
            case "ULINT", "LWORD" -> Unsigned.ulong(0L);
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
         * Create a read-write variable node. Override in tests to return a mock.
         */
        protected UaVariableNode createVariableNode(String nodeIdPath, String name, NodeId dataType) {
            return createVariableNode(nodeIdPath, name, dataType, false);
        }

        /**
         * Create a variable node with the given access level (ADDRESSING.md §3.12, C5a: a
         * {@code readOnly} node gets {@code AccessLevel.READ_ONLY} and no write filter - see
         * {@code AddressSpaceBuilder.createLeafVariable}). Override in tests to return a mock.
         */
        protected UaVariableNode createVariableNode(
            String nodeIdPath, String name, NodeId dataType, boolean readOnly) {
            var accessLevel = readOnly ? AccessLevel.READ_ONLY : AccessLevel.READ_WRITE;
            return UaVariableNode.build(nodeContext, b ->
                b.setNodeId(nodeId(nodeIdPath))
                    .setBrowseName(qualifiedName(name))
                    .setDisplayName(new LocalizedText(name))
                    .setDataType(dataType)
                    .setTypeDefinition(NodeIds.BaseDataVariableType)
                    .setAccessLevel(accessLevel)
                    .setUserAccessLevel(accessLevel)
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
