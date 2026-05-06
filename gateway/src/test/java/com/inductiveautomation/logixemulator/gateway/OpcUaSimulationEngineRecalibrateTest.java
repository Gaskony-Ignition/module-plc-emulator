package com.inductiveautomation.logixemulator.gateway;

import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression tests for C12 — Simulation overwrites user writes every tick.
 *
 * <p>Fix (Option B per FINAL_REVIEW): {@code recalibrate(tagPath, newBaseline)}
 * stores the user-written value as the new baseline of the simulation function.
 * RAMP continues from the new value, SINE re-phases around it, RANDOM
 * re-centres its band on it, STATIC holds it, TOGGLE seeds its phase from a
 * boolean baseline.</p>
 */
class OpcUaSimulationEngineRecalibrateTest {

    private OpcUaSimulationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new OpcUaSimulationEngine(
            LogixEmulatorConfig.SimulationPattern.STATIC,
            500,
            nodeId -> null
        );
    }

    @Test
    @DisplayName("C12: recalibrate() stores the new baseline for an enabled tag")
    void recalibrateStoresBaseline() {
        engine.enableTagSimulation("Controller:Global/Pump1");
        engine.recalibrate("Controller:Global/Pump1", 42.0);

        assertThat(engine.getBaseline("Controller:Global/Pump1")).isEqualTo(42.0);
    }

    @Test
    @DisplayName("C12: recalibrate() on a non-simulated tag is a no-op")
    void recalibrateOnUnsimulatedTagIsNoOp() {
        // Tag is *not* enabled for simulation
        engine.recalibrate("Controller:Global/NotSimulated", 99.0);

        assertThat(engine.getBaseline("Controller:Global/NotSimulated")).isNull();
    }

    @Test
    @DisplayName("C12: recalibrate(null) and recalibrate(\"\") are silent no-ops")
    void recalibrateNullOrEmptyDoesNotThrow() {
        assertThatCode(() -> engine.recalibrate(null, 1.0)).doesNotThrowAnyException();
        assertThatCode(() -> engine.recalibrate("", 1.0)).doesNotThrowAnyException();

        assertThat(engine.getBaseline(null)).isNull();
        assertThat(engine.getBaseline("")).isNull();
    }

    @Test
    @DisplayName("C12: recalibrate() works for all five simulation patterns (RAMP/SINE/RANDOM/TOGGLE/STATIC)")
    void recalibrateWorksForAllPatterns() {
        // RAMP
        engine.enableTagSimulation("Tag/Ramp", LogixEmulatorConfig.SimulationPattern.RAMP);
        engine.recalibrate("Tag/Ramp", 25.0);
        assertThat(engine.getBaseline("Tag/Ramp")).isEqualTo(25.0);

        // SINE
        engine.enableTagSimulation("Tag/Sine", LogixEmulatorConfig.SimulationPattern.SINE);
        engine.recalibrate("Tag/Sine", 50.0);
        assertThat(engine.getBaseline("Tag/Sine")).isEqualTo(50.0);

        // RANDOM
        engine.enableTagSimulation("Tag/Random", LogixEmulatorConfig.SimulationPattern.RANDOM);
        engine.recalibrate("Tag/Random", 75.0);
        assertThat(engine.getBaseline("Tag/Random")).isEqualTo(75.0);

        // TOGGLE
        engine.enableTagSimulation("Tag/Toggle", LogixEmulatorConfig.SimulationPattern.TOGGLE);
        engine.recalibrate("Tag/Toggle", true);
        assertThat(engine.getBaseline("Tag/Toggle")).isEqualTo(true);

        // STATIC
        engine.enableTagSimulation("Tag/Static", LogixEmulatorConfig.SimulationPattern.STATIC);
        engine.recalibrate("Tag/Static", 100.0);
        assertThat(engine.getBaseline("Tag/Static")).isEqualTo(100.0);
    }

    @Test
    @DisplayName("C12: a second recalibrate() overwrites the previous baseline")
    void recalibrateOverwritesPreviousBaseline() {
        engine.enableTagSimulation("Tag/Boiler");
        engine.recalibrate("Tag/Boiler", 10.0);
        assertThat(engine.getBaseline("Tag/Boiler")).isEqualTo(10.0);

        engine.recalibrate("Tag/Boiler", 20.0);
        assertThat(engine.getBaseline("Tag/Boiler")).isEqualTo(20.0);
    }

    @Test
    @DisplayName("C12: disableTagSimulation() clears the recorded baseline")
    void disableClearsBaseline() {
        engine.enableTagSimulation("Tag/Pump");
        engine.recalibrate("Tag/Pump", 33.0);
        assertThat(engine.getBaseline("Tag/Pump")).isEqualTo(33.0);

        engine.disableTagSimulation("Tag/Pump");
        assertThat(engine.getBaseline("Tag/Pump")).isNull();
    }

    @Test
    @DisplayName("C12: disableAllSimulation() clears all baselines")
    void disableAllClearsAllBaselines() {
        engine.enableTagSimulation("Tag/A");
        engine.enableTagSimulation("Tag/B");
        engine.recalibrate("Tag/A", 1.0);
        engine.recalibrate("Tag/B", 2.0);

        engine.disableAllSimulation();

        assertThat(engine.getBaseline("Tag/A")).isNull();
        assertThat(engine.getBaseline("Tag/B")).isNull();
    }

    @Test
    @DisplayName("C12: disableSimulationByScope() clears baselines for matched tags only")
    void disableScopeClearsScopedBaselines() {
        engine.enableTagSimulation("Controller:Global/X");
        engine.enableTagSimulation("Programs/Main/Y");
        engine.recalibrate("Controller:Global/X", 11.0);
        engine.recalibrate("Programs/Main/Y", 22.0);

        engine.disableSimulationByScope("Controller:Global");

        assertThat(engine.getBaseline("Controller:Global/X")).isNull();
        assertThat(engine.getBaseline("Programs/Main/Y")).isEqualTo(22.0);
    }
}
