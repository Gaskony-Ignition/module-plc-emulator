package com.inductiveautomation.logixemulator.gateway.device;

import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.logixemulator.gateway.OpcUaSimulationEngine;
import com.inductiveautomation.logixemulator.gateway.device.TagWriteDispatcher.WriteResult;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.server.UaNodeManager;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TagWriteDispatcher} — the C11/C12-aware tag I/O
 * collaborator extracted from {@code LogixEmulatorDevice} during the Sprint 3
 * P6 god-class refactor. The dispatcher owns the recalibration call to the
 * simulation engine after a successful write, so these tests verify both the
 * happy path and that recalibration is only invoked for currently-simulated
 * tags.
 */
@ExtendWith(MockitoExtension.class)
class TagWriteDispatcherTest {

    @Mock DeviceContext context;
    @Mock UaNodeManager nodeManager;
    @Mock OpcUaSimulationEngine simulationEngine;
    @Mock UaVariableNode variableNode;
    @Mock UaNode nonVariableNode;

    Supplier<UaNodeManager> nodeManagerSupplier;
    Supplier<OpcUaSimulationEngine> simulationEngineSupplier;

    TagWriteDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        nodeManagerSupplier = () -> nodeManager;
        simulationEngineSupplier = () -> simulationEngine;
        dispatcher = new TagWriteDispatcher(context, nodeManagerSupplier, simulationEngineSupplier);

        // The dispatcher uses context.nodeId(path) internally — return a
        // lenient stub so each test doesn't have to wire it up.
        lenient().when(context.nodeId(anyString())).thenAnswer(inv ->
            new NodeId(2, "test/" + inv.getArgument(0))
        );

        // Default the shared @Mock variableNode to a normal read-write tag so
        // existing write/read tests don't each have to stub AccessLevel
        // themselves; read-only-specific tests override this.
        lenient().when(variableNode.getAccessLevel())
            .thenReturn(AccessLevel.toValue(AccessLevel.READ_WRITE));
    }

    @Test
    @DisplayName("constructor rejects null context")
    void constructorRejectsNullContext() {
        assertThatThrownBy(() -> new TagWriteDispatcher(null, nodeManagerSupplier, simulationEngineSupplier))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constructor rejects null nodeManager supplier")
    void constructorRejectsNullNodeManagerSupplier() {
        assertThatThrownBy(() -> new TagWriteDispatcher(context, null, simulationEngineSupplier))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constructor rejects null simulation engine supplier")
    void constructorRejectsNullEngineSupplier() {
        assertThatThrownBy(() -> new TagWriteDispatcher(context, nodeManagerSupplier, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // readTagValue
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("readTagValue() returns the variant value when node exists")
    void readReturnsValue() {
        when(nodeManager.get(any(NodeId.class))).thenReturn(variableNode);
        when(variableNode.getValue()).thenReturn(new DataValue(new Variant(42)));

        Object value = dispatcher.readTagValue("Controller:Global/MyTag");

        assertThat(value).isEqualTo(42);
    }

    @Test
    @DisplayName("readTagValue() returns null when node manager returns null")
    void readNullNode() {
        when(nodeManager.get(any(NodeId.class))).thenReturn(null);

        assertThat(dispatcher.readTagValue("Foo")).isNull();
    }

    @Test
    @DisplayName("readTagValue() returns null when node is not a UaVariableNode")
    void readNonVariableNode() {
        when(nodeManager.get(any(NodeId.class))).thenReturn(nonVariableNode);

        assertThat(dispatcher.readTagValue("Foo")).isNull();
    }

    @Test
    @DisplayName("readTagValue() returns null and logs when DataValue is null")
    void readNullDataValue() {
        when(nodeManager.get(any(NodeId.class))).thenReturn(variableNode);
        when(variableNode.getValue()).thenReturn(null);

        assertThat(dispatcher.readTagValue("Foo")).isNull();
    }

    @Test
    @DisplayName("readTagValue() swallows exceptions and returns null")
    void readSwallowsExceptions() {
        when(context.nodeId(anyString())).thenThrow(new RuntimeException("bad path"));

        assertThat(dispatcher.readTagValue("???")).isNull();
    }

    // -------------------------------------------------------------------------
    // writeTagValue
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("writeTagValue() returns SUCCESS and calls setValue on success")
    void writeSuccess() {
        when(nodeManager.get(any(NodeId.class))).thenReturn(variableNode);
        when(simulationEngine.isTagSimulated(anyString())).thenReturn(false);

        WriteResult result = dispatcher.writeTagValue("MyTag", 99);

        assertThat(result).isEqualTo(WriteResult.SUCCESS);
        verify(variableNode).setValue(any(DataValue.class));
    }

    @Test
    @DisplayName("writeTagValue() returns NOT_FOUND when node manager returns null")
    void writeNoNode() {
        when(nodeManager.get(any(NodeId.class))).thenReturn(null);

        WriteResult result = dispatcher.writeTagValue("Missing", 1);

        assertThat(result).isEqualTo(WriteResult.NOT_FOUND);
        verify(simulationEngine, never()).recalibrate(anyString(), any());
    }

    @Test
    @DisplayName("writeTagValue() returns NOT_FOUND when node is not a UaVariableNode")
    void writeNonVariableNode() {
        when(nodeManager.get(any(NodeId.class))).thenReturn(nonVariableNode);

        WriteResult result = dispatcher.writeTagValue("Folder", 1);

        assertThat(result).isEqualTo(WriteResult.NOT_FOUND);
        verify(simulationEngine, never()).recalibrate(anyString(), any());
    }

    @Test
    @DisplayName("writeTagValue() recalibrates the engine for a simulated tag (C12)")
    void writeRecalibratesSimulatedTag() {
        when(nodeManager.get(any(NodeId.class))).thenReturn(variableNode);
        when(simulationEngine.isTagSimulated("Controller:Global/MyTag")).thenReturn(true);

        WriteResult result = dispatcher.writeTagValue("Controller:Global/MyTag", 42);

        assertThat(result).isEqualTo(WriteResult.SUCCESS);
        verify(simulationEngine, times(1)).recalibrate("Controller:Global/MyTag", 42);
    }

    @Test
    @DisplayName("writeTagValue() does NOT recalibrate when tag is not simulated")
    void writeNoRecalibrateForUnsimulatedTag() {
        when(nodeManager.get(any(NodeId.class))).thenReturn(variableNode);
        when(simulationEngine.isTagSimulated(anyString())).thenReturn(false);

        WriteResult result = dispatcher.writeTagValue("Controller:Global/Plain", 7);

        assertThat(result).isEqualTo(WriteResult.SUCCESS);
        verify(simulationEngine, never()).recalibrate(anyString(), any());
    }

    @Test
    @DisplayName("writeTagValue() handles a null engine supplier value gracefully")
    void writeWithNullEngine() {
        TagWriteDispatcher d = new TagWriteDispatcher(
            context,
            nodeManagerSupplier,
            () -> null
        );
        when(nodeManager.get(any(NodeId.class))).thenReturn(variableNode);

        WriteResult result = d.writeTagValue("MyTag", 1);

        assertThat(result).isEqualTo(WriteResult.SUCCESS);
        verify(variableNode).setValue(any(DataValue.class));
        // No engine — no recalibrate call possible. We assert the verify above
        // ran successfully (the dispatcher must have skipped the engine).
    }

    @Test
    @DisplayName("writeTagValue() swallows exceptions and returns NOT_FOUND")
    void writeSwallowsExceptions() {
        when(context.nodeId(anyString())).thenThrow(new RuntimeException("boom"));

        WriteResult result = dispatcher.writeTagValue("???", 1);

        assertThat(result).isEqualTo(WriteResult.NOT_FOUND);
    }

    @Test
    @DisplayName("writeTagValue() rejects a write to a read-only tag (FIX-2) — does not call "
        + "setValue and does not recalibrate")
    void writeRejectsReadOnlyTag() {
        when(nodeManager.get(any(NodeId.class))).thenReturn(variableNode);
        when(variableNode.getAccessLevel()).thenReturn(AccessLevel.toValue(AccessLevel.READ_ONLY));

        WriteResult result = dispatcher.writeTagValue("SimpleArray[0]", 123);

        assertThat(result).isEqualTo(WriteResult.READ_ONLY);
        verify(variableNode, never()).setValue(any(DataValue.class));
        verify(simulationEngine, never()).recalibrate(anyString(), any());
    }

    @Test
    @DisplayName("writeTagValue() still succeeds for a READ_WRITE tag (regression guard for the "
        + "read-only check above)")
    void writeStillSucceedsForReadWriteTag() {
        when(nodeManager.get(any(NodeId.class))).thenReturn(variableNode);
        when(variableNode.getAccessLevel()).thenReturn(AccessLevel.toValue(AccessLevel.READ_WRITE));

        WriteResult result = dispatcher.writeTagValue("WritableTag", 1);

        assertThat(result).isEqualTo(WriteResult.SUCCESS);
        verify(variableNode).setValue(any(DataValue.class));
    }

    // -------------------------------------------------------------------------
    // isReadOnly
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("isReadOnly() returns true for a tag with AccessLevel.READ_ONLY")
    void isReadOnlyTrueForReadOnlyNode() {
        when(nodeManager.get(any(NodeId.class))).thenReturn(variableNode);
        when(variableNode.getAccessLevel()).thenReturn(AccessLevel.toValue(AccessLevel.READ_ONLY));

        assertThat(dispatcher.isReadOnly("SimpleArray[0]")).isTrue();
    }

    @Test
    @DisplayName("isReadOnly() returns false for a tag with AccessLevel.READ_WRITE")
    void isReadOnlyFalseForReadWriteNode() {
        when(nodeManager.get(any(NodeId.class))).thenReturn(variableNode);

        assertThat(dispatcher.isReadOnly("WritableTag")).isFalse();
    }

    @Test
    @DisplayName("isReadOnly() returns false when the tag cannot be resolved (not-found is a "
        + "separate concern for the caller)")
    void isReadOnlyFalseWhenNodeNotFound() {
        when(nodeManager.get(any(NodeId.class))).thenReturn(null);

        assertThat(dispatcher.isReadOnly("Missing")).isFalse();
    }

    @Test
    @DisplayName("isReadOnly() swallows exceptions and returns false")
    void isReadOnlySwallowsExceptions() {
        when(context.nodeId(anyString())).thenThrow(new RuntimeException("boom"));

        assertThat(dispatcher.isReadOnly("???")).isFalse();
    }

    // -------------------------------------------------------------------------
    // getAllTagValues
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getAllTagValues() returns a map keyed by the node identifier")
    void getAllReturnsValues() {
        UaVariableNode n1 = mockVarNode("Tag1", 10);
        UaVariableNode n2 = mockVarNode("Tag2", "hello");
        when(nodeManager.getNodes()).thenReturn(List.of(n1, n2));

        Map<String, Object> values = dispatcher.getAllTagValues();

        assertThat(values).containsEntry("Tag1", 10).containsEntry("Tag2", "hello");
    }

    @Test
    @DisplayName("getAllTagValues() ignores non-variable nodes")
    void getAllSkipsNonVariableNodes() {
        UaVariableNode v = mockVarNode("Var", 1);
        when(nodeManager.getNodes()).thenReturn(List.of(v, nonVariableNode));

        Map<String, Object> values = dispatcher.getAllTagValues();

        assertThat(values).hasSize(1).containsKey("Var");
    }

    @Test
    @DisplayName("getAllTagValues() returns empty map when nodeManager throws")
    void getAllSwallowsTopLevelException() {
        when(nodeManager.getNodes()).thenThrow(new RuntimeException("kaboom"));

        Map<String, Object> values = dispatcher.getAllTagValues();

        assertThat(values).isEmpty();
    }

    private UaVariableNode mockVarNode(String identifier, Object value) {
        UaVariableNode n = org.mockito.Mockito.mock(UaVariableNode.class);
        NodeId id = new NodeId(2, identifier);
        when(n.getNodeId()).thenReturn(id);
        lenient().when(n.getBrowseName()).thenReturn(new QualifiedName(2, identifier));
        when(n.getValue()).thenReturn(new DataValue(new Variant(value)));
        return n;
    }
}
