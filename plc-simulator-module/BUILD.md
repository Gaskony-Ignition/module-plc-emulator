# PLC Simulator Module - Build & Package Guide

## Overview
This document describes how to build and package the PLC Simulator module for distribution.

## Prerequisites
- Java 17 JDK
- Gradle 7.x or higher (wrapper included)
- Python 3.x (for parser development)
- PyInstaller (for rebuilding parser executable)

## Building the Module

### Standard Build
```bash
./gradlew clean build
```

This creates a **signed** module at:
```
build/PLCSimulator-1.0.0.modl
```

And also creates an unsigned version:
```
build/PLCSimulator-1.0.0.unsigned.modl
```

The build process:
1. Compiles Java source code (Gateway, Designer, Common scopes)
2. Packages compiled classes into JARs
3. Embeds the Python parser executable (12MB)
4. Assembles everything into a .modl file
5. Signs the module with self-signed certificate (skipModlSigning=false)

### Build Output
- **Signed module**: `build/PLCSimulator-1.0.0.modl` (~12MB) - **Use this for installation**
- **Unsigned module**: `build/PLCSimulator-1.0.0.unsigned.modl` (~12MB)
- **Module report**: `build/reports/module-report.txt`
- **Certificate**: `certificate.der` (included in repository)
- **Keystore**: `keystore.jks` (included in repository)

## Module Signing

### Current Configuration (Self-Signed)
The module is **already configured** with self-signed certificates for development and testing:

- **Certificate**: `certificate.der` (included in repository)
- **Keystore**: `keystore.jks` (included in repository)
- **Alias**: `plcsimulator`
- **Password**: `REDACTED-DEV-PASSWORD` (development only - PUBLIC)
- **Organization**: Gaskony
- **Validity**: 10 years (2025-2035)

⚠️ **SECURITY WARNING**: These certificates use public development passwords and should ONLY be used for development/testing.

The signing configuration is in `gradle.properties`:
```properties
ignition.signing.keystoreFile=keystore.jks
ignition.signing.keystorePassword=REDACTED-DEV-PASSWORD
ignition.signing.certFile=certificate.der
ignition.signing.certAlias=plcsimulator
ignition.signing.certPassword=REDACTED-DEV-PASSWORD
```

### Regenerating Certificates
To generate new self-signed certificates:

```bash
# Run the certificate generation script
./generate-signing-certs.sh

# Or manually:
keytool -genkeypair -alias plcsimulator -keyalg RSA -keysize 2048 \
    -validity 3650 -keystore keystore.jks -storepass REDACTED-DEV-PASSWORD \
    -keypass REDACTED-DEV-PASSWORD \
    -dname "CN=PLC Simulator Module, OU=Development, O=Gaskony, L=Folsom, ST=CA, C=US"

keytool -exportcert -alias plcsimulator -keystore keystore.jks \
    -storepass REDACTED-DEV-PASSWORD -file certificate.der -rfc
```

### For Production (Official Signing)
For distribution on Ignition Exchange or production use:

1. Generate production certificates with private passwords (never commit to git)
2. Update `gradle.properties` with production certificate paths and passwords
3. Build signed module: `./gradlew clean build`
4. Distribute the signed `PLCSimulator-1.0.0.modl` file

Alternatively, submit to Inductive Automation for official signing:
1. Build module with your dev certificate
2. Submit to Inductive Automation for Exchange distribution
3. They will re-sign with their official certificate

## Rebuilding the Python Parser

The embedded parser executable is pre-built and included in `gateway/src/main/resources/bin/plc-parser-service`.

To rebuild the parser executable:

```bash
cd python-parser

# Install dependencies
pip install -r requirements.txt
pip install pyinstaller

# Build executable
pyinstaller --onefile \
    --name plc-parser-service \
    --add-data "../plc-simulator-refactored:plc_simulator" \
    parser_service.py

# Copy to resources
cp dist/plc-parser-service \
    ../gateway/src/main/resources/bin/
```

**Note**: The executable must be rebuilt for each target platform (Linux, Windows, macOS).

## Module Structure

```
PLCSimulator-1.0.0.unsigned.modl (ZIP archive)
├── module.xml                    # Module metadata
├── gateway.jar                   # Gateway scope code
│   ├── com/inductiveautomation/plcsimulator/gateway/
│   │   ├── GatewayHook.class
│   │   ├── PLCTagManager.class
│   │   ├── SimulationEngine.class
│   │   ├── ParserService.class
│   │   └── ...
│   └── bin/
│       └── plc-parser-service    # Embedded Python parser (12MB)
├── designer.jar                  # Designer scope code
└── common.jar                    # Common scope code
```

## Installation

### Install via Gateway Webpage
1. Navigate to Config > System > Modules
2. Click "Install or Upgrade a Module"
3. Select `PLCSimulator-1.0.0.modl` (signed version)
4. Click "Install"
5. Restart Gateway when prompted

**Note**: Use the **signed** version (`PLCSimulator-1.0.0.modl`) for installation. Gateway will accept the self-signed development certificate.

### Install via Command Line
```bash
# Copy to Ignition modules directory
cp build/PLCSimulator-1.0.0.modl \
    /path/to/ignition/user-lib/modules/

# Restart Ignition
systemctl restart ignition  # Linux
# or use Gateway webpage
```

## Troubleshooting

### Build Fails with "java executable not found"
Ensure Java 17 is installed and JAVA_HOME is set:
```bash
java -version  # Should show version 17
export JAVA_HOME=/path/to/java17
```

### Module Won't Load - "Parser service failed to start"
Check that the parser executable has execute permissions:
```bash
chmod +x gateway/src/main/resources/bin/plc-parser-service
```

### Module Size Too Large
The 12MB size is primarily due to the embedded Python parser. This is intentional for self-contained operation. If size is critical:
- Consider using an external parser service
- Remove unused dependencies from parser requirements

### Signing Issues
If using self-signed certificates and Ignition rejects the module:
1. Check Gateway logs for specific error
2. Verify certificate is valid: `keytool -list -v -keystore plcsimulator.jks`
3. Ensure Gateway is configured to accept modules from untrusted sources (development only)

## Version Management

Update version in `gradle.properties`:
```properties
version=1.0.0
```

This updates:
- Module version in `module.xml`
- Output filename
- Internal version strings

## CI/CD Integration

### GitHub Actions Example
```yaml
name: Build Module

on: [push]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2

      - name: Set up JDK 17
        uses: actions/setup-java@v2
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Build with Gradle
        run: ./gradlew clean build

      - name: Upload artifact
        uses: actions/upload-artifact@v2
        with:
          name: plcsimulator-module
          path: build/*.modl
```

## Next Steps

After building the module:
1. Install on test Gateway
2. Verify all features work (Phase 11 - Testing)
3. Review logs for errors
4. Test with real L5K files
5. Document usage for end users
