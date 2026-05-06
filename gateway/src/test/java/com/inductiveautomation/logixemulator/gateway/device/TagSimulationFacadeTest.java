package com.inductiveautomation.logixemulator.gateway.device;

import com.inductiveautomation.logixemulator.gateway.OpcUaSimulationEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TagSimulationFacade} — the dozen pass-through tag-
 * simulation methods extracted from {@code LogixEmulatorDevice} during the
 * Sprint 3 P6 god-class refactor. Each test asserts the facade either
 * delegates to the engine or returns a safe default when no engine is
 * present.
 */
@ExtendWith(MockitoExtension.class)
class TagSimulationFacadeTest {

    @Mock OpcUaSimulationEngine engine;

    Supplier<Set<String>> tagPathsSupplier;
    TagSimulationFacade facade;

    @BeforeEach
    void setUp() {
        tagPathsSupplier = () -> Set.of("Controller:Global/A", "Controller:Global/B");
        facade = new TagSimulationFacade(() -> engine, tagPathsSupplier);
    }

    @Test
    @DisplayName("constructor rejects null engine supplier")
    void constructorRejectsNullEngineSupplier() {
        assertThatThrownBy(() -> new TagSimulationFacade(null, tagPathsSupplier))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constructor rejects null tag-paths supplier")
    void constructorRejectsNullTagPathsSupplier() {
        assertThatThrownBy(() -> new TagSimulationFacade(() -> engine, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // No-engine branches — every method must return a safe default
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("when engine is null, every method returns a safe default")
    void noEngineSafeDefaults() {
        TagSimulationFacade nullFacade = new TagSimulationFacade(() -> null, tagPathsSupplier);

        assertThat(nullFacade.enableTagSimulation("a")).isFalse();
        assertThat(nullFacade.enableTagSimulation("a", "ramp")).isFalse();
        assertThat(nullFacade.disableTagSimulation("a")).isFalse();
        assertThat(nullFacade.toggleTagSimulation("a")).isFalse();
        assertThat(nullFacade.isTagSimulated("a")).isFalse();
        assertThat(nullFacade.getSimulatedTags()).isEmpty();
        assertThat(nullFacade.getTagSimulationPattern("a")).isEqualTo("static");
        assertThat(nullFacade.isSimulationEngineAvailable()).isFalse();
        assertThat(nullFacade.getSimulatedTagCount()).isZero();
        nullFacade.enableSimulationByScope("scope");
        nullFacade.disableSimulationByScope("scope");
        nullFacade.enableAllSimulation();
        nullFacade.disableAllSimulation();
    }

    // -------------------------------------------------------------------------
    // Delegation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enableTagSimulation(path) delegates to engine and returns true")
    void enableSingleArg() {
        assertThat(facade.enableTagSimulation("MyTag")).isTrue();
        verify(engine).enableTagSimulation("MyTag");
    }

    @Test
    @DisplayName("enableTagSimulation(path, pattern) maps key and delegates")
    void enableTwoArgValid() {
        assertThat(facade.enableTagSimulation("MyTag", "ramp")).isTrue();
        verify(engine).enableTagSimulation(
            eq("MyTag"),
            eq(LogixEmulatorConfig.SimulationPattern.fromKey("ramp"))
        );
    }

    @Test
    @DisplayName("enableTagSimulation(path, pattern) uses SINE fallback for unknown key (per fromKey contract)")
    void enableTwoArgInvalidPatternUsesFallback() {
        // SimulationPattern.fromKey() returns SINE for unknown keys — it
        // doesn't throw — so the facade still enables with SINE pattern.
        // This matches the existing behaviour the device used to have.
        assertThat(facade.enableTagSimulation("MyTag", "not-a-pattern")).isTrue();
        verify(engine, times(1)).enableTagSimulation(
            eq("MyTag"),
            eq(LogixEmulatorConfig.SimulationPattern.SINE)
        );
    }

    @Test
    @DisplayName("disableTagSimulation() delegates to engine and returns true")
    void disable() {
        assertThat(facade.disableTagSimulation("MyTag")).isTrue();
        verify(engine).disableTagSimulation("MyTag");
    }

    @Test
    @DisplayName("toggleTagSimulation() forwards engine result")
    void toggleForwards() {
        when(engine.toggleTagSimulation("MyTag")).thenReturn(true);

        Boolean result = facade.toggleTagSimulation("MyTag");

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isTagSimulated() forwards engine result")
    void isSimulatedForwards() {
        when(engine.isTagSimulated("X")).thenReturn(true);
        when(engine.isTagSimulated("Y")).thenReturn(false);

        assertThat(facade.isTagSimulated("X")).isTrue();
        assertThat(facade.isTagSimulated("Y")).isFalse();
    }

    @Test
    @DisplayName("getSimulatedTags() forwards engine result")
    void getSimulatedTagsForwards() {
        when(engine.getSimulatedTags()).thenReturn(Set.of("foo", "bar"));

        assertThat(facade.getSimulatedTags()).containsExactlyInAnyOrder("foo", "bar");
    }

    @Test
    @DisplayName("getTagSimulationPattern() forwards engine result as the pattern key")
    void getTagPatternForwards() {
        when(engine.getTagPattern("MyTag")).thenReturn(LogixEmulatorConfig.SimulationPattern.SINE);

        assertThat(facade.getTagSimulationPattern("MyTag")).isEqualTo("sine");
    }

    @Test
    @DisplayName("isSimulationEngineAvailable() reflects engine.isRunning()")
    void engineAvailability() {
        when(engine.isRunning()).thenReturn(true);
        assertThat(facade.isSimulationEngineAvailable()).isTrue();

        when(engine.isRunning()).thenReturn(false);
        assertThat(facade.isSimulationEngineAvailable()).isFalse();
    }

    @Test
    @DisplayName("getSimulatedTagCount() forwards engine count")
    void getSimulatedTagCountForwards() {
        when(engine.getSimulatedTagCount()).thenReturn(7);

        assertThat(facade.getSimulatedTagCount()).isEqualTo(7);
    }

    @Test
    @DisplayName("enableSimulationByScope() passes the supplier-provided tag paths")
    void enableScopePassesPaths() {
        facade.enableSimulationByScope("Controller:Global");

        verify(engine).enableSimulationByScope(eq("Controller:Global"), eq(tagPathsSupplier.get()));
    }

    @Test
    @DisplayName("disableSimulationByScope() delegates to engine")
    void disableScopeDelegates() {
        facade.disableSimulationByScope("Controller:Global");

        verify(engine).disableSimulationByScope("Controller:Global");
    }

    @Test
    @DisplayName("enableAllSimulation() passes the supplier-provided tag paths")
    void enableAllPassesPaths() {
        facade.enableAllSimulation();

        verify(engine).enableAllSimulation(eq(tagPathsSupplier.get()));
    }

    @Test
    @DisplayName("disableAllSimulation() delegates to engine")
    void disableAllDelegates() {
        facade.disableAllSimulation();

        verify(engine).disableAllSimulation();
    }

    @Test
    @DisplayName("engine supplier is consulted on every call (engine recreate is observed)")
    void supplierConsultedPerCall() {
        // Simulate the simulation engine being recreated after a hot-reload.
        OpcUaSimulationEngine first = engine;
        OpcUaSimulationEngine second = org.mockito.Mockito.mock(OpcUaSimulationEngine.class);
        OpcUaSimulationEngine[] holder = { first };

        TagSimulationFacade f = new TagSimulationFacade(() -> holder[0], tagPathsSupplier);

        f.disableTagSimulation("X");
        verify(first).disableTagSimulation("X");

        holder[0] = second;
        f.disableTagSimulation("Y");
        verify(second).disableTagSimulation("Y");
        // Original engine never sees the second call.
        verify(first, never()).disableTagSimulation("Y");
    }

    @Test
    @DisplayName("tag-paths supplier is not invoked for engine-only calls (no wasted scan)")
    void tagPathsSupplierUnusedWhenNotNeeded() {
        Supplier<Set<String>> paths = org.mockito.Mockito.mock(Supplier.class);
        lenient().when(paths.get()).thenReturn(Set.of());

        TagSimulationFacade f = new TagSimulationFacade(() -> engine, paths);
        f.disableTagSimulation("X");
        f.toggleTagSimulation("X");
        f.isTagSimulated("X");

        verifyNoInteractions(paths);
    }
}
