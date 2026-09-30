package com.inductiveautomation.logixemulator.gateway;

import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorConfig;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.ULong;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Simulation engine for the emulator's OPC-UA address space. Drives the value
 * attribute of the canonical {@link UaVariableNode} behind each simulated tag
 * according to its configured pattern (RAMP/SINE/RANDOM/TOGGLE/STATIC).
 *
 * <p>By default, NO tags are simulated. Tags must be explicitly enabled for
 * simulation via {@link #enableTagSimulation(String)} / the per-tag control API.</p>
 *
 * <h2>Why this engine ticks from its OWN registry, not the SubscriptionModel (defect B3-v10)</h2>
 *
 * <p>Until v10 the tick iterated the device's {@code SubscriptionModel.getDataItems()}
 * list and derived its per-tag key from each {@code DataItem}'s live {@code NodeId}.
 * On the JUnit bench (and in every stubbed integration test) that list was hand-populated,
 * so the tests passed. On a <b>real Milo server it stays EMPTY</b>: the emulator builds
 * value-backed {@code UaVariableNode}s and the OPC-UA server satisfies client subscriptions
 * straight from each node's own value attribute — it never registers device-side
 * {@code DataItem}s. So {@code getDataItems()} returned nothing, the tick loop had nothing
 * to iterate, {@code updateCount} was always 0, and <b>no tag value ever changed on a real
 * gateway</b> even though the registry, the API and genuine subscriptions were all healthy
 * (evidence: {@code ~/Downloads/plc-v10-artifacts/plc-dod2/item3-simulation-FAIL.txt}).</p>
 *
 * <p>The fix: the engine ticks over its own {@link #simulatedTags} registry and resolves
 * each tag key to the actual {@link UaVariableNode} via {@link #nodeResolver}, then calls
 * {@code varNode.setValue(...)} — the exact same node-object write path
 * {@code TagWriteDispatcher} uses, which the DoD proved propagates to live subscribers.
 * There is deliberately no {@code DataItem}/{@code SubscriptionModel} dependency left in
 * this class; do not reintroduce one — a stub can populate that layer while a real server
 * cannot, which is precisely how the incident hid behind green tests.</p>
 */
public class OpcUaSimulationEngine {

    private static final Logger logger = LoggerFactory.getLogger(OpcUaSimulationEngine.class);
    private final Random random = new Random();
    private final LogixEmulatorConfig.SimulationPattern defaultPattern;
    private final int updateIntervalMs;

    /**
     * Resolves a simulated-tag registry key (the canonical NodeId identifier string, e.g.
     * {@code "RampInt"} or {@code "Program:Main.Counter"}) to the live {@link UaVariableNode}
     * that backs it, or {@code null} if no variable node currently exists for that key. The
     * wiring builds this from {@code DeviceContext.nodeId(key)} + the node manager — the same
     * resolution the write path uses — so the engine and the write path always agree on which
     * node a key names (ADDRESSING.md §2.2: one canonical node per tag).
     */
    private final Function<String, UaVariableNode> nodeResolver;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> simulationTask;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long startTime;

    // Per-tag simulation state - tags NOT in this set will NOT be simulated.
    // This set IS the source of truth the tick iterates; enabling/disabling a
    // tag mid-run is observed on the very next tick without restarting the engine.
    private final Set<String> simulatedTags = ConcurrentHashMap.newKeySet();
    // Per-tag simulation pattern override (optional)
    private final Map<String, LogixEmulatorConfig.SimulationPattern> tagPatterns = new ConcurrentHashMap<>();
    // Per-tag baseline state for C12: an external write becomes the new baseline
    // around which the active simulation pattern is computed.
    private final Map<String, BaselineState> tagBaselines = new ConcurrentHashMap<>();

    /**
     * @param defaultPattern pattern used for tags without a per-tag override
     * @param updateIntervalMs tick interval in milliseconds (floored at 100ms)
     * @param nodeResolver maps a simulated-tag key to its live {@link UaVariableNode}
     *     (or {@code null} if none exists); must not be null
     */
    public OpcUaSimulationEngine(
        LogixEmulatorConfig.SimulationPattern defaultPattern,
        int updateIntervalMs,
        Function<String, UaVariableNode> nodeResolver) {
        this.defaultPattern = defaultPattern;
        this.updateIntervalMs = Math.max(100, updateIntervalMs); // Minimum 100ms
        this.nodeResolver = nodeResolver;
    }

    /**
     * Start the simulation engine. From this point the engine ticks every
     * {@code updateIntervalMs} over its own {@link #simulatedTags} registry —
     * there is no external data-item list to supply (see class Javadoc, B3-v10).
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            logger.warn("Simulation engine already running");
            return;
        }

        startTime = System.currentTimeMillis();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "OPC-UA-Simulation-Engine");
            t.setDaemon(true);
            return t;
        });

        simulationTask = executor.scheduleAtFixedRate(
            this::tick,
            0,
            updateIntervalMs,
            TimeUnit.MILLISECONDS
        );

        logger.info("OPC-UA Simulation engine started ({}ms interval, {} pattern)",
                    updateIntervalMs, defaultPattern);
    }

    /**
     * Per-tick entry point. Guards against any exception escaping into the
     * {@link ScheduledExecutorService} (which would silently cancel the task).
     */
    private void tick() {
        try {
            updateSimulatedValues();
        } catch (Exception e) {
            // Never let a tick failure cancel the scheduled task.
            logger.warn("Simulation tick failed", e);
        }
    }

    /**
     * Stop the simulation engine.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        if (simulationTask != null) {
            simulationTask.cancel(false);
        }

        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        logger.info("OPC-UA Simulation engine stopped");
    }

    /**
     * Update simulated values for every tag that has simulation enabled.
     *
     * <p>Iterates the engine's own {@link #simulatedTags} registry (NOT a device
     * {@code DataItem} list — see class Javadoc), resolves each tag key to its live
     * {@link UaVariableNode}, computes the next value from the tag's pattern, and writes
     * it back with {@code setValue} — which is what notifies OPC-UA subscribers.</p>
     */
    private void updateSimulatedValues() {
        if (!running.get() || nodeResolver == null) {
            return;
        }

        // If no tags are enabled for simulation, skip entirely.
        if (simulatedTags.isEmpty()) {
            return;
        }

        double elapsedSeconds = getElapsedSeconds();
        int updateCount = 0;
        int errorCount = 0;

        // ConcurrentHashMap keySet iteration is weakly consistent — safe to iterate
        // while enable/disable mutate it concurrently; the next tick sees the change.
        for (String tagKey : simulatedTags) {
            try {
                // Resolve the ONE canonical variable node this key names. On a real
                // server this returns the same node the write path targets; if the
                // node does not (yet) exist we simply skip it this tick.
                UaVariableNode variableNode = nodeResolver.apply(tagKey);
                if (variableNode == null) {
                    logger.trace("No variable node resolved for simulated tag: {}", tagKey);
                    continue;
                }

                // Get current value
                DataValue currentDataValue = variableNode.getValue();
                Object currentValue = currentDataValue.getValue().getValue();

                // Calculate new simulated value using tag-specific or default pattern.
                // If a baseline has been recorded for this tag (via recalibrate())
                // we feed the simulation a per-tag elapsed clock plus the user-written
                // baseline, so the pattern continues *from* the user's value rather
                // than overwriting it on the next tick.
                LogixEmulatorConfig.SimulationPattern pattern = tagPatterns.getOrDefault(tagKey, defaultPattern);
                BaselineState baseline = tagBaselines.get(tagKey);
                Object newValue = (baseline == null)
                    ? calculateValue(currentValue, elapsedSeconds, pattern)
                    : calculateRecalibratedValue(currentValue, elapsedSeconds, pattern, baseline);

                // Update the node value (this automatically notifies subscribers).
                variableNode.setValue(new DataValue(
                    new Variant(newValue),
                    StatusCode.GOOD,
                    DateTime.now()
                ));

                updateCount++;

            } catch (Exception e) {
                errorCount++;
                if (errorCount <= 5) { // Only log first few errors to avoid spam
                    logger.warn("Error updating simulated value for tag: {}", tagKey, e);
                }
            }
        }

        if (errorCount > 5) {
            logger.debug("Suppressed {} additional simulation errors this tick", errorCount - 5);
        }

        if (updateCount > 0) {
            logger.trace("Simulation tick complete - updated {}/{} values ({} errors)",
                        updateCount, simulatedTags.size(), errorCount);
        }
    }

    /**
     * Calculate new simulated value based on pattern and type.
     */
    private Object calculateValue(Object currentValue, double elapsedSeconds, LogixEmulatorConfig.SimulationPattern pattern) {
        // Determine data type. Unsigned types (C6: USINT/UINT/UDINT/ULINT and the
        // WORD/DWORD/LWORD aliases) arrive as Milo's UByte/UShort/UInteger/ULong
        // wrappers, none of which are a signed Short/Integer/Long — they must be
        // matched explicitly or the tag falls through to the return-unchanged path
        // and never simulates (the bug the independent review flagged).
        if (currentValue instanceof Boolean) {
            return calculateBooleanValue(elapsedSeconds, pattern);
        } else if (currentValue instanceof Short) {
            return (short) calculateNumericValue(elapsedSeconds, -128, 127, pattern);
        } else if (currentValue instanceof Integer) {
            return calculateIntegerValue(elapsedSeconds, pattern);
        } else if (currentValue instanceof Long) {
            return calculateLongValue(elapsedSeconds, pattern);
        } else if (currentValue instanceof Float) {
            return calculateFloatValue(elapsedSeconds, pattern);
        } else if (currentValue instanceof Double) {
            return calculateDoubleValue(elapsedSeconds, pattern);
        } else if (currentValue instanceof UByte) {
            // 8-bit unsigned: [0, 255]
            return Unsigned.ubyte((int) calculateNumericValue(elapsedSeconds, 0, 255, pattern));
        } else if (currentValue instanceof UShort) {
            // 16-bit unsigned: [0, 65535]
            return Unsigned.ushort((int) calculateNumericValue(elapsedSeconds, 0, 65_535, pattern));
        } else if (currentValue instanceof UInteger) {
            // 32-bit unsigned: [0, 4294967295]
            return Unsigned.uint((long) calculateNumericValue(elapsedSeconds, 0, 4_294_967_295L, pattern));
        } else if (currentValue instanceof ULong) {
            // 64-bit unsigned. Cap the simulated range at Long.MAX_VALUE: that stays
            // well within the ULong domain, avoids double->long overflow, and still
            // gives a visibly varying value across the whole positive long range.
            return Unsigned.ulong((long) calculateNumericValue(elapsedSeconds, 0, Long.MAX_VALUE, pattern));
        } else {
            // For unsupported types, return current value unchanged
            return currentValue;
        }
    }

    /**
     * Calculate boolean value (TOGGLE pattern).
     */
    private Boolean calculateBooleanValue(double elapsedSeconds, LogixEmulatorConfig.SimulationPattern pattern) {
        double period = 2.0; // Toggle every 2 seconds
        return (elapsedSeconds % period) < (period / 2.0);
    }

    /**
     * Calculate integer value based on pattern.
     */
    private Integer calculateIntegerValue(double elapsedSeconds, LogixEmulatorConfig.SimulationPattern pattern) {
        return (int) calculateNumericValue(elapsedSeconds, 0, 100, pattern);
    }

    /**
     * Calculate long value based on pattern.
     */
    private Long calculateLongValue(double elapsedSeconds, LogixEmulatorConfig.SimulationPattern pattern) {
        return (long) calculateNumericValue(elapsedSeconds, 0, 10000, pattern);
    }

    /**
     * Calculate float value based on pattern.
     */
    private Float calculateFloatValue(double elapsedSeconds, LogixEmulatorConfig.SimulationPattern pattern) {
        return (float) calculateNumericValue(elapsedSeconds, 0.0, 100.0, pattern);
    }

    /**
     * Calculate double value based on pattern.
     */
    private Double calculateDoubleValue(double elapsedSeconds, LogixEmulatorConfig.SimulationPattern pattern) {
        return calculateNumericValue(elapsedSeconds, 0.0, 100.0, pattern);
    }

    /**
     * Calculate numeric value based on specified pattern.
     */
    private double calculateNumericValue(double elapsedSeconds, double min, double max, LogixEmulatorConfig.SimulationPattern pattern) {
        double range = max - min;

        switch (pattern) {
            case STATIC:
                return (min + max) / 2.0;

            case RAMP:
                double period = 10.0;
                double phase = (elapsedSeconds % period) / period;
                return min + (range * phase);

            case SINE:
                double sinePeriod = 10.0;
                double frequency = 2 * Math.PI / sinePeriod;
                double amplitude = range / 2.0;
                double offset = (max + min) / 2.0;
                return offset + amplitude * Math.sin(frequency * elapsedSeconds);

            case RANDOM:
                return min + (random.nextDouble() * range);

            case TOGGLE:
                // For numeric types, alternate between min and max
                double togglePeriod = 2.0;
                return (elapsedSeconds % togglePeriod) < (togglePeriod / 2.0) ? max : min;

            default:
                return (min + max) / 2.0;
        }
    }

    /**
     * Check if simulation engine is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get elapsed simulation time in seconds.
     */
    public double getElapsedSeconds() {
        if (!running.get()) {
            return 0.0;
        }
        return (System.currentTimeMillis() - startTime) / 1000.0;
    }

    // =====================================================
    // Per-tag simulation control API
    // =====================================================

    /**
     * Enable simulation for a specific tag.
     * @param tagPath The tag path (e.g., "Controller:Global/MyTag")
     */
    public void enableTagSimulation(String tagPath) {
        if (tagPath != null && !tagPath.isEmpty()) {
            simulatedTags.add(tagPath);
            logger.debug("Enabled simulation for tag: {}", tagPath);
        }
    }

    /**
     * Enable simulation for a specific tag with a custom pattern.
     * @param tagPath The tag path
     * @param pattern The simulation pattern to use for this tag
     */
    public void enableTagSimulation(String tagPath, LogixEmulatorConfig.SimulationPattern pattern) {
        if (tagPath != null && !tagPath.isEmpty()) {
            simulatedTags.add(tagPath);
            if (pattern != null) {
                tagPatterns.put(tagPath, pattern);
            }
            logger.debug("Enabled simulation for tag: {} with pattern: {}", tagPath, pattern);
        }
    }

    /**
     * Disable simulation for a specific tag.
     * @param tagPath The tag path
     */
    public void disableTagSimulation(String tagPath) {
        if (tagPath != null) {
            simulatedTags.remove(tagPath);
            tagPatterns.remove(tagPath);
            tagBaselines.remove(tagPath);
            logger.debug("Disabled simulation for tag: {}", tagPath);
        }
    }

    /**
     * Toggle simulation for a specific tag.
     * @param tagPath The tag path
     * @return true if simulation is now enabled, false if disabled
     */
    public boolean toggleTagSimulation(String tagPath) {
        if (tagPath == null || tagPath.isEmpty()) {
            return false;
        }

        if (simulatedTags.contains(tagPath)) {
            disableTagSimulation(tagPath);
            return false;
        } else {
            enableTagSimulation(tagPath);
            return true;
        }
    }

    /**
     * Check if a specific tag has simulation enabled.
     * @param tagPath The tag path
     * @return true if simulation is enabled for this tag
     */
    public boolean isTagSimulated(String tagPath) {
        return tagPath != null && simulatedTags.contains(tagPath);
    }

    /**
     * Get all tags that have simulation enabled.
     * @return Set of tag paths with simulation enabled
     */
    public Set<String> getSimulatedTags() {
        return new HashSet<>(simulatedTags);
    }

    /**
     * Get the simulation pattern for a specific tag.
     * @param tagPath The tag path
     * @return The pattern for this tag, or the default pattern if not set
     */
    public LogixEmulatorConfig.SimulationPattern getTagPattern(String tagPath) {
        return tagPatterns.getOrDefault(tagPath, defaultPattern);
    }

    /**
     * Set the simulation pattern for a specific tag.
     * @param tagPath The tag path
     * @param pattern The pattern to use
     */
    public void setTagPattern(String tagPath, LogixEmulatorConfig.SimulationPattern pattern) {
        if (tagPath != null && pattern != null) {
            tagPatterns.put(tagPath, pattern);
        }
    }

    /**
     * Disable simulation for all tags.
     */
    public void disableAllSimulation() {
        simulatedTags.clear();
        tagPatterns.clear();
        tagBaselines.clear();
        logger.info("Disabled simulation for all tags");
    }

    // NOTE (v10 C1): the former enableSimulationByScope/disableSimulationByScope prefix matchers
    // were removed. Under the canonical NodeId scheme the controller scope has an EMPTY identifier
    // prefix, so raw startsWith() matching is wrong (it would match every tag, program-scoped
    // included). Scope membership is now decided by AddressPolicy.matchesScope() in
    // TagSimulationFacade, which drives the per-tag enable/disable API here — keeping scope
    // semantics in exactly one place.

    /**
     * Enable simulation for all provided tag paths.
     * @param allTagPaths All tag paths to enable
     */
    public void enableAllSimulation(Set<String> allTagPaths) {
        simulatedTags.addAll(allTagPaths);
        logger.info("Enabled simulation for all {} tags", allTagPaths.size());
    }

    /**
     * Get the count of tags with simulation enabled.
     * @return Number of simulated tags
     */
    public int getSimulatedTagCount() {
        return simulatedTags.size();
    }

    // =====================================================
    // C12 — User-write recalibration
    // =====================================================

    /**
     * Recalibrate the simulation for a tag so that {@code newBaseline} becomes
     * the new centre/origin of the active pattern. Called by the device whenever
     * an external write (OPC-UA, REST, attribute filter) lands on a simulated
     * tag — the next simulation tick will continue *from* the written value
     * instead of clobbering it within {@code updateIntervalMs}.
     *
     * <p>Semantics by pattern:</p>
     * <ul>
     *   <li><b>RAMP</b> — restarts at {@code newBaseline} and ramps upward</li>
     *   <li><b>SINE</b> — re-phases so the wave is centred on {@code newBaseline}</li>
     *   <li><b>RANDOM</b> — re-centres the band around {@code newBaseline}</li>
     *   <li><b>STATIC</b> — value is held at {@code newBaseline}</li>
     *   <li><b>TOGGLE</b> — boolean: written value seeds the toggle phase</li>
     * </ul>
     *
     * @param tagPath the tag whose simulation should re-anchor
     * @param newBaseline the freshly-written value to treat as the new origin
     */
    public void recalibrate(String tagPath, Object newBaseline) {
        if (tagPath == null || tagPath.isEmpty()) {
            return;
        }
        if (!isTagSimulated(tagPath)) {
            // Not a simulated tag — nothing to recalibrate.
            return;
        }
        BaselineState state = new BaselineState(newBaseline, System.currentTimeMillis());
        tagBaselines.put(tagPath, state);
        logger.debug("Recalibrated simulation baseline for tag {} to {}", tagPath, newBaseline);
    }

    /**
     * Get the recorded baseline for a tag (test/diagnostic hook).
     * @return the recorded baseline value, or null if none has been set
     */
    public Object getBaseline(String tagPath) {
        if (tagPath == null || tagPath.isEmpty()) {
            return null;
        }
        BaselineState state = tagBaselines.get(tagPath);
        return state == null ? null : state.value;
    }

    /**
     * Compute a value for a tag that has a recorded user-write baseline.
     * Numeric patterns continue around the new baseline; STATIC holds it;
     * TOGGLE flips boolean baselines around their own phase.
     */
    private Object calculateRecalibratedValue(
            Object currentValue,
            double elapsedSeconds,
            LogixEmulatorConfig.SimulationPattern pattern,
            BaselineState baseline) {

        // Per-tag elapsed clock — measured from the moment the baseline was set
        // so the pattern restarts from "now" instead of jumping mid-cycle.
        double localElapsed = Math.max(0.0, (System.currentTimeMillis() - baseline.recalibratedAtMillis) / 1000.0);

        // Numeric baseline as a double for amplitude calculations
        double base;
        try {
            base = toDouble(baseline.value, currentValue);
        } catch (NumberFormatException e) {
            // Fall back to the unrecalibrated path if the baseline cannot be coerced
            return calculateValue(currentValue, elapsedSeconds, pattern);
        }

        switch (pattern) {
            case STATIC:
                // Hold the user-written baseline indefinitely.
                return coerceToType(base, currentValue, baseline.value);

            case RAMP: {
                // Use a default range of 100 to keep parity with calculateNumericValue
                double period = 10.0;
                double phase = (localElapsed % period) / period;
                double ramped = base + (100.0 * phase);
                return coerceToType(ramped, currentValue, baseline.value);
            }

            case SINE: {
                double sinePeriod = 10.0;
                double frequency = 2 * Math.PI / sinePeriod;
                double amplitude = 50.0; // half of the default 100-range
                double v = base + amplitude * Math.sin(frequency * localElapsed);
                return coerceToType(v, currentValue, baseline.value);
            }

            case RANDOM: {
                // Random walk around the baseline, ±50 (default range/2)
                double v = base + (random.nextDouble() - 0.5) * 100.0;
                return coerceToType(v, currentValue, baseline.value);
            }

            case TOGGLE:
                if (currentValue instanceof Boolean || baseline.value instanceof Boolean) {
                    boolean seed = (baseline.value instanceof Boolean) && (Boolean) baseline.value;
                    double togglePeriod = 2.0;
                    boolean phase = (localElapsed % togglePeriod) < (togglePeriod / 2.0);
                    return seed ^ phase ? Boolean.TRUE : Boolean.FALSE;
                }
                // Numeric toggle: alternate between baseline and (baseline + range)
                double togglePeriod = 2.0;
                double v = (localElapsed % togglePeriod) < (togglePeriod / 2.0) ? base + 100.0 : base;
                return coerceToType(v, currentValue, baseline.value);

            default:
                return coerceToType(base, currentValue, baseline.value);
        }
    }

    /**
     * Coerce a double-valued simulation result back into the original tag's
     * Java type (Boolean/Short/Integer/Long/Float/Double/unsigned wrappers/String).
     */
    private Object coerceToType(double value, Object currentValue, Object baselineValue) {
        Object reference = currentValue != null ? currentValue : baselineValue;
        if (reference instanceof Boolean) {
            return value > 0.5;
        } else if (reference instanceof Short) {
            return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
        } else if (reference instanceof Integer) {
            return (int) value;
        } else if (reference instanceof Long) {
            return (long) value;
        } else if (reference instanceof Float) {
            return (float) value;
        } else if (reference instanceof Double) {
            return value;
        } else if (reference instanceof UByte) {
            return Unsigned.ubyte((int) clampUnsigned(value, 255));
        } else if (reference instanceof UShort) {
            return Unsigned.ushort((int) clampUnsigned(value, 65_535));
        } else if (reference instanceof UInteger) {
            return Unsigned.uint((long) clampUnsigned(value, 4_294_967_295L));
        } else if (reference instanceof ULong) {
            return Unsigned.ulong((long) clampUnsigned(value, Long.MAX_VALUE));
        }
        return value;
    }

    /**
     * Clamp a simulated double into the valid non-negative unsigned domain [0, max].
     */
    private double clampUnsigned(double value, double max) {
        return Math.max(0.0, Math.min(max, value));
    }

    /**
     * Best-effort conversion of an arbitrary value to a double. Falls back to
     * the current OPC-UA value if the baseline cannot be parsed.
     */
    private double toDouble(Object value, Object currentValue) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        if (value instanceof String s) {
            return Double.parseDouble(s.trim());
        }
        if (currentValue instanceof Number n) {
            return n.doubleValue();
        }
        throw new NumberFormatException("Cannot coerce baseline " + value + " to double");
    }

    /**
     * Per-tag baseline record — the value the user wrote and the wall-clock
     * timestamp at which it became authoritative. Final fields ensure
     * publication safety across the simulation thread without explicit locking.
     */
    private static final class BaselineState {
        final Object value;
        final long recalibratedAtMillis;

        BaselineState(Object value, long recalibratedAtMillis) {
            this.value = value;
            this.recalibratedAtMillis = recalibratedAtMillis;
        }
    }
}
