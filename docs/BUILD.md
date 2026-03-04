# Logix PLC Emulator - Build & Package Guide

## Overview
This document describes how to build and package the Logix PLC Emulator module for distribution.

## Prerequisites
- Java 17 JDK
- Gradle 7.x or higher (wrapper included)

## Building the Module

### Standard Build
```bash
./gradlew clean build
```

This creates a **signed** module at:
```
build/LogixPLCEmulator-{version}.modl
```

And also creates an unsigned version:
```
build/LogixPLCEmulator-{version}.unsigned.modl
```

The build process:
1. Compiles Java source code (Gateway, Designer, Common scopes)
2. Packages compiled classes into JARs
3. Assembles everything into a .modl file
4. Signs the module with self-signed certificate (skipModlSigning=false)

### Build Output
- **Signed module**: `build/LogixPLCEmulator-{version}.modl` (~12MB) - **Use this for installation**
- **Unsigned module**: `build/LogixPLCEmulator-{version}.unsigned.modl` (~12MB)
- **Module report**: `build/reports/module-report.txt`
- **Certificate**: `certificate.der` (included in repository)
- **Keystore**: `keystore.jks` (included in repository)

## Module Signing

### Current Configuration (Self-Signed)
The module is **already configured** with self-signed certificates for development and testing:

- **Certificate**: `certificate.der` (included in repository)
- **Keystore**: `keystore.jks` (included in repository)
- **Alias**: `plcsimulator`
- **Password**: [stored in CI secrets]
- **Organization**: Gaskony
- **Validity**: 10 years (2025-2035)

⚠️ **SECURITY WARNING**: These certificates are for development/testing only. Signing passwords are stored in CI/CD secrets.

The signing configuration is in `gradle.properties`:
```properties
ignition.signing.keystoreFile=keystore.jks
ignition.signing.keystorePassword=[stored in CI secrets]
ignition.signing.certFile=certificate.der
ignition.signing.certAlias=plcsimulator
ignition.signing.certPassword=[stored in CI secrets]
```

### Regenerating Certificates
To generate new self-signed certificates:

```bash
keytool -genkeypair -alias plcsimulator -keyalg RSA -keysize 2048 \
    -validity 3650 -keystore keystore.jks -storepass "$KEYSTORE_PASSWORD" \
    -keypass "$KEYSTORE_PASSWORD" \
    -dname "CN=Logix PLC Emulator Module, OU=Development, O=Gaskony, L=Folsom, ST=CA, C=US"

keytool -exportcert -alias plcsimulator -keystore keystore.jks \
    -storepass "$KEYSTORE_PASSWORD" -file certificate.der -rfc
```

### For Production (Official Signing)
For production deployment:

1. Generate production certificates with private passwords (never commit to git)
2. Update `gradle.properties` with production certificate paths and passwords
3. Build signed module: `./gradlew clean build`
4. Distribute the signed `LogixPLCEmulator-{version}.modl` file

For internal distribution:
1. Build module with your dev certificate
2. Test thoroughly in development environment
3. Deploy to production Ignition Gateways

## Module Structure

```
LogixPLCEmulator-{version}.modl (ZIP archive)
├── module.xml                    # Module metadata
├── gateway.jar                   # Gateway scope code
│   ├── com/inductiveautomation/logixemulator/gateway/
│   │   ├── SimulatorModuleHook.class
│   │   ├── LogixEmulatorDevice.class
│   │   ├── parser/
│   │   │   ├── L5KParser.class
│   │   │   ├── L5XParser.class (with XXE protection)
│   │   │   ├── JsonPLCParser.class
│   │   │   ├── CsvParser.class
│   │   │   └── ParserFactory.class
│   │   └── web/
│   │       └── FileUploadRoutes.class
│   └── mounted/                  # Web UI resources
│       ├── connection-browser.html
│       └── connectionBrowser.js
├── designer.jar                  # Designer scope code
└── common.jar                    # Common scope code
```

## Installation

### Install via Gateway Webpage
1. Navigate to Config > System > Modules
2. Click "Install or Upgrade a Module"
3. Select `LogixPLCEmulator-{version}.modl` (signed version)
4. Click "Install"
5. Restart Gateway when prompted

**Note**: Use the **signed** version (`LogixPLCEmulator-{version}.modl`) for installation. Gateway will accept the self-signed development certificate.

### Install via Command Line
```bash
# Copy to Ignition modules directory
cp build/LogixPLCEmulator-{version}.modl \
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

### Module Won't Load - Check Logs
Check Gateway logs for specific errors:
```bash
tail -f /usr/local/ignition/logs/wrapper.log
```

Common issues:
- Java version mismatch (requires Java 17)
- Missing dependencies
- Invalid module signature

### Signing Issues
If using self-signed certificates and Ignition rejects the module:
1. Check Gateway logs for specific error
2. Verify certificate is valid: `keytool -list -v -keystore keystore.jks`
3. Ensure Gateway is configured to accept modules from untrusted sources (development only)

## Version Management

Update version in `build.gradle.kts`:
```kotlin
version = "9.1.1"
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
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Build with Gradle
        run: ./gradlew clean build

      - name: Upload artifact
        uses: actions/upload-artifact@v4
        with:
          name: logix-emulator-module
          path: build/*.modl
```

## Next Steps

After building the module:
1. Install on test Gateway
2. Verify all features work (Phase 11 - Testing)
3. Review logs for errors
4. Test with real L5K files
5. Document usage for end users
