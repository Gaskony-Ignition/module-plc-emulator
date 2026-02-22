package com.inductiveautomation.logixemulator.gateway.web;

import com.google.gson.JsonObject;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and navigates a tag tree structure from parsed PLC data.
 * Supports lazy loading and pagination for efficient UI rendering.
 *
 * The tree is organized as:
 * - Controller:Global/ (folder containing global tags)
 * - Programs/ (folder containing program sub-folders)
 *   - Programs/MainProgram/ (each program with its tags)
 *
 * UDT instances are represented as folders containing their member tags.
 */
public class TagTreeBuilder {

    private final Map<String, List<JSONObject>> childrenByPath = new HashMap<>();
    private final Map<String, JSONObject> nodesByPath = new HashMap<>();
    private final TagStats stats = new TagStats();

    public static class TagStats {
        public int totalTags = 0;
        public int folderCount = 0;
        public int udtCount = 0;
    }

    public TagTreeBuilder(JsonObject parsedData) throws JSONException {
        // Build index of all tags organized by parent path
        if (parsedData.has("global_tags")) {
            var globalTags = parsedData.getAsJsonArray("global_tags");

            // Add Controller:Global as a root folder
            addFolderNode("", "Controller:Global", "Folder", false);
            stats.folderCount++;

            for (var elem : globalTags) {
                var tag = elem.getAsJsonObject();
                indexTag(tag, "Controller:Global");
            }
        }

        if (parsedData.has("programs")) {
            var programs = parsedData.getAsJsonArray("programs");

            // Add Programs as a root folder if there are programs
            if (programs.size() > 0) {
                addFolderNode("", "Programs", "Folder", false);
                stats.folderCount++;
            }

            for (var progElem : programs) {
                var prog = progElem.getAsJsonObject();
                String progName = prog.has("name") ? prog.get("name").getAsString() : "Program";

                // Add program as a folder under Programs
                addFolderNode("Programs", progName, "Program", false);
                stats.folderCount++;

                if (prog.has("tags")) {
                    var progTags = prog.getAsJsonArray("tags");
                    for (var tagElem : progTags) {
                        var tag = tagElem.getAsJsonObject();
                        indexTag(tag, "Programs/" + progName);
                    }
                }
            }
        }

        // Sort all child lists once after indexing is complete
        sortAllChildLists();
    }

    /**
     * Sort all child lists once during construction: folders first, then alphabetically by name.
     * This avoids mutating shared lists on every call to getChildrenOf().
     */
    private void sortAllChildLists() {
        for (List<JSONObject> children : childrenByPath.values()) {
            children.sort((a, b) -> {
                try {
                    boolean aFolder = a.optBoolean("isFolder", false);
                    boolean bFolder = b.optBoolean("isFolder", false);
                    if (aFolder != bFolder) {
                        return aFolder ? -1 : 1;
                    }
                    return a.optString("name", "").compareToIgnoreCase(b.optString("name", ""));
                } catch (Exception e) {
                    return 0;
                }
            });
        }
    }

    private void indexTag(JsonObject tag, String parentPath) throws JSONException {
        String tagName = tag.has("name") ? tag.get("name").getAsString() : "unknown";
        String dataType = tag.has("data_type") ? tag.get("data_type").getAsString() : "STRING";
        String tagPath = parentPath + "/" + tagName;

        // Check if this is a UDT instance with members
        if (tag.has("udt_members")) {
            var members = tag.getAsJsonArray("udt_members");
            if (members.size() > 0) {
                stats.udtCount++;

                // Add UDT instance as a folder
                addFolderNode(parentPath, tagName, dataType, true);
                stats.folderCount++;

                // Recursively index members
                for (var memberElem : members) {
                    var member = memberElem.getAsJsonObject();
                    indexTag(member, tagPath);
                }
                return;
            }
        }

        // Atomic tag - add as leaf
        addLeafNode(parentPath, tagName, dataType,
            tag.has("initial_value") ? tag.get("initial_value").toString() : "", tagPath);
        stats.totalTags++;
    }

    private void addFolderNode(String parentPath, String name, String dataType, boolean isUdt) throws JSONException {
        String path = parentPath.isEmpty() ? name : parentPath + "/" + name;

        JSONObject node = new JSONObject();
        node.put("name", name);
        node.put("path", path);
        node.put("data_type", dataType);
        node.put("isFolder", true);
        node.put("isUdt", isUdt);

        childrenByPath.computeIfAbsent(parentPath, k -> new ArrayList<>()).add(node);
        nodesByPath.put(path, node);
    }

    private void addLeafNode(String parentPath, String name, String dataType, String value, String path) throws JSONException {
        JSONObject node = new JSONObject();
        node.put("name", name);
        node.put("path", path);
        node.put("data_type", dataType);
        node.put("value", value);
        node.put("isFolder", false);

        childrenByPath.computeIfAbsent(parentPath, k -> new ArrayList<>()).add(node);
        nodesByPath.put(path, node);
    }

    public TagStats getStats() {
        return stats;
    }

    public int getChildCount(String parentPath) {
        List<JSONObject> children = childrenByPath.get(parentPath);
        return children != null ? children.size() : 0;
    }

    /**
     * Get children of a path with pagination.
     * @param parentPath Parent path (empty string for root)
     * @param depth How many levels to include (1 = direct children only)
     * @param offset Pagination offset
     * @param limit Max items to return
     */
    public JSONArray getChildrenOf(String parentPath, int depth, int offset, int limit) throws JSONException {
        JSONArray result = new JSONArray();
        List<JSONObject> children = childrenByPath.get(parentPath);

        if (children == null || children.isEmpty()) {
            return result;
        }

        // Apply pagination
        int end = Math.min(offset + limit, children.size());
        for (int i = offset; i < end; i++) {
            JSONObject child = children.get(i);
            JSONObject copy = new JSONObject(child.toString());

            // Add child count for folders (for UI to show expand arrow)
            if (child.optBoolean("isFolder", false)) {
                String childPath = child.optString("path", "");
                int childCount = getChildCount(childPath);
                copy.put("childCount", childCount);
                copy.put("hasChildren", childCount > 0);

                // If depth > 1, include nested children
                if (depth > 1 || depth == -1) {
                    JSONArray nested = getChildrenOf(childPath, depth == -1 ? -1 : depth - 1, 0, 500);
                    if (nested.length() > 0) {
                        copy.put("children", nested);
                    }
                }
            }

            result.put(copy);
        }

        return result;
    }

    /**
     * Get flattened list of all visible tags for virtual scrolling.
     * Only returns tags (not folders) for simpler virtual scroll implementation.
     */
    public JSONArray getFlatTags(String parentPath, int offset, int limit) throws JSONException {
        JSONArray result = new JSONArray();
        List<JSONObject> allTags = new ArrayList<>();

        // Collect all leaf tags under the given path
        collectLeafTags(parentPath.isEmpty() ? null : parentPath, allTags);

        // Sort alphabetically by path
        allTags.sort((a, b) -> a.optString("path", "").compareToIgnoreCase(b.optString("path", "")));

        // Apply pagination
        int end = Math.min(offset + limit, allTags.size());
        for (int i = offset; i < end; i++) {
            result.put(allTags.get(i));
        }

        return result;
    }

    private void collectLeafTags(String parentPath, List<JSONObject> tags) {
        for (var entry : childrenByPath.entrySet()) {
            String path = entry.getKey();
            // If parentPath is null, collect everything; otherwise filter by prefix
            if (parentPath == null || path.equals(parentPath) || path.startsWith(parentPath + "/")) {
                for (JSONObject node : entry.getValue()) {
                    if (!node.optBoolean("isFolder", false)) {
                        tags.add(node);
                    }
                }
            }
        }
    }

    public int getTotalFlatCount(String parentPath) {
        int count = 0;
        for (var entry : childrenByPath.entrySet()) {
            String path = entry.getKey();
            if (parentPath.isEmpty() || path.equals(parentPath) || path.startsWith(parentPath + "/")) {
                for (JSONObject node : entry.getValue()) {
                    if (!node.optBoolean("isFolder", false)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
