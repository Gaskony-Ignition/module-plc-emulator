"""
Multi-Protocol PLC Simulator for Ignition

A refactored, extensible PLC tag parser and simulator generator that supports
multiple PLC brands and protocols for Inductive Automation's Ignition platform.
"""

__version__ = "2.0.0"
__author__ = "PLC Simulator Contributors"

# Make key classes easily accessible
from .models.tag_model import PLCProject, Tag, UDT, Program, DataType, TagScope
from .parsers.base_parser import PLCParser, register_parser, get_parser_for_file
from .exporters.ignition_exporter import IgnitionExporter
from .protocols.address_provider import AddressProvider, create_address_provider

# Convenient imports
from .parsers.rockwell_l5k_parser import RockwellL5KParser
from .parsers.opcua_csv_parser import OPCUACSVParser
from .parsers.siemens_tia_parser import SiemensTIAParser
from .parsers.schneider_parser import SchneiderElectricParser
from .parsers.beckhoff_parser import BeckhoffTwinCATParser

__all__ = [
    # Core classes
    'PLCProject',
    'Tag',
    'UDT',
    'Program',
    'DataType',
    'TagScope',
    'PLCParser',
    'AddressProvider',
    'IgnitionExporter',

    # Parsers
    'RockwellL5KParser',
    'OPCUACSVParser',
    'SiemensTIAParser',
    'SchneiderElectricParser',
    'BeckhoffTwinCATParser',

    # Utility functions
    'register_parser',
    'get_parser_for_file',
    'create_address_provider',
]
