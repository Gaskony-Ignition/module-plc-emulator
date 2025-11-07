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
        ROCKWELL("rockwell", "Rockwell L5K (Allen-Bradley)"),
        JSON("json", "JSON Format"),
        SIEMENS("siemens", "Siemens TIA Portal"),
        SCHNEIDER("schneider", "Schneider Electric"),
        BECKHOFF("beckhoff", "Beckhoff TwinCAT");

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

        @Override
        public String toString() {
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
        STATIC("static", "Static (No Changes)"),
        SINE("sine", "Sine Wave"),
        RAMP("ramp", "Linear Ramp"),
        RANDOM("random", "Random Values"),
        TOGGLE("toggle", "Boolean Toggle");

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

        @Override
        public String toString() {
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
        @Label("PLC File Content")
        @FormField(FormFieldType.TEXTAREA)
        @Description("OPTIONAL: Click 'Upload PLC File' button or paste content here. You can create the device first and add the file later.")
        String fileContent,

        @FormCategory("PARSER")
        @Label("File Name")
        @FormField(FormFieldType.TEXT)
        @Description("OPTIONAL: Filename to save as (e.g., 'DemoWWTP.L5K'). Auto-populated when uploading. You can leave this empty when creating the device.")
        String fileName,

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
