package com.inductiveautomation.logixemulator.gateway.device;

import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.DefaultValue;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Description;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormCategory;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormField;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Label;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Required;
import com.inductiveautomation.ignition.gateway.web.nav.FormFieldType;

/**
 * Configuration for the Logix PLC Emulator device.
 * Uses modern Java records approach with form annotations for auto-generated UI.
 */
public record LogixEmulatorConfig(General general, ParserSettings parser, SimulationSettings simulation) {

    /**
     * Supported PLC file parser types.
     * Multi-vendor support for major PLC platforms.
     */
    public enum ParserType {
        ROCKWELL("rockwell", "Rockwell L5K/L5X (Allen-Bradley)"),
        JSON("json", "JSON Format"),
        CSV("csv", "CSV Format");

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
     * Note: The main device enabled/disabled state is handled by Ignition's device driver framework.
     * This field is used for internal state tracking only.
     */
    public record General(
        // Internal field - not displayed in form UI (Ignition provides this automatically)
        @DefaultValue("true")
        boolean enabled
    ) {}

    /**
     * Parser and file settings.
     * Upload PLC files via Gateway menu: Config → PLC Simulator → File Upload
     */
    public record ParserSettings(
        // Internal fields - stored but not displayed in form
        String fileName,
        String fileContent,

        @FormCategory("PARSER")
        @Label("Parser Type")
        @FormField(FormFieldType.SELECT)
        @Description("Select PLC file format: Rockwell L5K/L5X, JSON, or CSV")
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
        int reloadInterval,

        @FormCategory("PARSER")
        @Label("Max File Size (MB)")
        @FormField(FormFieldType.NUMBER)
        @Description("Maximum allowed file size for uploads (1-500 MB)")
        @DefaultValue("50")
        int maxFileSizeMB
    ) {
        /**
         * Returns the max file size, clamped to valid range (1-500 MB).
         */
        public int getValidatedMaxFileSizeMB() {
            if (maxFileSizeMB < 1) return 1;
            if (maxFileSizeMB > 500) return 500;
            return maxFileSizeMB;
        }
    }

    /**
     * Simulation settings.
     * NOTE: Simulation features are still in development and not fully tested.
     */
    public record SimulationSettings(
        @FormCategory("SIMULATION")
        @Label("Enable Simulation")
        @FormField(FormFieldType.CHECKBOX)
        @Description("⚠️ EXPERIMENTAL: Enable dynamic value simulation for tags. This feature is still in development and not fully tested. Disable this to allow manual tag value changes.")
        @DefaultValue("false")
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
