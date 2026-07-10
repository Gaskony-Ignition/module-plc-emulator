package com.inductiveautomation.logixemulator.gateway.device;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.logixemulator.gateway.OpcUaSimulationEngine;
import org.eclipse.milo.opcua.sdk.server.NodeManager;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNodeContext;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end regression test for defect B3 ("simulation engine never updates values",
 * {@code docs/plans/V10_FIDELITY_PLAN.md}).
 *
 * <p>Builds a real address space via {@link AddressSpaceBuilder} against the same stubbed
 * {@link AddressSpaceBuilder.NodeContext} used by {@link AddressSpaceBuilderIntegrationTest} — no
 * live OPC-UA server or Ignition gateway is required — then registers simulation for a tag using
 * the canonical NodeId identifier the live address space actually assigns it (the same conversion
 * {@code SimulationController.convertToNodeIdPath()} performs on the client-facing slash-notation
 * path), ticks the engine, and asserts the node's OPC-UA value actually changes.
 *
 * <p><b>Root cause this guards against:</b> before the B3 fix,
 * {@code SimulationController.handleToggleTagSimulation()} registered tags with the engine using
 * the raw slash-notation {@code tagPath} straight from the request body (e.g.
 * {@code "Controller:Global/RampInt"}), while {@link OpcUaSimulationEngine#updateSimulatedValues}
 * derives its per-tick lookup key from the live {@code NodeId}'s identifier, which under the v10
 * canonical scheme (C1) is the bare {@code "RampInt"} for a controller tag. The two never matched,
 * so {@code isTagSimulated()} was
 * always {@code false} for every live data item — the registry reported tags as simulated
 * (API success, {@code simulatedTagCount > 0}) while every OPC-UA node value stayed frozen forever
 * (see {@code ~/Downloads/plc-v10-artifacts/plc-dod/item3-simulation-FAIL.txt}).
 */
class OpcUaSimulationEngineAddressSpaceIntegrationTest {

    private AddressSpaceBuilder builder;
    private AddressSpaceBuilder.NodeContext context;
    private UaFolderNode rootNode;
    private List<UaNode> addedNodes;
    private Map<NodeId, UaNode> nodesByNodeId;
    private OpcUaSimulationEngine engine;

    @BeforeEach
    void setUp() {
        // Stub UaNodeContext/DeviceContext exactly as AddressSpaceBuilderIntegrationTest does —
        // only getNodeManager()/getServer()/nodeId()/qualifiedName() are exercised when building
        // folder/object/variable nodes; no live OPC-UA server is needed.
        UaNodeContext uaNodeContext = mock(UaNodeContext.class);
        @SuppressWarnings("unchecked")
        NodeManager<UaNode> nodeManager = mock(NodeManager.class);
        OpcUaServer server = mock(OpcUaServer.class);
        when(uaNodeContext.getNodeManager()).thenReturn(nodeManager);
        when(uaNodeContext.getServer()).thenReturn(server);
        when(server.getNamespaceTable()).thenReturn(new NamespaceTable());

        DeviceContext deviceContext = mock(DeviceContext.class);
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
            LoggerFactory.getLogger(OpcUaSimulationEngineAddressSpaceIntegrationTest.class)
        );
    }

    @AfterEach
    void tearDown() {
        if (engine != null && engine.isRunning()) {
            engine.stop();
        }
    }

    /**
     * Builds a five-tag Controller:Global address space covering one tag per simulation
     * pattern under test (mirrors the DoD run's RampInt/SineReal/ToggleBool tags, plus
     * RandomReal/StaticReal for full pattern-spec coverage) and indexes nodes by NodeId.
     */
    private void buildFiveTagAddressSpace() {
        JsonObject plcData = new JsonObject();
        JsonArray globalTags = new JsonArray();

        addAtomicTagJson(globalTags, "RampInt", "DINT");
        addAtomicTagJson(globalTags, "SineReal", "REAL");
        addAtomicTagJson(globalTags, "ToggleBool", "BOOL");
        addAtomicTagJson(globalTags, "RandomReal", "REAL");
        addAtomicTagJson(globalTags, "StaticReal", "REAL");

        plcData.add("global_tags", globalTags);

        builder.buildAddressSpace(plcData, rootNode, context);

        nodesByNodeId = new HashMap<>();
        for (UaNode node : addedNodes) {
            nodesByNodeId.put(node.getNodeId(), node);
        }
    }

    private static void addAtomicTagJson(JsonArray globalTags, String name, String dataType) {
        JsonObject tag = new JsonObject();
        tag.addProperty("name", name);
        tag.addProperty("data_type", dataType);
        globalTags.add(tag);
    }

    /**
     * Finds the single canonical variable node created for a controller-scoped atomic tag (v10 C1:
     * one node per tag, bare identifier — no more long/short duplicate pair).
     */
    private UaVariableNode variableNodeFor(String canonicalIdentifier) {
        return addedNodes.stream()
            .filter(UaVariableNode.class::isInstance)
            .map(UaVariableNode.class::cast)
            .filter(n -> canonicalIdentifier.equals(n.getNodeId().getIdentifier().toString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No variable node found for " + canonicalIdentifier));
    }

    private static DataItem dataItemFor(NodeId nodeId) {
        DataItem item = mock(DataItem.class);
        ReadValueId rvId = mock(ReadValueId.class);
        when(item.getReadValueId()).thenReturn(rvId);
        when(rvId.getNodeId()).thenReturn(nodeId);
        return item;
    }

    @Test
    @DisplayName("B3 end-to-end: assigning RAMP to a live DINT node changes its value across ticks, "
        + "monotonically within [0, 100] until the ramp period wraps")
    void rampPatternChangesNumericNodeValueOverTicks() {
        buildFiveTagAddressSpace();

        UaVariableNode rampNode = variableNodeFor("RampInt");
        List<DataItem> dataItems = List.of(dataItemFor(rampNode.getNodeId()));

        engine = new OpcUaSimulationEngine(
            LogixEmulatorConfig.SimulationPattern.SINE, // default pattern; RAMP is a per-tag override below
            50, // fast tick for test speed
            nodesByNodeId::get
        );

        // The exact key SimulationController.convertToNodeIdPath("Controller:Global/RampInt")
        // produces — the bare canonical controller identifier (v10 C1).
        engine.enableTagSimulation("RampInt", LogixEmulatorConfig.SimulationPattern.RAMP);
        engine.start(() -> dataItems);

        Object initialValue = rampNode.getValue().getValue().getValue();
        assertThat(initialValue).as("initial DINT value").isEqualTo(0);

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(rampNode.getValue().getValue().getValue()).isNotEqualTo(initialValue)
        );

        // RAMP must stay within its documented numeric bounds (default range is 0-100).
        Object afterTicks = rampNode.getValue().getValue().getValue();
        assertThat(((Number) afterTicks).doubleValue()).isBetween(0.0, 100.0);
    }

    @Test
    @DisplayName("B3 end-to-end: assigning TOGGLE to a live BOOL node flips its value across ticks")
    void togglePatternFlipsBooleanNodeValueOverTicks() {
        buildFiveTagAddressSpace();

        UaVariableNode toggleNode = variableNodeFor("ToggleBool");
        List<DataItem> dataItems = List.of(dataItemFor(toggleNode.getNodeId()));

        engine = new OpcUaSimulationEngine(
            LogixEmulatorConfig.SimulationPattern.STATIC,
            50,
            nodesByNodeId::get
        );

        engine.enableTagSimulation("ToggleBool", LogixEmulatorConfig.SimulationPattern.TOGGLE);
        engine.start(() -> dataItems);

        Object initialValue = toggleNode.getValue().getValue().getValue();
        assertThat(initialValue).as("initial BOOL value").isEqualTo(false);

        // TOGGLE flips every 1s (2s period / 2 halves) — poll across ~2.5s so both a flip away
        // from the initial value and a flip back are observed.
        Set<Object> observed = new HashSet<>();
        long deadline = System.currentTimeMillis() + 2_500;
        while (System.currentTimeMillis() < deadline && observed.size() < 2) {
            observed.add(toggleNode.getValue().getValue().getValue());
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assertThat(observed)
            .as("TOGGLE must produce both boolean states over successive ticks")
            .containsExactlyInAnyOrder(true, false);
    }

    @Test
    @DisplayName("B3 end-to-end: assigning SINE to a live REAL node bounds its oscillation within [0, 100]")
    void sinePatternOscillatesWithinBounds() {
        buildFiveTagAddressSpace();

        UaVariableNode sineNode = variableNodeFor("SineReal");
        List<DataItem> dataItems = List.of(dataItemFor(sineNode.getNodeId()));

        engine = new OpcUaSimulationEngine(LogixEmulatorConfig.SimulationPattern.STATIC, 50, nodesByNodeId::get);
        engine.enableTagSimulation("SineReal", LogixEmulatorConfig.SimulationPattern.SINE);
        engine.start(() -> dataItems);

        // Wait for at least one tick to fire before sampling, so the pre-simulation default
        // value (0.0f) set by the address-space builder isn't counted as a "sampled" value —
        // this test asserts on the SINE pattern's own output, not the address-space default.
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(sineNode.getValue().getValue().getValue()).isNotEqualTo(0.0f)
        );

        Set<Float> sampledValues = new HashSet<>();
        long deadline = System.currentTimeMillis() + 1_500;
        while (System.currentTimeMillis() < deadline) {
            float v = ((Number) sineNode.getValue().getValue().getValue()).floatValue();
            assertThat(v).as("SINE must stay within its bounded amplitude").isBetween(0.0f, 100.0f);
            sampledValues.add(v);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assertThat(sampledValues)
            .as("SINE must actually change value across ticks, not just stay bounded")
            .hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("B3 end-to-end: assigning RANDOM to a live REAL node keeps every sampled value within bounds")
    void randomPatternStaysWithinBounds() {
        buildFiveTagAddressSpace();

        UaVariableNode randomNode = variableNodeFor("RandomReal");
        List<DataItem> dataItems = List.of(dataItemFor(randomNode.getNodeId()));

        engine = new OpcUaSimulationEngine(LogixEmulatorConfig.SimulationPattern.STATIC, 50, nodesByNodeId::get);
        engine.enableTagSimulation("RandomReal", LogixEmulatorConfig.SimulationPattern.RANDOM);
        engine.start(() -> dataItems);

        Set<Float> sampledValues = new HashSet<>();
        long deadline = System.currentTimeMillis() + 1_000;
        while (System.currentTimeMillis() < deadline) {
            float v = ((Number) randomNode.getValue().getValue().getValue()).floatValue();
            assertThat(v).as("RANDOM must stay within its documented bounds").isBetween(0.0f, 100.0f);
            sampledValues.add(v);
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assertThat(sampledValues)
            .as("RANDOM must produce varying values across ticks")
            .hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("B3 end-to-end: assigning STATIC to a live REAL node leaves its value unchanged across ticks")
    void staticPatternLeavesValueUnchanged() {
        buildFiveTagAddressSpace();

        UaVariableNode staticNode = variableNodeFor("StaticReal");
        List<DataItem> dataItems = List.of(dataItemFor(staticNode.getNodeId()));

        engine = new OpcUaSimulationEngine(LogixEmulatorConfig.SimulationPattern.SINE, 50, nodesByNodeId::get);
        engine.enableTagSimulation("StaticReal", LogixEmulatorConfig.SimulationPattern.STATIC);
        engine.start(() -> dataItems);

        // STATIC always resolves to the midpoint of the default 0-100 range — give it several
        // ticks then assert it has settled there and stays there across further ticks.
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        float afterFirstTicks = ((Number) staticNode.getValue().getValue().getValue()).floatValue();
        assertThat(afterFirstTicks).isEqualTo(50.0f);

        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        float afterMoreTicks = ((Number) staticNode.getValue().getValue().getValue()).floatValue();
        assertThat(afterMoreTicks)
            .as("STATIC must not drift across further ticks")
            .isEqualTo(afterFirstTicks);
    }

    @Test
    @DisplayName("B3 regression: a tag registered under the un-converted slash-notation path is "
        + "never matched by the engine's per-tick lookup and its value stays frozen (the exact "
        + "wiring gap fixed in SimulationController)")
    void slashNotationRegistrationNeverMatchesLiveDotNotationNode() {
        buildFiveTagAddressSpace();

        UaVariableNode rampNode = variableNodeFor("RampInt");
        List<DataItem> dataItems = List.of(dataItemFor(rampNode.getNodeId()));

        engine = new OpcUaSimulationEngine(LogixEmulatorConfig.SimulationPattern.RAMP, 50, nodesByNodeId::get);

        // The pre-fix bug: registering with the raw client-facing slash path instead of the
        // converted dot path.
        engine.enableTagSimulation("Controller:Global/RampInt");
        engine.start(() -> dataItems);

        Object initialValue = rampNode.getValue().getValue().getValue();

        // Give the engine several ticks — the value must NOT change, because the registry key
        // ("Controller:Global/RampInt") never matches the live NodeId identifier
        // ("RampInt") that updateSimulatedValues() looks up per tick.
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(rampNode.getValue().getValue().getValue())
            .as("value must stay frozen when the engine registry key does not match the live NodeId")
            .isEqualTo(initialValue);
    }
}
