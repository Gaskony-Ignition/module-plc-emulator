package com.inductiveautomation.logixemulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link L5KParser} (Rockwell text format).
 *
 * <p><b>Corrected to L5K-GRAMMAR.md for the v10.1 parser rewrite.</b> The pre-v10.1 versions of
 * these tests used inline content that was not valid L5K at all - tag lines without the
 * mandatory {@code ;} statement terminator, DATATYPE members written {@code NAME : TYPE}
 * (the real grammar is {@code TYPE NAME}, §2.6), and {@code BIT} members with no host byte or
 * bit position (§2.6 requires both). The old regex parser happened to accept all of that; the
 * statement-oriented parser correctly does not, so every inline fixture here was rewritten to
 * the normative grammar while keeping each test's original intent. Per-construct grammar
 * coverage lives in {@link L5KParserGrammarTest}.
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
            CONTROLLER MainController (Description := "Test Controller")
            \tTAG
            \t\tMotor1_Speed : DINT (RADIX := Decimal) := 0;
            \t\tMotor2_Running : BOOL (RADIX := Decimal) := 0;
            \t\tTemperature : REAL (RADIX := Float) := 0.0;
            \tEND_TAG
            END_CONTROLLER
            """;

        JsonObject result = parser.parseContent(content, "test.l5k");

        assertThat(result).isNotNull();
        assertThat(result.get("vendor").getAsString()).isEqualTo("rockwell");
        assertThat(result.get("format").getAsString()).isEqualTo("L5K");
        assertThat(result.get("controller").getAsString()).isEqualTo("MainController");
        assertThat(result.getAsJsonArray("global_tags")).hasSize(3);
    }

    @Test
    @DisplayName("Should parse UDT definitions (TYPE NAME member order, §2.6)")
    void testParseUdtDefinitions() {
        String content = """
            CONTROLLER TestController (Description := "UDT test")
            \tDATATYPE MyMotorType (FamilyType := NoFamily)
            \t\tDINT Speed (Radix := Decimal);
            \t\tREAL Current (Radix := Float);
            \tEND_DATATYPE
            \tTAG
            \t\tMotor1 : MyMotorType;
            \tEND_TAG
            END_CONTROLLER
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
        assertThat(motor1.getAsJsonArray("udt_members")).hasSize(2);
    }

    @Test
    @DisplayName("Should parse AOI definitions")
    void testParseAoiDefinitions() {
        String content = """
            CONTROLLER TestController (Description := "AOI test")
            \tADD_ON_INSTRUCTION_DEFINITION MyAOI (Revision := "1.0")
            \t\tPARAMETERS
            \t\t\tInput1 : DINT (Usage := Input, RADIX := Decimal, Required := Yes, Visible := Yes, DefaultData := 0);
            \t\t\tOutput1 : DINT (Usage := Output, RADIX := Decimal, Required := No, Visible := Yes, DefaultData := 0);
            \t\tEND_PARAMETERS
            \t\tLOCAL_TAGS
            \t\t\tInternalVar : DINT (RADIX := Decimal, ExternalAccess := Read/Write, DefaultData := 0);
            \t\tEND_LOCAL_TAGS
            \tEND_ADD_ON_INSTRUCTION_DEFINITION
            \tTAG
            \t\tAOI_Instance : MyAOI;
            \tEND_TAG
            END_CONTROLLER
            """;

        JsonObject result = parser.parseContent(content, "aoi_test.l5k");

        assertThat(result).isNotNull();
        assertThat(result.has("global_tags")).isTrue();
        assertThat(result.has("aois")).isTrue();

        JsonObject instance = result.getAsJsonArray("global_tags").get(0).getAsJsonObject();
        assertThat(instance.get("name").getAsString()).isEqualTo("AOI_Instance");
        assertThat(instance.has("udt_members")).isTrue();
    }

    @Test
    @DisplayName("Should parse program tags")
    void testParseProgramTags() {
        String content = """
            CONTROLLER TestController (Description := "program test")
            \tTAG
            \tEND_TAG
            \tPROGRAM MainProgram (MAIN := 1)
            \t\tTAG
            \t\t\tLocalCounter : DINT (RADIX := Decimal) := 100;
            \t\t\tLocalFlag : BOOL (RADIX := Decimal) := 0;
            \t\tEND_TAG
            \tEND_PROGRAM
            END_CONTROLLER
            """;

        JsonObject result = parser.parseContent(content, "program_test.l5k");

        assertThat(result).isNotNull();
        assertThat(result.has("programs")).isTrue();

        JsonArray programs = result.getAsJsonArray("programs");
        assertThat(programs.size()).isGreaterThanOrEqualTo(1);

        JsonObject mainProgram = programs.get(0).getAsJsonObject();
        assertThat(mainProgram.get("name").getAsString()).isEqualTo("MainProgram");
        assertThat(mainProgram.getAsJsonArray("tags")).hasSize(2);
    }

    @Test
    @DisplayName("Should parse array tags")
    void testParseArrayTags() {
        String content = """
            CONTROLLER TestController (Description := "array test")
            \tTAG
            \t\tDataArray : DINT[10] (RADIX := Decimal);
            \t\tMatrix : REAL[5,3] (RADIX := Float);
            \tEND_TAG
            END_CONTROLLER
            """;

        JsonObject result = parser.parseContent(content, "array_test.l5k");

        assertThat(result).isNotNull();
        assertThat(result.has("global_tags")).isTrue();

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags).hasSize(2);
        assertThat(tags.get(0).getAsJsonObject().get("dimensions").getAsString()).isEqualTo("10");
        assertThat(tags.get(1).getAsJsonObject().get("dimensions").getAsString()).isEqualTo("5,3");
    }

    @Test
    @DisplayName("Should expand built-in TIMER type")
    void testExpandTimerType() {
        String content = """
            CONTROLLER TestController (Description := "timer test")
            \tTAG
            \t\tDelayTimer : TIMER := [0,5000,0];
            \tEND_TAG
            END_CONTROLLER
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
            CONTROLLER TestController (Description := "counter test")
            \tTAG
            \t\tProductCounter : COUNTER := [0,0,0];
            \tEND_TAG
            END_CONTROLLER
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
    @DisplayName("DoD FIX-6: an empty file yields no tags and must return null (an honest parse "
        + "failure), not a demo tag structure that masks the upload as successful")
    void testEmptyFile() {
        JsonObject result = parser.parseContent("", "empty.l5k");

        assertThat(result)
            .as("zero recognisable tags is a parse failure, not a demo opportunity")
            .isNull();
    }

    @Test
    @DisplayName("DoD FIX-6: malformed content with no recognisable TAG/PROGRAM sections must "
        + "return null rather than the old 'L5K_ParseError' demo tag - previously this let a "
        + "garbage .l5k upload report HTTP 200 success:true (plc-dod2/item6-verify.txt lineage)")
    void testMalformedContent() {
        String malformed = "This is not valid L5K content at all!";

        JsonObject result = parser.parseContent(malformed, "malformed.l5k");

        assertThat(result)
            .as("garbage content must not be silently replaced with a demo tag")
            .isNull();
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
    @DisplayName("Should parse BIT members in UDTs (host byte + bit position, §2.6)")
    void testParseBitMembers() {
        String content = """
            CONTROLLER TestController (Description := "bit member test")
            \tDATATYPE StatusType (FamilyType := NoFamily)
            \t\tSINT ZZZZZZZZZZStatusType0 (Hidden := 1);
            \t\tBIT Fault_A ZZZZZZZZZZStatusType0 : 0 (Radix := Decimal);
            \t\tBIT Fault_B ZZZZZZZZZZStatusType0 : 1 (Radix := Decimal);
            \t\tDINT Value (Radix := Decimal);
            \tEND_DATATYPE
            \tTAG
            \t\tSystemStatus : StatusType;
            \tEND_TAG
            END_CONTROLLER
            """;

        JsonObject result = parser.parseContent(content, "bit_test.l5k");

        assertThat(result).isNotNull();
        assertThat(result.has("udts")).isTrue();

        JsonObject udt = result.getAsJsonArray("udts").get(0).getAsJsonObject();
        JsonArray members = udt.getAsJsonArray("members");
        assertThat(members).hasSize(3); // Fault_A, Fault_B, Value - hidden host byte dropped
    }

    @Test
    @DisplayName("Should skip ZZZZ padding members")
    void testSkipZZZZMembers() {
        String content = """
            CONTROLLER TestController (Description := "hidden host test")
            \tDATATYPE MyType (FamilyType := NoFamily)
            \t\tDINT RealMember (Radix := Decimal);
            \t\tSINT ZZZZZZZZZZZZZZZZZZ (Hidden := 1);
            \tEND_DATATYPE
            \tTAG
            \t\tT1 : MyType;
            \tEND_TAG
            END_CONTROLLER
            """;

        JsonObject result = parser.parseContent(content, "zzzz_test.l5k");

        assertThat(result).isNotNull();
        assertThat(result.has("udts")).isTrue();
        JsonArray udts = result.getAsJsonArray("udts");
        for (var elem : udts) {
            JsonObject udt = elem.getAsJsonObject();
            if ("MyType".equals(udt.get("name").getAsString())) {
                JsonArray members = udt.getAsJsonArray("members");
                assertThat(members).hasSize(1);
                for (var memberElem : members) {
                    JsonObject member = memberElem.getAsJsonObject();
                    assertThat(member.get("name").getAsString()).doesNotStartWith("ZZZZ");
                }
            }
        }
    }

    @Test
    @DisplayName("Should expand nested UDTs recursively")
    void testNestedUdtExpansion() {
        String content = """
            CONTROLLER TestController (Description := "nested UDT test")
            \tDATATYPE InnerType (FamilyType := NoFamily)
            \t\tDINT Value (Radix := Decimal);
            \t\tBOOL Status (Radix := Decimal);
            \tEND_DATATYPE
            \tDATATYPE OuterType (FamilyType := NoFamily)
            \t\tSTRING Name;
            \t\tInnerType Inner;
            \t\tDINT Count (Radix := Decimal);
            \tEND_DATATYPE
            \tTAG
            \t\tMyOuter : OuterType;
            \tEND_TAG
            END_CONTROLLER
            """;

        JsonObject result = parser.parseContent(content, "nested_udt.l5k");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags).isNotNull();
        assertThat(tags.size()).isGreaterThan(0);

        // Find the MyOuter tag
        JsonObject outerTag = null;
        for (var elem : tags) {
            JsonObject tag = elem.getAsJsonObject();
            if ("MyOuter".equals(tag.get("name").getAsString())) {
                outerTag = tag;
                break;
            }
        }

        assertThat(outerTag).isNotNull();
        assertThat(outerTag.has("udt_members")).isTrue();

        // Find the Inner member which should itself have udt_members
        JsonArray outerMembers = outerTag.getAsJsonArray("udt_members");
        JsonObject innerMember = null;
        for (var elem : outerMembers) {
            JsonObject member = elem.getAsJsonObject();
            if ("Inner".equals(member.get("name").getAsString())) {
                innerMember = member;
                break;
            }
        }

        assertThat(innerMember).isNotNull();
        assertThat(innerMember.get("data_type").getAsString()).isEqualTo("InnerType");
        // The Inner member should have its own udt_members (nested expansion)
        assertThat(innerMember.has("udt_members")).isTrue();

        JsonArray innerMembers = innerMember.getAsJsonArray("udt_members");
        assertThat(innerMembers.size()).isEqualTo(2); // Value and Status
    }

    @Test
    @DisplayName("Should expand deeply nested UDTs (3 levels)")
    void testDeeplyNestedUdtExpansion() {
        String content = """
            CONTROLLER TestController (Description := "deep nesting test")
            \tDATATYPE Level3 (FamilyType := NoFamily)
            \t\tREAL DeepValue (Radix := Float);
            \tEND_DATATYPE
            \tDATATYPE Level2 (FamilyType := NoFamily)
            \t\tDINT MidValue (Radix := Decimal);
            \t\tLevel3 Deep;
            \tEND_DATATYPE
            \tDATATYPE Level1 (FamilyType := NoFamily)
            \t\tBOOL TopValue (Radix := Decimal);
            \t\tLevel2 Mid;
            \tEND_DATATYPE
            \tTAG
            \t\tTopLevel : Level1;
            \tEND_TAG
            END_CONTROLLER
            """;

        JsonObject result = parser.parseContent(content, "deep_nested.l5k");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");

        // Find TopLevel tag
        JsonObject topTag = null;
        for (var elem : tags) {
            JsonObject tag = elem.getAsJsonObject();
            if ("TopLevel".equals(tag.get("name").getAsString())) {
                topTag = tag;
                break;
            }
        }

        assertThat(topTag).isNotNull();
        assertThat(topTag.has("udt_members")).isTrue();

        // Navigate to Level1 -> Mid (Level2) -> Deep (Level3) -> DeepValue
        JsonArray level1Members = topTag.getAsJsonArray("udt_members");
        JsonObject midMember = null;
        for (var elem : level1Members) {
            JsonObject m = elem.getAsJsonObject();
            if ("Mid".equals(m.get("name").getAsString())) {
                midMember = m;
                break;
            }
        }

        assertThat(midMember).isNotNull();
        assertThat(midMember.has("udt_members")).isTrue();

        JsonArray level2Members = midMember.getAsJsonArray("udt_members");
        JsonObject deepMember = null;
        for (var elem : level2Members) {
            JsonObject m = elem.getAsJsonObject();
            if ("Deep".equals(m.get("name").getAsString())) {
                deepMember = m;
                break;
            }
        }

        assertThat(deepMember).isNotNull();
        assertThat(deepMember.has("udt_members")).isTrue();

        JsonArray level3Members = deepMember.getAsJsonArray("udt_members");
        assertThat(level3Members.size()).isEqualTo(1);
        assertThat(level3Members.get(0).getAsJsonObject().get("name").getAsString()).isEqualTo("DeepValue");
    }
}
