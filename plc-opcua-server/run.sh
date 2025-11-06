#!/bin/bash
#
# Run the PLC OPC-UA Simulator Server
#

cd "$(dirname "$0")"

echo "Starting PLC OPC-UA Simulator Server..."
echo ""

# Activate virtual environment and run
venv/bin/python opcua_simulator.py
