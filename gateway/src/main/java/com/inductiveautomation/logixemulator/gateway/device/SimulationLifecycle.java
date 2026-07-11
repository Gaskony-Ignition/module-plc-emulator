package com.inductiveautomation.logixemulator.gateway.device;

import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.logixemulator.gateway.OpcUaSimulationEngine;
import org.eclipse.milo.opcua.sdk.server.UaNodeManager;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import java.util.function.Function;
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
 *   <li><b>C11 / B3-v10</b>: the engine ticks over its own registry of simulated
 *       tags, resolving each to the live {@link UaVariableNode} via a key-&gt;node
 *       resolver built here. It no longer iterates the device
 *       {@code SubscriptionModel.getDataItems()} list — that list is empty on a real
 *       Milo server, which is why simulation silently did nothing on a real gateway.
 *       See {@link OpcUaSimulationEngine} class Javadoc for the full incident.</li>
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
     * Build a new simulation engine, start it, and publish the running engine
     * back to the device. On failure publishes {@code null} (the device's prior
     * contract was to leave its engine reference null on init failure).
     */
    public void initialize() {
        try {
            LogixEmulatorConfig.SimulationPattern pattern = config.simulation().defaultPattern();
            int updateInterval = config.simulation().updateInterval();

            // B3-v10: resolve a simulated-tag key straight to its canonical variable node,
            // via the SAME DeviceContext.nodeId(...) + node-manager path the write dispatcher
            // uses (so engine and write path always target the identical node object). The
            // engine ticks over its own registry and calls setValue on this node — it does
            // NOT depend on the device SubscriptionModel's DataItem list (empty on a real
            // server; see OpcUaSimulationEngine class Javadoc).
            Function<String, UaVariableNode> nodeResolver = tagKey -> {
                NodeId nodeId = context.nodeId(tagKey);
                UaNode node = nodeManagerSupplier.get().get(nodeId);
                return (node instanceof UaVariableNode variableNode) ? variableNode : null;
            };

            OpcUaSimulationEngine engine = new OpcUaSimulationEngine(
                pattern,
                updateInterval,
                nodeResolver
            );

            engine.start();

            enginePublisher.accept(engine);

            logger.info(
                "Simulation engine started: {} pattern, {}ms interval",
                pattern, updateInterval
            );

        } catch (Exception e) {
            logger.error("Failed to initialize simulation engine", e);
            enginePublisher.accept(null);
        }
    }
}
