#!/usr/bin/env python3
"""
Debug parsing of specific tag.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent / "plc-simulator-refactored"))

from parsers.rockwell_l5k_parser import RockwellL5KParser

def main():
    parser = RockwellL5KParser()
    project = parser.parse("DemoWWTP-sample-a.L5K", options={
        'select_tags': True,
        'allow_hidden': False,
        'use_stored_values': True
    })

    print("Looking for SBR_MODES UDT...")
    for udt in project.udts:
        if udt.name == "SBR_MODES":
            print(f"\nFound UDT: {udt.name}")
            print(f"Members: {len(udt.members)}")

            # Find SCADA_IND_DECANT member
            for member in udt.members:
                if "SCADA_IND_DECANT" in member.name:
                    print(f"\nMember: {member.name}")
                    print(f"  Data Type: {member.data_type}")
                    print(f"  Description: {member.description}")
                    print(f"  Initial Value: {member.initial_value}")
                    print(f"  Initial Value Type: {type(member.initial_value)}")
                    break
            break

if __name__ == "__main__":
    main()
