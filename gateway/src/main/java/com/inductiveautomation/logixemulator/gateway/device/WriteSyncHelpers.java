package com.inductiveautomation.logixemulator.gateway.device;

import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

/**
 * Static utilities supporting the synchronized-write pattern used by
 * {@link AddressSpaceBuilder} when keeping a duplicate "long-path"/"short-path"
 * UA-node pair in sync.
 *
 * <p>The pattern is: the first incoming write sets a {@link ThreadLocal} guard
 * to its own NodeId, propagates the value to the sibling, then clears the
 * guard. Sibling filters early-out while the guard is set. This file extracts
 * that bookkeeping out of the address-space builder so it can be unit-tested
 * in isolation. Behaviour is unchanged.</p>
 *
 * <p>Note: this still relies on the OPC-UA stack invoking the per-node filter
 * on a single thread per logical write. The review (SEV-2 in
 * {@code mod-plc-emulator.md}) flags that assumption as fragile but a
 * structural change to {@code AtomicReference}/listener-based equality is out
 * of scope for the Sprint 3 god-class refactor — this extraction only makes
 * the existing semantics testable.</p>
 */
public final class WriteSyncHelpers {

    /** Thread-scoped guard tracking which node initiated the current write. */
    private static final ThreadLocal<NodeId> CURRENTLY_WRITING_NODE = new ThreadLocal<>();

    private WriteSyncHelpers() {
        // utility class — not instantiable
    }

    /**
     * @return the NodeId of the node currently mid-write on this thread, or
     *     {@code null} if no write is in flight.
     */
    public static NodeId currentlyWriting() {
        return CURRENTLY_WRITING_NODE.get();
    }

    /**
     * @return {@code true} if a write is already in flight on this thread.
     */
    public static boolean isReentrant() {
        return CURRENTLY_WRITING_NODE.get() != null;
    }

    /**
     * Run {@code propagation} inside the re-entrancy guard. The guard is set
     * to {@code initiator} for the duration of the call and cleared even if
     * the propagation throws.
     *
     * <p>Callers that detect {@link #isReentrant()} should skip the
     * propagation entirely; that early-out is the whole reason the guard
     * exists.</p>
     *
     * @param initiator NodeId of the node initiating the write
     * @param propagation work to run with the guard active (typically a
     *     sibling-node setValue)
     */
    public static void runGuarded(NodeId initiator, Runnable propagation) {
        if (initiator == null) {
            throw new IllegalArgumentException("initiator NodeId must not be null");
        }
        if (propagation == null) {
            throw new IllegalArgumentException("propagation Runnable must not be null");
        }
        CURRENTLY_WRITING_NODE.set(initiator);
        try {
            propagation.run();
        } finally {
            CURRENTLY_WRITING_NODE.remove();
        }
    }

    /**
     * Test-only hook to forcibly clear the ThreadLocal. Production code should
     * never need this — the guard is always cleared by {@link #runGuarded}.
     */
    static void clearForTest() {
        CURRENTLY_WRITING_NODE.remove();
    }
}
