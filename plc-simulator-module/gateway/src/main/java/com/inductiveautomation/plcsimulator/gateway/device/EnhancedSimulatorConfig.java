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
        @Label("📁 Manage PLC Program")
        @FormField(FormFieldType.TEXT)
        @Description("<a href='/res/plcsimulator/edit-program.html' target='_blank' style='display:inline-block;padding:10px 20px;background:#0066cc;color:white;text-decoration:none;border-radius:4px;font-weight:500;margin-bottom:8px;'>Open Program Manager ↗</a><br/><br/>Opens drag-and-drop interface for uploading L5K, JSON, CSV, or XML files. After uploading, paste the content into the 'File Content (Internal)' field below.")
        @DefaultValue("See link above")
        String programManagerLink,

        @FormCategory("PARSER")
        @Label("Current File")
        @FormField(FormFieldType.TEXT)
        @Description("Currently loaded PLC file (read-only). Use Program Manager link above to change.")
        String fileName,

        @FormCategory("PARSER")
        @Label("File Content (Internal)")
        @FormField(FormFieldType.TEXTAREA)
        @Description("Internal storage for PLC file content. Managed automatically by Program Manager. You can also paste content here directly if needed.")
        String fileContent,

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
