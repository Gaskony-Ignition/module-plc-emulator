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
 * Parser for ABB Automation Builder / Control Builder Plus files.
 *
 * Supports:
 * - ABB Automation Builder projects (.apj, .xml)
 * - Control Builder Plus AC800M exports
 * - IEC 61131-3 compliant variable declarations
 *
 * Parses:
 * - Global variables
 * - Program variables
 * - Function block variables
 * - User-defined data types (structs)
 */
public class ABBParser implements PLCParser {

    private static final Logger logger = LoggerFactory.getLogger(ABBParser.class);

    @Override
    public JsonObject parse(String filePath) {
        try {
            String content = Files.readString(Paths.get(filePath));
            return parseContent(content, filePath);
        } catch (Exception e) {
            logger.error("Error reading ABB file: {}", filePath, e);
            return null;
        }
    }

    @Override
    public JsonObject parseContent(String fileContent, String fileName) {
        try {
            // Trim to remove leading/trailing whitespace
            fileContent = fileContent.trim();

            // Parse XML with XXE protection
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);

            // Disable XXE features
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(fileContent.getBytes()));

            JsonObject result = new JsonObject();
            Element root = doc.getDocumentElement();

            result.addProperty("vendor", "abb");
            result.addProperty("format", "automation_builder");

            logger.info("Parsing ABB file, root element: {}", root.getNodeName());

            JsonArray globalTags = new JsonArray();
            Map<String, JsonObject> udtDefinitions = new HashMap<>();

            // Parse data types first
            parseDataTypes(root, udtDefinitions, result);

            // Parse global variables
            parseGlobalVariables(root, globalTags, udtDefinitions);

            // Parse program variables
            parseProgramVariables(root, globalTags, udtDefinitions);

            result.add("global_tags", globalTags);
            logger.info("Parsed {} global tags from ABB file", globalTags.size());

            return result;

        } catch (Exception e) {
            logger.error("Error parsing ABB file", e);
            return null;
        }
    }

    /**
     * Parse global variables from ABB project.
     */
    private void parseGlobalVariables(Element root, JsonArray globalTags, Map<String, JsonObject> udtDefinitions) {
        // Look for GlobalVars, GVL, or Variables elements
        String[] globalVarTags = {"GlobalVars", "GVL", "Variables"};

        for (String tagName : globalVarTags) {
            NodeList varLists = root.getElementsByTagName(tagName);

            for (int i = 0; i < varLists.getLength(); i++) {
                Element varList = (Element) varLists.item(i);
                parseVariableList(varList, globalTags, udtDefinitions);
            }
        }
    }

    /**
     * Parse program variables from ABB project.
     */
    private void parseProgramVariables(Element root, JsonArray globalTags, Map<String, JsonObject> udtDefinitions) {
        NodeList programs = root.getElementsByTagName("Program");

        for (int i = 0; i < programs.getLength(); i++) {
            Element program = (Element) programs.item(i);
            parseVariableList(program, globalTags, udtDefinitions);
        }
    }

    /**
     * Parse a list of variables from an element.
     */
    private void parseVariableList(Element parent, JsonArray tags, Map<String, JsonObject> udtDefinitions) {
        // Look for Variable or Var elements
        NodeList variables = parent.getElementsByTagName("Variable");

        if (variables.getLength() == 0) {
            variables = parent.getElementsByTagName("Var");
        }

        logger.debug("Found {} variables in element {}", variables.getLength(), parent.getNodeName());

        for (int i = 0; i < variables.getLength(); i++) {
            Element varElement = (Element) variables.item(i);
            JsonObject tag = parseVariable(varElement);
            if (tag != null) {
                tags.add(tag);
            }
        }
    }

    /**
     * Parse a single variable element.
     */
    private JsonObject parseVariable(Element varElement) {
        try {
            String name = getAttribute(varElement, "Name", null);
            if (name == null || name.isEmpty()) {
                // Try reading from child element
                NodeList nameElements = varElement.getElementsByTagName("Name");
                if (nameElements.getLength() > 0) {
                    name = nameElements.item(0).getTextContent().trim();
                }
            }

            if (name == null || name.isEmpty()) {
                return null;
            }

            String dataType = getAttribute(varElement, "Type", null);
            if (dataType == null || dataType.isEmpty()) {
                // Try reading from child element
                NodeList typeElements = varElement.getElementsByTagName("Type");
                if (typeElements.getLength() > 0) {
                    dataType = typeElements.item(0).getTextContent().trim();
                }
            }

            if (dataType == null || dataType.isEmpty()) {
                dataType = "BOOL"; // Default
            }

            String initialValue = getAttribute(varElement, "InitialValue", "");
            String comment = getAttribute(varElement, "Comment", "");

            // Check for InitialValue child element
            if (initialValue.isEmpty()) {
                NodeList valueElements = varElement.getElementsByTagName("InitialValue");
                if (valueElements.getLength() > 0) {
                    initialValue = valueElements.item(0).getTextContent().trim();
                }
            }

            // Check for Comment child element
            if (comment.isEmpty()) {
                NodeList commentElements = varElement.getElementsByTagName("Comment");
                if (commentElements.getLength() > 0) {
                    comment = commentElements.item(0).getTextContent().trim();
                }
            }

            JsonObject tag = new JsonObject();
            tag.addProperty("name", name);
            tag.addProperty("dataType", convertABBDataType(dataType));

            if (!initialValue.isEmpty()) {
                addValueProperty(tag, parseInitialValue(initialValue, dataType));
            } else {
                addValueProperty(tag, getDefaultValue(dataType));
            }

            if (!comment.isEmpty()) {
                tag.addProperty("description", comment);
            }

            logger.debug("Parsed ABB variable: {} ({})", name, dataType);
            return tag;

        } catch (Exception e) {
            logger.debug("Error parsing ABB variable", e);
            return null;
        }
    }

    /**
     * Parse data types (structs/UDTs).
     */
    private void parseDataTypes(Element root, Map<String, JsonObject> udtDefinitions, JsonObject result) {
        NodeList dataTypes = root.getElementsByTagName("DataType");

        if (dataTypes.getLength() > 0) {
            JsonArray udts = new JsonArray();

            logger.info("Found {} data types", dataTypes.getLength());

            for (int i = 0; i < dataTypes.getLength(); i++) {
                Element dtElement = (Element) dataTypes.item(i);
                JsonObject udt = parseDataType(dtElement);
                if (udt != null) {
                    udts.add(udt);
                    udtDefinitions.put(udt.get("name").getAsString(), udt);
                }
            }

            if (udts.size() > 0) {
                result.add("udts", udts);
                logger.info("Parsed {} UDT definitions", udts.size());
            }
        }
    }

    /**
     * Parse a single data type (UDT/struct).
     */
    private JsonObject parseDataType(Element dtElement) {
        try {
            String name = getAttribute(dtElement, "Name", null);
            if (name == null || name.isEmpty()) {
                NodeList nameElements = dtElement.getElementsByTagName("Name");
                if (nameElements.getLength() > 0) {
                    name = nameElements.item(0).getTextContent().trim();
                }
            }

            if (name == null || name.isEmpty()) {
                return null;
            }

            JsonObject udt = new JsonObject();
            udt.addProperty("name", name);

            JsonArray members = new JsonArray();

            // Parse members
            NodeList memberElements = dtElement.getElementsByTagName("Member");
            if (memberElements.getLength() == 0) {
                memberElements = dtElement.getElementsByTagName("Variable");
            }

            for (int i = 0; i < memberElements.getLength(); i++) {
                Element memberElement = (Element) memberElements.item(i);
                JsonObject member = parseVariable(memberElement);
                if (member != null) {
                    members.add(member);
                }
            }

            udt.add("members", members);
            logger.debug("Parsed ABB data type: {} with {} members", name, members.size());

            return udt;

        } catch (Exception e) {
            logger.warn("Error parsing ABB data type", e);
            return null;
        }
    }

    /**
     * Convert ABB/IEC 61131-3 data types to common format.
     */
    private String convertABBDataType(String abbType) {
        if (abbType == null) {
            return "BOOL";
        }

        String normalized = abbType.toUpperCase().trim();

        // Handle array types
        if (normalized.startsWith("ARRAY[") || normalized.contains("ARRAY OF")) {
            int ofIndex = normalized.indexOf(" OF ");
            if (ofIndex > 0) {
                String baseType = normalized.substring(ofIndex + 4).trim();
                return convertABBDataType(baseType);
            }
            return "DINT";
        }

        return switch (normalized) {
            case "BOOL", "BOOLEAN" -> "BOOL";
            case "BYTE", "USINT" -> "SINT";
            case "WORD", "UINT" -> "INT";
            case "DWORD", "UDINT" -> "DINT";
            case "LWORD", "ULINT" -> "LINT";
            case "SINT" -> "SINT";
            case "INT", "INTEGER" -> "INT";
            case "DINT" -> "DINT";
            case "LINT" -> "LINT";
            case "REAL" -> "REAL";
            case "LREAL" -> "LREAL";
            case "STRING" -> "STRING";
            case "WSTRING" -> "STRING";
            case "TIME" -> "DINT"; // Time as milliseconds
            case "DATE" -> "LINT"; // Date as timestamp
            case "DATE_AND_TIME", "DT" -> "LINT";
            case "TIME_OF_DAY", "TOD" -> "DINT";
            default -> {
                logger.debug("Unknown ABB data type: {}, defaulting to DINT", abbType);
                yield "DINT";
            }
        };
    }

    /**
     * Get default value for a data type.
     */
    private Object getDefaultValue(String dataType) {
        String converted = convertABBDataType(dataType);

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
            String converted = convertABBDataType(dataType);

            return switch (converted) {
                case "BOOL" -> valueStr.equalsIgnoreCase("TRUE") ||
                               valueStr.equalsIgnoreCase("1") ||
                               valueStr.equalsIgnoreCase("YES");
                case "SINT", "INT", "DINT" -> Integer.parseInt(valueStr.replaceAll("[^0-9-]", ""));
                case "LINT" -> Long.parseLong(valueStr.replaceAll("[^0-9-]", ""));
                case "REAL", "LREAL" -> Double.parseDouble(valueStr.replaceAll("[^0-9.\\-E]", ""));
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

        // ABB Automation Builder / Control Builder Plus files
        return (lowerFileName.endsWith(".xml") &&
                (lowerFileName.contains("abb") ||
                 lowerFileName.contains("automation") ||
                 lowerFileName.contains("ac800"))) ||
               lowerFileName.endsWith(".apj");
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
        return "abb";
    }
}
