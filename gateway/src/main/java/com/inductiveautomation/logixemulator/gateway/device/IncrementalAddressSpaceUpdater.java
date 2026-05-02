package com.inductiveautomation.logixemulator.gateway.device;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
        this.nodeLookup = nodeLookup;
        this.nodeIdFactory = nodeIdFactory;
        this.deviceName = deviceName;
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
     */
    public void applyIncrementalUpdate(CompareResult changes) {
        if (!changes.hasChanges()) {
            logger.debug("No changes to apply");
            return;
        }

        int updated = 0;
        int failed = 0;

        for (TagChange change : changes.changedTags.values()) {
            try {
                // Tag paths from extractAllTags already include scope prefix:
                // Global tags: "TagName" or "UDT.Member" -> need "Controller:Global." prefix
                // Program tags: "Programs.ProgramName.TagName" -> already fully qualified
                String nodeIdPath = change.tagPath.startsWith("Programs.")
                    ? change.tagPath
                    : "Controller:Global." + change.tagPath;
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

    private Map<String, JsonObject> extractAllTags(JsonObject plcData) {
        Map<String, JsonObject> tags = new HashMap<>();

        // Extract global tags
        if (plcData.has("global_tags")) {
            JsonArray globalTags = plcData.getAsJsonArray("global_tags");
            for (JsonElement elem : globalTags) {
                JsonObject tag = elem.getAsJsonObject();
                if (tag.has("name")) {
                    String name = tag.get("name").getAsString();
                    tags.put(name, tag);

                    // Also extract UDT members
                    if (tag.has("udt_members")) {
                        JsonArray members = tag.getAsJsonArray("udt_members");
                        for (JsonElement memberElem : members) {
                            JsonObject member = memberElem.getAsJsonObject();
                            if (member.has("name")) {
                                String memberPath = name + "." + member.get("name").getAsString();
                                tags.put(memberPath, member);
                            }
                        }
                    }
                }
            }
        }

        // Extract program tags
        if (plcData.has("programs")) {
            JsonArray programs = plcData.getAsJsonArray("programs");
            for (JsonElement progElem : programs) {
                JsonObject program = progElem.getAsJsonObject();
                String programName = program.has("name") ? program.get("name").getAsString() : "Unknown";

                if (program.has("tags")) {
                    JsonArray programTags = program.getAsJsonArray("tags");
                    for (JsonElement tagElem : programTags) {
                        JsonObject tag = tagElem.getAsJsonObject();
                        if (tag.has("name")) {
                            String tagPath = "Programs." + programName + "." + tag.get("name").getAsString();
                            tags.put(tagPath, tag);
                        }
                    }
                }
            }
        }

        return tags;
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
        if (tag.has("value")) {
            return tag.get("value").toString();
        }
        if (tag.has("initial_value")) {
            return tag.get("initial_value").toString();
        }
        return "";
    }

    private String getDataType(JsonObject tag) {
        if (tag.has("data_type")) {
            return tag.get("data_type").getAsString();
        }
        return "STRING";
    }

    private Object parseValue(String value, String dataType) {
        try {
            return switch (dataType.toUpperCase()) {
                case "BOOL", "BOOLEAN" -> Boolean.parseBoolean(value);
                case "INT", "INT2", "SINT", "INT1" -> Short.parseShort(value);
                case "DINT", "INT4" -> Integer.parseInt(value);
                case "REAL", "FLOAT", "FLOAT4" -> Float.parseFloat(value);
                default -> value;
            };
        } catch (NumberFormatException e) {
            return value;
        }
    }
}
