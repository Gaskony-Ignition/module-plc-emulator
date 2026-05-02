package com.inductiveautomation.logixemulator.gateway.web;

import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.logixemulator.gateway.DeviceRegistry;
import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Manages file operations for Logix PLC Emulator devices.
 * Handles file storage, device updates, and hot-reload triggering.
 */
public class DeviceFileManager {

    private static final Logger logger = LoggerFactory.getLogger(DeviceFileManager.class);
    private static final String STORAGE_DIR_NAME = "logix-emulator";
    private static final String OLD_STORAGE_DIR_NAME = "plc-simulator";

    private final GatewayContext context;
    private final DeviceRegistry registry;

    public DeviceFileManager(GatewayContext context, DeviceRegistry registry) {
        this.context = context;
        this.registry = registry;
        migrateStorageDirectory();
    }

    /**
     * Migrate files from old plc-simulator directory to new logix-emulator directory.
     * Only runs once on first startup after the rename.
     */
    private void migrateStorageDirectory() {
        try {
            File dataDir = context.getSystemManager().getDataDir();
            File oldDir = new File(dataDir, OLD_STORAGE_DIR_NAME);
            File newDir = new File(dataDir, STORAGE_DIR_NAME);

            if (oldDir.exists() && oldDir.isDirectory() && !newDir.exists()) {
                logger.info("Migrating storage directory from '{}' to '{}'", OLD_STORAGE_DIR_NAME, STORAGE_DIR_NAME);
                if (oldDir.renameTo(newDir)) {
                    logger.info("Storage directory migrated successfully");
                } else {
                    logger.warn("Could not rename storage directory, attempting file copy");
                    if (newDir.mkdirs()) {
                        File[] files = oldDir.listFiles();
                        if (files != null) {
                            for (File file : files) {
                                Files.copy(file.toPath(), new File(newDir, file.getName()).toPath());
                            }
                            logger.info("Files copied to new storage directory");
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Storage directory migration failed (non-fatal)", e);
        }
    }

    /**
     * Find a device by name using the injected registry.
     */
    public Optional<LogixEmulatorDevice> findDeviceByName(String name) {
        try {
            return registry.findDeviceByName(name);
        } catch (Exception e) {
            logger.error("Error finding device: {}", name, e);
            return Optional.empty();
        }
    }

    /**
     * Get the storage directory for PLC files.
     */
    public File getStorageDirectory() {
        File dataDir = context.getSystemManager().getDataDir();
        return new File(dataDir, STORAGE_DIR_NAME);
    }

    /**
     * Save file content to disk and update device configuration.
     * @return true if successful
     */
    public boolean saveFileToDevice(LogixEmulatorDevice device, String fileContent, String filename) {
        try {
            File storageDir = getStorageDirectory();
            if (!storageDir.exists() && !storageDir.mkdirs()) {
                logger.error("Failed to create storage directory: {}", storageDir.getAbsolutePath());
                return false;
            }

            String sanitizedFileName = PathSecurity.sanitizeFileName(filename);
            String deviceSpecificName = device.getName() + "_" + sanitizedFileName;
            File targetFile = new File(storageDir, deviceSpecificName);

            Files.writeString(
                targetFile.toPath(),
                fileContent,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );

            logger.info("Saved file to disk: {} ({} bytes)", targetFile.getAbsolutePath(), fileContent.length());

            device.setCurrentFilePath(targetFile.getAbsolutePath());
            logger.debug("Updated device file path to: {}", targetFile.getAbsolutePath());
            return true;

        } catch (Exception e) {
            logger.error("Error saving file to device", e);
            return false;
        }
    }

    /**
     * Trigger device reload to apply new configuration.
     */
    public void reloadDevice(LogixEmulatorDevice device) {
        try {
            String filePath = device.getCurrentFilePath();

            if (filePath == null || filePath.isEmpty()) {
                logger.warn("No file path set for device: {}", device.getName());
                return;
            }

            File deviceFile = new File(filePath);
            if (!deviceFile.exists()) {
                logger.error("Device file does not exist: {}", filePath);
                return;
            }

            device.reloadFromFile(deviceFile);
            logger.info("Device reloaded successfully: {}", device.getName());

        } catch (Exception e) {
            logger.error("Error during device reload: {}", device.getName(), e);
        }
    }

    /**
     * Get the current file path for a device.
     */
    public String getDeviceFilePath(LogixEmulatorDevice device) {
        return device.getCurrentFilePath();
    }

    /**
     * Clear the device's file association and rebuild address space to remove all tags.
     * Used when a file is deleted.
     */
    public void clearDeviceFile(LogixEmulatorDevice device) {
        try {
            device.setCurrentFilePath(null);
            device.clearAndReset();
            logger.info("Cleared address space for device: {}", device.getName());
        } catch (Exception e) {
            logger.error("Error clearing device file: {}", device.getName(), e);
        }
    }
}
