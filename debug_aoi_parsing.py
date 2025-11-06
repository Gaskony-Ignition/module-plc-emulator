#!/usr/bin/env python3
"""
Debug AOI parsing to see why we're only getting 33 of 138 AOIs.
"""

import re

# Read L5K file
with open("DemoWWTP-sample-a.L5K", 'r', encoding='utf-8') as f:
    content = f.read()

print("File size:", len(content), "characters")
print()

# Test the current regex patterns
pattern1 = r"ADD_ON_INSTRUCTION_DEFINITION\s(.*?)END_ADD_ON_INSTRUCTION_DEFINITION"
pattern2 = r"ADD_ON_INSTRUCTION_DEFINITION,(.*?)END_ENCODED_DATA"

matches1 = re.findall(pattern1, content, re.DOTALL)
matches2 = re.findall(pattern2, content, re.DOTALL)

print(f"Pattern 1 matches: {len(matches1)}")
print(f"Pattern 2 matches: {len(matches2)}")
print(f"Total matches: {len(matches1) + len(matches2)}")
print()

# Try a better pattern that captures the name
pattern3 = r"\tADD_ON_INSTRUCTION_DEFINITION\s+(\w+)\s+\((.*?)END_ADD_ON_INSTRUCTION_DEFINITION"
matches3 = re.findall(pattern3, content, re.DOTALL)
print(f"Pattern 3 (with tab and name capture): {len(matches3)}")
print()

# Show first few AOI names from pattern 3
print("First 10 AOI names from pattern 3:")
for i, (name, body) in enumerate(matches3[:10]):
    # Check if PARAMETERS section exists
    has_params = "PARAMETERS" in body
    print(f"  {i+1}. {name} - Has PARAMETERS: {has_params}")
print()

# Try matching without capturing the name separately
pattern4 = r"\tADD_ON_INSTRUCTION_DEFINITION\s+(.*?)\r\n\t\tEND_ADD_ON_INSTRUCTION_DEFINITION"
matches4 = re.findall(pattern4, content, re.DOTALL)
print(f"Pattern 4 (tab at start, tab-tab at end): {len(matches4)}")
print()

# Show first AOI name from pattern 4
if matches4:
    first_aoi = matches4[0]
    # Extract name (first word)
    name_match = re.search(r'^(\w+)', first_aoi)
    if name_match:
        print(f"First AOI from pattern 4: {name_match.group(1)}")
        print(f"  Length of first AOI content: {len(first_aoi)}")
        # Check for PARAMETERS
        has_params = "PARAMETERS" in first_aoi
        print(f"  Has PARAMETERS section: {has_params}")
