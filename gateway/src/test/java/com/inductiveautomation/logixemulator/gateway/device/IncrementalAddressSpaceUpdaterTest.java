package com.inductiveautomation.logixemulator.gateway.device;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
        // v10 C1: program-scoped tags are keyed by their canonical NodeId identifier
        // "Program:<Prog>.Tag" (ADDRESSING.md §3.2), not the old "Programs.<Prog>.Tag" form.
        assertThat(result.changedTags).containsKey("Program:MainProgram.Counter");
    }

    // =====================================================================================
    // FIX-4 — the diff must operate on the same expanded canonical identifiers the address
    // space actually contains (post-C2/C3 no node exists at a bare array base id, and members
    // nest to arbitrary depth), and anything not incrementally applicable must be reported so
    // the caller can fall back to a full rebuild instead of claiming success.
    // =====================================================================================

    @Nested
    @DisplayName("FIX-4: expanded canonical identifiers + honest apply result")
    class ExpandedIdentifierDiffing {

        @Test
        @DisplayName("a value-only change to an array tag is keyed by the expanded element ids "
            + "(Arr[0], Arr[1]) — not the bare base id that has no node post-C2")
        void arrayValueChangeKeyedByExpandedElementIds() {
            JsonObject oldData = arrayTagData("Arr", "DINT", "2", 1);
            JsonObject newData = arrayTagData("Arr", "DINT", "2", 2);

            IncrementalAddressSpaceUpdater.CompareResult result = updater.compare(oldData, newData);

            assertThat(result.changedTags).containsKeys("Arr[0]", "Arr[1]");
            assertThat(result.changedTags).doesNotContainKey("Arr");
            assertThat(updater.canApplyIncrementally(result)).isTrue();
        }

        @Test
        @DisplayName("an array element value change actually applies — setValue reaches the real "
            + "element nodes and the apply reports success")
        void arrayValueChangeAppliesToElementNodes() {
            Map<NodeId, UaNode> nodes = new HashMap<>();
            UaVariableNode elem0 = mock(UaVariableNode.class);
            UaVariableNode elem1 = mock(UaVariableNode.class);
            nodes.put(new NodeId(1, "Arr[0]"), elem0);
            nodes.put(new NodeId(1, "Arr[1]"), elem1);

            IncrementalAddressSpaceUpdater liveUpdater = new IncrementalAddressSpaceUpdater(
                nodes::get, id -> new NodeId(1, id), "TestDevice");

            IncrementalAddressSpaceUpdater.CompareResult result = liveUpdater.compare(
                arrayTagData("Arr", "DINT", "2", 1),
                arrayTagData("Arr", "DINT", "2", 2));

            boolean applied = liveUpdater.applyIncrementalUpdate(result);

            assertThat(applied).as("all changes must apply to existing nodes").isTrue();
            ArgumentCaptor<DataValue> captor = ArgumentCaptor.forClass(DataValue.class);
            verify(elem0).setValue(captor.capture());
            verify(elem1).setValue(any(DataValue.class));
            assertThat(captor.getValue().getValue().getValue())
                .as("DINT value must arrive as an Integer, not a String")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("a changed member at depth 2 (Motor.Inner.Val) produces a diff and applies — "
            + "previously the one-level recursion produced NO diff at all")
        void depthTwoMemberValueChangeDiffsAndApplies() {
            Map<NodeId, UaNode> nodes = new HashMap<>();
            UaVariableNode valNode = mock(UaVariableNode.class);
            nodes.put(new NodeId(1, "Motor.Inner.Val"), valNode);

            IncrementalAddressSpaceUpdater liveUpdater = new IncrementalAddressSpaceUpdater(
                nodes::get, id -> new NodeId(1, id), "TestDevice");

            IncrementalAddressSpaceUpdater.CompareResult result = liveUpdater.compare(
                depthTwoUdtData("Motor", 5), depthTwoUdtData("Motor", 9));

            assertThat(result.changedTags).containsKey("Motor.Inner.Val");
            assertThat(liveUpdater.canApplyIncrementally(result)).isTrue();

            boolean applied = liveUpdater.applyIncrementalUpdate(result);

            assertThat(applied).isTrue();
            ArgumentCaptor<DataValue> captor = ArgumentCaptor.forClass(DataValue.class);
            verify(valNode).setValue(captor.capture());
            assertThat(captor.getValue().getValue().getValue()).isEqualTo(9);
        }

        @Test
        @DisplayName("a structural change (array grows) still triggers full rebuild")
        void structuralChangeStillTriggersFullRebuild() {
            JsonObject oldData = arrayTagData("Arr", "DINT", "2", 1);
            JsonObject newData = arrayTagData("Arr", "DINT", "4", 1);

            IncrementalAddressSpaceUpdater.CompareResult result = updater.compare(oldData, newData);

            assertThat(result.newTags).contains("Arr[2]", "Arr[3]");
            assertThat(updater.canApplyIncrementally(result)).isFalse();
            assertThat(result.requiresFullRebuild()).isTrue();
        }

        @Test
        @DisplayName("a BOOL array value diff is keyed by the packed Tag[word].bit ids — the bare "
            + "Tag[N] element node never exists (C3)")
        void boolArrayKeyedByPackedBitIds() {
            JsonObject oldData = arrayTagData("Bits", "BOOL", "33", false);
            JsonObject newData = arrayTagData("Bits", "BOOL", "33", true);

            IncrementalAddressSpaceUpdater.CompareResult result = updater.compare(oldData, newData);

            assertThat(result.changedTags).containsKeys("Bits[0].0", "Bits[0].31", "Bits[1].0");
            assertThat(result.changedTags).doesNotContainKey("Bits[0]");
            assertThat(result.changedTags).doesNotContainKey("Bits[32]");
        }

        @Test
        @DisplayName("applyIncrementalUpdate() reports failure when a changed tag has no node — "
            + "the caller must fall back to a full rebuild instead of claiming success")
        void applyReportsFailureWhenNodeMissing() {
            IncrementalAddressSpaceUpdater liveUpdater = new IncrementalAddressSpaceUpdater(
                nodeId -> null, id -> new NodeId(1, id), "TestDevice");

            IncrementalAddressSpaceUpdater.CompareResult result = liveUpdater.compare(
                createTestData("Tag1", "DINT", 100), createTestData("Tag1", "DINT", 200));

            assertThat(liveUpdater.applyIncrementalUpdate(result))
                .as("an update that reached no node must not report success")
                .isFalse();
        }
    }

    // Helper methods to create test data

    private JsonObject arrayTagData(String name, String type, String dims, Object value) {
        JsonObject data = new JsonObject();
        JsonArray tags = new JsonArray();

        JsonObject tag = new JsonObject();
        tag.addProperty("name", name);
        tag.addProperty("data_type", type);
        tag.addProperty("isArray", true);
        tag.addProperty("dimensions", dims);
        if (value instanceof Number number) {
            tag.addProperty("value", number);
        } else if (value instanceof Boolean bool) {
            tag.addProperty("value", bool);
        } else {
            tag.addProperty("value", String.valueOf(value));
        }

        tags.add(tag);
        data.add("global_tags", tags);
        return data;
    }

    /** Motor (MyUdt) -> Inner (InnerUdt) -> Val (DINT, {@code val}) — a depth-2 member. */
    private JsonObject depthTwoUdtData(String name, int val) {
        JsonObject data = new JsonObject();
        JsonArray tags = new JsonArray();

        JsonObject valMember = new JsonObject();
        valMember.addProperty("name", "Val");
        valMember.addProperty("data_type", "DINT");
        valMember.addProperty("value", val);
        JsonArray innerMembers = new JsonArray();
        innerMembers.add(valMember);

        JsonObject innerMember = new JsonObject();
        innerMember.addProperty("name", "Inner");
        innerMember.addProperty("data_type", "InnerUdt");
        innerMember.add("udt_members", innerMembers);
        JsonArray members = new JsonArray();
        members.add(innerMember);

        JsonObject tag = new JsonObject();
        tag.addProperty("name", name);
        tag.addProperty("data_type", "MyUdt");
        tag.add("udt_members", members);

        tags.add(tag);
        data.add("global_tags", tags);
        return data;
    }

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
