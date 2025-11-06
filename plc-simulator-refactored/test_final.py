"""
Final test to create OPC browser structure matching real PLC
"""

import sys
sys.path.insert(0, '.')

from parsers.rockwell_l5k_parser import RockwellL5KParser
from exporters.ignition_exporter import IgnitionExporter

# Parse the L5K
print("Parsing L5K file...")
parser = RockwellL5KParser()
project = parser.parse("../DemoWWTP-sample-a.L5K", options={
    'select_tags': True,
    'allow_hidden': False,
    'use_stored_values': True
})

print(f"\nResults:")
print(f"  Controller: {project.name}")
print(f"  UDTs: {len(project.udts)}")
print(f"  Global Tags: {len(project.global_tags)}")
print(f"  Programs: {len(project.programs)}")

# Show first 20 global tags
print("\nFirst 20 Global Tags:")
for tag in project.global_tags[:20]:
    dtype = tag.data_type if isinstance(tag.data_type, str) else tag.data_type.name
    print(f"  {tag.name:30} : {dtype:30} ({tag.description[:50] if tag.description else ''})")

# Export for simulator with OPC-UA
print("\n\nExporting to Ignition formats...")
exporter = IgnitionExporter(
    protocol="opcua",
    device_name="SimulatorDevice",
    udt_prefix="Rockwell",
    create_folders=True
)

# Export tags
tags_json = exporter.export_tags(project)
with open("final_tags.json", "w") as f:
    f.write(tags_json)

print(f"✓ Exported {len(project.global_tags)} tags to: final_tags.json")
print(f"  File size: {len(tags_json)} bytes")

# Show what the OPC browser structure will look like
print("\n\nOPC Browser Structure Preview:")
print("[DemoWWTP] (or [SimulatorDevice])")
print("  └── Controller:Global/")

# Show structure for a few example tags
for tag in project.global_tags[:10]:
    if tag.data_type in ['BOOL', 'INT', 'DINT', 'REAL']:
        print(f"      ├── {tag.name} (atomic {tag.data_type})")
    else:
        print(f"      ├── {tag.name}/ (folder - UDT: {tag.data_type})")
        print(f"      │   └── [UDT members will be nested here]")

print("\n  └── Program:MainProgram/")
print("      └── [program tags]")
print("  └── Program:TeSysIsland1/")
print("  └── Program:TeSysIsland2/")

print("\n✅ Done! Import 'final_tags.json' to Ignition Designer.")
