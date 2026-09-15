package com.inductiveautomation.logixemulator.gateway.device;

import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.logixemulator.gateway.OpcUaSimulationEngine;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.server.UaNodeManager;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Dispatches OPC-UA tag read/write operations on behalf of
 * {@link LogixEmulatorDevice}, including the simulation-baseline recalibration
 * that fires after a successful user write to a simulated tag (Sprint 2 C12
 * behaviour).
 *
 * <p>Extracted from the 1040-line {@code LogixEmulatorDevice} god class as
 * part of Sprint 3 P6. Public API surface mirrors the original device
 * methods exactly so callers see no change.</p>
 *
 * <p>The dispatcher resolves OPC-UA nodes via a {@link UaNodeManager} obtained
 * from a supplier (so the device can pass {@code this::getNodeManager} without
 * leaking ownership) and converts string tag paths into {@link NodeId}s via
 * the provided {@link DeviceContext}. The simulation engine reference is
 * pulled from a supplier on every write so that engine restarts (e.g. on
 * hot-reload {@code performFullRebuild}) are seen without re-wiring.</p>
 */
public final class TagWriteDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(TagWriteDispatcher.class);

    private final DeviceContext context;
    private final Supplier<UaNodeManager> nodeManagerSupplier;
    private final Supplier<OpcUaSimulationEngine> simulationEngineSupplier;

    public TagWriteDispatcher(
        DeviceContext context,
        Supplier<UaNodeManager> nodeManagerSupplier,
        Supplier<OpcUaSimulationEngine> simulationEngineSupplier
    ) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (nodeManagerSupplier == null) {
            throw new IllegalArgumentException("nodeManagerSupplier must not be null");
        }
        if (simulationEngineSupplier == null) {
            throw new IllegalArgumentException("simulationEngineSupplier must not be null");
        }
        this.context = context;
        this.nodeManagerSupplier = nodeManagerSupplier;
        this.simulationEngineSupplier = simulationEngineSupplier;
    }

    /**
     * Read the current value of a tag from the OPC-UA address space.
     *
     * @param tagPath The tag path (e.g., "Controller:Global/MyTag")
     * @return The current value, or null if not found
     */
    public Object readTagValue(String tagPath) {
        try {
            NodeId nodeId = context.nodeId(tagPath);
            UaNode node = nodeManagerSupplier.get().get(nodeId);
            if (node instanceof UaVariableNode varNode) {
                DataValue dataValue = varNode.getValue();
                if (dataValue != null && dataValue.getValue() != null) {
                    return dataValue.getValue().getValue();
                }
            }
        } catch (Exception e) {
            logger.debug("Could not read tag value for path: {}", tagPath, e);
        }
        return null;
    }

    /**
     * Outcome of a {@link #writeTagValue(String, Object)} call. Distinguishes a
     * rejected read-only write (FIX-2) from "tag not found", since the two
     * warrant different HTTP responses from the callers up the stack.
     */
    public enum WriteResult {
        /** The write succeeded. */
        SUCCESS,
        /** No such tag, or the node could not be resolved to a writable variable. */
        NOT_FOUND,
        /** The tag exists but is flagged read-only (ExternalAccess="Read Only" / Constant) and the write was refused. */
        READ_ONLY
    }

    /**
     * Write a value to a tag in the OPC-UA address space and, if the tag is
     * currently being simulated, recalibrate the simulation baseline so the
     * next tick continues from the user's value (C12 behaviour).
     *
     * <p>FIX-2: a direct {@code UaVariableNode.setValue()} call bypasses
     * Milo's attribute-filter chain, so the node's own {@code AccessLevel}
     * (which the OPC-UA layer itself honours on a real client write) is not
     * enforced automatically here. The read-only check below re-uses that
     * same AccessLevel — set by {@code AddressSpaceBuilder} from the L5X
     * {@code ExternalAccess} attribute — as the single source of truth,
     * rather than tracking read-only status separately.</p>
     *
     * @param tagPath The tag path (e.g., "Controller:Global/MyTag")
     * @param value The value to write
     * @return {@link WriteResult#SUCCESS}, {@link WriteResult#NOT_FOUND}, or
     *     {@link WriteResult#READ_ONLY}
     */
    public WriteResult writeTagValue(String tagPath, Object value) {
        try {
            NodeId nodeId = context.nodeId(tagPath);
            UaNode node = nodeManagerSupplier.get().get(nodeId);
            if (node instanceof UaVariableNode varNode) {
                if (isReadOnly(varNode)) {
                    logger.debug("Rejected write to read-only tag {}", tagPath);
                    return WriteResult.READ_ONLY;
                }

                Variant variant = new Variant(value);
                DataValue dataValue = new DataValue(variant);
                varNode.setValue(dataValue);
                logger.debug("Wrote value {} to tag {}", value, tagPath);

                // C12: tell the simulation engine that this user-written
                // value is the new baseline. The next tick will continue
                // *from* this value rather than overwriting it within the
                // configured update interval.
                OpcUaSimulationEngine engine = simulationEngineSupplier.get();
                if (engine != null && engine.isTagSimulated(tagPath)) {
                    engine.recalibrate(tagPath, value);
                }
                return WriteResult.SUCCESS;
            }
        } catch (Exception e) {
            logger.error("Could not write tag value for path: {}", tagPath, e);
        }
        return WriteResult.NOT_FOUND;
    }

    /**
     * Report whether a tag is flagged read-only in the OPC-UA address space,
     * without attempting to write to it. Used by {@link TagSimulationFacade}
     * (via {@code LogixEmulatorDevice}) to refuse assigning a simulation
     * pattern to a read-only tag before the simulation engine ever starts
     * writing to it on tick (FIX-2 part 2) — the engine writes to nodes
     * directly and, like the REST write path, does not itself consult
     * AccessLevel.
     *
     * @param tagPath The tag path (e.g., "Controller:Global/MyTag")
     * @return true if the tag exists and lacks {@code AccessLevel.CurrentWrite};
     *     false if it is writable, or if it cannot be resolved at all (unknown
     *     tags are not considered read-only by this method — that is a
     *     separate "not found" concern for the caller).
     */
    public boolean isReadOnly(String tagPath) {
        try {
            NodeId nodeId = context.nodeId(tagPath);
            UaNode node = nodeManagerSupplier.get().get(nodeId);
            if (node instanceof UaVariableNode varNode) {
                return isReadOnly(varNode);
            }
        } catch (Exception e) {
            logger.debug("Could not determine read-only status for path: {}", tagPath, e);
        }
        return false;
    }

    private static boolean isReadOnly(UaVariableNode varNode) {
        EnumSet<AccessLevel> accessLevels = AccessLevel.fromValue(varNode.getAccessLevel());
        return !accessLevels.contains(AccessLevel.CurrentWrite);
    }

    /**
     * Get all variable nodes from the address space with their current values.
     * Used by the tag browser API.
     *
     * @return Map of tag path to current value (keyed by node identifier
     *     {@link Object#toString()} per the existing contract).
     */
    public Map<String, Object> getAllTagValues() {
        Map<String, Object> values = new HashMap<>();
        try {
            nodeManagerSupplier.get().getNodes().forEach(node -> {
                if (node instanceof UaVariableNode varNode) {
                    String browseName = varNode.getBrowseName().getName();
                    try {
                        DataValue dataValue = varNode.getValue();
                        if (dataValue != null && dataValue.getValue() != null) {
                            String nodeIdStr = varNode.getNodeId().getIdentifier().toString();
                            values.put(nodeIdStr, dataValue.getValue().getValue());
                        }
                    } catch (Exception e) {
                        logger.trace("Could not read value for node {}: {}", browseName, e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            logger.error("Error getting all tag values", e);
        }
        return values;
    }
}
