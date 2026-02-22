package com.inductiveautomation.logixemulator.gateway.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Tests for TagTreeBuilder — covers tree construction, pagination,
 * depth-limited traversal, flat-tag enumeration, and statistics.
 */
class TagTreeBuilderTest {

    // -------------------------------------------------------------------------
    // Helper builders
    // -------------------------------------------------------------------------

    private JsonObject tag(String name, String dataType) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("data_type", dataType);
        return t;
    }

    private JsonObject tag(String name, String dataType, String value) {
        JsonObject t = tag(name, dataType);
        t.addProperty("initial_value", value);
        return t;
    }

    private JsonObject tagWithMembers(String name, String dataType, JsonObject... members) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("data_type", dataType);
        JsonArray arr = new JsonArray();
        for (JsonObject m : members) {
            arr.add(m);
        }
        t.add("udt_members", arr);
        return t;
    }

    private JsonObject program(String name, JsonObject... tags) {
        JsonObject p = new JsonObject();
        p.addProperty("name", name);
        JsonArray arr = new JsonArray();
        for (JsonObject t : tags) {
            arr.add(t);
        }
        p.add("tags", arr);
        return p;
    }

    private JsonObject plcData(JsonArray globalTags, JsonArray programs) {
        JsonObject data = new JsonObject();
        if (globalTags != null) data.add("global_tags", globalTags);
        if (programs != null)   data.add("programs", programs);
        return data;
    }

    private JsonArray arr(JsonObject... items) {
        JsonArray a = new JsonArray();
        for (JsonObject item : items) a.add(item);
        return a;
    }

    // -------------------------------------------------------------------------
    // 1. Empty parsedData → no children at root
    // -------------------------------------------------------------------------

    @Test
    void emptyData_rootHasNoChildren() throws Exception {
        TagTreeBuilder builder = new TagTreeBuilder(new JsonObject());

        assertThat(builder.getChildCount("")).isEqualTo(0);
        assertThat(builder.getChildrenOf("", 1, 0, 100).length()).isEqualTo(0);
    }

    @Test
    void emptyData_statsAreZero() throws Exception {
        TagTreeBuilder builder = new TagTreeBuilder(new JsonObject());

        TagTreeBuilder.TagStats stats = builder.getStats();
        assertThat(stats.totalTags).isEqualTo(0);
        assertThat(stats.folderCount).isEqualTo(0);
        assertThat(stats.udtCount).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // 2. Global tags only → Controller:Global folder created
    // -------------------------------------------------------------------------

    @Test
    void globalTagsOnly_controllerGlobalFolderAtRoot() throws Exception {
        JsonObject data = plcData(arr(tag("MyTag", "BOOL")), null);
        TagTreeBuilder builder = new TagTreeBuilder(data);

        assertThat(builder.getChildCount("")).isEqualTo(1);

        JSONObject rootChild = builder.getChildrenOf("", 1, 0, 10).getJSONObject(0);
        assertThat(rootChild.getString("name")).isEqualTo("Controller:Global");
        assertThat(rootChild.getBoolean("isFolder")).isTrue();
    }

    @Test
    void globalTagsOnly_folderCountAtLeastOne() throws Exception {
        JsonObject data = plcData(arr(tag("T1", "INT")), null);
        TagTreeBuilder builder = new TagTreeBuilder(data);

        assertThat(builder.getStats().folderCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void globalTagsOnly_tagAppearsUnderControllerGlobal() throws Exception {
        JsonObject data = plcData(arr(tag("Alpha", "DINT"), tag("Beta", "BOOL")), null);
        TagTreeBuilder builder = new TagTreeBuilder(data);

        assertThat(builder.getChildCount("Controller:Global")).isEqualTo(2);
    }

    @Test
    void globalTagsOnly_totalTagsCountsAtomicLeaves() throws Exception {
        JsonObject data = plcData(
            arr(tag("T1", "INT"), tag("T2", "BOOL"), tag("T3", "REAL")),
            null
        );
        TagTreeBuilder builder = new TagTreeBuilder(data);

        assertThat(builder.getStats().totalTags).isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // 3. Programs → Programs folder + program subfolder created
    // -------------------------------------------------------------------------

    @Test
    void programs_programsFolderAtRoot() throws Exception {
        JsonObject data = plcData(null, arr(program("MainProgram", tag("Counter", "INT"))));
        TagTreeBuilder builder = new TagTreeBuilder(data);

        // Root has "Programs"
        assertThat(builder.getChildCount("")).isEqualTo(1);
        JSONObject rootChild = builder.getChildrenOf("", 1, 0, 10).getJSONObject(0);
        assertThat(rootChild.getString("name")).isEqualTo("Programs");
        assertThat(rootChild.getBoolean("isFolder")).isTrue();
    }

    @Test
    void programs_programSubfolderUnderPrograms() throws Exception {
        JsonObject data = plcData(null, arr(program("MainProgram", tag("Counter", "INT"))));
        TagTreeBuilder builder = new TagTreeBuilder(data);

        assertThat(builder.getChildCount("Programs")).isEqualTo(1);
        JSONObject progFolder = builder.getChildrenOf("Programs", 1, 0, 10).getJSONObject(0);
        assertThat(progFolder.getString("name")).isEqualTo("MainProgram");
        assertThat(progFolder.getBoolean("isFolder")).isTrue();
    }

    @Test
    void programs_tagsUnderProgramSubfolder() throws Exception {
        JsonObject data = plcData(null, arr(
            program("MainProgram", tag("Counter", "INT"), tag("Enable", "BOOL"))
        ));
        TagTreeBuilder builder = new TagTreeBuilder(data);

        assertThat(builder.getChildCount("Programs/MainProgram")).isEqualTo(2);
    }

    @Test
    void programs_multiplePrograms() throws Exception {
        JsonObject data = plcData(null, arr(
            program("Prog1", tag("A", "INT")),
            program("Prog2", tag("B", "BOOL"))
        ));
        TagTreeBuilder builder = new TagTreeBuilder(data);

        assertThat(builder.getChildCount("Programs")).isEqualTo(2);
    }

    @Test
    void programs_noProgramsFolder_whenProgramsArrayEmpty() throws Exception {
        JsonObject data = new JsonObject();
        data.add("programs", new JsonArray()); // empty array
        TagTreeBuilder builder = new TagTreeBuilder(data);

        // No "Programs" folder should be created for an empty list
        assertThat(builder.getChildCount("")).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // 4. UDT members → UDT instance is a folder, members are children
    // -------------------------------------------------------------------------

    @Test
    void udtInstance_isFolder() throws Exception {
        JsonObject udt = tagWithMembers("MyUDT", "MY_UDT_TYPE", tag("Member1", "INT"));
        JsonObject data = plcData(arr(udt), null);
        TagTreeBuilder builder = new TagTreeBuilder(data);

        // Under Controller:Global we have the UDT folder
        JSONArray children = builder.getChildrenOf("Controller:Global", 1, 0, 10);
        assertThat(children.length()).isEqualTo(1);
        assertThat(children.getJSONObject(0).getBoolean("isFolder")).isTrue();
        assertThat(children.getJSONObject(0).getString("name")).isEqualTo("MyUDT");
    }

    @Test
    void udtInstance_membersAreChildren() throws Exception {
        JsonObject udt = tagWithMembers("MyUDT", "MY_UDT_TYPE",
            tag("MemberA", "INT"),
            tag("MemberB", "BOOL")
        );
        JsonObject data = plcData(arr(udt), null);
        TagTreeBuilder builder = new TagTreeBuilder(data);

        assertThat(builder.getChildCount("Controller:Global/MyUDT")).isEqualTo(2);
    }

    @Test
    void udtInstance_incrementsUdtCount() throws Exception {
        JsonObject udt = tagWithMembers("MyUDT", "MY_UDT_TYPE", tag("M", "INT"));
        JsonObject data = plcData(arr(udt), null);
        TagTreeBuilder builder = new TagTreeBuilder(data);

        assertThat(builder.getStats().udtCount).isEqualTo(1);
    }

    @Test
    void udtInstance_multipleUdts_incrementsCountPerUdt() throws Exception {
        JsonObject udt1 = tagWithMembers("Udt1", "TYPE_A", tag("X", "INT"));
        JsonObject udt2 = tagWithMembers("Udt2", "TYPE_B", tag("Y", "BOOL"));
        JsonObject data = plcData(arr(udt1, udt2), null);
        TagTreeBuilder builder = new TagTreeBuilder(data);

        assertThat(builder.getStats().udtCount).isEqualTo(2);
    }

    @Test
    void udtInstance_doesNotCountMembersAsToplevelTotalTags() throws Exception {
        // UDT folder should not count toward totalTags — only atomic leaves do
        JsonObject udt = tagWithMembers("MyUDT", "MY_UDT_TYPE",
            tag("M1", "INT"), tag("M2", "BOOL")
        );
        JsonObject atomic = tag("AtomicTag", "DINT");
        JsonObject data = plcData(arr(udt, atomic), null);
        TagTreeBuilder builder = new TagTreeBuilder(data);

        // Only the 2 members + 1 atomic = 3 atomic leaves
        assertThat(builder.getStats().totalTags).isEqualTo(3);
    }

    @Test
    void udtInstance_nestedUdt_recursive() throws Exception {
        // UDT inside a UDT
        JsonObject innerUdt = tagWithMembers("Inner", "INNER_T", tag("Val", "INT"));
        JsonObject outerUdt = tagWithMembers("Outer", "OUTER_T", innerUdt);
        JsonObject data = plcData(arr(outerUdt), null);
        TagTreeBuilder builder = new TagTreeBuilder(data);

        // Inner UDT should be a folder under Outer
        assertThat(builder.getChildCount("Controller:Global/Outer")).isEqualTo(1);
        JSONObject innerFolder = builder.getChildrenOf("Controller:Global/Outer", 1, 0, 10).getJSONObject(0);
        assertThat(innerFolder.getBoolean("isFolder")).isTrue();
        assertThat(innerFolder.getString("name")).isEqualTo("Inner");
        // Inner UDT member
        assertThat(builder.getChildCount("Controller:Global/Outer/Inner")).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // 5. getChildCount() returns correct count
    // -------------------------------------------------------------------------

    @Test
    void getChildCount_unknownPath_returnsZero() throws Exception {
        TagTreeBuilder builder = new TagTreeBuilder(new JsonObject());
        assertThat(builder.getChildCount("does/not/exist")).isEqualTo(0);
    }

    @Test
    void getChildCount_correctForMultipleTags() throws Exception {
        JsonObject data = plcData(
            arr(tag("A", "INT"), tag("B", "BOOL"), tag("C", "REAL"), tag("D", "DINT")),
            null
        );
        TagTreeBuilder builder = new TagTreeBuilder(data);
        assertThat(builder.getChildCount("Controller:Global")).isEqualTo(4);
    }

    // -------------------------------------------------------------------------
    // 6. getChildrenOf() paginates correctly (offset/limit)
    // -------------------------------------------------------------------------

    @Test
    void getChildrenOf_offsetSkipsItems() throws Exception {
        JsonObject data = plcData(
            arr(tag("A", "INT"), tag("B", "BOOL"), tag("C", "REAL")),
            null
        );
        TagTreeBuilder builder = new TagTreeBuilder(data);

        JSONArray page = builder.getChildrenOf("Controller:Global", 1, 1, 10);
        assertThat(page.length()).isEqualTo(2); // skipped first, got 2
    }

    @Test
    void getChildrenOf_limitCapsResults() throws Exception {
        JsonObject data = plcData(
            arr(tag("A", "INT"), tag("B", "BOOL"), tag("C", "REAL"), tag("D", "DINT")),
            null
        );
        TagTreeBuilder builder = new TagTreeBuilder(data);

        JSONArray page = builder.getChildrenOf("Controller:Global", 1, 0, 2);
        assertThat(page.length()).isEqualTo(2);
    }

    @Test
    void getChildrenOf_offsetPlusLimitCombined() throws Exception {
        JsonObject data = plcData(
            arr(tag("A", "INT"), tag("B", "BOOL"), tag("C", "REAL"), tag("D", "DINT"), tag("E", "STRING")),
            null
        );
        TagTreeBuilder builder = new TagTreeBuilder(data);

        // offset=2, limit=2 → items at index 2 and 3
        JSONArray page = builder.getChildrenOf("Controller:Global", 1, 2, 2);
        assertThat(page.length()).isEqualTo(2);
    }

    @Test
    void getChildrenOf_offsetBeyondEnd_returnsEmpty() throws Exception {
        JsonObject data = plcData(arr(tag("A", "INT")), null);
        TagTreeBuilder builder = new TagTreeBuilder(data);

        JSONArray page = builder.getChildrenOf("Controller:Global", 1, 99, 10);
        assertThat(page.length()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // 7. getChildrenOf() depth=-1 includes nested children recursively
    // -------------------------------------------------------------------------

    @Test
    void getChildrenOf_depthMinusOne_includesNestedChildren() throws Exception {
        JsonObject udt = tagWithMembers("MyUDT", "MY_TYPE", tag("M1", "INT"), tag("M2", "BOOL"));
        JsonObject data = plcData(arr(udt, tag("AtomicTag", "DINT")), null);
        TagTreeBuilder builder = new TagTreeBuilder(data);

        // depth=-1 on root: Controller:Global folder should carry nested children
        JSONArray rootChildren = builder.getChildrenOf("", -1, 0, 100);
        assertThat(rootChildren.length()).isEqualTo(1); // Controller:Global

        JSONObject cgFolder = rootChildren.getJSONObject(0);
        assertThat(cgFolder.has("children")).isTrue();

        JSONArray cgChildren = cgFolder.getJSONArray("children");
        // Should contain MyUDT (folder) and AtomicTag (leaf)
        assertThat(cgChildren.length()).isEqualTo(2);

        // Find the UDT folder
        JSONObject udtFolder = null;
        for (int i = 0; i < cgChildren.length(); i++) {
            if (cgChildren.getJSONObject(i).getBoolean("isFolder")) {
                udtFolder = cgChildren.getJSONObject(i);
                break;
            }
        }
        assertThat(udtFolder).isNotNull();
        assertThat(udtFolder.has("children")).isTrue();
        assertThat(udtFolder.getJSONArray("children").length()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // 8. getFlatTags() returns only leaf nodes (isFolder=false), paginated
    // -------------------------------------------------------------------------

    @Test
    void getFlatTags_returnsOnlyLeafNodes() throws Exception {
        JsonObject udt = tagWithMembers("MyUDT", "MY_TYPE", tag("M1", "INT"));
        JsonObject data = plcData(arr(udt, tag("Leaf", "BOOL")), null);
        TagTreeBuilder builder = new TagTreeBuilder(data);

        JSONArray flat = builder.getFlatTags("", 0, 100);
        for (int i = 0; i < flat.length(); i++) {
            assertThat(flat.getJSONObject(i).optBoolean("isFolder", false)).isFalse();
        }
    }

    @Test
    void getFlatTags_paginatesWithOffset() throws Exception {
        JsonObject data = plcData(
            arr(tag("A", "INT"), tag("B", "BOOL"), tag("C", "REAL"), tag("D", "DINT")),
            null
        );
        TagTreeBuilder builder = new TagTreeBuilder(data);

        JSONArray all  = builder.getFlatTags("", 0, 100);
        JSONArray page = builder.getFlatTags("", 2, 100);
        assertThat(page.length()).isEqualTo(all.length() - 2);
    }

    @Test
    void getFlatTags_paginatesWithLimit() throws Exception {
        JsonObject data = plcData(
            arr(tag("A", "INT"), tag("B", "BOOL"), tag("C", "REAL"), tag("D", "DINT")),
            null
        );
        TagTreeBuilder builder = new TagTreeBuilder(data);

        JSONArray page = builder.getFlatTags("", 0, 2);
        assertThat(page.length()).isEqualTo(2);
    }

    @Test
    void getFlatTags_emptyData_returnsEmpty() throws Exception {
        TagTreeBuilder builder = new TagTreeBuilder(new JsonObject());
        assertThat(builder.getFlatTags("", 0, 100).length()).isEqualTo(0);
    }

    @Test
    void getFlatTags_filteredByParentPath() throws Exception {
        JsonObject data = plcData(
            arr(tag("GlobalTag", "INT")),
            arr(program("Prog1", tag("ProgTag", "BOOL")))
        );
        TagTreeBuilder builder = new TagTreeBuilder(data);

        // Only get flat tags under Controller:Global
        JSONArray flat = builder.getFlatTags("Controller:Global", 0, 100);
        assertThat(flat.length()).isEqualTo(1);
        assertThat(flat.getJSONObject(0).getString("name")).isEqualTo("GlobalTag");
    }

    // -------------------------------------------------------------------------
    // 9. getTotalFlatCount() counts only leaf nodes
    // -------------------------------------------------------------------------

    @Test
    void getTotalFlatCount_countsOnlyLeaves() throws Exception {
        JsonObject udt = tagWithMembers("UDT", "T", tag("M1", "INT"), tag("M2", "BOOL"));
        JsonObject data = plcData(arr(udt, tag("Leaf", "DINT")), null);
        TagTreeBuilder builder = new TagTreeBuilder(data);

        // M1, M2, Leaf = 3 leaves
        assertThat(builder.getTotalFlatCount("")).isEqualTo(3);
    }

    @Test
    void getTotalFlatCount_emptyData_returnsZero() throws Exception {
        assertThat(new TagTreeBuilder(new JsonObject()).getTotalFlatCount("")).isEqualTo(0);
    }

    @Test
    void getTotalFlatCount_filteredBySubPath() throws Exception {
        JsonObject data = plcData(
            arr(tag("G1", "INT"), tag("G2", "BOOL")),
            arr(program("P1", tag("P1T", "DINT")))
        );
        TagTreeBuilder builder = new TagTreeBuilder(data);

        assertThat(builder.getTotalFlatCount("Controller:Global")).isEqualTo(2);
        assertThat(builder.getTotalFlatCount("Programs")).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // 10. Sort order: folders come before leaf tags in getChildrenOf()
    // -------------------------------------------------------------------------

    @Test
    void sortOrder_foldersBeforeLeafTags() throws Exception {
        // Mix of atomic tags and a UDT folder
        JsonObject udt = tagWithMembers("ZFolder", "T", tag("M", "INT"));
        JsonObject data = plcData(
            arr(tag("ATag", "INT"), tag("BTag", "BOOL"), udt),
            null
        );
        TagTreeBuilder builder = new TagTreeBuilder(data);

        JSONArray children = builder.getChildrenOf("Controller:Global", 1, 0, 100);
        // First item must be the folder (ZFolder)
        assertThat(children.getJSONObject(0).getBoolean("isFolder")).isTrue();
        assertThat(children.getJSONObject(0).getString("name")).isEqualTo("ZFolder");
        // Remaining items are leaves
        assertThat(children.getJSONObject(1).getBoolean("isFolder")).isFalse();
        assertThat(children.getJSONObject(2).getBoolean("isFolder")).isFalse();
    }

    @Test
    void sortOrder_alphabeticalWithinSameType() throws Exception {
        JsonObject data = plcData(
            arr(tag("Charlie", "INT"), tag("Alpha", "BOOL"), tag("Bravo", "REAL")),
            null
        );
        TagTreeBuilder builder = new TagTreeBuilder(data);

        JSONArray children = builder.getChildrenOf("Controller:Global", 1, 0, 100);
        assertThat(children.getJSONObject(0).getString("name")).isEqualTo("Alpha");
        assertThat(children.getJSONObject(1).getString("name")).isEqualTo("Bravo");
        assertThat(children.getJSONObject(2).getString("name")).isEqualTo("Charlie");
    }

    @Test
    void sortOrder_caseInsensitiveAlpha() throws Exception {
        JsonObject data = plcData(
            arr(tag("zebra", "INT"), tag("Apple", "BOOL"), tag("mango", "REAL")),
            null
        );
        TagTreeBuilder builder = new TagTreeBuilder(data);

        JSONArray children = builder.getChildrenOf("Controller:Global", 1, 0, 100);
        assertThat(children.getJSONObject(0).getString("name")).isEqualToIgnoringCase("Apple");
        assertThat(children.getJSONObject(1).getString("name")).isEqualToIgnoringCase("mango");
        assertThat(children.getJSONObject(2).getString("name")).isEqualToIgnoringCase("zebra");
    }

    // -------------------------------------------------------------------------
    // 11. Stats: totalTags counts only atomic (leaf) tags, not folders
    // -------------------------------------------------------------------------

    @Test
    void stats_totalTagsExcludesFolders() throws Exception {
        // 2 UDT folders + 1 atomic at global level + 2 members inside each UDT = 5 atomics
        JsonObject udt1 = tagWithMembers("U1", "T", tag("M1a", "INT"), tag("M1b", "BOOL"));
        JsonObject udt2 = tagWithMembers("U2", "T", tag("M2a", "REAL"));
        JsonObject atomic = tag("Bare", "DINT");
        JsonObject data = plcData(arr(udt1, udt2, atomic), null);
        TagTreeBuilder builder = new TagTreeBuilder(data);

        // M1a + M1b + M2a + Bare = 4
        assertThat(builder.getStats().totalTags).isEqualTo(4);
        // Controller:Global + U1 + U2 = 3 folders
        assertThat(builder.getStats().folderCount).isGreaterThanOrEqualTo(3);
    }

    @Test
    void stats_folderCountIncludesAllLevels() throws Exception {
        JsonObject data = plcData(
            null,
            arr(
                program("P1", tag("T1", "INT")),
                program("P2", tag("T2", "BOOL"))
            )
        );
        TagTreeBuilder builder = new TagTreeBuilder(data);
        // Programs + P1 + P2 = 3 folders
        assertThat(builder.getStats().folderCount).isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // 12. getChildrenOf() with depth=1 does NOT include nested children
    // -------------------------------------------------------------------------

    @Test
    void getChildrenOf_depth1_noNestedChildren() throws Exception {
        JsonObject udt = tagWithMembers("MyUDT", "MY_TYPE", tag("M1", "INT"), tag("M2", "BOOL"));
        JsonObject data = plcData(arr(udt), null);
        TagTreeBuilder builder = new TagTreeBuilder(data);

        // depth=1 at Controller:Global: MyUDT folder is returned but without "children" key
        JSONArray children = builder.getChildrenOf("Controller:Global", 1, 0, 100);
        assertThat(children.length()).isEqualTo(1);
        JSONObject udtNode = children.getJSONObject(0);
        assertThat(udtNode.getBoolean("isFolder")).isTrue();
        assertThat(udtNode.has("children")).isFalse();
    }

    @Test
    void getChildrenOf_depth1_root_noNestedChildren() throws Exception {
        JsonObject data = plcData(arr(tag("T", "INT")), null);
        TagTreeBuilder builder = new TagTreeBuilder(data);

        // depth=1 at root: Controller:Global returned without "children" key
        JSONArray rootChildren = builder.getChildrenOf("", 1, 0, 100);
        assertThat(rootChildren.length()).isEqualTo(1);
        assertThat(rootChildren.getJSONObject(0).has("children")).isFalse();
    }

    // -------------------------------------------------------------------------
    // 13. getChildrenOf() — childCount / hasChildren metadata on folders
    // -------------------------------------------------------------------------

    @Test
    void getChildrenOf_folderHasChildCountMetadata() throws Exception {
        JsonObject data = plcData(arr(tag("A", "INT"), tag("B", "BOOL")), null);
        TagTreeBuilder builder = new TagTreeBuilder(data);

        JSONArray rootChildren = builder.getChildrenOf("", 1, 0, 10);
        JSONObject cgFolder = rootChildren.getJSONObject(0);
        assertThat(cgFolder.getInt("childCount")).isEqualTo(2);
        assertThat(cgFolder.getBoolean("hasChildren")).isTrue();
    }

    @Test
    void getChildrenOf_emptyFolder_hasChildrenFalse() throws Exception {
        // Program with no tags
        JsonObject data = plcData(null, arr(program("EmptyProg")));
        TagTreeBuilder builder = new TagTreeBuilder(data);

        JSONArray progChildren = builder.getChildrenOf("Programs", 1, 0, 10);
        JSONObject emptyProg = progChildren.getJSONObject(0);
        assertThat(emptyProg.getInt("childCount")).isEqualTo(0);
        assertThat(emptyProg.getBoolean("hasChildren")).isFalse();
    }

    // -------------------------------------------------------------------------
    // 14. Mixed global + programs
    // -------------------------------------------------------------------------

    @Test
    void mixedGlobalAndPrograms_bothFoldersAtRoot() throws Exception {
        JsonObject data = plcData(
            arr(tag("GlobalTag", "INT")),
            arr(program("Main", tag("ProgTag", "BOOL")))
        );
        TagTreeBuilder builder = new TagTreeBuilder(data);

        assertThat(builder.getChildCount("")).isEqualTo(2);

        JSONArray rootChildren = builder.getChildrenOf("", 1, 0, 10);
        boolean hasControllerGlobal = false;
        boolean hasPrograms = false;
        for (int i = 0; i < rootChildren.length(); i++) {
            String name = rootChildren.getJSONObject(i).getString("name");
            if ("Controller:Global".equals(name)) hasControllerGlobal = true;
            if ("Programs".equals(name)) hasPrograms = true;
        }
        assertThat(hasControllerGlobal).isTrue();
        assertThat(hasPrograms).isTrue();
    }

    @Test
    void mixedGlobalAndPrograms_totalTagsCountsBothSides() throws Exception {
        JsonObject data = plcData(
            arr(tag("G1", "INT"), tag("G2", "BOOL")),
            arr(program("Main", tag("P1", "DINT"), tag("P2", "REAL")))
        );
        TagTreeBuilder builder = new TagTreeBuilder(data);

        assertThat(builder.getStats().totalTags).isEqualTo(4);
    }
}
