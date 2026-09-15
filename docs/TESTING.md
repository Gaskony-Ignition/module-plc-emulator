# Logix PLC Emulator - Testing Guide v11.1.2

**Version**: 11.1.2
**Last Updated**: 2026-02-21
**Status**: Production Ready - Security Hardened & Fully Tested

---

## Table of Contents

1. [Automated Test Suite](#automated-test-suite)
2. [Installation Testing](#installation-testing)
3. [Device Configuration Testing](#device-configuration-testing)
4. [File Upload Testing](#file-upload-testing)
5. [Tag Creation Testing](#tag-creation-testing)
6. [Security Testing](#security-testing)
7. [Gateway Config Integration Testing](#gateway-config-integration-testing)
8. [Cross-Platform Testing](#cross-platform-testing)
9. [Performance Testing](#performance-testing)
10. [Regression Testing Checklist](#regression-testing-checklist)
11. [Test Data Files](#test-data-files)
12. [Troubleshooting Test Failures](#troubleshooting-test-failures)

---

## Automated Test Suite

**Location**: `logix-emulator-module/gateway/src/test/java/`

### Test Coverage Summary

**Total Tests**: 92 (100% passing)

| Test Class | Tests | Focus Area |
|------------|-------|------------|
| **L5XParserTest** | 12 | L5X parsing, UDT expansion, XXE prevention |
| **FileValidatorTest** | 10 | File validation, size limits, type detection |
| **FileUploadRoutesSecurityTest** | 18 | Authentication, path traversal, DoS prevention |

### Running All Tests

```bash
./gradlew test
```

**Expected output**:
```
BUILD SUCCESSFUL in 15s
92 tests completed, 92 succeeded
```

### Running Specific Test Classes

```bash
# L5X Parser tests
./gradlew test --tests L5XParserTest

# File validation tests
./gradlew test --tests FileValidatorTest

# Security tests
./gradlew test --tests FileUploadRoutesSecurityTest
```

### Test Reports

After running tests, view detailed HTML reports:
```
build/reports/tests/test/index.html
```

### L5XParserTest Details (12 tests)

1. **testParseBasicL5X** - Basic L5X file parsing
2. **testParseControllerTag** - Controller-scoped tag parsing
3. **testParseProgramTag** - Program-scoped tag parsing
4. **testParseUDT** - User Defined Type parsing
5. **testExpandUDT** - UDT member expansion
6. **testParseArrayTag** - Array tag handling
7. **testParsePredefinedTypes** - All 22 Rockwell types (TIMER, COUNTER, PID, PIDE, ALARM_ANALOG, AXIS_CIP_DRIVE, etc.)
8. **testHandleEmptyFile** - Empty file handling
9. **testHandleMalformedXML** - Malformed XML error handling
10. **testHandleInvalidControllerName** - Invalid controller detection
11. **testHandleMissingTags** - Missing tag section handling
12. **testXXEPrevention** - XXE (XML External Entity) attack prevention

**Critical Security Test**:
```java
@Test
void testXXEPrevention() throws Exception {
    // Malicious L5X with external entity reference
    String maliciousXML = "<?xml version=\"1.0\"?>\n" +
        "<!DOCTYPE Controller [\n" +
        "  <!ENTITY xxe SYSTEM \"file:///etc/passwd\">\n" +
        "]>\n" +
        "<Controller>&xxe;</Controller>";

    // Should throw exception and NOT read external file
    assertThatThrownBy(() -> parser.parseL5X(maliciousXML))
        .isInstanceOf(SAXParseException.class);
}
```

### FileValidatorTest Details (10 tests)

1. **testValidL5KFile** - Valid L5K file acceptance
2. **testValidL5XFileIsRejected** - L5X rejected with L5K guidance (11.0.0)
3. **testValidJSONFile** - Valid JSON file acceptance
4. **testValidCSVFile** - Valid CSV file acceptance
5. **testRejectOversizedFile** - File size limit enforcement (50MB, corrected 10/07/2026 — was
   incorrectly documented as 10MB, see Test Case 6 below and defect B7)
6. **testRejectEmptyFile** - Empty file rejection
7. **testRejectInvalidExtension** - Invalid extension rejection
8. **testRejectMismatchedContent** - Content/extension mismatch detection
9. **testContentLengthValidation** - Content-Length header validation
10. **testFileTypeDetection** - Automatic file type detection

**Example**:
```java
@Test
void testRejectOversizedFile() {
    byte[] largeContent = new byte[51 * 1024 * 1024]; // 51MB - over the real 50MB limit

    ValidationResult result = validator.validate("large.L5K", largeContent);

    assertThat(result.isValid()).isFalse();
    assertThat(result.getError()).contains("exceeds maximum");
}
```

### FileUploadRoutesSecurityTest Details (18 tests)

**Authentication Tests** (6 tests):
1. **testUploadRequiresAuthentication** - Unauthenticated upload rejected
2. **testUploadWithValidAuthentication** - Authenticated upload succeeds
3. **testUploadWithInvalidCredentials** - Invalid credentials rejected
4. **testUploadWithExpiredSession** - Expired session rejected
5. **testUploadRequiresDesignerRole** - Role-based access control
6. **testUploadWithAdminRole** - Admin role allowed

**Path Traversal Tests** (6 tests):
7. **testRejectPathTraversalFilename** - `../../../etc/passwd` rejected
8. **testRejectPathTraversalDeviceName** - `../../devices` rejected
9. **testRejectAbsolutePathFilename** - `/etc/shadow` rejected
10. **testRejectWindowsPathTraversal** - `..\\..\\windows\\system32` rejected
11. **testRejectURLEncodedTraversal** - `%2e%2e%2f` rejected
12. **testAcceptSafeFilenames** - `myplc.L5K` accepted

**DoS Prevention Tests** (4 tests):
13. **testRejectMissingContentLength** - Missing Content-Length rejected
14. **testRejectOversizedContent** - Content > 50MB rejected
15. **testEnforceStreamingRead** - Streaming read enforced (no buffering)
16. **testRateLimitEnforcement** - Rate limiting enforced (future)

**XXE Prevention Tests** (2 tests):
17. **testRejectXXEInL5X** - XXE attack in L5X rejected
18. **testRejectDTDInL5X** - DTD declarations rejected

---

## Installation Testing

### Pre-Build Testing

#### ✓ Build Module from Source

```bash
cd /modules/ignition-module-plc-emulator/logix-emulator-module
./gradlew clean build
```

**Expected output**:
```
BUILD SUCCESSFUL in 45s
16 actionable tasks: 16 executed
```

**Verify module file**:
```bash
ls -lh build/LogixPLCEmulator-{version}.modl
```

Expected size: ~12-15MB

#### ✓ Verify Module Contents

```bash
unzip -l build/LogixPLCEmulator-{version}.modl | head -20
```

**Required files**:
- `module.xml`
- `gateway.jar`
- `designer.jar`
- `common.jar`
- `lib/` directory with dependencies

### Gateway Installation

#### ✓ Install Module

1. Navigate to **Gateway Config → System → Modules**
2. Click **"Install or Upgrade a Module"**
3. Upload `LogixPLCEmulator-{version}.modl`
4. Click **"Install"**
5. Gateway prompts for restart
6. Click **"Restart"**

**Expected behaviour**:
- Installation starts without errors
- Gateway restarts automatically
- Module appears in list with "RUNNING" status

#### ✓ Verify Installation

Check module details:
- **Name**: Logix PLC Emulator
- **Version**: 11.1.2
- **License**: Free Module
- **Scopes**: G (Gateway only)
- **Status**: Running

#### ✓ Check Gateway Logs

```bash
tail -f /usr/local/ignition/logs/wrapper.log
```

**Expected log entries**:
```
INFO  [SimulatorModuleHook] Logix PLC Emulator module starting...
INFO  [SimulatorModuleHook] Registered Logix PLC Emulator device driver
INFO  [SimulatorModuleHook] Logix PLC Emulator module started successfully
```

**No errors should appear** during startup.

### Post-Installation Verification

#### ✓ Device Type Registration

1. Navigate to **Config → Devices → Create New Device**
2. In the device type dropdown, verify **"Logix PLC Emulator"** appears

#### ✓ Sidebar Menu (Optional Feature)

1. Navigate to **Gateway Config**
2. Check left sidebar for **"Logix PLC Emulator"** menu item (if implemented)

---

## Device Configuration Testing

### Test Case 1: Create Device Without File

**Purpose**: Verify device can be created in "waiting" state

**Steps**:
1. Go to **Config → Devices → Create New Device**
2. Select **"Logix PLC Emulator"**
3. Enter device name: `TestDevice1`
4. Select parser type: **Rockwell L5K**
5. Leave **PLC File Content** empty
6. Click **"Save"**

**Expected Result**:
- Device created successfully
- Status: **"Ready - Waiting for file upload"**
- No tags created yet
- No errors in logs

**Verification**:
```bash
grep "TestDevice1" /usr/local/ignition/logs/wrapper.log
```

Should show device registration but no tag creation.

### Test Case 2: Create Device With File Content

**Purpose**: Verify immediate tag creation when file provided

**Steps**:
1. Create new device: `TestDevice2`
2. Select parser type: **Rockwell L5K**
3. Paste valid L5K content into **PLC File Content** field
4. Set **File Name**: `test.L5K`
5. Click **"Save"**

**Expected Result**:
- Device created successfully
- Status: **"Running - X tags"** (where X = tag count from file)
- Tags visible in OPC browser
- Logs show successful parsing and tag creation

**Verification**:
```bash
grep -A 5 "TestDevice2.*tags created" /usr/local/ignition/logs/wrapper.log
```

### Test Case 3: Edit Device and Upload File

**Purpose**: Verify file upload button functionality

**Steps**:
1. Edit existing device `TestDevice1`
2. Click **"📁 Upload PLC File"** button
3. Select a `.L5K` file
4. Verify file content populates textarea
5. Click **"Save"**

**Expected Result**:
- File content appears in textarea
- File name auto-populated
- Tags created after save
- Device status changes to "Running"

**Note**: If upload button doesn't appear, use copy/paste method (see Method 3 in QUICK_START.md)

---

## File Upload Testing

### Test Case 1: Upload Valid L5K File

**Endpoint**: `/res/logixemulator/edit-program.html`

**Steps**:
1. Navigate to Edit Program page
2. Drag and drop `sample.L5K` file
3. Observe upload progress
4. Check response message

**Expected Result**:
```json
{
  "success": true,
  "message": "File uploaded successfully",
  "filename": "sample.L5K",
  "size": 12345,
  "tags_created": 42
}
```

**Verification**:
- Response status: 200 OK
- Tags visible in OPC browser
- Logs show parsing success

### Test Case 2: Upload an L5X File (must be rejected)

**File**: `controller.L5X` (Rockwell XML export)

**Steps**:
1. Attempt to upload the L5X file via Edit Program page
2. Verify it is rejected, not parsed (L5X withdrawn in 11.0.0)

**Expected Result**:
- File parsed successfully
- UDTs expanded correctly
- Predefined types (TIMER, COUNTER, PID) expanded
- All 22 Rockwell types supported

### Test Case 3: Upload JSON File

**File**: `tags.json`

**Sample content**:
```json
{
  "controller": "MyController",
  "tags": [
    {
      "name": "Motor1_Speed",
      "type": "REAL",
      "scope": "Controller:Global"
    },
    {
      "name": "Counter1",
      "type": "DINT",
      "scope": "Program:MainProgram"
    }
  ]
}
```

**Expected Result**:
- JSON parsed successfully
- Tags created with correct data types
- Scope hierarchy preserved

### Test Case 4: Upload CSV File

**File**: `tags.csv`

**Sample content**:
```csv
TagName,DataType,Scope
Motor1_Speed,REAL,Controller:Global
Motor1_Running,BOOL,Controller:Global
Counter1,DINT,Program:MainProgram
```

**Expected Result**:
- CSV parsed successfully
- Tags created with correct data types
- Scope hierarchy preserved

### Test Case 5: Empty File Rejection

**File**: `empty.L5K` (0 bytes)

**Expected Result**:
```json
{
  "success": false,
  "error": "File is empty"
}
```

- Response status: 400 Bad Request
- No tags created
- Logs show validation failure

### Test Case 6: Oversized File Rejection

**File**: `huge.L5K` (60MB)

> The real limit is 50MB (`FileValidator.DEFAULT_MAX_SIZE_MB`). Use a file well over that
> threshold — an 11MB or 12MB file is accepted, not rejected.

**Expected Result**:
```json
{
  "success": false,
  "error": "File too large: 60 MB exceeds max 50 MB"
}
```

- Response status: 413 Payload Too Large
- Upload rejected before reading content
- DoS attack prevented

### Test Case 7: Invalid File Type Rejection

**File**: `malware.exe`

**Expected Result**:
```json
{
  "success": false,
  "error": "Invalid file type. Supported: .l5k, .json, .csv"
}
```

- Response status: 400 Bad Request
- File rejected by extension and content validation

---

## Tag Creation Testing

### Test Case 1: Basic Tag Creation

**Input**: L5K file with 5 DINT tags

**Expected OPC-UA Structure**:
```
[ns=1;s=TestDevice]/
└── Controller:Global/
    ├── Tag1 (Int32)
    ├── Tag2 (Int32)
    ├── Tag3 (Int32)
    ├── Tag4 (Int32)
    └── Tag5 (Int32)
```

**Verification**:
1. Open **Designer → Tools → OPC Browser**
2. Navigate to device folder
3. Verify all 5 tags present
4. Verify data types correct (Int32)

### Test Case 2: UDT Tag Creation

**Input**: L5K with UDT definition and instance

**UDT Definition**:
```
TYPE MyMotor
  Speed : REAL;
  Running : BOOL;
  Alarm : BOOL;
END_TYPE

TAG Motor1 : MyMotor;
```

**Expected OPC-UA Structure**:
```
[ns=1;s=TestDevice]/
└── Controller:Global/
    └── Motor1/ (Folder)
        ├── Speed (Float)
        ├── Running (Boolean)
        └── Alarm (Boolean)
```

**Verification**:
- UDT appears as folder, not single tag
- Member variables appear as individual tags
- Data types correct

### Test Case 3: Predefined Type Expansion

**Input**: L5K with TIMER, COUNTER, PID tags

**Expected**:
- **TIMER** expands to: PRE, ACC, EN, TT, DN (5 members)
- **COUNTER** expands to: PRE, ACC, CU, CD, DN, OV, UN (7 members)
- **PID** expands to: 14 members (PV, SP, CV, etc.)

**Verification**:
- All members appear as separate tags
- Data types match Rockwell specification

### Test Case 4: Array Tag Creation

**Input**: L5K with array tag

```
TAG Speeds : ARRAY[0..9] OF REAL;
```

**Expected OPC-UA Structure**:
```
[ns=1;s=TestDevice]/
└── Controller:Global/
    └── Speeds/ (Folder)
        ├── [0] (Float)
        ├── [1] (Float)
        ...
        └── [9] (Float)
```

**Verification**:
- Array appears as folder
- Each element is individual tag
- Indexing correct

---

## Security Testing

### Test Case 1: Authentication Enforcement

**Purpose**: Verify unauthenticated uploads are blocked

**Test**:
```bash
curl -X POST http://localhost:8088/data/logixemulator/upload \
  -H "Content-Type: multipart/form-data" \
  -F "file=@test.L5K"
```

**Expected Result**:
- Response status: **401 Unauthorized**
- Response body: `{"error": "Authentication required"}`
- File NOT uploaded
- Logs show authentication failure

**Automated Test**: `FileUploadRoutesSecurityTest.testUploadRequiresAuthentication()`

### Test Case 2: Path Traversal Protection

**Purpose**: Verify path traversal attacks are blocked

**Test payloads**:
```
../../../etc/passwd
..\\..\\windows\\system32\\config
/etc/shadow
%2e%2e%2fpasswd
```

**Expected Result**:
- Response status: **400 Bad Request**
- Response body: `{"error": "Invalid filename"}`
- File NOT uploaded
- Logs show path traversal attempt detected

**Automated Tests**:
- `testRejectPathTraversalFilename()`
- `testRejectWindowsPathTraversal()`
- `testRejectURLEncodedTraversal()`

### Test Case 3: XXE Attack Prevention

**Purpose**: Verify XML External Entity attacks are blocked

**Malicious L5X**:
```xml
<?xml version="1.0"?>
<!DOCTYPE Controller [
  <!ENTITY xxe SYSTEM "file:///etc/passwd">
]>
<Controller>
  <Name>&xxe;</Name>
</Controller>
```

**Expected Result**:
- XML parsing throws exception
- External entity NOT resolved
- `/etc/passwd` NOT read
- Response status: **400 Bad Request**

**Automated Test**: `L5XParserTest.testXXEPrevention()`

**Security Features Applied**:
```java
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
factory.setXIncludeAware(false);
factory.setExpandEntityReferences(false);
```

### Test Case 4: File Size DoS Prevention

**Purpose**: Verify large file attacks are blocked

> The real limit is 50MB — use a file over 50MB, not a 15MB file. Auth is a Gateway session
> cookie (see [API_REFERENCE.md](API_REFERENCE.md#authentication)), not HTTP Basic.

**Test**:
```bash
# Generate a 60MB file - over the real 50MB limit
dd if=/dev/zero of=huge.L5K bs=1M count=60

# Attempt upload (requires an authenticated session cookie, not Basic auth)
curl -X POST "http://localhost:8088/data/logixemulator/upload?device=SomeDevice" \
  -b cookies.txt \
  -H "X-Requested-With: XMLHttpRequest" \
  -H "X-Filename: huge.L5K" \
  --data-binary @huge.L5K
```

**Expected Result**:
- Response status: **413 Payload Too Large**
- Upload rejected BEFORE reading content
- Server memory not exhausted
- Response time < 1 second

**Automated Test**: `FileValidatorTest.testRejectOversizedFile()`

---

## Gateway Config Integration Testing

### ✓ Device Configuration Form

1. Navigate to **Config → Devices → Create New Device**
2. Select **"Logix PLC Emulator"**
3. Verify form fields:
   - **Device Name** (text input, required)
   - **Parser Type** (dropdown: Rockwell L5K, JSON, CSV)
   - **PLC File Content** (textarea, optional)
   - **File Name** (text input, optional)
   - **📁 Upload PLC File** button (if JavaScript loaded)
4. Test form validation:
   - Submit with empty device name → Error
   - Submit with no parser type → Error
   - Submit with device name only → Success (waiting state)

### ✓ Device Status Display

1. Create device without file
2. Verify status: **"Ready - Waiting for file upload"**
3. Upload file
4. Verify status changes to: **"Running - X tags"**

### ✓ OPC Browser Integration

1. Open **Designer → Tools → OPC Browser**
2. Navigate to **OPC-UA → Ignition OPC-UA Server**
3. Find device in namespace
4. Verify folder structure matches PLC file
5. Subscribe to tag and verify value updates

---

## Cross-Platform Testing

### Windows Testing

**Platform**: Windows Server 2019/2022

**Steps**:
1. Install Ignition Gateway 8.3.0+
2. Install module
3. Create device
4. Upload L5K file via Edit Program page
5. Verify tags created

**Expected**: All features work identically to Linux

### Linux Testing

**Platform**: Ubuntu 22.04 LTS, CentOS 8

**Steps**:
1. Install Ignition Gateway
2. Install module
3. Create device
4. Upload file
5. Verify tags

**Expected**: All features work identically

### Docker Testing

**Image**: `inductiveautomation/ignition:8.3.0`

**Steps**:
```bash
# Start Ignition Gateway in Docker
docker run -d -p 8088:8088 inductiveautomation/ignition:8.3.0

# Copy module to container
docker cp LogixPLCEmulator-{version}.modl <container>:/tmp/

# Install via Gateway Config web UI
# Test device creation and file upload
```

**Expected**: Full functionality in containerized environment

---

## Performance Testing

### Test Case 1: Large File Parsing

**File**: L5K with 1,000 tags

**Test**:
1. Upload file via Edit Program page
2. Measure time to completion
3. Verify all tags created

**Expected**:
- Parse time: < 5 seconds
- All 1,000 tags created
- Gateway remains responsive
- Memory usage < 100MB increase

**Automated**:
```bash
time curl -X POST http://localhost:8088/data/logixemulator/upload \
  -u admin:password \
  -F "file=@large_1000_tags.L5K"
```

### Test Case 2: Multiple Devices

**Test**:
1. Create 10 devices
2. Upload different files to each
3. Verify all devices running

**Expected**:
- All 10 devices in "Running" status
- No tag namespace collisions
- Each device independent
- Gateway CPU usage < 10%

### Test Case 3: Hot Reload Performance (If Implemented)

**Test**:
1. Create device with file
2. Modify file on disk
3. Measure reload time

**Expected**:
- File change detected within 5 seconds
- Tags updated within 2 seconds
- No device downtime

---

## Regression Testing Checklist

Before each release, verify:

### Core Functionality
- [ ] Module builds without errors
- [ ] Module installs successfully
- [ ] Device appears in dropdown
- [ ] Device can be created without file
- [ ] File upload works (all 4 formats)
- [ ] Tags created correctly
- [ ] OPC-UA browsing works

### Security
- [ ] All 92 automated tests pass
- [ ] Authentication enforced
- [ ] Path traversal blocked
- [ ] XXE attacks blocked
- [ ] File size limits enforced

### File Formats
- [ ] L5K parsing works
- [ ] L5X upload is rejected with L5K guidance
- [ ] JSON parsing works
- [ ] CSV parsing works

### Tag Types
- [ ] Basic types (BOOL, DINT, REAL, STRING)
- [ ] UDT expansion
- [ ] Predefined types (TIMER, COUNTER, PID, PIDE, ALARM_ANALOG, AXIS_CIP_DRIVE)
- [ ] Arrays

### UI Integration
- [ ] Edit Program page loads
- [ ] Upload button appears (or copy/paste works)
- [ ] Device config form works
- [ ] Status displays correctly

### Documentation
- [ ] README.md accurate
- [ ] QUICK_START.md accurate
- [ ] CHANGELOG.md updated
- [ ] Version numbers consistent

---

## Test Data Files

### Recommended Test Files

**Location**: `logix-emulator-module/test-data/`

1. **basic.L5K** - 5 simple tags (BOOL, DINT, REAL)
2. **udt.L5K** - UDT definition and instance
3. **timer_counter.L5K** - TIMER and COUNTER predefined types
4. **pid.L5K** - PID predefined type (14 members)
5. **large.L5K** - 1,000 tags for performance testing
6. **controller.L5X** - XML format Rockwell export
7. **tags.json** - JSON format tag definitions
8. **tags.csv** - CSV format tag list
9. **empty.L5K** - Empty file (0 bytes)
10. **malformed.L5X** - Invalid XML for error handling
11. **xxe-attack.L5X** - XXE attack payload for security testing

### Creating Test Files

**Generate large L5K**:
```python
with open('large.L5K', 'w') as f:
    f.write('CONTROLLER TestController\n')
    for i in range(1000):
        f.write(f'TAG Tag_{i} : DINT;\n')
```

**Sample JSON**:
```json
{
  "controller": "TestController",
  "tags": [
    {"name": "Motor1_Speed", "type": "REAL", "scope": "Controller:Global"},
    {"name": "Motor1_Running", "type": "BOOL", "scope": "Controller:Global"}
  ]
}
```

---

## Troubleshooting Test Failures

### Module Installation Fails

**Symptoms**: Error during installation, module status "Failed"

**Check**:
1. Ignition version (must be 8.3.0+)
2. Java version (must be 17)
3. Gateway logs for specific error
4. Module signing (if using signed modules)

**Solution**:
- Verify dependencies in `build.gradle.kts`
- Check for conflicting modules
- Review `wrapper.log` for stack traces

### Authentication Tests Failing

**Symptoms**: `testUploadRequiresAuthentication()` fails

**Check**:
1. SecurityContext properly injected
2. Authentication filter registered
3. Logs show auth check executed

**Solution**:
```bash
grep "Authentication" /usr/local/ignition/logs/wrapper.log
```

Look for "Authentication required" messages.

### XXE Test Failing

**Symptoms**: `testXXEPrevention()` doesn't throw exception

**Check**:
1. XML security features enabled in `L5XParser.java:52-67`
2. DTD processing disabled
3. External entities disabled

**Solution**:
Verify all 6 security features are set to `true`/`false` correctly.

### Tags Not Created

**Symptoms**: File uploads successfully but no tags appear

**Check**:
1. Parser type matches file format
2. File content valid
3. Device status shows tag count
4. Logs show parsing success

**Solution**:
```bash
grep -A 10 "Parsing file" /usr/local/ignition/logs/wrapper.log
```

Look for parse errors or validation failures.

### Performance Tests Slow

**Symptoms**: Large file parsing takes > 5 seconds

**Check**:
1. Gateway CPU usage
2. Memory available
3. Disk I/O performance
4. Number of concurrent devices

**Solution**:
- Increase Gateway heap size
- Use SSD for Gateway data directory
- Profile code for bottlenecks

---

## CI/CD Integration

### GitHub Actions

**File**: `.github/workflows/test.yml`

**Automated on every push**:
1. Build module
2. Run all 92 tests
3. Generate test reports
4. Check for test failures

**View results**:
```
https://github.com/nigelgwork/ignition-plc-simulator/actions
```

### Local CI Testing

```bash
# Simulate CI environment
./gradlew clean test --no-daemon --console=plain
```

**Expected**: All tests pass, no warnings.

---

## Version Information

**Module Version**: 11.1.2
**Ignition Compatibility**: 8.3.0+
**Java Version**: 17
**Test Framework**: JUnit Jupiter 5.10.1
**Mocking**: Mockito 5.7.0
**Assertions**: AssertJ 3.24.2

---

## Additional Resources

- **QUICK_START.md** - User installation and usage guide
- **ARCHITECTURE.md** - Technical architecture deep dive
- **SECURITY.md** - Security best practices and credential management
- **TAG_CREATION_FLOW.md** - Detailed tag creation flow diagrams
- **CHANGELOG.md** - Complete version history
- **BUILD.md** - Building and packaging instructions
