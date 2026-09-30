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

    // =====================================================================================
    // FIX-15 — array-element-only value changes must be visible to the diff (release blocker,
    // plc-dod3/item4-hotreload.txt: RealArray[2] value 0.0 -> 42.5 reported "0 changed" and the
    // element silently kept reading 0.0 after hot-reload). Previously every element shared the
    // same (valueless) array-tag object, so compare() could never see a per-element difference;
    // now each element is keyed to its own decoded value from the tag's element_values map.
    // =====================================================================================

    @Nested
    @DisplayName("FIX-15: array-element value-only change is diffed and applied")
    class ArrayElementValueDiffing {

        @Test
        @DisplayName("only the changed element (RealArray[2]) is reported - unchanged sibling "
            + "elements are not")
        void onlyChangedElementIsReported() {
            Map<String, String> oldValues = Map.of("[0]", "0.0", "[1]", "0.0", "[2]", "0.0");
            Map<String, String> newValues = Map.of("[0]", "0.0", "[1]", "0.0", "[2]", "42.5");

            JsonObject oldData = arrayTagDataWithElementValues("RealArray", "REAL", "3", oldValues);
            JsonObject newData = arrayTagDataWithElementValues("RealArray", "REAL", "3", newValues);

            IncrementalAddressSpaceUpdater.CompareResult result = updater.compare(oldData, newData);

            assertThat(result.changedTags).containsOnlyKeys("RealArray[2]");
            IncrementalAddressSpaceUpdater.TagChange change = result.changedTags.get("RealArray[2]");
            assertThat(change.oldValue).isEqualTo("0.0");
            assertThat(change.newValue).isEqualTo("42.5");
            assertThat(updater.canApplyIncrementally(result)).isTrue();
        }

        @Test
        @DisplayName("the changed element applies to its own canonical element node, as a Float "
            + "not a String (the exact DoD reproduction: RealArray[2] 0.0 -> 42.5)")
        void changedElementAppliesToElementNode() {
            Map<NodeId, UaNode> nodes = new HashMap<>();
            UaVariableNode elem2 = mock(UaVariableNode.class);
            nodes.put(new NodeId(1, "RealArray[2]"), elem2);

            IncrementalAddressSpaceUpdater liveUpdater = new IncrementalAddressSpaceUpdater(
                nodes::get, id -> new NodeId(1, id), "TestDevice");

            IncrementalAddressSpaceUpdater.CompareResult result = liveUpdater.compare(
                arrayTagDataWithElementValues("RealArray", "REAL", "3", Map.of("[2]", "0.0")),
                arrayTagDataWithElementValues("RealArray", "REAL", "3", Map.of("[2]", "42.5")));

            boolean applied = liveUpdater.applyIncrementalUpdate(result);

            assertThat(applied).as("the array-element change must apply cleanly").isTrue();
            ArgumentCaptor<DataValue> captor = ArgumentCaptor.forClass(DataValue.class);
            verify(elem2).setValue(captor.capture());
            assertThat(captor.getValue().getValue().getValue())
                .as("REAL value must arrive as a Float, not a String")
                .isEqualTo(42.5f);
        }

        @Test
        @DisplayName("an element with no export value in either snapshot stays on the type "
            + "default and is not reported as changed")
        void elementWithoutExportValueUnchanged() {
            JsonObject oldData = arrayTagDataWithElementValues("Arr", "DINT", "3", Map.of());
            JsonObject newData = arrayTagDataWithElementValues("Arr", "DINT", "3", Map.of());

            IncrementalAddressSpaceUpdater.CompareResult result = updater.compare(oldData, newData);

            assertThat(result.hasChanges()).isFalse();
        }

        @Test
        @DisplayName("a BOOL array element-only change is keyed by its packed Tag[word].bit id, "
            + "not the bare (driver-invalid) element index")
        void boolArrayElementValueChangeKeyedByPackedBit() {
            JsonObject oldData = arrayTagDataWithElementValues("Bits", "BOOL", "32", Map.of("[5]", "false"));
            JsonObject newData = arrayTagDataWithElementValues("Bits", "BOOL", "32", Map.of("[5]", "true"));

            IncrementalAddressSpaceUpdater.CompareResult result = updater.compare(oldData, newData);

            assertThat(result.changedTags).containsOnlyKeys("Bits[0].5");
        }
    }

    // =====================================================================================
    // FIX-11 — hot-reload value coercion must route through the same type mapping the builder
    // uses (AddressSpaceBuilder.getInitialValue's switch), not a 5-type local switch whose
    // default wrote a java.lang.String into LINT/LREAL/unsigned/time-typed nodes.
    // =====================================================================================

    @Nested
    @DisplayName("FIX-11: value coercion routes through the builder's type mapping")
    class ValueCoercion {

        @Test
        @DisplayName("LINT arrives as Long, not String")
        void lintCoercesToLong() {
            assertThat(applyAndCapture("LINT", "100", "200")).isEqualTo(200L);
        }

        @Test
        @DisplayName("LREAL arrives as Double, not String")
        void lrealCoercesToDouble() {
            assertThat(applyAndCapture("LREAL", "1.5", "2.5")).isEqualTo(2.5d);
        }

        @Test
        @DisplayName("TIME arrives as Long (epoch/duration per ADDRESSING.md §3.14)")
        void timeCoercesToLong() {
            assertThat(applyAndCapture("TIME", "0", "5000")).isEqualTo(5000L);
        }

        @Test
        @DisplayName("USINT arrives as Milo UByte")
        void usintCoercesToUByte() {
            assertThat(applyAndCapture("USINT", "0", "255"))
                .isEqualTo(org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte(255));
        }

        @Test
        @DisplayName("UINT arrives as Milo UShort")
        void uintCoercesToUShort() {
            assertThat(applyAndCapture("UINT", "0", "65535"))
                .isEqualTo(org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ushort(65535));
        }

        @Test
        @DisplayName("UDINT arrives as Milo UInteger")
        void udintCoercesToUInteger() {
            assertThat(applyAndCapture("UDINT", "0", "4294967295"))
                .isEqualTo(org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint(4294967295L));
        }

        @Test
        @DisplayName("ULINT arrives as Milo ULong")
        void ulintCoercesToULong() {
            assertThat(applyAndCapture("ULINT", "0", "42"))
                .isEqualTo(org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ulong(42L));
        }

        @Test
        @DisplayName("REAL still arrives as Float; DINT as Integer (existing families unchanged)")
        void existingFamiliesUnchanged() {
            assertThat(applyAndCapture("REAL", "1.0", "2.5")).isEqualTo(2.5f);
            assertThat(applyAndCapture("DINT", "1", "7")).isEqualTo(7);
        }

        @Test
        @DisplayName("an unparseable value for a typed node fails the apply (full-rebuild fallback) "
            + "instead of writing new Variant(\"garbage\") - a String - into the typed node")
        void unparseableValueFailsInsteadOfWritingString() {
            Map<NodeId, UaNode> nodes = new HashMap<>();
            UaVariableNode node = mock(UaVariableNode.class);
            nodes.put(new NodeId(1, "Tag1"), node);

            IncrementalAddressSpaceUpdater liveUpdater = new IncrementalAddressSpaceUpdater(
                nodes::get, id -> new NodeId(1, id), "TestDevice");

            IncrementalAddressSpaceUpdater.CompareResult result = liveUpdater.compare(
                createTestData("Tag1", "LREAL", 1.0), createTestData("Tag1", "LREAL", "garbage"));

            assertThat(liveUpdater.applyIncrementalUpdate(result)).isFalse();
            verify(node, org.mockito.Mockito.never()).setValue(any(DataValue.class));
        }

        /**
         * Runs a scalar old→new value change for {@code dataType} through compare + apply against
         * a mocked node at "Tag1" and returns the value actually written.
         */
        private Object applyAndCapture(String dataType, String oldValue, String newValue) {
            Map<NodeId, UaNode> nodes = new HashMap<>();
            UaVariableNode node = mock(UaVariableNode.class);
            nodes.put(new NodeId(1, "Tag1"), node);

            IncrementalAddressSpaceUpdater liveUpdater = new IncrementalAddressSpaceUpdater(
                nodes::get, id -> new NodeId(1, id), "TestDevice");

            IncrementalAddressSpaceUpdater.CompareResult result = liveUpdater.compare(
                createTestData("Tag1", dataType, oldValue), createTestData("Tag1", dataType, newValue));

            assertThat(liveUpdater.applyIncrementalUpdate(result))
                .as("the %s change must apply cleanly", dataType)
                .isTrue();

            ArgumentCaptor<DataValue> captor = ArgumentCaptor.forClass(DataValue.class);
            verify(node).setValue(captor.capture());
            return captor.getValue().getValue().getValue();
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

    /**
     * Builds an array tag shaped exactly like {@code L5XParser}'s real FIX-15 output: an
     * {@code isArray}/{@code dimensions} tag carrying an {@code element_values} object keyed by
     * the plain L5X bracket-index string, with no tag-level {@code value}/{@code initial_value}
     * at all (arrays never carry one, C8) - unlike the older {@link #arrayTagData} helper above,
     * which predates FIX-15 and synthesises an unrealistic tag-level value shared by every element.
     */
    private JsonObject arrayTagDataWithElementValues(
            String name, String type, String dims, Map<String, String> elementValues) {
        JsonObject data = new JsonObject();
        JsonArray tags = new JsonArray();

        JsonObject tag = new JsonObject();
        tag.addProperty("name", name);
        tag.addProperty("data_type", type);
        tag.addProperty("isArray", true);
        tag.addProperty("dimensions", dims);

        JsonObject values = new JsonObject();
        for (Map.Entry<String, String> entry : elementValues.entrySet()) {
            values.addProperty(entry.getKey(), entry.getValue());
        }
        tag.add("element_values", values);

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
