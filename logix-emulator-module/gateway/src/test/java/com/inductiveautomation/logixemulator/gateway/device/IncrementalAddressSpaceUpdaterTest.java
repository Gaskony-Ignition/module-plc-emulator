package com.inductiveautomation.logixemulator.gateway.device;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for IncrementalAddressSpaceUpdater.
 * Tests change detection and incremental update capability.
 */
class IncrementalAddressSpaceUpdaterTest {

    private IncrementalAddressSpaceUpdater updater;

    @BeforeEach
    void setUp() {
        // Create updater with mock functions
        updater = new IncrementalAddressSpaceUpdater(
            nodeId -> null,  // Mock node lookup - return null for testing
            identifier -> null,  // Mock nodeId factory
            "TestDevice"
        );
    }

    @Test
    @DisplayName("Should detect no changes when data is identical")
    void testNoChanges() {
        JsonObject oldData = createTestData("Tag1", "DINT", 100);
        JsonObject newData = createTestData("Tag1", "DINT", 100);

        IncrementalAddressSpaceUpdater.CompareResult result = updater.compare(oldData, newData);

        assertThat(result.hasChanges()).isFalse();
        assertThat(result.changedTags).isEmpty();
        assertThat(result.newTags).isEmpty();
        assertThat(result.removedTags).isEmpty();
    }

    @Test
    @DisplayName("Should detect value changes")
    void testValueChanges() {
        JsonObject oldData = createTestData("Tag1", "DINT", 100);
        JsonObject newData = createTestData("Tag1", "DINT", 200);

        IncrementalAddressSpaceUpdater.CompareResult result = updater.compare(oldData, newData);

        assertThat(result.hasChanges()).isTrue();
        assertThat(result.changedTags).hasSize(1);
        assertThat(result.changedTags).containsKey("Tag1");

        IncrementalAddressSpaceUpdater.TagChange change = result.changedTags.get("Tag1");
        assertThat(change.oldValue).isEqualTo("100");
        assertThat(change.newValue).isEqualTo("200");
    }

    @Test
    @DisplayName("Should detect new tags")
    void testNewTags() {
        JsonObject oldData = createTestData("Tag1", "DINT", 100);
        JsonObject newData = createTestDataMultiple(
            new String[]{"Tag1", "Tag2"},
            new String[]{"DINT", "REAL"},
            new Object[]{100, 25.5}
        );

        IncrementalAddressSpaceUpdater.CompareResult result = updater.compare(oldData, newData);

        assertThat(result.hasChanges()).isTrue();
        assertThat(result.newTags).containsExactly("Tag2");
    }

    @Test
    @DisplayName("Should detect removed tags")
    void testRemovedTags() {
        JsonObject oldData = createTestDataMultiple(
            new String[]{"Tag1", "Tag2"},
            new String[]{"DINT", "REAL"},
            new Object[]{100, 25.5}
        );
        JsonObject newData = createTestData("Tag1", "DINT", 100);

        IncrementalAddressSpaceUpdater.CompareResult result = updater.compare(oldData, newData);

        assertThat(result.hasChanges()).isTrue();
        assertThat(result.removedTags).containsExactly("Tag2");
    }

    @Test
    @DisplayName("Should allow incremental update for value-only changes")
    void testCanApplyIncrementally_ValueChanges() {
        JsonObject oldData = createTestData("Tag1", "DINT", 100);
        JsonObject newData = createTestData("Tag1", "DINT", 200);

        IncrementalAddressSpaceUpdater.CompareResult result = updater.compare(oldData, newData);

        assertThat(updater.canApplyIncrementally(result)).isTrue();
    }

    @Test
    @DisplayName("Should require full rebuild for new tags")
    void testCanApplyIncrementally_NewTags() {
        JsonObject oldData = createTestData("Tag1", "DINT", 100);
        JsonObject newData = createTestDataMultiple(
            new String[]{"Tag1", "Tag2"},
            new String[]{"DINT", "BOOL"},
            new Object[]{100, true}
        );

        IncrementalAddressSpaceUpdater.CompareResult result = updater.compare(oldData, newData);

        assertThat(updater.canApplyIncrementally(result)).isFalse();
        assertThat(result.requiresFullRebuild()).isTrue();
    }

    @Test
    @DisplayName("Should require full rebuild for removed tags")
    void testCanApplyIncrementally_RemovedTags() {
        JsonObject oldData = createTestDataMultiple(
            new String[]{"Tag1", "Tag2"},
            new String[]{"DINT", "BOOL"},
            new Object[]{100, true}
        );
        JsonObject newData = createTestData("Tag1", "DINT", 100);

        IncrementalAddressSpaceUpdater.CompareResult result = updater.compare(oldData, newData);

        assertThat(updater.canApplyIncrementally(result)).isFalse();
        assertThat(result.requiresFullRebuild()).isTrue();
    }

    @Test
    @DisplayName("Should extract UDT members for comparison")
    void testUdtMemberComparison() {
        JsonObject oldData = createUdtTestData("Motor1", "TIMER", 10, 5);
        JsonObject newData = createUdtTestData("Motor1", "TIMER", 10, 8);

        IncrementalAddressSpaceUpdater.CompareResult result = updater.compare(oldData, newData);

        // The UDT member "Motor1.ACC" should have changed
        assertThat(result.hasChanges()).isTrue();
    }

    @Test
    @DisplayName("Should handle empty data structures")
    void testEmptyData() {
        JsonObject emptyOld = new JsonObject();
        JsonObject emptyNew = new JsonObject();

        IncrementalAddressSpaceUpdater.CompareResult result = updater.compare(emptyOld, emptyNew);

        assertThat(result.hasChanges()).isFalse();
    }

    @Test
    @DisplayName("Should handle program tags")
    void testProgramTags() {
        JsonObject oldData = createProgramTestData("MainProgram", "Counter", "DINT", 0);
        JsonObject newData = createProgramTestData("MainProgram", "Counter", "DINT", 10);

        IncrementalAddressSpaceUpdater.CompareResult result = updater.compare(oldData, newData);

        assertThat(result.hasChanges()).isTrue();
        assertThat(result.changedTags).containsKey("Programs.MainProgram.Counter");
    }

    // Helper methods to create test data

    private JsonObject createTestData(String name, String type, Object value) {
        JsonObject data = new JsonObject();
        JsonArray tags = new JsonArray();

        JsonObject tag = new JsonObject();
        tag.addProperty("name", name);
        tag.addProperty("data_type", type);
        if (value instanceof Number) {
            tag.addProperty("value", (Number) value);
        } else if (value instanceof Boolean) {
            tag.addProperty("value", (Boolean) value);
        } else {
            tag.addProperty("value", String.valueOf(value));
        }

        tags.add(tag);
        data.add("global_tags", tags);
        return data;
    }

    private JsonObject createTestDataMultiple(String[] names, String[] types, Object[] values) {
        JsonObject data = new JsonObject();
        JsonArray tags = new JsonArray();

        for (int i = 0; i < names.length; i++) {
            JsonObject tag = new JsonObject();
            tag.addProperty("name", names[i]);
            tag.addProperty("data_type", types[i]);
            if (values[i] instanceof Number) {
                tag.addProperty("value", (Number) values[i]);
            } else if (values[i] instanceof Boolean) {
                tag.addProperty("value", (Boolean) values[i]);
            } else {
                tag.addProperty("value", String.valueOf(values[i]));
            }
            tags.add(tag);
        }

        data.add("global_tags", tags);
        return data;
    }

    private JsonObject createUdtTestData(String name, String type, int pre, int acc) {
        JsonObject data = new JsonObject();
        JsonArray tags = new JsonArray();

        JsonObject tag = new JsonObject();
        tag.addProperty("name", name);
        tag.addProperty("data_type", type);

        JsonArray members = new JsonArray();
        JsonObject preMember = new JsonObject();
        preMember.addProperty("name", "PRE");
        preMember.addProperty("data_type", "DINT");
        preMember.addProperty("value", pre);
        members.add(preMember);

        JsonObject accMember = new JsonObject();
        accMember.addProperty("name", "ACC");
        accMember.addProperty("data_type", "DINT");
        accMember.addProperty("value", acc);
        members.add(accMember);

        tag.add("udt_members", members);
        tags.add(tag);
        data.add("global_tags", tags);
        return data;
    }

    private JsonObject createProgramTestData(String programName, String tagName, String type, Object value) {
        JsonObject data = new JsonObject();
        JsonArray programs = new JsonArray();

        JsonObject program = new JsonObject();
        program.addProperty("name", programName);

        JsonArray tags = new JsonArray();
        JsonObject tag = new JsonObject();
        tag.addProperty("name", tagName);
        tag.addProperty("data_type", type);
        if (value instanceof Number) {
            tag.addProperty("value", (Number) value);
        } else {
            tag.addProperty("value", String.valueOf(value));
        }
        tags.add(tag);

        program.add("tags", tags);
        programs.add(program);
        data.add("programs", programs);
        return data;
    }
}
