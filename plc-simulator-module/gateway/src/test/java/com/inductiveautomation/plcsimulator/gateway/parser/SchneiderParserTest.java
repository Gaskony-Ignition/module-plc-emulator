package com.inductiveautomation.plcsimulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Schneider Electric Unity Pro / EcoStruxure parser.
 */
public class SchneiderParserTest {

    private final SchneiderParser parser = new SchneiderParser();

    @Test
    public void shouldParseUnityProXML() {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Project xmlns="http://www.schneider-electric.com/UnityPro">
              <Variables>
                <Variable Name="TankLevel" Type="INT" Address="%MW100" Comment="Main tank level sensor" />
                <Variable Name="PumpRunning" Type="BOOL" Address="%M10" Comment="Pump status" />
                <Variable Name="Temperature" Type="REAL" Comment="Process temperature" />
              </Variables>
            </Project>
            """;

        JsonObject result = parser.parseContent(xml, "unity_pro.xml");

        assertNotNull(result, "Parser should return a result");
        assertEquals("schneider", result.get("vendor").getAsString());
        assertTrue(result.has("global_tags"));

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(3, tags.size(), "Should parse 3 variables");

        // Check first variable (TankLevel)
        JsonObject tankLevel = tags.get(0).getAsJsonObject();
        assertEquals("TankLevel", tankLevel.get("name").getAsString());
        assertEquals("INT", tankLevel.get("dataType").getAsString());
        assertEquals("%MW100", tankLevel.get("address").getAsString());
        assertEquals("Main tank level sensor", tankLevel.get("description").getAsString());

        // Check second variable (PumpRunning)
        JsonObject pumpRunning = tags.get(1).getAsJsonObject();
        assertEquals("PumpRunning", pumpRunning.get("name").getAsString());
        assertEquals("BOOL", pumpRunning.get("dataType").getAsString());
        assertEquals("%M10", pumpRunning.get("address").getAsString());

        // Check third variable (Temperature - no address)
        JsonObject temperature = tags.get(2).getAsJsonObject();
        assertEquals("Temperature", temperature.get("name").getAsString());
        assertEquals("REAL", temperature.get("dataType").getAsString());
        assertFalse(temperature.has("address"));
    }

    @Test
    public void shouldParseCSVFormat() {
        String csv = """
            Name,Type,Address,InitialValue,Comment
            MotorSpeed,INT,%MW200,1500,Motor speed setpoint
            EmergencyStop,BOOL,%I0.0,FALSE,Emergency stop button
            ProcessValue,REAL,%MW300,25.5,Current process value
            """;

        JsonObject result = parser.parseContent(csv, "schneider_variables.csv");

        assertNotNull(result);
        assertEquals("schneider", result.get("vendor").getAsString());

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(3, tags.size());

        // Check MotorSpeed
        JsonObject motorSpeed = tags.get(0).getAsJsonObject();
        assertEquals("MotorSpeed", motorSpeed.get("name").getAsString());
        assertEquals("INT", motorSpeed.get("dataType").getAsString());
        assertEquals("%MW200", motorSpeed.get("address").getAsString());
        assertEquals(1500, motorSpeed.get("value").getAsInt());
        assertEquals("Motor speed setpoint", motorSpeed.get("description").getAsString());

        // Check EmergencyStop
        JsonObject emergencyStop = tags.get(1).getAsJsonObject();
        assertEquals("EmergencyStop", emergencyStop.get("name").getAsString());
        assertEquals("BOOL", emergencyStop.get("dataType").getAsString());
        assertFalse(emergencyStop.get("value").getAsBoolean());

        // Check ProcessValue
        JsonObject processValue = tags.get(2).getAsJsonObject();
        assertEquals("ProcessValue", processValue.get("name").getAsString());
        assertEquals("REAL", processValue.get("dataType").getAsString());
        assertEquals(25.5, processValue.get("value").getAsDouble(), 0.01);
    }

    @Test
    public void shouldHandleCSVWithQuotes() {
        String csv = """
            Name,Type,Address,InitialValue,Comment
            "Variable With Spaces",INT,%MW100,0,"This is a comment, with commas"
            SimpleVar,BOOL,%M1,TRUE,Simple comment
            """;

        JsonObject result = parser.parseContent(csv, "quoted.csv");

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(2, tags.size());

        JsonObject firstTag = tags.get(0).getAsJsonObject();
        assertEquals("Variable With Spaces", firstTag.get("name").getAsString());
        assertEquals("This is a comment, with commas", firstTag.get("description").getAsString());
    }

    @Test
    public void shouldConvertSchneiderDataTypes() {
        String xml = """
            <?xml version="1.0"?>
            <Project>
              <Variables>
                <Variable Name="BoolVar" Type="BOOL" />
                <Variable Name="ByteVar" Type="BYTE" />
                <Variable Name="WordVar" Type="WORD" />
                <Variable Name="DWordVar" Type="DWORD" />
                <Variable Name="IntVar" Type="INT" />
                <Variable Name="DIntVar" Type="DINT" />
                <Variable Name="RealVar" Type="REAL" />
                <Variable Name="StringVar" Type="STRING" />
                <Variable Name="TimeVar" Type="TIME" />
              </Variables>
            </Project>
            """;

        JsonObject result = parser.parseContent(xml, "datatypes.xml");
        JsonArray tags = result.getAsJsonArray("global_tags");

        assertEquals("BOOL", tags.get(0).getAsJsonObject().get("dataType").getAsString());
        assertEquals("SINT", tags.get(1).getAsJsonObject().get("dataType").getAsString());
        assertEquals("INT", tags.get(2).getAsJsonObject().get("dataType").getAsString());
        assertEquals("DINT", tags.get(3).getAsJsonObject().get("dataType").getAsString());
        assertEquals("INT", tags.get(4).getAsJsonObject().get("dataType").getAsString());
        assertEquals("DINT", tags.get(5).getAsJsonObject().get("dataType").getAsString());
        assertEquals("REAL", tags.get(6).getAsJsonObject().get("dataType").getAsString());
        assertEquals("STRING", tags.get(7).getAsJsonObject().get("dataType").getAsString());
        assertEquals("DINT", tags.get(8).getAsJsonObject().get("dataType").getAsString()); // TIME as DINT
    }

    @Test
    public void shouldParseDerivedDataTypes() {
        String xml = """
            <?xml version="1.0"?>
            <Project>
              <DataTypes>
                <DDTType Name="MotorData">
                  <Member Name="Speed" Type="INT" />
                  <Member Name="Current" Type="REAL" />
                  <Member Name="Running" Type="BOOL" />
                </DDTType>
              </DataTypes>
            </Project>
            """;

        JsonObject result = parser.parseContent(xml, "ddt.xml");

        assertTrue(result.has("udts"));
        JsonArray udts = result.getAsJsonArray("udts");
        assertEquals(1, udts.size());

        JsonObject motorData = udts.get(0).getAsJsonObject();
        assertEquals("MotorData", motorData.get("name").getAsString());

        JsonArray members = motorData.getAsJsonArray("members");
        assertEquals(3, members.size());

        assertEquals("Speed", members.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("INT", members.get(0).getAsJsonObject().get("dataType").getAsString());

        assertEquals("Current", members.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("REAL", members.get(1).getAsJsonObject().get("dataType").getAsString());

        assertEquals("Running", members.get(2).getAsJsonObject().get("name").getAsString());
        assertEquals("BOOL", members.get(2).getAsJsonObject().get("dataType").getAsString());
    }

    @Test
    public void shouldSkipCSVHeaderRow() {
        String csv = """
            Name,Type,Address,InitialValue,Comment
            Variable1,INT,%MW100,0,Test variable
            """;

        JsonObject result = parser.parseContent(csv, "with_header.csv");

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(1, tags.size(), "Should skip header row");
        assertEquals("Variable1", tags.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    public void shouldIdentifySchneiderFiles() {
        assertTrue(parser.canHandle("schneider_project.xml"));
        assertTrue(parser.canHandle("unity_pro_export.xml"));
        assertTrue(parser.canHandle("m340_variables.xml"));
        assertTrue(parser.canHandle("schneider_m580_tags.csv")); // Must contain "schneider" or "unity"
        assertTrue(parser.canHandle("unity_vars.csv"));
        assertTrue(parser.canHandle("project.xef"));
        assertFalse(parser.canHandle("rockwell_file.l5x"));
        assertFalse(parser.canHandle("generic.csv")); // Generic CSV won't match
    }

    @Test
    public void shouldReturnCorrectParserType() {
        assertEquals("schneider", parser.getParserType());
    }

    @Test
    public void shouldHandleEmptyXML() {
        String xml = """
            <?xml version="1.0"?>
            <Project>
              <Variables>
              </Variables>
            </Project>
            """;

        JsonObject result = parser.parseContent(xml, "empty.xml");

        assertNotNull(result);
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(0, tags.size());
    }

    @Test
    public void shouldHandleEmptyCSV() {
        String csv = "Name,Type,Address,InitialValue,Comment\n";

        JsonObject result = parser.parseContent(csv, "empty.csv");

        assertNotNull(result);
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(0, tags.size());
    }

    @Test
    public void shouldHandleMalformedXML() {
        String xml = "Not valid XML content";

        JsonObject result = parser.parseContent(xml, "invalid.xml");

        assertNull(result, "Parser should return null for malformed XML");
    }
}
