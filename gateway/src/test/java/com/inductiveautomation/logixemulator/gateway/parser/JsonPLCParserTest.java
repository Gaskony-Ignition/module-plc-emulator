package com.inductiveautomation.logixemulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

class JsonPLCParserTest {

    private JsonPLCParser parser;

    @BeforeEach
    void setUp() {
        parser = new JsonPLCParser();
    }

    @Test
    @DisplayName("Should parse JSON with global tags")
    void testParseGlobalTags() {
        String content = """
            {
              "controller": "TestPLC",
              "global_tags": [
                {"name": "Motor1_Speed", "data_type": "DINT", "initial_value": 100},
                {"name": "Temperature", "data_type": "REAL", "initial_value": 72.5}
              ]
            }
            """;

        JsonObject result = parser.parseContent(content, "test.json");

        assertThat(result).isNotNull();
        assertThat(result.get("controller").getAsString()).isEqualTo("TestPLC");
        assertThat(result.has("global_tags")).isTrue();

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isEqualTo(2);

        JsonObject motor = tags.get(0).getAsJsonObject();
        assertThat(motor.get("name").getAsString()).isEqualTo("Motor1_Speed");
        assertThat(motor.get("data_type").getAsString()).isEqualTo("DINT");
    }

    @Test
    @DisplayName("Should parse JSON with programs")
    void testParsePrograms() {
        String content = """
            {
              "controller": "TestPLC",
              "programs": [
                {
                  "name": "MainProgram",
                  "tags": [
                    {"name": "LocalVar", "data_type": "DINT", "initial_value": 0}
                  ]
                }
              ]
            }
            """;

        JsonObject result = parser.parseContent(content, "test.json");

        assertThat(result).isNotNull();
        assertThat(result.has("programs")).isTrue();

        JsonArray programs = result.getAsJsonArray("programs");
        assertThat(programs.size()).isEqualTo(1);

        JsonObject mainProgram = programs.get(0).getAsJsonObject();
        assertThat(mainProgram.get("name").getAsString()).isEqualTo("MainProgram");
    }

    @Test
    @DisplayName("Should add default controller name if missing")
    void testDefaultControllerName() {
        String content = """
            {
              "global_tags": [
                {"name": "Tag1", "data_type": "BOOL"}
              ]
            }
            """;

        JsonObject result = parser.parseContent(content, "test.json");

        assertThat(result).isNotNull();
        assertThat(result.get("controller").getAsString()).isEqualTo("JSONController");
    }

    @Test
    @DisplayName("Should add default vendor if missing")
    void testDefaultVendor() {
        String content = """
            {
              "global_tags": [
                {"name": "Tag1", "data_type": "DINT"}
              ]
            }
            """;

        JsonObject result = parser.parseContent(content, "test.json");

        assertThat(result).isNotNull();
        assertThat(result.get("vendor").getAsString()).isEqualTo("json");
    }

    @Test
    @DisplayName("Should preserve existing controller and vendor")
    void testPreserveExistingMetadata() {
        String content = """
            {
              "controller": "MyPLC",
              "vendor": "custom",
              "global_tags": []
            }
            """;

        JsonObject result = parser.parseContent(content, "test.json");

        assertThat(result).isNotNull();
        assertThat(result.get("controller").getAsString()).isEqualTo("MyPLC");
        assertThat(result.get("vendor").getAsString()).isEqualTo("custom");
    }

    @Test
    @DisplayName("Should reject JSON without tags or programs")
    void testRejectNoTags() {
        String content = """
            {
              "controller": "TestPLC",
              "someOtherField": "value"
            }
            """;

        JsonObject result = parser.parseContent(content, "test.json");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should accept JSON with 'tags' key (alternate schema)")
    void testAcceptAlternateTags() {
        String content = """
            {
              "tags": [
                {"name": "Tag1", "data_type": "DINT"}
              ]
            }
            """;

        JsonObject result = parser.parseContent(content, "test.json");

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("Should handle invalid JSON gracefully")
    void testInvalidJson() {
        String content = "this is not valid JSON {{{";

        JsonObject result = parser.parseContent(content, "test.json");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should handle empty JSON object")
    void testEmptyJsonObject() {
        String content = "{}";

        JsonObject result = parser.parseContent(content, "test.json");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should parse UDT members in tags")
    void testParseUdtMembers() {
        String content = """
            {
              "global_tags": [
                {
                  "name": "Motor1",
                  "data_type": "MotorType",
                  "udt_members": [
                    {"name": "Speed", "data_type": "DINT", "initial_value": 0},
                    {"name": "Running", "data_type": "BOOL", "initial_value": false}
                  ]
                }
              ]
            }
            """;

        JsonObject result = parser.parseContent(content, "test.json");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        JsonObject motor = tags.get(0).getAsJsonObject();
        assertThat(motor.get("data_type").getAsString()).isEqualTo("MotorType");
        assertThat(motor.has("udt_members")).isTrue();
        assertThat(motor.getAsJsonArray("udt_members").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should correctly identify JSON files")
    void testCanHandle() {
        assertThat(parser.canHandle("tags.json")).isTrue();
        assertThat(parser.canHandle("TAGS.JSON")).isTrue();
        assertThat(parser.canHandle("data.JSON")).isTrue();

        assertThat(parser.canHandle("tags.csv")).isFalse();
        assertThat(parser.canHandle("tags.l5k")).isFalse();
        assertThat(parser.canHandle(null)).isFalse();
    }

    @Test
    @DisplayName("Should return correct parser type")
    void testGetParserType() {
        assertThat(parser.getParserType()).isEqualTo("json");
    }

    @Test
    @DisplayName("Should handle both global tags and programs together")
    void testGlobalTagsAndPrograms() {
        String content = """
            {
              "controller": "FullPLC",
              "global_tags": [
                {"name": "GlobalTag", "data_type": "DINT", "initial_value": 42}
              ],
              "programs": [
                {
                  "name": "MainProgram",
                  "tags": [
                    {"name": "LocalTag", "data_type": "BOOL", "initial_value": true}
                  ]
                }
              ]
            }
            """;

        JsonObject result = parser.parseContent(content, "test.json");

        assertThat(result).isNotNull();
        assertThat(result.getAsJsonArray("global_tags").size()).isEqualTo(1);
        assertThat(result.getAsJsonArray("programs").size()).isEqualTo(1);
    }
}
