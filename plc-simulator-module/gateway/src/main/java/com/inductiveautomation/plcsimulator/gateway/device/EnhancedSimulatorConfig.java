package com.inductiveautomation.plcsimulator.gateway.device;

import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.DefaultValue;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Description;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormCategory;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormField;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Label;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Required;
import com.inductiveautomation.ignition.gateway.web.nav.FormFieldType;

/**
 * Configuration for the Enhanced PLC Simulator device.
 * Uses modern Java records approach with form annotations for auto-generated UI.
 */
public record EnhancedSimulatorConfig(General general, ParserSettings parser, SimulationSettings simulation) {

    /**
     * General device settings.
     */
    public record General(
        @FormCategory("GENERAL")
        @Label("Device Name")
        @FormField(FormFieldType.TEXT)
        @Description("Name of the simulated PLC device")
        @Required
        String deviceName,

        @FormCategory("GENERAL")
        @Label("Enabled")
        @FormField(FormFieldType.CHECKBOX)
        @Description("Enable or disable this device")
        @DefaultValue("true")
        boolean enabled
    ) {}

    /**
     * Parser and file settings.
     */
    public record ParserSettings(
        @FormCategory("PARSER")
        @Label("PLC File Path")
        @FormField(FormFieldType.TEXT)
        @Description("Absolute path to PLC file (.L5K, .json, etc.)")
        @Required
        String filePath,

        @FormCategory("PARSER")
        @Label("Parser Type")
        @FormField(FormFieldType.TEXT)
        @Description("Parser type: rockwell, json, siemens, schneider, beckhoff, or gaskony")
        @DefaultValue("rockwell")
        @Required
        String parserType,

        @FormCategory("PARSER")
        @Label("Auto-reload on File Change")
        @FormField(FormFieldType.CHECKBOX)
        @Description("Automatically reload tags when the PLC file changes")
        @DefaultValue("true")
        boolean hotReload,

        @FormCategory("PARSER")
        @Label("Reload Interval (seconds)")
        @FormField(FormFieldType.NUMBER)
        @Description("How often to check for file changes (if hot reload is enabled)")
        @DefaultValue("5")
        int reloadInterval
    ) {}

    /**
     * Simulation settings.
     */
    public record SimulationSettings(
        @FormCategory("SIMULATION")
        @Label("Enable Simulation")
        @FormField(FormFieldType.CHECKBOX)
        @Description("Enable dynamic value simulation for tags")
        @DefaultValue("true")
        boolean enabled,

        @FormCategory("SIMULATION")
        @Label("Update Interval (ms)")
        @FormField(FormFieldType.NUMBER)
        @Description("How often to update simulated values (milliseconds)")
        @DefaultValue("1000")
        int updateInterval,

        @FormCategory("SIMULATION")
        @Label("Default Simulation Pattern")
        @FormField(FormFieldType.TEXT)
        @Description("Simulation pattern: static, sine, ramp, random, or toggle")
        @DefaultValue("sine")
        String defaultPattern
    ) {}
}
