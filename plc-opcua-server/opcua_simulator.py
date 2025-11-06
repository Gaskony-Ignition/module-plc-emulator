#!/usr/bin/env python3
"""
PLC OPC-UA Simulator Server
Creates a hierarchical OPC-UA server from PLC code files (L5K, etc.)
with proper UDT folder structure matching real PLCs.
"""

import asyncio
import sys
import os
import logging
import time
from pathlib import Path
from typing import Dict, List, Optional
import fnmatch

# Add parent directory to path to import existing parsers
sys.path.insert(0, str(Path(__file__).parent.parent / "plc-simulator-refactored"))

from asyncua import Server, ua
from asyncua.common.node import Node
from parsers.rockwell_l5k_parser import RockwellL5KParser
from parsers.json_parser import JSONParser
from models.tag_model import PLCProject, Tag, UDT, Program, DataType, TagScope

# Local imports
from config_loader import SimulatorConfig
from simulation_engine import SimulationEngine

logger = logging.getLogger(__name__)


class PLCOPCUAServer:
    """
    OPC-UA Server that exposes PLC tags with hierarchical structure.
    """

    def __init__(self, config: SimulatorConfig):
        self.config = config
        server_config = config.get_server_config()

        self.endpoint = server_config.get('endpoint', 'opc.tcp://0.0.0.0:4840/plc-simulator/')
        self.server_name = server_config.get('name', 'PLC Simulator OPC-UA Server')
        self.namespace_uri = server_config.get('namespace', 'http://plc-simulator.opcua')

        self.server = Server()
        self.namespace_index = None
        self.projects: Dict[str, PLCProject] = {}  # Multiple PLC projects by name
        self.simulation_engines: Dict[str, SimulationEngine] = {}  # Simulation engine per PLC
        self.tag_nodes: Dict[str, Node] = {}  # Map tag paths to OPC-UA nodes
        self.simulation_tasks = []  # Background tasks for value updates

        # File watching for hot reload
        self.file_mtimes: Dict[str, float] = {}  # Track file modification times
        self.hot_reload_enabled = server_config.get('hot_reload', False)
        self.reload_interval = server_config.get('reload_interval', 5)

    async def init(self):
        """Initialize the OPC-UA server"""
        await self.server.init()
        self.server.set_endpoint(self.endpoint)

        # Set server properties
        self.server.set_server_name(self.server_name)
        await self.server.set_application_uri("urn:plc-simulator:opcua:server")

        # Register namespace
        self.namespace_index = await self.server.register_namespace(self.namespace_uri)

        logger.info(f"OPC-UA Server initialized at {self.endpoint}")
        logger.info(f"Namespace index: {self.namespace_index}")
        logger.info(f"Hot reload: {'enabled' if self.hot_reload_enabled else 'disabled'}")

    async def load_plc_projects(self):
        """Load all enabled PLC projects from configuration"""
        plc_configs = self.config.get_plcs()

        for plc_config in plc_configs:
            plc_name = plc_config.get('name', 'UnnamedPLC')
            file_path = plc_config.get('file')
            parser_type = plc_config.get('parser', 'rockwell')
            parser_options = plc_config.get('parser_options', {})

            logger.info(f"Loading PLC project '{plc_name}' from: {file_path}")

            # Track file modification time for hot reload
            if os.path.exists(file_path):
                self.file_mtimes[file_path] = os.path.getmtime(file_path)

            # Parse the PLC file
            if parser_type == "rockwell":
                parser = RockwellL5KParser()
                project = parser.parse(file_path, options=parser_options)
            elif parser_type == "json":
                parser = JSONParser()
                project = parser.parse(file_path, options=parser_options)
            else:
                logger.warning(f"Parser type '{parser_type}' not yet supported, skipping {plc_name}")
                continue

            # Store project
            self.projects[plc_name] = project

            logger.info(f"Loaded project: {project.name}")
            logger.info(f"  - UDTs: {len(project.udts)}")
            logger.info(f"  - Global Tags: {len(project.global_tags)}")
            logger.info(f"  - Programs: {len(project.programs)}")

            # Initialize simulation engine for this PLC
            sim_config = plc_config.get('simulation', {})
            if sim_config.get('enabled', False):
                self.simulation_engines[plc_name] = SimulationEngine()
                logger.info(f"  - Simulation: enabled (interval: {sim_config.get('update_interval', 1.0)}s)")
            else:
                logger.info(f"  - Simulation: disabled")

    async def build_address_space(self):
        """
        Build the OPC-UA address space from all loaded PLC projects.
        Creates hierarchical structure matching real PLC:

        Objects/
          └── Devices/
              ├── [PLC1]/
              │   ├── [Diagnostics]/
              │   ├── Controller:Global/
              │   │   ├── UDT_TAG/
              │   │   │   ├── Member1
              │   │   │   └── Member2
              │   │   └── AtomicTag
              │   └── Program:ProgramName/
              │       └── ...
              └── [PLC2]/
                  └── ...
        """
        if not self.projects:
            raise RuntimeError("No PLC projects loaded. Call load_plc_projects() first.")

        logger.info("Building OPC-UA address space...")

        # Get the Objects node (root of address space)
        objects = self.server.nodes.objects

        # Create Devices folder
        devices = await objects.add_folder(self.namespace_index, "Devices")
        logger.info("Created: Devices/")

        # Build address space for each PLC
        for plc_name, project in self.projects.items():
            logger.info(f"\nBuilding address space for PLC: {plc_name}")

            # Create device folder [DeviceName]
            device_name = project.name or plc_name
            device = await devices.add_folder(self.namespace_index, f"[{device_name}]")
            logger.info(f"Created: Devices/[{device_name}]/")

            # Create optional organizational folders
            diagnostics = await device.add_folder(self.namespace_index, "[Diagnostics]")
            logger.info(f"Created: Devices/[{device_name}]/[Diagnostics]/")

            # Create Controller:Global folder
            controller_global = await device.add_folder(self.namespace_index, "Controller:Global")
            logger.info(f"Created: Devices/[{device_name}]/Controller:Global/")

            # Add global tags
            await self._add_tags_to_folder(
                plc_name,
                controller_global,
                project.global_tags,
                project.udts,
                f"{plc_name}/Controller:Global"
            )

            # Create Program folders
            for program in project.programs:
                program_folder = await device.add_folder(
                    self.namespace_index,
                    f"Program:{program.name}"
                )
                logger.info(f"Created: Devices/[{device_name}]/Program:{program.name}/")

                await self._add_tags_to_folder(
                    plc_name,
                    program_folder,
                    program.tags,
                    project.udts,
                    f"{plc_name}/Program:{program.name}"
                )

        logger.info("\nAddress space build complete!")

    async def _add_tags_to_folder(
        self,
        plc_name: str,
        parent_folder: Node,
        tags: List[Tag],
        udts: List[UDT],
        base_path: str
    ):
        """
        Add tags to a folder, creating UDT subfolders as needed.

        Args:
            plc_name: Name of the PLC (for simulation lookup)
            parent_folder: OPC-UA folder node to add tags to
            tags: List of tags to add
            udts: List of UDT definitions for resolving types
            base_path: Base path for this folder (e.g., "PLC1/Controller:Global")
        """
        for tag in tags:
            try:
                tag_path = f"{base_path}/{tag.name}"
                if tag.is_atomic():
                    # Atomic tag - add as variable
                    await self._add_atomic_tag(plc_name, parent_folder, tag, tag_path)
                else:
                    # UDT instance - create folder and add members
                    await self._add_udt_instance(plc_name, parent_folder, tag, udts, tag_path)
            except Exception as e:
                logger.error(f"Error adding tag '{tag.name}': {e}")

    async def _add_atomic_tag(self, plc_name: str, parent: Node, tag: Tag, tag_path: str):
        """Add an atomic (simple) tag as an OPC-UA variable"""
        # Map DataType to OPC-UA variant type
        variant_type = self._get_variant_type(tag.data_type)

        # Get initial value (or from simulation)
        initial_value = self._get_initial_value(tag)

        # Create the variable node
        var = await parent.add_variable(
            self.namespace_index,
            tag.name,
            initial_value,
            varianttype=variant_type
        )

        # Set as writable (for simulation updates)
        await var.set_writable()

        # Store node reference for simulation updates
        self.tag_nodes[tag_path] = var

        # Initialize simulation state if simulation is enabled for this PLC
        if plc_name in self.simulation_engines:
            sim_engine = self.simulation_engines[plc_name]
            sim_engine.initialize_tag(tag_path, initial_value)

        logger.debug(f"  Added atomic tag: {tag.name} ({tag.data_type})")

    async def _add_udt_instance(self, plc_name: str, parent: Node, tag: Tag, udts: List[UDT], tag_path: str):
        """
        Add a UDT instance as a folder with member variables.
        This creates the hierarchical structure that matches real PLCs.
        """
        # Find the UDT definition
        udt = self._find_udt(tag.data_type, udts)
        if not udt:
            logger.warning(f"UDT definition not found for '{tag.data_type}', skipping tag '{tag.name}'")
            return

        # Create folder for this UDT instance
        udt_folder = await parent.add_folder(self.namespace_index, tag.name)
        logger.debug(f"  Added UDT folder: {tag.name} ({tag.data_type}, {len(udt.members)} members)")

        # Add all UDT members to the folder
        for member in udt.members:
            if member.hidden:
                continue  # Skip hidden members

            try:
                member_path = f"{tag_path}.{member.name}"
                if member.is_atomic():
                    # Add atomic member
                    await self._add_atomic_tag(plc_name, udt_folder, member, member_path)
                else:
                    # Nested UDT - recurse
                    await self._add_udt_instance(plc_name, udt_folder, member, udts, member_path)
            except Exception as e:
                logger.error(f"Error adding UDT member '{member.name}': {e}")

    def _find_udt(self, data_type, udts: List[UDT]) -> Optional[UDT]:
        """Find UDT definition by name"""
        search_name = data_type if isinstance(data_type, str) else str(data_type)
        for udt in udts:
            if udt.name.upper() == search_name.upper():
                return udt
        return None

    def _get_variant_type(self, data_type: DataType) -> ua.VariantType:
        """Map PLCProject DataType to OPC-UA VariantType"""
        if isinstance(data_type, str):
            # String type name (shouldn't happen for atomic tags)
            return ua.VariantType.String

        mapping = {
            DataType.BOOL: ua.VariantType.Boolean,
            DataType.INT1: ua.VariantType.SByte,
            DataType.INT2: ua.VariantType.Int16,
            DataType.INT4: ua.VariantType.Int32,
            DataType.FLOAT4: ua.VariantType.Float,
            DataType.STRING: ua.VariantType.String,
        }
        return mapping.get(data_type, ua.VariantType.String)

    def _get_initial_value(self, tag: Tag):
        """Get appropriate initial value for a tag"""
        if tag.initial_value is not None:
            try:
                # Try to parse the value
                if tag.data_type == DataType.BOOL:
                    return bool(int(tag.initial_value))
                elif tag.data_type in [DataType.INT1, DataType.INT2, DataType.INT4]:
                    return int(float(tag.initial_value))
                elif tag.data_type == DataType.FLOAT4:
                    return float(tag.initial_value)
                elif tag.data_type == DataType.STRING:
                    return str(tag.initial_value)
            except (ValueError, TypeError):
                pass

        # Default values by type
        defaults = {
            DataType.BOOL: False,
            DataType.INT1: 0,
            DataType.INT2: 0,
            DataType.INT4: 0,
            DataType.FLOAT4: 0.0,
            DataType.STRING: "",
        }
        return defaults.get(tag.data_type, 0)

    async def _simulation_update_task(self, plc_name: str):
        """Background task to update simulated tag values"""
        plc_configs = self.config.get_plcs()
        plc_config = next((p for p in plc_configs if p['name'] == plc_name), None)
        if not plc_config:
            return

        sim_config = plc_config.get('simulation', {})
        if not sim_config.get('enabled', False):
            return

        update_interval = sim_config.get('update_interval', 1.0)
        rules = sim_config.get('rules', {})
        defaults = sim_config.get('defaults', {})

        sim_engine = self.simulation_engines.get(plc_name)
        if not sim_engine:
            return

        logger.info(f"Starting simulation task for {plc_name} (interval: {update_interval}s)")

        try:
            while True:
                await asyncio.sleep(update_interval)

                # Update all tags for this PLC
                for tag_path, node in self.tag_nodes.items():
                    # Check if this tag belongs to this PLC
                    if not tag_path.startswith(plc_name):
                        continue

                    # Find matching simulation rule
                    sim_rule = self._find_simulation_rule(tag_path, rules, defaults, node)
                    if not sim_rule:
                        continue

                    try:
                        # Get data type from node
                        data_type = await self._get_node_data_type(node)

                        # Get simulated value
                        new_value = sim_engine.get_simulated_value(tag_path, sim_rule, data_type)

                        # Convert Python float to correct OPC-UA type if needed
                        if isinstance(new_value, float):
                            # OPC-UA Float is 32-bit, Python float is 64-bit
                            # Use ua.DataValue to write with correct variant type
                            variant_type = await self._get_node_variant_type(node)
                            dv = ua.DataValue(ua.Variant(new_value, variant_type))
                            await node.write_value(dv.Value)
                        else:
                            # Update OPC-UA node directly
                            await node.write_value(new_value)

                    except Exception as e:
                        logger.debug(f"Error updating tag {tag_path}: {e}")

        except asyncio.CancelledError:
            logger.info(f"Simulation task for {plc_name} cancelled")
        except Exception as e:
            logger.error(f"Simulation task error for {plc_name}: {e}")

    def _find_simulation_rule(self, tag_path: str, rules: dict, defaults: dict, node: Node):
        """Find the simulation rule for a tag using pattern matching"""
        # Extract the tag portion (after PLC name and scope)
        # e.g., "DemoWWTP/Controller:Global/AI_DOP201_DATA.PV" -> "AI_DOP201_DATA.PV"
        parts = tag_path.split('/', 2)
        if len(parts) < 3:
            return None
        tag_name = parts[2]

        # Try exact match first
        if tag_name in rules:
            return rules[tag_name]

        # Try wildcard pattern matching
        for pattern, rule in rules.items():
            if fnmatch.fnmatch(tag_name, pattern):
                return rule

        # Fall back to default by data type
        # We'll need to determine the data type - for now return None
        return None

    async def _get_node_data_type(self, node: Node) -> str:
        """Get the data type of an OPC-UA node"""
        try:
            variant = await node.read_value()
            variant_type = variant.__class__.__name__

            # Map Python types to our data type strings
            type_map = {
                'bool': 'BOOL',
                'int': 'INT',
                'float': 'REAL',
                'str': 'STRING'
            }
            return type_map.get(variant_type, 'REAL')
        except:
            return 'REAL'

    async def _get_node_variant_type(self, node: Node) -> ua.VariantType:
        """Get the OPC-UA variant type of a node"""
        try:
            # Read the node's data type attribute
            data_type_node = await node.read_data_type()

            # Common OPC-UA data type node IDs
            type_mapping = {
                1: ua.VariantType.Boolean,
                2: ua.VariantType.SByte,
                3: ua.VariantType.Byte,
                4: ua.VariantType.Int16,
                5: ua.VariantType.UInt16,
                6: ua.VariantType.Int32,
                7: ua.VariantType.UInt32,
                8: ua.VariantType.Int64,
                9: ua.VariantType.UInt64,
                10: ua.VariantType.Float,
                11: ua.VariantType.Double,
                12: ua.VariantType.String,
            }

            # Get the node ID identifier
            if hasattr(data_type_node, 'Identifier'):
                return type_mapping.get(data_type_node.Identifier, ua.VariantType.Float)

            return ua.VariantType.Float
        except:
            return ua.VariantType.Float

    async def _hot_reload_task(self):
        """Background task to watch for file changes and reload"""
        if not self.hot_reload_enabled:
            return

        logger.info(f"Starting hot reload task (interval: {self.reload_interval}s)")

        try:
            while True:
                await asyncio.sleep(self.reload_interval)

                # Check for file modifications
                reload_needed = False
                for file_path, old_mtime in self.file_mtimes.items():
                    if os.path.exists(file_path):
                        current_mtime = os.path.getmtime(file_path)
                        if current_mtime > old_mtime:
                            logger.info(f"File changed: {file_path}")
                            reload_needed = True
                            break

                if reload_needed:
                    logger.info("Hot reload triggered - reloading configuration and PLC files...")
                    # TODO: Implement actual reload logic
                    # This would involve:
                    # 1. Stop simulation tasks
                    # 2. Reload config
                    # 3. Rebuild address space
                    # 4. Restart simulation tasks
                    logger.warning("Hot reload not fully implemented yet")

        except asyncio.CancelledError:
            logger.info("Hot reload task cancelled")
        except Exception as e:
            logger.error(f"Hot reload task error: {e}")

    async def start(self):
        """Start the OPC-UA server"""
        async with self.server:
            logger.info("=" * 70)
            logger.info("PLC OPC-UA Simulator Server Started")
            logger.info("=" * 70)
            logger.info(f"Endpoint: {self.endpoint}")
            logger.info(f"PLCs Loaded: {', '.join(self.projects.keys())}")
            logger.info(f"Namespace: {self.namespace_index}")
            logger.info("")
            logger.info("Connect from Ignition:")
            logger.info(f"  1. Add OPC-UA Connection")
            logger.info(f"  2. Endpoint URL: {self.endpoint}")
            for plc_name, project in self.projects.items():
                device_name = project.name or plc_name
                logger.info(f"  3. Browse to: Devices/[{device_name}]/Controller:Global")
            logger.info("=" * 70)

            # Start simulation tasks for each PLC that has simulation enabled
            for plc_name in self.simulation_engines.keys():
                task = asyncio.create_task(self._simulation_update_task(plc_name))
                self.simulation_tasks.append(task)

            # Start hot reload task if enabled
            if self.hot_reload_enabled:
                hot_reload_task = asyncio.create_task(self._hot_reload_task())
                self.simulation_tasks.append(hot_reload_task)

            logger.info("Press Ctrl+C to stop")
            logger.info("")

            try:
                # Wait forever (or until Ctrl+C)
                await asyncio.Event().wait()
            finally:
                # Cancel all background tasks
                for task in self.simulation_tasks:
                    task.cancel()
                await asyncio.gather(*self.simulation_tasks, return_exceptions=True)


async def main():
    """Main entry point"""
    # Load configuration
    config_file = os.environ.get('CONFIG_FILE', 'config.yaml')
    config = SimulatorConfig(config_file)

    # Setup logging from config
    logging_config = config.get_logging_config()
    log_level = getattr(logging, logging_config.get('level', 'INFO'))
    log_format = logging_config.get('format', '%(asctime)s - %(name)s - %(levelname)s - %(message)s')

    # Reconfigure logging
    logging.basicConfig(
        level=log_level,
        format=log_format,
        force=True  # Override existing config
    )

    # Setup file logging if enabled
    file_config = logging_config.get('file', {})
    if file_config.get('enabled', False):
        from logging.handlers import RotatingFileHandler
        file_handler = RotatingFileHandler(
            file_config.get('path', 'opcua_simulator.log'),
            maxBytes=file_config.get('max_bytes', 10485760),
            backupCount=file_config.get('backup_count', 5)
        )
        file_handler.setFormatter(logging.Formatter(log_format))
        logging.getLogger().addHandler(file_handler)
        logger.info(f"File logging enabled: {file_config.get('path')}")

    try:
        # Create server instance with configuration
        server = PLCOPCUAServer(config)

        # Initialize
        await server.init()

        # Load all PLC projects from configuration
        await server.load_plc_projects()

        # Build address space
        await server.build_address_space()

        # Start server (this blocks until Ctrl+C)
        await server.start()

    except KeyboardInterrupt:
        logger.info("\nShutting down server...")
    except Exception as e:
        logger.error(f"Server error: {e}", exc_info=True)
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
