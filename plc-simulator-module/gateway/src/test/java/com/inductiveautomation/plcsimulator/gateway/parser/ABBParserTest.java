package com.inductiveautomation.plcsimulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ABB Automation Builder / Control Builder Plus parser.
 */
public class ABBParserTest {

    private final ABBParser parser = new ABBParser();

    @Test
    public void shouldParseAutomationBuilderProject() {
        String xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <Project>
              <GlobalVars>
                <Variable Name="Temperature" Type="REAL" InitialValue="25.5" Comment="Process temperature" />
                <Variable Name="Pressure" Type="INT" InitialValue="100" Comment="System pressure" />
                <Variable Name="Running" Type="BOOL" InitialValue="TRUE" Comment="System running status" />
              </GlobalVars>
            </Project>
            """;

        JsonObject result = parser.parseContent(xml, "abb_project.xml");

        assertNotNull(result, "Parser should return a result");
        assertEquals("abb", result.get("vendor").getAsString());
        assertTrue(result.has("global_tags"));

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(3, tags.size(), "Should parse 3 variables");

        // Check Temperature
        JsonObject temperature = tags.get(0).getAsJsonObject();
        assertEquals("Temperature", temperature.get("name").getAsString());
        assertEquals("REAL", temperature.get("dataType").getAsString());
        assertEquals(25.5, temperature.get("value").getAsDouble(), 0.01);
        assertEquals("Process temperature", temperature.get("description").getAsString());

        // Check Pressure
        JsonObject pressure = tags.get(1).getAsJsonObject();
        assertEquals("Pressure", pressure.get("name").getAsString());
        assertEquals("INT", pressure.get("dataType").getAsString());
        assertEquals(100, pressure.get("value").getAsInt());

        // Check Running
        JsonObject running = tags.get(2).getAsJsonObject();
        assertEquals("Running", running.get("name").getAsString());
        assertEquals("BOOL", running.get("dataType").getAsString());
        assertTrue(running.get("value").getAsBoolean());
    }

    @Test
    public void shouldParseControlBuilderPlusVariables() {
        String xml = """
            <?xml version="1.0"?>
            <ABBProject>
              <Variables>
                <Variable Name="MotorSpeed" Type="INT" Comment="Motor speed setpoint" />
                <Variable Name="FlowRate" Type="REAL" InitialValue="50.0" />
                <Variable Name="Enabled" Type="BOOL" InitialValue="FALSE" />
              </Variables>
            </ABBProject>
            """;

        JsonObject result = parser.parseContent(xml, "abb_ac800m.xml");

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(3, tags.size());

        assertEquals("MotorSpeed", tags.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("INT", tags.get(0).getAsJsonObject().get("dataType").getAsString());

        assertEquals("FlowRate", tags.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals(50.0, tags.get(1).getAsJsonObject().get("value").getAsDouble(), 0.01);

        assertEquals("Enabled", tags.get(2).getAsJsonObject().get("name").getAsString());
        assertFalse(tags.get(2).getAsJsonObject().get("value").getAsBoolean());
    }

    @Test
    public void shouldConvertABBDataTypes() {
        String xml = """
            <?xml version="1.0"?>
            <Project>
              <GlobalVars>
                <Variable Name="BoolVar" Type="BOOL" />
                <Variable Name="ByteVar" Type="BYTE" />
                <Variable Name="WordVar" Type="WORD" />
                <Variable Name="DWordVar" Type="DWORD" />
                <Variable Name="IntVar" Type="INT" />
                <Variable Name="DIntVar" Type="DINT" />
                <Variable Name="RealVar" Type="REAL" />
                <Variable Name="LRealVar" Type="LREAL" />
                <Variable Name="StringVar" Type="STRING" />
              </GlobalVars>
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
        assertEquals("LREAL", tags.get(7).getAsJsonObject().get("dataType").getAsString());
        assertEquals("STRING", tags.get(8).getAsJsonObject().get("dataType").getAsString());
    }

    @Test
    public void shouldParseDataTypes() {
        String xml = """
            <?xml version="1.0"?>
            <Project>
              <DataType Name="MotorControl">
                <Member Name="Speed" Type="INT" />
                <Member Name="Current" Type="REAL" />
                <Member Name="Running" Type="BOOL" />
              </DataType>
            </Project>
            """;

        JsonObject result = parser.parseContent(xml, "udt.xml");

        assertTrue(result.has("udts"));
        JsonArray udts = result.getAsJsonArray("udts");
        assertEquals(1, udts.size());

        JsonObject motorControl = udts.get(0).getAsJsonObject();
        assertEquals("MotorControl", motorControl.get("name").getAsString());

        JsonArray members = motorControl.getAsJsonArray("members");
        assertEquals(3, members.size());

        assertEquals("Speed", members.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("INT", members.get(0).getAsJsonObject().get("dataType").getAsString());

        assertEquals("Current", members.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("REAL", members.get(1).getAsJsonObject().get("dataType").getAsString());

        assertEquals("Running", members.get(2).getAsJsonObject().get("name").getAsString());
        assertEquals("BOOL", members.get(2).getAsJsonObject().get("dataType").getAsString());
    }

    @Test
    public void shouldParseProgramVariables() {
        String xml = """
            <?xml version="1.0"?>
            <Project>
              <Program Name="MainProgram">
                <Variable Name="Counter" Type="DINT" InitialValue="0" />
                <Variable Name="Status" Type="BOOL" />
              </Program>
            </Project>
            """;

        JsonObject result = parser.parseContent(xml, "program.xml");

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(2, tags.size());

        assertEquals("Counter", tags.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("DINT", tags.get(0).getAsJsonObject().get("dataType").getAsString());
        assertEquals(0, tags.get(0).getAsJsonObject().get("value").getAsInt());

        assertEquals("Status", tags.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("BOOL", tags.get(1).getAsJsonObject().get("dataType").getAsString());
    }

    @Test
    public void shouldHandleChildElementStructure() {
        String xml = """
            <?xml version="1.0"?>
            <Project>
              <GlobalVars>
                <Variable>
                  <Name>TestVar</Name>
                  <Type>INT</Type>
                  <InitialValue>42</InitialValue>
                  <Comment>Test variable</Comment>
                </Variable>
              </GlobalVars>
            </Project>
            """;

        JsonObject result = parser.parseContent(xml, "child_elements.xml");

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(1, tags.size());

        JsonObject testVar = tags.get(0).getAsJsonObject();
        assertEquals("TestVar", testVar.get("name").getAsString());
        assertEquals("INT", testVar.get("dataType").getAsString());
        assertEquals(42, testVar.get("value").getAsInt());
        assertEquals("Test variable", testVar.get("description").getAsString());
    }

    @Test
    public void shouldIdentifyABBFiles() {
        assertTrue(parser.canHandle("abb_project.xml"));
        assertTrue(parser.canHandle("automation_builder.xml"));
        assertTrue(parser.canHandle("ac800m_export.xml"));
        assertTrue(parser.canHandle("project.apj"));
        assertFalse(parser.canHandle("rockwell_file.l5x"));
        assertFalse(parser.canHandle("generic.xml"));
    }

    @Test
    public void shouldReturnCorrectParserType() {
        assertEquals("abb", parser.getParserType());
    }

    @Test
    public void shouldHandleEmptyProject() {
        String xml = """
            <?xml version="1.0"?>
            <Project>
              <GlobalVars>
              </GlobalVars>
            </Project>
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
