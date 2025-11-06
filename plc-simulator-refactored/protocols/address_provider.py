"""
Protocol abstraction layer for generating tag addresses.
Each protocol (OPC-UA, Modbus, EtherNet/IP, etc.) has its own addressing scheme.
"""

from abc import ABC, abstractmethod
from typing import Optional, Dict, Any

# Handle imports for both module and direct execution
try:
    from models.tag_model import Tag, DataType
except ImportError:
    from ..models.tag_model import Tag, DataType


class AddressProvider(ABC):
    """
    Abstract base class for protocol-specific address providers.
    Each protocol implements this to generate appropriate addresses/paths.
    """

    def __init__(self, device_name: str, config: Optional[Dict[str, Any]] = None):
        self.device_name = device_name
        self.config = config or {}

    @abstractmethod
    def get_tag_address(self, tag: Tag, tag_path: str) -> str:
        """
        Generate the protocol-specific address for a tag.

        Args:
            tag: The tag object
            tag_path: Hierarchical path (e.g., "Program MainProgram/AI_PLS101_DATA.PV")

        Returns:
            Protocol-specific address string
        """
        pass

    @abstractmethod
    def get_value_source(self) -> str:
        """Get the value source type (e.g., 'opc', 'modbus', 'memory')"""
        pass

    @abstractmethod
    def get_server_name(self) -> Optional[str]:
        """Get the server name if applicable (e.g., 'Ignition OPC UA Server')"""
        pass

    @abstractmethod
    def supports_hierarchy(self) -> bool:
        """Does this protocol support hierarchical browsing?"""
        pass


class OPCUAAddressProvider(AddressProvider):
    """
    OPC-UA address provider.
    Generates OPC-UA NodeId strings like: ns=1;s=[DeviceName]TagPath
    """

    def __init__(self, device_name: str, namespace_index: int = 1, config: Optional[Dict[str, Any]] = None):
        super().__init__(device_name, config)
        self.namespace_index = namespace_index
        self.server_name = config.get("server_name", "Ignition OPC UA Server") if config else "Ignition OPC UA Server"

    def get_tag_address(self, tag: Tag, tag_path: str) -> str:
        """
        Generate OPC-UA address.
        Example: ns=1;s=[SimulatorDevice]AI_PLS101_DATA.PV
        """
        # Remove "Program " prefix if present (for cleaner OPC paths)
        clean_path = tag_path.replace("Program ", "")
        return f"ns={self.namespace_index};s=[{self.device_name}]{clean_path}"

    def get_value_source(self) -> str:
        return "opc"

    def get_server_name(self) -> Optional[str]:
        return self.server_name

    def supports_hierarchy(self) -> bool:
        return True


class ParameterizedOPCUAAddressProvider(AddressProvider):
    """
    Parameterized OPC-UA address provider for UDT definitions.
    Uses placeholders like {DeviceName} and {TagPrefix} that get resolved at instance creation.
    """

    def __init__(self, namespace_index: int = 1, config: Optional[Dict[str, Any]] = None):
        super().__init__("", config)  # No device name needed for parameterized
        self.namespace_index = namespace_index
        self.server_name = config.get("server_name", "Ignition OPC UA Server") if config else "Ignition OPC UA Server"

    def get_tag_address(self, tag: Tag, tag_path: str) -> str:
        """
        Generate parameterized OPC-UA address.
        Example: ns=1;s=[{DeviceName}]{TagPrefix}.PV
        """
        # For UDT members, tag_path is just the member name
        return f"ns={self.namespace_index};s=[{{DeviceName}}]{{TagPrefix}}.{tag_path}"

    def get_value_source(self) -> str:
        return "opc"

    def get_server_name(self) -> Optional[str]:
        return self.server_name

    def supports_hierarchy(self) -> bool:
        return True


class ModbusAddressProvider(AddressProvider):
    """
    Modbus address provider.
    Maps tags to Modbus register addresses.
    """

    def __init__(self, device_name: str, start_address: int = 40001, config: Optional[Dict[str, Any]] = None):
        super().__init__(device_name, config)
        self.start_address = start_address
        self.current_address = start_address
        self.address_map = {}  # tag_path -> address mapping

    def get_tag_address(self, tag: Tag, tag_path: str) -> str:
        """
        Generate Modbus address.
        Example: [DeviceName]HR40001
        """
        if tag_path not in self.address_map:
            # Calculate register count based on data type
            register_count = self._get_register_count(tag.data_type)
            self.address_map[tag_path] = self.current_address
            self.current_address += register_count * (tag.array_length if tag.is_array() else 1)

        address = self.address_map[tag_path]
        return f"[{self.device_name}]HR{address}"

    def _get_register_count(self, data_type: DataType) -> int:
        """Get number of Modbus registers needed for this data type"""
        if data_type == DataType.BOOL:
            return 1  # Can pack into coils, but using holding registers for simplicity
        elif data_type in [DataType.INT1, DataType.INT2]:
            return 1
        elif data_type in [DataType.INT4, DataType.FLOAT4]:
            return 2
        elif data_type == DataType.STRING:
            return 40  # Assume 80 character string (2 bytes per register)
        return 1

    def get_value_source(self) -> str:
        return "opc"  # Modbus through OPC

    def get_server_name(self) -> Optional[str]:
        return "Modbus Device"

    def supports_hierarchy(self) -> bool:
        return False  # Modbus doesn't support hierarchical browsing


class EtherNetIPAddressProvider(AddressProvider):
    """
    EtherNet/IP (CIP) address provider.
    Generates CIP paths for Allen-Bradley PLCs accessed over EtherNet/IP.
    """

    def __init__(self, device_name: str, slot: int = 0, config: Optional[Dict[str, Any]] = None):
        super().__init__(device_name, config)
        self.slot = slot

    def get_tag_address(self, tag: Tag, tag_path: str) -> str:
        """
        Generate EtherNet/IP CIP path.
        Example: [DeviceName]Program:MainProgram.AI_PLS101_DATA.PV
        """
        # EtherNet/IP uses ":" to separate program scope
        clean_path = tag_path.replace("Program ", "Program:")
        clean_path = clean_path.replace("/", ".")
        return f"[{self.device_name}]{clean_path}"

    def get_value_source(self) -> str:
        return "opc"  # EtherNet/IP through OPC

    def get_server_name(self) -> Optional[str]:
        return "Allen-Bradley Logix Driver"

    def supports_hierarchy(self) -> bool:
        return True


class MemoryAddressProvider(AddressProvider):
    """
    Memory tag provider for internal Ignition tags.
    No external device, just memory-based tags.
    """

    def __init__(self, config: Optional[Dict[str, Any]] = None):
        super().__init__("", config)

    def get_tag_address(self, tag: Tag, tag_path: str) -> str:
        """
        Memory tags don't need addresses.
        Return empty string as they're value-source: memory
        """
        return ""

    def get_value_source(self) -> str:
        return "memory"

    def get_server_name(self) -> Optional[str]:
        return None

    def supports_hierarchy(self) -> bool:
        return True


class SimulatorAddressProvider(AddressProvider):
    """
    Address provider for Ignition Programmable Device Simulator.
    Supports both hierarchical browse paths (with /) and flat browse paths (with .).
    """

    def __init__(self, device_name: str, config: Optional[Dict[str, Any]] = None):
        super().__init__(device_name, config)

    def get_tag_address(self, tag: Tag, tag_path: str) -> str:
        """
        Generate simulator browse path.
        Preserves hierarchical structure if present (Devices/[Name]/Controller:Global/TAG).
        For flat structure, uses dotted notation (AI_PLS101_DATA.PV).
        """
        # Check if this is a hierarchical path (starts with "Devices/")
        if tag_path.startswith("Devices/"):
            # Hierarchical structure - preserve "/" separators, but use "." for UDT members
            # This allows paths like: Devices/[Name]/Controller:Global/UDT_TAG.Member
            return tag_path
        else:
            # Legacy flat structure - remove "Program " prefix and replace "/" with "."
            clean_path = tag_path.replace("Program ", "")
            clean_path = clean_path.replace("/", ".")
            return clean_path

    def get_value_source(self) -> str:
        return "opc"

    def get_server_name(self) -> Optional[str]:
        return "Programmable Device Simulator"

    def supports_hierarchy(self) -> bool:
        return True  # Now supports hierarchical browsing


def create_address_provider(protocol: str, device_name: str, config: Optional[Dict[str, Any]] = None) -> AddressProvider:
    """
    Factory function to create the appropriate address provider.

    Args:
        protocol: Protocol type ("opcua", "modbus", "ethernetip", "memory", "simulator")
        device_name: Device name
        config: Optional configuration dictionary

    Returns:
        AddressProvider instance
    """
    protocol = protocol.lower()

    if protocol == "opcua":
        return OPCUAAddressProvider(device_name, config=config)
    elif protocol == "opcua_parameterized":
        return ParameterizedOPCUAAddressProvider(config=config)
    elif protocol == "modbus":
        return ModbusAddressProvider(device_name, config=config)
    elif protocol == "ethernetip":
        return EtherNetIPAddressProvider(device_name, config=config)
    elif protocol == "memory":
        return MemoryAddressProvider(config=config)
    elif protocol == "simulator":
        return SimulatorAddressProvider(device_name, config=config)
    else:
        raise ValueError(f"Unknown protocol: {protocol}")
