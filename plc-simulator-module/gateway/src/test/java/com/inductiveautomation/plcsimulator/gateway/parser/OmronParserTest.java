package com.inductiveautomation.plcsimulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for OmronParser.
 * Tests CX-Programmer and Sysmac Studio format parsing.
 */
class OmronParserTest {

    private OmronParser parser;

    @BeforeEach
    void setUp() {
        parser = new OmronParser();
    }

    @Test
    @DisplayName("Should parse CX-Programmer CSV symbol table")
    void testParseCxProgrammerCsv() {
        String content = """
            SymbolName, Address, DataType, Comment
            StartButton, CIO0.00, BOOL, Main start button
            Motor1Speed, D100, DINT, Motor 1 speed setpoint
            Temperature, D200, REAL, Process temperature
            SystemStatus, W0, WORD, System status word
            """;

        JsonObject result = parser.parseContent(content, "symbols.csv");

        assertThat(result).isNotNull();
        assertThat(result.get("vendor").getAsString()).isEqualTo("omron");
        assertThat(result.has("global_tags")).isTrue();

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isEqualTo(4);

        // Verify first tag
        JsonObject startButton = tags.get(0).getAsJsonObject();
        assertThat(startButton.get("name").getAsString()).isEqualTo("StartButton");
        assertThat(startButton.get("data_type").getAsString()).isEqualTo("BOOL");
        assertThat(startButton.get("address").getAsString()).isEqualTo("CIO0.00");
    }

    @Test
    @DisplayName("Should parse Sysmac Studio CSV format")
    void testParseSysmacCsv() {
        String content = """
            "Name","DataType","Address","InitialValue","Comment"
            "Conveyor_Run","BOOL","","FALSE","Conveyor run command"
            "SetpointTemp","REAL","","25.0","Temperature setpoint"
            "ProductCount","DINT","","0","Total product count"
            """;

        JsonObject result = parser.parseContent(content, "variables.csv");

        assertThat(result).isNotNull();
        assertThat(result.has("global_tags")).isTrue();

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isEqualTo(3);

        JsonObject conveyor = tags.get(0).getAsJsonObject();
        assertThat(conveyor.get("name").getAsString()).isEqualTo("Conveyor_Run");
        assertThat(conveyor.get("data_type").getAsString()).isEqualTo("BOOL");
    }

    @Test
    @DisplayName("Should parse simple CSV with Name,Address format")
    void testParseSimpleCsv() {
        String content = """
            Name,Address
            Pump1,D0
            Pump2,D1
            Valve1,CIO0.00
            Valve2,CIO0.01
            """;

        JsonObject result = parser.parseContent(content, "simple.csv");

        assertThat(result).isNotNull();
        assertThat(result.has("global_tags")).isTrue();

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isEqualTo(4);
    }

    @Test
    @DisplayName("Should infer data type from device address")
    void testInferDataTypeFromAddress() {
        String content = """
            Symbol, Address, Type
            BitVar, CIO0.00, BOOL
            WordVar, D100, WORD
            TimerVar, TIM0, TIME
            """;

        JsonObject result = parser.parseContent(content, "types.csv");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");

        // CIO with bit should be BOOL
        JsonObject bitVar = tags.get(0).getAsJsonObject();
        assertThat(bitVar.get("data_type").getAsString()).isEqualTo("BOOL");
    }

    @Test
    @DisplayName("Should normalize Omron data types")
    void testNormalizeOmronTypes() {
        String content = """
            Name,DataType,Address
            Var1,UINT,D0
            Var2,UDINT,D10
            Var3,LREAL,D20
            Var4,STRING,D30
            """;

        JsonObject result = parser.parseContent(content, "types.csv");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isEqualTo(4);

        // UINT should normalize to INT
        assertThat(tags.get(0).getAsJsonObject().get("data_type").getAsString()).isEqualTo("INT");
        // UDINT should normalize to DINT
        assertThat(tags.get(1).getAsJsonObject().get("data_type").getAsString()).isEqualTo("DINT");
        // LREAL stays LREAL
        assertThat(tags.get(2).getAsJsonObject().get("data_type").getAsString()).isEqualTo("LREAL");
        // STRING stays STRING
        assertThat(tags.get(3).getAsJsonObject().get("data_type").getAsString()).isEqualTo("STRING");
    }

    @Test
    @DisplayName("Should parse Sysmac Studio XML format")
    void testParseSysmacXml() {
        String content = """
            <?xml version="1.0" encoding="UTF-8"?>
            <NXProjectData>
                <Controller Name="NJ501">
                    <Variables>
                        <Variable Name="ProcessTemp" DataType="REAL" Address="D100">
                            <Comment>Process temperature sensor</Comment>
                        </Variable>
                        <Variable Name="MotorRunning" DataType="BOOL">
                            <Comment>Motor running status</Comment>
                        </Variable>
                    </Variables>
                </Controller>
            </NXProjectData>
            """;

        JsonObject result = parser.parseContent(content, "project.smc2");

        assertThat(result).isNotNull();
        assertThat(result.get("vendor").getAsString()).isEqualTo("omron");
        assertThat(result.get("format").getAsString()).isEqualTo("sysmac");
        assertThat(result.get("controller").getAsString()).isEqualTo("NJ501");

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isEqualTo(2);

        JsonObject tempTag = tags.get(0).getAsJsonObject();
        assertThat(tempTag.get("name").getAsString()).isEqualTo("ProcessTemp");
        assertThat(tempTag.get("data_type").getAsString()).isEqualTo("REAL");
    }

    @Test
    @DisplayName("Should handle empty content gracefully")
    void testEmptyContent() {
        JsonObject result = parser.parseContent("", "empty.csv");
        assertThat(result).isNull();

        result = parser.parseContent(null, "null.csv");
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should skip comment lines")
    void testSkipComments() {
        String content = """
            // This is a comment
            ; This is also a comment
            Name,Address,Type
            ValidTag,D0,DINT
            """;

        JsonObject result = parser.parseContent(content, "comments.csv");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isEqualTo(1);
        assertThat(tags.get(0).getAsJsonObject().get("name").getAsString()).isEqualTo("ValidTag");
    }

    @Test
    @DisplayName("Should correctly identify Omron file extensions")
    void testCanHandle() {
        assertThat(parser.canHandle("project.cxp")).isTrue();
        assertThat(parser.canHandle("symbols.opt")).isTrue();
        assertThat(parser.canHandle("project.smc2")).isTrue();
        assertThat(parser.canHandle("project.cxf")).isTrue();
        assertThat(parser.canHandle("omron_tags.csv")).isTrue();
        assertThat(parser.canHandle("omron_export.xml")).isTrue();

        // Should not handle non-Omron files
        assertThat(parser.canHandle("program.l5k")).isFalse();
        assertThat(parser.canHandle("project.json")).isFalse();
        assertThat(parser.canHandle("tags.csv")).isFalse(); // No "omron" in name
        assertThat(parser.canHandle(null)).isFalse();
    }

    @Test
    @DisplayName("Should return correct parser type")
    void testGetParserType() {
        assertThat(parser.getParserType()).isEqualTo("omron");
    }

    @Test
    @DisplayName("Should extract controller name from filename")
    void testExtractControllerName() {
        String content = "Name,Address\nTag1,D0";

        JsonObject result = parser.parseContent(content, "/path/to/MyController.cxp");
        assertThat(result.get("controller").getAsString()).isEqualTo("MyController");

        result = parser.parseContent(content, "ProductionLine.csv");
        assertThat(result.get("controller").getAsString()).isEqualTo("ProductionLine");
    }
}
