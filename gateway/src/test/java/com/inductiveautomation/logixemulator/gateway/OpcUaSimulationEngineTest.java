package com.inductiveautomation.logixemulator.gateway;

import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorConfig;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.junit.jupiter.api.*;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for OpcUaSimulationEngine.
 * All tests exercise the per-tag simulation control API without starting the engine,
 * so no OPC-UA nodes or live scheduling are needed.
 */
class OpcUaSimulationEngineTest {

    private OpcUaSimulationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new OpcUaSimulationEngine(
            LogixEmulatorConfig.SimulationPattern.STATIC,
            500,
            nodeId -> null
        );
    }

    // -------------------------------------------------------------------------
    // 1. isRunning() is false after construction
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("isRunning() returns false after construction")
    void testIsRunningFalseAfterConstruction() {
        assertThat(engine.isRunning()).isFalse();
    }

    // -------------------------------------------------------------------------
    // 2. getElapsedSeconds() returns 0.0 when not running
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("getElapsedSeconds() returns 0.0 when engine is not running")
    void testElapsedSecondsZeroWhenNotRunning() {
        assertThat(engine.getElapsedSeconds()).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // 3. enableTagSimulation(path) → isTagSimulated returns true
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("enableTagSimulation() causes isTagSimulated() to return true")
    void testEnableTagSimulation() {
        engine.enableTagSimulation("Controller:Global/MyTag");
        assertThat(engine.isTagSimulated("Controller:Global/MyTag")).isTrue();
    }

    // -------------------------------------------------------------------------
    // 4. enableTagSimulation with null/empty path → no crash, isTagSimulated false
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("enableTagSimulation(null) does not throw and tag is not simulated")
    void testEnableTagSimulationNull() {
        assertThatCode(() -> engine.enableTagSimulation((String) null)).doesNotThrowAnyException();
        assertThat(engine.isTagSimulated(null)).isFalse();
    }

    @Test
    @DisplayName("enableTagSimulation(\"\") does not throw and empty path is not simulated")
    void testEnableTagSimulationEmpty() {
        assertThatCode(() -> engine.enableTagSimulation("")).doesNotThrowAnyException();
        assertThat(engine.isTagSimulated("")).isFalse();
    }

    // -------------------------------------------------------------------------
    // 5. disableTagSimulation → isTagSimulated returns false
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("disableTagSimulation() causes isTagSimulated() to return false")
    void testDisableTagSimulation() {
        engine.enableTagSimulation("Controller:Global/TagA");
        assertThat(engine.isTagSimulated("Controller:Global/TagA")).isTrue();

        engine.disableTagSimulation("Controller:Global/TagA");
        assertThat(engine.isTagSimulated("Controller:Global/TagA")).isFalse();
    }

    // -------------------------------------------------------------------------
    // 6. toggleTagSimulation: enable → disable → enable cycle, return values
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("toggleTagSimulation() cycles through enable/disable states with correct return values")
    void testToggleTagSimulationCycle() {
        String tag = "Controller:Global/Toggle";

        // Initially not simulated — toggle enables it
        boolean afterFirstToggle = engine.toggleTagSimulation(tag);
        assertThat(afterFirstToggle).isTrue();
        assertThat(engine.isTagSimulated(tag)).isTrue();

        // Toggle again — disables it
        boolean afterSecondToggle = engine.toggleTagSimulation(tag);
        assertThat(afterSecondToggle).isFalse();
        assertThat(engine.isTagSimulated(tag)).isFalse();

        // Toggle once more — enables it again
        boolean afterThirdToggle = engine.toggleTagSimulation(tag);
        assertThat(afterThirdToggle).isTrue();
        assertThat(engine.isTagSimulated(tag)).isTrue();
    }

    // -------------------------------------------------------------------------
    // 7. toggleTagSimulation null/empty → returns false
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("toggleTagSimulation(null) returns false without throwing")
    void testToggleTagSimulationNull() {
        assertThat(engine.toggleTagSimulation(null)).isFalse();
    }

    @Test
    @DisplayName("toggleTagSimulation(\"\") returns false without throwing")
    void testToggleTagSimulationEmpty() {
        assertThat(engine.toggleTagSimulation("")).isFalse();
    }

    // -------------------------------------------------------------------------
    // 8. getSimulatedTags() returns defensive copy
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("getSimulatedTags() returns a defensive copy — mutations do not affect engine state")
    void testGetSimulatedTagsDefensiveCopy() {
        engine.enableTagSimulation("Controller:Global/Alpha");
        engine.enableTagSimulation("Controller:Global/Beta");

        Set<String> copy = engine.getSimulatedTags();
        assertThat(copy).containsExactlyInAnyOrder(
            "Controller:Global/Alpha",
            "Controller:Global/Beta"
        );

        // Mutate the returned set
        copy.add("Controller:Global/Injected");
        copy.remove("Controller:Global/Alpha");

        // Engine state must be unaffected
        assertThat(engine.isTagSimulated("Controller:Global/Alpha")).isTrue();
        assertThat(engine.isTagSimulated("Controller:Global/Injected")).isFalse();
        assertThat(engine.getSimulatedTagCount()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // 9. getTagPattern() returns default when no override set
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("getTagPattern() returns default pattern when no per-tag override is set")
    void testGetTagPatternReturnsDefault() {
        // No override set — should return the constructor default (STATIC)
        assertThat(engine.getTagPattern("Controller:Global/NoOverride"))
            .isEqualTo(LogixEmulatorConfig.SimulationPattern.STATIC);
    }

    // -------------------------------------------------------------------------
    // 10. setTagPattern() + getTagPattern() returns custom pattern
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("setTagPattern() then getTagPattern() returns the custom pattern")
    void testSetAndGetTagPattern() {
        engine.setTagPattern("Controller:Global/MyTag", LogixEmulatorConfig.SimulationPattern.SINE);
        assertThat(engine.getTagPattern("Controller:Global/MyTag"))
            .isEqualTo(LogixEmulatorConfig.SimulationPattern.SINE);
    }

    // -------------------------------------------------------------------------
    // 11. enableTagSimulation(path, pattern) sets both membership and pattern
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("enableTagSimulation(path, pattern) enables tag and sets its pattern")
    void testEnableTagSimulationWithPattern() {
        engine.enableTagSimulation("Controller:Global/Ramp", LogixEmulatorConfig.SimulationPattern.RAMP);

        assertThat(engine.isTagSimulated("Controller:Global/Ramp")).isTrue();
        assertThat(engine.getTagPattern("Controller:Global/Ramp"))
            .isEqualTo(LogixEmulatorConfig.SimulationPattern.RAMP);
    }

    @Test
    @DisplayName("enableTagSimulation(path, null pattern) still enables tag, uses default pattern")
    void testEnableTagSimulationWithNullPattern() {
        engine.enableTagSimulation("Controller:Global/NullPat", null);

        assertThat(engine.isTagSimulated("Controller:Global/NullPat")).isTrue();
        // No override stored — falls back to default (STATIC)
        assertThat(engine.getTagPattern("Controller:Global/NullPat"))
            .isEqualTo(LogixEmulatorConfig.SimulationPattern.STATIC);
    }

    // -------------------------------------------------------------------------
    // 12. disableTagSimulation clears pattern too
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("disableTagSimulation() also removes the per-tag pattern override")
    void testDisableTagSimulationClearsPattern() {
        engine.enableTagSimulation("Controller:Global/Tog", LogixEmulatorConfig.SimulationPattern.TOGGLE);
        assertThat(engine.getTagPattern("Controller:Global/Tog"))
            .isEqualTo(LogixEmulatorConfig.SimulationPattern.TOGGLE);

        engine.disableTagSimulation("Controller:Global/Tog");

        // Pattern override cleared — should now return default (STATIC)
        assertThat(engine.getTagPattern("Controller:Global/Tog"))
            .isEqualTo(LogixEmulatorConfig.SimulationPattern.STATIC);
    }

    // -------------------------------------------------------------------------
    // 13. disableAllSimulation() clears everything
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("disableAllSimulation() clears all simulated tags and all pattern overrides")
    void testDisableAllSimulation() {
        engine.enableTagSimulation("Controller:Global/T1", LogixEmulatorConfig.SimulationPattern.SINE);
        engine.enableTagSimulation("Controller:Global/T2", LogixEmulatorConfig.SimulationPattern.RAMP);
        engine.enableTagSimulation("Controller:Global/T3");

        engine.disableAllSimulation();

        assertThat(engine.getSimulatedTagCount()).isEqualTo(0);
        assertThat(engine.getSimulatedTags()).isEmpty();
        // All per-tag patterns cleared — fall back to default
        assertThat(engine.getTagPattern("Controller:Global/T1"))
            .isEqualTo(LogixEmulatorConfig.SimulationPattern.STATIC);
    }

    // -------------------------------------------------------------------------
    // 14-16 (removed, v10 C1): the engine's enableSimulationByScope/
    // disableSimulationByScope prefix matchers were deleted — raw startsWith()
    // scope matching is wrong under the canonical NodeId scheme (the controller
    // scope has an EMPTY prefix). Scope semantics now live solely in
    // TagSimulationFacade via AddressPolicy.matchesScope() and are covered by
    // TagSimulationFacadeTest. Per-tag pattern clearing on disable is covered
    // by the existing disableTagSimulation tests below.
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // 17. enableAllSimulation() enables all provided paths
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("enableAllSimulation() enables every tag in the provided set")
    void testEnableAllSimulation() {
        Set<String> all = Set.of(
            "Controller:Global/A",
            "Controller:Global/B",
            "Programs/Prog/C"
        );

        engine.enableAllSimulation(all);

        assertThat(engine.isTagSimulated("Controller:Global/A")).isTrue();
        assertThat(engine.isTagSimulated("Controller:Global/B")).isTrue();
        assertThat(engine.isTagSimulated("Programs/Prog/C")).isTrue();
    }

    // -------------------------------------------------------------------------
    // 18. getSimulatedTagCount() returns correct count
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("getSimulatedTagCount() returns the exact number of simulated tags")
    void testGetSimulatedTagCount() {
        assertThat(engine.getSimulatedTagCount()).isEqualTo(0);

        engine.enableTagSimulation("Controller:Global/One");
        assertThat(engine.getSimulatedTagCount()).isEqualTo(1);

        engine.enableTagSimulation("Controller:Global/Two");
        assertThat(engine.getSimulatedTagCount()).isEqualTo(2);

        engine.disableTagSimulation("Controller:Global/One");
        assertThat(engine.getSimulatedTagCount()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // 19. Duplicate enableTagSimulation calls don't create duplicates
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Calling enableTagSimulation() multiple times for the same tag does not inflate the count")
    void testDuplicateEnableDoesNotInflateCount() {
        engine.enableTagSimulation("Controller:Global/Dup");
        engine.enableTagSimulation("Controller:Global/Dup");
        engine.enableTagSimulation("Controller:Global/Dup");

        assertThat(engine.getSimulatedTagCount()).isEqualTo(1);
        assertThat(engine.getSimulatedTags()).containsExactly("Controller:Global/Dup");
    }
}
