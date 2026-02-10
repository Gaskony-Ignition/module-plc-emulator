package com.inductiveautomation.logixemulator.gateway.parser;

/**
 * Utility methods for PLC data type handling.
 * Provides consistent type normalization and default values across all parsers.
 */
public class DataTypeUtils {

    /**
     * Get default initial value for a PLC data type.
     * Returns string representation suitable for JSON serialization.
     *
     * @param dataType The PLC data type (BOOL, INT, DINT, REAL, STRING, etc.)
     * @return String representation of default value
     */
    public static String getDefaultValue(String dataType) {
        return switch (dataType.toUpperCase()) {
            case "BOOL", "BOOLEAN" -> "false";
            case "SINT", "INT", "DINT", "LINT", "BYTE", "INT1", "INT2", "INT4" -> "0";
            case "REAL", "LREAL", "FLOAT", "FLOAT4" -> "0.0";
            case "STRING" -> "";
            default -> "0";
        };
    }

    /**
     * Normalize Rockwell data type names to standard format.
     * Removes parentheses and parameters, converts to standard name.
     *
     * @param dataType Raw data type string from L5K/L5X file (e.g., "STRING(82)")
     * @return Normalized data type name (e.g., "STRING")
     */
    public static String normalizeDataType(String dataType) {
        // Remove anything in parentheses (e.g., STRING(82) -> STRING)
        String cleanType = dataType.split("\\(")[0].trim();

        return switch (cleanType.toUpperCase()) {
            // Basic data types
            case "BOOL", "BIT" -> "BOOL";
            case "SINT", "BYTE", "INT1" -> "SINT";
            case "INT", "INT2" -> "INT";
            case "DINT", "INT4" -> "DINT";
            case "LINT" -> "LINT";
            case "REAL", "FLOAT4", "FLOAT" -> "REAL";
            case "LREAL" -> "LREAL";
            case "STRING" -> "STRING";

            // Basic structured types
            case "TIMER" -> "TIMER";
            case "COUNTER" -> "COUNTER";
            case "CONTROL" -> "CONTROL";
            case "MESSAGE" -> "MESSAGE";

            // Process control types (v3.0.0)
            case "PID" -> "PID";
            case "PIDE" -> "PIDE";
            case "ALARM_ANALOG", "ALMA" -> "ALARM_ANALOG";
            case "ALARM_DIGITAL", "ALMD" -> "ALARM_DIGITAL";

            // Motion control types (v3.0.0)
            case "AXIS_CIP_DRIVE" -> "AXIS_CIP_DRIVE";
            case "AXIS_VIRTUAL" -> "AXIS_VIRTUAL";
            case "AXIS_SERVO_DRIVE" -> "AXIS_SERVO_DRIVE";
            case "MOTION_GROUP" -> "MOTION_GROUP";
            case "CAM" -> "CAM";
            case "CAM_PROFILE" -> "CAM_PROFILE";

            // Specialty types (v3.0.0)
            case "COORDINATE_SYSTEM" -> "COORDINATE_SYSTEM";
            case "PHASE" -> "PHASE";
            case "EQUIPMENT_SEQUENCE" -> "EQUIPMENT_SEQUENCE";
            case "FBD_TIMER" -> "FBD_TIMER";
            case "FBD_COUNTER" -> "FBD_COUNTER";

            default -> cleanType;  // Return as-is for UDTs/AOIs
        };
    }
}
