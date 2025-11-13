package com.inductiveautomation.plcsimulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Rockwell L5K files (text-based format).
 * L5K files are NOT XML - they use a proprietary text format with sections like:
 * - CONTROLLER section
 * - TAG sections with format: TAG Name : DataType [attributes]
 * - PROGRAM sections
 *
 * This is different from L5X which is XML-based.
 */
public class L5KParser implements PLCParser {

    private static final Logger logger = LoggerFactory.getLogger(L5KParser.class);

    // Regex patterns for parsing L5K format
    private static final Pattern CONTROLLER_PATTERN = Pattern.compile("CONTROLLER\\s+(\\S+)\\s*\\{", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_PATTERN = Pattern.compile("TAG\\s+(\\S+)\\s*:\\s*(\\S+)(?:\\[(\\d+)\\])?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROGRAM_PATTERN = Pattern.compile("PROGRAM\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_TYPE_PATTERN = Pattern.compile("DATATYPE\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

    @Override
    public JsonObject parse(String filePath) {
        try {
            String content = Files.readString(Paths.get(filePath));
            return parseContent(content, filePath);
        } catch (Exception e) {
            logger.error("Error reading L5K file: {}", filePath, e);
            return null;
        }
    }

    @Override
    public JsonObject parseContent(String fileContent, String fileName) {
        try {
            logger.info("Parsing L5K file: {}", fileName);

            JsonObject result = new JsonObject();
            result.addProperty("vendor", "rockwell");
            result.addProperty("format", "L5K");

            // Split content into lines for processing
            String[] lines = fileContent.split("\\r?\\n");

            // Parse controller name
            String controllerName = parseControllerName(lines);
            if (controllerName != null) {
                result.addProperty("controller", controllerName);
            }

            // Parse tags (both controller and program scoped)
            List<Tag> allTags = parseTags(lines);

            // Separate controller tags and program tags
            JsonArray globalTags = new JsonArray();
            Map<String, JsonArray> programTags = new HashMap<>();

            for (Tag tag : allTags) {
                JsonObject tagJson = new JsonObject();
                tagJson.addProperty("name", tag.name);
                tagJson.addProperty("dataType", tag.dataType);
                tagJson.addProperty("value", getDefaultValue(tag.dataType));

                if (tag.arraySize > 0) {
                    tagJson.addProperty("dimensions", tag.arraySize);
                    tagJson.addProperty("isArray", true);
                }

                if (tag.description != null) {
                    tagJson.addProperty("description", tag.description);
                }

                if (tag.program != null) {
                    // Program-scoped tag
                    programTags.computeIfAbsent(tag.program, k -> new JsonArray()).add(tagJson);
                } else {
                    // Controller-scoped tag
                    globalTags.add(tagJson);
                }
            }

            // Add global tags
            if (globalTags.size() > 0) {
                result.add("global_tags", globalTags);
                logger.info("Found {} global tags", globalTags.size());
            }

            // Add program tags
            if (!programTags.isEmpty()) {
                JsonArray programs = new JsonArray();
                for (Map.Entry<String, JsonArray> entry : programTags.entrySet()) {
                    JsonObject program = new JsonObject();
                    program.addProperty("name", entry.getKey());
                    program.add("tags", entry.getValue());
                    programs.add(program);
                }
                result.add("programs", programs);
                logger.info("Found {} programs with tags", programTags.size());
            }

            // Log summary
            int totalTags = globalTags.size() + programTags.values().stream()
                .mapToInt(JsonArray::size)
                .sum();
            logger.info("L5K parsing complete: {} total tags found", totalTags);

            // If no tags found, create demo structure
            if (totalTags == 0) {
                logger.warn("No tags found in L5K file - creating demo structure");
                return createDemoStructure();
            }

            return result;

        } catch (Exception e) {
            logger.error("Error parsing L5K content", e);
            return createDemoStructure();
        }
    }

    private String parseControllerName(String[] lines) {
        for (String line : lines) {
            Matcher matcher = CONTROLLER_PATTERN.matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "UnknownController";
    }

    private List<Tag> parseTags(String[] lines) {
        List<Tag> tags = new ArrayList<>();
        String currentProgram = null;
        boolean inTagSection = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // Skip empty lines and comments
            if (line.isEmpty() || line.startsWith("(*") || line.startsWith("//")) {
                continue;
            }

            // Check for PROGRAM section
            Matcher programMatcher = PROGRAM_PATTERN.matcher(line);
            if (programMatcher.find()) {
                currentProgram = programMatcher.group(1);
                logger.debug("Entering program section: {}", currentProgram);
                continue;
            }

            // Check for TAG definition
            Matcher tagMatcher = TAG_PATTERN.matcher(line);
            if (tagMatcher.find()) {
                Tag tag = new Tag();
                tag.name = tagMatcher.group(1);
                tag.dataType = normalizeDataType(tagMatcher.group(2));
                tag.program = currentProgram;

                // Check for array size
                if (tagMatcher.group(3) != null) {
                    tag.arraySize = Integer.parseInt(tagMatcher.group(3));
                }

                // Look for description in following lines
                if (i + 1 < lines.length) {
                    String nextLine = lines[i + 1].trim();
                    if (nextLine.contains("Description :=") || nextLine.contains("(Description :=")) {
                        int start = nextLine.indexOf("\"");
                        int end = nextLine.lastIndexOf("\"");
                        if (start >= 0 && end > start) {
                            tag.description = nextLine.substring(start + 1, end);
                        }
                    }
                }

                tags.add(tag);
                logger.trace("Found tag: {} of type {} in {}",
                    tag.name, tag.dataType,
                    tag.program != null ? "program " + tag.program : "controller scope");
            }
        }

        return tags;
    }

    private String normalizeDataType(String dataType) {
        // Normalize Rockwell data types to standard names
        return switch (dataType.toUpperCase()) {
            case "BOOL" -> "BOOL";
            case "SINT" -> "SINT";
            case "INT" -> "INT";
            case "DINT" -> "DINT";
            case "LINT" -> "LINT";
            case "REAL" -> "REAL";
            case "LREAL" -> "LREAL";
            case "STRING" -> "STRING";
            default -> dataType; // Keep original for UDTs
        };
    }

    private String getDefaultValue(String dataType) {
        return switch (dataType) {
            case "BOOL" -> "false";
            case "REAL", "LREAL" -> "0.0";
            case "STRING" -> "";
            default -> "0";
        };
    }

    private JsonObject createDemoStructure() {
        String json = """
            {
                "controller": "DemoController",
                "vendor": "rockwell",
                "format": "L5K",
                "global_tags": [
                    {
                        "name": "L5K_ParseError",
                        "dataType": "DINT",
                        "value": 1,
                        "description": "L5K file could not be parsed - check format"
                    }
                ]
            }
            """;
        return new com.google.gson.Gson().fromJson(json, JsonObject.class);
    }

    @Override
    public boolean canHandle(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lowerName = fileName.toLowerCase();
        // Only handle .l5k files, NOT .l5x (those are XML)
        return lowerName.endsWith(".l5k");
    }

    @Override
    public String getParserType() {
        return "l5k";
    }

    // Inner class to hold tag information during parsing
    private static class Tag {
        String name;
        String dataType;
        String program;
        String description;
        int arraySize;
    }
}