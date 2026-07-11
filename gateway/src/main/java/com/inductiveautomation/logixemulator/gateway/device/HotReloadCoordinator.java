package com.inductiveautomation.logixemulator.gateway.device;

import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.logixemulator.gateway.FileWatcher;
import org.eclipse.milo.opcua.sdk.server.UaNodeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Coordinates file-watcher setup and the hot-reload pipeline (incremental
 * update vs. full rebuild) for a {@code LogixEmulatorDevice}. Extracted as
 * part of the Sprint 3 P6 god-class refactor.
 *
 * <p>The device retains ownership of the wider state (parsedData, status,
 * simulation-engine restart) and passes the coordinator a small set of
 * callbacks. The coordinator never reaches back into the device directly —
 * it only invokes the callbacks it was given.</p>
 *
 * <p>Sprint 1/2 invariants preserved exactly: incremental updates are tried
 * first; only structural changes trigger full rebuild; deviceStatus
 * transitions match the prior in-class implementation byte-for-byte.</p>
 */
public final class HotReloadCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(HotReloadCoordinator.class);

    private final DeviceContext context;
    private final LogixEmulatorConfig config;
    private final Supplier<UaNodeManager> nodeManagerSupplier;

    /** Callbacks back to the device — keep the dependency one-way. */
    private final Consumer<String> statusSetter;
    private final Supplier<JsonObject> parsedDataGetter;
    private final Consumer<JsonObject> parsedDataSetter;
    private final Consumer<JsonObject> fullRebuildHandler;
    private final Consumer<File> fileChangeHandler;

    private volatile FileWatcher fileWatcher;

    public HotReloadCoordinator(
        DeviceContext context,
        LogixEmulatorConfig config,
        Supplier<UaNodeManager> nodeManagerSupplier,
        Consumer<String> statusSetter,
        Supplier<JsonObject> parsedDataGetter,
        Consumer<JsonObject> parsedDataSetter,
        Consumer<JsonObject> fullRebuildHandler,
        Consumer<File> fileChangeHandler
    ) {
        this.context = context;
        this.config = config;
        this.nodeManagerSupplier = nodeManagerSupplier;
        this.statusSetter = statusSetter;
        this.parsedDataGetter = parsedDataGetter;
        this.parsedDataSetter = parsedDataSetter;
        this.fullRebuildHandler = fullRebuildHandler;
        this.fileChangeHandler = fileChangeHandler;
    }

    /** @return the running watcher, or {@code null} if not started. */
    public FileWatcher getFileWatcher() {
        return fileWatcher;
    }

    /**
     * Setup file watcher for hot reload. Wires reloads through
     * {@link #fileChangeHandler}.
     */
    public void setupFileWatcher(String currentFilePath) {
        if (currentFilePath == null) {
            logger.warn("Cannot setup file watcher - no file path available");
            return;
        }

        try {
            int reloadInterval = config.parser().reloadInterval();

            fileWatcher = new FileWatcher(
                currentFilePath,
                fileChangeHandler::accept,
                reloadInterval
            );
            fileWatcher.start();
            logger.info(
                "File watcher started for: {} ({}s interval)",
                currentFilePath, reloadInterval
            );
        } catch (Exception e) {
            logger.error("Failed to setup file watcher", e);
            fileWatcher = null;
        }
    }

    /**
     * Stop the file watcher if it is running. Safe to call from shutdown
     * and address-space-clear paths.
     */
    public void stopFileWatcher() {
        if (fileWatcher != null && fileWatcher.isRunning()) {
            fileWatcher.stop();
            fileWatcher = null;
            logger.debug("Stopped file watcher");
        }
    }

    /**
     * Handle a file-change event from the watcher. Tries incremental updates
     * first; falls back to a full rebuild via the callback when structural
     * changes are detected.
     */
    public void handleFileChange(String currentFilePath, FilePreparation filePreparation) {
        logger.info("File change detected, reloading device: {}", context.getName());
        try {
            statusSetter.accept("Reloading");

            JsonObject newData = filePreparation.parseFile(currentFilePath);
            if (newData == null) {
                // Name the file, not just "a file", so the honest 4xx this status feeds into
                // (DeviceController/VersionController's B4 gate) is specific enough for a caller
                // to tell which upload/revert failed (FIX-1/FIX-6).
                statusSetter.accept(
                    "Error: Failed to parse file '" + new File(currentFilePath).getName() + "' after reload");
                logger.error("Failed to parse file during hot reload: {}", currentFilePath);
                return;
            }

            JsonObject existing = parsedDataGetter.get();
            if (existing != null) {
                IncrementalAddressSpaceUpdater updater = new IncrementalAddressSpaceUpdater(
                    nodeId -> nodeManagerSupplier.get().get(nodeId),
                    context::nodeId,
                    context.getName()
                );

                IncrementalAddressSpaceUpdater.CompareResult changes = updater.compare(existing, newData);

                if (updater.canApplyIncrementally(changes)) {
                    logger.info(
                        "Applying incremental update ({} value changes)",
                        changes.changedTags.size()
                    );
                    updater.applyIncrementalUpdate(changes);
                    parsedDataSetter.accept(newData);
                    statusSetter.accept("Running");
                    logger.info("Incremental update complete - no rebuild required");
                    return;
                }

                logger.info("Structural changes detected - performing full rebuild");
            }

            fullRebuildHandler.accept(newData);

        } catch (Exception e) {
            logger.error("Error handling file change", e);
            statusSetter.accept("Error: Hot reload failed - " + e.getMessage());
        }
    }
}
