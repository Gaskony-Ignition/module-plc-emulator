package com.inductiveautomation.plcsimulator.gateway;

import com.inductiveautomation.plcsimulator.gateway.device.EnhancedSimulatorConfig;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Simulation engine adapted for OPC-UA address space.
 * Updates DataItem values according to configured simulation patterns.
 */
public class OpcUaSimulationEngine {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Random random = new Random();
    private final EnhancedSimulatorConfig.SimulationPattern defaultPattern;
    private final int updateIntervalMs;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> simulationTask;
    private volatile boolean running = false;
    private long startTime;

    public OpcUaSimulationEngine(EnhancedSimulatorConfig.SimulationPattern defaultPattern, int updateIntervalMs) {
        this.defaultPattern = defaultPattern;
        this.updateIntervalMs = Math.max(100, updateIntervalMs); // Minimum 100ms
    }

    /**
     * Start the simulation engine.
     */
    public void start(List<DataItem> dataItems) {
        if (running) {
            logger.warn("Simulation engine already running");
            return;
        }

        running = true;
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
        if (!running) {
            return;
        }

        running = false;

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
     * Update all simulated values.
     *
     * TODO: This requires deeper integration with the address space to update node values.
     * For now, simulation is disabled to allow module compilation.
     * Future implementation should use ManagedAddressSpace to update node values directly.
     */
    private void updateSimulatedValues(List<DataItem> dataItems) {
        if (!running || dataItems == null) {
            return;
        }

        // Simulation temporarily disabled - requires address space integration
        logger.trace("Simulation tick - {} items monitored", dataItems.size());

        // TODO: Implement node value updates through address space:
        // 1. Get UaVariableNode from addressSpace using item.getReadValueId().getNodeId()
        // 2. Calculate new value based on simulation pattern using calculateValue()
        // 3. Update node.setValue(new DataValue(...))
        // 4. Fire value change events to notify subscribers
    }

    /**
     * Calculate new simulated value based on pattern and type.
     */
    private Object calculateValue(Object currentValue, double elapsedSeconds) {
        // Determine data type
        if (currentValue instanceof Boolean) {
            return calculateBooleanValue(elapsedSeconds);
        } else if (currentValue instanceof Integer) {
            return calculateIntegerValue(elapsedSeconds);
        } else if (currentValue instanceof Long) {
            return calculateLongValue(elapsedSeconds);
        } else if (currentValue instanceof Float) {
            return calculateFloatValue(elapsedSeconds);
        } else if (currentValue instanceof Double) {
            return calculateDoubleValue(elapsedSeconds);
        } else {
            // For unsupported types, return current value unchanged
            return currentValue;
        }
    }

    /**
     * Calculate boolean value (TOGGLE pattern).
     */
    private Boolean calculateBooleanValue(double elapsedSeconds) {
        double period = 2.0; // Toggle every 2 seconds
        return (elapsedSeconds % period) < (period / 2.0);
    }

    /**
     * Calculate integer value based on pattern.
     */
    private Integer calculateIntegerValue(double elapsedSeconds) {
        return (int) calculateNumericValue(elapsedSeconds, 0, 100);
    }

    /**
     * Calculate long value based on pattern.
     */
    private Long calculateLongValue(double elapsedSeconds) {
        return (long) calculateNumericValue(elapsedSeconds, 0, 10000);
    }

    /**
     * Calculate float value based on pattern.
     */
    private Float calculateFloatValue(double elapsedSeconds) {
        return (float) calculateNumericValue(elapsedSeconds, 0.0, 100.0);
    }

    /**
     * Calculate double value based on pattern.
     */
    private Double calculateDoubleValue(double elapsedSeconds) {
        return calculateNumericValue(elapsedSeconds, 0.0, 100.0);
    }

    /**
     * Calculate numeric value based on configured pattern.
     */
    private double calculateNumericValue(double elapsedSeconds, double min, double max) {
        double range = max - min;

        switch (defaultPattern) {
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
        return running;
    }

    /**
     * Get elapsed simulation time in seconds.
     */
    public double getElapsedSeconds() {
        if (!running) {
            return 0.0;
        }
        return (System.currentTimeMillis() - startTime) / 1000.0;
    }
}
