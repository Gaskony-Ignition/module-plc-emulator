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

import static com.inductiveautomation.plcsimulator.gateway.parser.DataTypeUtils.*;

/**
 * Parser for Rockwell L5K files (text-based format).
 * L5K files are NOT XML - they use a proprietary text format with sections like:
 * - CONTROLLER section
 * - DATATYPE definitions (User Defined Types)
 * - TAG sections with multi-line format
 * - PROGRAM sections
 *
 * This parser now correctly handles UDTs and creates hierarchical structures.
 */
public class L5KParser implements PLCParser {

    private static final Logger logger = LoggerFactory.getLogger(L5KParser.class);

    // Regex patterns for parsing L5K format
    // Fixed: Accept both ( and { for CONTROLLER section (real Studio 5000 uses parentheses)
    private static final Pattern CONTROLLER_PATTERN = Pattern.compile("^\\s*CONTROLLER\\s+(\\S+)\\s*[\\({]", Pattern.CASE_INSENSITIVE);

    // DATATYPE patterns for UDT parsing
    private static final Pattern DATATYPE_START_PATTERN = Pattern.compile("^\\s*DATATYPE\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATATYPE_END_PATTERN = Pattern.compile("^\\s*END_DATATYPE", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATATYPE_MEMBER_PATTERN = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*([A-Za-z_][A-Za-z0-9_]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATATYPE_BIT_PATTERN = Pattern.compile("^\\s*BIT\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+", Pattern.CASE_INSENSITIVE);

    // ADD_ON_INSTRUCTION patterns for AOI parsing (critical for full tag expansion)
    private static final Pattern AOI_START_PATTERN = Pattern.compile("^\\s*ADD_ON_INSTRUCTION_DEFINITION\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AOI_END_PATTERN = Pattern.compile("^\\s*END_ADD_ON_INSTRUCTION_DEFINITION", Pattern.CASE_INSENSITIVE);
    private static final Pattern AOI_PARAMETERS_START = Pattern.compile("^\\s*PARAMETERS", Pattern.CASE_INSENSITIVE);
    private static final Pattern AOI_PARAMETERS_END = Pattern.compile("^\\s*END_PARAMETERS", Pattern.CASE_INSENSITIVE);
    private static final Pattern AOI_LOCAL_TAGS_START = Pattern.compile("^\\s*LOCAL_TAGS", Pattern.CASE_INSENSITIVE);
    private static final Pattern AOI_LOCAL_TAGS_END = Pattern.compile("^\\s*END_LOCAL_TAGS", Pattern.CASE_INSENSITIVE);

    // Tag patterns within TAG...END_TAG blocks
    private static final Pattern TAG_BLOCK_START_PATTERN = Pattern.compile("^\\s*TAG\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_BLOCK_END_PATTERN = Pattern.compile("^\\s*END_TAG", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_DEFINITION_PATTERN = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*([A-Za-z_][A-Za-z0-9_]*)(?:\\[(\\d+(?:,\\d+)*)\\])?", Pattern.CASE_INSENSITIVE);

    // Controller-scoped tags (outside TAG blocks) - Critical for real Studio 5000 files
    private static final Pattern CONTROLLER_TAG_PATTERN = Pattern.compile("^\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\(", Pattern.CASE_INSENSITIVE);

    // Program pattern - Fixed: Require at start of line to avoid matching "PROGRAM" in comments
    private static final Pattern PROGRAM_PATTERN = Pattern.compile("^\\s*PROGRAM\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern END_PROGRAM_PATTERN = Pattern.compile("END_PROGRAM", Pattern.CASE_INSENSITIVE);

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

            // CRITICAL: Parse UDT and AOI definitions FIRST - we need these to expand instances
            Map<String, UDTDefinition> udtDefinitions = parseUDTDefinitions(lines);
            logger.info("Parsed {} UDT definitions", udtDefinitions.size());

            // Parse AOI (Add-On Instruction) definitions - treat like UDTs for expansion
            Map<String, UDTDefinition> aoiDefinitions = parseAOIDefinitions(lines);
            logger.info("Parsed {} AOI definitions", aoiDefinitions.size());

            // Merge AOI definitions with UDT definitions (both expand the same way)
            Map<String, UDTDefinition> allDefinitions = new HashMap<>(udtDefinitions);
            allDefinitions.putAll(aoiDefinitions);
            logger.info("Total definitions (UDTs + AOIs): {}", allDefinitions.size());

            // Add UDT/AOI definitions to result (for debugging/reference)
            if (!udtDefinitions.isEmpty()) {
                JsonArray udts = new JsonArray();
                for (UDTDefinition udt : udtDefinitions.values()) {
                    JsonObject udtJson = new JsonObject();
                    udtJson.addProperty("name", udt.name);
                    JsonArray members = new JsonArray();
                    for (UDTMember member : udt.members) {
                        JsonObject memberJson = new JsonObject();
                        memberJson.addProperty("name", member.name);
                        memberJson.addProperty("data_type", member.dataType);
                        members.add(memberJson);
                    }
                    udtJson.add("members", members);
                    udts.add(udtJson);
                }
                result.add("udts", udts);
            }

            // Parse tags (both controller and program scoped) with UDT/AOI expansion
            ParseResult parseResult = parseTagsWithUDTs(lines, allDefinitions);

            // Add global tags
            if (parseResult.globalTags.size() > 0) {
                result.add("global_tags", parseResult.globalTags);
                logger.info("Found {} global tags", parseResult.globalTags.size());
            }

            // Add program tags
            if (!parseResult.programTags.isEmpty()) {
                JsonArray programs = new JsonArray();
                for (Map.Entry<String, JsonArray> entry : parseResult.programTags.entrySet()) {
                    JsonObject program = new JsonObject();
                    program.addProperty("name", entry.getKey());
                    program.add("tags", entry.getValue());
                    programs.add(program);
                }
                result.add("programs", programs);
                logger.info("Found {} programs with tags", parseResult.programTags.size());
            }

            // Log summary
            int totalTags = parseResult.globalTags.size() + parseResult.programTags.values().stream()
                .mapToInt(JsonArray::size)
                .sum();
            logger.info("L5K parsing complete: {} total tag declarations found, {} were UDT/AOI instances that will expand into folders",
                totalTags, parseResult.udtInstanceCount);

            // If no tags found, provide detailed diagnostic info
            if (totalTags == 0) {
                logger.warn("No tags found in L5K file - file may have unexpected format");
                return createDemoStructure();
            }

            return result;

        } catch (Exception e) {
            logger.error("Error parsing L5K content", e);
            return createDemoStructure();
        }
    }

    /**
     * Parse all DATATYPE definitions from the file.
     * These define User Defined Types (UDTs) that tags can reference.
     */
    private Map<String, UDTDefinition> parseUDTDefinitions(String[] lines) {
        Map<String, UDTDefinition> udtDefinitions = new HashMap<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            Matcher startMatcher = DATATYPE_START_PATTERN.matcher(line);
            if (startMatcher.find()) {
                String udtName = startMatcher.group(1);
                UDTDefinition udt = new UDTDefinition(udtName);

                logger.debug("Found DATATYPE: {} at line {}", udtName, i+1);

                // Parse members until END_DATATYPE
                for (int j = i + 1; j < lines.length; j++) {
                    String memberLine = lines[j];

                    // Check for end of datatype
                    if (DATATYPE_END_PATTERN.matcher(memberLine).find()) {
                        udtDefinitions.put(udtName, udt);
                        logger.debug("Completed UDT {} with {} members", udtName, udt.members.size());
                        i = j; // Skip to end of this datatype
                        break;
                    }

                    // Check for BIT field (special case)
                    Matcher bitMatcher = DATATYPE_BIT_PATTERN.matcher(memberLine);
                    if (bitMatcher.find()) {
                        String bitName = bitMatcher.group(1);
                        udt.addMember(bitName, "BOOL");
                        continue;
                    }

                    // Check for regular member
                    Matcher memberMatcher = DATATYPE_MEMBER_PATTERN.matcher(memberLine);
                    if (memberMatcher.find()) {
                        String memberName = memberMatcher.group(1);
                        String memberType = memberMatcher.group(2);

                        // Skip hidden/internal members (start with ZZZZ)
                        if (!memberName.startsWith("ZZZZ")) {
                            udt.addMember(memberName, normalizeDataType(memberType));
                            logger.trace("Added UDT member: {}.{} of type {}", udtName, memberName, memberType);
                        }
                    }
                }
            }
        }

        return udtDefinitions;
    }

    /**
     * Parse all ADD_ON_INSTRUCTION definitions from the file.
     * AOIs (Add-On Instructions) are custom function blocks with PARAMETERS and LOCAL_TAGS.
     * These should be treated like UDTs for tag expansion purposes.
     */
    private Map<String, UDTDefinition> parseAOIDefinitions(String[] lines) {
        Map<String, UDTDefinition> aoiDefinitions = new HashMap<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            Matcher startMatcher = AOI_START_PATTERN.matcher(line);
            if (startMatcher.find()) {
                String aoiName = startMatcher.group(1);
                UDTDefinition aoi = new UDTDefinition(aoiName);

                logger.debug("Found ADD_ON_INSTRUCTION: {} at line {}", aoiName, i+1);

                boolean inParameters = false;
                boolean inLocalTags = false;

                // Parse PARAMETERS and LOCAL_TAGS sections until END_ADD_ON_INSTRUCTION_DEFINITION
                for (int j = i + 1; j < lines.length; j++) {
                    String aoiLine = lines[j];

                    // Check for end of AOI
                    if (AOI_END_PATTERN.matcher(aoiLine).find()) {
                        aoiDefinitions.put(aoiName, aoi);
                        logger.debug("Completed AOI {} with {} members", aoiName, aoi.members.size());
                        i = j; // Skip to end of this AOI
                        break;
                    }

                    // Track PARAMETERS section
                    if (AOI_PARAMETERS_START.matcher(aoiLine).find()) {
                        inParameters = true;
                        inLocalTags = false;
                        continue;
                    }
                    if (AOI_PARAMETERS_END.matcher(aoiLine).find()) {
                        inParameters = false;
                        continue;
                    }

                    // Track LOCAL_TAGS section
                    if (AOI_LOCAL_TAGS_START.matcher(aoiLine).find()) {
                        inLocalTags = true;
                        inParameters = false;
                        continue;
                    }
                    if (AOI_LOCAL_TAGS_END.matcher(aoiLine).find()) {
                        inLocalTags = false;
                        continue;
                    }

                    // Parse members in PARAMETERS or LOCAL_TAGS sections
                    if (inParameters || inLocalTags) {
                        Matcher memberMatcher = DATATYPE_MEMBER_PATTERN.matcher(aoiLine);
                        if (memberMatcher.find()) {
                            String memberName = memberMatcher.group(1);
                            String memberType = memberMatcher.group(2);

                            // Skip system parameters (EnableIn, EnableOut) and hidden members
                            if (!memberName.equals("EnableIn") && !memberName.equals("EnableOut")
                                && !memberName.startsWith("ZZZZ")) {
                                aoi.addMember(memberName, normalizeDataType(memberType));
                                logger.trace("Added AOI member: {}.{} of type {} (from {})",
                                    aoiName, memberName, memberType, inParameters ? "PARAMETERS" : "LOCAL_TAGS");
                            }
                        }
                    }
                }
            }
        }

        return aoiDefinitions;
    }

    /**
     * Parse tags with UDT/AOI expansion.
     * AOIs are treated just like UDTs for expansion purposes.
     */
    private ParseResult parseTagsWithUDTs(String[] lines, Map<String, UDTDefinition> udtDefinitions) {
        ParseResult result = new ParseResult();
        String currentProgram = null;
        boolean inTagBlock = false;
        boolean inControllerScope = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Check for CONTROLLER section (contains controller-scoped tags)
            if (CONTROLLER_PATTERN.matcher(line).find()) {
                inControllerScope = true;
                currentProgram = null;
                continue;
            }

            // Check for PROGRAM section
            Matcher programMatcher = PROGRAM_PATTERN.matcher(line);
            if (programMatcher.find()) {
                currentProgram = programMatcher.group(1);
                inControllerScope = false;
                logger.debug("Entering program section: {}", currentProgram);
                continue;
            }

            // Check for END_PROGRAM
            if (END_PROGRAM_PATTERN.matcher(line).find()) {
                currentProgram = null;
                continue;
            }

            // Check for TAG block start
            if (TAG_BLOCK_START_PATTERN.matcher(line).find()) {
                inTagBlock = true;
                logger.debug("Entering TAG block at line {} in {}", i+1,
                    currentProgram != null ? "program " + currentProgram : "controller scope");
                continue;
            }

            // Check for TAG block end
            if (TAG_BLOCK_END_PATTERN.matcher(line).find()) {
                inTagBlock = false;
                continue;
            }

            // CRITICAL: Parse controller-scoped tags that appear OUTSIDE TAG blocks
            // This handles the real Studio 5000 format where controller tags don't use TAG keyword
            if (!inTagBlock && inControllerScope && currentProgram == null) {
                Matcher controllerTagMatcher = CONTROLLER_TAG_PATTERN.matcher(line);
                if (controllerTagMatcher.find()) {
                    String tagName = controllerTagMatcher.group(1);
                    String dataType = controllerTagMatcher.group(2);

                    JsonObject tag = new JsonObject();
                    tag.addProperty("name", tagName);
                    tag.addProperty("data_type", normalizeDataType(dataType));

                    // Check if this is a UDT/AOI instance and expand it
                    if (expandUdtInstance(tag, dataType, udtDefinitions)) {
                        result.udtInstanceCount++;
                    } else {
                        // Regular atomic tag (MESSAGE, TIMER, etc.)
                        tag.addProperty("value", getDefaultValue(normalizeDataType(dataType)));
                    }

                    result.globalTags.add(tag);
                    logger.trace("Added controller tag (outside TAG block): {} of type {}", tagName, dataType);
                    continue;  // Skip to next line
                }
            }

            // Parse tag definitions within TAG blocks
            if (inTagBlock) {
                Matcher tagMatcher = TAG_DEFINITION_PATTERN.matcher(line);
                if (tagMatcher.find()) {
                    String tagName = tagMatcher.group(1);
                    String dataType = tagMatcher.group(2);
                    String arrayDimensions = tagMatcher.group(3);

                    JsonObject tag = new JsonObject();
                    tag.addProperty("name", tagName);
                    tag.addProperty("data_type", normalizeDataType(dataType));

                    // Handle arrays
                    if (arrayDimensions != null) {
                        tag.addProperty("dimensions", arrayDimensions);
                        tag.addProperty("isArray", true);
                    }

                    // Check if this is a UDT/AOI instance and expand it
                    if (expandUdtInstance(tag, dataType, udtDefinitions)) {
                        result.udtInstanceCount++;
                    } else {
                        // Regular atomic tag
                        tag.addProperty("value", getDefaultValue(normalizeDataType(dataType)));
                    }

                    // Add tag to appropriate collection
                    if (currentProgram != null) {
                        result.programTags.computeIfAbsent(currentProgram, k -> new JsonArray()).add(tag);
                    } else {
                        result.globalTags.add(tag);
                    }

                    logger.trace("Added tag: {} of type {} to {}", tagName, dataType,
                        currentProgram != null ? "program " + currentProgram : "controller scope");
                }
            }
        }

        return result;
    }

    /**
     * Expands a UDT/AOI instance by adding its members to the tag.
     *
     * @param tag The tag JSON object to expand
     * @param dataType The UDT/AOI type name
     * @param udtDefinitions Map of all UDT/AOI definitions
     * @return true if expansion occurred, false if not a UDT/AOI
     */
    private boolean expandUdtInstance(JsonObject tag, String dataType, Map<String, UDTDefinition> udtDefinitions) {
        if (!udtDefinitions.containsKey(dataType)) {
            return false;
        }

        UDTDefinition udtDef = udtDefinitions.get(dataType);
        JsonArray udtMembers = new JsonArray();

        for (UDTMember member : udtDef.members) {
            JsonObject memberJson = new JsonObject();
            memberJson.addProperty("name", member.name);
            memberJson.addProperty("data_type", member.dataType);
            memberJson.addProperty("initial_value", getDefaultValue(member.dataType));
            udtMembers.add(memberJson);
        }

        tag.add("udt_members", udtMembers);
        logger.debug("Expanded UDT/AOI instance: {} of type {} with {} members",
            tag.get("name").getAsString(), dataType, udtMembers.size());

        return true;
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
        return lowerName.endsWith(".l5k");
    }

    @Override
    public String getParserType() {
        return "l5k";
    }

    // Helper class for UDT definition
    private static class UDTDefinition {
        String name;
        List<UDTMember> members = new ArrayList<>();

        UDTDefinition(String name) {
            this.name = name;
        }

        void addMember(String memberName, String memberType) {
            members.add(new UDTMember(memberName, memberType));
        }
    }

    // Helper class for UDT member
    private static class UDTMember {
        String name;
        String dataType;

        UDTMember(String name, String dataType) {
            this.name = name;
            this.dataType = dataType;
        }
    }

    // Helper class to hold parsing results
    private static class ParseResult {
        JsonArray globalTags = new JsonArray();
        Map<String, JsonArray> programTags = new HashMap<>();
        int udtInstanceCount = 0;
    }
}