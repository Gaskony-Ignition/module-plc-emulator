package com.inductiveautomation.logixemulator.gateway;

import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorConfig;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for known issue #14: with simulate-all on RAMP every BOOL member of a UDT
 * toggled in step (a pump read Fault and Running true at once), because every BOOL shared one
 * 2 s square wave. Tags on the default pattern now get a per-tag period and phase from the tag
 * path; tags given their own pattern keep the old shared toggle.
 */
class OpcUaSimulationEngineBoolStaggerTest {

    private static final List<String> PUMP_BOOLS = List.of(
        "Pump1.Running", "Pump1.Fault", "Pump1.Auto", "Pump1.Manual",
        "Pump1.Interlock", "Pump1.RunTimer.EN", "Pump1.RunTimer.TT", "Pump1.RunTimer.DN");

    private OpcUaSimulationEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null && engine.isRunning()) {
            engine.stop();
        }
    }

    /** A variable node that holds its value in memory, as a real value-backed node does. */
    private static UaVariableNode boolNode(AtomicReference<DataValue> holder) {
        UaVariableNode node = mock(UaVariableNode.class);
        when(node.getValue()).thenAnswer(inv -> holder.get());
        doAnswer(inv -> {
            holder.set(inv.getArgument(0));
            return null;
        }).when(node).setValue(any(DataValue.class));
        return node;
    }

    private Map<String, AtomicReference<DataValue>> startEngine(boolean explicitToggle) {
        Map<String, AtomicReference<DataValue>> values = new LinkedHashMap<>();
        Map<String, UaVariableNode> nodes = new LinkedHashMap<>();
        for (String tag : PUMP_BOOLS) {
            AtomicReference<DataValue> holder = new AtomicReference<>(new DataValue(new Variant(false)));
            values.put(tag, holder);
            nodes.put(tag, boolNode(holder));
        }
        engine = new OpcUaSimulationEngine(LogixEmulatorConfig.SimulationPattern.RAMP, 100, nodes::get);
        if (explicitToggle) {
            PUMP_BOOLS.forEach(tag ->
                engine.enableTagSimulation(tag, LogixEmulatorConfig.SimulationPattern.TOGGLE));
        } else {
            engine.enableAllSimulation(new HashSet<>(PUMP_BOOLS));
        }
        engine.start();
        return values;
    }

    private static Set<Object> distinctValues(Map<String, AtomicReference<DataValue>> values) {
        Set<Object> distinct = new HashSet<>();
        values.values().forEach(v -> distinct.add(v.get().getValue().getValue()));
        return distinct;
    }

    @Test
    @DisplayName("Regression #14: simulate-all RAMP does not drive every BOOL of one UDT in step")
    void simulateAllBoolsDoNotMoveTogether() {
        Map<String, AtomicReference<DataValue>> values = startEngine(false);

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(distinctValues(values))
                .as("BOOL members after a simulate-all tick")
                .containsExactlyInAnyOrder(true, false));
    }

    @Test
    @DisplayName("Explicit per-tag TOGGLE keeps the shared 2 s toggle it always had")
    void explicitTogglePatternUnchanged() {
        Map<String, AtomicReference<DataValue>> values = startEngine(true);

        // All eight read TRUE together: they still share one toggle.
        await().atMost(2, TimeUnit.SECONDS).until(() ->
            values.values().stream().allMatch(v -> Boolean.TRUE.equals(v.get().getValue().getValue())));
    }

    @Test
    @DisplayName("Staggered BOOL is deterministic per tag and differs between tags over a sample")
    void staggeredValueIsDeterministicAndVaries() {
        int differ = 0;
        for (int i = 0; i < 200; i++) {
            double t = i * 0.25;
            boolean running = OpcUaSimulationEngine.staggeredBooleanValue("Pump1.Running", t);
            boolean fault = OpcUaSimulationEngine.staggeredBooleanValue("Pump1.Fault", t);
            assertThat(OpcUaSimulationEngine.staggeredBooleanValue("Pump1.Running", t)).isEqualTo(running);
            if (running != fault) {
                differ++;
            }
        }
        assertThat(differ).as("samples where Running and Fault differ").isBetween(20, 180);
    }
}
