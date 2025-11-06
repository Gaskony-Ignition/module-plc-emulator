#!/usr/bin/env python3
"""
Debug why AOI PARAMETERS sections aren't being found.
"""

import re

# Read L5K file
with open("DemoWWTP-sample-a.L5K", 'r', encoding='utf-8') as f:
    content = f.read()

# Get all AOI sections
pattern = r"\tADD_ON_INSTRUCTION_DEFINITION\s+(.*?)END_ADD_ON_INSTRUCTION_DEFINITION"
aoi_matches = re.findall(pattern, content, re.DOTALL)

print(f"Total AOI sections found: {len(aoi_matches)}")
print()

# Test the PARAMETERS regex pattern used in the parser
params_pattern = r"PARAMETERS\r\n(.*?)END_PARAMETERS"

found_params = 0
missing_params = []

for i, aoi_section in enumerate(aoi_matches):
    # Extract AOI name
    name_match = re.search(r'^(\w+)', aoi_section)
    aoi_name = name_match.group(1) if name_match else f"AOI_{i}"

    # Try to find PARAMETERS section
    params_match = re.search(params_pattern, aoi_section, re.DOTALL)

    if params_match:
        found_params += 1
    else:
        missing_params.append(aoi_name)

print(f"AOIs with PARAMETERS section found: {found_params}")
print(f"AOIs missing PARAMETERS section: {len(missing_params)}")
print()

if missing_params:
    print(f"First 10 AOIs missing PARAMETERS:")
    for name in missing_params[:10]:
        print(f"  - {name}")
    print()

# Check for the A130_Valve_FB_1Coil_RETRY specifically
print("Looking for A130_Valve_FB_1Coil_RETRY:")
for aoi_section in aoi_matches:
    if aoi_section.startswith("A130_Valve_FB_1Coil_RETRY"):
        print("  Found the AOI section")

        # Check different PARAMETERS patterns
        has_params_crlf = bool(re.search(r"PARAMETERS\r\n", aoi_section))
        has_params_lf = bool(re.search(r"PARAMETERS\n", aoi_section))
        has_params_any = "PARAMETERS" in aoi_section
        has_end_params = "END_PARAMETERS" in aoi_section

        print(f"  Has 'PARAMETERS\\r\\n': {has_params_crlf}")
        print(f"  Has 'PARAMETERS\\n': {has_params_lf}")
        print(f"  Has 'PARAMETERS': {has_params_any}")
        print(f"  Has 'END_PARAMETERS': {has_end_params}")

        # Try to extract parameters section
        params_match = re.search(params_pattern, aoi_section, re.DOTALL)
        if params_match:
            params_content = params_match.group(1)
            print(f"  PARAMETERS section length: {len(params_content)}")
            # Count parameter lines
            param_lines = [l for l in params_content.split('\n') if ':' in l and 'BOOL' in l or 'DINT' in l or 'SINT' in l]
            print(f"  Approximate parameter count: {len(param_lines)}")
        else:
            print(f"  PARAMETERS section NOT extracted by regex!")
            # Show first 500 chars to see what's there
            print(f"  First 500 chars of AOI section:")
            print(f"  {aoi_section[:500]}")
        break
