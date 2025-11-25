package com.inductiveautomation.plcsimulator.gateway.web;

import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.plcsimulator.gateway.SimulatorModuleHook;
import com.inductiveautomation.plcsimulator.gateway.device.EnhancedSimulatorDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Manages file operations for PLC simulator devices.
 * Handles file storage, device updates, and hot-reload triggering.
 */
public class DeviceFileManager {

    private static final Logger logger = LoggerFactory.getLogger(DeviceFileManager.class);
    private static final String STORAGE_DIR_NAME = "plc-simulator";

    private final GatewayContext context;

    public DeviceFileManager(GatewayContext context) {
        this.context = context;
    }

    /**
     * Find a device by name in the registry.
     */
    public Optional<EnhancedSimulatorDevice> findDeviceByName(String name) {
        try {
            return SimulatorModuleHook.findDeviceByName(name);
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
    public boolean saveFileToDevice(EnhancedSimulatorDevice device, String fileContent, String filename) {
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

            updateDeviceFilePath(device, targetFile.getAbsolutePath());
            return true;

        } catch (Exception e) {
            logger.error("Error saving file to device", e);
            return false;
        }
    }

    /**
     * Trigger device reload to apply new configuration.
     */
    public void reloadDevice(EnhancedSimulatorDevice device) {
        try {
            Field filePathField = EnhancedSimulatorDevice.class.getDeclaredField("currentFilePath");
            filePathField.setAccessible(true);
            String filePath = (String) filePathField.get(device);

            if (filePath == null || filePath.isEmpty()) {
                logger.warn("No file path set for device: {}", device.getName());
                return;
            }

            File deviceFile = new File(filePath);
            if (!deviceFile.exists()) {
                logger.error("Device file does not exist: {}", filePath);
                return;
            }

            Method handleFileChangeMethod = EnhancedSimulatorDevice.class.getDeclaredMethod("handleFileChange", File.class);
            handleFileChangeMethod.setAccessible(true);
            handleFileChangeMethod.invoke(device, deviceFile);

            logger.info("Device reloaded successfully: {}", device.getName());

        } catch (NoSuchFieldException | NoSuchMethodException e) {
            logger.error("Reflection error - device class structure may have changed", e);
        } catch (Exception e) {
            logger.error("Error during device reload: {}", device.getName(), e);
        }
    }

    /**
     * Get the current file path for a device.
     */
    public String getDeviceFilePath(EnhancedSimulatorDevice device) {
        try {
            Field filePathField = EnhancedSimulatorDevice.class.getDeclaredField("currentFilePath");
            filePathField.setAccessible(true);
            return (String) filePathField.get(device);
        } catch (Exception e) {
            logger.warn("Could not access currentFilePath field", e);
            return null;
        }
    }

    private void updateDeviceFilePath(EnhancedSimulatorDevice device, String filePath) throws Exception {
        Field filePathField = EnhancedSimulatorDevice.class.getDeclaredField("currentFilePath");
        filePathField.setAccessible(true);
        filePathField.set(device, filePath);
        logger.debug("Updated device file path to: {}", filePath);
    }
}
