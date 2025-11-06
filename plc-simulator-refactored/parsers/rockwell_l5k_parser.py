"""
Rockwell Logix 5000 (.L5K) file parser.
Refactored to use the common tag model and protocol abstractions.
"""

import re
from typing import Optional, Dict, Any, List

# Handle imports for both module and direct execution
try:
    from parsers.base_parser import PLCParser
    from models.tag_model import PLCProject, Tag, UDT, Program, DataType, TagScope
except ImportError:
    from .base_parser import PLCParser
    from ..models.tag_model import PLCProject, Tag, UDT, Program, DataType, TagScope


class RockwellL5KParser(PLCParser):
    """
    Parser for Rockwell Automation Logix 5000 L5K export files.
    Supports ControlLogix, CompactLogix, and other Logix-based PLCs.
    """

    # Built-in Rockwell UDTs
    BUILT_IN_UDTS = ["TIMER", "COUNTER", "MESSAGE", "CONTROL"]

    def __init__(self, config: Optional[Dict[str, Any]] = None):
        super().__init__(config)

    def can_parse(self, file_path: str, file_content: Optional[str] = None) -> bool:
        """Check if this is an L5K file"""
        if file_path.lower().endswith(('.l5k', '.l5x')):
            return True

        # Check content if provided
        if file_content:
            return "CONTROLLER" in file_content and ("DATATYPE" in file_content or "TAG" in file_content)

        return False

    def get_supported_extensions(self) -> list:
        """Get supported file extensions"""
        return ['.l5k', '.L5K', '.l5x', '.L5X']

    def get_plc_type_name(self) -> str:
        """Get PLC type name"""
        return "Rockwell Logix 5000"

    def parse(self, file_path: str, options: Optional[Dict[str, Any]] = None) -> PLCProject:
        """
        Parse L5K file and return PLCProject.

        Options:
            - select_tags: bool - Default selection state for tags (default: False)
            - allow_hidden: bool - Include hidden tags (default: False)
            - use_stored_values: bool - Use values from L5K file (default: True)
        """
        options = options or {}
        select_tags = options.get('select_tags', False)
        allow_hidden = options.get('allow_hidden', False)
        use_stored_values = options.get('use_stored_values', True)

        # Read file
        with open(file_path, 'r', encoding='utf-8') as f:
            l5k_content = f.read()

        # Parse components
        udts = self._parse_udts(l5k_content, allow_hidden, select_tags, use_stored_values)
        global_tags = self._parse_global_tags(l5k_content, udts, allow_hidden, select_tags, use_stored_values)
        programs = self._parse_programs(l5k_content, udts, allow_hidden, select_tags, use_stored_values)

        # Extract controller metadata
        controller_name = self._search_one(r"CONTROLLER\s(\w+)", l5k_content, "Controller")
        comm_path = self._search_one(r"CommPath\s:\=\s\"(.*?)\"", l5k_content)

        metadata = {
            "controller_name": controller_name,
            "comm_path": comm_path,
            "source_file": file_path
        }

        if comm_path:
            parts = comm_path.split("\\\\")
            if len(parts) > 1:
                metadata["ip_address"] = parts[1]
                metadata["slot"] = parts[-1] if len(parts) > 2 else "0"

        return PLCProject(
            name=controller_name,
            plc_type=self.get_plc_type_name(),
            udts=udts,
            global_tags=global_tags,
            programs=programs,
            metadata=metadata
        )

    def _parse_udts(
        self,
        l5k_content: str,
        allow_hidden: bool,
        select_tags: bool,
        use_stored_values: bool
    ) -> List[UDT]:
        """Parse all UDT definitions from L5K file"""
        udts = []

        # Add built-in UDTs (TIMER, COUNTER, MESSAGE, CONTROL)
        udts.extend(self._get_built_in_udts())

        # Parse DATATYPE sections
        for datatype_section in self._findall("DATATYPE(.*?)END_DATATYPE", l5k_content):
            udt_name = datatype_section[0:datatype_section.find("(")].strip()
            udt_description = self._search_one("Description.*\"(.*?)\"", datatype_section, "")
            is_string_family = "FamilyType := StringFamily" in datatype_section

            # Parse members
            members_section = datatype_section[datatype_section.find(")\r\n")+3:]
            members = self._parse_tags_section(
                members_section,
                [],
                allow_hidden,
                select_tags,
                use_stored_values,
                is_datatype=True
            )

            udt = UDT(
                name=udt_name,
                description=udt_description,
                members=members,
                is_string_family=is_string_family,
                metadata={"source": "DATATYPE"}
            )
            udts.append(udt)

        # Parse ADD_ON_INSTRUCTION_DEFINITION sections
        # Format 1: ADD_ON_INSTRUCTION_DEFINITION <name>
        for aoi_section in self._findall(r"ADD_ON_INSTRUCTION_DEFINITION\s(.*?)END_ADD_ON_INSTRUCTION_DEFINITION", l5k_content):
            aoi_name = self._search_one(r"(.*?)\s", aoi_section, "")

            # Parse local tags for aliasing
            local_tags = self._parse_local_tags(aoi_section)

            # Parse parameters section - handle both \r\n and \n line endings
            params_section = self._search_one(r"PARAMETERS(?:\r?\n)(.*?)END_PARAMETERS", aoi_section, "")
            if params_section:
                members = self._parse_tags_section(
                    params_section,
                    local_tags,
                    allow_hidden,
                    select_tags,
                    use_stored_values,
                    is_datatype=False
                )

                udt = UDT(
                    name=aoi_name,
                    description="",
                    members=members,
                    is_string_family=False,
                    metadata={"source": "AOI"}
                )
                udts.append(udt)

        # Format 2: ADD_ON_INSTRUCTION_DEFINITION, (legacy format)
        for aoi_section in self._findall("ADD_ON_INSTRUCTION_DEFINITION,(.*?)END_ENCODED_DATA", l5k_content):
            aoi_name = self._search_one("Name.*\"(.*?)\"", aoi_section, "")
            aoi_description = self._search_one("Description.*\"(.*?)\"", aoi_section, "")

            local_tags = self._parse_local_tags(aoi_section)

            # Parse parameters section - handle both \r\n and \n line endings
            params_section = self._search_one(r"PARAMETERS(?:\r?\n)(.*?)END_PARAMETERS", aoi_section, "")
            if params_section:
                members = self._parse_tags_section(
                    params_section,
                    local_tags,
                    allow_hidden,
                    select_tags,
                    use_stored_values,
                    is_datatype=False
                )

                udt = UDT(
                    name=aoi_name,
                    description=aoi_description,
                    members=members,
                    is_string_family=False,
                    metadata={"source": "AOI_LEGACY"}
                )
                udts.append(udt)

        return udts

    def _parse_global_tags(
        self,
        l5k_content: str,
        udts: List[UDT],
        allow_hidden: bool,
        select_tags: bool,
        use_stored_values: bool
    ) -> List[Tag]:
        """Parse global controller-scoped tags"""
        tags = []

        # Try traditional TAG section first
        try:
            controller_section = self._findall_one("CONTROLLER(.*?)END_TAG\r\n", l5k_content)
            tags_section = self._findall_one("TAG\r\n(.*?)END_TAG\r\n", controller_section + "END_TAG\r\n")

            tags = self._parse_tags_section(
                tags_section,
                [],
                allow_hidden,
                select_tags,
                use_stored_values,
                is_datatype=False,
                scope=TagScope.GLOBAL
            )
        except:
            pass

        # Also parse inline controller-scoped tags (not in TAG sections)
        # These are defined like: "\t\tAV101 : A130_Valve_FB_1Coil_RETRY := [...]"
        try:
            controller_match = re.search(r'CONTROLLER\s+\w+(.*?)(?:PROGRAM\s+|END_CONTROLLER)', l5k_content, re.DOTALL)
            if controller_match:
                controller_content = controller_match.group(1)

                # Pattern for inline tag definitions
                inline_pattern = r'^\t\t([A-Z][A-Z0-9_]+)\s+:\s+([A-Z][A-Z0-9_]+[^\r\n]*?)\s*(?:\(Description\s*:\=\s*"([^"]*)"[^\r\n]*?)?\s*:='

                for match in re.finditer(inline_pattern, controller_content, re.MULTILINE):
                    tag_name = match.group(1)
                    udt_type_full = match.group(2).strip()
                    description = match.group(3) if match.group(3) else ""

                    # Extract just the UDT type name (before any parenthesis or space)
                    udt_type = udt_type_full.split()[0] if ' ' in udt_type_full else udt_type_full

                    # Determine if this is an atomic type or UDT
                    try:
                        data_type = DataType.from_rockwell(udt_type)
                        is_atomic = True
                    except:
                        # It's a UDT
                        data_type = udt_type
                        is_atomic = False

                    tag = Tag(
                        name=tag_name,
                        data_type=data_type,
                        scope=TagScope.GLOBAL,
                        description=description.replace("$N", "\n"),
                        folder_path=None,
                        metadata={"selected": select_tags, "inline_defined": True}
                    )
                    tags.append(tag)
        except Exception as e:
            print(f"Warning: Could not parse inline controller tags: {e}")

        return tags

    def _parse_programs(
        self,
        l5k_content: str,
        udts: List[UDT],
        allow_hidden: bool,
        select_tags: bool,
        use_stored_values: bool
    ) -> List[Program]:
        """Parse all programs and their tags"""
        programs = []

        for program_section in self._findall(r"\tPROGRAM\s(.*?)END_PROGRAM", l5k_content):
            program_name = self._search_one(r"(\w+)\s(.*?)", program_section, "UnknownProgram")

            try:
                tags_section = self._findall_one("TAG\r\n(.*?)END_TAG\r\n", program_section)
                program_tags = self._parse_tags_section(
                    tags_section,
                    [],
                    allow_hidden,
                    select_tags,
                    use_stored_values,
                    is_datatype=False,
                    scope=TagScope.PROGRAM,
                    folder_path=f"Program {program_name}"
                )
            except:
                program_tags = []

            program = Program(
                name=program_name,
                tags=program_tags,
                description="",
                metadata={}
            )
            programs.append(program)

        return programs

    def _parse_tags_section(
        self,
        tags_section: str,
        local_tags: List[Dict[str, str]],
        allow_hidden: bool,
        select_tags: bool,
        use_stored_values: bool,
        is_datatype: bool,
        scope: TagScope = TagScope.GLOBAL,
        folder_path: Optional[str] = None
    ) -> List[Tag]:
        """Parse a section of tag definitions"""
        tags = []

        # Handle both \r\n and \n line endings
        for tag_line in re.split(r";(?:\r?\n)", tags_section):
            tag_line = tag_line.strip()
            if not tag_line:
                continue

            # Check for BIT type (special case)
            parts = self._search(r"(\w+)(\sOF\s)?", tag_line.strip())
            if parts and parts[1] is not None:
                # BIT type tag
                tag = Tag(
                    name=parts[0],
                    data_type=DataType.BOOL,
                    scope=scope,
                    description="",
                    folder_path=folder_path,
                    metadata={"selected": select_tags}
                )
                tags.append(tag)
            else:
                # Regular tag
                parsed_tag = self._parse_single_tag(
                    tag_line,
                    is_datatype,
                    allow_hidden,
                    select_tags,
                    use_stored_values,
                    scope,
                    folder_path,
                    local_tags
                )
                if parsed_tag:
                    tags.append(parsed_tag)

        return tags

    def _parse_single_tag(
        self,
        tag_line: str,
        is_datatype: bool,
        allow_hidden: bool,
        select_tags: bool,
        use_stored_values: bool,
        scope: TagScope,
        folder_path: Optional[str],
        local_tags: List[Dict[str, str]]
    ) -> Optional[Tag]:
        """Parse a single tag definition line"""

        # Parse tag components
        if is_datatype:
            # DATATYPE format: <type> <name>[<array>] <attributes>
            match = re.search(r"([a-zA-Z0-9_:]+)\s(\w+)(\[[\d+|\,]+\])?\s?(.+)?", tag_line)
            if not match:
                return None
            data_type_str, tag_name, array_str, attributes = match.groups()
        else:
            # TAG format: <name> OF <type>[<array>] <attributes> or <name> : <type>[<array>] <attributes>
            match = re.search(r"([a-zA-Z0-9_:.]+)\sOF\s([a-zA-Z0-9_:.]+)\s?(\[[\d+|\,]+\])?\s?(.+)?", tag_line)
            if not match:
                match = re.search(r"([a-zA-Z0-9_:.]+)\s:\s(\w+)(\[[\d+|\,]+\])?\s?(.+)?", tag_line)
            if not match:
                return None
            tag_name, data_type_str, array_str, attributes = match.groups()

        # Handle aliased data types (e.g., "LocalTag.Member")
        if "." in data_type_str:
            data_type_str = self._resolve_alias(data_type_str, local_tags)

        # Parse array dimensions
        array_length = None
        if array_str:
            array_str = array_str[1:-1]  # Remove brackets
            if "," in array_str:
                # Multi-dimensional array - flatten
                dims = [int(d) for d in array_str.split(",")]
                array_length = dims[0] * dims[1]
            else:
                array_length = int(array_str)

        # Parse attributes
        hidden = False
        description = ""
        initial_value = None
        is_in_out = False

        if attributes:
            hidden = bool(int(self._search_one(r"Hidden\s:\=\s(.*?)(\)|\,)", attributes, "0")))
            description = self._search_one(r"Description.*\"(.*?)\"", attributes, "")
            is_in_out = ("InOut" in attributes)

            if use_stored_values:
                # Only extract initial values from actual value assignments, not from parameter attributes
                # Look for specific patterns like "DefaultData :=" or a value at the very end ":= <value>)"
                default_data_match = re.search(r"DefaultData\s*:\=\s*([^,\)]+)", attributes)
                if default_data_match:
                    raw_value = default_data_match.group(1).strip()
                    raw_value = raw_value.replace("$00", "").replace("2#", "")
                    initial_value = raw_value
                elif is_datatype:
                    # For DATATYPEs, only extract values if they're explicit data assignments
                    # Skip extraction for most attributes - DATATYPE members typically don't have initial values
                    # unless they're actual value assignments like ":= 0)" or ":= [...]"
                    # Do NOT extract from Description, ExternalAccess, or other attribute fields
                    pass  # Don't extract initial values from DATATYPE member attributes

        # Skip hidden tags if not allowed
        if hidden and not allow_hidden:
            return None

        # Skip InOut parameters (they're not part of the UDT)
        if is_in_out:
            return None

        # Convert data type
        try:
            data_type = DataType.from_rockwell(data_type_str)
        except:
            # Custom UDT - store as string
            data_type = data_type_str

        return Tag(
            name=tag_name,
            data_type=data_type,
            scope=scope,
            description=description,
            array_length=array_length,
            initial_value=initial_value,
            hidden=hidden,
            folder_path=folder_path,
            metadata={"selected": select_tags}
        )

    def _parse_local_tags(self, aoi_section: str) -> List[Dict[str, str]]:
        """Parse LOCAL_TAGS section for alias resolution"""
        local_tags = {}
        try:
            # Handle both \r\n and \n line endings
            local_section = self._findall_one(r"LOCAL_TAGS(?:\r?\n)(.*?)END_LOCAL_TAGS", aoi_section)
            # Split on both possible line endings
            for tag_line in re.split(r";(?:\r?\n)", local_section):
                match = re.search(r"([a-zA-Z0-9_:.]+)\s:\s(\w+)", tag_line.strip())
                if match:
                    name, dtype = match.groups()
                    local_tags[name] = dtype
        except:
            pass
        return local_tags

    def _resolve_alias(self, aliased_type: str, local_tags: Dict[str, str]) -> str:
        """Resolve aliased data type (e.g., "LocalTag.Member" -> actual type)"""
        parts = aliased_type.split(".")
        if len(parts) >= 2 and parts[0] in local_tags:
            return local_tags[parts[0]]
        return "BOOL"  # Default fallback

    def _get_built_in_udts(self) -> List[UDT]:
        """Get Rockwell built-in UDT definitions"""
        return [
            UDT(
                name="TIMER",
                description="Built-in timer",
                members=[
                    Tag("DN", DataType.BOOL, description="Done bit"),
                    Tag("EN", DataType.BOOL, description="Enable bit"),
                    Tag("TT", DataType.BOOL, description="Timing bit"),
                    Tag("PRE", DataType.INT4, description="Preset value"),
                    Tag("ACC", DataType.INT4, description="Accumulated value")
                ],
                metadata={"built_in": True}
            ),
            UDT(
                name="COUNTER",
                description="Built-in counter",
                members=[
                    Tag("CU", DataType.BOOL, description="Count up bit"),
                    Tag("CD", DataType.BOOL, description="Count down bit"),
                    Tag("DN", DataType.BOOL, description="Done bit"),
                    Tag("OV", DataType.BOOL, description="Overflow bit"),
                    Tag("UN", DataType.BOOL, description="Underflow bit"),
                    Tag("PRE", DataType.INT4, description="Preset value"),
                    Tag("ACC", DataType.INT4, description="Accumulated value")
                ],
                metadata={"built_in": True}
            ),
            # MESSAGE and CONTROL would go here...
        ]

    # Regex helper methods (ported from original code)
    def _search_one(self, pattern: str, text: str, default: str = None, flags=re.DOTALL) -> Optional[str]:
        """Search and return first group"""
        match = re.search(pattern, text, flags)
        return match.group(1) if match and match.groups() else default

    def _search(self, pattern: str, text: str, flags=re.DOTALL) -> tuple:
        """Search and return all groups"""
        match = re.search(pattern, text, flags)
        return match.groups() if match else ()

    def _findall(self, pattern: str, text: str, flags=re.DOTALL) -> list:
        """Find all matches"""
        return re.findall(pattern, text, flags)

    def _findall_one(self, pattern: str, text: str, flags=re.DOTALL) -> str:
        """Find all and return first match"""
        matches = re.findall(pattern, text, flags)
        return matches[0] if matches else ""
