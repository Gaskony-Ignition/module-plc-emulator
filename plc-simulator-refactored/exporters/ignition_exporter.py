"""
Ignition exporter that converts the common tag model to Ignition formats.
Supports UDT definitions, tag instances, and simulator CSV export.
"""

import json
import csv
from io import StringIO
from typing import List, Dict, Any, Optional

# Handle imports for both module and direct execution
try:
    from models.tag_model import PLCProject, Tag, UDT, Program, DataType, TagScope
    from protocols.address_provider import AddressProvider, create_address_provider
except ImportError:
    from ..models.tag_model import PLCProject, Tag, UDT, Program, DataType, TagScope
    from ..protocols.address_provider import AddressProvider, create_address_provider


class IgnitionExporter:
    """
    Exports PLCProject to Ignition-compatible formats.
    Supports three export modes:
    1. UDT definitions (JSON)
    2. Tag instances (JSON)
    3. Simulator program (CSV)
    """

    def __init__(
        self,
        protocol: str = "opcua",
        device_name: str = "SimulatorDevice",
        udt_prefix: Optional[str] = None,
        create_folders: bool = True
    ):
        """
        Args:
            protocol: Protocol type ("opcua", "modbus", etc.)
            device_name: Device name for tag bindings
            udt_prefix: Optional folder prefix for UDT organization
            create_folders: Create true folder structures for programs/hierarchies
        """
        self.protocol = protocol
        self.device_name = device_name
        self.udt_prefix = udt_prefix
        self.create_folders = create_folders

    def export_udts(self, project: PLCProject) -> str:
        """
        Export UDT definitions as Ignition JSON.

        Args:
            project: PLCProject to export

        Returns:
            JSON string of UDT definitions
        """
        # Use parameterized address provider for UDT definitions
        address_provider = create_address_provider("opcua_parameterized", "")

        udt_defs = []

        for udt in project.udts:
            if udt.is_string_family:
                continue  # Skip string family UDTs

            tags = []
            for member in udt.members:
                if member.hidden and not self._should_include_hidden():
                    continue

                tags.extend(self._build_udt_member_tags(member, address_provider, project.udts))

            udt_def = {
                "name": udt.name,
                "parameters": {
                    "DeviceName": {"dataType": "String"},
                    "TagPrefix": {"dataType": "String"},
                    "Description": {"dataType": "String", "value": udt.description}
                },
                "tagType": "UdtType",
                "tags": tags
            }
            udt_defs.append(udt_def)

        # Wrap in folder if udt_prefix is specified
        if self.udt_prefix:
            result = {
                "name": self.udt_prefix,
                "tagType": "Folder",
                "tags": udt_defs
            }
        else:
            result = {"tags": udt_defs}

        return json.dumps(result, indent=2)

    def export_tags(self, project: PLCProject) -> str:
        """
        Export tag instances as Ignition JSON.

        Args:
            project: PLCProject to export

        Returns:
            JSON string of tag instances
        """
        address_provider = create_address_provider(self.protocol, self.device_name)

        all_tags = []

        # Export global tags
        for tag in project.global_tags:
            if self._should_export_tag(tag):
                all_tags.extend(self._build_tag_instance(tag, address_provider, project.udts, ""))

        # Export program tags with folder structure
        for program in project.programs:
            program_tags = []
            for tag in program.tags:
                if self._should_export_tag(tag):
                    program_path = f"Program {program.name}"
                    program_tags.extend(self._build_tag_instance(tag, address_provider, project.udts, program_path))

            if program_tags:
                if self.create_folders:
                    # Create true folder structure
                    all_tags.append({
                        "name": f"Program {program.name}",
                        "tagType": "Folder",
                        "tags": program_tags
                    })
                else:
                    # Flatten into global namespace
                    all_tags.extend(program_tags)

        result = {"tags": all_tags}
        return json.dumps(result, indent=2)

    def export_simulator_csv(self, project: PLCProject, include_hierarchy: bool = True) -> str:
        """
        Export simulator program as CSV.

        Args:
            project: PLCProject to export
            include_hierarchy: If True, creates hierarchical browse paths matching PLC structure
                              (Devices/[DeviceName]/Controller:Global, Program:Name, etc.)

        Returns:
            CSV string for Programmable Device Simulator
        """
        address_provider = create_address_provider("simulator", self.device_name)

        rows = []
        header = ["Time Interval", "Browse Path", "Value Source", "Data Type"]

        if include_hierarchy:
            # Create hierarchical structure matching real PLC browsing
            # Use project name or device name for the device folder
            device_name = project.name if project.name else self.device_name

            # Create base path: Devices/[DeviceName]
            base_path = f"Devices/[{device_name}]"

            # Add organizational folders (empty, just for structure)
            # Note: Ignition simulator may or may not show empty folders, but we document the structure

            # Export global tags under Controller:Global
            controller_path = f"{base_path}/Controller:Global"
            for tag in project.global_tags:
                if self._should_export_tag(tag):
                    rows.extend(self._build_simulator_rows(tag, address_provider, project.udts, controller_path))

            # Export program tags under Program:ProgramName
            for program in project.programs:
                program_path = f"{base_path}/Program:{program.name}"
                for tag in program.tags:
                    if self._should_export_tag(tag):
                        rows.extend(self._build_simulator_rows(tag, address_provider, project.udts, program_path))
        else:
            # Flat structure (legacy behavior)
            # Export global tags
            for tag in project.global_tags:
                if self._should_export_tag(tag):
                    rows.extend(self._build_simulator_rows(tag, address_provider, project.udts, ""))

            # Export program tags
            for program in project.programs:
                for tag in program.tags:
                    if self._should_export_tag(tag):
                        program_path = program.name
                        rows.extend(self._build_simulator_rows(tag, address_provider, project.udts, program_path))

        # Convert to CSV
        output = StringIO()
        writer = csv.writer(output)
        writer.writerow(header)
        writer.writerows(rows)
        return output.getvalue()

    def _build_udt_member_tags(
        self,
        member: Tag,
        address_provider: AddressProvider,
        udts: List[UDT]
    ) -> List[Dict[str, Any]]:
        """Build UDT member tag definitions"""
        tags = []

        if member.is_array():
            # Create folder for array
            array_tags = []
            for i in range(member.array_length):
                member_path = f"{member.name}[{i}]"
                array_tags.extend(self._build_single_udt_member(member, address_provider, udts, member_path, str(i)))

            tags.append({
                "name": member.name,
                "tagType": "Folder",
                "tags": array_tags
            })
        else:
            tags.extend(self._build_single_udt_member(member, address_provider, udts, member.name, member.name))

        return tags

    def _build_single_udt_member(
        self,
        member: Tag,
        address_provider: AddressProvider,
        udts: List[UDT],
        opc_path: str,
        tag_name: str
    ) -> List[Dict[str, Any]]:
        """Build a single UDT member tag"""
        if member.is_atomic():
            # Atomic tag
            return [{
                "opcItemPath": {
                    "bindType": "parameter",
                    "binding": address_provider.get_tag_address(member, opc_path)
                },
                "valueSource": address_provider.get_value_source(),
                "dataType": member.data_type.to_ignition(),
                "name": tag_name,
                "documentation": member.description,
                "tagType": "AtomicTag",
                "opcServer": address_provider.get_server_name()
            }]
        else:
            # Nested UDT instance
            type_id = member.data_type if isinstance(member.data_type, str) else member.data_type.name
            if self.udt_prefix:
                type_id = f"{self.udt_prefix}/{type_id}"

            nested_tags = self._get_nested_udt_tags(member, udts)

            return [{
                "name": tag_name,
                "typeId": type_id,
                "parameters": {
                    "DeviceName": {
                        "dataType": "String",
                        "value": {"bindType": "parameter", "binding": "{DeviceName}"}
                    },
                    "TagPrefix": {
                        "dataType": "String",
                        "value": {"bindType": "parameter", "binding": f"{{TagPrefix}}.{opc_path}"}
                    },
                    "Description": {
                        "dataType": "String",
                        "value": {"bindType": "parameter", "binding": "{Description}"}
                    }
                },
                "tagType": "UdtInstance",
                "tags": nested_tags
            }]

    def _build_tag_instance(
        self,
        tag: Tag,
        address_provider: AddressProvider,
        udts: List[UDT],
        path_prefix: str
    ) -> List[Dict[str, Any]]:
        """Build tag instance for export"""
        tags = []
        full_path = f"{path_prefix}/{tag.name}" if path_prefix else tag.name

        if tag.is_array():
            # Create folder for array
            array_tags = []
            for i in range(tag.array_length):
                array_path = f"{full_path}[{i}]"
                array_tags.extend(self._build_single_tag_instance(tag, address_provider, udts, array_path, str(i)))

            tags.append({
                "name": tag.name,
                "tagType": "Folder",
                "tags": array_tags
            })
        else:
            tags.extend(self._build_single_tag_instance(tag, address_provider, udts, full_path, tag.name))

        return tags

    def _build_single_tag_instance(
        self,
        tag: Tag,
        address_provider: AddressProvider,
        udts: List[UDT],
        tag_path: str,
        tag_name: str
    ) -> List[Dict[str, Any]]:
        """Build a single tag instance"""
        if tag.is_atomic():
            # Atomic tag
            return [{
                "opcItemPath": address_provider.get_tag_address(tag, tag_path),
                "valueSource": address_provider.get_value_source(),
                "dataType": tag.data_type.to_ignition(),
                "name": tag_name,
                "documentation": tag.description,
                "tagType": "AtomicTag",
                "opcServer": address_provider.get_server_name()
            }]
        else:
            # UDT instance
            type_id = tag.data_type if isinstance(tag.data_type, str) else tag.data_type.name
            if self.udt_prefix:
                type_id = f"{self.udt_prefix}/{type_id}"

            return [{
                "name": tag_name,
                "typeId": type_id,
                "parameters": {
                    "DeviceName": {"dataType": "String", "value": {"bindType": "parameter", "binding": self.device_name}},
                    "TagPrefix": {"dataType": "String", "value": {"bindType": "parameter", "binding": tag_path}},
                    "Description": {"dataType": "String", "value": {"bindType": "parameter", "binding": tag.description}}
                },
                "tagType": "UdtInstance",
                "tags": self._get_nested_udt_tags(tag, udts)
            }]

    def _build_simulator_rows(
        self,
        tag: Tag,
        address_provider: AddressProvider,
        udts: List[UDT],
        path_prefix: str
    ) -> List[List[str]]:
        """Build simulator CSV rows for a tag"""
        rows = []
        # Use "/" for hierarchical browse paths, "." for member access
        separator = "/" if "/" in path_prefix else "."
        full_path = f"{path_prefix}{separator}{tag.name}" if path_prefix else tag.name

        if tag.is_array():
            for i in range(tag.array_length):
                array_path = f"{full_path}[{i}]"
                rows.extend(self._build_single_simulator_row(tag, address_provider, udts, array_path))
        else:
            rows.extend(self._build_single_simulator_row(tag, address_provider, udts, full_path))

        return rows

    def _build_single_simulator_row(
        self,
        tag: Tag,
        address_provider: AddressProvider,
        udts: List[UDT],
        tag_path: str
    ) -> List[List[str]]:
        """Build a single simulator row"""
        if tag.is_atomic():
            value = str(tag.initial_value) if tag.initial_value is not None else ""
            return [[
                "0",  # Time interval
                address_provider.get_tag_address(tag, tag_path),  # Browse path
                value,  # Value source
                tag.data_type.to_simulation()  # Data type
            ]]
        else:
            # UDT - recursively build members
            rows = []
            udt = self._find_udt(tag.data_type, udts)
            if udt:
                for member in udt.members:
                    if not member.hidden:
                        member_path = f"{tag_path}.{member.name}"
                        rows.extend(self._build_single_simulator_row(member, address_provider, udts, member_path))
            return rows

    def _get_nested_udt_tags(self, tag: Tag, udts: List[UDT]) -> List[Dict[str, Any]]:
        """Get nested UDT tags for overrides"""
        # This would contain tag overrides (comments, etc.)
        # For now, return empty list
        return []

    def _find_udt(self, data_type, udts: List[UDT]) -> Optional[UDT]:
        """Find UDT by name or DataType"""
        search_name = data_type if isinstance(data_type, str) else (data_type.name if hasattr(data_type, 'name') else str(data_type))
        for udt in udts:
            if udt.name.upper() == search_name.upper():
                return udt
        return None

    def _should_export_tag(self, tag: Tag) -> bool:
        """Check if tag should be exported"""
        if tag.hidden and not self._should_include_hidden():
            return False
        return tag.metadata.get("selected", True)

    def _should_include_hidden(self) -> bool:
        """Check if hidden tags should be included"""
        return False  # TODO: Make configurable
