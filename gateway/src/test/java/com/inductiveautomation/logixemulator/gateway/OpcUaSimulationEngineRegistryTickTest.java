package com.inductiveautomation.logixemulator.gateway;

import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorConfig;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Tick-loop robustness tests for {@link OpcUaSimulationEngine}, written against the v10
 * <b>registry-driven</b> tick (defect B3-v10).
 *
 * <p><b>Incident this replaces:</b> the previous {@code OpcUaSimulationEngineSupplierTest}
 * exercised a per-tick {@code Supplier<List<DataItem>>} the engine iterated. That whole
 * mechanism was the bug: on a real Milo server the device {@code SubscriptionModel} never
 * registers {@code DataItem}s for value-backed nodes, so the supplier returned an empty list
 * forever and no value ever changed — yet the supplier tests were green because the test
 * hand-fed the list. The engine now ticks over its own {@code simulatedTags} registry and
 * resolves each key to a live {@link UaVariableNode}; there is no {@code DataItem}/supplier
 * layer left to stub. These tests assert on the registry + resolver path that a real server
 * actually observes. Do NOT reintroduce a data-item supplier.</p>
 */
class OpcUaSimulationEngineRegistryTickTest {

    private OpcUaSimulationEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null && engine.isRunning()) {
            engine.stop();
        }
    }

    @Test
    @DisplayName("Registry tick: the resolver is invoked for every enabled tag on each tick")
    void resolverInvokedForEnabledTagsEachTick() {
        // Record which keys the engine asks the resolver to resolve — proof it iterates
        // its own registry, not any external data-item list.
        List<String> resolved = new CopyOnWriteArrayList<>();
        engine = new OpcUaSimulationEngine(
            LogixEmulatorConfig.SimulationPattern.STATIC,
            100,
            key -> {
                resolved.add(key);
                return null; // null resolution is fine — engine logs trace and skips
            }
        );
        engine.enableTagSimulation("Tag0");
        engine.enableTagSimulation("Tag1");
        engine.enableTagSimulation("Tag2");

        engine.start();

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(resolved).contains("Tag0", "Tag1", "Tag2")
        );
    }

    @Test
    @DisplayName("Registry tick: tags enabled/disabled mid-run are picked up/dropped without restart")
    void enableAndDisableMidRunWithoutRestart() {
        List<String> resolved = new CopyOnWriteArrayList<>();
        engine = new OpcUaSimulationEngine(
            LogixEmulatorConfig.SimulationPattern.STATIC,
            100,
            key -> {
                resolved.add(key);
                return null;
            }
        );
        engine.enableTagSimulation("Alpha");
        engine.start();

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(resolved).contains("Alpha")
        );

        // Enable a new tag and disable the old one mid-run.
        engine.enableTagSimulation("Beta");
        engine.disableTagSimulation("Alpha");
        resolved.clear();

        // Next ticks must resolve Beta and NOT Alpha — the live registry is read per tick.
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(resolved).contains("Beta");
            assertThat(resolved).doesNotContain("Alpha");
        });
    }

    @Test
    @DisplayName("Registry tick: an exception thrown by the resolver does not kill the scheduled task")
    void resolverExceptionDoesNotKillTask() {
        AtomicInteger invocations = new AtomicInteger(0);
        Function<String, UaVariableNode> throwingResolver = key -> {
            int n = invocations.incrementAndGet();
            if (n == 2) {
                throw new IllegalStateException("simulated resolver failure on invocation 2");
            }
            return null;
        };
        engine = new OpcUaSimulationEngine(
            LogixEmulatorConfig.SimulationPattern.STATIC,
            100,
            throwingResolver
        );
        engine.enableTagSimulation("Boom");
        engine.start();

        // The scheduled task must keep firing well past the failing invocation — earlier
        // designs let a thrown exception cancel the ScheduledFuture forever.
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(invocations.get()).isGreaterThanOrEqualTo(5)
        );
        assertThat(engine.isRunning()).isTrue();
    }

    @Test
    @DisplayName("Registry tick: with no tags enabled the engine still ticks (elapsed advances) and stays running")
    void noTagsEnabledStillTicks() {
        engine = new OpcUaSimulationEngine(
            LogixEmulatorConfig.SimulationPattern.STATIC,
            100,
            key -> null
        );
        engine.start();

        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(engine.getElapsedSeconds()).isGreaterThan(0.0)
        );
        assertThat(engine.isRunning()).isTrue();
    }

    @Test
    @DisplayName("Registry tick: stop() then start() restarts cleanly with a fresh resolver")
    void stopThenRestartWorks() {
        engine = new OpcUaSimulationEngine(
            LogixEmulatorConfig.SimulationPattern.STATIC,
            100,
            key -> null
        );
        engine.start();
        assertThat(engine.isRunning()).isTrue();

        engine.stop();
        assertThat(engine.isRunning()).isFalse();

        engine.start();
        assertThat(engine.isRunning()).isTrue();
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(engine.getElapsedSeconds()).isGreaterThan(0.0)
        );
    }
}
