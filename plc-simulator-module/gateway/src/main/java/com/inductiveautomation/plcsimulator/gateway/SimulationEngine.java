package com.inductiveautomation.plcsimulator.gateway;

import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.gateway.tags.managed.ManagedTagProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulation engine for dynamically updating tag values.
 * Supports various simulation patterns: ramp, sine, random, toggle, etc.
 */
public class SimulationEngine implements Runnable {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ManagedTagProvider provider;
    private final Map<String, SimulationConfig> simulations;
    private final Random random;
    private volatile boolean running;
    private long startTime;

    public SimulationEngine(ManagedTagProvider provider) {
        this.provider = provider;
        this.simulations = new ConcurrentHashMap<>();
        this.random = new Random();
        this.running = false;
    }

    /**
     * Start the simulation engine.
     */
    public void start() {
        if (!running) {
            running = true;
            startTime = System.currentTimeMillis();
            logger.info("Simulation engine started");
        }
    }

    /**
     * Stop the simulation engine.
     */
    public void stop() {
        running = false;
        logger.info("Simulation engine stopped");
    }

    /**
     * Add a simulation for a tag.
     *
     * @param tagPath Tag path
     * @param type    Simulation type
     * @param config  Configuration parameters
     */
    public void addSimulation(String tagPath, SimulationType type, SimulationConfig config) {
        config.type = type;
        simulations.put(tagPath, config);
        logger.debug("Added {} simulation for tag: {}", type, tagPath);
    }

    /**
     * Remove simulation for a tag.
     *
     * @param tagPath Tag path
     */
    public void removeSimulation(String tagPath) {
        simulations.remove(tagPath);
        logger.debug("Removed simulation for tag: {}", tagPath);
    }

    /**
     * Clear all simulations.
     */
    public void clearSimulations() {
        simulations.clear();
        logger.info("Cleared all simulations");
    }

    @Override
    public void run() {
        if (!running) {
            return;
        }

        long elapsed = System.currentTimeMillis() - startTime;
        double elapsedSeconds = elapsed / 1000.0;

        // Update all simulated tags
        for (Map.Entry<String, SimulationConfig> entry : simulations.entrySet()) {
            String tagPath = entry.getKey();
            SimulationConfig config = entry.getValue();

            try {
                Object value = calculateValue(config, elapsedSeconds);
                provider.updateValue(tagPath, value, QualityCode.Good);
            } catch (Exception e) {
                logger.error("Error updating simulated tag: {}", tagPath, e);
            }
        }
    }

    /**
     * Calculate the simulated value based on configuration.
     */
    private Object calculateValue(SimulationConfig config, double elapsedSeconds) {
        switch (config.type) {
            case RAMP:
                return calculateRamp(config, elapsedSeconds);

            case SINE:
                return calculateSine(config, elapsedSeconds);

            case RANDOM:
                return calculateRandom(config);

            case TOGGLE:
                return calculateToggle(config, elapsedSeconds);

            case PULSE:
                return calculatePulse(config, elapsedSeconds);

            case NOISE:
                return calculateNoise(config);

            case CONSTANT:
            default:
                return config.value;
        }
    }

    /**
     * Ramp: Linear increase from min to max, then restart.
     */
    private Object calculateRamp(SimulationConfig config, double elapsedSeconds) {
        double period = config.period > 0 ? config.period : 10.0;
        double phase = (elapsedSeconds % period) / period;
        double range = config.max - config.min;
        return config.min + (range * phase);
    }

    /**
     * Sine: Sinusoidal oscillation between min and max.
     */
    private Object calculateSine(SimulationConfig config, double elapsedSeconds) {
        double period = config.period > 0 ? config.period : 10.0;
        double frequency = 2 * Math.PI / period;
        double amplitude = (config.max - config.min) / 2.0;
        double offset = (config.max + config.min) / 2.0;
        return offset + amplitude * Math.sin(frequency * elapsedSeconds);
    }

    /**
     * Random: Random value between min and max.
     */
    private Object calculateRandom(SimulationConfig config) {
        if (config.isInteger) {
            int min = (int) config.min;
            int max = (int) config.max;
            return min + random.nextInt(max - min + 1);
        } else {
            double range = config.max - config.min;
            return config.min + (random.nextDouble() * range);
        }
    }

    /**
     * Toggle: Boolean alternating between true/false.
     */
    private Object calculateToggle(SimulationConfig config, double elapsedSeconds) {
        double period = config.period > 0 ? config.period : 2.0;
        return (elapsedSeconds % period) < (period / 2.0);
    }

    /**
     * Pulse: Boolean pulse (true for short duration, then false).
     */
    private Object calculatePulse(SimulationConfig config, double elapsedSeconds) {
        double period = config.period > 0 ? config.period : 5.0;
        double dutyCycle = config.dutyCycle > 0 ? config.dutyCycle : 0.1;
        return (elapsedSeconds % period) < (period * dutyCycle);
    }

    /**
     * Noise: Random noise around a center value.
     */
    private Object calculateNoise(SimulationConfig config) {
        double amplitude = (config.max - config.min) / 2.0;
        double center = (config.max + config.min) / 2.0;
        return center + (random.nextGaussian() * amplitude * 0.3);
    }

    /**
     * Get the number of active simulations.
     */
    public int getSimulationCount() {
        return simulations.size();
    }

    /**
     * Check if simulation engine is running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Check if a tag has an active simulation.
     *
     * @param tagPath Tag path to check
     * @return True if tag has an active simulation
     */
    public boolean hasSimulation(String tagPath) {
        return simulations.containsKey(tagPath);
    }

    /**
     * Simulation types.
     */
    public enum SimulationType {
        CONSTANT,  // Fixed value
        RAMP,      // Linear ramp from min to max
        SINE,      // Sinusoidal oscillation
        RANDOM,    // Random values
        TOGGLE,    // Boolean toggle
        PULSE,     // Boolean pulse
        NOISE      // Random noise around center
    }

    /**
     * Simulation configuration.
     */
    public static class SimulationConfig {
        public SimulationType type = SimulationType.CONSTANT;
        public double min = 0.0;
        public double max = 100.0;
        public double value = 50.0;
        public double period = 10.0;  // Period in seconds
        public double dutyCycle = 0.5; // For pulse (0-1)
        public boolean isInteger = false;

        public static SimulationConfig ramp(double min, double max, double period) {
            SimulationConfig config = new SimulationConfig();
            config.type = SimulationType.RAMP;
            config.min = min;
            config.max = max;
            config.period = period;
            return config;
        }

        public static SimulationConfig sine(double min, double max, double period) {
            SimulationConfig config = new SimulationConfig();
            config.type = SimulationType.SINE;
            config.min = min;
            config.max = max;
            config.period = period;
            return config;
        }

        public static SimulationConfig random(double min, double max, boolean isInteger) {
            SimulationConfig config = new SimulationConfig();
            config.type = SimulationType.RANDOM;
            config.min = min;
            config.max = max;
            config.isInteger = isInteger;
            return config;
        }

        public static SimulationConfig toggle(double period) {
            SimulationConfig config = new SimulationConfig();
            config.type = SimulationType.TOGGLE;
            config.period = period;
            return config;
        }

        public static SimulationConfig pulse(double period, double dutyCycle) {
            SimulationConfig config = new SimulationConfig();
            config.type = SimulationType.PULSE;
            config.period = period;
            config.dutyCycle = dutyCycle;
            return config;
        }
    }
}
