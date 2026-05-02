package com.inductiveautomation.logixemulator.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Watches a file for changes and triggers a callback when modified.
 * Uses polling mechanism for cross-platform compatibility.
 */
public class FileWatcher {

    private static final Logger logger = LoggerFactory.getLogger(FileWatcher.class);

    private final File watchedFile;
    private final Consumer<File> onChange;
    private final int checkIntervalSeconds;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> watchTask;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long lastModified;

    /**
     * Create a file watcher.
     *
     * @param filePath Path to file to watch
     * @param onChange Callback when file changes
     * @param checkIntervalSeconds How often to check for changes (seconds)
     */
    public FileWatcher(String filePath, Consumer<File> onChange, int checkIntervalSeconds) {
        this.watchedFile = new File(filePath);
        this.onChange = onChange;
        this.checkIntervalSeconds = Math.max(1, checkIntervalSeconds); // Minimum 1 second
        this.lastModified = watchedFile.exists() ? watchedFile.lastModified() : 0;
    }

    /**
     * Start watching the file.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            logger.warn("File watcher already running for: {}", watchedFile.getAbsolutePath());
            return;
        }

        if (!watchedFile.exists()) {
            running.set(false);
            logger.warn("Cannot watch non-existent file: {}", watchedFile.getAbsolutePath());
            return;
        }
        lastModified = watchedFile.lastModified();

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "File-Watcher-" + watchedFile.getName());
            t.setDaemon(true);
            return t;
        });

        watchTask = executor.scheduleAtFixedRate(
            this::checkForChanges,
            checkIntervalSeconds,
            checkIntervalSeconds,
            TimeUnit.SECONDS
        );

        logger.info("Started file watcher for: {} ({}s interval)",
                   watchedFile.getAbsolutePath(), checkIntervalSeconds);
    }

    /**
     * Stop watching the file.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        if (watchTask != null) {
            watchTask.cancel(false);
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

        logger.info("Stopped file watcher for: {}", watchedFile.getAbsolutePath());
    }

    /**
     * Check if file has been modified.
     */
    private void checkForChanges() {
        if (!running.get() || !watchedFile.exists()) {
            return;
        }

        try {
            long currentModified = watchedFile.lastModified();

            if (currentModified > lastModified) {
                logger.info("File change detected: {}", watchedFile.getAbsolutePath());
                lastModified = currentModified;
                onChange.accept(watchedFile);
            }

        } catch (Exception e) {
            logger.error("Error checking file for changes: {}", watchedFile.getAbsolutePath(), e);
        }
    }

    /**
     * Check if watcher is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get the watched file path.
     */
    public String getFilePath() {
        return watchedFile.getAbsolutePath();
    }
}
