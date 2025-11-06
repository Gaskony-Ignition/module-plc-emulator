#!/bin/bash
# Build standalone Python executable for PLC Parser Service

set -e

echo "Building PLC Parser Service executable..."

# Check if venv exists, create if not
if [ ! -d "venv" ]; then
    echo "Creating virtual environment..."
    python3 -m venv venv
fi

# Activate virtual environment
source venv/bin/activate

# Install requirements
echo "Installing requirements..."
pip install -q --upgrade pip
pip install -q -r requirements.txt
pip install -q pyinstaller

# Build executable with PyInstaller
echo "Building with PyInstaller..."
pyinstaller --onefile \
    --name plc-parser-service \
    --add-data "../../plc-simulator-refactored/parsers:parsers" \
    --add-data "../../plc-simulator-refactored/models:models" \
    --hidden-import=flask \
    --hidden-import=werkzeug \
    --collect-all flask \
    --clean \
    parser_service.py

# Copy executable to gateway resources
echo "Copying executable to module resources..."
mkdir -p ../gateway/src/main/resources/bin
cp dist/plc-parser-service ../gateway/src/main/resources/bin/

echo "Build complete! Executable at: ../gateway/src/main/resources/bin/plc-parser-service"
echo "File size: $(du -h ../gateway/src/main/resources/bin/plc-parser-service | cut -f1)"

deactivate
