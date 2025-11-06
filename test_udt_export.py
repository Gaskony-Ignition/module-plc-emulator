#!/usr/bin/env python3
"""
Test UDT expansion in export.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent / "plc-simulator-refactored"))

from parsers.rockwell_l5k_parser import RockwellL5KParser
from exporters.ignition_exporter import IgnitionExporter

def main():
    parser = RockwellL5KParser()
    project = parser.parse("DemoWWTP-sample-a.L5K", options={
        'select_tags': True,
        'allow_hidden': False,
        'use_stored_values': True
    })

    print("UDT Definitions found:")
    for udt in project.udts[:10]:
        print(f"  - {udt.name} ({len(udt.members)} members)")

    print("\nLooking for AV101 tag:")
    for tag in project.global_tags:
        if tag.name == "AV101":
            print(f"  Tag: {tag.name}")
            print(f"  Type: {tag.data_type}")
            print(f"  Is atomic: {tag.is_atomic()}")

            # Try to find the UDT
            found_udt = None
            for udt in project.udts:
                if udt.name == tag.data_type or udt.name.upper() == str(tag.data_type).upper():
                    found_udt = udt
                    break

            if found_udt:
                print(f"  UDT Definition found: {found_udt.name}")
                print(f"  UDT Members ({len(found_udt.members)}):")
                for m in found_udt.members[:10]:
                    dtype = m.data_type.name if hasattr(m.data_type, 'name') else str(m.data_type)
                    print(f"    - {m.name} ({dtype})")
            else:
                print(f"  UDT Definition NOT FOUND!")
                print(f"  Available UDT names:")
                for udt in project.udts:
                    print(f"    - {udt.name}")
            break

if __name__ == "__main__":
    main()
