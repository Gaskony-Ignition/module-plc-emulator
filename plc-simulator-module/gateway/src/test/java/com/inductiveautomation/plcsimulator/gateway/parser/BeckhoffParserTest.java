package com.inductiveautomation.plcsimulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Beckhoff TwinCAT parser.
 */
public class BeckhoffParserTest {

    private final BeckhoffParser parser = new BeckhoffParser();

    @Test
    public void shouldParseTwinCAT3GlobalVariableList() {
        String xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <TcPlcObject Version="1.1.0.1">
              <GVL>
                <Declaration><![CDATA[VAR_GLOBAL
    Temperature : REAL := 25.5;
    Pressure : INT := 100;
    SystemRunning : BOOL := FALSE;
    Counter : DINT;
END_VAR]]></Declaration>
              </GVL>
            </TcPlcObject>
            """;

        JsonObject result = parser.parseContent(xml, "twincat_gvl.xml");

        assertNotNull(result, "Parser should return a result");
        assertEquals("beckhoff", result.get("vendor").getAsString());
        assertTrue(result.has("global_tags"));

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(4, tags.size(), "Should parse 4 variables");

        // Check Temperature
        JsonObject temperature = tags.get(0).getAsJsonObject();
        assertEquals("Temperature", temperature.get("name").getAsString());
        assertEquals("REAL", temperature.get("dataType").getAsString());
        assertEquals(25.5, temperature.get("value").getAsDouble(), 0.01);

        // Check Pressure
        JsonObject pressure = tags.get(1).getAsJsonObject();
        assertEquals("Pressure", pressure.get("name").getAsString());
        assertEquals("INT", pressure.get("dataType").getAsString());
        assertEquals(100, pressure.get("value").getAsInt());

        // Check SystemRunning
        JsonObject running = tags.get(2).getAsJsonObject();
        assertEquals("SystemRunning", running.get("name").getAsString());
        assertEquals("BOOL", running.get("dataType").getAsString());
        assertFalse(running.get("value").getAsBoolean());

        // Check Counter (no initial value)
        JsonObject counter = tags.get(3).getAsJsonObject();
        assertEquals("Counter", counter.get("name").getAsString());
        assertEquals("DINT", counter.get("dataType").getAsString());
    }

    @Test
    public void shouldParseProgramOrganizationUnit() {
        String xml = """
            <?xml version="1.0"?>
            <TcPlcObject>
              <POU>
                <Declaration><![CDATA[VAR
    Speed : INT;
    Position : DINT := 1000;
    Enabled : BOOL := TRUE;
END_VAR]]></Declaration>
              </POU>
            </TcPlcObject>
            """;

        JsonObject result = parser.parseContent(xml, "pou.xml");

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(3, tags.size());

        assertEquals("Speed", tags.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("INT", tags.get(0).getAsJsonObject().get("dataType").getAsString());

        assertEquals("Position", tags.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("DINT", tags.get(1).getAsJsonObject().get("dataType").getAsString());
        assertEquals(1000, tags.get(1).getAsJsonObject().get("value").getAsInt());

        assertEquals("Enabled", tags.get(2).getAsJsonObject().get("name").getAsString());
        assertEquals("BOOL", tags.get(2).getAsJsonObject().get("dataType").getAsString());
        assertTrue(tags.get(2).getAsJsonObject().get("value").getAsBoolean());
    }

    @Test
    public void shouldParseDataUnitType() {
        String xml = """
            <?xml version="1.0"?>
            <TcPlcObject>
              <DUT Name="MotorControl">
                <Declaration><![CDATA[TYPE MotorControl :
STRUCT
    Speed : INT;
    Current : REAL;
    Running : BOOL;
    Position : DINT;
END_STRUCT
END_TYPE]]></Declaration>
              </DUT>
            </TcPlcObject>
            """;

        JsonObject result = parser.parseContent(xml, "dut.xml");

        assertTrue(result.has("udts"));
        JsonArray udts = result.getAsJsonArray("udts");
        assertEquals(1, udts.size());

        JsonObject motorControl = udts.get(0).getAsJsonObject();
        assertEquals("MotorControl", motorControl.get("name").getAsString());

        JsonArray members = motorControl.getAsJsonArray("members");
        assertEquals(4, members.size());

        assertEquals("Speed", members.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("INT", members.get(0).getAsJsonObject().get("dataType").getAsString());

        assertEquals("Current", members.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("REAL", members.get(1).getAsJsonObject().get("dataType").getAsString());

        assertEquals("Running", members.get(2).getAsJsonObject().get("name").getAsString());
        assertEquals("BOOL", members.get(2).getAsJsonObject().get("dataType").getAsString());

        assertEquals("Position", members.get(3).getAsJsonObject().get("name").getAsString());
        assertEquals("DINT", members.get(3).getAsJsonObject().get("dataType").getAsString());
    }

    @Test
    public void shouldConvertBeckhoffDataTypes() {
        String xml = """
            <?xml version="1.0"?>
            <TcPlcObject>
              <GVL>
                <Declaration><![CDATA[
VAR_GLOBAL
    BoolVar : BOOL;
    ByteVar : BYTE;
    WordVar : WORD;
    DWordVar : DWORD;
    SIntVar : SINT;
    IntVar : INT;
    DIntVar : DINT;
    LIntVar : LINT;
    RealVar : REAL;
    LRealVar : LREAL;
    StringVar : STRING;
    TimeVar : TIME;
END_VAR
]]></Declaration>
              </GVL>
            </TcPlcObject>
            """;

        JsonObject result = parser.parseContent(xml, "datatypes.xml");
        JsonArray tags = result.getAsJsonArray("global_tags");

        assertEquals("BOOL", tags.get(0).getAsJsonObject().get("dataType").getAsString());
        assertEquals("SINT", tags.get(1).getAsJsonObject().get("dataType").getAsString());
        assertEquals("INT", tags.get(2).getAsJsonObject().get("dataType").getAsString());
        assertEquals("DINT", tags.get(3).getAsJsonObject().get("dataType").getAsString());
        assertEquals("SINT", tags.get(4).getAsJsonObject().get("dataType").getAsString());
        assertEquals("INT", tags.get(5).getAsJsonObject().get("dataType").getAsString());
        assertEquals("DINT", tags.get(6).getAsJsonObject().get("dataType").getAsString());
        assertEquals("LINT", tags.get(7).getAsJsonObject().get("dataType").getAsString());
        assertEquals("REAL", tags.get(8).getAsJsonObject().get("dataType").getAsString());
        assertEquals("LREAL", tags.get(9).getAsJsonObject().get("dataType").getAsString());
        assertEquals("STRING", tags.get(10).getAsJsonObject().get("dataType").getAsString());
        assertEquals("DINT", tags.get(11).getAsJsonObject().get("dataType").getAsString()); // TIME as DINT
    }

    @Test
    public void shouldHandlePersistentAndRetainKeywords() {
        String xml = """
            <?xml version="1.0"?>
            <TcPlcObject>
              <GVL>
                <Declaration><![CDATA[VAR_GLOBAL
    PERSISTENT NormalVar : INT := 100;
    RETAIN RetainVar : BOOL := TRUE;
    CONSTANT ConstVar : DINT := 999;
END_VAR]]></Declaration>
              </GVL>
            </TcPlcObject>
            """;

        JsonObject result = parser.parseContent(xml, "persistent.xml");
        JsonArray tags = result.getAsJsonArray("global_tags");

        assertEquals(3, tags.size());

        assertEquals("NormalVar", tags.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(100, tags.get(0).getAsJsonObject().get("value").getAsInt());

        assertEquals("RetainVar", tags.get(1).getAsJsonObject().get("name").getAsString());
        assertTrue(tags.get(1).getAsJsonObject().get("value").getAsBoolean());

        assertEquals("ConstVar", tags.get(2).getAsJsonObject().get("name").getAsString());
        assertEquals(999, tags.get(2).getAsJsonObject().get("value").getAsInt());
    }

    @Test
    public void shouldHandleComments() {
        String xml = """
            <?xml version="1.0"?>
            <TcPlcObject>
              <GVL>
                <Declaration><![CDATA[VAR_GLOBAL
    // This is a comment
    Variable1 : INT;
    (* This is a
       multi-line comment *)
    Variable2 : BOOL;
END_VAR]]></Declaration>
              </GVL>
            </TcPlcObject>
            """;

        JsonObject result = parser.parseContent(xml, "comments.xml");
        JsonArray tags = result.getAsJsonArray("global_tags");

        assertEquals(2, tags.size(), "Should skip comment lines");
        assertEquals("Variable1", tags.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("Variable2", tags.get(1).getAsJsonObject().get("name").getAsString());
    }

    @Test
    public void shouldParseTwinCAT2Format() {
        String xml = """
            <?xml version="1.0"?>
            <Project>
              <Variables>
                <Variable Name="Speed" Type="INT" Comment="Motor speed" />
                <Variable Name="Running" Type="BOOL" Comment="System running" />
                <Variable Name="Temperature" Type="REAL" />
              </Variables>
            </Project>
            """;

        JsonObject result = parser.parseContent(xml, "twincat2.xml");

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(3, tags.size());

        JsonObject speed = tags.get(0).getAsJsonObject();
        assertEquals("Speed", speed.get("name").getAsString());
        assertEquals("INT", speed.get("dataType").getAsString());
        assertEquals("Motor speed", speed.get("description").getAsString());

        JsonObject running = tags.get(1).getAsJsonObject();
        assertEquals("Running", running.get("name").getAsString());
        assertEquals("BOOL", running.get("dataType").getAsString());
        assertEquals("System running", running.get("description").getAsString());

        JsonObject temperature = tags.get(2).getAsJsonObject();
        assertEquals("Temperature", temperature.get("name").getAsString());
        assertEquals("REAL", temperature.get("dataType").getAsString());
    }

    @Test
    public void shouldHandleStringInitialValues() {
        String xml = """
            <?xml version="1.0"?>
            <TcPlcObject>
              <GVL>
                <Declaration><![CDATA[VAR_GLOBAL
    Message : STRING := 'Hello World';
    Status : STRING := "Ready";
END_VAR]]></Declaration>
              </GVL>
            </TcPlcObject>
            """;

        JsonObject result = parser.parseContent(xml, "strings.xml");
        JsonArray tags = result.getAsJsonArray("global_tags");

        assertEquals(2, tags.size());

        assertEquals("Message", tags.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("Hello World", tags.get(0).getAsJsonObject().get("value").getAsString());

        assertEquals("Status", tags.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("Ready", tags.get(1).getAsJsonObject().get("value").getAsString());
    }

    @Test
    public void shouldIdentifyBeckhoffFiles() {
        assertTrue(parser.canHandle("beckhoff_project.xml"));
        assertTrue(parser.canHandle("twincat_plc.xml"));
        assertTrue(parser.canHandle("project.plcproj.xml"));
        assertTrue(parser.canHandle("gvl.xti"));
        assertTrue(parser.canHandle("variables.tpy"));
        assertTrue(parser.canHandle("data.tsm"));
        assertFalse(parser.canHandle("rockwell_file.l5x"));
        assertFalse(parser.canHandle("generic.xml"));
    }

    @Test
    public void shouldReturnCorrectParserType() {
        assertEquals("beckhoff", parser.getParserType());
    }

    @Test
    public void shouldHandleEmptyDeclaration() {
        String xml = """
            <?xml version="1.0"?>
            <TcPlcObject>
              <GVL>
                <Declaration><![CDATA[VAR_GLOBAL
END_VAR]]></Declaration>
              </GVL>
            </TcPlcObject>
            """;

        JsonObject result = parser.parseContent(xml, "empty.xml");

        assertNotNull(result);
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(0, tags.size());
    }

    @Test
    public void shouldHandleMalformedXML() {
        String xml = "This is not valid XML";

        JsonObject result = parser.parseContent(xml, "invalid.xml");

        assertNull(result, "Parser should return null for malformed XML");
    }
}
