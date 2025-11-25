package com.inductiveautomation.plcsimulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static com.inductiveautomation.plcsimulator.gateway.parser.DataTypeUtils.*;

/**
 * Parser for Beckhoff TwinCAT files (TwinCAT 2, TwinCAT 3).
 *
 * Supports:
 * - TwinCAT 3 XTI project exports (XML)
 * - TwinCAT 3 PLC project exports
 * - TwinCAT 2 TPY/TSM exports
 * - Global Variable Lists (GVL)
 *
 * Parses:
 * - Global Variable Lists (GVL)
 * - PLC Program variables (PRG)
 * - Function Block variables (FB)
 * - Data Unit Types (DUT)
 * - Persistent variables
 */
public class BeckhoffParser implements PLCParser {

    private static final Logger logger = LoggerFactory.getLogger(BeckhoffParser.class);

    @Override
    public JsonObject parse(String filePath) {
        try {
            String content = Files.readString(Paths.get(filePath));
            return parseContent(content, filePath);
        } catch (Exception e) {
            logger.error("Error reading Beckhoff TwinCAT file: {}", filePath, e);
            return null;
        }
    }

    @Override
    public JsonObject parseContent(String fileContent, String fileName) {
        try {
            // Trim to remove leading/trailing whitespace (XML declaration must be first)
            fileContent = fileContent.trim();

            // Parse XML with XXE protection
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);

            // Disable XXE features (but allow XML declarations)
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(fileContent.getBytes()));

            JsonObject result = new JsonObject();
            Element root = doc.getDocumentElement();

            result.addProperty("vendor", "beckhoff");
            result.addProperty("format", "twincat");

            logger.info("Parsing Beckhoff TwinCAT file, root element: {}", root.getNodeName());

            // Parse based on TwinCAT version and format
            if (isTwinCAT3Format(root)) {
                parseTwinCAT3(root, result);
            } else {
                parseTwinCAT2(root, result);
            }

            return result;

        } catch (Exception e) {
            logger.error("Error parsing Beckhoff TwinCAT file", e);
            return null;
        }
    }

    /**
     * Check if this is TwinCAT 3 format (XTI XML).
     * TwinCAT 3 has TcPlcObject, GVL, POU, or DUT elements.
     * TwinCAT 2 has Variables with Variable child elements.
     */
    private boolean isTwinCAT3Format(Element root) {
        String rootName = root.getNodeName();

        // Explicit TwinCAT 3 root element
        if (rootName.equals("TcPlcObject")) {
            return true;
        }

        // Check for TwinCAT 3 specific elements
        if (root.getElementsByTagName("TcPlcObject").getLength() > 0 ||
            root.getElementsByTagName("GVL").getLength() > 0 ||
            root.getElementsByTagName("POU").getLength() > 0 ||
            root.getElementsByTagName("DUT").getLength() > 0) {
            return true;
        }

        // Check for TwinCAT 2 specific structure (Variables with Variable elements)
        NodeList variablesLists = root.getElementsByTagName("Variables");
        if (variablesLists.getLength() > 0) {
            Element variablesElement = (Element) variablesLists.item(0);
            if (variablesElement.getElementsByTagName("Variable").getLength() > 0) {
                return false; // This is TwinCAT 2
            }
        }

        // Default to TwinCAT 3 for <Project> without clear markers
        return rootName.equals("Project");
    }

    /**
     * Parse TwinCAT 3 project export.
     */
    private void parseTwinCAT3(Element root, JsonObject result) {
        JsonArray globalTags = new JsonArray();
        Map<String, JsonObject> dutDefinitions = new HashMap<>();

        // Parse DUTs (Data Unit Types) first
        parseDUTs(root, dutDefinitions, result);

        // Parse Global Variable Lists (GVL)
        parseGlobalVariableLists(root, globalTags, dutDefinitions);

        // Parse POUs (Program Organization Units)
        parsePOUs(root, globalTags, dutDefinitions);

        result.add("global_tags", globalTags);
        logger.info("Parsed {} global tags from TwinCAT 3", globalTags.size());
    }

    /**
     * Parse TwinCAT 2 project export.
     */
    private void parseTwinCAT2(Element root, JsonObject result) {
        JsonArray globalTags = new JsonArray();

        // TwinCAT 2 has a simpler structure
        NodeList variables = root.getElementsByTagName("Variable");

        logger.info("Found {} variables in TwinCAT 2 format", variables.getLength());

        for (int i = 0; i < variables.getLength(); i++) {
            Element varElement = (Element) variables.item(i);
            JsonObject tag = parseTwinCAT2Variable(varElement);
            if (tag != null) {
                globalTags.add(tag);
            }
        }

        result.add("global_tags", globalTags);
        logger.info("Parsed {} global tags from TwinCAT 2", globalTags.size());
    }

    /**
     * Parse Global Variable Lists (GVL).
     */
    private void parseGlobalVariableLists(Element root, JsonArray globalTags, Map<String, JsonObject> dutDefinitions) {
        NodeList gvlElements = root.getElementsByTagName("GVL");

        logger.info("Found {} Global Variable Lists", gvlElements.getLength());

        for (int i = 0; i < gvlElements.getLength(); i++) {
            Element gvlElement = (Element) gvlElements.item(i);
            parseDeclarationBlock(gvlElement, globalTags, dutDefinitions);
        }
    }

    /**
     * Parse POUs (Program Organization Units).
     */
    private void parsePOUs(Element root, JsonArray globalTags, Map<String, JsonObject> dutDefinitions) {
        NodeList pouElements = root.getElementsByTagName("POU");

        logger.info("Found {} POUs", pouElements.getLength());

        for (int i = 0; i < pouElements.getLength(); i++) {
            Element pouElement = (Element) pouElements.item(i);
            parseDeclarationBlock(pouElement, globalTags, dutDefinitions);
        }
    }

    /**
     * Parse DUTs (Data Unit Types).
     */
    private void parseDUTs(Element root, Map<String, JsonObject> dutDefinitions, JsonObject result) {
        NodeList dutElements = root.getElementsByTagName("DUT");

        if (dutElements.getLength() > 0) {
            JsonArray udts = new JsonArray();

            logger.info("Found {} DUTs", dutElements.getLength());

            for (int i = 0; i < dutElements.getLength(); i++) {
                Element dutElement = (Element) dutElements.item(i);
                JsonObject dut = parseDUT(dutElement);
                if (dut != null) {
                    udts.add(dut);
                    dutDefinitions.put(dut.get("name").getAsString(), dut);
                }
            }

            if (udts.size() > 0) {
                result.add("udts", udts);
                logger.info("Parsed {} DUT definitions", udts.size());
            }
        }
    }

    /**
     * Parse a declaration block (common to GVL and POU).
     */
    private void parseDeclarationBlock(Element parent, JsonArray tags, Map<String, JsonObject> dutDefinitions) {
        NodeList declarations = parent.getElementsByTagName("Declaration");

        for (int i = 0; i < declarations.getLength(); i++) {
            Element declaration = (Element) declarations.item(i);
            String declarationText = declaration.getTextContent();

            if (declarationText != null && !declarationText.isEmpty()) {
                parseDeclarationText(declarationText, tags, dutDefinitions);
            }
        }
    }

    /**
     * Parse declaration text (Structured Text format).
     * Example: "varName : INT := 0;"
     */
    private void parseDeclarationText(String declarationText, JsonArray tags, Map<String, JsonObject> dutDefinitions) {
        // First, remove VAR blocks and comments
        String cleaned = declarationText;

        // Remove single-line comments
        cleaned = cleaned.replaceAll("//.*?(?=\\r?\\n|$)", "");

        // Remove multi-line comments
        cleaned = cleaned.replaceAll("\\(\\*.*?\\*\\)", "");

        // Split by newlines to process each line
        String[] lines = cleaned.split("\\r?\\n");

        for (String line : lines) {
            line = line.trim();

            // Skip empty lines and VAR block keywords
            if (line.isEmpty() ||
                line.matches("(?i)^VAR(_GLOBAL)?$") ||
                line.matches("(?i)^END_VAR$")) {
                continue;
            }

            // Remove trailing semicolon
            if (line.endsWith(";")) {
                line = line.substring(0, line.length() - 1).trim();
            }

            JsonObject tag = parseVariableDeclaration(line);
            if (tag != null) {
                tags.add(tag);
            }
        }
    }

    /**
     * Parse a single variable declaration.
     * Format: "varName : DataType := InitialValue"
     */
    private JsonObject parseVariableDeclaration(String declaration) {
        try {
            // Remove attribute keywords (PERSISTENT, RETAIN, etc.)
            declaration = declaration.replaceAll("(?i)\\b(PERSISTENT|RETAIN|CONSTANT)\\b", "").trim();

            // Split by colon to get name and type/value
            int colonIndex = declaration.indexOf(':');
            if (colonIndex < 0) {
                return null;
            }

            String name = declaration.substring(0, colonIndex).trim();
            String typeAndValue = declaration.substring(colonIndex + 1).trim();

            // Split by := to separate type and initial value
            String dataType;
            String initialValue = null;

            int assignIndex = typeAndValue.indexOf(":=");
            if (assignIndex > 0) {
                dataType = typeAndValue.substring(0, assignIndex).trim();
                initialValue = typeAndValue.substring(assignIndex + 2).trim();
            } else {
                dataType = typeAndValue.trim();
            }

            if (name.isEmpty() || dataType.isEmpty()) {
                return null;
            }

            JsonObject tag = new JsonObject();
            tag.addProperty("name", name);
            tag.addProperty("dataType", convertBeckhoffDataType(dataType));

            if (initialValue != null && !initialValue.isEmpty()) {
                addValueProperty(tag, parseInitialValue(initialValue, dataType));
            } else {
                addValueProperty(tag, getDefaultValue(dataType));
            }

            logger.debug("Parsed TwinCAT variable: {} ({})", name, dataType);
            return tag;

        } catch (Exception e) {
            logger.debug("Error parsing variable declaration: {}", declaration, e);
            return null;
        }
    }

    /**
     * Parse TwinCAT 2 variable element.
     */
    private JsonObject parseTwinCAT2Variable(Element varElement) {
        try {
            String name = getAttribute(varElement, "Name", null);
            if (name == null || name.isEmpty()) {
                return null;
            }

            String dataType = getAttribute(varElement, "Type", "BOOL");
            String comment = getAttribute(varElement, "Comment", "");

            JsonObject tag = new JsonObject();
            tag.addProperty("name", name);
            tag.addProperty("dataType", convertBeckhoffDataType(dataType));
            addValueProperty(tag, getDefaultValue(dataType));

            if (!comment.isEmpty()) {
                tag.addProperty("description", comment);
            }

            logger.debug("Parsed TwinCAT 2 variable: {} ({})", name, dataType);
            return tag;

        } catch (Exception e) {
            logger.warn("Error parsing TwinCAT 2 variable", e);
            return null;
        }
    }

    /**
     * Parse a DUT (Data Unit Type).
     */
    private JsonObject parseDUT(Element dutElement) {
        try {
            String name = getAttribute(dutElement, "Name", null);
            if (name == null || name.isEmpty()) {
                return null;
            }

            JsonObject dut = new JsonObject();
            dut.addProperty("name", name);

            JsonArray members = new JsonArray();

            // Parse declaration to extract members
            NodeList declarations = dutElement.getElementsByTagName("Declaration");
            if (declarations.getLength() > 0) {
                String declarationText = declarations.item(0).getTextContent();
                parseStructMembers(declarationText, members);
            }

            dut.add("members", members);
            logger.debug("Parsed DUT: {} with {} members", name, members.size());

            return dut;

        } catch (Exception e) {
            logger.warn("Error parsing DUT", e);
            return null;
        }
    }

    /**
     * Parse struct members from declaration text.
     */
    private void parseStructMembers(String declarationText, JsonArray members) {
        String[] lines = declarationText.split("\\r?\\n");

        for (String line : lines) {
            line = line.trim();

            // Skip TYPE, STRUCT, END_STRUCT, END_TYPE, comments
            if (line.isEmpty() ||
                line.startsWith("TYPE") ||
                line.startsWith("STRUCT") ||
                line.startsWith("END_") ||
                line.startsWith("//") ||
                line.startsWith("(*")) {
                continue;
            }

            JsonObject member = parseVariableDeclaration(line.replace(";", ""));
            if (member != null) {
                members.add(member);
            }
        }
    }

    /**
     * Convert Beckhoff/IEC 61131-3 data types to common format.
     */
    private String convertBeckhoffDataType(String beckhoffType) {
        if (beckhoffType == null) {
            return "BOOL";
        }

        String normalized = beckhoffType.toUpperCase().trim();

        // Handle array types
        if (normalized.startsWith("ARRAY[")) {
            int ofIndex = normalized.indexOf(" OF ");
            if (ofIndex > 0) {
                String baseType = normalized.substring(ofIndex + 4).trim();
                return convertBeckhoffDataType(baseType);
            }
            return "DINT";
        }

        return switch (normalized) {
            case "BOOL" -> "BOOL";
            case "BYTE", "USINT" -> "SINT";
            case "WORD", "UINT" -> "INT";
            case "DWORD", "UDINT" -> "DINT";
            case "LWORD", "ULINT" -> "LINT";
            case "SINT" -> "SINT";
            case "INT" -> "INT";
            case "DINT" -> "DINT";
            case "LINT" -> "LINT";
            case "REAL" -> "REAL";
            case "LREAL" -> "LREAL";
            case "STRING" -> "STRING";
            case "WSTRING" -> "STRING";
            case "TIME", "TIME_OF_DAY", "TOD" -> "DINT"; // Time as milliseconds
            case "DATE", "DATE_AND_TIME", "DT" -> "LINT"; // Date/time as timestamp
            default -> {
                logger.debug("Unknown Beckhoff data type: {}, defaulting to DINT", beckhoffType);
                yield "DINT";
            }
        };
    }

    /**
     * Get default value for a data type.
     */
    private Object getDefaultValue(String dataType) {
        String converted = convertBeckhoffDataType(dataType);

        return switch (converted) {
            case "BOOL" -> false;
            case "SINT", "INT", "DINT", "LINT" -> 0;
            case "REAL", "LREAL" -> 0.0;
            case "STRING" -> "";
            default -> 0;
        };
    }

    /**
     * Parse initial value from string.
     */
    private Object parseInitialValue(String valueStr, String dataType) {
        try {
            String converted = convertBeckhoffDataType(dataType);

            return switch (converted) {
                case "BOOL" -> valueStr.equalsIgnoreCase("TRUE") || valueStr.equals("1");
                case "SINT", "INT", "DINT" -> Integer.parseInt(valueStr);
                case "LINT" -> Long.parseLong(valueStr);
                case "REAL", "LREAL" -> Double.parseDouble(valueStr);
                case "STRING" -> valueStr.replace("'", "").replace("\"", "");
                default -> 0;
            };
        } catch (Exception e) {
            logger.debug("Error parsing initial value '{}', using default", valueStr);
            return getDefaultValue(dataType);
        }
    }

    /**
     * Get attribute value from XML element.
     */
    private String getAttribute(Element element, String attributeName, String defaultValue) {
        String value = element.getAttribute(attributeName);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }

    @Override
    public boolean canHandle(String fileName) {
        if (fileName == null) {
            return false;
        }

        String lowerFileName = fileName.toLowerCase();

        // Beckhoff TwinCAT files
        return (lowerFileName.endsWith(".xml") &&
                (lowerFileName.contains("beckhoff") ||
                 lowerFileName.contains("twincat") ||
                 lowerFileName.contains("plcproj"))) ||
               lowerFileName.endsWith(".xti") ||
               lowerFileName.endsWith(".tpy") ||
               lowerFileName.endsWith(".tsm");
    }

    /**
     * Helper method to add value property with proper type handling.
     */
    private void addValueProperty(JsonObject tag, Object value) {
        if (value instanceof Boolean) {
            tag.addProperty("value", (Boolean) value);
        } else if (value instanceof Number) {
            tag.addProperty("value", (Number) value);
        } else if (value instanceof String) {
            tag.addProperty("value", (String) value);
        } else {
            tag.addProperty("value", String.valueOf(value));
        }
    }

    @Override
    public String getParserType() {
        return "beckhoff";
    }
}
