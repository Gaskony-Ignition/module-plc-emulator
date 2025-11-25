package com.inductiveautomation.plcsimulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.inductiveautomation.plcsimulator.gateway.parser.DataTypeUtils.*;

/**
 * Parser for Omron PLC export files.
 *
 * Supports:
 * - CX-Programmer (.cxp, .opt) - Omron CJ/CS/CP/NJ series
 * - Sysmac Studio (.smc2) - Omron NX/NJ/NY series
 * - CSV symbol table exports (.csv)
 * - CIO/DM/HR area exports
 *
 * Omron uses device areas:
 * - CIO: Core I/O area (digital I/O)
 * - WR: Work area (internal relays)
 * - HR: Holding relays (retentive)
 * - AR: Auxiliary area
 * - DM: Data memory (word devices)
 * - TIM: Timers
 * - CNT: Counters
 *
 * Market share: ~6-7% global PLC market
 */
public class OmronParser implements PLCParser {

    private static final Logger logger = LoggerFactory.getLogger(OmronParser.class);

    // Pattern for CX-Programmer symbol table format
    // Format: SymbolName, Address, DataType, Comment
    private static final Pattern CX_SYMBOL_PATTERN = Pattern.compile(
        "^([A-Za-z_][A-Za-z0-9_]*)\\s*,\\s*([A-Za-z]+\\d+(?:\\.\\d+)?)\\s*,\\s*([A-Za-z0-9_]+)(?:\\s*,\\s*(.*))?$"
    );

    // Pattern for Sysmac Studio variable export
    // Format: Name, DataType, Address, InitialValue, Comment
    private static final Pattern SYSMAC_PATTERN = Pattern.compile(
        "^\"?([^\"]+)\"?\\s*,\\s*\"?([A-Za-z0-9_]+)\"?\\s*,\\s*\"?([^\"]+)?\"?\\s*,\\s*\"?([^\"]+)?\"?(?:\\s*,\\s*\"?([^\"]+)?\"?)?$"
    );

    // Pattern for device address parsing (e.g., D100, CIO0.00, W0.00)
    private static final Pattern DEVICE_ADDRESS_PATTERN = Pattern.compile(
        "^([A-Za-z]+)(\\d+)(?:\\.(\\d+))?$"
    );

    // Map Omron data types to standard types
    private static final Map<String, String> OMRON_TYPE_MAP = new HashMap<>();
    static {
        // Bit types
        OMRON_TYPE_MAP.put("BOOL", "BOOL");
        OMRON_TYPE_MAP.put("BIT", "BOOL");

        // Integer types
        OMRON_TYPE_MAP.put("INT", "INT");
        OMRON_TYPE_MAP.put("UINT", "INT");
        OMRON_TYPE_MAP.put("SINT", "SINT");
        OMRON_TYPE_MAP.put("USINT", "SINT");
        OMRON_TYPE_MAP.put("DINT", "DINT");
        OMRON_TYPE_MAP.put("UDINT", "DINT");
        OMRON_TYPE_MAP.put("LINT", "LINT");
        OMRON_TYPE_MAP.put("ULINT", "LINT");
        OMRON_TYPE_MAP.put("WORD", "INT");
        OMRON_TYPE_MAP.put("DWORD", "DINT");
        OMRON_TYPE_MAP.put("LWORD", "LINT");

        // Floating point
        OMRON_TYPE_MAP.put("REAL", "REAL");
        OMRON_TYPE_MAP.put("LREAL", "LREAL");

        // String
        OMRON_TYPE_MAP.put("STRING", "STRING");

        // Time types (treated as DINT)
        OMRON_TYPE_MAP.put("TIME", "DINT");
        OMRON_TYPE_MAP.put("DATE", "DINT");
        OMRON_TYPE_MAP.put("TOD", "DINT");
        OMRON_TYPE_MAP.put("DT", "DINT");
    }

    @Override
    public JsonObject parse(String filePath) {
        try {
            String content = Files.readString(Paths.get(filePath));
            return parseContent(content, filePath);
        } catch (Exception e) {
            logger.error("Error reading Omron file: {}", filePath, e);
            return null;
        }
    }

    @Override
    public JsonObject parseContent(String fileContent, String fileName) {
        if (fileContent == null || fileContent.trim().isEmpty()) {
            logger.warn("Empty content provided for Omron file: {}", fileName);
            return null;
        }

        try {
            logger.info("Parsing Omron file: {} ({} bytes)", fileName, fileContent.length());

            // Detect format by content and extension
            String lowerName = fileName.toLowerCase();

            if (lowerName.endsWith(".smc2") || fileContent.contains("<NXProjectData>")) {
                return parseSysmacXml(fileContent, fileName);
            } else if (lowerName.endsWith(".cxp") || lowerName.endsWith(".opt") ||
                       fileContent.contains("CX-Programmer")) {
                return parseCxProgrammer(fileContent, fileName);
            } else if (fileContent.contains(",") && fileContent.contains("\n")) {
                return parseCsvSymbolTable(fileContent, fileName);
            }

            // Default to CSV parsing
            return parseCsvSymbolTable(fileContent, fileName);

        } catch (Exception e) {
            logger.error("Error parsing Omron content", e);
            return null;
        }
    }

    /**
     * Parse Sysmac Studio XML format (.smc2 or exported XML).
     */
    private JsonObject parseSysmacXml(String content, String fileName) {
        try {
            JsonObject result = new JsonObject();
            result.addProperty("vendor", "omron");
            result.addProperty("format", "sysmac");

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Security: Disable external entities
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(content)));

            JsonArray globalTags = new JsonArray();

            // Parse global variables
            NodeList variables = doc.getElementsByTagName("Variable");
            for (int i = 0; i < variables.getLength(); i++) {
                Element var = (Element) variables.item(i);
                JsonObject tag = parseVariableElement(var);
                if (tag != null) {
                    globalTags.add(tag);
                }
            }

            // Also try "Symbol" elements
            NodeList symbols = doc.getElementsByTagName("Symbol");
            for (int i = 0; i < symbols.getLength(); i++) {
                Element sym = (Element) symbols.item(i);
                JsonObject tag = parseSymbolElement(sym);
                if (tag != null) {
                    globalTags.add(tag);
                }
            }

            if (globalTags.size() > 0) {
                result.add("global_tags", globalTags);
            }

            // Extract controller name
            NodeList controllers = doc.getElementsByTagName("Controller");
            if (controllers.getLength() > 0) {
                Element controller = (Element) controllers.item(0);
                String name = controller.getAttribute("Name");
                if (name != null && !name.isEmpty()) {
                    result.addProperty("controller", name);
                }
            }

            if (!result.has("controller")) {
                result.addProperty("controller", extractControllerName(fileName));
            }

            logger.info("Sysmac XML parsing complete: {} tags", globalTags.size());
            return result;

        } catch (Exception e) {
            logger.error("Error parsing Sysmac XML", e);
            return null;
        }
    }

    /**
     * Parse CX-Programmer project files.
     */
    private JsonObject parseCxProgrammer(String content, String fileName) {
        JsonObject result = new JsonObject();
        result.addProperty("vendor", "omron");
        result.addProperty("format", "cx-programmer");
        result.addProperty("controller", extractControllerName(fileName));

        JsonArray globalTags = new JsonArray();
        String[] lines = content.split("\\r?\\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith(";")) {
                continue;
            }

            // Try CX symbol table format
            Matcher matcher = CX_SYMBOL_PATTERN.matcher(line);
            if (matcher.find()) {
                String name = matcher.group(1);
                String address = matcher.group(2);
                String dataType = matcher.group(3);
                String comment = matcher.groupCount() > 3 ? matcher.group(4) : null;

                JsonObject tag = new JsonObject();
                tag.addProperty("name", name);
                tag.addProperty("data_type", normalizeOmronType(dataType));
                tag.addProperty("address", address);
                tag.addProperty("value", getDefaultValue(normalizeOmronType(dataType)));

                if (comment != null && !comment.trim().isEmpty()) {
                    tag.addProperty("description", comment.trim());
                }

                globalTags.add(tag);
            }
        }

        if (globalTags.size() > 0) {
            result.add("global_tags", globalTags);
        }

        logger.info("CX-Programmer parsing complete: {} tags", globalTags.size());
        return result;
    }

    /**
     * Parse CSV symbol table export.
     * Common format from both CX-Programmer and Sysmac Studio CSV exports.
     */
    private JsonObject parseCsvSymbolTable(String content, String fileName) {
        JsonObject result = new JsonObject();
        result.addProperty("vendor", "omron");
        result.addProperty("format", "csv");
        result.addProperty("controller", extractControllerName(fileName));

        JsonArray globalTags = new JsonArray();
        String[] lines = content.split("\\r?\\n");
        boolean headerSkipped = false;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // Skip header row
            if (!headerSkipped && (line.toLowerCase().contains("name") ||
                                    line.toLowerCase().contains("symbol") ||
                                    line.toLowerCase().contains("address"))) {
                headerSkipped = true;
                continue;
            }

            // Try Sysmac format first (more columns)
            Matcher sysmacMatcher = SYSMAC_PATTERN.matcher(line);
            if (sysmacMatcher.find()) {
                String name = sysmacMatcher.group(1);
                String dataType = sysmacMatcher.group(2);
                String address = sysmacMatcher.group(3);
                String initialValue = sysmacMatcher.group(4);
                String comment = sysmacMatcher.groupCount() > 4 ? sysmacMatcher.group(5) : null;

                JsonObject tag = createTag(name, dataType, address, initialValue, comment);
                if (tag != null) {
                    globalTags.add(tag);
                }
                continue;
            }

            // Try CX-Programmer format
            Matcher cxMatcher = CX_SYMBOL_PATTERN.matcher(line);
            if (cxMatcher.find()) {
                String name = cxMatcher.group(1);
                String address = cxMatcher.group(2);
                String dataType = cxMatcher.group(3);
                String comment = cxMatcher.groupCount() > 3 ? cxMatcher.group(4) : null;

                JsonObject tag = createTag(name, dataType, address, null, comment);
                if (tag != null) {
                    globalTags.add(tag);
                }
                continue;
            }

            // Try simple CSV format: Name,Address or Name,DataType
            String[] parts = line.split(",");
            if (parts.length >= 2) {
                String name = parts[0].trim().replace("\"", "");
                String second = parts[1].trim().replace("\"", "");

                // Determine if second field is address or data type
                String dataType = "WORD"; // Default Omron type
                String address = null;

                if (DEVICE_ADDRESS_PATTERN.matcher(second).matches()) {
                    address = second;
                    dataType = inferTypeFromAddress(second);
                } else {
                    dataType = second;
                }

                if (parts.length >= 3) {
                    String third = parts[2].trim().replace("\"", "");
                    if (address == null && DEVICE_ADDRESS_PATTERN.matcher(third).matches()) {
                        address = third;
                    }
                }

                if (!name.isEmpty() && !name.toLowerCase().equals("name")) {
                    JsonObject tag = createTag(name, dataType, address, null, null);
                    if (tag != null) {
                        globalTags.add(tag);
                    }
                }
            }
        }

        if (globalTags.size() > 0) {
            result.add("global_tags", globalTags);
        }

        logger.info("CSV symbol table parsing complete: {} tags", globalTags.size());
        return result;
    }

    private JsonObject parseVariableElement(Element elem) {
        String name = elem.getAttribute("Name");
        if (name == null || name.isEmpty()) {
            name = getTextContent(elem, "Name");
        }

        String dataType = elem.getAttribute("DataType");
        if (dataType == null || dataType.isEmpty()) {
            dataType = getTextContent(elem, "DataType");
        }

        if (name == null || name.isEmpty()) {
            return null;
        }

        JsonObject tag = new JsonObject();
        tag.addProperty("name", name);
        tag.addProperty("data_type", normalizeOmronType(dataType != null ? dataType : "WORD"));
        tag.addProperty("value", getDefaultValue(normalizeOmronType(dataType != null ? dataType : "WORD")));

        String address = elem.getAttribute("Address");
        if (address != null && !address.isEmpty()) {
            tag.addProperty("address", address);
        }

        String comment = getTextContent(elem, "Comment");
        if (comment != null && !comment.isEmpty()) {
            tag.addProperty("description", comment);
        }

        return tag;
    }

    private JsonObject parseSymbolElement(Element elem) {
        return parseVariableElement(elem); // Same structure
    }

    private String getTextContent(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent();
        }
        return null;
    }

    private JsonObject createTag(String name, String dataType, String address,
                                  String initialValue, String comment) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }

        JsonObject tag = new JsonObject();
        tag.addProperty("name", name.trim());
        tag.addProperty("data_type", normalizeOmronType(dataType));

        if (address != null && !address.trim().isEmpty()) {
            tag.addProperty("address", address.trim());
        }

        // Set initial value
        if (initialValue != null && !initialValue.trim().isEmpty()) {
            tag.addProperty("value", initialValue.trim());
        } else {
            tag.addProperty("value", getDefaultValue(normalizeOmronType(dataType)));
        }

        if (comment != null && !comment.trim().isEmpty()) {
            tag.addProperty("description", comment.trim());
        }

        return tag;
    }

    private String normalizeOmronType(String omronType) {
        if (omronType == null || omronType.isEmpty()) {
            return "DINT";
        }

        String upper = omronType.toUpperCase().trim();
        return OMRON_TYPE_MAP.getOrDefault(upper, normalizeDataType(upper));
    }

    /**
     * Infer data type from Omron device address.
     */
    private String inferTypeFromAddress(String address) {
        if (address == null) return "DINT";

        Matcher matcher = DEVICE_ADDRESS_PATTERN.matcher(address.toUpperCase());
        if (matcher.find()) {
            String device = matcher.group(1);
            boolean hasBit = matcher.group(3) != null;

            return switch (device) {
                // Bit devices (or word.bit notation)
                case "CIO", "W", "WR", "HR", "AR" -> hasBit ? "BOOL" : "INT";
                // Word devices
                case "D", "DM" -> "DINT";
                // Timers/Counters
                case "T", "TIM", "C", "CNT" -> "DINT";
                // Index registers
                case "IR" -> "DINT";
                default -> "DINT";
            };
        }
        return "DINT";
    }

    private String extractControllerName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "OmronPLC";
        }

        String name = fileName;
        int lastSep = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        if (lastSep >= 0) {
            name = fileName.substring(lastSep + 1);
        }

        int dotPos = name.lastIndexOf('.');
        if (dotPos > 0) {
            name = name.substring(0, dotPos);
        }

        return name.isEmpty() ? "OmronPLC" : name;
    }

    @Override
    public boolean canHandle(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".cxp") ||      // CX-Programmer project
               lower.endsWith(".opt") ||      // CX-Programmer options/symbols
               lower.endsWith(".smc2") ||     // Sysmac Studio project
               lower.endsWith(".cxf") ||      // CX-One file
               (lower.contains("omron") && (lower.endsWith(".csv") || lower.endsWith(".xml")));
    }

    @Override
    public String getParserType() {
        return "omron";
    }
}
