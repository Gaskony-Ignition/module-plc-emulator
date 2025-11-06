"""
Common tag model used by all parsers and exporters.
This provides a protocol-agnostic representation of PLC tags.
"""

from enum import Enum
from typing import Optional, List, Dict, Any


class DataType(Enum):
    """Standard data types across all PLC platforms"""
    BOOL = "Boolean"
    INT1 = "Int1"  # SINT in Rockwell, BYTE in Siemens
    INT2 = "Int2"  # INT in Rockwell, INT in Siemens
    INT4 = "Int4"  # DINT in Rockwell, DINT in Siemens
    FLOAT4 = "Float4"  # REAL in Rockwell, REAL in Siemens
    STRING = "String"
    UDT = "UDT"  # User-defined type

    @classmethod
    def from_rockwell(cls, rockwell_type: str):
        """Convert Rockwell data type to standard"""
        mapping = {
            "BOOL": cls.BOOL,
            "BIT": cls.BOOL,
            "SINT": cls.INT1,
            "INT": cls.INT2,
            "DINT": cls.INT4,
            "REAL": cls.FLOAT4,
            "STRING": cls.STRING
        }
        # Raise exception if not found - don't default to UDT
        if rockwell_type.upper() in mapping:
            return mapping[rockwell_type.upper()]
        raise ValueError(f"Unknown Rockwell data type: {rockwell_type}")

    @classmethod
    def from_siemens(cls, siemens_type: str):
        """Convert Siemens data type to standard"""
        mapping = {
            "BOOL": cls.BOOL,
            "BYTE": cls.INT1,
            "INT": cls.INT2,
            "DINT": cls.INT4,
            "REAL": cls.FLOAT4,
            "STRING": cls.STRING,
            "CHAR": cls.STRING
        }
        return mapping.get(siemens_type.upper(), cls.UDT)

    @classmethod
    def from_beckhoff(cls, beckhoff_type: str):
        """Convert Beckhoff/IEC 61131-3 data type to standard"""
        mapping = {
            "BOOL": cls.BOOL,
            "BYTE": cls.INT1,
            "SINT": cls.INT1,
            "INT": cls.INT2,
            "DINT": cls.INT4,
            "REAL": cls.FLOAT4,
            "STRING": cls.STRING
        }
        return mapping.get(beckhoff_type.upper(), cls.UDT)

    def to_ignition(self) -> str:
        """Convert to Ignition data type"""
        return self.value

    def to_simulation(self) -> str:
        """Convert to simulation data type"""
        sim_mapping = {
            self.BOOL: "Boolean",
            self.INT1: "Int16",
            self.INT2: "Int16",
            self.INT4: "Int32",
            self.FLOAT4: "Float",
            self.STRING: "String"
        }
        return sim_mapping.get(self, "String")


class TagScope(Enum):
    """Scope of the tag"""
    GLOBAL = "global"
    PROGRAM = "program"
    LOCAL = "local"


class Tag:
    """
    Universal tag representation that works across all PLC types.
    This is the common model that all parsers produce.
    """

    def __init__(
        self,
        name: str,
        data_type: DataType,
        scope: TagScope = TagScope.GLOBAL,
        description: str = "",
        array_length: Optional[int] = None,
        initial_value: Optional[Any] = None,
        hidden: bool = False,
        folder_path: Optional[str] = None,
        udt_members: Optional[List['Tag']] = None,
        metadata: Optional[Dict[str, Any]] = None
    ):
        self.name = name
        self.data_type = data_type
        self.scope = scope
        self.description = description
        self.array_length = array_length
        self.initial_value = initial_value
        self.hidden = hidden
        self.folder_path = folder_path  # e.g., "Program MainProgram/Pumps"
        self.udt_members = udt_members or []
        self.metadata = metadata or {}  # Protocol-specific data

    def get_full_path(self) -> str:
        """Get the full hierarchical path of this tag"""
        if self.folder_path:
            return f"{self.folder_path}/{self.name}"
        return self.name

    def is_atomic(self) -> bool:
        """Check if this is an atomic (simple) tag"""
        # Atomic tags have DataType enum values, UDT instances have string type names
        return isinstance(self.data_type, DataType)

    def is_array(self) -> bool:
        """Check if this is an array tag"""
        return self.array_length is not None

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization"""
        return {
            "name": self.name,
            "data_type": self.data_type.name if isinstance(self.data_type, DataType) else str(self.data_type),
            "scope": self.scope.value,
            "description": self.description,
            "array_length": self.array_length,
            "initial_value": self.initial_value,
            "hidden": self.hidden,
            "folder_path": self.folder_path,
            "udt_members": [m.to_dict() for m in self.udt_members] if self.udt_members else [],
            "metadata": self.metadata
        }


class UDT:
    """
    Universal UDT (User-Defined Type) representation.
    Works for Rockwell UDTs, Siemens UDTs, Beckhoff structures, etc.
    """

    def __init__(
        self,
        name: str,
        description: str = "",
        members: Optional[List[Tag]] = None,
        is_string_family: bool = False,
        metadata: Optional[Dict[str, Any]] = None
    ):
        self.name = name
        self.description = description
        self.members = members or []
        self.is_string_family = is_string_family
        self.metadata = metadata or {}

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization"""
        return {
            "name": self.name,
            "description": self.description,
            "members": [m.to_dict() for m in self.members],
            "is_string_family": self.is_string_family,
            "metadata": self.metadata
        }


class Program:
    """
    Represents a PLC program or organizational unit.
    Maps to Rockwell Programs, Siemens FCs/FBs, Beckhoff POUs, etc.
    """

    def __init__(
        self,
        name: str,
        tags: Optional[List[Tag]] = None,
        description: str = "",
        metadata: Optional[Dict[str, Any]] = None
    ):
        self.name = name
        self.tags = tags or []
        self.description = description
        self.metadata = metadata or {}

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization"""
        return {
            "name": self.name,
            "tags": [t.to_dict() for t in self.tags],
            "description": self.description,
            "metadata": self.metadata
        }


class PLCProject:
    """
    Top-level representation of a complete PLC project.
    Contains all UDTs, global tags, and programs.
    """

    def __init__(
        self,
        name: str = "",
        plc_type: str = "",
        udts: Optional[List[UDT]] = None,
        global_tags: Optional[List[Tag]] = None,
        programs: Optional[List[Program]] = None,
        metadata: Optional[Dict[str, Any]] = None
    ):
        self.name = name
        self.plc_type = plc_type
        self.udts = udts or []
        self.global_tags = global_tags or []
        self.programs = programs or []
        self.metadata = metadata or {}

    def get_all_tags(self) -> List[Tag]:
        """Get all tags (global + program)"""
        all_tags = list(self.global_tags)
        for program in self.programs:
            all_tags.extend(program.tags)
        return all_tags

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization"""
        return {
            "name": self.name,
            "plc_type": self.plc_type,
            "udts": [u.to_dict() for u in self.udts],
            "global_tags": [t.to_dict() for t in self.global_tags],
            "programs": [p.to_dict() for p in self.programs],
            "metadata": self.metadata
        }
