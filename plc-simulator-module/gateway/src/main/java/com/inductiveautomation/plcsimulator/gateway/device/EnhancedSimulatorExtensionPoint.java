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
     * IMPORTANT: DeviceExtensionPoint expects i18n KEYS, not direct strings!
     * These keys must exist in EnhancedSimulator.properties and the bundle must be registered in ModuleHook.
     */
    public EnhancedSimulatorExtensionPoint() {
        super(
            TYPE_ID,
            "EnhancedSimulator.Meta.DisplayName",      // i18n key (NOT direct text!)
            "EnhancedSimulator.Meta.Description",      // i18n key (NOT direct text!)
            EnhancedSimulatorConfig.class
        );
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
     * Automatically injects plc-file-upload.js to enable file upload button.
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
                Set.of("/res/plcsimulator/plc-file-upload.js")
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

        // Note: File content and file name are now optional - device can be created without them
        // Device will start in "Ready - Waiting for file upload" status if no file provided

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
