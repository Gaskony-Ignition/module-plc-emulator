package com.inductiveautomation.logixemulator.gateway.device;

import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.UaNodeManager;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNodeContext;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Owns root-folder creation, address-space build, and per-device node teardown
 * for a {@code LogixEmulatorDevice}.
 *
 * <p>Extracted from the 1040-line god class as part of the Sprint 3 P6
 * refactor. The orchestration paths in the device that mutate broader state
 * (stop sim engine, stop file watcher, recreate the engine afterwards) stay
 * in the device — only the address-space-specific operations live here.</p>
 *
 * <p>The lifecycle holds the current {@link UaFolderNode} root reference; the
 * device queries it via {@link #getRootNodeId()} to expose the public
 * {@code Device#getRootNodeId} API. Removal logic still uses the existing
 * "match by NodeId-identifier prefix" approach the review (SEV-3) flagged as
 * fragile — but the structural extraction makes a tracked-NodeIds upgrade
 * possible in a future change.</p>
 */
public final class AddressSpaceLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(AddressSpaceLifecycle.class);

    private final DeviceContext context;
    private final Supplier<UaNodeContext> nodeContextSupplier;
    private final Supplier<UaNodeManager> nodeManagerSupplier;

    private volatile UaFolderNode rootNode;

    public AddressSpaceLifecycle(
        DeviceContext context,
        Supplier<UaNodeContext> nodeContextSupplier,
        Supplier<UaNodeManager> nodeManagerSupplier
    ) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (nodeContextSupplier == null) {
            throw new IllegalArgumentException("nodeContextSupplier must not be null");
        }
        if (nodeManagerSupplier == null) {
            throw new IllegalArgumentException("nodeManagerSupplier must not be null");
        }
        this.context = context;
        this.nodeContextSupplier = nodeContextSupplier;
        this.nodeManagerSupplier = nodeManagerSupplier;
    }

    /** @return the current root NodeId, or {@code null} if no root exists. */
    public NodeId getRootNodeId() {
        UaFolderNode r = rootNode;
        return r != null ? r.getNodeId() : null;
    }

    /** @return the current root folder node, or {@code null} if not yet built. */
    public UaFolderNode getRootNode() {
        return rootNode;
    }

    /**
     * Creates the root folder node for this device and registers it with the
     * server's node manager. Replaces any prior root reference held by this
     * lifecycle.
     */
    public void createRootNode() {
        String deviceName = context.getName();
        UaFolderNode newRoot = new UaFolderNode(
            nodeContextSupplier.get(),
            context.nodeId(deviceName),
            context.qualifiedName(String.format("[%s]", deviceName)),
            new LocalizedText(String.format("[%s]", deviceName))
        );

        nodeManagerSupplier.get().addNode(newRoot);

        newRoot.addReference(new Reference(
            newRoot.getNodeId(),
            NodeIds.Organizes,
            context.getRootNodeId().expanded(),
            Reference.Direction.INVERSE
        ));

        this.rootNode = newRoot;
        logger.info("Created root node: [{}]", deviceName);
    }

    /**
     * Builds the OPC-UA address space from parsed PLC data. No-op (with
     * warning) if either the parsed data or the root node is missing.
     */
    public void buildAddressSpace(JsonObject parsedData) {
        if (parsedData == null || rootNode == null) {
            logger.error("Cannot build address space: missing parsed data or root node");
            return;
        }

        AddressSpaceBuilder builder = new AddressSpaceBuilder(
            nodeManagerSupplier.get()::addNode,
            context.getName(),
            logger
        );

        AddressSpaceBuilder.NodeContext addrContext = new AddressSpaceBuilder.NodeContext(
            nodeContextSupplier.get(),
            context
        );

        builder.buildAddressSpace(parsedData, rootNode, addrContext);

        logger.info("Address space built successfully");
    }

    /**
     * Remove all OPC-UA nodes belonging to this device from the node manager
     * and clear the lifecycle's root reference. Matches by string-prefix on
     * the NodeId identifier, consistent with the historical implementation.
     *
     * @return the number of nodes that were removed
     */
    public int removeAllDeviceNodes() {
        String deviceName = context.getName();
        logger.debug("Removing all nodes for device: {}", deviceName);

        List<NodeId> nodesToRemove = new ArrayList<>();

        nodeManagerSupplier.get().getNodes().forEach(node -> {
            NodeId nodeId = node.getNodeId();
            Object identifier = nodeId.getIdentifier();
            if (identifier instanceof String idStr) {
                if (idStr.equals(deviceName) || idStr.startsWith(deviceName + ".")) {
                    nodesToRemove.add(nodeId);
                }
            }
        });

        for (NodeId nodeId : nodesToRemove) {
            nodeManagerSupplier.get().removeNode(nodeId);
        }

        rootNode = null;
        logger.info("Removed {} nodes for device: {}", nodesToRemove.size(), deviceName);
        return nodesToRemove.size();
    }
}
