package com.inductiveautomation.logixemulator.gateway.device;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.logixemulator.gateway.OpcUaSimulationEngine;
import org.eclipse.milo.opcua.sdk.server.NodeManager;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNodeContext;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.ULong;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the independent-review finding that {@link OpcUaSimulationEngine} never
 * simulated the v32+ unsigned atomics (USINT/UINT/UDINT/ULINT, mapped by C6 to Milo's
 * UByte/UShort/UInteger/ULong wrappers). Before the fix those wrappers matched none of
 * {@code calculateValue}'s signed {@code Short/Integer/Long} branches and fell through to the
 * "return current value unchanged" path — so enabling RAMP on an unsigned tag reported success
 * but the value stayed frozen, exactly like the B3 defect but scoped to unsigned types.
 *
 * <p>Each test builds a real address space via {@link AddressSpaceBuilder} (so the node holds a
 * genuine unsigned wrapper value) and drives the engine through the same {@code key -> node}
 * resolver a real server observes.</p>
 */
class OpcUaSimulationEngineUnsignedTypeTest {

    private AddressSpaceBuilder builder;
    private AddressSpaceBuilder.NodeContext context;
    private DeviceContext deviceContext;
    private UaFolderNode rootNode;
    private List<UaNode> addedNodes;
    private Map<NodeId, UaNode> nodesByNodeId;
    private OpcUaSimulationEngine engine;

    @BeforeEach
    void setUp() {
        UaNodeContext uaNodeContext = mock(UaNodeContext.class);
        @SuppressWarnings("unchecked")
        NodeManager<UaNode> nodeManager = mock(NodeManager.class);
        OpcUaServer server = mock(OpcUaServer.class);
        when(uaNodeContext.getNodeManager()).thenReturn(nodeManager);
        when(uaNodeContext.getServer()).thenReturn(server);
        when(server.getNamespaceTable()).thenReturn(new NamespaceTable());

        deviceContext = mock(DeviceContext.class);
        when(deviceContext.nodeId(any())).thenAnswer(inv -> {
            Object identifier = inv.getArgument(0);
            return new NodeId(1, String.valueOf(identifier));
        });
        when(deviceContext.qualifiedName(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return new QualifiedName(1, name);
        });

        context = new AddressSpaceBuilder.NodeContext(uaNodeContext, deviceContext);
        rootNode = context.createFolder("TestDevice", "TestDevice");

        addedNodes = new ArrayList<>();
        builder = new AddressSpaceBuilder(
            addedNodes::add,
            "TestDevice",
            LoggerFactory.getLogger(OpcUaSimulationEngineUnsignedTypeTest.class)
        );

        JsonObject plcData = new JsonObject();
        JsonArray globalTags = new JsonArray();
        addAtomicTagJson(globalTags, "USintTag", "USINT");
        addAtomicTagJson(globalTags, "UIntTag", "UINT");
        addAtomicTagJson(globalTags, "UDintTag", "UDINT");
        addAtomicTagJson(globalTags, "ULintTag", "ULINT");
        plcData.add("global_tags", globalTags);
        builder.buildAddressSpace(plcData, rootNode, context);

        nodesByNodeId = new HashMap<>();
        for (UaNode node : addedNodes) {
            nodesByNodeId.put(node.getNodeId(), node);
        }
    }

    @AfterEach
    void tearDown() {
        if (engine != null && engine.isRunning()) {
            engine.stop();
        }
    }

    private static void addAtomicTagJson(JsonArray globalTags, String name, String dataType) {
        JsonObject tag = new JsonObject();
        tag.addProperty("name", name);
        tag.addProperty("data_type", dataType);
        globalTags.add(tag);
    }

    private Function<String, UaVariableNode> nodeResolver() {
        return key -> {
            UaNode node = nodesByNodeId.get(deviceContext.nodeId(key));
            return (node instanceof UaVariableNode v) ? v : null;
        };
    }

    private UaVariableNode variableNodeFor(String canonicalIdentifier) {
        return addedNodes.stream()
            .filter(UaVariableNode.class::isInstance)
            .map(UaVariableNode.class::cast)
            .filter(n -> canonicalIdentifier.equals(n.getNodeId().getIdentifier().toString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No variable node found for " + canonicalIdentifier));
    }

    private void startWithRamp(String tagKey) {
        engine = new OpcUaSimulationEngine(LogixEmulatorConfig.SimulationPattern.STATIC, 50, nodeResolver());
        engine.enableTagSimulation(tagKey, LogixEmulatorConfig.SimulationPattern.RAMP);
        engine.start();
    }

    @Test
    @DisplayName("RAMP on a USINT tag changes the value, keeps it a UByte, and stays within [0, 255]")
    void rampChangesUByteWithinBounds() {
        UaVariableNode node = variableNodeFor("USintTag");
        assertThat(node.getValue().getValue().getValue()).isInstanceOf(UByte.class).isEqualTo(UByte.valueOf(0));

        startWithRamp("USintTag");

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(node.getValue().getValue().getValue()).isNotEqualTo(UByte.valueOf(0)));

        Object v = node.getValue().getValue().getValue();
        assertThat(v).isInstanceOf(UByte.class);
        assertThat(((UByte) v).intValue()).isBetween(0, 255);
    }

    @Test
    @DisplayName("RAMP on a UINT tag changes the value, keeps it a UShort, and stays within [0, 65535]")
    void rampChangesUShortWithinBounds() {
        UaVariableNode node = variableNodeFor("UIntTag");
        assertThat(node.getValue().getValue().getValue()).isInstanceOf(UShort.class);

        startWithRamp("UIntTag");

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(node.getValue().getValue().getValue()).isNotEqualTo(UShort.valueOf(0)));

        Object v = node.getValue().getValue().getValue();
        assertThat(v).isInstanceOf(UShort.class);
        assertThat(((UShort) v).intValue()).isBetween(0, 65_535);
    }

    @Test
    @DisplayName("RAMP on a UDINT tag changes the value, keeps it a UInteger, and stays within [0, 2^32-1]")
    void rampChangesUIntegerWithinBounds() {
        UaVariableNode node = variableNodeFor("UDintTag");
        assertThat(node.getValue().getValue().getValue()).isInstanceOf(UInteger.class);

        startWithRamp("UDintTag");

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(node.getValue().getValue().getValue()).isNotEqualTo(UInteger.valueOf(0)));

        Object v = node.getValue().getValue().getValue();
        assertThat(v).isInstanceOf(UInteger.class);
        assertThat(((UInteger) v).longValue()).isBetween(0L, 4_294_967_295L);
    }

    @Test
    @DisplayName("RAMP on a ULINT tag changes the value, keeps it a ULong, and stays non-negative")
    void rampChangesULongWithinBounds() {
        UaVariableNode node = variableNodeFor("ULintTag");
        assertThat(node.getValue().getValue().getValue()).isInstanceOf(ULong.class);

        startWithRamp("ULintTag");

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(node.getValue().getValue().getValue()).isNotEqualTo(ULong.valueOf(0)));

        Object v = node.getValue().getValue().getValue();
        assertThat(v).isInstanceOf(ULong.class);
        // The engine caps the ULong simulation range at Long.MAX_VALUE, so longValue() stays
        // in [0, Long.MAX_VALUE] and never wraps negative.
        assertThat(((ULong) v).longValue()).isBetween(0L, Long.MAX_VALUE);
    }

    @Test
    @DisplayName("STATIC on a USINT tag holds the midpoint (127) and never drifts")
    void staticHoldsUByteMidpoint() {
        UaVariableNode node = variableNodeFor("USintTag");
        engine = new OpcUaSimulationEngine(LogixEmulatorConfig.SimulationPattern.RAMP, 50, nodeResolver());
        engine.enableTagSimulation("USintTag", LogixEmulatorConfig.SimulationPattern.STATIC);
        engine.start();

        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(node.getValue().getValue().getValue()).isEqualTo(UByte.valueOf(127)));

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(node.getValue().getValue().getValue())
            .as("STATIC must not drift")
            .isEqualTo(UByte.valueOf(127));
    }
}
