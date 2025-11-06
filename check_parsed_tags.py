#!/usr/bin/env python3
"""
Check what tags were actually parsed from the L5K file.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent / "plc-simulator-refactored"))

from parsers.rockwell_l5k_parser import RockwellL5KParser
from models.tag_model import DataType

def main():
    parser = RockwellL5KParser()
    project = parser.parse("DemoWWTP-sample-a.L5K", options={
        'select_tags': True,
        'allow_hidden': False,
        'use_stored_values': True
    })

    print(f"Total global tags: {len(project.global_tags)}")
    print(f"Total UDTs: {len(project.udts)}")

    # Count atomic vs UDT tags
    atomic_tags = [t for t in project.global_tags if t.is_atomic()]
    udt_tags = [t for t in project.global_tags if not t.is_atomic()]

    print(f"\nAtomic tags: {len(atomic_tags)}")
    print(f"UDT instance tags: {len(udt_tags)}")

    # Show first 10 UDT instances
    print("\nFirst 10 UDT instance tags:")
    for i, tag in enumerate(udt_tags[:10]):
        print(f"  {i+1}. {tag.name} ({tag.data_type})")

    # Find a specific UDT to examine
    print("\n" + "="*80)
    print("Looking for AI_DOP201_DATA UDT instance:")
    for tag in project.global_tags:
        if tag.name == "AI_DOP201_DATA":
            print(f"  Found: {tag.name}")
            print(f"  Data type: {tag.data_type}")
            print(f"  Is atomic: {tag.is_atomic()}")
            print(f"  Should export: {tag.metadata.get('selected', True)}")

            # Find the UDT definition
            for udt in project.udts:
                if udt.name == tag.data_type:
                    print(f"\n  UDT Definition: {udt.name}")
                    print(f"  Members: {len(udt.members)}")
                    print(f"  First 5 members:")
                    for m in udt.members[:5]:
                        dtype = m.data_type.name if hasattr(m.data_type, 'name') else str(m.data_type)
                        print(f"    - {m.name} ({dtype})")
            break

    # Look for AV101
    print("\n" + "="*80)
    print("Looking for AV101 UDT instance:")
    for tag in project.global_tags:
        if tag.name == "AV101":
            print(f"  Found: {tag.name}")
            print(f"  Data type: {tag.data_type}")
            print(f"  Is atomic: {tag.is_atomic()}")

            # Find the UDT definition
            for udt in project.udts:
                if udt.name == tag.data_type:
                    print(f"\n  UDT Definition: {udt.name}")
                    print(f"  Members: {len(udt.members)}")
                    print(f"  All members:")
                    for m in udt.members:
                        dtype = m.data_type.name if hasattr(m.data_type, 'name') else str(m.data_type)
                        print(f"    - {m.name} ({dtype})")
            break

if __name__ == "__main__":
    main()
