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
 * - TAG sections with multi-line format
 * - PROGRAM sections
 *
 * This is different from L5X which is XML-based.
 */
public class L5KParser implements PLCParser {

    private static final Logger logger = LoggerFactory.getLogger(L5KParser.class);

    // Regex patterns for parsing L5K format
    private static final Pattern CONTROLLER_PATTERN = Pattern.compile("CONTROLLER\\s+(\\S+)\\s*\\{", Pattern.CASE_INSENSITIVE);

    // CRITICAL: Real Studio 5000 L5K files have controller-scoped tags declared as:
    // 		TagName : DataType (properties...)
    // NOT with "TAG" keyword! The TAG keyword only appears in PROGRAM sections.
    private static final Pattern CONTROLLER_TAG_PATTERN = Pattern.compile("^\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*([A-Z][A-Za-z0-9_]*)\\s*\\(", Pattern.CASE_INSENSITIVE);

    // Legacy patterns for TAG keyword format (used inside PROGRAM sections)
    private static final Pattern TAG_START_PATTERN = Pattern.compile("^\\s*TAG\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATATYPE_LINE_PATTERN = Pattern.compile("^\\s*:(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_SIMPLE_PATTERN = Pattern.compile("TAG\\s+(\\S+)\\s*:\\s*(\\S+)(?:\\[(\\d+)\\])?", Pattern.CASE_INSENSITIVE);

    private static final Pattern PROGRAM_PATTERN = Pattern.compile("PROGRAM\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_TYPE_PATTERN = Pattern.compile("DATATYPE\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern END_TAG_PATTERN = Pattern.compile("^\\s*END_TAG", Pattern.CASE_INSENSITIVE);

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
            logger.info("File content length: {} characters", fileContent.length());

            JsonObject result = new JsonObject();
            result.addProperty("vendor", "rockwell");
            result.addProperty("format", "L5K");

            // Split content into lines for processing
            String[] lines = fileContent.split("\\r?\\n");
            logger.info("File has {} lines", lines.length);

            // Parse controller name
            String controllerName = parseControllerName(lines);
            if (controllerName != null) {
                result.addProperty("controller", controllerName);
                logger.info("Found controller: {}", controllerName);
            }

            // Parse tags (both controller and program scoped)
            List<Tag> allTags = parseTags(lines);
            logger.info("Parsed {} tags total", allTags.size());

            // Separate controller tags and program tags
            JsonArray globalTags = new JsonArray();
            Map<String, JsonArray> programTags = new HashMap<>();

            for (Tag tag : allTags) {
                JsonObject tagJson = new JsonObject();
                tagJson.addProperty("name", tag.name);
                // CRITICAL FIX: Use "data_type" with underscore to match AddressSpaceBuilder expectations
                tagJson.addProperty("data_type", tag.dataType);  // Changed from "dataType" to "data_type"
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

            // If no tags found, provide detailed diagnostic info
            if (totalTags == 0) {
                logger.warn("⚠️ No tags found in L5K file - file may have unexpected format");
                logger.info("Creating demo structure. Please check:");
                logger.info("1. File contains 'TAG' definitions");
                logger.info("2. TAG format matches expected patterns");
                logger.info("3. File is not corrupted or truncated");

                // Log first few lines for debugging
                logger.debug("First 10 lines of file:");
                for (int i = 0; i < Math.min(10, lines.length); i++) {
                    logger.debug("Line {}: {}", i+1, lines[i]);
                }

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

            // CRITICAL: Check for controller-scoped tag format: "TagName : DataType ("
            // This is the REAL format used in Studio 5000 L5K exports!
            Matcher controllerTagMatcher = CONTROLLER_TAG_PATTERN.matcher(lines[i]);  // Use original line (not trimmed) to preserve indent
            if (controllerTagMatcher.find()) {
                Tag tag = new Tag();
                tag.name = controllerTagMatcher.group(1);
                tag.dataType = normalizeDataType(controllerTagMatcher.group(2));
                tag.program = currentProgram;  // Will be null for controller-scoped tags

                tags.add(tag);
                logger.trace("Found controller tag: {} of type {}", tag.name, tag.dataType);
                continue;
            }

            // First try simple single-line TAG format (legacy)
            Matcher simpleTagMatcher = TAG_SIMPLE_PATTERN.matcher(line);
            if (simpleTagMatcher.find()) {
                Tag tag = new Tag();
                tag.name = simpleTagMatcher.group(1);
                tag.dataType = normalizeDataType(simpleTagMatcher.group(2));
                tag.program = currentProgram;

                // Check for array size
                if (simpleTagMatcher.group(3) != null) {
                    tag.arraySize = Integer.parseInt(simpleTagMatcher.group(3));
                }

                tags.add(tag);
                logger.debug("Found simple format tag: {} of type {}", tag.name, tag.dataType);
                continue;
            }

            // Check for multi-line TAG definition (real Studio 5000 format)
            Matcher tagStartMatcher = TAG_START_PATTERN.matcher(line);
            if (tagStartMatcher.find()) {
                Tag tag = new Tag();
                tag.name = tagStartMatcher.group(1);
                tag.program = currentProgram;

                logger.debug("Found TAG start: {} at line {}", tag.name, i+1);

                // Look for datatype in following lines
                boolean foundDataType = false;
                for (int j = i + 1; j < lines.length && j < i + 20; j++) {
                    String nextLine = lines[j].trim();

                    // Stop if we hit another TAG or section
                    if (nextLine.startsWith("TAG ") || nextLine.startsWith("PROGRAM ") ||
                        nextLine.startsWith("CONTROLLER ") || nextLine.startsWith("DATATYPE ")) {
                        break;
                    }

                    // Look for :datatype pattern
                    Matcher datatypeMatcher = DATATYPE_LINE_PATTERN.matcher(nextLine);
                    if (datatypeMatcher.find()) {
                        tag.dataType = normalizeDataType(datatypeMatcher.group(1));
                        foundDataType = true;
                        logger.debug("Found datatype {} for tag {} at line {}", tag.dataType, tag.name, j+1);
                        break;
                    }

                    // Also check for inline format like ":DINT" or ":BOOL[32]"
                    if (nextLine.startsWith(":")) {
                        String typeStr = nextLine.substring(1).trim();
                        // Handle array notation
                        if (typeStr.contains("[")) {
                            int bracketIdx = typeStr.indexOf("[");
                            tag.dataType = normalizeDataType(typeStr.substring(0, bracketIdx));
                            String arraySizeStr = typeStr.substring(bracketIdx + 1, typeStr.indexOf("]"));
                            try {
                                tag.arraySize = Integer.parseInt(arraySizeStr);
                            } catch (NumberFormatException e) {
                                logger.warn("Could not parse array size: {}", arraySizeStr);
                            }
                        } else {
                            tag.dataType = normalizeDataType(typeStr.split("\\s+")[0]);
                        }
                        foundDataType = true;
                        logger.debug("Found inline datatype {} for tag {}", tag.dataType, tag.name);
                        break;
                    }

                    // Look for Description
                    if (nextLine.contains("Description :=") || nextLine.contains("(Description :=")) {
                        int start = nextLine.indexOf("\"");
                        int end = nextLine.lastIndexOf("\"");
                        if (start >= 0 && end > start) {
                            tag.description = nextLine.substring(start + 1, end);
                        }
                    }
                }

                // Only add tag if we found a datatype
                if (foundDataType) {
                    tags.add(tag);
                    logger.trace("Added tag: {} of type {} in {}",
                        tag.name, tag.dataType,
                        tag.program != null ? "program " + tag.program : "controller scope");
                } else {
                    logger.warn("Could not find datatype for TAG: {}", tag.name);
                }
            }
        }

        logger.info("Tag parsing complete: found {} tags", tags.size());
        return tags;
    }

    private String normalizeDataType(String dataType) {
        // Normalize Rockwell data types to standard names
        // Handle both simple types and complex ones (remove extra info)
        String cleanType = dataType.split("\\(")[0].trim();  // Remove any parenthetical info

        return switch (cleanType.toUpperCase()) {
            case "BOOL" -> "BOOL";
            case "SINT" -> "SINT";
            case "INT" -> "INT";
            case "DINT" -> "DINT";
            case "LINT" -> "LINT";
            case "REAL" -> "REAL";
            case "LREAL" -> "LREAL";
            case "STRING" -> "STRING";
            default -> cleanType; // Keep original for UDTs
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
                        "data_type": "DINT",
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