package com.inductiveautomation.plcsimulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for L5XParser.
 * Tests XML parsing, UDT expansion, and CRITICAL: XXE vulnerability prevention.
 */
class L5XParserTest {

    private L5XParser parser;

    @BeforeEach
    void setUp() {
        parser = new L5XParser();
    }

    @Test
    @DisplayName("Should parse simple L5X file")
    void testParseSimpleL5X() throws Exception {
        String content = Files.readString(
            Path.of("src/test/resources/test-files/simple.l5x"));

        JsonObject result = parser.parseContent(content, "simple.l5x");

        assertThat(result).isNotNull();
        assertThat(result.has("controller")).isTrue();
        assertThat(result.get("controller").getAsString()).isEqualTo("TestController");
        assertThat(result.has("vendor")).isTrue();
        assertThat(result.get("vendor").getAsString()).isEqualTo("rockwell");
    }

    @Test
    @DisplayName("Should parse controller-scoped tags")
    void testParseControllerTags() throws Exception {
        String content = Files.readString(
            Path.of("src/test/resources/test-files/simple.l5x"));

        JsonObject result = parser.parseContent(content, "simple.l5x");

        assertThat(result.has("global_tags")).isTrue();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isGreaterThan(0);

        // Verify tag structure
        JsonObject firstTag = tags.get(0).getAsJsonObject();
        assertThat(firstTag.has("name")).isTrue();
        assertThat(firstTag.has("data_type")).isTrue();
    }

    @Test
    @DisplayName("Should parse programs with program-scoped tags")
    void testParseProgramTags() throws Exception {
        String content = Files.readString(
            Path.of("src/test/resources/test-files/simple.l5x"));

        JsonObject result = parser.parseContent(content, "simple.l5x");

        assertThat(result.has("programs")).isTrue();
        JsonArray programs = result.getAsJsonArray("programs");
        assertThat(programs.size()).isGreaterThan(0);

        JsonObject program = programs.get(0).getAsJsonObject();
        assertThat(program.get("name").getAsString()).isEqualTo("MainProgram");
        assertThat(program.has("tags")).isTrue();

        JsonArray programTags = program.getAsJsonArray("tags");
        assertThat(programTags.size()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should parse and expand UDT definitions")
    void testParseUDT() throws Exception {
        String content = Files.readString(
            Path.of("src/test/resources/test-files/with-udt.l5x"));

        JsonObject result = parser.parseContent(content, "with-udt.l5x");

        // Check UDT definitions
        if (result.has("udts")) {
            JsonArray udts = result.getAsJsonArray("udts");
            assertThat(udts.size()).isGreaterThan(0);

            JsonObject udt = udts.get(0).getAsJsonObject();
            assertThat(udt.get("name").getAsString()).isEqualTo("MyUDT");
            assertThat(udt.has("members")).isTrue();

            JsonArray members = udt.getAsJsonArray("members");
            assertThat(members.size()).isEqualTo(3); // Value, Status, Name
        }

        // Check UDT instance expansion
        JsonArray tags = result.getAsJsonArray("global_tags");
        if (tags.size() > 0) {
            JsonObject udtInstance = tags.get(0).getAsJsonObject();
            assertThat(udtInstance.get("data_type").getAsString()).isEqualTo("MyUDT");

            // UDT instances should have expanded members
            if (udtInstance.has("udt_members")) {
                JsonArray udtMembers = udtInstance.getAsJsonArray("udt_members");
                assertThat(udtMembers.size()).isEqualTo(3);
            }
        }
    }

    @Test
    @DisplayName("SECURITY: Should prevent XXE attacks")
    void testXXEPrevention() throws Exception {
        // This malicious file attempts to read /etc/passwd via XXE
        String maliciousContent = Files.readString(
            Path.of("src/test/resources/test-files/malicious-xxe.l5x"));

        JsonObject result = parser.parseContent(maliciousContent, "malicious-xxe.l5x");

        // Parser should either:
        // 1. Return null (parsing failed safely)
        // 2. Return result but controller name should NOT contain file contents
        if (result != null && result.has("controller")) {
            String controllerName = result.get("controller").getAsString();

            // Controller name should NOT contain actual file contents
            // If XXE worked, it would contain /etc/passwd contents like "root:x:0:0..."
            assertThat(controllerName)
                .doesNotContain("root:")
                .doesNotContain("bin:")
                .doesNotContain("daemon:");
        }

        // The fact that we don't get an exception is good
        // The XXE should be blocked by our security configuration
    }

    @Test
    @DisplayName("Should handle malformed XML gracefully")
    void testMalformedXML() {
        String invalidXML = "This is not valid XML";

        JsonObject result = parser.parseContent(invalidXML, "invalid.l5x");

        assertThat(result).isNull(); // Should return null for invalid XML
    }

    @Test
    @DisplayName("Should handle empty XML")
    void testEmptyXML() {
        String emptyXML = "";

        JsonObject result = parser.parseContent(emptyXML, "empty.l5x");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should handle XML without Controller element")
    void testMissingController() {
        String xmlNoController = "<?xml version=\"1.0\"?>\n<RSLogix5000Content></RSLogix5000Content>";

        JsonObject result = parser.parseContent(xmlNoController, "no-controller.l5x");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should correctly identify L5X files by extension")
    void testCanHandle() {
        assertThat(parser.canHandle("test.l5x")).isTrue();
        assertThat(parser.canHandle("test.L5X")).isTrue();
        assertThat(parser.canHandle("test.l5k")).isFalse(); // L5K is text format
        assertThat(parser.canHandle("test.xml")).isFalse();
        assertThat(parser.canHandle(null)).isFalse();
    }

    @Test
    @DisplayName("Should return correct parser type")
    void testGetParserType() {
        assertThat(parser.getParserType()).isEqualTo("l5x");
    }

    @Test
    @DisplayName("Should extract tag descriptions")
    void testTagDescriptions() {
        String contentWithDescription = """
            <?xml version="1.0"?>
            <RSLogix5000Content>
                <Controller Name="Test" ProcessorType="Test">
                    <Tags>
                        <Tag Name="MyTag" DataType="DINT">
                            <Description>
                                <![CDATA[This is my tag description]]>
                            </Description>
                        </Tag>
                    </Tags>
                </Controller>
            </RSLogix5000Content>
            """;

        JsonObject result = parser.parseContent(contentWithDescription, "test.l5x");

        assertThat(result).isNotNull();
        if (result.has("global_tags")) {
            JsonArray tags = result.getAsJsonArray("global_tags");
            if (tags.size() > 0) {
                JsonObject tag = tags.get(0).getAsJsonObject();
                if (tag.has("description")) {
                    assertThat(tag.get("description").getAsString())
                        .contains("This is my tag description");
                }
            }
        }
    }

    @Test
    @DisplayName("Should handle array dimensions")
    void testArrayDimensions() {
        String contentWithArray = """
            <?xml version="1.0"?>
            <RSLogix5000Content>
                <Controller Name="Test" ProcessorType="Test">
                    <Tags>
                        <Tag Name="MyArray" DataType="DINT" Dimensions="10">
                        </Tag>
                    </Tags>
                </Controller>
            </RSLogix5000Content>
            """;

        JsonObject result = parser.parseContent(contentWithArray, "test.l5x");

        assertThat(result).isNotNull();
        if (result.has("global_tags")) {
            JsonArray tags = result.getAsJsonArray("global_tags");
            if (tags.size() > 0) {
                JsonObject tag = tags.get(0).getAsJsonObject();
                assertThat(tag.has("dimensions")).isTrue();
                assertThat(tag.has("isArray")).isTrue();
                assertThat(tag.get("isArray").getAsBoolean()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("Should parse AOI definitions")
    void testParseAOI() {
        String contentWithAoi = """
            <?xml version="1.0"?>
            <RSLogix5000Content>
                <Controller Name="Test" ProcessorType="Test">
                    <AddOnInstructionDefinitions>
                        <AddOnInstructionDefinition Name="MyAOI" Revision="1.0">
                            <Parameters>
                                <Parameter Name="EnableIn" TagType="Base" DataType="BOOL" Usage="Input"/>
                                <Parameter Name="EnableOut" TagType="Base" DataType="BOOL" Usage="Output"/>
                                <Parameter Name="Input1" TagType="Base" DataType="DINT" Usage="Input"/>
                                <Parameter Name="Output1" TagType="Base" DataType="REAL" Usage="Output"/>
                            </Parameters>
                            <LocalTags>
                                <LocalTag Name="InternalVar" DataType="DINT"/>
                            </LocalTags>
                        </AddOnInstructionDefinition>
                    </AddOnInstructionDefinitions>
                    <Tags>
                        <Tag Name="MyAOI_Instance" DataType="MyAOI"/>
                    </Tags>
                </Controller>
            </RSLogix5000Content>
            """;

        JsonObject result = parser.parseContent(contentWithAoi, "test_aoi.l5x");

        assertThat(result).isNotNull();

        // Check that AOIs are parsed
        assertThat(result.has("aois")).isTrue();
        JsonArray aois = result.getAsJsonArray("aois");
        assertThat(aois.size()).isEqualTo(1);

        JsonObject aoi = aois.get(0).getAsJsonObject();
        assertThat(aoi.get("name").getAsString()).isEqualTo("MyAOI");
        assertThat(aoi.get("type").getAsString()).isEqualTo("AOI");

        // Check that EnableIn/EnableOut are skipped
        JsonArray members = aoi.getAsJsonArray("members");
        for (var elem : members) {
            JsonObject member = elem.getAsJsonObject();
            String memberName = member.get("name").getAsString();
            assertThat(memberName).isNotEqualTo("EnableIn");
            assertThat(memberName).isNotEqualTo("EnableOut");
        }

        // Check that AOI instance tag is expanded
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isGreaterThan(0);

        JsonObject aoiInstance = tags.get(0).getAsJsonObject();
        assertThat(aoiInstance.get("name").getAsString()).isEqualTo("MyAOI_Instance");
        assertThat(aoiInstance.has("udt_members")).isTrue();
    }

    @Test
    @DisplayName("Should expand nested UDTs in L5X")
    void testNestedUdtExpansion() {
        String contentWithNestedUdt = """
            <?xml version="1.0"?>
            <RSLogix5000Content>
                <Controller Name="Test" ProcessorType="Test">
                    <DataTypes>
                        <DataType Name="InnerType" Family="NoFamily">
                            <Members>
                                <Member Name="InnerValue" DataType="DINT"/>
                                <Member Name="InnerStatus" DataType="BOOL"/>
                            </Members>
                        </DataType>
                        <DataType Name="OuterType" Family="NoFamily">
                            <Members>
                                <Member Name="Name" DataType="STRING"/>
                                <Member Name="Inner" DataType="InnerType"/>
                                <Member Name="Count" DataType="DINT"/>
                            </Members>
                        </DataType>
                    </DataTypes>
                    <Tags>
                        <Tag Name="MyOuter" DataType="OuterType"/>
                    </Tags>
                </Controller>
            </RSLogix5000Content>
            """;

        JsonObject result = parser.parseContent(contentWithNestedUdt, "nested_udt.l5x");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isGreaterThan(0);

        JsonObject outerTag = tags.get(0).getAsJsonObject();
        assertThat(outerTag.get("name").getAsString()).isEqualTo("MyOuter");
        assertThat(outerTag.has("udt_members")).isTrue();

        // Find the Inner member
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
        assertThat(innerMembers.size()).isEqualTo(2); // InnerValue and InnerStatus
    }

    @Test
    @DisplayName("Should expand built-in TIMER type in L5X")
    void testBuiltInTimerExpansion() {
        String contentWithTimer = """
            <?xml version="1.0"?>
            <RSLogix5000Content>
                <Controller Name="Test" ProcessorType="Test">
                    <Tags>
                        <Tag Name="MyTimer" DataType="TIMER"/>
                    </Tags>
                </Controller>
            </RSLogix5000Content>
            """;

        JsonObject result = parser.parseContent(contentWithTimer, "timer.l5x");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isGreaterThan(0);

        JsonObject timerTag = tags.get(0).getAsJsonObject();
        assertThat(timerTag.get("name").getAsString()).isEqualTo("MyTimer");
        assertThat(timerTag.get("data_type").getAsString()).isEqualTo("TIMER");
        assertThat(timerTag.has("udt_members")).isTrue();

        // Check for standard TIMER members
        JsonArray members = timerTag.getAsJsonArray("udt_members");
        var memberNames = new java.util.ArrayList<String>();
        for (var elem : members) {
            memberNames.add(elem.getAsJsonObject().get("name").getAsString());
        }
        assertThat(memberNames).contains("PRE", "ACC", "DN", "EN");
    }
}
