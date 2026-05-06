package com.inductiveautomation.logixemulator.gateway.device;

import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WriteSyncHelpers}. The helper extracts the
 * ThreadLocal write-sync bookkeeping from {@code AddressSpaceBuilder} so the
 * filter logic can be exercised without spinning up an OPC-UA server.
 */
class WriteSyncHelpersTest {

    @AfterEach
    void cleanup() {
        // Defensive — the public API always clears the guard, but tests that
        // exercise misuse still want a clean slate for the next test.
        WriteSyncHelpers.clearForTest();
    }

    @Test
    @DisplayName("currentlyWriting() returns null when no write is in flight")
    void currentlyWritingNullByDefault() {
        assertThat(WriteSyncHelpers.currentlyWriting()).isNull();
        assertThat(WriteSyncHelpers.isReentrant()).isFalse();
    }

    @Test
    @DisplayName("runGuarded() exposes the initiator NodeId during propagation")
    void runGuardedExposesInitiator() {
        NodeId initiator = new NodeId(2, "Controller:Global.MyTag");
        AtomicReference<NodeId> seen = new AtomicReference<>();

        WriteSyncHelpers.runGuarded(initiator, () -> seen.set(WriteSyncHelpers.currentlyWriting()));

        assertThat(seen.get()).isEqualTo(initiator);
    }

    @Test
    @DisplayName("runGuarded() reports re-entrancy from inside the propagation")
    void runGuardedReportsReentrancy() {
        AtomicBoolean reentrantInside = new AtomicBoolean();

        WriteSyncHelpers.runGuarded(
            new NodeId(2, "n1"),
            () -> reentrantInside.set(WriteSyncHelpers.isReentrant())
        );

        assertThat(reentrantInside).isTrue();
    }

    @Test
    @DisplayName("runGuarded() clears the ThreadLocal after a normal return")
    void runGuardedClearsAfterNormalReturn() {
        WriteSyncHelpers.runGuarded(new NodeId(2, "n1"), () -> { /* no-op */ });

        assertThat(WriteSyncHelpers.currentlyWriting()).isNull();
        assertThat(WriteSyncHelpers.isReentrant()).isFalse();
    }

    @Test
    @DisplayName("runGuarded() clears the ThreadLocal even if propagation throws")
    void runGuardedClearsOnException() {
        assertThatThrownBy(() ->
            WriteSyncHelpers.runGuarded(
                new NodeId(2, "n1"),
                () -> { throw new RuntimeException("boom"); }
            )
        ).isInstanceOf(RuntimeException.class);

        assertThat(WriteSyncHelpers.currentlyWriting()).isNull();
    }

    @Test
    @DisplayName("nested runGuarded() preserves the outer guard via the early-out check")
    void nestedRunGuardedRespectsExistingGuard() {
        // This mirrors how AddressSpaceBuilder uses the helper: the SECOND
        // call must early-out via isReentrant(), not invoke runGuarded again.
        NodeId outer = new NodeId(2, "outer");
        AtomicInteger propagationCount = new AtomicInteger();

        WriteSyncHelpers.runGuarded(outer, () -> {
            // Simulate the sibling's filter: it sees the guard and skips.
            if (!WriteSyncHelpers.isReentrant()) {
                propagationCount.incrementAndGet();
            }
        });

        assertThat(propagationCount.get()).isZero();
        assertThat(WriteSyncHelpers.currentlyWriting()).isNull();
    }

    @Test
    @DisplayName("runGuarded() rejects a null initiator")
    void runGuardedRejectsNullInitiator() {
        assertThatThrownBy(() -> WriteSyncHelpers.runGuarded(null, () -> {}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("initiator");
    }

    @Test
    @DisplayName("runGuarded() rejects a null Runnable")
    void runGuardedRejectsNullRunnable() {
        assertThatThrownBy(() -> WriteSyncHelpers.runGuarded(new NodeId(2, "n1"), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("propagation");
    }

    @Test
    @DisplayName("guard state is per-thread")
    void guardIsThreadLocal() throws InterruptedException {
        AtomicBoolean otherThreadSawGuard = new AtomicBoolean();
        AtomicBoolean otherThreadFinished = new AtomicBoolean();
        Object lock = new Object();

        WriteSyncHelpers.runGuarded(new NodeId(2, "n1"), () -> {
            Thread other = new Thread(() -> {
                otherThreadSawGuard.set(WriteSyncHelpers.isReentrant());
                synchronized (lock) {
                    otherThreadFinished.set(true);
                    lock.notifyAll();
                }
            });
            other.start();
            synchronized (lock) {
                while (!otherThreadFinished.get()) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });

        assertThat(otherThreadSawGuard).isFalse();
    }
}
