"""
Generic OPC-UA CSV parser for tag lists exported from various PLC/SCADA systems.
Supports simple CSV format with columns: TagName, DataType, Description, InitialValue, Folder
"""

import csv
from typing import Optional, Dict, Any, List

# Handle imports for both module and direct execution
try:
    from parsers.base_parser import PLCParser
    from models.tag_model import PLCProject, Tag, UDT, Program, DataType, TagScope
except ImportError:
    from .base_parser import PLCParser
    from ..models.tag_model import PLCProject, Tag, UDT, Program, DataType, TagScope


class OPCUACSVParser(PLCParser):
    """
    Parser for generic OPC-UA tag exports in CSV format.
    This works with tag lists from any OPC-UA server that can export to CSV.

    Expected CSV format:
    TagName,DataType,Description,InitialValue,Folder,ArrayLength
    Tank1_Level,REAL,Tank 1 level sensor,,Tanks,
    Pump_Status,BOOL,Main pump status,false,Pumps,
    Temperatures,INT,Temperature readings,,Sensors,10
    """

    # Data type mappings from common OPC-UA types
    DATATYPE_MAPPING = {
        # OPC-UA standard types
        "Boolean": DataType.BOOL,
        "Byte": DataType.INT1,
        "Int16": DataType.INT2,
        "Int32": DataType.INT4,
        "Float": DataType.FLOAT4,
        "String": DataType.STRING,
        # Common aliases
        "BOOL": DataType.BOOL,
        "INT": DataType.INT2,
        "DINT": DataType.INT4,
        "REAL": DataType.FLOAT4,
        "STRING": DataType.STRING,
        # Siemens types
        "S7_Boolean": DataType.BOOL,
        "S7_Int": DataType.INT2,
        "S7_DInt": DataType.INT4,
        "S7_Real": DataType.FLOAT4,
        "S7_String": DataType.STRING,
    }

    def __init__(self, config: Optional[Dict[str, Any]] = None):
        super().__init__(config)
        self.delimiter = config.get('delimiter', ',') if config else ','
        self.has_header = config.get('has_header', True) if config else True

    def can_parse(self, file_path: str, file_content: Optional[str] = None) -> bool:
        """Check if this is a CSV file with tag data"""
        if not file_path.lower().endswith('.csv'):
            return False

        # Try to validate content
        if file_content:
            lines = file_content.split('\n')
            if len(lines) < 2:
                return False

            # Check if first line looks like a header
            first_line = lines[0].strip().lower()
            return any(keyword in first_line for keyword in ['tagname', 'name', 'datatype', 'type'])

        return True

    def get_supported_extensions(self) -> list:
        """Get supported file extensions"""
        return ['.csv', '.CSV']

    def get_plc_type_name(self) -> str:
        """Get PLC type name"""
        return "Generic OPC-UA CSV"

    def parse(self, file_path: str, options: Optional[Dict[str, Any]] = None) -> PLCProject:
        """
        Parse CSV file and return PLCProject.

        Options:
            - select_tags: bool - Default selection state for tags (default: True)
        """
        options = options or {}
        select_tags = options.get('select_tags', True)

        tags_by_folder = {}  # folder_path -> list of tags
        global_tags = []

        with open(file_path, 'r', encoding='utf-8') as f:
            reader = csv.DictReader(f, delimiter=self.delimiter) if self.has_header else csv.reader(f, delimiter=self.delimiter)

            for row in reader:
                if self.has_header:
                    tag = self._parse_csv_row_dict(row, select_tags)
                else:
                    tag = self._parse_csv_row_list(row, select_tags)

                if tag:
                    folder = tag.folder_path
                    if folder:
                        if folder not in tags_by_folder:
                            tags_by_folder[folder] = []
                        tags_by_folder[folder].append(tag)
                    else:
                        global_tags.append(tag)

        # Create programs from folders (treat each folder as a program)
        programs = []
        for folder_name, folder_tags in tags_by_folder.items():
            program = Program(
                name=folder_name,
                tags=folder_tags,
                description=f"Tags from folder: {folder_name}",
                metadata={"source": "folder"}
            )
            programs.append(program)

        return PLCProject(
            name="CSV Import",
            plc_type=self.get_plc_type_name(),
            udts=[],
            global_tags=global_tags,
            programs=programs,
            metadata={"source_file": file_path}
        )

    def _parse_csv_row_dict(self, row: Dict[str, str], select_tags: bool) -> Optional[Tag]:
        """Parse a CSV row with header (dictionary)"""
        # Try different column name variations
        tag_name = row.get('TagName') or row.get('Name') or row.get('name') or row.get('Tag')
        if not tag_name:
            return None

        data_type_str = row.get('DataType') or row.get('Type') or row.get('type') or 'DINT'
        description = row.get('Description') or row.get('Comment') or row.get('description') or ''
        initial_value = row.get('InitialValue') or row.get('Value') or row.get('value')
        folder = row.get('Folder') or row.get('folder') or row.get('Path')
        array_length_str = row.get('ArrayLength') or row.get('array_length')

        # Parse array length
        array_length = None
        if array_length_str and array_length_str.strip():
            try:
                array_length = int(array_length_str)
            except:
                pass

        # Map data type
        data_type = self.DATATYPE_MAPPING.get(data_type_str, DataType.INT4)

        return Tag(
            name=tag_name,
            data_type=data_type,
            scope=TagScope.GLOBAL if not folder else TagScope.PROGRAM,
            description=description,
            array_length=array_length,
            initial_value=initial_value,
            folder_path=folder,
            metadata={"selected": select_tags, "data_type_str": data_type_str}
        )

    def _parse_csv_row_list(self, row: List[str], select_tags: bool) -> Optional[Tag]:
        """Parse a CSV row without header (list)"""
        if len(row) < 2:
            return None

        tag_name = row[0].strip()
        data_type_str = row[1].strip() if len(row) > 1 else 'DINT'
        description = row[2].strip() if len(row) > 2 else ''
        initial_value = row[3].strip() if len(row) > 3 else None
        folder = row[4].strip() if len(row) > 4 else None
        array_length = None
        if len(row) > 5 and row[5].strip():
            try:
                array_length = int(row[5].strip())
            except:
                pass

        data_type = self.DATATYPE_MAPPING.get(data_type_str, DataType.INT4)

        return Tag(
            name=tag_name,
            data_type=data_type,
            scope=TagScope.GLOBAL if not folder else TagScope.PROGRAM,
            description=description,
            array_length=array_length,
            initial_value=initial_value,
            folder_path=folder,
            metadata={"selected": select_tags, "data_type_str": data_type_str}
        )
