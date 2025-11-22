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
 * Parser for Rockwell L5X/L5K files (Studio 5000 / RSLogix 5000).
 *
 * Parses the XML structure to extract:
 * - Controller information
 * - Controller-scoped tags
 * - Program-scoped tags
 * - User Defined Types (UDTs)
 * - Arrays and complex structures
 *
 * L5X is XML-based, L5K is the same format.
 */
public class L5XParser implements PLCParser {

    private static final Logger logger = LoggerFactory.getLogger(L5XParser.class);

    @Override
    public JsonObject parse(String filePath) {
        try {
            String content = Files.readString(Paths.get(filePath));
            return parseContent(content, filePath);
        } catch (Exception e) {
            logger.error("Error reading L5X file: {}", filePath, e);
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

            // Extract controller information
            Element root = doc.getDocumentElement();
            if (!root.getNodeName().equals("RSLogix5000Content")) {
                logger.error("Not a valid L5X file - root element is: {}", root.getNodeName());
                return null;
            }

            // Get controller element
            NodeList controllers = root.getElementsByTagName("Controller");
            if (controllers.getLength() == 0) {
                logger.error("No Controller element found in L5X file");
                return null;
            }

            Element controller = (Element) controllers.item(0);
            String controllerName = controller.getAttribute("Name");
            String processorType = controller.getAttribute("ProcessorType");

            result.addProperty("controller", controllerName);
            result.addProperty("vendor", "rockwell");
            result.addProperty("processorType", processorType);

            // Parse User Defined Types (UDTs) FIRST - we need these to expand UDT instances
            Map<String, JsonObject> udtDefinitions = new HashMap<>();
            JsonArray udts = new JsonArray();
            NodeList udtElements = controller.getElementsByTagName("DataType");
            for (int i = 0; i < udtElements.getLength(); i++) {
                Element udtElement = (Element) udtElements.item(i);
                JsonObject udt = parseUDT(udtElement);
                if (udt != null) {
                    udts.add(udt);
                    udtDefinitions.put(udt.get("name").getAsString(), udt);
                }
            }

            if (udts.size() > 0) {
                result.add("udts", udts);
            }

            // Parse controller tags
            JsonArray controllerTags = new JsonArray();
            NodeList tagElements = controller.getElementsByTagName("Tag");
            for (int i = 0; i < tagElements.getLength(); i++) {
                Element tagElement = (Element) tagElements.item(i);

                // Skip tags that are inside programs (we'll get those separately)
                if (isChildOf(tagElement, "Program")) {
                    continue;
                }

                JsonObject tag = parseTag(tagElement, udtDefinitions);  // Pass UDT definitions for expansion
                if (tag != null) {
                    tag.addProperty("scope", "Controller");
                    controllerTags.add(tag);
                }
            }

            result.add("global_tags", controllerTags);  // Fixed: Changed from "tags" to "global_tags" to match AddressSpaceBuilder expectations

            // Parse programs
            JsonArray programs = new JsonArray();
            NodeList programElements = controller.getElementsByTagName("Program");
            for (int i = 0; i < programElements.getLength(); i++) {
                Element programElement = (Element) programElements.item(i);
                JsonObject program = parseProgram(programElement, udtDefinitions);  // Pass UDT definitions for expansion
                if (program != null) {
                    programs.add(program);
                }
            }

            result.add("programs", programs);

            int totalTags = controllerTags.size();
            for (int i = 0; i < programs.size(); i++) {
                JsonObject prog = programs.get(i).getAsJsonObject();
                if (prog.has("tags")) {
                    totalTags += prog.getAsJsonArray("tags").size();
                }
            }

            logger.info("Successfully parsed L5X file: {} ({} controller tags, {} programs, {} UDTs, {} total tags)",
                    fileName, controllerTags.size(), programs.size(), udts.size(), totalTags);

            return result;

        } catch (Exception e) {
            logger.error("Error parsing L5X content from: {}", fileName, e);
            return null;
        }
    }

    /**
     * Parse a single tag element.
     * @param udtDefinitions Map of UDT definitions for expanding UDT instances
     */
    private JsonObject parseTag(Element tagElement, Map<String, JsonObject> udtDefinitions) {
        try {
            JsonObject tag = new JsonObject();

            String name = tagElement.getAttribute("Name");
            String dataType = tagElement.getAttribute("DataType");
            String usage = tagElement.getAttribute("Usage");
            String constant = tagElement.getAttribute("Constant");

            tag.addProperty("name", name);
            tag.addProperty("data_type", dataType);  // Fixed: Changed from "type" to "data_type" to match AddressSpaceBuilder expectations

            if (usage != null && !usage.isEmpty()) {
                tag.addProperty("usage", usage);
            }

            if ("true".equalsIgnoreCase(constant)) {
                tag.addProperty("constant", true);
            }

            // Check for array dimensions
            String dimensions = tagElement.getAttribute("Dimensions");
            if (dimensions != null && !dimensions.isEmpty()) {
                tag.addProperty("dimensions", dimensions);
                tag.addProperty("isArray", true);
            }

            // Check if this is a UDT instance and expand it
            if (udtDefinitions.containsKey(dataType)) {
                JsonObject udtDef = udtDefinitions.get(dataType);
                if (udtDef.has("members")) {
                    JsonArray members = udtDef.getAsJsonArray("members");
                    JsonArray udtMembers = new JsonArray();

                    // Copy member definitions to create UDT instance members
                    for (int i = 0; i < members.size(); i++) {
                        JsonObject memberDef = members.get(i).getAsJsonObject();
                        JsonObject member = new JsonObject();

                        member.addProperty("name", memberDef.get("name").getAsString());
                        member.addProperty("data_type", memberDef.get("data_type").getAsString());

                        if (memberDef.has("dimensions")) {
                            member.addProperty("dimensions", memberDef.get("dimensions").getAsString());
                        }

                        // Set default initial value based on data type
                        member.addProperty("initial_value", getDefaultValue(memberDef.get("data_type").getAsString()));

                        udtMembers.add(member);
                    }

                    tag.add("udt_members", udtMembers);
                    logger.debug("Expanded UDT instance: {} of type {} with {} members", name, dataType, udtMembers.size());
                }
            }

            // Get description from Comments element
            NodeList comments = tagElement.getElementsByTagName("Description");
            if (comments.getLength() > 0) {
                Element descElement = (Element) comments.item(0);
                String description = descElement.getTextContent();
                if (description != null && !description.trim().isEmpty()) {
                    tag.addProperty("description", description.trim());
                }
            }

            // Try to extract initial value
            NodeList dataElements = tagElement.getElementsByTagName("Data");
            if (dataElements.getLength() > 0) {
                Element dataElement = (Element) dataElements.item(0);
                String value = extractValue(dataElement, dataType);
                if (value != null) {
                    tag.addProperty("initial_value", value);
                }
            }

            return tag;

        } catch (Exception e) {
            logger.error("Error parsing tag element", e);
            return null;
        }
    }


    /**
     * Parse a program element.
     * @param udtDefinitions Map of UDT definitions for expanding UDT instances
     */
    private JsonObject parseProgram(Element programElement, Map<String, JsonObject> udtDefinitions) {
        try {
            JsonObject program = new JsonObject();

            String name = programElement.getAttribute("Name");
            program.addProperty("name", name);

            JsonArray programTags = new JsonArray();
            NodeList tagElements = programElement.getElementsByTagName("Tag");

            for (int i = 0; i < tagElements.getLength(); i++) {
                Element tagElement = (Element) tagElements.item(i);
                JsonObject tag = parseTag(tagElement, udtDefinitions);  // Pass UDT definitions
                if (tag != null) {
                    tag.addProperty("scope", "Program:" + name);
                    programTags.add(tag);
                }
            }

            program.add("tags", programTags);

            return program;

        } catch (Exception e) {
            logger.error("Error parsing program element", e);
            return null;
        }
    }

    /**
     * Parse a User Defined Type (UDT).
     */
    private JsonObject parseUDT(Element udtElement) {
        try {
            JsonObject udt = new JsonObject();

            String name = udtElement.getAttribute("Name");
            String family = udtElement.getAttribute("Family");

            udt.addProperty("name", name);
            udt.addProperty("family", family);

            JsonArray members = new JsonArray();
            NodeList memberElements = udtElement.getElementsByTagName("Member");

            for (int i = 0; i < memberElements.getLength(); i++) {
                Element memberElement = (Element) memberElements.item(i);
                JsonObject member = new JsonObject();

                member.addProperty("name", memberElement.getAttribute("Name"));
                member.addProperty("data_type", memberElement.getAttribute("DataType"));  // Fixed: Changed from "type" to "data_type"

                String dimensions = memberElement.getAttribute("Dimension");
                if (dimensions != null && !dimensions.isEmpty()) {
                    member.addProperty("dimensions", dimensions);
                }

                members.add(member);
            }

            udt.add("members", members);

            return udt;

        } catch (Exception e) {
            logger.error("Error parsing UDT element", e);
            return null;
        }
    }

    /**
     * Extract value from Data element.
     */
    private String extractValue(Element dataElement, String dataType) {
        try {
            String format = dataElement.getAttribute("Format");

            // For simple types, look for DataValue element
            NodeList dataValues = dataElement.getElementsByTagName("DataValue");
            if (dataValues.getLength() > 0) {
                Element valueElement = (Element) dataValues.item(0);
                return valueElement.getTextContent();
            }

            // For complex types, return structure indicator
            if (dataElement.hasChildNodes()) {
                return "{structure}";
            }

            return null;

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check if element is child of a parent with given tag name.
     */
    private boolean isChildOf(Element element, String parentTagName) {
        Node parent = element.getParentNode();
        while (parent != null && parent instanceof Element) {
            if (parent.getNodeName().equals(parentTagName)) {
                return true;
            }
            parent = parent.getParentNode();
        }
        return false;
    }

    @Override
    public boolean canHandle(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lowerName = fileName.toLowerCase();
        // ONLY handle L5X (XML) files, NOT L5K (text) files
        return lowerName.endsWith(".l5x");
    }

    @Override
    public String getParserType() {
        return "l5x";  // Changed from "rockwell" to be more specific
    }
}
