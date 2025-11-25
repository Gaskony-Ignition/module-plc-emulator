package com.inductiveautomation.plcsimulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Mitsubishi Electric GX Works 2/3 parser.
 */
public class MitsubishiParserTest {

    private final MitsubishiParser parser = new MitsubishiParser();

    @Test
    public void shouldParseGXWorksCSVExport() {
        String csv = """
            Device,Type,Comment
            D100,INT,Temperature sensor value
            D200,DINT,Production counter
            M10,BOOL,Motor running status
            """;

        JsonObject result = parser.parseContent(csv, "mitsubishi_gx.csv");

        assertNotNull(result, "Parser should return a result");
        assertEquals("mitsubishi", result.get("vendor").getAsString());
        assertTrue(result.has("global_tags"));

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(3, tags.size(), "Should parse 3 variables");

        // Check D100 - Temperature
        JsonObject d100 = tags.get(0).getAsJsonObject();
        assertEquals("D100", d100.get("name").getAsString());
        assertEquals("INT", d100.get("dataType").getAsString());
        assertEquals("D100", d100.get("address").getAsString());
        assertEquals("Temperature sensor value", d100.get("description").getAsString());

        // Check D200 - Counter
        JsonObject d200 = tags.get(1).getAsJsonObject();
        assertEquals("D200", d200.get("name").getAsString());
        assertEquals("DINT", d200.get("dataType").getAsString());

        // Check M10 - Motor status
        JsonObject m10 = tags.get(2).getAsJsonObject();
        assertEquals("M10", m10.get("name").getAsString());
        assertEquals("BOOL", m10.get("dataType").getAsString());
    }

    @Test
    public void shouldParseCSVWithNameColumn() {
        String csv = """
            Device,Name,Type,InitialValue,Comment
            D100,TempSensor,INT,0,Temperature sensor
            M0,MotorRun,BOOL,FALSE,Motor running
            D200,SpeedSetpoint,REAL,50.5,Speed setpoint
            """;

        JsonObject result = parser.parseContent(csv, "gx_works_export.csv");

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(3, tags.size());

        // Check that name column is used
        JsonObject tag1 = tags.get(0).getAsJsonObject();
        assertEquals("TempSensor", tag1.get("name").getAsString());
        assertEquals("D100", tag1.get("address").getAsString());
        assertEquals("INT", tag1.get("dataType").getAsString());

        JsonObject tag2 = tags.get(1).getAsJsonObject();
        assertEquals("MotorRun", tag2.get("name").getAsString());
        assertEquals("BOOL", tag2.get("dataType").getAsString());
        assertFalse(tag2.get("value").getAsBoolean());

        JsonObject tag3 = tags.get(2).getAsJsonObject();
        assertEquals("SpeedSetpoint", tag3.get("name").getAsString());
        assertEquals("REAL", tag3.get("dataType").getAsString());
        assertEquals(50.5, tag3.get("value").getAsDouble(), 0.01);
    }

    @Test
    public void shouldInferDataTypeFromDeviceCode() {
        String csv = """
            Device
            M0
            X10
            Y20
            D100
            T0
            C5
            """;

        JsonObject result = parser.parseContent(csv, "device_types.csv");

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(6, tags.size());

        // M = Internal relay (bit)
        assertEquals("BOOL", tags.get(0).getAsJsonObject().get("dataType").getAsString());

        // X = Input (bit)
        assertEquals("BOOL", tags.get(1).getAsJsonObject().get("dataType").getAsString());

        // Y = Output (bit)
        assertEquals("BOOL", tags.get(2).getAsJsonObject().get("dataType").getAsString());

        // D = Data register (16-bit word)
        assertEquals("INT", tags.get(3).getAsJsonObject().get("dataType").getAsString());

        // T = Timer (32-bit)
        assertEquals("DINT", tags.get(4).getAsJsonObject().get("dataType").getAsString());

        // C = Counter (32-bit)
        assertEquals("DINT", tags.get(5).getAsJsonObject().get("dataType").getAsString());
    }

    @Test
    public void shouldConvertMitsubishiDataTypes() {
        String csv = """
            Device,Type
            D0,BIT
            D1,BYTE
            D2,WORD
            D3,DWORD
            D4,INT
            D5,DINT
            D6,REAL
            D7,LREAL
            D8,STRING
            """;

        JsonObject result = parser.parseContent(csv, "datatypes.csv");
        JsonArray tags = result.getAsJsonArray("global_tags");

        assertEquals("BOOL", tags.get(0).getAsJsonObject().get("dataType").getAsString());
        assertEquals("SINT", tags.get(1).getAsJsonObject().get("dataType").getAsString());
        assertEquals("INT", tags.get(2).getAsJsonObject().get("dataType").getAsString());
        assertEquals("DINT", tags.get(3).getAsJsonObject().get("dataType").getAsString());
        assertEquals("INT", tags.get(4).getAsJsonObject().get("dataType").getAsString());
        assertEquals("DINT", tags.get(5).getAsJsonObject().get("dataType").getAsString());
        assertEquals("REAL", tags.get(6).getAsJsonObject().get("dataType").getAsString());
        assertEquals("LREAL", tags.get(7).getAsJsonObject().get("dataType").getAsString());
        assertEquals("STRING", tags.get(8).getAsJsonObject().get("dataType").getAsString());
    }

    @Test
    public void shouldHandleQuotedCSVFields() {
        String csv = """
            Device,Name,Type,Comment
            "D100","Temp Sensor","INT","This is a comment, with commas"
            M0,SimpleVar,BOOL,Simple comment
            """;

        JsonObject result = parser.parseContent(csv, "quoted.csv");

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(2, tags.size());

        JsonObject firstTag = tags.get(0).getAsJsonObject();
        assertEquals("Temp Sensor", firstTag.get("name").getAsString());
        assertEquals("This is a comment, with commas", firstTag.get("description").getAsString());
    }

    @Test
    public void shouldHandleHexInitialValues() {
        String csv = """
            Device,Type,InitialValue
            D100,INT,H64
            D200,DINT,H1234
            """;

        JsonObject result = parser.parseContent(csv, "hex_values.csv");

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(2, tags.size());

        // H64 = 100 decimal
        assertEquals(100, tags.get(0).getAsJsonObject().get("value").getAsInt());

        // H1234 = 4660 decimal
        assertEquals(4660, tags.get(1).getAsJsonObject().get("value").getAsInt());
    }

    @Test
    public void shouldSkipCSVHeaderRow() {
        String csv = """
            Device,Type,Comment
            D100,INT,Test variable
            """;

        JsonObject result = parser.parseContent(csv, "with_header.csv");

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(1, tags.size(), "Should skip header row");
        assertEquals("D100", tags.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    public void shouldIdentifyMitsubishiFiles() {
        assertTrue(parser.canHandle("mitsubishi_project.csv"));
        assertTrue(parser.canHandle("gx_works_export.csv"));
        assertTrue(parser.canHandle("melsec_vars.csv"));
        assertTrue(parser.canHandle("project.gxw"));
        assertTrue(parser.canHandle("program.gpj"));
        assertTrue(parser.canHandle("application.gpa"));
        assertFalse(parser.canHandle("rockwell_file.l5x"));
        assertFalse(parser.canHandle("generic.csv"));
    }

    @Test
    public void shouldReturnCorrectParserType() {
        assertEquals("mitsubishi", parser.getParserType());
    }

    @Test
    public void shouldHandleEmptyCSV() {
        String csv = "Device,Type,Comment\n";

        JsonObject result = parser.parseContent(csv, "empty.csv");

        assertNotNull(result);
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(0, tags.size());
    }

    @Test
    public void shouldHandleNonCSVFormat() {
        String nonCsv = "<?xml version=\"1.0\"?><Project></Project>";

        JsonObject result = parser.parseContent(nonCsv, "invalid.xml");

        assertNull(result, "Parser should return null for non-CSV format");
    }
}
