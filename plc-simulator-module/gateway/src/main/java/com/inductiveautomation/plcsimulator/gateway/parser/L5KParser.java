package com.inductiveautomation.plcsimulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
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
 */
public class L5KParser implements PLCParser {

    private static final Logger logger = LoggerFactory.getLogger(L5KParser.class);

    // Regex patterns for parsing L5K format
    private static final Pattern CONTROLLER_PATTERN = Pattern.compile(
        "^\\s*CONTROLLER\\s+(\\S+)\\s*[\\({]", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATATYPE_START_PATTERN = Pattern.compile(
        "^\\s*DATATYPE\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATATYPE_END_PATTERN = Pattern.compile(
        "^\\s*END_DATATYPE", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATATYPE_MEMBER_PATTERN = Pattern.compile(
        "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*([A-Za-z_][A-Za-z0-9_]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATATYPE_BIT_PATTERN = Pattern.compile(
        "^\\s*BIT\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern AOI_START_PATTERN = Pattern.compile(
        "^\\s*ADD_ON_INSTRUCTION_DEFINITION\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AOI_END_PATTERN = Pattern.compile(
        "^\\s*END_ADD_ON_INSTRUCTION_DEFINITION", Pattern.CASE_INSENSITIVE);
    private static final Pattern AOI_PARAMETERS_START = Pattern.compile(
        "^\\s*PARAMETERS", Pattern.CASE_INSENSITIVE);
    private static final Pattern AOI_PARAMETERS_END = Pattern.compile(
        "^\\s*END_PARAMETERS", Pattern.CASE_INSENSITIVE);
    private static final Pattern AOI_LOCAL_TAGS_START = Pattern.compile(
        "^\\s*LOCAL_TAGS", Pattern.CASE_INSENSITIVE);
    private static final Pattern AOI_LOCAL_TAGS_END = Pattern.compile(
        "^\\s*END_LOCAL_TAGS", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_BLOCK_START_PATTERN = Pattern.compile(
        "^\\s*TAG\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_BLOCK_END_PATTERN = Pattern.compile(
        "^\\s*END_TAG", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_DEFINITION_PATTERN = Pattern.compile(
        "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*([A-Za-z_][A-Za-z0-9_]*)(?:\\[(\\d+(?:,\\d+)*)\\])?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTROLLER_TAG_PATTERN = Pattern.compile(
        "^\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROGRAM_PATTERN = Pattern.compile(
        "^\\s*PROGRAM\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern END_PROGRAM_PATTERN = Pattern.compile(
        "END_PROGRAM", Pattern.CASE_INSENSITIVE);

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
            logger.info("Parsing L5K file: {} ({} chars)", fileName, fileContent.length());

            JsonObject result = new JsonObject();
            result.addProperty("vendor", "rockwell");
            result.addProperty("format", "L5K");

            String[] lines = fileContent.split("\\r?\\n");

            // Parse controller name
            String controllerName = parseControllerName(lines);
            if (controllerName != null) {
                result.addProperty("controller", controllerName);
            }

            // Build type definitions map: UDTs + AOIs + Built-ins
            Map<String, UDTDefinition> allDefinitions = buildTypeDefinitions(lines);
            logger.info("Total type definitions: {}", allDefinitions.size());

            // Add UDT definitions to result for reference
            addUdtDefinitionsToResult(result, allDefinitions);

            // Parse tags with UDT expansion
            ParseResult parseResult = parseTagsWithUDTs(lines, allDefinitions);

            if (!parseResult.globalTags.isEmpty()) {
                result.add("global_tags", parseResult.globalTags);
            }

            if (!parseResult.programTags.isEmpty()) {
                JsonArray programs = new JsonArray();
                for (var entry : parseResult.programTags.entrySet()) {
                    JsonObject program = new JsonObject();
                    program.addProperty("name", entry.getKey());
                    program.add("tags", entry.getValue());
                    programs.add(program);
                }
                result.add("programs", programs);
            }

            int totalTags = parseResult.globalTags.size() +
                parseResult.programTags.values().stream().mapToInt(JsonArray::size).sum();
            logger.info("L5K parsing complete: {} tags, {} UDT instances", totalTags, parseResult.udtInstanceCount);

            return totalTags > 0 ? result : createDemoStructure();

        } catch (Exception e) {
            logger.error("Error parsing L5K content", e);
            return createDemoStructure();
        }
    }

    private Map<String, UDTDefinition> buildTypeDefinitions(String[] lines) {
        Map<String, UDTDefinition> definitions = new HashMap<>();

        // Parse UDT definitions from file
        definitions.putAll(parseUDTDefinitions(lines));

        // Parse AOI definitions (expand like UDTs)
        definitions.putAll(parseAOIDefinitions(lines));

        // Add built-in Rockwell types
        definitions.putAll(RockwellBuiltInTypes.createAll());

        return definitions;
    }

    private Map<String, UDTDefinition> parseUDTDefinitions(String[] lines) {
        Map<String, UDTDefinition> definitions = new HashMap<>();

        for (int i = 0; i < lines.length; i++) {
            Matcher startMatcher = DATATYPE_START_PATTERN.matcher(lines[i]);
            if (startMatcher.find()) {
                String udtName = startMatcher.group(1);
                UDTDefinition udt = new UDTDefinition(udtName);

                for (int j = i + 1; j < lines.length; j++) {
                    if (DATATYPE_END_PATTERN.matcher(lines[j]).find()) {
                        definitions.put(udtName, udt);
                        i = j;
                        break;
                    }

                    // BIT field
                    Matcher bitMatcher = DATATYPE_BIT_PATTERN.matcher(lines[j]);
                    if (bitMatcher.find()) {
                        udt.addMember(bitMatcher.group(1), "BOOL");
                        continue;
                    }

                    // Regular member
                    Matcher memberMatcher = DATATYPE_MEMBER_PATTERN.matcher(lines[j]);
                    if (memberMatcher.find()) {
                        String memberName = memberMatcher.group(1);
                        if (!memberName.startsWith("ZZZZ")) {
                            udt.addMember(memberName, normalizeDataType(memberMatcher.group(2)));
                        }
                    }
                }
            }
        }
        return definitions;
    }

    private Map<String, UDTDefinition> parseAOIDefinitions(String[] lines) {
        Map<String, UDTDefinition> definitions = new HashMap<>();

        for (int i = 0; i < lines.length; i++) {
            Matcher startMatcher = AOI_START_PATTERN.matcher(lines[i]);
            if (startMatcher.find()) {
                String aoiName = startMatcher.group(1);
                UDTDefinition aoi = new UDTDefinition(aoiName);
                boolean inParameters = false, inLocalTags = false;

                for (int j = i + 1; j < lines.length; j++) {
                    String line = lines[j];

                    if (AOI_END_PATTERN.matcher(line).find()) {
                        definitions.put(aoiName, aoi);
                        i = j;
                        break;
                    }

                    if (AOI_PARAMETERS_START.matcher(line).find()) { inParameters = true; inLocalTags = false; continue; }
                    if (AOI_PARAMETERS_END.matcher(line).find()) { inParameters = false; continue; }
                    if (AOI_LOCAL_TAGS_START.matcher(line).find()) { inLocalTags = true; inParameters = false; continue; }
                    if (AOI_LOCAL_TAGS_END.matcher(line).find()) { inLocalTags = false; continue; }

                    if (inParameters || inLocalTags) {
                        Matcher memberMatcher = DATATYPE_MEMBER_PATTERN.matcher(line);
                        if (memberMatcher.find()) {
                            String memberName = memberMatcher.group(1);
                            if (!memberName.equals("EnableIn") && !memberName.equals("EnableOut") && !memberName.startsWith("ZZZZ")) {
                                aoi.addMember(memberName, normalizeDataType(memberMatcher.group(2)));
                            }
                        }
                    }
                }
            }
        }
        return definitions;
    }

    private ParseResult parseTagsWithUDTs(String[] lines, Map<String, UDTDefinition> udtDefinitions) {
        ParseResult result = new ParseResult();
        String currentProgram = null;
        boolean inTagBlock = false, inControllerScope = false;

        for (String line : lines) {
            if (CONTROLLER_PATTERN.matcher(line).find()) {
                inControllerScope = true;
                currentProgram = null;
                continue;
            }

            Matcher programMatcher = PROGRAM_PATTERN.matcher(line);
            if (programMatcher.find()) {
                currentProgram = programMatcher.group(1);
                inControllerScope = false;
                continue;
            }

            if (END_PROGRAM_PATTERN.matcher(line).find()) {
                currentProgram = null;
                continue;
            }

            if (TAG_BLOCK_START_PATTERN.matcher(line).find()) {
                inTagBlock = true;
                continue;
            }

            if (TAG_BLOCK_END_PATTERN.matcher(line).find()) {
                inTagBlock = false;
                continue;
            }

            // Controller-scoped tags outside TAG blocks
            if (!inTagBlock && inControllerScope && currentProgram == null) {
                Matcher tagMatcher = CONTROLLER_TAG_PATTERN.matcher(line);
                if (tagMatcher.find()) {
                    JsonObject tag = createTag(tagMatcher.group(1), tagMatcher.group(2), null, udtDefinitions, result);
                    result.globalTags.add(tag);
                    continue;
                }
            }

            // Tags within TAG blocks
            if (inTagBlock) {
                Matcher tagMatcher = TAG_DEFINITION_PATTERN.matcher(line);
                if (tagMatcher.find()) {
                    JsonObject tag = createTag(tagMatcher.group(1), tagMatcher.group(2), tagMatcher.group(3), udtDefinitions, result);
                    if (currentProgram != null) {
                        result.programTags.computeIfAbsent(currentProgram, k -> new JsonArray()).add(tag);
                    } else {
                        result.globalTags.add(tag);
                    }
                }
            }
        }
        return result;
    }

    private JsonObject createTag(String name, String dataType, String dimensions,
                                  Map<String, UDTDefinition> udtDefinitions, ParseResult result) {
        JsonObject tag = new JsonObject();
        tag.addProperty("name", name);
        tag.addProperty("data_type", normalizeDataType(dataType));

        if (dimensions != null) {
            tag.addProperty("dimensions", dimensions);
            tag.addProperty("isArray", true);
        }

        if (udtDefinitions.containsKey(dataType)) {
            expandUdtInstance(tag, udtDefinitions.get(dataType), udtDefinitions, 0);
            result.udtInstanceCount++;
        } else {
            tag.addProperty("value", getDefaultValue(normalizeDataType(dataType)));
        }

        return tag;
    }

    /**
     * Recursively expands a UDT instance, including nested UDTs.
     *
     * @param tag The tag JSON object to add udt_members to
     * @param udt The UDT definition to expand
     * @param allDefinitions Map of all UDT/AOI definitions for nested expansion
     * @param depth Current recursion depth (to prevent infinite loops)
     */
    private void expandUdtInstance(JsonObject tag, UDTDefinition udt,
                                   Map<String, UDTDefinition> allDefinitions, int depth) {
        // Prevent infinite recursion (max 10 levels deep)
        if (depth > 10) {
            logger.warn("Maximum UDT nesting depth exceeded for type: {}", udt.getName());
            return;
        }

        JsonArray members = new JsonArray();
        for (var member : udt.getMembers()) {
            JsonObject memberJson = new JsonObject();
            memberJson.addProperty("name", member.getName());
            memberJson.addProperty("data_type", member.getDataType());

            String memberType = member.getDataType();

            // Check if this member is itself a UDT/AOI that needs expansion
            if (allDefinitions.containsKey(memberType)) {
                // Recursively expand nested UDT
                expandUdtInstance(memberJson, allDefinitions.get(memberType), allDefinitions, depth + 1);
                logger.trace("Expanded nested UDT member: {}.{} of type {}",
                    udt.getName(), member.getName(), memberType);
            } else {
                // Atomic type - set default value
                memberJson.addProperty("initial_value", getDefaultValue(memberType));
            }

            members.add(memberJson);
        }
        tag.add("udt_members", members);
    }

    private void addUdtDefinitionsToResult(JsonObject result, Map<String, UDTDefinition> allDefinitions) {
        JsonArray udts = new JsonArray();
        for (var udt : allDefinitions.values()) {
            // Only include user-defined UDTs, not built-ins
            if (!RockwellBuiltInTypes.createAll().containsKey(udt.getName())) {
                JsonObject udtJson = new JsonObject();
                udtJson.addProperty("name", udt.getName());
                JsonArray members = new JsonArray();
                for (var member : udt.getMembers()) {
                    JsonObject memberJson = new JsonObject();
                    memberJson.addProperty("name", member.getName());
                    memberJson.addProperty("data_type", member.getDataType());
                    members.add(memberJson);
                }
                udtJson.add("members", members);
                udts.add(udtJson);
            }
        }
        if (udts.size() > 0) {
            result.add("udts", udts);
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

    private JsonObject createDemoStructure() {
        JsonObject result = new JsonObject();
        result.addProperty("controller", "DemoController");
        result.addProperty("vendor", "rockwell");
        result.addProperty("format", "L5K");
        JsonArray tags = new JsonArray();
        JsonObject errorTag = new JsonObject();
        errorTag.addProperty("name", "L5K_ParseError");
        errorTag.addProperty("data_type", "DINT");
        errorTag.addProperty("value", 1);
        errorTag.addProperty("description", "L5K file could not be parsed - check format");
        tags.add(errorTag);
        result.add("global_tags", tags);
        return result;
    }

    @Override
    public boolean canHandle(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".l5k");
    }

    @Override
    public String getParserType() {
        return "l5k";
    }

    private static class ParseResult {
        final JsonArray globalTags = new JsonArray();
        final Map<String, JsonArray> programTags = new HashMap<>();
        int udtInstanceCount = 0;
    }
}
