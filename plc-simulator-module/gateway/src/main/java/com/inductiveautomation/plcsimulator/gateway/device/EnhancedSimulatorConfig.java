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
     * Supported PLC file parser types.
     */
    public enum ParserType {
        ROCKWELL("rockwell", "Rockwell l5k (allen-bradley)"),
        JSON("json", "Json format"),
        SIEMENS("siemens", "Siemens tia portal"),
        SCHNEIDER("schneider", "Schneider electric"),
        BECKHOFF("beckhoff", "Beckhoff twincat");

        private final String key;
        private final String displayName;

        ParserType(String key, String displayName) {
            this.key = key;
            this.displayName = displayName;
        }

        public String getKey() {
            return key;
        }

        public String getDisplayName() {
            return displayName;
        }

        public static ParserType fromKey(String key) {
            for (ParserType type : values()) {
                if (type.key.equalsIgnoreCase(key)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown parser type: " + key);
        }
    }

    /**
     * Simulation pattern types.
     */
    public enum SimulationPattern {
        STATIC("static", "Static (no changes)"),
        SINE("sine", "Sine wave"),
        RAMP("ramp", "Linear ramp"),
        RANDOM("random", "Random values"),
        TOGGLE("toggle", "Boolean toggle");

        private final String key;
        private final String displayName;

        SimulationPattern(String key, String displayName) {
            this.key = key;
            this.displayName = displayName;
        }

        public String getKey() {
            return key;
        }

        public String getDisplayName() {
            return displayName;
        }

        public static SimulationPattern fromKey(String key) {
            for (SimulationPattern pattern : values()) {
                if (pattern.key.equalsIgnoreCase(key)) {
                    return pattern;
                }
            }
            return SINE; // Default fallback
        }
    }

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
        @FormField(FormFieldType.FILE)
        @Description("Path to PLC file accessible from Gateway (e.g., /usr/local/bin/ignition/data/plc-files/yourfile.L5K). For Docker: use mounted volumes or copy files to gateway container.")
        @Required
        String filePath,

        @FormCategory("PARSER")
        @Label("Parser Type")
        @FormField(FormFieldType.SELECT)
        @Description("Select the PLC vendor/format")
        @DefaultValue("ROCKWELL")
        @Required
        ParserType parserType,

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
        @FormField(FormFieldType.SELECT)
        @Description("Default simulation behavior for tags")
        @DefaultValue("SINE")
        SimulationPattern defaultPattern
    ) {}
}
