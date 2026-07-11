package com.inductiveautomation.logixemulator.gateway.device;

import com.inductiveautomation.logixemulator.gateway.OpcUaSimulationEngine;
import com.inductiveautomation.logixemulator.gateway.address.AddressPolicy;
import com.inductiveautomation.logixemulator.gateway.address.RockwellLogixPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Thin facade over the per-tag simulation API of {@link OpcUaSimulationEngine}.
 *
 * <p>The original {@code LogixEmulatorDevice} class exposed twelve nearly-
 * identical pass-through methods (enable/disable/toggle/scope/all + status
 * queries). The review (SEV-2 in {@code mod-plc-emulator.md}) flagged those
 * as the bulk of the 1040-line god class. The facade preserves the public
 * method names and semantics — the device delegates to it — so callers see
 * no behavioural change.</p>
 *
 * <p>The simulation engine reference is read from a {@link Supplier} on
 * every call so engine restarts (e.g. on hot-reload {@code performFullRebuild})
 * are observed without re-wiring. A null engine is treated as "not available"
 * and methods return safe defaults (empty set, false, "static", etc.) — the
 * exact behaviour of the previous in-class methods.</p>
 */
public final class TagSimulationFacade {

    private static final Logger logger = LoggerFactory.getLogger(TagSimulationFacade.class);

    private final Supplier<OpcUaSimulationEngine> engineSupplier;
    private final Supplier<Set<String>> allTagPathsSupplier;
    private final Predicate<String> readOnlyPredicate;

    /**
     * Vendor addressing policy used for scope membership (v10 C1): under the canonical NodeId
     * scheme the controller scope has an EMPTY identifier prefix (controller tags are bare), so
     * scope matching needs policy knowledge — a naive {@code startsWith("")} would match every
     * tag, including program-scoped ones.
     */
    private final AddressPolicy policy = new RockwellLogixPolicy();

    /**
     * @param engineSupplier returns the current simulation engine (may be
     *     {@code null} if no engine is started)
     * @param allTagPathsSupplier returns the set of all known tag paths in
     *     the address space — used by enable-all and enable-by-scope. Should
     *     never return {@code null}; an empty set is fine.
     */
    public TagSimulationFacade(
        Supplier<OpcUaSimulationEngine> engineSupplier,
        Supplier<Set<String>> allTagPathsSupplier
    ) {
        this(engineSupplier, allTagPathsSupplier, tagPath -> false);
    }

    /**
     * @param engineSupplier returns the current simulation engine (may be
     *     {@code null} if no engine is started)
     * @param allTagPathsSupplier returns the set of all known tag paths in
     *     the address space — used by enable-all and enable-by-scope. Should
     *     never return {@code null}; an empty set is fine.
     * @param readOnlyPredicate returns whether a given tag path is flagged
     *     read-only in the OPC-UA address space (FIX-2 part 2). Consulted by
     *     {@link #enableSimulationByScope} to skip read-only tags rather than
     *     let the engine start writing simulated values into them on tick —
     *     the engine itself never consults AccessLevel, so this is the only
     *     choke point for the bulk-by-scope path.
     */
    public TagSimulationFacade(
        Supplier<OpcUaSimulationEngine> engineSupplier,
        Supplier<Set<String>> allTagPathsSupplier,
        Predicate<String> readOnlyPredicate
    ) {
        if (engineSupplier == null) {
            throw new IllegalArgumentException("engineSupplier must not be null");
        }
        if (allTagPathsSupplier == null) {
            throw new IllegalArgumentException("allTagPathsSupplier must not be null");
        }
        if (readOnlyPredicate == null) {
            throw new IllegalArgumentException("readOnlyPredicate must not be null");
        }
        this.engineSupplier = engineSupplier;
        this.allTagPathsSupplier = allTagPathsSupplier;
        this.readOnlyPredicate = readOnlyPredicate;
    }

    /** Enable simulation for a specific tag using the engine's default pattern. */
    public boolean enableTagSimulation(String tagPath) {
        OpcUaSimulationEngine engine = engineSupplier.get();
        if (engine == null) {
            return false;
        }
        engine.enableTagSimulation(tagPath);
        return true;
    }

    /** Enable simulation for a specific tag with a custom pattern (key form). */
    public boolean enableTagSimulation(String tagPath, String pattern) {
        OpcUaSimulationEngine engine = engineSupplier.get();
        if (engine == null) {
            return false;
        }
        try {
            LogixEmulatorConfig.SimulationPattern simPattern =
                LogixEmulatorConfig.SimulationPattern.fromKey(pattern);
            engine.enableTagSimulation(tagPath, simPattern);
        } catch (Exception e) {
            // Preserve historical behaviour: log + fall back to default
            // pattern; still return true so callers see the tag enabled.
            logger.warn("Invalid simulation pattern: {}", pattern);
            engine.enableTagSimulation(tagPath);
        }
        return true;
    }

    /** Disable simulation for a specific tag. */
    public boolean disableTagSimulation(String tagPath) {
        OpcUaSimulationEngine engine = engineSupplier.get();
        if (engine == null) {
            return false;
        }
        engine.disableTagSimulation(tagPath);
        return true;
    }

    /** Toggle simulation for a specific tag. */
    public Boolean toggleTagSimulation(String tagPath) {
        OpcUaSimulationEngine engine = engineSupplier.get();
        if (engine == null) {
            return Boolean.FALSE;
        }
        return engine.toggleTagSimulation(tagPath);
    }

    /** @return whether simulation is currently enabled for the given tag. */
    public boolean isTagSimulated(String tagPath) {
        OpcUaSimulationEngine engine = engineSupplier.get();
        return engine != null && engine.isTagSimulated(tagPath);
    }

    /** @return the set of all tag paths with simulation currently enabled. */
    public Set<String> getSimulatedTags() {
        OpcUaSimulationEngine engine = engineSupplier.get();
        if (engine == null) {
            return Collections.emptySet();
        }
        return engine.getSimulatedTags();
    }

    /** @return the simulation pattern key (e.g. "ramp") for the given tag. */
    public String getTagSimulationPattern(String tagPath) {
        OpcUaSimulationEngine engine = engineSupplier.get();
        if (engine == null) {
            return "static";
        }
        return engine.getTagPattern(tagPath).getKey();
    }

    /** @return whether the simulation engine is started and running. */
    public boolean isSimulationEngineAvailable() {
        OpcUaSimulationEngine engine = engineSupplier.get();
        return engine != null && engine.isRunning();
    }

    /** @return the count of tags with simulation enabled, or 0 if no engine. */
    public int getSimulatedTagCount() {
        OpcUaSimulationEngine engine = engineSupplier.get();
        return engine != null ? engine.getSimulatedTagCount() : 0;
    }

    /**
     * Enable simulation for every tag within the given canonical scope selector (empty selector =
     * controller scope, {@code Program:} = all programs, {@code Program:<Prog>} = one program).
     * Membership is decided by {@link AddressPolicy#matchesScope} — see the {@code policy} field
     * note for why plain prefix matching is wrong under the v10 canonical NodeId scheme.
     *
     * <p>FIX-2 part 2: tags flagged read-only are SKIPPED rather than simulated, so one read-only
     * tag in a scope doesn't fail the whole bulk operation. The skipped count is returned so the
     * caller can surface it (e.g. as a {@code skippedReadOnly} response field) rather than the
     * exclusion being silent.</p>
     *
     * @return the number of matched tags skipped because they are flagged read-only
     */
    public int enableSimulationByScope(String scope) {
        OpcUaSimulationEngine engine = engineSupplier.get();
        if (engine == null) {
            return 0;
        }
        int count = 0;
        int skippedReadOnly = 0;
        for (String tagPath : allTagPathsSupplier.get()) {
            if (policy.matchesScope(tagPath, scope)) {
                if (readOnlyPredicate.test(tagPath)) {
                    skippedReadOnly++;
                    continue;
                }
                engine.enableTagSimulation(tagPath);
                count++;
            }
        }
        logger.info("Enabled simulation for {} tags in scope: {} ({} read-only tags skipped)",
            count, scope, skippedReadOnly);
        return skippedReadOnly;
    }

    /** Disable simulation for every currently-simulated tag within the given scope selector. */
    public void disableSimulationByScope(String scope) {
        OpcUaSimulationEngine engine = engineSupplier.get();
        if (engine == null) {
            return;
        }
        int count = 0;
        for (String tagPath : engine.getSimulatedTags()) {
            if (policy.matchesScope(tagPath, scope)) {
                engine.disableTagSimulation(tagPath);
                count++;
            }
        }
        logger.info("Disabled simulation for {} tags in scope: {}", count, scope);
    }

    /** Enable simulation for every known tag path. */
    public void enableAllSimulation() {
        OpcUaSimulationEngine engine = engineSupplier.get();
        if (engine == null) {
            return;
        }
        engine.enableAllSimulation(allTagPathsSupplier.get());
    }

    /** Disable simulation for every tag. */
    public void disableAllSimulation() {
        OpcUaSimulationEngine engine = engineSupplier.get();
        if (engine != null) {
            engine.disableAllSimulation();
        }
    }
}
