package com.inductiveautomation.logixemulator.gateway;

import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorConfig;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
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
 * Simulation engine adapted for OPC-UA address space.
 * Updates DataItem values according to configured simulation patterns.
 *
 * By default, NO tags are simulated. Tags must be explicitly enabled for simulation.
 */
public class OpcUaSimulationEngine {

    private static final Logger logger = LoggerFactory.getLogger(OpcUaSimulationEngine.class);
    private final Random random = new Random();
    private final LogixEmulatorConfig.SimulationPattern defaultPattern;
    private final int updateIntervalMs;
    private final Function<NodeId, UaNode> nodeLookup;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> simulationTask;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long startTime;

    // Per-tag simulation state - tags NOT in this set will NOT be simulated
    private final Set<String> simulatedTags = ConcurrentHashMap.newKeySet();
    // Per-tag simulation pattern override (optional)
    private final Map<String, LogixEmulatorConfig.SimulationPattern> tagPatterns = new ConcurrentHashMap<>();

    public OpcUaSimulationEngine(
        LogixEmulatorConfig.SimulationPattern defaultPattern,
        int updateIntervalMs,
        Function<NodeId, UaNode> nodeLookup) {
        this.defaultPattern = defaultPattern;
        this.updateIntervalMs = Math.max(100, updateIntervalMs); // Minimum 100ms
        this.nodeLookup = nodeLookup;
    }

    /**
     * Start the simulation engine.
     */
    public void start(List<DataItem> dataItems) {
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
            () -> updateSimulatedValues(dataItems),
            0,
            updateIntervalMs,
            TimeUnit.MILLISECONDS
        );

        logger.info("OPC-UA Simulation engine started ({}ms interval, {} pattern)",
                    updateIntervalMs, defaultPattern);
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
     * Update simulated values for tags that have simulation enabled.
     * Only tags in the simulatedTags set are updated.
     * Retrieves nodes from the address space, calculates new values based on simulation patterns,
     * and updates node values to trigger OPC-UA subscription notifications.
     */
    private void updateSimulatedValues(List<DataItem> dataItems) {
        if (!running.get() || dataItems == null || nodeLookup == null) {
            return;
        }

        // If no tags are enabled for simulation, skip entirely
        if (simulatedTags.isEmpty()) {
            return;
        }

        double elapsedSeconds = getElapsedSeconds();
        int updateCount = 0;
        int errorCount = 0;

        for (DataItem item : dataItems) {
            try {
                // Get the NodeId for this data item
                NodeId nodeId = item.getReadValueId().getNodeId();

                // Get tag path from NodeId identifier
                String tagPath = nodeId.getIdentifier().toString();

                // Skip tags that don't have simulation enabled
                if (!isTagSimulated(tagPath)) {
                    continue;
                }

                // Look up the actual node in the address space
                UaNode node = nodeLookup.apply(nodeId);

                if (node instanceof UaVariableNode variableNode) {
                    // Get current value
                    DataValue currentDataValue = variableNode.getValue();
                    Object currentValue = currentDataValue.getValue().getValue();

                    // Calculate new simulated value using tag-specific or default pattern
                    LogixEmulatorConfig.SimulationPattern pattern = tagPatterns.getOrDefault(tagPath, defaultPattern);
                    Object newValue = calculateValue(currentValue, elapsedSeconds, pattern);

                    // Create new DataValue with current timestamp
                    DataValue newDataValue = new DataValue(
                        new Variant(newValue),
                        StatusCode.GOOD,
                        DateTime.now()
                    );

                    // Update the node value (this automatically notifies subscribers)
                    variableNode.setValue(newDataValue);

                    updateCount++;

                } else {
                    logger.trace("Node is not a variable node: {}", nodeId);
                }

            } catch (Exception e) {
                errorCount++;
                if (errorCount < 5) { // Only log first few errors to avoid spam
                    logger.warn("Error updating simulated value for node: {}", item.getReadValueId().getNodeId(), e);
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
        // Determine data type
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
        logger.info("Disabled simulation for all tags");
    }

    /**
     * Enable simulation for all tags matching a scope prefix.
     * @param prefix The scope prefix (e.g., "Controller:Global", "Programs/MainProgram")
     * @param allTagPaths All available tag paths to filter
     */
    public void enableSimulationByScope(String prefix, Set<String> allTagPaths) {
        int count = 0;
        for (String tagPath : allTagPaths) {
            if (tagPath.startsWith(prefix)) {
                simulatedTags.add(tagPath);
                count++;
            }
        }
        logger.info("Enabled simulation for {} tags matching scope: {}", count, prefix);
    }

    /**
     * Disable simulation for all tags matching a scope prefix.
     * @param prefix The scope prefix
     */
    public void disableSimulationByScope(String prefix) {
        int count = 0;
        var iterator = simulatedTags.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().startsWith(prefix)) {
                iterator.remove();
                count++;
            }
        }
        // Also clean up patterns for disabled tags
        tagPatterns.keySet().removeIf(k -> k.startsWith(prefix));
        logger.info("Disabled simulation for {} tags matching scope: {}", count, prefix);
    }

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
}
