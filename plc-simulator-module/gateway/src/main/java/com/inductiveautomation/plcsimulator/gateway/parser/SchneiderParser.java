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

import static com.inductiveautomation.plcsimulator.gateway.parser.DataTypeUtils.*;

/**
 * Parser for Schneider Electric Unity Pro / EcoStruxure files.
 *
 * Supports:
 * - Unity Pro XML export (M340, M580, Quantum)
 * - EcoStruxure Control Expert export
 * - CSV variable lists
 * - XEF project exports
 *
 * Parses:
 * - Located variables (%M, %I, %Q, %MW, etc.)
 * - Unlocated variables
 * - Function blocks
 * - Derived data types (DDT)
 */
public class SchneiderParser implements PLCParser {

    private static final Logger logger = LoggerFactory.getLogger(SchneiderParser.class);

    @Override
    public JsonObject parse(String filePath) {
        try {
            String content = Files.readString(Paths.get(filePath));
            String fileName = Paths.get(filePath).getFileName().toString();

            // Determine format
            if (fileName.toLowerCase().endsWith(".csv")) {
                return parseCsv(content, fileName);
            } else {
                return parseXml(content, fileName);
            }

        } catch (Exception e) {
            logger.error("Error reading Schneider file: {}", filePath, e);
            return null;
        }
    }

    @Override
    public JsonObject parseContent(String fileContent, String fileName) {
        try {
            if (fileName.toLowerCase().endsWith(".csv")) {
                return parseCsv(fileContent, fileName);
            } else {
                return parseXml(fileContent, fileName);
            }
        } catch (Exception e) {
            logger.error("Error parsing Schneider content", e);
            return null;
        }
    }

    /**
     * Parse XML format (Unity Pro XML export or XEF).
     */
    private JsonObject parseXml(String fileContent, String fileName) {
        try {
            // Parse XML with XXE protection
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);

            // Disable XXE features
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(fileContent.getBytes()));

            JsonObject result = new JsonObject();
            Element root = doc.getDocumentElement();

            result.addProperty("vendor", "schneider");
            result.addProperty("format", "unity_pro");

            logger.info("Parsing Schneider Unity Pro XML, root element: {}", root.getNodeName());

            JsonArray globalTags = new JsonArray();

            // Parse variables from different sections
            // Unity Pro structure: Project/Variables/Variable
            parseVariables(root, globalTags);

            // Parse data types (DDT - Derived Data Types)
            parseDerivedDataTypes(root, result);

            result.add("global_tags", globalTags);
            logger.info("Parsed {} global tags from Schneider Unity Pro", globalTags.size());

            return result;

        } catch (Exception e) {
            logger.error("Error parsing Schneider XML", e);
            return null;
        }
    }

    /**
     * Parse CSV variable list export.
     * Common CSV format from Unity Pro variable list export.
     */
    private JsonObject parseCsv(String fileContent, String fileName) {
        try {
            JsonObject result = new JsonObject();
            result.addProperty("vendor", "schneider");
            result.addProperty("format", "unity_csv");

            JsonArray globalTags = new JsonArray();

            String[] lines = fileContent.split("\\r?\\n");
            boolean headerPassed = false;

            for (String line : lines) {
                line = line.trim();

                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                    continue;
                }

                // Skip header row (usually contains "Name", "Type", "Address", etc.)
                if (!headerPassed && line.toLowerCase().contains("name")) {
                    headerPassed = true;
                    logger.debug("Skipping header: {}", line);
                    continue;
                }

                // Parse CSV line
                JsonObject tag = parseCsvLine(line);
                if (tag != null) {
                    globalTags.add(tag);
                }
            }

            result.add("global_tags", globalTags);
            logger.info("Parsed {} tags from Schneider CSV", globalTags.size());

            return result;

        } catch (Exception e) {
            logger.error("Error parsing Schneider CSV", e);
            return null;
        }
    }

    /**
     * Parse variables from XML document.
     */
    private void parseVariables(Element root, JsonArray globalTags) {
        // Unity Pro: Look for Variable elements
        NodeList variables = root.getElementsByTagName("Variable");

        logger.info("Found {} variables in Schneider XML", variables.getLength());

        for (int i = 0; i < variables.getLength(); i++) {
            Element varElement = (Element) variables.item(i);
            JsonObject tag = parseXmlVariable(varElement);
            if (tag != null) {
                globalTags.add(tag);
            }
        }

        // Also check for UnityVariable elements (alternate format)
        NodeList unityVars = root.getElementsByTagName("UnityVariable");
        for (int i = 0; i < unityVars.getLength(); i++) {
            Element varElement = (Element) unityVars.item(i);
            JsonObject tag = parseXmlVariable(varElement);
            if (tag != null) {
                globalTags.add(tag);
            }
        }
    }

    /**
     * Parse derived data types (DDT).
     */
    private void parseDerivedDataTypes(Element root, JsonObject result) {
        NodeList ddtElements = root.getElementsByTagName("DDTType");

        if (ddtElements.getLength() > 0) {
            JsonArray ddts = new JsonArray();

            for (int i = 0; i < ddtElements.getLength(); i++) {
                Element ddtElement = (Element) ddtElements.item(i);
                JsonObject ddt = parseDDT(ddtElement);
                if (ddt != null) {
                    ddts.add(ddt);
                }
            }

            if (ddts.size() > 0) {
                result.add("udts", ddts);
                logger.info("Parsed {} DDT definitions", ddts.size());
            }
        }
    }

    /**
     * Parse an XML variable element.
     */
    private JsonObject parseXmlVariable(Element varElement) {
        try {
            String name = getAttribute(varElement, "Name", null);
            if (name == null || name.isEmpty()) {
                return null;
            }

            String dataType = getAttribute(varElement, "Type", "BOOL");
            String address = getAttribute(varElement, "Address", "");
            String comment = getAttribute(varElement, "Comment", "");

            JsonObject tag = new JsonObject();
            tag.addProperty("name", name);
            tag.addProperty("dataType", convertSchneiderDataType(dataType));
            addValueProperty(tag, getDefaultValue(dataType));

            if (!address.isEmpty()) {
                tag.addProperty("address", address);
            }

            if (!comment.isEmpty()) {
                tag.addProperty("description", comment);
            }

            logger.debug("Parsed XML variable: {} ({})", name, dataType);
            return tag;

        } catch (Exception e) {
            logger.warn("Error parsing XML variable", e);
            return null;
        }
    }

    /**
     * Parse a CSV line into a tag.
     * Expected format: Name,Type,Address,InitialValue,Comment
     */
    private JsonObject parseCsvLine(String line) {
        try {
            // Handle quoted fields (CSV with commas in fields)
            String[] fields = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);

            if (fields.length < 2) {
                logger.debug("Skipping malformed CSV line: {}", line);
                return null;
            }

            String name = unquote(fields[0].trim());
            String dataType = unquote(fields[1].trim());

            if (name.isEmpty() || dataType.isEmpty()) {
                return null;
            }

            JsonObject tag = new JsonObject();
            tag.addProperty("name", name);
            tag.addProperty("dataType", convertSchneiderDataType(dataType));

            // Optional address field
            if (fields.length > 2 && !fields[2].trim().isEmpty()) {
                tag.addProperty("address", unquote(fields[2].trim()));
            }

            // Optional initial value
            if (fields.length > 3 && !fields[3].trim().isEmpty()) {
                addValueProperty(tag, parseValue(unquote(fields[3].trim()), dataType));
            } else {
                addValueProperty(tag, getDefaultValue(dataType));
            }

            // Optional comment
            if (fields.length > 4 && !fields[4].trim().isEmpty()) {
                tag.addProperty("description", unquote(fields[4].trim()));
            }

            logger.debug("Parsed CSV variable: {} ({})", name, dataType);
            return tag;

        } catch (Exception e) {
            logger.warn("Error parsing CSV line: {}", line, e);
            return null;
        }
    }

    /**
     * Parse a DDT (Derived Data Type).
     */
    private JsonObject parseDDT(Element ddtElement) {
        try {
            String name = getAttribute(ddtElement, "Name", null);
            if (name == null || name.isEmpty()) {
                return null;
            }

            JsonObject ddt = new JsonObject();
            ddt.addProperty("name", name);

            JsonArray members = new JsonArray();

            // Parse DDT members
            NodeList memberElements = ddtElement.getElementsByTagName("Member");
            for (int i = 0; i < memberElements.getLength(); i++) {
                Element memberElement = (Element) memberElements.item(i);

                String memberName = getAttribute(memberElement, "Name", null);
                String memberType = getAttribute(memberElement, "Type", "BOOL");

                if (memberName != null && !memberName.isEmpty()) {
                    JsonObject member = new JsonObject();
                    member.addProperty("name", memberName);
                    member.addProperty("dataType", convertSchneiderDataType(memberType));
                    members.add(member);
                }
            }

            ddt.add("members", members);
            logger.debug("Parsed DDT: {} with {} members", name, members.size());

            return ddt;

        } catch (Exception e) {
            logger.warn("Error parsing DDT", e);
            return null;
        }
    }

    /**
     * Convert Schneider data types to common format.
     */
    private String convertSchneiderDataType(String schneiderType) {
        if (schneiderType == null) {
            return "BOOL";
        }

        String normalized = schneiderType.toUpperCase().trim();

        // Handle array types (ARRAY[1..10] OF INT)
        if (normalized.startsWith("ARRAY")) {
            int ofIndex = normalized.indexOf(" OF ");
            if (ofIndex > 0) {
                String baseType = normalized.substring(ofIndex + 4).trim();
                return convertSchneiderDataType(baseType);
            }
            return "DINT";
        }

        return switch (normalized) {
            case "BOOL", "EBOOL" -> "BOOL";
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
            case "TIME" -> "DINT";  // Time as milliseconds
            case "DATE", "DT", "TOD" -> "LINT";  // Date/time as timestamp
            case "CHAR" -> "STRING";
            default -> {
                logger.debug("Unknown Schneider data type: {}, defaulting to DINT", schneiderType);
                yield "DINT";
            }
        };
    }

    /**
     * Get default value for a data type.
     */
    private Object getDefaultValue(String dataType) {
        String converted = convertSchneiderDataType(dataType);

        return switch (converted) {
            case "BOOL" -> false;
            case "SINT", "INT", "DINT", "LINT" -> 0;
            case "REAL", "LREAL" -> 0.0;
            case "STRING" -> "";
            default -> 0;
        };
    }

    /**
     * Parse a value from string based on data type.
     */
    private Object parseValue(String valueStr, String dataType) {
        try {
            String converted = convertSchneiderDataType(dataType);

            return switch (converted) {
                case "BOOL" -> Boolean.parseBoolean(valueStr) || valueStr.equals("1");
                case "SINT", "INT", "DINT" -> Integer.parseInt(valueStr);
                case "LINT" -> Long.parseLong(valueStr);
                case "REAL", "LREAL" -> Double.parseDouble(valueStr);
                case "STRING" -> valueStr;
                default -> 0;
            };
        } catch (Exception e) {
            logger.debug("Error parsing value '{}' for type {}, using default", valueStr, dataType);
            return getDefaultValue(dataType);
        }
    }

    /**
     * Remove quotes from a string.
     */
    private String unquote(String str) {
        if (str.startsWith("\"") && str.endsWith("\"")) {
            return str.substring(1, str.length() - 1);
        }
        return str;
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

        // Schneider Unity Pro / EcoStruxure files
        return (lowerFileName.endsWith(".xml") &&
                (lowerFileName.contains("schneider") ||
                 lowerFileName.contains("unity") ||
                 lowerFileName.contains("m340") ||
                 lowerFileName.contains("m580"))) ||
               (lowerFileName.endsWith(".csv") &&
                (lowerFileName.contains("schneider") ||
                 lowerFileName.contains("unity"))) ||
               lowerFileName.endsWith(".xef");
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
        return "schneider";
    }
}
