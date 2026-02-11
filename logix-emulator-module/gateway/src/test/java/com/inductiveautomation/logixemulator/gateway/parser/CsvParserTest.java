package com.inductiveautomation.logixemulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

class CsvParserTest {

    private CsvParser parser;

    @BeforeEach
    void setUp() {
        parser = new CsvParser();
    }

    @Test
    @DisplayName("Should parse simple CSV with header")
    void testParseWithHeader() {
        String content = "name,type,value,description\n" +
            "Motor1_Speed,DINT,100,Motor speed\n" +
            "Temperature,REAL,72.5,Current temp\n" +
            "Running,BOOL,true,Motor running\n";

        JsonObject result = parser.parseContent(content, "test.csv");

        assertThat(result).isNotNull();
        assertThat(result.get("vendor").getAsString()).isEqualTo("csv");
        assertThat(result.get("controller").getAsString()).isEqualTo("CSVController");
        assertThat(result.has("global_tags")).isTrue();

        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isEqualTo(3);

        JsonObject motor = tags.get(0).getAsJsonObject();
        assertThat(motor.get("name").getAsString()).isEqualTo("Motor1_Speed");
        assertThat(motor.get("data_type").getAsString()).isEqualTo("DINT");
        assertThat(motor.get("initial_value").getAsString()).isEqualTo("100");
    }

    @Test
    @DisplayName("Should parse CSV without header")
    void testParseWithoutHeader() {
        String content = "Pump1_Flow,REAL,45.2\n" +
            "Pump1_Running,BOOL,false\n";

        JsonObject result = parser.parseContent(content, "test.csv");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isEqualTo(2);

        JsonObject pump = tags.get(0).getAsJsonObject();
        assertThat(pump.get("name").getAsString()).isEqualTo("Pump1_Flow");
    }

    @Test
    @DisplayName("Should skip comment lines")
    void testSkipComments() {
        String content = "# This is a comment\n" +
            "Tag1,DINT,42\n" +
            "# Another comment\n" +
            "Tag2,BOOL,true\n";

        JsonObject result = parser.parseContent(content, "test.csv");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should skip empty lines")
    void testSkipEmptyLines() {
        String content = "Tag1,DINT,10\n" +
            "\n" +
            "Tag2,REAL,20.5\n" +
            "\n";

        JsonObject result = parser.parseContent(content, "test.csv");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should handle semicolon delimiter")
    void testSemicolonDelimiter() {
        String content = "Tag1;DINT;100\n" +
            "Tag2;REAL;55.5\n";

        JsonObject result = parser.parseContent(content, "test.csv");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isEqualTo(2);
        assertThat(tags.get(0).getAsJsonObject().get("name").getAsString()).isEqualTo("Tag1");
    }

    @Test
    @DisplayName("Should handle quoted values")
    void testQuotedValues() {
        String content = "\"My Tag\",DINT,100,\"A tag, with comma\"\n";

        JsonObject result = parser.parseContent(content, "test.csv");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isEqualTo(1);
        assertThat(tags.get(0).getAsJsonObject().get("name").getAsString()).isEqualTo("My Tag");
    }

    @Test
    @DisplayName("Should provide default values when missing")
    void testDefaultValues() {
        String content = "Tag1,DINT\n" +
            "Tag2,BOOL\n";

        JsonObject result = parser.parseContent(content, "test.csv");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isEqualTo(2);

        assertThat(tags.get(0).getAsJsonObject().get("initial_value").getAsString()).isEqualTo("0");
        assertThat(tags.get(1).getAsJsonObject().get("initial_value").getAsString()).isEqualTo("false");
    }

    @Test
    @DisplayName("Should normalize boolean values")
    void testBooleanNormalization() {
        String content = "Tag1,BOOL,1\n" +
            "Tag2,BOOL,true\n" +
            "Tag3,BOOL,false\n" +
            "Tag4,BOOL,0\n";

        JsonObject result = parser.parseContent(content, "test.csv");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.get(0).getAsJsonObject().get("initial_value").getAsString()).isEqualTo("true");
        assertThat(tags.get(1).getAsJsonObject().get("initial_value").getAsString()).isEqualTo("true");
        assertThat(tags.get(2).getAsJsonObject().get("initial_value").getAsString()).isEqualTo("false");
        assertThat(tags.get(3).getAsJsonObject().get("initial_value").getAsString()).isEqualTo("false");
    }

    @Test
    @DisplayName("Should uppercase data types")
    void testUppercaseDataTypes() {
        String content = "Tag1,dint,0\n" +
            "Tag2,real,0.0\n";

        JsonObject result = parser.parseContent(content, "test.csv");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.get(0).getAsJsonObject().get("data_type").getAsString()).isEqualTo("DINT");
        assertThat(tags.get(1).getAsJsonObject().get("data_type").getAsString()).isEqualTo("REAL");
    }

    @Test
    @DisplayName("Should handle lines with too few columns")
    void testTooFewColumns() {
        String content = "Tag1,DINT,100\n" +
            "invalid\n" +
            "Tag2,REAL,50.0\n";

        JsonObject result = parser.parseContent(content, "test.csv");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should correctly identify CSV files")
    void testCanHandle() {
        assertThat(parser.canHandle("tags.csv")).isTrue();
        assertThat(parser.canHandle("tags.CSV")).isTrue();
        assertThat(parser.canHandle("tags.txt")).isTrue();

        assertThat(parser.canHandle("tags.json")).isFalse();
        assertThat(parser.canHandle("tags.l5k")).isFalse();
        assertThat(parser.canHandle(null)).isFalse();
    }

    @Test
    @DisplayName("Should return correct parser type")
    void testGetParserType() {
        assertThat(parser.getParserType()).isEqualTo("csv");
    }

    @Test
    @DisplayName("Should handle Windows line endings")
    void testWindowsLineEndings() {
        String content = "Tag1,DINT,10\r\nTag2,REAL,20.5\r\n";

        JsonObject result = parser.parseContent(content, "test.csv");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should handle invalid numeric values gracefully")
    void testInvalidNumericValues() {
        String content = "Tag1,DINT,not_a_number\n" +
            "Tag2,REAL,also_not_a_number\n";

        JsonObject result = parser.parseContent(content, "test.csv");

        assertThat(result).isNotNull();
        JsonArray tags = result.getAsJsonArray("global_tags");
        assertThat(tags.get(0).getAsJsonObject().get("initial_value").getAsString()).isEqualTo("0");
        assertThat(tags.get(1).getAsJsonObject().get("initial_value").getAsString()).isEqualTo("0.0");
    }
}
