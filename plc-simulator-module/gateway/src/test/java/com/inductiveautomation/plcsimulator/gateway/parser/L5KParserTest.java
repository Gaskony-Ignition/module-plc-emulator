package com.inductiveautomation.plcsimulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for L5KParser (Rockwell text format).
 * Tests parsing of controller tags, UDTs, AOIs, and programs.
 */
class L5KParserTest {

    private L5KParser parser;

    @BeforeEach
    void setUp() {
        parser = new L5KParser();
    }

    @Test
    @DisplayName("Should parse simple controller with tags")
    void testParseSimpleController() {
        String content = """
            CONTROLLER MainController (
                Description := "Test Controller"
            )
            TAG
                Motor1_Speed : DINT
                Motor2_Running : BOOL
                Temperature : REAL
            END_TAG
            """;

        JsonObject result = parser.parseContent(content, "test.l5k");

        assertThat(result).isNotNull();
        assertThat(result.get("vendor").getAsString()).isEqualTo("rockwell");
        assertThat(result.get("format").getAsString()).isEqualTo("L5K");
        assertThat(result.get("controller").getAsString()).isEqualTo("MainController");
    }

    @Test
    @DisplayName("Should parse UDT definitions")
    void testParseUdtDefinitions() {
        String content = """
            CONTROLLER TestController (
            )
            DATATYPE MyMotorType
                Speed : DINT
                Running : BOOL
                Current : REAL
            END_DATATYPE
            TAG
                Motor1 : MyMotorType
            END_TAG
            """;

        JsonObject result = parser.parseContent(content, "udt_test.l5k");

        assertThat(result).isNotNull();
        assertThat(result.has("global_tags")).isTrue();

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isGreaterThanOrEqualTo(1);

        // Find Motor1 tag
        JsonObject motor1 = null;
        for (var elem : tags) {
            JsonObject tag = elem.getAsJsonObject();
            if ("Motor1".equals(tag.get("name").getAsString())) {
                motor1 = tag;
                break;
            }
        }

        assertThat(motor1).isNotNull();
        assertThat(motor1.has("udt_members")).isTrue();
    }

    @Test
    @DisplayName("Should parse AOI definitions")
    void testParseAoiDefinitions() {
        String content = """
            CONTROLLER TestController (
            )
            ADD_ON_INSTRUCTION_DEFINITION MyAOI
            PARAMETERS
                Input1 : DINT
                Output1 : DINT
            END_PARAMETERS
            LOCAL_TAGS
                InternalVar : DINT
            END_LOCAL_TAGS
            END_ADD_ON_INSTRUCTION_DEFINITION
            TAG
                AOI_Instance : MyAOI
            END_TAG
            """;

        JsonObject result = parser.parseContent(content, "aoi_test.l5k");

        assertThat(result).isNotNull();
        assertThat(result.has("global_tags")).isTrue();
    }

    @Test
    @DisplayName("Should parse program tags")
    void testParseProgramTags() {
        String content = """
            CONTROLLER TestController (
            )
            PROGRAM MainProgram
            TAG
                LocalCounter : DINT
                LocalFlag : BOOL
            END_TAG
            END_PROGRAM
            """;

        JsonObject result = parser.parseContent(content, "program_test.l5k");

        assertThat(result).isNotNull();
        assertThat(result.has("programs")).isTrue();

        JsonArray programs = result.getAsJsonArray("programs");
        assertThat(programs.size()).isGreaterThanOrEqualTo(1);

        JsonObject mainProgram = programs.get(0).getAsJsonObject();
        assertThat(mainProgram.get("name").getAsString()).isEqualTo("MainProgram");
    }

    @Test
    @DisplayName("Should parse array tags")
    void testParseArrayTags() {
        String content = """
            CONTROLLER TestController (
            )
            TAG
                DataArray : DINT[10]
                Matrix : REAL[5,3]
            END_TAG
            """;

        JsonObject result = parser.parseContent(content, "array_test.l5k");

        assertThat(result).isNotNull();
        assertThat(result.has("global_tags")).isTrue();
    }

    @Test
    @DisplayName("Should expand built-in TIMER type")
    void testExpandTimerType() {
        String content = """
            CONTROLLER TestController (
            )
            TAG
                DelayTimer : TIMER
            END_TAG
            """;

        JsonObject result = parser.parseContent(content, "timer_test.l5k");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags).isNotNull();

        // Find the TIMER tag
        for (var elem : tags) {
            JsonObject tag = elem.getAsJsonObject();
            if ("DelayTimer".equals(tag.get("name").getAsString())) {
                assertThat(tag.has("udt_members")).isTrue();
                JsonArray members = tag.getAsJsonArray("udt_members");
                assertThat(members.size()).isGreaterThan(0);
                break;
            }
        }
    }

    @Test
    @DisplayName("Should expand built-in COUNTER type")
    void testExpandCounterType() {
        String content = """
            CONTROLLER TestController (
            )
            TAG
                ProductCounter : COUNTER
            END_TAG
            """;

        JsonObject result = parser.parseContent(content, "counter_test.l5k");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");

        for (var elem : tags) {
            JsonObject tag = elem.getAsJsonObject();
            if ("ProductCounter".equals(tag.get("name").getAsString())) {
                assertThat(tag.has("udt_members")).isTrue();
                break;
            }
        }
    }

    @Test
    @DisplayName("Should handle empty file gracefully")
    void testEmptyFile() {
        JsonObject result = parser.parseContent("", "empty.l5k");

        // Should return a demo structure rather than null
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("Should handle malformed content gracefully")
    void testMalformedContent() {
        String malformed = "This is not valid L5K content at all!";

        JsonObject result = parser.parseContent(malformed, "malformed.l5k");

        // Should return demo structure, not crash
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("Should correctly identify L5K files")
    void testCanHandle() {
        assertThat(parser.canHandle("program.l5k")).isTrue();
        assertThat(parser.canHandle("PROGRAM.L5K")).isTrue();
        assertThat(parser.canHandle("test.L5k")).isTrue();

        assertThat(parser.canHandle("program.l5x")).isFalse();
        assertThat(parser.canHandle("program.json")).isFalse();
        assertThat(parser.canHandle(null)).isFalse();
    }

    @Test
    @DisplayName("Should return correct parser type")
    void testGetParserType() {
        assertThat(parser.getParserType()).isEqualTo("l5k");
    }

    @Test
    @DisplayName("Should parse BIT members in UDTs")
    void testParseBitMembers() {
        String content = """
            CONTROLLER TestController (
            )
            DATATYPE StatusType
                BIT Fault_A
                BIT Fault_B
                Value : DINT
            END_DATATYPE
            TAG
                SystemStatus : StatusType
            END_TAG
            """;

        JsonObject result = parser.parseContent(content, "bit_test.l5k");

        assertThat(result).isNotNull();
        assertThat(result.has("udts")).isTrue();
    }

    @Test
    @DisplayName("Should skip ZZZZ padding members")
    void testSkipZZZZMembers() {
        String content = """
            CONTROLLER TestController (
            )
            DATATYPE MyType
                RealMember : DINT
                ZZZZZZZZZZZZZZZZZZ : SINT
            END_DATATYPE
            """;

        JsonObject result = parser.parseContent(content, "zzzz_test.l5k");

        assertThat(result).isNotNull();
        if (result.has("udts")) {
            JsonArray udts = result.getAsJsonArray("udts");
            for (var elem : udts) {
                JsonObject udt = elem.getAsJsonObject();
                if ("MyType".equals(udt.get("name").getAsString())) {
                    JsonArray members = udt.getAsJsonArray("members");
                    for (var memberElem : members) {
                        JsonObject member = memberElem.getAsJsonObject();
                        assertThat(member.get("name").getAsString()).doesNotStartWith("ZZZZ");
                    }
                }
            }
        }
    }
}
