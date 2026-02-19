# Development Guide

This guide covers the development workflow, architecture, and procedures for working on the Logix PLC Emulator module.

## Prerequisites

- **Java 17 JDK** (OpenJDK or Oracle)
- **Gradle 7.x+** (wrapper included in project)
- **Ignition Gateway 8.3+** for testing
- **IDE**: IntelliJ IDEA or Eclipse with Gradle plugin
- **Git** for version control

## Project Structure

```
logix-emulator-module/
├── build.gradle.kts          # Main build configuration
├── settings.gradle.kts       # Multi-project settings
├── gradle.properties         # Version and signing configuration
├── common/
│   └── build.gradle.kts      # Common scope (shared code)
├── gateway/
│   ├── build.gradle.kts      # Gateway scope (server-side)
│   └── src/main/
│       ├── java/             # Gateway Java code
│       └── resources/        # i18n bundles, resources
├── designer/
│   ├── build.gradle.kts      # Designer scope (client-side)
│   └── src/main/java/        # Designer Java code
├── BUILD.md                  # Build instructions
├── SIGNING.md                # Module signing documentation
├── TESTING.md                # Testing procedures
└── README.md                 # Main documentation
```

## Development Setup

### 1. Clone Repository

```bash
git clone <repository-url>
cd logix-emulator-module
```

### 2. Verify Java Installation

```bash
java -version
# Should show Java 17

echo $JAVA_HOME
# Should point to Java 17 installation
```

If not set:
```bash
export JAVA_HOME=/path/to/jdk-17
export PATH=$JAVA_HOME/bin:$PATH
```

### 3. Build Module

```bash
./gradlew clean build
```

This produces:
- `build/LogixPLCEmulator-{version}.modl` (signed)
- `build/LogixPLCEmulator-{version}.unsigned.modl` (unsigned)

### 4. Install in Ignition Gateway

**Method 1: Gateway UI**
1. Open Gateway at `http://localhost:8088`
2. Config > System > Modules
3. Install or Upgrade a Module
4. Select `.modl` file
5. Restart Gateway

**Method 2: Direct Copy** (faster for development)
```bash
# Stop Gateway first
cp build/LogixPLCEmulator-{version}.modl \
   /path/to/ignition/user-lib/modules/

# Restart Gateway
systemctl restart ignition
# or docker restart ignition
```

### 5. Verify Installation

Check Gateway logs:
```bash
tail -f /path/to/ignition/logs/wrapper.log | grep "LogixEmulator"
```

Expected output:
```
INFO  [LogixEmulatorExtensionPoint] Registering device type: LogixEmulator
INFO  [LogixEmulatorExtensionPoint] Registered bundle: com.inductiveautomation.logixemulator...
```

## Architecture Overview

### Module Hook Architecture

The module uses **AbstractDeviceModuleHook** to register as a device driver:

```java
// LogixEmulatorExtensionPoint.java
public class LogixEmulatorExtensionPoint extends DeviceExtensionPoint<LogixEmulatorConfig> {
    @Override
    public void startup(LicenseState licenseState) {
        // Initialize module
        // Register device type
        // Register resource bundles
    }

    @Override
    public void shutdown() {
        // Cleanup
    }
}
```

### Device Type Registration

Device types are registered via `DeviceType` interface:

```java
// LogixEmulatorDevice.java
public class LogixEmulatorDevice extends ManagedAddressSpaceWithLifecycle implements Device {
    @Override
    public String getDeviceTypeName() {
        return "LogixEmulator";
    }

    @Override
    protected void connectDevice() {
        // Parse file content
        // Create tags
    }
}
```

### Configuration Schema

Device configuration defined in `LogixEmulatorConfig.java`:

```java
public class LogixEmulatorConfig {
    @FormField(
        ordinal = 0,
        type = FormFieldType.TEXT,
        description = "Device name"
    )
    public String deviceName;

    @FormField(
        ordinal = 1,
        type = FormFieldType.SELECT,
        description = "Parser type"
    )
    public ParserType parserType;

    @FormField(
        ordinal = 2,
        type = FormFieldType.TEXTAREA,
        description = "File content"
    )
    public String fileContent;
}
```

### Internationalization (i18n)

Display names are resolved from resource bundles:

**File**: `gateway/src/main/resources/.../LogixEmulator.properties`
```properties
LogixEmulator.Meta.DisplayName=Logix PLC Emulator
ParserType.ROCKWELL.DisplayName=Rockwell L5K (Allen-Bradley)
ParserType.JSON.DisplayName=JSON Format
ParserType.CSV.DisplayName=CSV Format
```

**Registration** (in module startup):
```java
BundleUtil.get().addBundle(
    "LogixEmulator",
    LogixEmulatorExtensionPoint.class,
    "LogixEmulator"
);
```

## Common Development Tasks

### Adding a New Parser Type

**1. Add enum value** (`LogixEmulatorConfig.java`):
```java
public enum ParserType {
    ROCKWELL("rockwell", "Rockwell L5K/L5X (Allen-Bradley)"),
    JSON("json", "JSON Format"),
    CSV("csv", "CSV Format"),
    MYNEWPARSER("mynewparser", "My New Parser (Description)"); // ADD THIS

    private final String key;
    private final String displayName;

    ParserType(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }
}
```

**2. Add i18n entry** (`LogixEmulator.properties`):
```properties
ParserType.MYNEWPARSER.DisplayName=My New Parser (Description)
```

**3. Implement parser logic** (`LogixEmulatorDevice.java`):
```java
private void parseFileContent(String content, ParserType parserType) {
    switch (parserType) {
        case ROCKWELL:
            // Existing Rockwell parser
            break;
        case JSON:
            // Existing JSON parser
            break;
        case MYNEWPARSER:
            // YOUR PARSER LOGIC HERE
            parseMyNewFormat(content);
            break;
        default:
            log.warn("Parser type not implemented: " + parserType);
    }
}

private void parseMyNewFormat(String content) {
    // Parse content and create tags
    // See existing parsers for reference
}
```

**4. Test**:
```bash
./gradlew clean build
# Install in Gateway
# Create device with new parser type
# Verify tags are created
```

### Changing Module Version

**1. Update `build.gradle.kts`**:
```kotlin
version = "8.2.11"
```

**2. Update CHANGELOG.md**:
```markdown
## [1.2.0] - 2025-11-XX

### Added
- New feature description
```

**3. Build and test**:
```bash
./gradlew clean build
# Output: LogixPLCEmulator-{version}.modl
```

### Adding Configuration Fields

**1. Add field to Config class**:
```java
// LogixEmulatorConfig.java
@FormField(
    ordinal = 10,
    type = FormFieldType.CHECKBOX,
    description = "Enable advanced features"
)
public boolean enableAdvancedFeatures = false;
```

**2. Add i18n entry**:
```properties
LogixEmulator.Config.enableAdvancedFeatures=Enable Advanced Features
LogixEmulator.Config.enableAdvancedFeatures.Desc=Enable experimental features
```

**3. Use in Device**:
```java
// LogixEmulatorDevice.java
protected void connectDevice() {
    LogixEmulatorConfig config = getDriverConfig();
    if (config.enableAdvancedFeatures) {
        // Advanced features code
    }
}
```

### Debugging

**Enable Debug Logging**:

Gateway `wrapper.conf`:
```properties
wrapper.java.additional.10=-Dignition.log.level=DEBUG
```

Or Gateway UI:
- Config > System > Console/Wrapper > Logging
- Set log level to DEBUG for specific logger:
  - `com.inductiveautomation.logixemulator`

**View Logs**:
```bash
tail -f /path/to/ignition/logs/wrapper.log
```

**Common Debug Points**:
- Module startup: Check `startup()` method execution
- Device creation: Check `connectDevice()` called
- Configuration: Log config values in `onStartup()`
- Tag creation: Log tag paths and values

### Testing Changes

**Quick Test Cycle**:
```bash
# 1. Make code changes
# 2. Build
./gradlew clean build

# 3. Copy to Gateway (if using direct copy method)
cp build/LogixPLCEmulator-*.modl /path/to/ignition/user-lib/modules/

# 4. Restart Gateway
docker restart ignition
# or systemctl restart ignition

# 5. Check logs
docker logs -f ignition | grep LogixEmulator

# 6. Test in Gateway UI
# Config > Devices > Create Device
```

**Full Test**:
See [TESTING.md](TESTING.md) for comprehensive test checklist.

## Code Style

### Java Conventions
- **Indentation**: 4 spaces
- **Line length**: 120 characters max
- **Naming**:
  - Classes: `PascalCase`
  - Methods: `camelCase`
  - Constants: `UPPER_SNAKE_CASE`
- **Imports**: No wildcard imports
- **Logging**: Use SLF4J logger
  ```java
  private static final Logger log = LoggerFactory.getLogger(MyClass.class);
  ```

### Comments
```java
/**
 * JavaDoc for public methods/classes
 *
 * @param paramName Description
 * @return Description
 */
public String myMethod(String paramName) {
    // Inline comment for complex logic
    return result;
}
```

### TODOs
Mark incomplete work:
```java
// TODO: Add support for new file format
// FIXME: Handle edge case where file is empty
```

## Troubleshooting Development Issues

### Build Fails: "Could not find method implementation()"

**Problem**: Gradle version mismatch

**Solution**:
```bash
./gradlew wrapper --gradle-version=7.6
./gradlew clean build
```

### Module Won't Load: "Class not found"

**Problem**: Module not built for correct Java version

**Solution**:
1. Verify Java 17 is being used
2. Clean and rebuild:
   ```bash
   ./gradlew clean
   rm -rf .gradle build
   ./gradlew build
   ```

### Display Names Show as "?LogixEmulator...?"

**Problem**: Resource bundle not registered

**Solution**:
1. Check bundle registration in startup():
   ```java
   BundleUtil.get().addBundle(...);
   ```
2. Verify properties file exists at correct path
3. Rebuild and reinstall module

### Enum Dropdown Shows "ROCKWELL" Instead of "Rockwell L5K"

**Problem**: Enum missing toString() method

**Solution**:
```java
@Override
public String toString() {
    return displayName;
}
```

## Release Process

**1. Update Version**:
- `build.gradle.kts`: `version = "8.x.0"`
- `CHANGELOG.md`: Add release notes

**2. Test**:
- Run full test checklist (TESTING.md)
- Verify on clean Gateway install
- Test upgrade from previous version

**3. Build Release**:
```bash
./gradlew clean build
```

**4. Create Git Tag**:
```bash
git tag -a v1.x.0 -m "Release v1.x.0"
git push origin v1.x.0
```

**5. Distribute**:
- Upload `.modl` file to release location
- Update documentation
- Notify users

## Additional Resources

- [Ignition Module SDK Documentation](https://github.com/inductiveautomation/ignition-sdk-examples)
- [Ignition SDK Javadocs](https://docs.inductiveautomation.com/javadocs/)
- [OPC-UA Device Example](https://github.com/inductiveautomation/ignition-sdk-examples/tree/master/opc-ua-device)
- [Module Development Guide](https://docs.inductiveautomation.com/docs/8.1/ignition-sdk/ignition-sdk-guide)

## Getting Help

- Check existing documentation (README.md, BUILD.md, TESTING.md, KNOWN_ISSUES.md)
- Review Gateway logs for error messages
- Search Inductive Automation forums
- Review SDK examples on GitHub
