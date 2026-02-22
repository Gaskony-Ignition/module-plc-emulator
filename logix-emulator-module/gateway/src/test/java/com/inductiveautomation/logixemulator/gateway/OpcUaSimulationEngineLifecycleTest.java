package com.inductiveautomation.logixemulator.gateway;

import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorConfig;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * Lifecycle tests for OpcUaSimulationEngine.
 * Exercises start()/stop() behaviour — no OPC-UA runtime required because the
 * engine is started with an empty DataItem list and a no-op nodeLookup.
 */
class OpcUaSimulationEngineLifecycleTest {

    private OpcUaSimulationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new OpcUaSimulationEngine(
            LogixEmulatorConfig.SimulationPattern.STATIC,
            100,        // 100 ms interval — fast enough for tests
            nodeId -> null  // no-op node lookup
        );
    }

    @AfterEach
    void tearDown() {
        // Ensure engine is stopped after each test even if the test fails
        if (engine.isRunning()) {
            engine.stop();
        }
    }

    // -------------------------------------------------------------------------
    // 1. start() → isRunning() true, getElapsedSeconds() > 0
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("start() sets isRunning() to true and getElapsedSeconds() becomes positive")
    void testStartSetsRunning() {
        engine.start(List.of());

        assertThat(engine.isRunning()).isTrue();

        // Wait for the clock to advance
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(engine.getElapsedSeconds()).isGreaterThan(0.0)
        );
    }

    // -------------------------------------------------------------------------
    // 2. stop() after start → isRunning() false, getElapsedSeconds() == 0
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("stop() after start sets isRunning() false and resets getElapsedSeconds() to 0")
    void testStopResetsState() {
        engine.start(List.of());

        // Wait for the engine to accumulate some elapsed time
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(engine.getElapsedSeconds()).isGreaterThan(0.0)
        );

        engine.stop();

        assertThat(engine.isRunning()).isFalse();
        assertThat(engine.getElapsedSeconds()).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // 3. Double start() → second call is a no-op (still running, no exception)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Calling start() twice is a no-op — engine stays running, no exception thrown")
    void testDoubleStartIsNoOp() {
        engine.start(List.of());
        assertThat(engine.isRunning()).isTrue();

        // Second start() must not throw and engine must still be running
        assertThatCode(() -> engine.start(List.of())).doesNotThrowAnyException();
        assertThat(engine.isRunning()).isTrue();
    }

    // -------------------------------------------------------------------------
    // 4. stop() while stopped → no-op, no exception
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Calling stop() on an already-stopped engine is a no-op — no exception thrown")
    void testStopWhileStoppedIsNoOp() {
        assertThat(engine.isRunning()).isFalse();
        assertThatCode(() -> engine.stop()).doesNotThrowAnyException();
        assertThat(engine.isRunning()).isFalse();
    }

    // -------------------------------------------------------------------------
    // 5. getElapsedSeconds() increases over time while running
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("getElapsedSeconds() increases monotonically while the engine is running")
    void testElapsedSecondsIncreasesOverTime() {
        engine.start(List.of());

        double first = engine.getElapsedSeconds();

        // Wait until the elapsed time exceeds the initial reading
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(engine.getElapsedSeconds()).isGreaterThan(first)
        );
    }
}
