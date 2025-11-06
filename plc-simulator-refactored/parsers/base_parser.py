"""
Base parser interface that all PLC file parsers implement.
"""

from abc import ABC, abstractmethod
from typing import Optional, Dict, Any
import sys
from pathlib import Path

# Handle imports for both module and direct execution
try:
    from models.tag_model import PLCProject
except ImportError:
    from ..models.tag_model import PLCProject


class PLCParser(ABC):
    """
    Abstract base class for PLC file parsers.
    Each PLC type (Rockwell, Siemens, Beckhoff, etc.) implements this interface.
    """

    def __init__(self, config: Optional[Dict[str, Any]] = None):
        self.config = config or {}

    @abstractmethod
    def can_parse(self, file_path: str, file_content: Optional[str] = None) -> bool:
        """
        Check if this parser can handle the given file.

        Args:
            file_path: Path to the file
            file_content: Optional file content (if already loaded)

        Returns:
            True if this parser can handle the file
        """
        pass

    @abstractmethod
    def parse(self, file_path: str, options: Optional[Dict[str, Any]] = None) -> PLCProject:
        """
        Parse the PLC file and return a PLCProject object.

        Args:
            file_path: Path to the file to parse
            options: Optional parsing options (e.g., selectTags, allowHidden, useStoredValues)

        Returns:
            PLCProject object containing all parsed data
        """
        pass

    @abstractmethod
    def get_supported_extensions(self) -> list:
        """
        Get list of file extensions this parser supports.

        Returns:
            List of file extensions (e.g., ['.l5k', '.L5K'])
        """
        pass

    @abstractmethod
    def get_plc_type_name(self) -> str:
        """
        Get the human-readable name of the PLC type.

        Returns:
            PLC type name (e.g., "Rockwell Logix 5000", "Siemens TIA Portal")
        """
        pass

    def validate_file(self, file_path: str) -> tuple[bool, Optional[str]]:
        """
        Validate that the file is well-formed.

        Args:
            file_path: Path to the file to validate

        Returns:
            Tuple of (is_valid, error_message)
        """
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                content = f.read()
            return self.can_parse(file_path, content), None
        except Exception as e:
            return False, str(e)


class ParserRegistry:
    """
    Registry for all available parsers.
    Automatically selects the appropriate parser based on file type.
    """

    def __init__(self):
        self._parsers: list[PLCParser] = []

    def register(self, parser: PLCParser):
        """Register a parser"""
        self._parsers.append(parser)

    def get_parser(self, file_path: str, file_content: Optional[str] = None) -> Optional[PLCParser]:
        """
        Get the appropriate parser for a file.

        Args:
            file_path: Path to the file
            file_content: Optional file content (if already loaded)

        Returns:
            Parser instance or None if no parser found
        """
        for parser in self._parsers:
            if parser.can_parse(file_path, file_content):
                return parser
        return None

    def get_all_parsers(self) -> list[PLCParser]:
        """Get all registered parsers"""
        return self._parsers.copy()

    def get_supported_formats(self) -> Dict[str, str]:
        """
        Get all supported file formats.

        Returns:
            Dictionary mapping PLC type to supported extensions
        """
        formats = {}
        for parser in self._parsers:
            formats[parser.get_plc_type_name()] = parser.get_supported_extensions()
        return formats


# Global parser registry
_global_registry = ParserRegistry()


def register_parser(parser: PLCParser):
    """Register a parser with the global registry"""
    _global_registry.register(parser)


def get_parser_for_file(file_path: str, file_content: Optional[str] = None) -> Optional[PLCParser]:
    """Get the appropriate parser for a file from the global registry"""
    return _global_registry.get_parser(file_path, file_content)


def get_all_parsers() -> list[PLCParser]:
    """Get all registered parsers from the global registry"""
    return _global_registry.get_all_parsers()


def get_supported_formats() -> Dict[str, str]:
    """Get all supported file formats from the global registry"""
    return _global_registry.get_supported_formats()
