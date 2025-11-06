"""
Quick script to extract controller-scoped tags from the L5K file.
These tags are defined inline in the CONTROLLER section, not in TAG blocks.
"""

import re

# Read the L5K file
with open('../DemoWWTP-sample-a.L5K', 'r', encoding='utf-8') as f:
    content = f.read()

# Extract CONTROLLER section
controller_match = re.search(r'CONTROLLER DemoWWTP(.*?)(?:^PROGRAM |^END_CONTROLLER)', content, re.DOTALL | re.MULTILINE)

if controller_match:
    controller_section = controller_match.group(1)

    # Find all controller-scoped tags
    # Pattern: Two tabs, tag name, space, colon, space, UDT type
    tag_pattern = r'^\t\t([A-Z][A-Z0-9_]+)\s+:\s+([A-Z][A-Z0-9_]+)'

    tags = []
    for match in re.finditer(tag_pattern, controller_section, re.MULTILINE):
        tag_name = match.group(1)
        udt_type = match.group(2)
        tags.append((tag_name, udt_type))

    print(f"Found {len(tags)} controller-scoped tags:\n")

    # Print first 50
    for tag_name, udt_type in sorted(tags)[:50]:
        print(f"  {tag_name:30} : {udt_type}")

    if len(tags) > 50:
        print(f"\n  ... and {len(tags) - 50} more tags")

    # Save full list
    with open('/tmp/all_controller_tags.txt', 'w') as f:
        for tag_name, udt_type in sorted(tags):
            f.write(f"{tag_name} : {udt_type}\n")

    print(f"\nFull list saved to: /tmp/all_controller_tags.txt")
else:
    print("Could not find CONTROLLER section")
