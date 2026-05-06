package com.inductiveautomation.logixemulator.gateway.device;

import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.logixemulator.gateway.OpcUaSimulationEngine;
import org.eclipse.milo.opcua.sdk.server.UaNodeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns construction and startup of an {@link OpcUaSimulationEngine} for the
 * device. Extracted from {@code LogixEmulatorDevice} as part of the Sprint 3
 * P6 refactor.
 *
 * <p>The lifecycle uses a {@link Consumer} to publish the freshly-built
 * engine back to the device's volatile field — the device cannot construct
 * and assign in one expression because its single-source-of-truth
 * {@code simulationEngine} field must be visible to the C11 supplier
 * registered earlier. Pattern: build → start → publish via consumer.</p>
 *
 * <p>Sprint 2 invariants preserved:</p>
 * <ul>
 *   <li><b>C11</b>: {@link OpcUaSimulationEngine#start(Supplier)} is called
 *       with a supplier that returns the live {@code DataItem} list per tick.</li>
 *   <li><b>C12</b>: the engine's {@code recalibrate(...)} method is invoked
 *       by {@link TagWriteDispatcher} on user writes — not by this class.</li>
 * </ul>
 */
public final class SimulationLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(SimulationLifecycle.class);

    private final DeviceContext context;
    private final LogixEmulatorConfig config;
    private final Supplier<UaNodeManager> nodeManagerSupplier;
    private final Consumer<OpcUaSimulationEngine> enginePublisher;

    public SimulationLifecycle(
        DeviceContext context,
        LogixEmulatorConfig config,
        Supplier<UaNodeManager> nodeManagerSupplier,
        Consumer<OpcUaSimulationEngine> enginePublisher
    ) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (nodeManagerSupplier == null) {
            throw new IllegalArgumentException("nodeManagerSupplier must not be null");
        }
        if (enginePublisher == null) {
            throw new IllegalArgumentException("enginePublisher must not be null");
        }
        this.context = context;
        this.config = config;
        this.nodeManagerSupplier = nodeManagerSupplier;
        this.enginePublisher = enginePublisher;
    }

    /**
     * Build a new simulation engine, start it with a per-tick data-items
     * supplier (C11), and publish the running engine back to the device.
     * On failure publishes {@code null} (the device's prior contract was to
     * leave its engine reference null on init failure).
     */
    public void initialize() {
        try {
            int initialCount = context.getSubscriptionModel()
                .getDataItems(context.getName())
                .size();

            if (initialCount == 0) {
                logger.info(
                    "No data items currently subscribed - simulation engine will pick them up "
                    + "as they are created (supplier-based)"
                );
            }

            LogixEmulatorConfig.SimulationPattern pattern = config.simulation().defaultPattern();
            int updateInterval = config.simulation().updateInterval();

            OpcUaSimulationEngine engine = new OpcUaSimulationEngine(
                pattern,
                updateInterval,
                nodeId -> nodeManagerSupplier.get().get(nodeId)
            );

            // C11: per-tick supplier so subscriptions added/removed mid-run
            // are observed without restarting the engine.
            engine.start(() ->
                context.getSubscriptionModel().getDataItems(context.getName())
            );

            enginePublisher.accept(engine);

            logger.info(
                "Simulation engine started: {} pattern, {}ms interval, {} initial tags",
                pattern, updateInterval, initialCount
            );

        } catch (Exception e) {
            logger.error("Failed to initialize simulation engine", e);
            enginePublisher.accept(null);
        }
    }
}
