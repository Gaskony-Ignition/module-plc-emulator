package com.inductiveautomation.plcsimulator.gateway.device;

import com.inductiveautomation.ignition.gateway.config.ValidationErrors.Builder;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.SchemaUtil;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceProfileConfig;
import com.inductiveautomation.ignition.gateway.web.nav.ExtensionPointResourceForm;
import com.inductiveautomation.ignition.gateway.web.nav.WebUiComponent;

import java.io.File;
import java.util.Optional;
import java.util.Set;

/**
 * Extension point for the Enhanced PLC Simulator device.
 * This class registers the device type with Ignition, making it appear in the
 * device connection dropdown list.
 */
public class EnhancedSimulatorExtensionPoint extends DeviceExtensionPoint<EnhancedSimulatorConfig> {

    /**
     * Unique identifier for this device type.
     * This will be used internally by Ignition to identify this device driver.
     */
    public static final String TYPE_ID = "com.gaskony.plcsimulator.EnhancedSimulator";

    /**
     * Constructor registers this device type with Ignition.
     */
    public EnhancedSimulatorExtensionPoint() {
        super(
            TYPE_ID,
            TYPE_ID,      // Use TYPE_ID as fallback for i18n key
            TYPE_ID,      // Use TYPE_ID as fallback for i18n key
            EnhancedSimulatorConfig.class
        );
    }

    public String getDisplayName() {
        return "Enhanced PLC Simulator";
    }

    public String getDescription() {
        return "Multi-vendor PLC simulator supporting Rockwell, Siemens, Schneider, and Beckhoff with hierarchical tag structure";
    }

    /**
     * Creates a new device instance when user creates a device connection.
     *
     * @param context Device context provided by Ignition
     * @param profileConfig Profile-level configuration
     * @param deviceConfig Device-specific configuration from user
     * @return New device instance
     */
    @Override
    protected Device createDevice(
        DeviceContext context,
        DeviceProfileConfig profileConfig,
        EnhancedSimulatorConfig deviceConfig) {

        return new EnhancedSimulatorDevice(context, deviceConfig);
    }

    /**
     * Provides the web UI component for device configuration.
     * This generates the configuration form in the Gateway automatically.
     *
     * @param type Component type
     * @return Web UI component for configuration form
     */
    @Override
    public Optional<WebUiComponent> getWebUiComponent(ComponentType type) {
        return Optional.of(
            new ExtensionPointResourceForm(
                DeviceExtensionPoint.DEVICE_RESOURCE_TYPE,
                "Device Connection",
                TYPE_ID,
                SchemaUtil.fromType(DeviceProfileConfig.class),
                SchemaUtil.fromType(EnhancedSimulatorConfig.class),
                Set.of()
            )
        );
    }

    /**
     * Validates device configuration before saving.
     * Checks that required fields are valid and file paths exist.
     *
     * @param config Device configuration to validate
     * @param errors Error builder for collecting validation errors
     */
    @Override
    protected void validate(EnhancedSimulatorConfig config, Builder errors) {
        // Validate device name
        if (config.general().deviceName() == null || config.general().deviceName().trim().isEmpty()) {
            errors.check(false, "Device name is required");
        }

        // Validate file content or file name is provided
        String fileContent = config.parser().fileContent();
        String fileName = config.parser().fileName();

        if ((fileContent == null || fileContent.trim().isEmpty()) &&
            (fileName == null || fileName.trim().isEmpty())) {
            errors.check(false, "Either paste PLC file content or specify a file name");
        }

        // If file name is provided without content, check if file exists
        if ((fileContent == null || fileContent.trim().isEmpty()) &&
            fileName != null && !fileName.trim().isEmpty()) {
            File file = new File("/usr/local/bin/ignition/data/plc-simulator/" + fileName);
            if (!file.exists()) {
                errors.check(false, "File does not exist: " + fileName + ". Please paste file content to upload.");
            }
        }

        // Validate parser type
        EnhancedSimulatorConfig.ParserType parserType = config.parser().parserType();
        if (parserType == null) {
            errors.check(false, "Parser type is required");
        }

        // Validate update interval
        if (config.simulation().updateInterval() < 100) {
            errors.check(false, "Update interval must be at least 100ms");
        }

        // Validate reload interval
        if (config.parser().reloadInterval() < 1) {
            errors.check(false, "Reload interval must be at least 1 second");
        }
    }
}
