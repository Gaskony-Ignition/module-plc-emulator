package com.inductiveautomation.plcsimulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Siemens TIA Portal parser.
 */
public class SiemensParserTest {

    private final SiemensParser parser = new SiemensParser();

    @Test
    public void shouldParseSimpleTiaPortalDataBlock() {
        String xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <Document>
              <Engineering version="V17">
                <SW.Blocks.GlobalDB ID="0">
                  <AttributeList>
                    <Name>GlobalData</Name>
                  </AttributeList>
                  <ObjectList>
                    <Member Name="Temperature" Datatype="Real">
                      <Comment>
                        <MultiLanguageText Lang="en-US">Current temperature</MultiLanguageText>
                      </Comment>
                    </Member>
                    <Member Name="Pressure" Datatype="Int">
                      <Comment>
                        <MultiLanguageText Lang="en-US">System pressure</MultiLanguageText>
                      </Comment>
                    </Member>
                    <Member Name="Running" Datatype="Bool" />
                  </ObjectList>
                </SW.Blocks.GlobalDB>
              </Engineering>
            </Document>
            """;

        JsonObject result = parser.parseContent(xml, "siemens_test.xml");

        assertNotNull(result, "Parser should return a result");
        assertEquals("siemens", result.get("vendor").getAsString());
        assertTrue(result.has("global_tags"));

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(3, tags.size(), "Should parse 3 tags");

        // Check first tag (Temperature)
        JsonObject tempTag = tags.get(0).getAsJsonObject();
        assertEquals("Temperature", tempTag.get("name").getAsString());
        assertEquals("REAL", tempTag.get("dataType").getAsString());
        assertTrue(tempTag.has("description"));

        // Check second tag (Pressure)
        JsonObject pressureTag = tags.get(1).getAsJsonObject();
        assertEquals("Pressure", pressureTag.get("name").getAsString());
        assertEquals("INT", pressureTag.get("dataType").getAsString());

        // Check third tag (Running - Boolean)
        JsonObject runningTag = tags.get(2).getAsJsonObject();
        assertEquals("Running", runningTag.get("name").getAsString());
        assertEquals("BOOL", runningTag.get("dataType").getAsString());
    }

    @Test
    public void shouldParseTagTable() {
        String xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <Document>
              <SW.Tags.PlcTagTable ID="1">
                <AttributeList>
                  <Name>DefaultTagTable</Name>
                </AttributeList>
                <ObjectList>
                  <Tag Name="StartButton" DataTypeName="Bool" LogicalAddress="%I0.0">
                    <Comment>
                      <MultiLanguageText Lang="en-US">Start button input</MultiLanguageText>
                    </Comment>
                  </Tag>
                  <Tag Name="MotorSpeed" DataTypeName="Int" LogicalAddress="%MW100">
                    <Comment>
                      <MultiLanguageText Lang="en-US">Motor speed setpoint</MultiLanguageText>
                    </Comment>
                  </Tag>
                </ObjectList>
              </SW.Tags.PlcTagTable>
            </Document>
            """;

        JsonObject result = parser.parseContent(xml, "siemens_tags.xml");

        assertNotNull(result);
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(2, tags.size());

        JsonObject startButton = tags.get(0).getAsJsonObject();
        assertEquals("StartButton", startButton.get("name").getAsString());
        assertEquals("BOOL", startButton.get("dataType").getAsString());
        assertEquals("%I0.0", startButton.get("address").getAsString());
        assertTrue(startButton.get("description").getAsString().contains("Start button"));

        JsonObject motorSpeed = tags.get(1).getAsJsonObject();
        assertEquals("MotorSpeed", motorSpeed.get("name").getAsString());
        assertEquals("INT", motorSpeed.get("dataType").getAsString());
        assertEquals("%MW100", motorSpeed.get("address").getAsString());
    }

    @Test
    public void shouldConvertSiemensDataTypes() {
        String xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <Document>
              <SW.Blocks.GlobalDB>
                <ObjectList>
                  <Member Name="BoolVar" Datatype="Bool" />
                  <Member Name="ByteVar" Datatype="Byte" />
                  <Member Name="WordVar" Datatype="Word" />
                  <Member Name="DWordVar" Datatype="DWord" />
                  <Member Name="IntVar" Datatype="Int" />
                  <Member Name="DIntVar" Datatype="DInt" />
                  <Member Name="RealVar" Datatype="Real" />
                  <Member Name="StringVar" Datatype="String" />
                </ObjectList>
              </SW.Blocks.GlobalDB>
            </Document>
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
    }

    @Test
    public void shouldHandleArrays() {
        String xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <Document>
              <SW.Blocks.GlobalDB>
                <ObjectList>
                  <Member Name="TempArray" Datatype="Array[1..10] of Real" />
                  <Member Name="StatusArray" Datatype="Array[0..15] of Bool" />
                </ObjectList>
              </SW.Blocks.GlobalDB>
            </Document>
            """;

        JsonObject result = parser.parseContent(xml, "arrays.xml");
        JsonArray tags = result.getAsJsonArray("global_tags");

        assertEquals(2, tags.size());

        JsonObject tempArray = tags.get(0).getAsJsonObject();
        assertEquals("TempArray", tempArray.get("name").getAsString());
        assertEquals("REAL", tempArray.get("dataType").getAsString());
        assertTrue(tempArray.has("dimensions"));
        assertEquals(10, tempArray.getAsJsonArray("dimensions").get(0).getAsInt());

        JsonObject statusArray = tags.get(1).getAsJsonObject();
        assertEquals("StatusArray", statusArray.get("name").getAsString());
        assertEquals("BOOL", statusArray.get("dataType").getAsString());
        assertTrue(statusArray.has("dimensions"));
        assertEquals(16, statusArray.getAsJsonArray("dimensions").get(0).getAsInt());
    }

    @Test
    public void shouldIdentifySiemensFiles() {
        assertTrue(parser.canHandle("siemens_project.xml"));
        assertTrue(parser.canHandle("tia_portal_export.xml"));
        assertTrue(parser.canHandle("s7-1500_tags.xml"));
        assertTrue(parser.canHandle("global_db.db.xml"));
        assertFalse(parser.canHandle("rockwell_file.l5x"));
        assertFalse(parser.canHandle("data.csv"));
    }

    @Test
    public void shouldReturnCorrectParserType() {
        assertEquals("siemens", parser.getParserType());
    }

    @Test
    public void shouldHandleEmptyXML() {
        String xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <Document>
              <SW.Blocks.GlobalDB>
                <ObjectList>
                </ObjectList>
              </SW.Blocks.GlobalDB>
            </Document>
            """;

        JsonObject result = parser.parseContent(xml, "empty.xml");

        assertNotNull(result);
        assertTrue(result.has("global_tags"));
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertEquals(0, tags.size(), "Empty data block should result in 0 tags");
    }

    @Test
    public void shouldHandleMalformedXML() {
        String xml = "This is not valid XML";

        JsonObject result = parser.parseContent(xml, "invalid.xml");

        assertNull(result, "Parser should return null for malformed XML");
    }
}
