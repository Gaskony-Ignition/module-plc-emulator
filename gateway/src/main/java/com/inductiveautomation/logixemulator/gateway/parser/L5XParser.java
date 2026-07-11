package com.inductiveautomation.logixemulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

            // Parse Add-On Instructions (AOIs) - they expand like UDTs. Modern Studio 5000 exports
            // wrap each definition as <AddOnInstruction> (singular element name matches the plural
            // <AddOnInstructionDefinitions> container); some tooling/older exports instead use the
            // fully-spelled-out <AddOnInstructionDefinition>. Support both element names so AOIs
            // from either export style are found and expanded (defect C5b - a real corpus file,
            // NodeblueAI, uses the <AddOnInstruction> form and its AOI instance tags were silently
            // left unexpanded).
            JsonArray aois = new JsonArray();
            List<Element> aoiElements = elementsByAnyTagName(controller,
                "AddOnInstructionDefinition", "AddOnInstruction");
            for (Element aoiElement : aoiElements) {
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

            // Synthesise I/O module tags from the <Modules> section (ADDRESSING.md §3.13, C5c,
            // INFERRED) as ordinary controller-scope tags - AddressSpaceBuilder needs no
            // module-specific handling since they reuse the same UDT-instance-with-members shape.
            JsonArray moduleIoTags = parseModuleIoTags(controller);
            for (JsonElement moduleTag : moduleIoTags) {
                controllerTags.add(moduleTag);
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
            String name = tagElement.getAttribute("Name");
            String dataType = tagElement.getAttribute("DataType");
            String usage = tagElement.getAttribute("Usage");
            String constant = tagElement.getAttribute("Constant");

            // ExternalAccess governs Ignition's CIP-based Logix driver (ADDRESSING.md §3.12):
            // "None" tags are never returned by the real driver, so the emulator must not create
            // a node for them at all (defect C5a). Note the DELIBERATE omission of the sibling
            // OpcUaAccess attribute here - it governs the controller's own native OPC-UA server,
            // not Ignition's driver, and reading it would wrongly hide the 132 corpus tags that
            // carry OpcUaAccess="None" alongside a visible ExternalAccess.
            String externalAccess = tagElement.getAttribute("ExternalAccess");
            if (isExternalAccessNone(externalAccess)) {
                logger.debug("Tag '{}' has ExternalAccess=None - omitting (ADDRESSING.md §3.12)", name);
                return null;
            }

            JsonObject tag = new JsonObject();
            tag.addProperty("name", name);
            tag.addProperty("data_type", dataType);

            if (usage != null && !usage.isEmpty()) {
                tag.addProperty("usage", usage);
            }

            if ("true".equalsIgnoreCase(constant)) {
                tag.addProperty("constant", true);
                // A Constant tag is never writable on the real driver either (ADDRESSING.md §3.12).
                tag.addProperty("read_only", true);
            } else if (isExternalAccessReadOnly(externalAccess)) {
                tag.addProperty("read_only", true);
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

            // Try to extract initial value. A tag can carry more than one sibling <Data> block
            // (an "L5K" text-format block alongside a "Decorated" one); the DataValue/Value
            // attribute we need only ever lives in the Decorated block (defect C8).
            Element decoratedData = findDecoratedData(tagElement);
            if (decoratedData != null) {
                String value = extractValue(decoratedData, dataType);
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

            // ExternalAccess=None members are never returned by the real driver either
            // (ADDRESSING.md §3.12, C5a) - e.g. the Motor_Control AOI's RunLatch/FaultTimer
            // LocalTags. Omit them from the expanded instance entirely rather than emitting a
            // node the driver would never surface.
            if (memberDef.has("hidden") && memberDef.get("hidden").getAsBoolean()) {
                logger.debug("Member '{}' has ExternalAccess=None - omitting (ADDRESSING.md §3.12)",
                    memberDef.get("name").getAsString());
                continue;
            }

            JsonObject member = new JsonObject();

            String memberName = memberDef.get("name").getAsString();
            String memberType = memberDef.get("data_type").getAsString();

            member.addProperty("name", memberName);
            member.addProperty("data_type", memberType);

            if (memberDef.has("dimensions")) {
                member.addProperty("dimensions", memberDef.get("dimensions").getAsString());
            }

            if (memberDef.has("read_only") && memberDef.get("read_only").getAsBoolean()) {
                member.addProperty("read_only", true);
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
     * @return {@code true} if the L5X {@code ExternalAccess} attribute is exactly {@code "None"}
     *     (ADDRESSING.md §3.12) - the tag/member must not be created at all.
     */
    private static boolean isExternalAccessNone(String externalAccess) {
        return "None".equalsIgnoreCase(externalAccess);
    }

    /**
     * @return {@code true} if the L5X {@code ExternalAccess} attribute is exactly
     *     {@code "Read Only"} (ADDRESSING.md §3.12) - the tag/member must be created read-only.
     */
    private static boolean isExternalAccessReadOnly(String externalAccess) {
        return "Read Only".equalsIgnoreCase(externalAccess);
    }

    /**
     * Marks a UDT/AOI member definition's JSON with the ADDRESSING.md §3.12 disposition of its
     * {@code ExternalAccess} attribute, for {@link #expandUdtInstance} to honour when the
     * definition is later expanded into an instance's members.
     */
    private static void markExternalAccess(JsonObject member, String externalAccess) {
        if (isExternalAccessNone(externalAccess)) {
            member.addProperty("hidden", true);
        } else if (isExternalAccessReadOnly(externalAccess)) {
            member.addProperty("read_only", true);
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

                markExternalAccess(member, memberElement.getAttribute("ExternalAccess"));

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

                markExternalAccess(member, paramElement.getAttribute("ExternalAccess"));

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

                markExternalAccess(member, localTagElement.getAttribute("ExternalAccess"));

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
     * Collects every descendant element matching any of the given tag names, in document order
     * per name (used by C5b to accept both {@code <AddOnInstructionDefinition>} and
     * {@code <AddOnInstruction>} as the AOI-definition element name).
     */
    private static List<Element> elementsByAnyTagName(Element parent, String... tagNames) {
        List<Element> result = new ArrayList<>();
        for (String tagName : tagNames) {
            NodeList nodes = parent.getElementsByTagName(tagName);
            for (int i = 0; i < nodes.getLength(); i++) {
                result.add((Element) nodes.item(i));
            }
        }
        return result;
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
                if (member.getDimensions() != null && !member.getDimensions().isEmpty()) {
                    memberJson.addProperty("dimensions", member.getDimensions());
                }
                members.add(memberJson);
            }
            typeJson.add("members", members);

            definitions.put(typeName, typeJson);
        }

        logger.debug("Added {} built-in Rockwell types to definitions", builtIns.size());
    }

    /**
     * Finds a tag's Decorated-format {@code <Data>} element. A tag can carry more than one
     * sibling {@code <Data>} block (an "L5K" text-format block alongside the "Decorated" one that
     * holds the {@code <DataValue>}/{@code Value} attribute we need - defect C8); falls back to
     * the first {@code <Data>} element found if none is explicitly marked "Decorated" (preserves
     * behaviour for fixtures that omit the {@code Format} attribute).
     */
    private static Element findDecoratedData(Element tagElement) {
        NodeList dataElements = tagElement.getElementsByTagName("Data");
        for (int i = 0; i < dataElements.getLength(); i++) {
            Element candidate = (Element) dataElements.item(i);
            if ("Decorated".equals(candidate.getAttribute("Format"))) {
                return candidate;
            }
        }
        return dataElements.getLength() > 0 ? (Element) dataElements.item(0) : null;
    }

    /**
     * Extract the scalar initial value from a Decorated {@code <Data>} element (defect C8).
     *
     * <p>Real Studio 5000 exports render a scalar/atomic tag's value as a self-closing
     * {@code <DataValue DataType="..." Value="42"/>} - the value lives in the {@code Value}
     * attribute, never as element text content (which is always empty for a self-closing tag).
     * This reads that attribute, falling back to text content for any export style that puts the
     * value there instead.
     *
     * <p>Array ({@code <Array>/<Element>}) and structured (UDT/AOI {@code <Structure>}, STRING
     * {@code <Structure>}) Data elements have no {@code <DataValue>} child at all, so this
     * correctly returns {@code null} for them - {@code AddressSpaceBuilder.getInitialValue()}
     * then takes its type-appropriate default path (defect B1's fix). Per-member/per-element
     * initial values for those constructs are deliberately out of scope for C8 (FIX-14,
     * ADDRESSING.md §3.10a records this as a maintainer scope decision, post-v10) - this stays
     * conservative: only the single scalar tag value is read.
     */
    private String extractValue(Element dataElement, String dataType) {
        try {
            NodeList dataValues = dataElement.getElementsByTagName("DataValue");
            if (dataValues.getLength() == 0) {
                return null;
            }

            Element valueElement = (Element) dataValues.item(0);
            String valueAttr = valueElement.getAttribute("Value");
            String rawValue = (valueAttr != null && !valueAttr.isEmpty())
                ? valueAttr
                : valueElement.getTextContent();

            if (rawValue == null || rawValue.isEmpty()) {
                return null;
            }

            return normalizeValueForType(rawValue, dataType);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Normalises a raw decorated {@code Value} string for its declared data type so that
     * {@code AddressSpaceBuilder.getInitialValue()}'s per-type parsing round-trips correctly.
     * Studio 5000 renders BOOL values as {@code "0"}/{@code "1"}, which
     * {@code Boolean.parseBoolean} would silently misread ({@code "1"} -&gt; {@code false}) - this
     * is the only normalisation C8 requires; every other atomic type's {@code Value} text already
     * round-trips through its numeric/string parser unchanged. Binary/hex-radix literals (e.g.
     * {@code "2#0000_...."}, {@code "16#0c"}) and Date/Time literals (e.g.
     * {@code "DT#1970-01-01..."}) are intentionally left as-is: they are not numeric per
     * {@code Integer}/{@code Long} parsing, so they fall back to the type default via the
     * existing B1 safety net rather than being decoded here (kept conservative; a future
     * enhancement could decode them).
     */
    private static String normalizeValueForType(String rawValue, String dataType) {
        String upperType = dataType == null ? "" : dataType.toUpperCase();
        if (!"BOOL".equals(upperType) && !"BOOLEAN".equals(upperType)) {
            return rawValue;
        }
        String trimmed = rawValue.trim();
        if ("0".equals(trimmed)) {
            return "false";
        }
        if ("1".equals(trimmed)) {
            return "true";
        }
        return rawValue;
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

    /**
     * Parses the L5X {@code <Modules>} section into synthetic controller-scope tags representing
     * each module's readable I/O data (ADDRESSING.md §3.13 - I/O module tags, INFERRED; defect
     * C5c). Each synthesised tag reuses the ordinary UDT-instance-with-members shape
     * ({@code udt_members}) so {@code AddressSpaceBuilder} needs no module-specific handling: a
     * tag named {@code <ModuleName>:I} (or {@code :O}) with a single member named {@code Data}
     * expands, through the existing generic machinery, to {@code <ModuleName>:I.Data} (scalar or
     * array, per the member's declared type/dimensions).
     *
     * <p><b>Scope (per ADDRESSING.md §3.13's explicit guidance):</b> only the module's
     * Input/OutputTag member literally named {@code "Data"} is synthesised - the module's
     * ConfigTag and any other diagnostic/config sub-members (e.g. an analog module's per-channel
     * status/alarm/calibration members) are deliberately NOT modelled; a module whose
     * Input/OutputTag structure has no top-level member named {@code "Data"} (e.g. the analog
     * {@code AB:1756_IF8_Float} modules in the corpus, which expose per-channel {@code ChNData}
     * members instead) contributes no tag at all, rather than guessing at its layout. The
     * {@code Local:&lt;slot&gt;:} alias form ADDRESSING.md §3.13 says MAY be added for
     * local-chassis modules is likewise not emitted - the canonical {@code <ModuleName>:} form is
     * sufficient and keeps this INFERRED area's surface minimal pending a bench diff.
     */
    private JsonArray parseModuleIoTags(Element controller) {
        JsonArray moduleTags = new JsonArray();
        NodeList moduleElements = controller.getElementsByTagName("Module");

        for (int i = 0; i < moduleElements.getLength(); i++) {
            Element moduleElement = (Element) moduleElements.item(i);
            String moduleName = moduleElement.getAttribute("Name");
            if (moduleName == null || moduleName.isEmpty()) {
                continue;
            }

            Element inputData = findConnectionDataMember(moduleElement, "InputTag");
            if (inputData != null) {
                addModuleIoTag(moduleTags, moduleName + ":I", inputData);
            }

            Element outputData = findConnectionDataMember(moduleElement, "OutputTag");
            if (outputData != null) {
                addModuleIoTag(moduleTags, moduleName + ":O", outputData);
            }
        }

        return moduleTags;
    }

    /**
     * Finds the direct "Data" member (a {@code DataValueMember} or {@code ArrayMember} whose
     * {@code Name} is exactly {@code "Data"}) inside a module's InputTag/OutputTag Decorated
     * {@code <Structure>}, if any. Only direct children of the outer {@code <Structure>} are
     * considered - nested {@code <StructureMember>} sub-groups (diagnostic/config detail) are
     * out of scope (ADDRESSING.md §3.13).
     */
    private static Element findConnectionDataMember(Element moduleElement, String connectionTagName) {
        NodeList connectionTags = moduleElement.getElementsByTagName(connectionTagName);
        for (int i = 0; i < connectionTags.getLength(); i++) {
            Element connectionTag = (Element) connectionTags.item(i);
            NodeList structures = connectionTag.getElementsByTagName("Structure");
            for (int j = 0; j < structures.getLength(); j++) {
                Element structure = (Element) structures.item(j);
                NodeList children = structure.getChildNodes();
                for (int k = 0; k < children.getLength(); k++) {
                    Node child = children.item(k);
                    if (child instanceof Element && "Data".equals(((Element) child).getAttribute("Name"))) {
                        return (Element) child;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Adds a synthesised module I/O tag ({@code <ModuleName>:I} or {@code :O}) with a single
     * {@code Data} member to {@code moduleTags}.
     */
    private static void addModuleIoTag(JsonArray moduleTags, String tagName, Element dataMember) {
        JsonObject member = new JsonObject();
        member.addProperty("name", "Data");
        member.addProperty("data_type", dataMember.getAttribute("DataType"));

        String dimensions = dataMember.getAttribute("Dimensions");
        if (dimensions != null && !dimensions.isEmpty()) {
            member.addProperty("dimensions", dimensions);
        }

        JsonArray members = new JsonArray();
        members.add(member);

        JsonObject tag = new JsonObject();
        tag.addProperty("name", tagName);
        // Never used for OPC type mapping (the tag becomes an Object node because it carries
        // udt_members, regardless of this string) - kept only as human-readable provenance.
        tag.addProperty("data_type", "MODULE_IO");
        tag.addProperty("scope", "Controller");
        tag.add("udt_members", members);

        moduleTags.add(tag);
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
