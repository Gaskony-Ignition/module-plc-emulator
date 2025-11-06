#!/usr/bin/env python3
"""
Test script to parse L5K file and export with hierarchical structure.
"""

import sys
import os
from pathlib import Path

# Add refactored directory to path
sys.path.insert(0, str(Path(__file__).parent / "plc-simulator-refactored"))

from parsers.rockwell_l5k_parser import RockwellL5KParser
from exporters.ignition_exporter import IgnitionExporter

def main():
    # Path to L5K file
    l5k_file = "DemoWWTP-sample-a.L5K"

    if not os.path.exists(l5k_file):
        print(f"ERROR: L5K file not found: {l5k_file}")
        return 1

    print("=" * 80)
    print("PARSING L5K FILE WITH HIERARCHICAL STRUCTURE")
    print("=" * 80)

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
    print(f"  Global Tags: {len(project.global_tags)}")
    print(f"  Programs: {len(project.programs)}")

    # Show first 10 global tags
    print("\n  First 10 Controller:Global Tags:")
    for i, tag in enumerate(project.global_tags[:10]):
        dtype = tag.data_type.name if hasattr(tag.data_type, 'name') else str(tag.data_type)
        print(f"    {i+1}. {tag.name} ({dtype})")

    # Show programs
    print("\n  Programs:")
    for program in project.programs:
        print(f"    - Program:{program.name} ({len(program.tags)} tags)")

    # Export to simulator CSV with hierarchical structure
    print("\n" + "-" * 80)
    print("Exporting to Simulator CSV with Hierarchical Structure...")
    print("-" * 80)

    exporter = IgnitionExporter(
        protocol="simulator",
        device_name="SimulatorDevice",  # Will be overridden by project name
        create_folders=True
    )

    # Export with hierarchy
    simulator_csv = exporter.export_simulator_csv(project, include_hierarchy=True)

    output_file = "hierarchical_simulator.csv"
    with open(output_file, "w") as f:
        f.write(simulator_csv)

    print(f"\n✓ Exported hierarchical simulator CSV to: {output_file}")

    # Show first 20 lines of output
    print("\n" + "-" * 80)
    print("First 20 lines of output:")
    print("-" * 80)
    lines = simulator_csv.split('\n')
    for i, line in enumerate(lines[:20]):
        print(f"{i+1:3d}: {line}")

    print(f"\n... ({len(lines)} total lines)")

    # Show some statistics
    print("\n" + "-" * 80)
    print("Structure Analysis:")
    print("-" * 80)

    controller_tags = [l for l in lines if "Controller:Global" in l]
    print(f"  Controller:Global tags: {len(controller_tags)}")

    for program in project.programs:
        prog_tags = [l for l in lines if f"Program:{program.name}" in l]
        if prog_tags:
            print(f"  Program:{program.name} tags: {len(prog_tags)}")

    print("\n✓ Export complete!")
    return 0

if __name__ == "__main__":
    sys.exit(main())
