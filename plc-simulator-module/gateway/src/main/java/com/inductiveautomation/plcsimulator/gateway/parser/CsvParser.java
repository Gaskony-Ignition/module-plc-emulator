package com.inductiveautomation.plcsimulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for CSV PLC tag lists.
 *
 * Expected CSV format:
 * name,type,initialValue,description
 * Tag1,DINT,0,First tag
 * Tag2,REAL,123.45,Second tag
 * Tag3,BOOL,true,Boolean tag
 *
 * Supports:
 * - Header row (optional, auto-detected)
 * - Comma or semicolon delimiters
 * - Quoted values
 * - Comments (lines starting with #)
 */
public class CsvParser implements PLCParser {

    private static final Logger logger = LoggerFactory.getLogger(CsvParser.class);

    @Override
    public JsonObject parse(String filePath) {
        try {
            String content = Files.readString(Paths.get(filePath));
            return parseContent(content, filePath);
        } catch (Exception e) {
            logger.error("Error reading CSV file: {}", filePath, e);
            return null;
        }
    }

    @Override
    public JsonObject parseContent(String fileContent, String fileName) {
        try {
            JsonObject result = new JsonObject();
            JsonArray tags = new JsonArray();

            String[] lines = fileContent.split("\\r?\\n");
            boolean hasHeader = false;
            int nameCol = 0;
            int typeCol = 1;
            int valueCol = 2;
            int descCol = 3;

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();

                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // Detect delimiter
                String delimiter = line.contains(";") ? ";" : ",";
                String[] parts = splitCsvLine(line, delimiter);

                // Check if first line is header
                if (i == 0 && isHeaderLine(parts)) {
                    hasHeader = true;
                    continue;
                }

                // Skip if not enough columns
                if (parts.length < 2) {
                    logger.warn("Skipping invalid CSV line {}: {}", i + 1, line);
                    continue;
                }

                // Parse tag
                JsonObject tag = new JsonObject();
                tag.addProperty("name", parts[nameCol].trim());
                tag.addProperty("type", parts[typeCol].trim().toUpperCase());

                if (parts.length > valueCol) {
                    tag.addProperty("initialValue", parseValue(parts[valueCol].trim(), parts[typeCol].trim()));
                } else {
                    tag.addProperty("initialValue", getDefaultValue(parts[typeCol].trim()));
                }

                if (parts.length > descCol) {
                    tag.addProperty("description", parts[descCol].trim());
                }

                tags.add(tag);
            }

            result.addProperty("controller", "CSVController");
            result.addProperty("vendor", "csv");
            result.add("tags", tags);

            logger.info("Successfully parsed CSV file with {} tags: {}", tags.size(), fileName);
            return result;

        } catch (Exception e) {
            logger.error("Error parsing CSV content from: {}", fileName, e);
            return null;
        }
    }

    /**
     * Split CSV line respecting quoted values.
     */
    private String[] splitCsvLine(String line, String delimiter) {
        List<String> result = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();

        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == delimiter.charAt(0) && !inQuotes) {
                result.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        result.add(current.toString());

        return result.toArray(new String[0]);
    }

    /**
     * Check if line is likely a header.
     */
    private boolean isHeaderLine(String[] parts) {
        if (parts.length < 2) {
            return false;
        }

        String first = parts[0].toLowerCase().trim();
        return first.equals("name") || first.equals("tag") || first.equals("tagname");
    }

    /**
     * Parse value string based on data type.
     */
    private String parseValue(String value, String type) {
        value = value.trim();
        type = type.toUpperCase();

        // Handle booleans
        if (type.equals("BOOL") || type.equals("BOOLEAN")) {
            return value.equalsIgnoreCase("true") || value.equals("1") ? "true" : "false";
        }

        // Handle numbers
        if (type.equals("DINT") || type.equals("INT") || type.equals("SINT")) {
            try {
                return String.valueOf(Integer.parseInt(value));
            } catch (NumberFormatException e) {
                return "0";
            }
        }

        if (type.equals("REAL") || type.equals("FLOAT")) {
            try {
                return String.valueOf(Double.parseDouble(value));
            } catch (NumberFormatException e) {
                return "0.0";
            }
        }

        // Strings and others - return as-is
        return value;
    }

    /**
     * Get default value for data type.
     */
    private String getDefaultValue(String type) {
        type = type.toUpperCase();

        if (type.equals("BOOL") || type.equals("BOOLEAN")) {
            return "false";
        }

        if (type.equals("DINT") || type.equals("INT") || type.equals("SINT")) {
            return "0";
        }

        if (type.equals("REAL") || type.equals("FLOAT")) {
            return "0.0";
        }

        return "";
    }

    @Override
    public boolean canHandle(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".csv") || lowerName.endsWith(".txt");
    }

    @Override
    public String getParserType() {
        return "csv";
    }
}
