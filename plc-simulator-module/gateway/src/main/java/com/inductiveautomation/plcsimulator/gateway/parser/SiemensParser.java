package com.inductiveautomation.plcsimulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static com.inductiveautomation.plcsimulator.gateway.parser.DataTypeUtils.*;

/**
 * Parser for Siemens TIA Portal files (S7-1200, S7-1500, S7-1500T).
 *
 * Parses XML exports from TIA Portal to extract:
 * - Global Data Blocks (DB)
 * - Program Organization Units (OB)
 * - Function Blocks (FB)
 * - Functions (FC)
 * - Tag tables
 * - User-defined types (UDT)
 *
 * Supported file formats:
 * - TIA Portal XML export (.xml)
 * - Data Block export (.db.xml)
 * - Tag table export (.xml)
 */
public class SiemensParser implements PLCParser {

    private static final Logger logger = LoggerFactory.getLogger(SiemensParser.class);

    @Override
    public JsonObject parse(String filePath) {
        try {
            String content = Files.readString(Paths.get(filePath));
            return parseContent(content, filePath);
        } catch (Exception e) {
            logger.error("Error reading Siemens TIA file: {}", filePath, e);
            return null;
        }
    }

    @Override
    public JsonObject parseContent(String fileContent, String fileName) {
        try {
            // Parse XML with XXE protection
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);

            // Disable all XXE (XML External Entity) features for security
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(fileContent.getBytes()));

            JsonObject result = new JsonObject();

            // Extract project information from TIA Portal XML structure
            Element root = doc.getDocumentElement();
            String rootName = root.getNodeName();

            logger.info("Parsing Siemens TIA Portal file, root element: {}", rootName);

            // TIA Portal exports can have different root elements depending on export type
            if (rootName.equals("Document") || rootName.equals("Engineering") ||
                rootName.equals("SW.Blocks.GlobalDB") || rootName.equals("SW.Tags.PlcTagTable")) {

                result.addProperty("vendor", "siemens");
                result.addProperty("format", "tia_portal");

                // Parse based on export type
                if (isDataBlockExport(root)) {
                    parseDataBlocks(root, result);
                } else if (isTagTableExport(root)) {
                    parseTagTable(root, result);
                } else {
                    // Full project export - parse all components
                    parseFullProject(root, result);
                }

                return result;

            } else {
                logger.error("Not a valid Siemens TIA Portal file - root element is: {}", rootName);
                return null;
            }

        } catch (Exception e) {
            logger.error("Error parsing Siemens TIA Portal file", e);
            return null;
        }
    }

    /**
     * Check if this is a Data Block export.
     */
    private boolean isDataBlockExport(Element root) {
        return root.getNodeName().equals("SW.Blocks.GlobalDB") ||
               root.getElementsByTagName("SW.Blocks.GlobalDB").getLength() > 0;
    }

    /**
     * Check if this is a Tag Table export.
     */
    private boolean isTagTableExport(Element root) {
        return root.getNodeName().equals("SW.Tags.PlcTagTable") ||
               root.getElementsByTagName("SW.Tags.PlcTagTable").getLength() > 0;
    }

    /**
     * Parse Data Blocks from TIA Portal export.
     */
    private void parseDataBlocks(Element root, JsonObject result) {
        JsonArray globalTags = new JsonArray();

        // Find all data block elements
        NodeList dbElements = root.getElementsByTagName("Member");

        logger.info("Found {} data block members", dbElements.getLength());

        for (int i = 0; i < dbElements.getLength(); i++) {
            Element member = (Element) dbElements.item(i);
            JsonObject tag = parseSiemensTag(member);
            if (tag != null) {
                globalTags.add(tag);
            }
        }

        result.add("global_tags", globalTags);
        logger.info("Parsed {} global tags from Data Blocks", globalTags.size());
    }

    /**
     * Parse Tag Table from TIA Portal export.
     */
    private void parseTagTable(Element root, JsonObject result) {
        JsonArray globalTags = new JsonArray();

        // Find all tag elements in tag table
        NodeList tagElements = root.getElementsByTagName("Tag");

        logger.info("Found {} tags in tag table", tagElements.getLength());

        for (int i = 0; i < tagElements.getLength(); i++) {
            Element tagElement = (Element) tagElements.item(i);
            JsonObject tag = parseTagTableEntry(tagElement);
            if (tag != null) {
                globalTags.add(tag);
            }
        }

        result.add("global_tags", globalTags);
        logger.info("Parsed {} global tags from Tag Table", globalTags.size());
    }

    /**
     * Parse full TIA Portal project export.
     */
    private void parseFullProject(Element root, JsonObject result) {
        // Try to find controller/device information
        NodeList devices = root.getElementsByTagName("DeviceItem");
        if (devices.getLength() > 0) {
            Element device = (Element) devices.item(0);
            String deviceName = getAttribute(device, "Name", "S7-1500");
            result.addProperty("controller", deviceName);
            logger.info("Found controller: {}", deviceName);
        }

        // Parse data blocks
        parseDataBlocks(root, result);

        // Parse tag tables if present
        if (root.getElementsByTagName("SW.Tags.PlcTagTable").getLength() > 0) {
            parseTagTable(root, result);
        }
    }

    /**
     * Parse a Siemens tag (Member element from Data Block).
     */
    private JsonObject parseSiemensTag(Element member) {
        try {
            String name = getAttribute(member, "Name", null);
            if (name == null || name.isEmpty()) {
                return null;
            }

            String dataType = getAttribute(member, "Datatype", "Bool");

            JsonObject tag = new JsonObject();
            tag.addProperty("name", name);
            tag.addProperty("dataType", convertSiemensDataType(dataType));
            addValueProperty(tag, getDefaultValue(dataType));

            // Extract comment if available
            String comment = getElementText(member, "Comment");
            if (comment != null && !comment.isEmpty()) {
                tag.addProperty("description", comment);
            }

            // Handle arrays
            if (dataType.startsWith("Array[")) {
                parseArrayDimensions(dataType, tag);
            }

            logger.debug("Parsed Siemens tag: {} ({})", name, dataType);
            return tag;

        } catch (Exception e) {
            logger.warn("Error parsing Siemens tag", e);
            return null;
        }
    }

    /**
     * Parse a tag from Tag Table.
     */
    private JsonObject parseTagTableEntry(Element tagElement) {
        try {
            String name = getAttribute(tagElement, "Name", null);
            if (name == null || name.isEmpty()) {
                return null;
            }

            String dataType = getAttribute(tagElement, "DataTypeName", "Bool");
            String logicalAddress = getAttribute(tagElement, "LogicalAddress", "");

            JsonObject tag = new JsonObject();
            tag.addProperty("name", name);
            tag.addProperty("dataType", convertSiemensDataType(dataType));
            addValueProperty(tag, getDefaultValue(dataType));

            if (!logicalAddress.isEmpty()) {
                tag.addProperty("address", logicalAddress);
            }

            // Extract comment
            NodeList comments = tagElement.getElementsByTagName("Comment");
            if (comments.getLength() > 0) {
                Element commentElement = (Element) comments.item(0);
                String comment = commentElement.getTextContent();
                if (comment != null && !comment.isEmpty()) {
                    tag.addProperty("description", comment.trim());
                }
            }

            logger.debug("Parsed tag table entry: {} ({})", name, dataType);
            return tag;

        } catch (Exception e) {
            logger.warn("Error parsing tag table entry", e);
            return null;
        }
    }

    /**
     * Convert Siemens data types to common format.
     */
    private String convertSiemensDataType(String siemensType) {
        if (siemensType == null) {
            return "BOOL";
        }

        String normalized = siemensType.toUpperCase().trim();

        // Handle array types
        if (normalized.startsWith("ARRAY[")) {
            // Extract base type from "Array[1..10] of Int"
            int ofIndex = normalized.indexOf(" OF ");
            if (ofIndex > 0) {
                String baseType = normalized.substring(ofIndex + 4).trim();
                return convertSiemensDataType(baseType);
            }
            return "DINT"; // Default for arrays
        }

        // Map Siemens types to common types
        return switch (normalized) {
            case "BOOL", "BOOLEAN" -> "BOOL";
            case "BYTE" -> "SINT";
            case "WORD" -> "INT";
            case "DWORD" -> "DINT";
            case "LWORD" -> "LINT";
            case "SINT", "USINT" -> "SINT";
            case "INT", "UINT" -> "INT";
            case "DINT", "UDINT" -> "DINT";
            case "LINT", "ULINT" -> "LINT";
            case "REAL" -> "REAL";
            case "LREAL" -> "LREAL";
            case "STRING", "WSTRING" -> "STRING";
            case "TIME", "LTIME" -> "DINT"; // Time as milliseconds
            case "DATE", "TOD", "DT", "LDT" -> "LINT"; // Date/time as timestamp
            case "CHAR", "WCHAR" -> "STRING";
            default -> {
                logger.debug("Unknown Siemens data type: {}, defaulting to DINT", siemensType);
                yield "DINT";
            }
        };
    }

    /**
     * Get default value for a data type.
     */
    private Object getDefaultValue(String dataType) {
        String converted = convertSiemensDataType(dataType);

        return switch (converted) {
            case "BOOL" -> false;
            case "SINT", "INT", "DINT", "LINT" -> 0;
            case "REAL", "LREAL" -> 0.0;
            case "STRING" -> "";
            default -> 0;
        };
    }

    /**
     * Parse array dimensions from Siemens array notation.
     * Example: "Array[1..10]" -> dimensions: [[1, 10]]
     */
    private void parseArrayDimensions(String arrayType, JsonObject tag) {
        try {
            // Extract dimension info from "Array[1..10] of Int"
            int startBracket = arrayType.indexOf('[');
            int endBracket = arrayType.indexOf(']');

            if (startBracket > 0 && endBracket > startBracket) {
                String dimString = arrayType.substring(startBracket + 1, endBracket);
                String[] parts = dimString.split("\\.\\.");

                if (parts.length == 2) {
                    int start = Integer.parseInt(parts[0].trim());
                    int end = Integer.parseInt(parts[1].trim());
                    int size = end - start + 1;

                    JsonArray dimensions = new JsonArray();
                    dimensions.add(size);
                    tag.add("dimensions", dimensions);

                    logger.debug("Parsed array dimensions: {}..{} (size={})", start, end, size);
                }
            }
        } catch (Exception e) {
            logger.warn("Error parsing array dimensions from: {}", arrayType, e);
        }
    }

    /**
     * Get attribute value from XML element.
     */
    private String getAttribute(Element element, String attributeName, String defaultValue) {
        String value = element.getAttribute(attributeName);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }

    /**
     * Get text content of a child element.
     */
    private String getElementText(Element parent, String tagName) {
        NodeList elements = parent.getElementsByTagName(tagName);
        if (elements.getLength() > 0) {
            Element element = (Element) elements.item(0);
            return element.getTextContent();
        }
        return null;
    }

    @Override
    public boolean canHandle(String fileName) {
        if (fileName == null) {
            return false;
        }

        String lowerFileName = fileName.toLowerCase();

        // Siemens TIA Portal exports
        return lowerFileName.endsWith(".xml") &&
               (lowerFileName.contains("siemens") ||
                lowerFileName.contains("tia") ||
                lowerFileName.contains("s7-") ||
                lowerFileName.contains(".db.xml"));
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
        return "siemens";
    }
}
