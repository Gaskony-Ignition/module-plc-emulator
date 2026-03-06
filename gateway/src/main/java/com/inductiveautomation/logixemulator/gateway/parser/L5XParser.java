package com.inductiveautomation.logixemulator.gateway.parser;

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

import static com.inductiveautomation.logixemulator.gateway.parser.DataTypeUtils.*;

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
            Document doc = builder.parse(new ByteArrayInputStream(fileContent.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

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

            // Parse Add-On Instructions (AOIs) - they expand like UDTs
            JsonArray aois = new JsonArray();
            NodeList aoiElements = controller.getElementsByTagName("AddOnInstructionDefinition");
            for (int i = 0; i < aoiElements.getLength(); i++) {
                Element aoiElement = (Element) aoiElements.item(i);
                JsonObject aoi = parseAOI(aoiElement);
                if (aoi != null) {
                    aois.add(aoi);
                    // Add AOIs to UDT definitions map so they can be expanded when used as tag types
                    udtDefinitions.put(aoi.get("name").getAsString(), aoi);
                }
            }

            if (udts.size() > 0) {
                result.add("udts", udts);
            }

            if (aois.size() > 0) {
                result.add("aois", aois);
            }

            // Add built-in Rockwell types to definitions for expansion
            addBuiltInTypes(udtDefinitions);

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
     * @param udtDefinitions Map of UDT/AOI definitions for expanding instances
     */
    private JsonObject parseTag(Element tagElement, Map<String, JsonObject> udtDefinitions) {
        try {
            JsonObject tag = new JsonObject();

            String name = tagElement.getAttribute("Name");
            String dataType = tagElement.getAttribute("DataType");
            String usage = tagElement.getAttribute("Usage");
            String constant = tagElement.getAttribute("Constant");

            tag.addProperty("name", name);
            tag.addProperty("data_type", dataType);

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

            // Check if this is a UDT/AOI instance and expand it recursively
            if (udtDefinitions.containsKey(dataType)) {
                expandUdtInstance(tag, udtDefinitions.get(dataType), udtDefinitions, 0);
                logger.debug("Expanded UDT/AOI instance: {} of type {}", name, dataType);
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
     * Recursively expands a UDT/AOI instance, including nested types.
     *
     * @param tag The tag JSON object to add udt_members to
     * @param udtDef The UDT/AOI definition containing members
     * @param allDefinitions Map of all UDT/AOI definitions for nested expansion
     * @param depth Current recursion depth (to prevent infinite loops)
     */
    private void expandUdtInstance(JsonObject tag, JsonObject udtDef,
                                   Map<String, JsonObject> allDefinitions, int depth) {
        // Prevent infinite recursion (max 10 levels deep)
        if (depth > 10) {
            logger.warn("Maximum UDT nesting depth exceeded");
            return;
        }

        if (!udtDef.has("members")) {
            return;
        }

        JsonArray members = udtDef.getAsJsonArray("members");
        JsonArray udtMembers = new JsonArray();

        for (int i = 0; i < members.size(); i++) {
            JsonObject memberDef = members.get(i).getAsJsonObject();
            JsonObject member = new JsonObject();

            String memberName = memberDef.get("name").getAsString();
            String memberType = memberDef.get("data_type").getAsString();

            member.addProperty("name", memberName);
            member.addProperty("data_type", memberType);

            if (memberDef.has("dimensions")) {
                member.addProperty("dimensions", memberDef.get("dimensions").getAsString());
            }

            // Check if this member is itself a UDT/AOI that needs expansion
            if (allDefinitions.containsKey(memberType)) {
                // Recursively expand nested UDT/AOI
                expandUdtInstance(member, allDefinitions.get(memberType), allDefinitions, depth + 1);
                logger.trace("Expanded nested type member: {} of type {} at depth {}",
                    memberName, memberType, depth);
            } else {
                // Atomic type - set default initial value
                member.addProperty("initial_value", getDefaultValue(memberType));
            }

            udtMembers.add(member);
        }

        tag.add("udt_members", udtMembers);
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
            udt.addProperty("type", "UDT");

            JsonArray members = new JsonArray();
            NodeList memberElements = udtElement.getElementsByTagName("Member");

            for (int i = 0; i < memberElements.getLength(); i++) {
                Element memberElement = (Element) memberElements.item(i);

                // Skip hidden/internal members (like ZZZZ padding)
                String memberName = memberElement.getAttribute("Name");
                if (memberName.startsWith("ZZZZ")) {
                    continue;
                }

                JsonObject member = new JsonObject();
                member.addProperty("name", memberName);
                member.addProperty("data_type", memberElement.getAttribute("DataType"));

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
     * Parse an Add-On Instruction (AOI) definition.
     * AOIs are treated similarly to UDTs for expansion purposes.
     *
     * L5X AOI structure:
     * <AddOnInstructionDefinition Name="MyAOI" Revision="1.0">
     *   <Parameters>
     *     <Parameter Name="Input1" TagType="Base" DataType="DINT" Usage="Input"/>
     *     <Parameter Name="Output1" TagType="Base" DataType="DINT" Usage="Output"/>
     *   </Parameters>
     *   <LocalTags>
     *     <LocalTag Name="Internal1" DataType="DINT"/>
     *   </LocalTags>
     * </AddOnInstructionDefinition>
     */
    private JsonObject parseAOI(Element aoiElement) {
        try {
            JsonObject aoi = new JsonObject();

            String name = aoiElement.getAttribute("Name");
            String revision = aoiElement.getAttribute("Revision");
            String className = aoiElement.getAttribute("Class");

            aoi.addProperty("name", name);
            aoi.addProperty("type", "AOI");

            if (revision != null && !revision.isEmpty()) {
                aoi.addProperty("revision", revision);
            }
            if (className != null && !className.isEmpty()) {
                aoi.addProperty("class", className);
            }

            JsonArray members = new JsonArray();

            // Parse Parameters (Input, Output, InOut parameters)
            NodeList paramElements = aoiElement.getElementsByTagName("Parameter");
            for (int i = 0; i < paramElements.getLength(); i++) {
                Element paramElement = (Element) paramElements.item(i);

                String paramName = paramElement.getAttribute("Name");
                String dataType = paramElement.getAttribute("DataType");
                String usage = paramElement.getAttribute("Usage");

                // Skip EnableIn and EnableOut (standard AOI parameters)
                if ("EnableIn".equals(paramName) || "EnableOut".equals(paramName)) {
                    continue;
                }

                JsonObject member = new JsonObject();
                member.addProperty("name", paramName);
                member.addProperty("data_type", dataType);

                if (usage != null && !usage.isEmpty()) {
                    member.addProperty("usage", usage);
                }

                String dimensions = paramElement.getAttribute("Dimensions");
                if (dimensions != null && !dimensions.isEmpty()) {
                    member.addProperty("dimensions", dimensions);
                }

                members.add(member);
            }

            // Parse LocalTags (internal AOI variables)
            NodeList localTagElements = aoiElement.getElementsByTagName("LocalTag");
            for (int i = 0; i < localTagElements.getLength(); i++) {
                Element localTagElement = (Element) localTagElements.item(i);

                String tagName = localTagElement.getAttribute("Name");
                String dataType = localTagElement.getAttribute("DataType");

                // Skip internal/hidden members
                if (tagName.startsWith("ZZZZ")) {
                    continue;
                }

                JsonObject member = new JsonObject();
                member.addProperty("name", tagName);
                member.addProperty("data_type", dataType);
                member.addProperty("usage", "Local");

                String dimensions = localTagElement.getAttribute("Dimensions");
                if (dimensions != null && !dimensions.isEmpty()) {
                    member.addProperty("dimensions", dimensions);
                }

                members.add(member);
            }

            aoi.add("members", members);

            logger.debug("Parsed AOI: {} with {} members", name, members.size());
            return aoi;

        } catch (Exception e) {
            logger.error("Error parsing AOI element", e);
            return null;
        }
    }

    /**
     * Add built-in Rockwell types to the definitions map.
     * These include TIMER, COUNTER, PID, PIDE, AXIS_CIP_DRIVE, etc.
     */
    private void addBuiltInTypes(Map<String, JsonObject> definitions) {
        Map<String, UDTDefinition> builtIns = RockwellBuiltInTypes.createAll();

        for (var entry : builtIns.entrySet()) {
            String typeName = entry.getKey();
            UDTDefinition udtDef = entry.getValue();

            // Convert UDTDefinition to JsonObject format matching our parsing structure
            JsonObject typeJson = new JsonObject();
            typeJson.addProperty("name", typeName);
            typeJson.addProperty("type", "BuiltIn");

            JsonArray members = new JsonArray();
            for (var member : udtDef.getMembers()) {
                JsonObject memberJson = new JsonObject();
                memberJson.addProperty("name", member.getName());
                memberJson.addProperty("data_type", member.getDataType());
                members.add(memberJson);
            }
            typeJson.add("members", members);

            definitions.put(typeName, typeJson);
        }

        logger.debug("Added {} built-in Rockwell types to definitions", builtIns.size());
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
