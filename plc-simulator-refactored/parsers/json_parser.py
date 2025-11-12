"""
JSON Parser for generic tag definitions.
Allows defining PLC tag structures in JSON format for simulation purposes.
"""

import json
import logging
from pathlib import Path
from typing import Dict, Any, List, Optional

try:
    from models.tag_model import PLCProject, Tag, UDT, Program, DataType, TagScope
    from parsers.base_parser import PLCParser
except ImportError:
    from ..models.tag_model import PLCProject, Tag, UDT, Program, DataType, TagScope
    from .base_parser import PLCParser

logger = logging.getLogger(__name__)


class JSONParser(PLCParser):
    """
    Parser for JSON-format tag definitions.

    JSON Format:
    {
        "name": "MyPLC",
        "description": "Description of PLC",
        "udts": [
            {
                "name": "MyUDT",
                "description": "My custom UDT",
                "members": [
                    {"name": "Value", "data_type": "REAL", "initial_value": 0.0},
                    {"name": "Status", "data_type": "BOOL", "initial_value": false}
                ]
            }
        ],
        "global_tags": [
            {"name": "Temperature", "data_type": "REAL", "initial_value": 25.0},
            {"name": "Running", "data_type": "BOOL", "initial_value": false},
            {"name": "Sensor1", "data_type": "MyUDT"}
        ],
        "programs": [
            {
                "name": "MainProgram",
                "tags": [
                    {"name": "Counter", "data_type": "INT", "initial_value": 0}
                ]
            }
        ]
    }
    """

    def __init__(self, config: Optional[Dict[str, Any]] = None):
        super().__init__(config)

    def can_parse(self, file_path: str, file_content: Optional[str] = None) -> bool:
        """Check if file is a valid JSON tag definition"""
        # Check file extension
        if not file_path.lower().endswith('.json'):
            return False

        # Try to parse as JSON
        try:
            if file_content:
                data = json.loads(file_content)
            else:
                with open(file_path, 'r', encoding='utf-8') as f:
                    data = json.load(f)

            # Validate required fields for PLC tag definition
            # Must have at least a name and one of: global_tags, programs, udts
            if not isinstance(data, dict):
                return False

            if 'name' not in data:
                return False

            # Check if it has PLC-related content
            has_content = any(key in data for key in ['global_tags', 'programs', 'udts', 'tags'])
            return has_content

        except (json.JSONDecodeError, FileNotFoundError, Exception):
            return False

    def parse(self, file_path: str, options: Optional[Dict[str, Any]] = None) -> PLCProject:
        """Parse JSON file and create PLCProject"""
        options = options or {}

        logger.info(f"Parsing JSON file: {file_path}")

        with open(file_path, 'r', encoding='utf-8') as f:
            data = json.load(f)

        # Create project
        project_name = data.get('name', Path(file_path).stem)
        project = PLCProject(name=project_name)

        # Parse UDTs first (needed for tag references)
        if 'udts' in data:
            for udt_data in data['udts']:
                udt = self._parse_udt(udt_data)
                if udt:
                    project.udts.append(udt)
                    logger.debug(f"Parsed UDT: {udt.name} with {len(udt.members)} members")

        # Parse global tags
        if 'global_tags' in data:
            for tag_data in data['global_tags']:
                tag = self._parse_tag(tag_data, TagScope.GLOBAL, project.udts)
                if tag:
                    project.global_tags.append(tag)

        # Support 'tags' as alias for 'global_tags' (for backward compatibility)
        elif 'tags' in data:
            for tag_data in data['tags']:
                tag = self._parse_tag(tag_data, TagScope.GLOBAL, project.udts)
                if tag:
                    project.global_tags.append(tag)

        # Parse programs
        if 'programs' in data:
            for program_data in data['programs']:
                program = self._parse_program(program_data, project.udts)
                if program:
                    project.programs.append(program)
                    logger.debug(f"Parsed program: {program.name} with {len(program.tags)} tags")

        logger.info(f"Parsed JSON project '{project_name}': {len(project.udts)} UDTs, "
                   f"{len(project.global_tags)} global tags, {len(project.programs)} programs")

        return project

    def _parse_udt(self, udt_data: Dict[str, Any]) -> Optional[UDT]:
        """Parse UDT definition from JSON"""
        try:
            name = udt_data.get('name')
            if not name:
                logger.warning("UDT missing 'name' field, skipping")
                return None

            description = udt_data.get('description', '')
            members = []

            # Parse members
            for member_data in udt_data.get('members', []):
                member = self._parse_tag(member_data, TagScope.LOCAL, [])
                if member:
                    members.append(member)

            return UDT(
                name=name,
                description=description,
                members=members
            )

        except Exception as e:
            logger.error(f"Error parsing UDT: {e}")
            return None

    def _parse_program(self, program_data: Dict[str, Any], udts: List[UDT]) -> Optional[Program]:
        """Parse program definition from JSON"""
        try:
            name = program_data.get('name')
            if not name:
                logger.warning("Program missing 'name' field, skipping")
                return None

            description = program_data.get('description', '')
            tags = []

            # Parse program tags
            for tag_data in program_data.get('tags', []):
                tag = self._parse_tag(tag_data, TagScope.PROGRAM, udts)
                if tag:
                    tags.append(tag)

            return Program(
                name=name,
                description=description,
                tags=tags
            )

        except Exception as e:
            logger.error(f"Error parsing program: {e}")
            return None

    def _parse_tag(self, tag_data: Dict[str, Any], scope: TagScope, udts: List[UDT]) -> Optional[Tag]:
        """Parse tag definition from JSON"""
        try:
            name = tag_data.get('name')
            if not name:
                logger.warning("Tag missing 'name' field, skipping")
                return None

            data_type_str = tag_data.get('data_type', 'REAL')
            initial_value = tag_data.get('initial_value')
            description = tag_data.get('description', '')
            hidden = tag_data.get('hidden', False)

            # Try to map to DataType enum, or use as string for UDT references
            try:
                data_type = DataType.from_rockwell(data_type_str)
            except ValueError:
                # Not a standard type, might be a UDT reference
                data_type = data_type_str

            return Tag(
                name=name,
                data_type=data_type,
                scope=scope,
                initial_value=initial_value,
                description=description,
                hidden=hidden
            )

        except Exception as e:
            logger.error(f"Error parsing tag '{tag_data.get('name', 'unknown')}': {e}")
            return None

    def get_supported_extensions(self) -> list:
        """Get supported file extensions"""
        return ['.json', '.JSON']

    def get_plc_type_name(self) -> str:
        """Get PLC type name"""
        return "JSON Tag Definition"
