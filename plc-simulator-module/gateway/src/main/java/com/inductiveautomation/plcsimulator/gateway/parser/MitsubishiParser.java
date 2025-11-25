package com.inductiveautomation.plcsimulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.inductiveautomation.plcsimulator.gateway.parser.DataTypeUtils.*;

/**
 * Parser for Mitsubishi Electric GX Works 2/3 files.
 *
 * Supports:
 * - GX Works 2/3 CSV exports
 * - iQ-Platform PLCs (Q series, L series, F series)
 * - Device label lists
 * - Global device comments
 *
 * Parses:
 * - Device variables (D, M, X, Y, etc.)
 * - Data registers
 * - Bit devices
 * - Timer/Counter values
 */
public class MitsubishiParser implements PLCParser {

    private static final Logger logger = LoggerFactory.getLogger(MitsubishiParser.class);

    // Mitsubishi device type patterns
    private static final Pattern DEVICE_PATTERN = Pattern.compile("^([A-Z]+)(\\d+)$");

    @Override
    public JsonObject parse(String filePath) {
        try {
            String content = Files.readString(Paths.get(filePath));
            return parseContent(content, filePath);
        } catch (Exception e) {
            logger.error("Error reading Mitsubishi file: {}", filePath, e);
            return null;
        }
    }

    @Override
    public JsonObject parseContent(String fileContent, String fileName) {
        try {
            JsonObject result = new JsonObject();
            result.addProperty("vendor", "mitsubishi");
            result.addProperty("format", "gx_works");

            JsonArray globalTags = new JsonArray();

            // Mitsubishi GX Works exports are typically CSV format
            if (isCSVFormat(fileContent)) {
                parseCSV(fileContent, globalTags);
            } else {
                logger.warn("Unsupported Mitsubishi file format");
                return null;
            }

            result.add("global_tags", globalTags);
            logger.info("Parsed {} global tags from Mitsubishi file", globalTags.size());

            return result;

        } catch (Exception e) {
            logger.error("Error parsing Mitsubishi file", e);
            return null;
        }
    }

    /**
     * Check if content is CSV format.
     * Accepts both comma-separated and single-column formats with newlines.
     */
    private boolean isCSVFormat(String content) {
        // CSV must have at least one newline (multiple rows)
        return content.contains("\n") || content.contains("\r");
    }

    /**
     * Parse Mitsubishi CSV format.
     * Common formats:
     * - Device,Type,Comment
     * - Device,DataType,InitialValue,Comment
     * - Address,Name,Type,Comment
     */
    private void parseCSV(String csvContent, JsonArray tags) {
        String[] lines = csvContent.split("\\r?\\n");
        boolean isHeaderRow = true;
        int deviceCol = -1;
        int nameCol = -1;
        int typeCol = -1;
        int valueCol = -1;
        int commentCol = -1;

        for (String line : lines) {
            line = line.trim();

            if (line.isEmpty()) {
                continue;
            }

            String[] fields = parseCSVLine(line);

            // First non-empty line is header
            if (isHeaderRow) {
                isHeaderRow = false;

                // Detect column positions from header
                for (int i = 0; i < fields.length; i++) {
                    String header = fields[i].toLowerCase().trim();
                    if (header.contains("device") || header.contains("address")) {
                        deviceCol = i;
                    } else if (header.contains("name") || header.contains("label")) {
                        nameCol = i;
                    } else if (header.contains("type") || header.contains("datatype")) {
                        typeCol = i;
                    } else if (header.contains("value") || header.contains("initial")) {
                        valueCol = i;
                    } else if (header.contains("comment") || header.contains("description")) {
                        commentCol = i;
                    }
                }

                // If no clear headers detected, assume standard format
                if (deviceCol == -1 && fields.length >= 1) {
                    deviceCol = 0;
                    if (fields.length >= 2) typeCol = 1;
                    if (fields.length >= 3) commentCol = 2;
                    if (fields.length >= 4) {
                        valueCol = 2;
                        commentCol = 3;
                    }
                }

                // Skip header row only if it looks like actual header text
                if (fields[0].toLowerCase().contains("device") ||
                    fields[0].toLowerCase().contains("address") ||
                    fields[0].toLowerCase().contains("name")) {
                    continue;
                }

                // Otherwise, process it as data
                isHeaderRow = false;
            }

            // Parse data row
            JsonObject tag = parseCSVRow(fields, deviceCol, nameCol, typeCol, valueCol, commentCol);
            if (tag != null) {
                tags.add(tag);
            }
        }
    }

    /**
     * Parse a CSV line handling quoted fields.
     */
    private String[] parseCSVLine(String line) {
        // Simple CSV parser that handles quoted fields
        Pattern pattern = Pattern.compile("\"([^\"]*)\"|(?<=,|^)([^,]*)(?=,|$)");
        Matcher matcher = pattern.matcher(line);

        java.util.List<String> fields = new java.util.ArrayList<>();
        while (matcher.find()) {
            String field = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            fields.add(field != null ? field.trim() : "");
        }

        return fields.toArray(new String[0]);
    }

    /**
     * Parse a CSV row into a tag.
     */
    private JsonObject parseCSVRow(String[] fields, int deviceCol, int nameCol, int typeCol, int valueCol, int commentCol) {
        try {
            if (fields.length == 0 || deviceCol >= fields.length) {
                return null;
            }

            String device = fields[deviceCol].trim();
            if (device.isEmpty()) {
                return null;
            }

            // Extract name from device address if no separate name column
            String name = device;
            if (nameCol >= 0 && nameCol < fields.length && !fields[nameCol].isEmpty()) {
                name = fields[nameCol].trim();
            }

            // Determine data type
            String dataType = "DINT"; // Default
            if (typeCol >= 0 && typeCol < fields.length && !fields[typeCol].isEmpty()) {
                dataType = fields[typeCol].trim();
            } else {
                // Infer type from device code
                dataType = inferDataTypeFromDevice(device);
            }

            // Get initial value
            String initialValue = "";
            if (valueCol >= 0 && valueCol < fields.length) {
                initialValue = fields[valueCol].trim();
            }

            // Get comment
            String comment = "";
            if (commentCol >= 0 && commentCol < fields.length) {
                comment = fields[commentCol].trim();
            }

            JsonObject tag = new JsonObject();
            tag.addProperty("name", name);
            tag.addProperty("dataType", convertMitsubishiDataType(dataType));
            tag.addProperty("address", device);

            if (!initialValue.isEmpty()) {
                addValueProperty(tag, parseInitialValue(initialValue, dataType));
            } else {
                addValueProperty(tag, getDefaultValue(dataType));
            }

            if (!comment.isEmpty()) {
                tag.addProperty("description", comment);
            }

            logger.debug("Parsed Mitsubishi variable: {} ({}) at {}", name, dataType, device);
            return tag;

        } catch (Exception e) {
            logger.debug("Error parsing Mitsubishi CSV row", e);
            return null;
        }
    }

    /**
     * Infer data type from Mitsubishi device code.
     * D = Data register (16-bit word)
     * M = Internal relay (bit)
     * X = Input (bit)
     * Y = Output (bit)
     * T = Timer
     * C = Counter
     * etc.
     */
    private String inferDataTypeFromDevice(String device) {
        Matcher matcher = DEVICE_PATTERN.matcher(device.toUpperCase());
        if (matcher.matches()) {
            String deviceType = matcher.group(1);

            return switch (deviceType) {
                case "M", "X", "Y", "B", "SB", "SM" -> "BOOL"; // Bit devices
                case "D", "W", "SW", "SD" -> "INT"; // Word devices (16-bit)
                case "R", "ZR" -> "INT"; // File registers
                case "T", "ST", "C", "SC" -> "DINT"; // Timers and counters (32-bit)
                case "L", "SL" -> "DINT"; // Long word devices (32-bit)
                default -> "DINT";
            };
        }

        return "DINT";
    }

    /**
     * Convert Mitsubishi data types to common format.
     */
    private String convertMitsubishiDataType(String mitsubishiType) {
        if (mitsubishiType == null) {
            return "DINT";
        }

        String normalized = mitsubishiType.toUpperCase().trim();

        return switch (normalized) {
            case "BIT", "BOOL", "BOOLEAN" -> "BOOL";
            case "BYTE", "SBYTE" -> "SINT";
            case "WORD", "SWORD" -> "INT";
            case "DWORD", "SDWORD", "LONG" -> "DINT";
            case "LWORD" -> "LINT";
            case "INT", "INTEGER" -> "INT";
            case "DINT", "DOUBLE_INTEGER" -> "DINT";
            case "REAL", "FLOAT" -> "REAL";
            case "LREAL", "DOUBLE" -> "LREAL";
            case "STRING", "STR" -> "STRING";
            case "TIME" -> "DINT";
            case "DATE" -> "LINT";
            default -> {
                logger.debug("Unknown Mitsubishi data type: {}, defaulting to DINT", mitsubishiType);
                yield "DINT";
            }
        };
    }

    /**
     * Get default value for a data type.
     */
    private Object getDefaultValue(String dataType) {
        String converted = convertMitsubishiDataType(dataType);

        return switch (converted) {
            case "BOOL" -> false;
            case "SINT", "INT", "DINT", "LINT" -> 0;
            case "REAL", "LREAL" -> 0.0;
            case "STRING" -> "";
            default -> 0;
        };
    }

    /**
     * Parse initial value from string.
     */
    private Object parseInitialValue(String valueStr, String dataType) {
        try {
            String converted = convertMitsubishiDataType(dataType);

            return switch (converted) {
                case "BOOL" -> valueStr.equalsIgnoreCase("TRUE") ||
                               valueStr.equalsIgnoreCase("1") ||
                               valueStr.equalsIgnoreCase("ON");
                case "SINT", "INT", "DINT" -> {
                    // Handle hex format (H prefix)
                    if (valueStr.toUpperCase().startsWith("H")) {
                        yield Integer.parseInt(valueStr.substring(1), 16);
                    }
                    yield Integer.parseInt(valueStr.replaceAll("[^0-9-]", ""));
                }
                case "LINT" -> Long.parseLong(valueStr.replaceAll("[^0-9-]", ""));
                case "REAL", "LREAL" -> Double.parseDouble(valueStr.replaceAll("[^0-9.\\-E]", ""));
                case "STRING" -> valueStr.replace("'", "").replace("\"", "");
                default -> 0;
            };
        } catch (Exception e) {
            logger.debug("Error parsing initial value '{}', using default", valueStr);
            return getDefaultValue(dataType);
        }
    }

    @Override
    public boolean canHandle(String fileName) {
        if (fileName == null) {
            return false;
        }

        String lowerFileName = fileName.toLowerCase();

        // Mitsubishi GX Works files
        return (lowerFileName.endsWith(".csv") &&
                (lowerFileName.contains("mitsubishi") ||
                 lowerFileName.contains("gx") ||
                 lowerFileName.contains("melsec"))) ||
               lowerFileName.endsWith(".gxw") ||
               lowerFileName.endsWith(".gpj") ||
               lowerFileName.endsWith(".gpa");
    }

    /**
     * Helper method to add value property with proper type handling.
     */
    private void addValueProperty(JsonObject tag, Object value) {
        if (value instanceof Boolean) {
            tag.addProperty("value", (Boolean) value);
        } else if (value instanceof Number) {
            tag.addProperty("value", (Number) value);
        } else if (value instanceof String) {
            tag.addProperty("value", (String) value);
        } else {
            tag.addProperty("value", String.valueOf(value));
        }
    }

    @Override
    public String getParserType() {
        return "mitsubishi";
    }
}
