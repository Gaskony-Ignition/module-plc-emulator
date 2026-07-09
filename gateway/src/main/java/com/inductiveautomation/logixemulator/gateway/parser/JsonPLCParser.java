package com.inductiveautomation.logixemulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parser for JSON PLC definitions.
 *
 * <p>Two input shapes are accepted:
 *
 * <p>1. The builder-native shape, matched directly against what
 * {@link com.inductiveautomation.logixemulator.gateway.device.AddressSpaceBuilder#buildAddressSpace}
 * expects:
 * <pre>
 * {
 *   "controller": "ControllerName",
 *   "global_tags": [
 *     {
 *       "name": "Tag1",
 *       "data_type": "DINT",
 *       "initial_value": 0,
 *       "description": "Tag description"
 *     }
 *   ],
 *   "programs": [
 *     {
 *       "name": "MainProgram",
 *       "tags": [
 *         { "name": "Tag2", "data_type": "REAL", "initial_value": 0.0 }
 *       ]
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>2. A flat "tags" shape (the form real-world hand-authored JSON tag files actually use, per
 * the v10.0.0 DoD run - see {@code plc-dod/tags.json}), using {@code type} instead of
 * {@code data_type} and an optional {@code scope} per tag (defaulting to
 * {@code Controller:Global}; {@code Program:&lt;name&gt;} routes the tag into that program):
 * <pre>
 * {
 *   "controller": "ControllerName",
 *   "tags": [
 *     {"name": "Tag1", "type": "DINT", "scope": "Controller:Global"},
 *     {"name": "Tag2", "type": "DINT", "scope": "Program:MainProgram"}
 *   ]
 * }
 * </pre>
 *
 * <p>Defect B2 (v10.0.0): the flat shape used to be accepted by {@link #parseContent} (it
 * satisfied the "has tags/global_tags/programs" validation) but passed through unchanged, so
 * {@code AddressSpaceBuilder.buildAddressSpace()} - which only ever looks at {@code global_tags}
 * and {@code programs} - created zero OPC-UA tags despite parsing reporting success.
 * {@link #normalizeToBuilderShape(JsonObject)} now translates the flat shape into the
 * builder-native {@code global_tags}/{@code programs} shape (with {@code type} copied to
 * {@code data_type}) before the parsed result is returned.
 */
public class JsonPLCParser implements PLCParser {

    private static final Logger logger = LoggerFactory.getLogger(JsonPLCParser.class);

    private static final String GLOBAL_SCOPE = "Controller:Global";
    private static final String PROGRAM_SCOPE_PREFIX = "Program:";

    @Override
    public JsonObject parse(String filePath) {
        try {
            String content = Files.readString(Paths.get(filePath));
            return parseContent(content, filePath);
        } catch (Exception e) {
            logger.error("Error reading JSON file: {}", filePath, e);
            return null;
        }
    }

    @Override
    public JsonObject parseContent(String fileContent, String fileName) {
        try {
            // Parse JSON content
            JsonObject json = JsonParser.parseString(fileContent).getAsJsonObject();

            // Validate required fields
            if (!json.has("global_tags") && !json.has("programs") && !json.has("tags")) {
                logger.error("JSON file must contain 'global_tags' or 'programs' array");
                return null;
            }

            // Add metadata
            if (!json.has("controller")) {
                json.addProperty("controller", "JSONController");
            }

            if (!json.has("vendor")) {
                json.addProperty("vendor", "json");
            }

            normalizeToBuilderShape(json);

            logger.info("Successfully parsed JSON PLC file: {}", fileName);
            return json;

        } catch (Exception e) {
            logger.error("Error parsing JSON content from: {}", fileName, e);
            return null;
        }
    }

    /**
     * Ensures the parsed JSON exposes {@code global_tags}/{@code programs} - the shape
     * {@code AddressSpaceBuilder.buildAddressSpace()} reads - even when the input used the flat
     * {@code tags} shape with {@code type}/{@code scope} fields instead (defect B2).
     *
     * <p>If the input already has {@code global_tags} or {@code programs}, it is left untouched
     * (already builder-native). Otherwise, any flat {@code tags} array is grouped by
     * {@code scope} into {@code global_tags} (default/{@code Controller:Global}) and
     * {@code programs[].tags} (for {@code Program:&lt;name&gt;} scopes), and the flat
     * {@code tags} key is removed once migrated.
     */
    private void normalizeToBuilderShape(JsonObject json) {
        if (json.has("global_tags") || json.has("programs")) {
            return;
        }
        if (!json.has("tags") || !json.get("tags").isJsonArray()) {
            return;
        }

        JsonArray flatTags = json.getAsJsonArray("tags");
        JsonArray globalTags = new JsonArray();
        Map<String, JsonArray> programTags = new LinkedHashMap<>();

        for (JsonElement tagElement : flatTags) {
            if (!tagElement.isJsonObject()) {
                continue;
            }
            JsonObject tag = normalizeTag(tagElement.getAsJsonObject());

            String scope = tag.has("scope") && !tag.get("scope").isJsonNull()
                ? tag.get("scope").getAsString()
                : GLOBAL_SCOPE;
            tag.remove("scope");

            if (scope.regionMatches(true, 0, PROGRAM_SCOPE_PREFIX, 0, PROGRAM_SCOPE_PREFIX.length())) {
                String programName = scope.substring(PROGRAM_SCOPE_PREFIX.length()).trim();
                programTags.computeIfAbsent(programName, name -> new JsonArray()).add(tag);
            } else {
                globalTags.add(tag);
            }
        }

        json.add("global_tags", globalTags);

        if (!programTags.isEmpty()) {
            JsonArray programs = new JsonArray();
            for (Map.Entry<String, JsonArray> entry : programTags.entrySet()) {
                JsonObject program = new JsonObject();
                program.addProperty("name", entry.getKey());
                program.add("tags", entry.getValue());
                programs.add(program);
            }
            json.add("programs", programs);
        }

        json.remove("tags");

        logger.debug(
            "Normalised flat 'tags' JSON shape into {} global tag(s) and {} program(s)",
            globalTags.size(), programTags.size());
    }

    /**
     * Returns a copy of {@code tag} with {@code data_type} present (copied from {@code type} when
     * {@code data_type} is absent) so downstream code only ever has to look for {@code data_type}.
     */
    private JsonObject normalizeTag(JsonObject tag) {
        JsonObject normalized = tag.deepCopy();
        if (!normalized.has("data_type") && normalized.has("type")) {
            normalized.add("data_type", normalized.get("type"));
        }
        normalized.remove("type");
        return normalized;
    }

    @Override
    public boolean canHandle(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".json");
    }

    @Override
    public String getParserType() {
        return "json";
    }
}
