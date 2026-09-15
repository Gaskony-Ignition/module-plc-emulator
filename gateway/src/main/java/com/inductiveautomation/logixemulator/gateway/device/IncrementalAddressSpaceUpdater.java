package com.inductiveautomation.logixemulator.gateway.device;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.inductiveautomation.logixemulator.gateway.address.AddressPolicy;
import com.inductiveautomation.logixemulator.gateway.address.RockwellLogixPolicy;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Performs incremental updates to OPC-UA address space when PLC files change.
 * Instead of rebuilding the entire address space, this class:
 * 1. Compares old and new parsed data
 * 2. Updates only changed tag values
 * 3. Adds new tags
 * 4. Marks removed tags (but doesn't delete them to avoid subscription issues)
 *
 * This significantly reduces OPC-UA disconnection time during hot-reload.
 */
public class IncrementalAddressSpaceUpdater {

    private static final Logger logger = LoggerFactory.getLogger(IncrementalAddressSpaceUpdater.class);

    private final Function<NodeId, UaNode> nodeLookup;
    private final Function<String, NodeId> nodeIdFactory;
    private final String deviceName;
    private final AddressPolicy policy;

    /**
     * Result of comparing two PLC data structures.
     */
    public static class CompareResult {
        public final Map<String, TagChange> changedTags = new HashMap<>();
        public final Set<String> newTags = new HashSet<>();
        public final Set<String> removedTags = new HashSet<>();
        public final int unchangedCount;

        public CompareResult(int unchangedCount) {
            this.unchangedCount = unchangedCount;
        }

        public boolean hasChanges() {
            return !changedTags.isEmpty() || !newTags.isEmpty() || !removedTags.isEmpty();
        }

        public boolean requiresFullRebuild() {
            // Full rebuild needed if structure changes (tags added/removed)
            return !newTags.isEmpty() || !removedTags.isEmpty();
        }
    }

    public static class TagChange {
        public final String tagPath;
        public final String oldValue;
        public final String newValue;
        public final String dataType;

        public TagChange(String tagPath, String oldValue, String newValue, String dataType) {
            this.tagPath = tagPath;
            this.oldValue = oldValue;
            this.newValue = newValue;
            this.dataType = dataType;
        }
    }

    public IncrementalAddressSpaceUpdater(
            Function<NodeId, UaNode> nodeLookup,
            Function<String, NodeId> nodeIdFactory,
            String deviceName) {
        this(nodeLookup, nodeIdFactory, deviceName, new RockwellLogixPolicy());
    }

    public IncrementalAddressSpaceUpdater(
            Function<NodeId, UaNode> nodeLookup,
            Function<String, NodeId> nodeIdFactory,
            String deviceName,
            AddressPolicy policy) {
        this.nodeLookup = nodeLookup;
        this.nodeIdFactory = nodeIdFactory;
        this.deviceName = deviceName;
        this.policy = policy;
    }

    /**
     * Compare old and new PLC data to determine what changed.
     */
    public CompareResult compare(JsonObject oldData, JsonObject newData) {
        Map<String, JsonObject> oldTags = extractAllTags(oldData);
        Map<String, JsonObject> newTags = extractAllTags(newData);

        int unchangedCount = 0;

        // Find changed and removed tags
        for (Map.Entry<String, JsonObject> entry : oldTags.entrySet()) {
            String tagPath = entry.getKey();
            JsonObject oldTag = entry.getValue();

            if (!newTags.containsKey(tagPath)) {
                // tag was removed
            } else {
                JsonObject newTag = newTags.get(tagPath);
                TagChange change = compareTag(tagPath, oldTag, newTag);
                if (change == null) {
                    unchangedCount++;
                }
            }
        }

        CompareResult result = new CompareResult(unchangedCount);

        // Re-iterate to populate result (unchangedCount must be final for CompareResult constructor)
        for (Map.Entry<String, JsonObject> entry : oldTags.entrySet()) {
            String tagPath = entry.getKey();
            JsonObject oldTag = entry.getValue();

            if (!newTags.containsKey(tagPath)) {
                result.removedTags.add(tagPath);
            } else {
                JsonObject newTag = newTags.get(tagPath);
                TagChange change = compareTag(tagPath, oldTag, newTag);
                if (change != null) {
                    result.changedTags.put(tagPath, change);
                }
            }
        }

        // Find new tags
        for (String tagPath : newTags.keySet()) {
            if (!oldTags.containsKey(tagPath)) {
                result.newTags.add(tagPath);
            }
        }

        logger.info("Compare result: {} changed, {} new, {} removed, {} unchanged",
            result.changedTags.size(), result.newTags.size(), result.removedTags.size(),
            result.unchangedCount);

        return result;
    }

    /**
     * Apply incremental updates to existing nodes.
     * Only updates values of existing tags - doesn't add/remove nodes.
     *
     * @return {@code true} if every changed tag was applied to a real variable node; {@code false}
     *     if any update failed. A {@code false} return means the address space no longer matches
     *     the parsed data, so the caller MUST fall back to a full rebuild rather than report the
     *     reload as successful (FIX-4 - previously failures were only counted and logged, and the
     *     upload path claimed success regardless).
     */
    public boolean applyIncrementalUpdate(CompareResult changes) {
        if (!changes.hasChanges()) {
            logger.debug("No changes to apply");
            return true;
        }

        int updated = 0;
        int failed = 0;

        for (TagChange change : changes.changedTags.values()) {
            try {
                // Tag paths from extractAllTags ARE the canonical NodeId identifiers (v10
                // C1/C2/C3): controller tags/members are bare ("TagName", "UDT.Member", "Arr[0]",
                // "Bits[0].5"); program tags carry the Program:<Prog> selector
                // ("Program:MainProgram.TagName"). No further prefixing.
                String nodeIdPath = change.tagPath;
                NodeId nodeId = nodeIdFactory.apply(nodeIdPath);
                UaNode node = nodeLookup.apply(nodeId);

                if (node instanceof UaVariableNode varNode) {
                    Object newValue = parseValue(change.newValue, change.dataType);
                    varNode.setValue(new DataValue(new Variant(newValue)));
                    updated++;
                    logger.trace("Updated tag value: {} = {}", change.tagPath, change.newValue);
                } else {
                    logger.warn("Node not found or not a variable: {}", nodeIdPath);
                    failed++;
                }
            } catch (Exception e) {
                logger.warn("Failed to update tag: {}", change.tagPath, e);
                failed++;
            }
        }

        logger.info("Incremental update complete: {} updated, {} failed", updated, failed);
        return failed == 0;
    }

    /**
     * Check if changes can be applied incrementally (values only) or need full rebuild.
     */
    public boolean canApplyIncrementally(CompareResult changes) {
        // Can apply incrementally only if no structural changes
        if (!changes.newTags.isEmpty()) {
            logger.info("Full rebuild required: {} new tags detected", changes.newTags.size());
            return false;
        }
        if (!changes.removedTags.isEmpty()) {
            logger.info("Full rebuild required: {} removed tags detected", changes.removedTags.size());
            return false;
        }
        // Only value changes - can update incrementally
        return true;
    }

    /**
     * Flattens the parsed PLC data into a map of canonical NodeId identifier to the JSON
     * tag/member object that carries its value - the SAME expanded identifiers the address space
     * actually contains post-C2/C3 (FIX-4). Expansion mirrors {@code AddressSpaceBuilder}'s
     * node-creation dispatch exactly, driven through the shared {@link AddressPolicy}:
     * <ul>
     *   <li>arrays expand to {@code Tag[i]}/{@code Tag[i,j]} element identifiers - the bare array
     *       base identifier has NO node and is never used as a key;</li>
     *   <li>1-D BOOL arrays expand to DWORD-packed {@code Tag[word].bit} identifiers (C3);</li>
     *   <li>UDT/AOI members recurse to arbitrary depth ({@code Tag.Inner.Val}) - previously only
     *       one member level was visited, so a depth-2 change produced no diff at all;</li>
     *   <li>UDT parent Object nodes carry no value and are not keyed; the base-STRING hybrid
     *       parent (FIX-5) IS keyed because it is a value-carrying variable node.</li>
     * </ul>
     */
    private Map<String, JsonObject> extractAllTags(JsonObject plcData) {
        Map<String, JsonObject> tags = new HashMap<>();

        // Controller-scoped (global) tags - bare canonical identifiers (v10 C1).
        if (plcData.has("global_tags")) {
            JsonArray globalTags = plcData.getAsJsonArray("global_tags");
            for (JsonElement elem : globalTags) {
                collectTag(tags, policy.controllerScopePrefix(), elem.getAsJsonObject());
            }
        }

        // Program-scoped tags - Program:<Prog>.TagName canonical identifiers (v10 C1).
        if (plcData.has("programs")) {
            JsonArray programs = plcData.getAsJsonArray("programs");
            for (JsonElement progElem : programs) {
                JsonObject program = progElem.getAsJsonObject();
                String programName = program.has("name") ? program.get("name").getAsString() : "Unknown";

                if (program.has("tags")) {
                    String scopePrefix = policy.programScopePrefix(programName);
                    for (JsonElement tagElem : program.getAsJsonArray("tags")) {
                        collectTag(tags, scopePrefix, tagElem.getAsJsonObject());
                    }
                }
            }
        }

        return tags;
    }

    /**
     * Collects a single top-level tag, mirroring {@code AddressSpaceBuilder.addTag}'s dispatch:
     * a tag is an array only when it carries BOTH {@code isArray} and {@code dimensions} (a
     * member needs only {@code dimensions}), and tags without a name or data type create no
     * nodes so they contribute no keys.
     */
    private void collectTag(Map<String, JsonObject> out, String scopePrefix, JsonObject tag) {
        if (tag.get("name") == null || tag.get("data_type") == null) {
            return;
        }
        String baseId = policy.join(scopePrefix, tag.get("name").getAsString());
        String dataType = tag.get("data_type").getAsString();

        boolean isArray = tag.has("isArray") && tag.get("isArray").getAsBoolean() && tag.has("dimensions");
        int[] dims = isArray
            ? policy.parseDimensions(tag.get("dimensions").getAsString())
            : new int[0];

        collectExpanded(out, baseId, dataType, tag, dims);
    }

    /**
     * Expands one tag/member into its canonical value-node identifiers, recursing through
     * members at arbitrary depth. {@code dims} is empty for a scalar.
     */
    private void collectExpanded(
            Map<String, JsonObject> out, String baseId, String dataType, JsonObject source, int[] dims) {
        boolean isUdt = AddressSpaceBuilder.hasMembers(source);

        if (dims.length > 0) {
            if (policy.isBoolType(dataType) && !isUdt && dims.length == 1) {
                // DWORD-packed BOOL array (C3): only Tag[word].bit nodes exist. Each bit is keyed
                // to its own element-value source (FIX-15) so a single changed bit produces a
                // diff, rather than every bit sharing the same (valueless) array-tag object.
                for (int n = 0; n < dims[0]; n++) {
                    JsonObject elementSource =
                        AddressSpaceBuilder.arrayElementSource(source, dataType, "[" + n + "]");
                    out.put(policy.boolArrayBit(baseId, n), elementSource);
                }
                return;
            }
            for (int[] indices : policy.enumerateIndices(dims)) {
                String elemId = policy.arrayElement(baseId, indices);
                if (isUdt) {
                    // Array-of-struct element: an Object node (no value) with member children.
                    collectMembers(out, elemId, source);
                } else {
                    // FIX-15: key each element to its own decoded value (source.element_values,
                    // populated by L5XParser for a top-level array) when present, so a
                    // value-only change to a SINGLE element produces a diff - previously every
                    // element shared the same array-tag object (which never carried a per-element
                    // value at all), so compare() could never see an array-element change.
                    String bracketIndex = AddressSpaceBuilder.bracket(indices);
                    JsonObject elementSource =
                        AddressSpaceBuilder.arrayElementSource(source, dataType, bracketIndex);
                    out.put(elemId, elementSource);
                }
            }
            return;
        }

        if (isUdt) {
            if (AddressSpaceBuilder.isBaseStringType(dataType)) {
                // FIX-5 hybrid: the STRING parent is a value-carrying variable node.
                out.put(baseId, source);
            }
            collectMembers(out, baseId, source);
            return;
        }

        out.put(baseId, source);
    }

    /** Recurses into a UDT/AOI instance's members (arbitrary depth - FIX-4). */
    private void collectMembers(Map<String, JsonObject> out, String parentId, JsonObject udtNode) {
        for (JsonElement memberElem : udtNode.getAsJsonArray("udt_members")) {
            JsonObject member = memberElem.getAsJsonObject();
            if (member.get("name") == null || member.get("data_type") == null) {
                continue;
            }
            String memberId = policy.join(parentId, member.get("name").getAsString());
            String memberType = member.get("data_type").getAsString();
            int[] dims = member.has("dimensions")
                ? policy.parseDimensions(member.get("dimensions").getAsString())
                : new int[0];
            collectExpanded(out, memberId, memberType, member, dims);
        }
    }

    private TagChange compareTag(String tagPath, JsonObject oldTag, JsonObject newTag) {
        String oldValue = getTagValue(oldTag);
        String newValue = getTagValue(newTag);
        String dataType = getDataType(newTag);

        if (!oldValue.equals(newValue)) {
            return new TagChange(tagPath, oldValue, newValue, dataType);
        }

        return null; // No change
    }

    private String getTagValue(JsonObject tag) {
        JsonElement value = null;
        if (tag.has("value")) {
            value = tag.get("value");
        } else if (tag.has("initial_value")) {
            value = tag.get("initial_value");
        }
        if (value == null || value.isJsonNull()) {
            return "";
        }
        // A primitive's bare string form ("2.5", not the JSON-encoded "\"2.5\"") - the compare is
        // symmetric either way, but parseValue() must receive an unquoted literal (FIX-11).
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private String getDataType(JsonObject tag) {
        if (tag.has("data_type")) {
            return tag.get("data_type").getAsString();
        }
        return "STRING";
    }

    /**
     * Coerces a changed value to the Java type the target node stores, via the builder's own
     * type mapping (FIX-11 - previously a local 5-type switch whose default handed
     * {@code new Variant("2.5")}, a String, to LINT/LREAL/unsigned/time-typed nodes).
     *
     * <p>Deliberately does NOT swallow parse failures: an unparseable value propagates to
     * {@link #applyIncrementalUpdate}'s per-change catch, counts as a failed change, and so
     * triggers the coordinator's full-rebuild fallback (FIX-4) instead of corrupting the node.
     */
    private Object parseValue(String value, String dataType) {
        return AddressSpaceBuilder.coerceValueForType(new JsonPrimitive(value), dataType);
    }
}
