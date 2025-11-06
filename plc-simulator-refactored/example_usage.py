"""
Example usage of the refactored multi-protocol PLC simulator.
Demonstrates how to parse PLC files and export to Ignition formats.
"""

import sys
import os
from pathlib import Path

# Add current directory to path for imports
current_dir = Path(__file__).parent
sys.path.insert(0, str(current_dir))

# Import from local modules
import parsers.rockwell_l5k_parser as rockwell_parser
import parsers.opcua_csv_parser as csv_parser
import parsers.siemens_tia_parser as siemens_parser
import parsers.beckhoff_parser as beckhoff_parser
import parsers.schneider_parser as schneider_parser
import parsers.base_parser as base_parser
import exporters.ignition_exporter as ignition_exp
import protocols.address_provider as addr_prov
import models.tag_model as tag_model

# Convenient aliases
RockwellL5KParser = rockwell_parser.RockwellL5KParser
OPCUACSVParser = csv_parser.OPCUACSVParser
SiemensTIAParser = siemens_parser.SiemensTIAParser
BeckhoffTwinCATParser = beckhoff_parser.BeckhoffTwinCATParser
SchneiderElectricParser = schneider_parser.SchneiderElectricParser
register_parser = base_parser.register_parser
get_parser_for_file = base_parser.get_parser_for_file
get_supported_formats = base_parser.get_supported_formats
IgnitionExporter = ignition_exp.IgnitionExporter
create_address_provider = addr_prov.create_address_provider


def example_1_parse_l5k_file():
    """Example 1: Parse Rockwell L5K file and export to Ignition"""
    print("=" * 80)
    print("EXAMPLE 1: Parse Rockwell L5K File")
    print("=" * 80)

    # Path to your L5K file
    l5k_file = "../DemoWWTP-sample-a.L5K"

    if not os.path.exists(l5k_file):
        print(f"ERROR: L5K file not found: {l5k_file}")
        return

    # Create parser
    parser = RockwellL5KParser()

    # Parse the file
    print(f"\nParsing: {l5k_file}")
    project = parser.parse(l5k_file, options={
        'select_tags': True,
        'allow_hidden': False,
        'use_stored_values': True
    })

    print(f"\nParsed Project: {project.name}")
    print(f"  PLC Type: {project.plc_type}")
    print(f"  UDTs: {len(project.udts)}")
    print(f"  Global Tags: {len(project.global_tags)}")
    print(f"  Programs: {len(project.programs)}")

    # List first 5 UDTs
    print("\n  First 5 UDTs:")
    for udt in project.udts[:5]:
        print(f"    - {udt.name}: {len(udt.members)} members")

    # List first 5 global tags
    print("\n  First 5 Global Tags:")
    for tag in project.global_tags[:5]:
        print(f"    - {tag.name} ({tag.data_type}): {tag.description}")

    # List programs
    print("\n  Programs:")
    for program in project.programs:
        print(f"    - {program.name}: {len(program.tags)} tags")

    # Export to Ignition formats
    print("\n" + "-" * 80)
    print("Exporting to Ignition formats...")
    print("-" * 80)

    exporter = IgnitionExporter(
        protocol="opcua",
        device_name="SimulatorDevice",
        udt_prefix="Rockwell",
        create_folders=True  # TRUE FOLDER STRUCTURES!
    )

    # Export UDT definitions
    udts_json = exporter.export_udts(project)
    with open("output_udts.json", "w") as f:
        f.write(udts_json)
    print("\n✓ Exported UDT definitions to: output_udts.json")

    # Export tag instances
    tags_json = exporter.export_tags(project)
    with open("output_tags.json", "w") as f:
        f.write(tags_json)
    print("✓ Exported tag instances to: output_tags.json")

    # Export simulator CSV
    simulator_csv = exporter.export_simulator_csv(project)
    with open("output_simulator.csv", "w") as f:
        f.write(simulator_csv)
    print("✓ Exported simulator program to: output_simulator.csv")

    print("\n✓ Export complete!")


def example_2_parse_csv_file():
    """Example 2: Parse generic OPC-UA CSV and export"""
    print("\n\n" + "=" * 80)
    print("EXAMPLE 2: Parse Generic OPC-UA CSV File")
    print("=" * 80)

    # Create a sample CSV file
    sample_csv = "sample_tags.csv"
    with open(sample_csv, "w") as f:
        f.write("TagName,DataType,Description,InitialValue,Folder\n")
        f.write("Tank1_Level,REAL,Tank 1 level sensor,0.0,Tanks\n")
        f.write("Tank2_Level,REAL,Tank 2 level sensor,0.0,Tanks\n")
        f.write("Pump1_Status,BOOL,Main pump status,false,Pumps\n")
        f.write("Pump2_Status,BOOL,Backup pump status,false,Pumps\n")
        f.write("Temperature,INT,System temperature,20,Sensors\n")
        f.write("Pressure,INT,System pressure,100,Sensors\n")
        f.write("SystemReady,BOOL,System ready flag,false,\n")

    print(f"\nCreated sample CSV: {sample_csv}")

    # Parse the CSV
    parser = OPCUACSVParser()
    project = parser.parse(sample_csv, options={'select_tags': True})

    print(f"\nParsed Project: {project.name}")
    print(f"  Global Tags: {len(project.global_tags)}")
    print(f"  Programs (folders): {len(project.programs)}")

    # Export to Ignition
    exporter = IgnitionExporter(
        protocol="opcua",
        device_name="GenericDevice",
        create_folders=True
    )

    tags_json = exporter.export_tags(project)
    with open("output_csv_tags.json", "w") as f:
        f.write(tags_json)

    print("\n✓ Exported to: output_csv_tags.json")


def example_3_multiple_protocols():
    """Example 3: Demonstrate multiple protocol support"""
    print("\n\n" + "=" * 80)
    print("EXAMPLE 3: Multiple Protocol Support")
    print("=" * 80)

    # Register all parsers
    register_parser(RockwellL5KParser())
    register_parser(OPCUACSVParser())
    register_parser(SiemensTIAParser())
    register_parser(BeckhoffTwinCATParser())
    register_parser(SchneiderElectricParser())

    # Show supported formats
    print("\nSupported PLC formats:")
    formats = get_supported_formats()
    for plc_type, extensions in formats.items():
        print(f"  • {plc_type}: {', '.join(extensions)}")

    # Auto-detect parser for a file
    print("\n\nAuto-detecting parser...")
    l5k_file = "../DemoWWTP-sample-a.L5K"
    if os.path.exists(l5k_file):
        parser = get_parser_for_file(l5k_file)
        if parser:
            print(f"  ✓ Detected: {parser.get_plc_type_name()}")


def example_4_different_protocols():
    """Example 4: Export same project with different protocols"""
    print("\n\n" + "=" * 80)
    print("EXAMPLE 4: Export with Different Protocols")
    print("=" * 80)

    # Create a simple in-memory project
    from models.tag_model import PLCProject, Tag, DataType, TagScope

    project = PLCProject(
        name="Demo Project",
        plc_type="Generic",
        global_tags=[
            Tag("Temperature", DataType.FLOAT4, description="Temperature sensor"),
            Tag("Pressure", DataType.FLOAT4, description="Pressure sensor"),
            Tag("PumpRunning", DataType.BOOL, description="Pump status"),
        ]
    )

    # Export with OPC-UA
    print("\n1. OPC-UA Protocol:")
    exporter_opcua = IgnitionExporter(protocol="opcua", device_name="OPC_Device")
    tags = exporter_opcua.export_tags(project)
    print("   Sample tag address:", '"opcItemPath": "ns=1;s=[OPC_Device]Temperature"')

    # Export with Modbus
    print("\n2. Modbus Protocol:")
    exporter_modbus = IgnitionExporter(protocol="modbus", device_name="Modbus_Device")
    tags = exporter_modbus.export_tags(project)
    print("   Sample tag address:", '"opcItemPath": "[Modbus_Device]HR40001"')

    # Export with Memory tags
    print("\n3. Memory Protocol (no external device):")
    exporter_memory = IgnitionExporter(protocol="memory")
    tags = exporter_memory.export_tags(project)
    print("   Value source:", '"valueSource": "memory"')

    print("\n✓ Same project, different protocols!")


def main():
    """Run all examples"""
    print("\n")
    print("╔" + "=" * 78 + "╗")
    print("║" + " " * 20 + "PLC SIMULATOR - MULTI-PROTOCOL SUPPORT" + " " * 20 + "║")
    print("╚" + "=" * 78 + "╝")

    try:
        example_1_parse_l5k_file()
    except Exception as e:
        print(f"\nExample 1 Error: {e}")

    try:
        example_2_parse_csv_file()
    except Exception as e:
        print(f"\nExample 2 Error: {e}")

    try:
        example_3_multiple_protocols()
    except Exception as e:
        print(f"\nExample 3 Error: {e}")

    try:
        example_4_different_protocols()
    except Exception as e:
        print(f"\nExample 4 Error: {e}")

    print("\n" + "=" * 80)
    print("All examples complete!")
    print("=" * 80)


if __name__ == "__main__":
    main()
