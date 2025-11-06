#!/usr/bin/env python3
"""
Test script for JSON parser
"""

import sys
from pathlib import Path

# Add parent directory to path
sys.path.insert(0, str(Path(__file__).parent.parent / "plc-simulator-refactored"))

from parsers.json_parser import JSONParser

def main():
    parser = JSONParser()

    # Test with simple PLC
    print("Testing simple_plc.json...")
    project = parser.parse("../simple_plc.json")

    print(f"\nProject: {project.name}")
    print(f"UDTs: {len(project.udts)}")
    for udt in project.udts:
        print(f"  - {udt.name} ({len(udt.members)} members)")
        for member in udt.members:
            print(f"      {member.name}: {member.data_type}")

    print(f"\nGlobal Tags: {len(project.global_tags)}")
    for tag in project.global_tags:
        print(f"  - {tag.name}: {tag.data_type} = {tag.initial_value}")

    print(f"\nPrograms: {len(project.programs)}")
    for program in project.programs:
        print(f"  - {program.name} ({len(program.tags)} tags)")

    print("\n✅ JSON parser test passed!")

    # Test with example PLC
    print("\n" + "="*60)
    print("Testing example_plc.json...")
    project2 = parser.parse("../example_plc.json")

    print(f"\nProject: {project2.name}")
    print(f"UDTs: {len(project2.udts)}")
    print(f"Global Tags: {len(project2.global_tags)}")
    print(f"Programs: {len(project2.programs)}")

    print("\n✅ All JSON parser tests passed!")

if __name__ == "__main__":
    main()
