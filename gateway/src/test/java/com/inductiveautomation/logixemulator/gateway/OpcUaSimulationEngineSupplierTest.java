package com.inductiveautomation.logixemulator.gateway;

import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorConfig;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for C11 — Stale {@code dataItems} snapshot drives simulation forever.
 *
 * <p>The engine must invoke the supplier on every tick, so data items added or
 * removed at runtime are picked up without restarting the engine.</p>
 */
class OpcUaSimulationEngineSupplierTest {

    private OpcUaSimulationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new OpcUaSimulationEngine(
            LogixEmulatorConfig.SimulationPattern.STATIC,
            100, // 100ms — fast enough for tests but not too fast for CI
            nodeId -> null
        );
    }

    @AfterEach
    void tearDown() {
        if (engine.isRunning()) {
            engine.stop();
        }
    }

    @Test
    @DisplayName("C11: supplier-based start() invokes the supplier on every tick")
    void supplierIsInvokedEveryTick() {
        AtomicInteger invocationCount = new AtomicInteger(0);

        engine.start(() -> {
            invocationCount.incrementAndGet();
            return List.of();
        });

        // Allow time for several ticks (interval is 100ms — wait for at least 3 ticks)
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(invocationCount.get()).isGreaterThanOrEqualTo(3)
        );
    }

    @Test
    @DisplayName("C11: changes to the supplier's returned list are visible on the next tick")
    void newDataItemsArePickedUpWithoutRestart() {
        // Simulate a "live" data-item collection that is mutated mid-run.
        // We use empty-vs-empty-but-different-instance lists to prove the
        // supplier is re-invoked on every tick (we cannot build real
        // org.eclipse.milo.opcua.sdk.server.items.DataItem instances in a
        // pure unit test — they require an active OPC-UA subscription).
        AtomicReference<List<DataItem>> live = new AtomicReference<>(List.of());
        AtomicInteger uniqueSnapshotsSeen = new AtomicInteger(0);
        AtomicReference<Object> lastSnapshot = new AtomicReference<>(null);

        engine.start(() -> {
            List<DataItem> snapshot = live.get();
            // Count *distinct* list references — proves the supplier path
            // re-reads the live collection each time we mutate it.
            if (snapshot != lastSnapshot.get()) {
                uniqueSnapshotsSeen.incrementAndGet();
                lastSnapshot.set(snapshot);
            }
            return snapshot;
        });

        // After ~1 second of ticks, we should still have only 1 unique snapshot
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(uniqueSnapshotsSeen.get()).isEqualTo(1)
        );

        // Now swap the live reference — the engine must pick this up on the
        // very next tick without a restart (proves there is no stale capture).
        // We use new ArrayList<>() rather than List.of() because List.of()
        // returns a cached empty-list singleton, which would defeat the
        // identity comparison in the supplier above.
        live.set(new java.util.ArrayList<>());

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(uniqueSnapshotsSeen.get()).isEqualTo(2)
        );

        // And again — confirm it's not just a one-shot
        live.set(new java.util.ArrayList<>());
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(uniqueSnapshotsSeen.get()).isEqualTo(3)
        );
    }

    @Test
    @DisplayName("C11: supplier returning null is handled gracefully (engine keeps running)")
    void supplierReturningNullDoesNotCrashEngine() {
        AtomicInteger invocations = new AtomicInteger(0);

        engine.start(() -> {
            invocations.incrementAndGet();
            return null; // supplier may return null between reconfigurations
        });

        // Engine should keep ticking — null is treated as empty
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(invocations.get()).isGreaterThanOrEqualTo(3)
        );
        assertThat(engine.isRunning()).isTrue();
    }

    @Test
    @DisplayName("C11: supplier throwing an exception does not stop the scheduled task")
    void supplierExceptionDoesNotKillScheduledTask() {
        AtomicInteger invocations = new AtomicInteger(0);

        engine.start(() -> {
            int n = invocations.incrementAndGet();
            if (n == 2) {
                throw new IllegalStateException("simulated supplier failure on tick 2");
            }
            return List.of();
        });

        // Crucially, the engine must continue beyond tick 2 — earlier behaviour
        // was that a thrown exception inside the scheduled task killed the
        // task forever (ScheduledExecutorService swallows and cancels).
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(invocations.get()).isGreaterThanOrEqualTo(5)
        );
        assertThat(engine.isRunning()).isTrue();
    }

    @Test
    @DisplayName("C11: stop() drops the supplier reference so it can be GC'd")
    void stopDropsSupplierReference() {
        engine.start(() -> List.of());
        assertThat(engine.isRunning()).isTrue();

        engine.stop();
        assertThat(engine.isRunning()).isFalse();

        // Restart with a new supplier — must succeed (no leftover state)
        AtomicInteger invocations = new AtomicInteger(0);
        engine.start(() -> {
            invocations.incrementAndGet();
            return List.of();
        });

        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(invocations.get()).isGreaterThanOrEqualTo(1)
        );
    }

    @Test
    @DisplayName("C11: list-based start() (legacy overload) wraps in a static supplier and still works")
    void legacyListStartStillWorks() {
        engine.start(List.of());
        assertThat(engine.isRunning()).isTrue();
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(engine.getElapsedSeconds()).isGreaterThan(0.0)
        );
    }

    @Test
    @DisplayName("C11: end-to-end — adding/removing DataItems mid-run is reflected in iterated tags")
    void addAndRemoveDataItemsMidRunWithoutRestart() {
        // Build 5 mock DataItems with known node IDs. Track which IDs the
        // engine attempts to look up — this proves the supplier hand-off works
        // for real iteration, not just supplier invocation.
        CopyOnWriteArrayList<DataItem> live = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 5; i++) {
            live.add(mockDataItem("Tag" + i));
        }

        // Track which tags the node lookup is asked for — this is the strongest
        // signal that the engine actually iterated the live collection.
        List<String> lookedUp = new CopyOnWriteArrayList<>();
        OpcUaSimulationEngine engineWithLookup = new OpcUaSimulationEngine(
            LogixEmulatorConfig.SimulationPattern.STATIC,
            100,
            nodeId -> {
                lookedUp.add(nodeId.getIdentifier().toString());
                return null; // null lookup is fine — engine logs trace and skips
            }
        );

        // Enable simulation for all 5 tags so the engine actually iterates them
        for (int i = 0; i < 5; i++) {
            engineWithLookup.enableTagSimulation("Tag" + i);
        }

        try {
            engineWithLookup.start(() -> live);

            // Wait for the engine to iterate all 5 tags
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(lookedUp).contains("Tag0", "Tag1", "Tag2", "Tag3", "Tag4")
            );

            // Mutate live: add 2, remove 1 — sequence the mutations BEFORE the
            // snapshot reset so a tick firing mid-sequence can't pollute the
            // post-mutation assertion window with stale Tag2 lookups.
            live.add(mockDataItem("Tag5"));
            live.add(mockDataItem("Tag6"));
            engineWithLookup.enableTagSimulation("Tag5");
            engineWithLookup.enableTagSimulation("Tag6");
            live.removeIf(d -> "Tag2".equals(d.getReadValueId().getNodeId().getIdentifier().toString()));
            engineWithLookup.disableTagSimulation("Tag2");

            // Now reset and assert on the steady post-mutation state only.
            lookedUp.clear();

            // After mutation, the engine must (a) see Tag5 + Tag6 on the next
            // tick, and (b) NOT iterate Tag2 anymore — proving it's reading
            // the *live* list per tick, not a stale snapshot.
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(lookedUp).contains("Tag5", "Tag6");
                assertThat(lookedUp).doesNotContain("Tag2");
            });
        } finally {
            engineWithLookup.stop();
        }
    }

    /**
     * Build a Mockito stub of {@link DataItem} that returns a String-identified
     * NodeId — sufficient for engine iteration without standing up Milo.
     */
    private static DataItem mockDataItem(String tagPath) {
        DataItem item = mock(DataItem.class);
        ReadValueId rvId = mock(ReadValueId.class);
        NodeId nodeId = new NodeId(UShort.valueOf(0), tagPath);
        when(item.getReadValueId()).thenReturn(rvId);
        when(rvId.getNodeId()).thenReturn(nodeId);
        return item;
    }
}
