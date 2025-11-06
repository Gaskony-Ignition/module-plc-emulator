package com.inductiveautomation.plcsimulator.gateway;

import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.plcsimulator.gateway.records.PLCSimSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simpleorm.dataset.SQuery;

/**
 * Manages persistent settings for the PLC Simulator module.
 * Provides methods to read and update configuration stored in the Gateway database.
 */
public class SettingsManager {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final GatewayContext context;
    private PLCSimSettings settings;

    public SettingsManager(GatewayContext context) {
        this.context = context;
    }

    /**
     * Load settings from the database, creating defaults if none exist.
     */
    public void loadSettings() {
        try {
            settings = context.getPersistenceInterface().queryOne(
                new SQuery<>(PLCSimSettings.META)
            );

            if (settings == null) {
                logger.info("No existing settings found, creating defaults");
                createDefaultSettings();
            } else {
                logger.info("Loaded PLC Simulator settings from database");
                logCurrentSettings();
            }

        } catch (Exception e) {
            logger.error("Error loading settings", e);
            createDefaultSettings();
        }
    }

    /**
     * Create and save default settings.
     */
    private void createDefaultSettings() {
        try {
            settings = context.getPersistenceInterface().createNew(PLCSimSettings.META);

            // Defaults are set in PLCSimSettings static initializer
            settings.setParserHost("localhost");
            settings.setParserPort(5000);
            settings.setPersistTags(false);
            settings.setAllowTagCustomization(true);
            settings.setAutoStartSimulations(true);
            settings.setSimulationUpdateInterval(1000);
            settings.setCreateSampleTags(true);
            settings.setLastLoadedFile("");

            context.getPersistenceInterface().save(settings);
            logger.info("Created default PLC Simulator settings");

        } catch (Exception e) {
            logger.error("Error creating default settings", e);
        }
    }

    /**
     * Save current settings to the database.
     */
    public void saveSettings() {
        if (settings != null) {
            try {
                context.getPersistenceInterface().save(settings);
                logger.info("Saved PLC Simulator settings");
            } catch (Exception e) {
                logger.error("Error saving settings", e);
            }
        }
    }

    /**
     * Get current settings record.
     */
    public PLCSimSettings getSettings() {
        return settings;
    }

    /**
     * Update parser service configuration.
     */
    public void updateParserConfig(String host, int port) {
        if (settings != null) {
            settings.setParserHost(host);
            settings.setParserPort(port);
            saveSettings();
            logger.info("Updated parser configuration: {}:{}", host, port);
        }
    }

    /**
     * Update tag provider configuration.
     */
    public void updateTagProviderConfig(boolean persistTags, boolean allowCustomization) {
        if (settings != null) {
            settings.setPersistTags(persistTags);
            settings.setAllowTagCustomization(allowCustomization);
            saveSettings();
            logger.info("Updated tag provider configuration");
        }
    }

    /**
     * Update simulation configuration.
     */
    public void updateSimulationConfig(boolean autoStart, int updateInterval) {
        if (settings != null) {
            settings.setAutoStartSimulations(autoStart);
            settings.setSimulationUpdateInterval(updateInterval);
            saveSettings();
            logger.info("Updated simulation configuration");
        }
    }

    /**
     * Update last loaded file path.
     */
    public void updateLastLoadedFile(String filePath) {
        if (settings != null) {
            settings.setLastLoadedFile(filePath);
            saveSettings();
            logger.debug("Updated last loaded file: {}", filePath);
        }
    }

    /**
     * Log current settings for debugging.
     */
    private void logCurrentSettings() {
        if (settings != null) {
            logger.debug("Current settings:");
            logger.debug("  Parser: {}:{}", settings.getParserHost(), settings.getParserPort());
            logger.debug("  Persist Tags: {}", settings.getPersistTags());
            logger.debug("  Allow Customization: {}", settings.getAllowTagCustomization());
            logger.debug("  Auto-start Simulations: {}", settings.getAutoStartSimulations());
            logger.debug("  Simulation Interval: {}ms", settings.getSimulationUpdateInterval());
            logger.debug("  Create Sample Tags: {}", settings.getCreateSampleTags());
        }
    }
}
