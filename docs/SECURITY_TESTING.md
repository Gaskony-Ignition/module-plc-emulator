# Security Testing Guide - Logix PLC Emulator

**Version**: 10.1.0
**Last Updated**: 2026-02-21
**Security Status**: All critical vulnerabilities resolved

---

## Table of Contents

1. [Overview](#overview)
2. [Threat Model](#threat-model)
3. [Automated Security Tests](#automated-security-tests)
4. [Manual Penetration Testing](#manual-penetration-testing)
5. [XXE Attack Testing](#xxe-attack-testing)
6. [Authentication Testing](#authentication-testing)
7. [Path Traversal Testing](#path-traversal-testing)
8. [Denial of Service Testing](#denial-of-service-testing)
9. [Credential Management Testing](#credential-management-testing)
10. [Security Regression Testing](#security-regression-testing)
11. [Reporting Security Issues](#reporting-security-issues)

---

## Overview

This guide provides comprehensive security testing procedures for the Logix PLC Emulator module. Use this guide to:

- Validate security fixes in v5.4.9
- Perform penetration testing
- Conduct security audits
- Verify compliance with security standards
- Test security regression after code changes

### Security Vulnerabilities Fixed in v5.4.9

| Vulnerability | Severity | Status | Test Coverage |
|---------------|----------|--------|---------------|
| **XXE (XML External Entity)** | CRITICAL | ✅ FIXED | 2 automated tests |
| **Authentication Bypass** | HIGH | ✅ FIXED | 6 automated tests |
| **Path Traversal** | HIGH | ✅ FIXED | 6 automated tests |
| **Hardcoded Credentials** | MEDIUM | ✅ FIXED | Manual verification |
| **File Size DoS** | MEDIUM | ✅ FIXED | 4 automated tests |

**Total Security Tests**: 18 automated + manual verification

---

## Threat Model

### Attack Surface

1. **File Upload Endpoints**
   - `/data/logixemulator/upload` - REST API for file upload
   - `/res/logixemulator/edit-program.html` - Web UI for file upload
   - Device configuration form - Gateway Config UI

2. **Input Vectors**
   - PLC file content (L5K, L5X, JSON, CSV)
   - File names
   - Device names
   - HTTP headers (Content-Length, Content-Type)

3. **Authentication Layer**
   - Ignition SecurityContext
   - Gateway user sessions
   - Role-based access control

### Threat Actors

1. **Unauthenticated Attackers** - External attackers without gateway access
2. **Authenticated Users** - Legitimate users attempting privilege escalation
3. **Malicious Files** - Crafted PLC files designed to exploit parsing vulnerabilities
4. **Insider Threats** - Users with legitimate access attempting unauthorized actions

### Assets to Protect

1. **Gateway Server Filesystem** - Prevent arbitrary file read/write
2. **Gateway Memory** - Prevent memory exhaustion (DoS)
3. **Configuration Data** - Device settings, credentials
4. **OPC-UA Tag Space** - Prevent unauthorized tag manipulation

---

## Automated Security Tests

### Running All Security Tests

```bash
./gradlew test --tests FileUploadRoutesSecurityTest
```

**Expected Output**:
```
FileUploadRoutesSecurityTest > testUploadRequiresAuthentication() PASSED
FileUploadRoutesSecurityTest > testUploadWithValidAuthentication() PASSED
FileUploadRoutesSecurityTest > testRejectPathTraversalFilename() PASSED
FileUploadRoutesSecurityTest > testRejectPathTraversalDeviceName() PASSED
FileUploadRoutesSecurityTest > testRejectAbsolutePathFilename() PASSED
FileUploadRoutesSecurityTest > testRejectWindowsPathTraversal() PASSED
FileUploadRoutesSecurityTest > testRejectURLEncodedTraversal() PASSED
FileUploadRoutesSecurityTest > testAcceptSafeFilenames() PASSED
FileUploadRoutesSecurityTest > testRejectMissingContentLength() PASSED
FileUploadRoutesSecurityTest > testRejectOversizedContent() PASSED
FileUploadRoutesSecurityTest > testEnforceStreamingRead() PASSED
FileUploadRoutesSecurityTest > testXXEPrevention() PASSED
FileUploadRoutesSecurityTest > testRejectDTDInL5X() PASSED
... (18 total tests)

BUILD SUCCESSFUL
18 tests completed, 18 succeeded
```

### Test Coverage Matrix

| Security Feature | Test Method | Line Coverage |
|------------------|-------------|---------------|
| Authentication enforcement | `testUploadRequiresAuthentication()` | FileUploadRoutes:771-858 |
| Valid auth acceptance | `testUploadWithValidAuthentication()` | FileUploadRoutes:771-858 |
| Path traversal - Unix | `testRejectPathTraversalFilename()` | FileUploadRoutes:859-923 |
| Path traversal - Windows | `testRejectWindowsPathTraversal()` | FileUploadRoutes:859-923 |
| Path traversal - URL encoded | `testRejectURLEncodedTraversal()` | FileUploadRoutes:859-923 |
| Absolute path rejection | `testRejectAbsolutePathFilename()` | FileUploadRoutes:859-923 |
| XXE prevention | `testXXEPrevention()` | L5XParser:52-67 |
| DTD rejection | `testRejectDTDInL5X()` | L5XParser:52-67 |
| File size DoS | `testRejectOversizedContent()` | FileUploadRoutes:137-216 |
| Content-Length validation | `testRejectMissingContentLength()` | FileUploadRoutes:137-216 |

---

## Manual Penetration Testing

### Prerequisites

1. **Test Environment**
   - Ignition Gateway 8.3.0+ running on isolated network
   - Logix PLC Emulator module v5.4.9 installed
   - Burp Suite or similar intercepting proxy
   - curl or Postman for API testing
   - Valid test credentials (admin/password)

2. **Tools**
   - curl (command-line HTTP client)
   - Burp Suite Professional (recommended)
   - OWASP ZAP (alternative)
   - xxe-payloads repository (https://github.com/swisskyrepo/PayloadsAllTheThings/tree/master/XXE%20Injection)

3. **Safety Precautions**
   - Use isolated test environment ONLY
   - Do NOT test on production systems
   - Obtain written authorization before testing
   - Document all findings

---

## XXE Attack Testing

### Background

**XXE (XML External Entity)** attacks exploit XML parsers that process external entity references. Attackers can:
- Read arbitrary files from the server (`/etc/passwd`, `C:\Windows\win.ini`)
- Perform SSRF (Server-Side Request Forgery) attacks
- Cause denial of service with billion laughs attacks

### Fixed Implementation

**Location**: `gateway/src/main/java/com/inductiveautomation/logixemulator/gateway/parser/L5XParser.java:52-67`

**Security Features**:
```java
DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

// 1. Disallow DOCTYPE declarations (strictest - recommended)
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

// 2. Disable external general entities
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);

// 3. Disable external parameter entities
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

// 4. Disable loading external DTDs
factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

// 5. Disable XInclude processing
factory.setXIncludeAware(false);

// 6. Disable entity expansion
factory.setExpandEntityReferences(false);
```

### Test Case 1: Classic XXE File Read

**Payload**: `xxe-file-read.L5X`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE Controller [
  <!ELEMENT Controller ANY >
  <!ENTITY xxe SYSTEM "file:///etc/passwd" >
]>
<RSLogix5000Content SchemaRevision="1.0">
  <Controller Use="Target" Name="&xxe;">
    <Tags>
      <Tag Name="ExfilData" DataType="STRING"/>
    </Tags>
  </Controller>
</RSLogix5000Content>
```

**Test Procedure**:
```bash
curl -X POST http://localhost:8088/data/logixemulator/upload \
  -u admin:password \
  -H "Content-Type: multipart/form-data" \
  -F "file=@xxe-file-read.L5X" \
  -F "deviceName=TestDevice"
```

**Expected Result**:
- **Response Status**: 400 Bad Request
- **Response Body**: `{"error": "XML parsing failed: DOCTYPE is disallowed"}`
- **Server Logs**: `SAXParseException: DOCTYPE is disallowed when the feature "http://apache.org/xml/features/disallow-doctype-decl" set to true`
- **File NOT Read**: `/etc/passwd` is NOT accessed
- **Tags NOT Created**: No tags appear in OPC browser

**Automated Test**: `L5XParserTest.testXXEPrevention()`

### Test Case 2: Billion Laughs Attack (XML Bomb)

**Payload**: `xxe-billion-laughs.L5X`

```xml
<?xml version="1.0"?>
<!DOCTYPE lolz [
  <!ENTITY lol "lol">
  <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
  <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
  <!ENTITY lol4 "&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;">
  <!ENTITY lol5 "&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;">
]>
<RSLogix5000Content>
  <Controller Name="&lol5;">
    <Tags></Tags>
  </Controller>
</RSLogix5000Content>
```

**Expected Result**:
- **Response Status**: 400 Bad Request
- **Memory Usage**: No spike in JVM heap
- **Response Time**: < 1 second (no exponential expansion)
- **Error**: DTD rejected before entity expansion

**Verification**:
```bash
# Monitor Gateway memory during attack
watch -n 1 'ps aux | grep ignition'
```

### Test Case 3: SSRF via XXE

**Payload**: `xxe-ssrf.L5X`

```xml
<?xml version="1.0"?>
<!DOCTYPE Controller [
  <!ENTITY xxe SYSTEM "http://169.254.169.254/latest/meta-data/iam/security-credentials/">
]>
<RSLogix5000Content>
  <Controller Name="&xxe;">
    <Tags></Tags>
  </Controller>
</RSLogix5000Content>
```

**Expected Result**:
- **Response Status**: 400 Bad Request
- **No Network Requests**: Verify firewall logs show no outbound requests to 169.254.169.254
- **DTD Rejected**: Same error as Test Case 1

### Test Case 4: Local DTD Exploitation

**Payload**: `xxe-local-dtd.L5X`

```xml
<?xml version="1.0"?>
<!DOCTYPE Controller SYSTEM "/usr/share/xml/fontconfig/fonts.dtd">
<RSLogix5000Content>
  <Controller Name="Test">
    <Tags></Tags>
  </Controller>
</RSLogix5000Content>
```

**Expected Result**:
- **Response Status**: 400 Bad Request
- **Error**: `load-external-dtd` is disabled
- **No File Access**: DTD file not loaded

---

## Authentication Testing

### Background

File upload endpoints MUST require authentication. Unauthenticated access could allow attackers to:
- Upload malicious files
- Create unauthorized devices
- Exfiltrate configuration data
- Perform reconnaissance

### Fixed Implementation

**Location**: `gateway/src/main/java/com/inductiveautomation/logixemulator/gateway/web/FileUploadRoutes.java:771-858`

**Authentication Check**:
```java
SecurityContext securityContext = gatewayContext.getSecurityManager().getSecurityContext();

if (securityContext == null || !securityContext.isAuthenticated()) {
    response.setStatus(401);
    response.getWriter().write("{\"error\": \"Authentication required\"}");
    return;
}
```

**Key Fix**: Removed 1-second fallback that allowed temporary unauthenticated access.

### Test Case 1: Unauthenticated Upload Attempt

**Test**:
```bash
curl -v -X POST http://localhost:8088/data/logixemulator/upload \
  -H "Content-Type: multipart/form-data" \
  -F "file=@test.L5K" \
  -F "deviceName=TestDevice"
```

**Expected Result**:
- **Response Status**: 401 Unauthorized
- **Response Body**: `{"error": "Authentication required"}`
- **Response Headers**: `WWW-Authenticate: Basic realm="Ignition"`
- **File NOT Uploaded**: Verify no files created in temp directory
- **Device NOT Created**: Verify device list unchanged
- **Logs**: `Authentication required for file upload`

**Automated Test**: `FileUploadRoutesSecurityTest.testUploadRequiresAuthentication()`

### Test Case 2: Valid Authentication

**Test**:
```bash
curl -X POST http://localhost:8088/data/logixemulator/upload \
  -u admin:password \
  -H "Content-Type: multipart/form-data" \
  -F "file=@test.L5K" \
  -F "deviceName=TestDevice"
```

**Expected Result**:
- **Response Status**: 200 OK
- **Response Body**: `{"success": true, "message": "File uploaded successfully"}`
- **File Processed**: Tags created from file
- **Logs**: `File uploaded by user: admin`

**Automated Test**: `FileUploadRoutesSecurityTest.testUploadWithValidAuthentication()`

### Test Case 3: Invalid Credentials

**Test**:
```bash
curl -v -X POST http://localhost:8088/data/logixemulator/upload \
  -u hacker:wrongpassword \
  -H "Content-Type: multipart/form-data" \
  -F "file=@test.L5K"
```

**Expected Result**:
- **Response Status**: 401 Unauthorized
- **Failed Login Logged**: Check Gateway audit log
- **Account Lockout**: After 5 failed attempts (if enabled)

### Test Case 4: Expired Session

**Test**:
1. Obtain valid session cookie
2. Wait for session timeout (default 30 minutes)
3. Attempt upload with expired session

**Expected Result**:
- **Response Status**: 401 Unauthorized
- **Redirect**: To login page (for web UI)
- **Session Invalidated**: Cannot reuse expired session

### Test Case 5: Session Fixation Attack

**Attack Scenario**:
1. Attacker creates session before login
2. Tricks victim into logging in with attacker's session ID
3. Attacker uses victim's authenticated session

**Test**:
```bash
# Get session cookie before auth
COOKIE=$(curl -c - http://localhost:8088/data/logixemulator/upload | grep JSESSIONID)

# Attempt to use pre-auth cookie after victim login
curl -b "$COOKIE" -X POST http://localhost:8088/data/logixemulator/upload \
  -F "file=@test.L5K"
```

**Expected Result**:
- **Response Status**: 401 Unauthorized
- **Session Regenerated**: Ignition regenerates session ID on login
- **Old Session Invalid**: Pre-auth cookie cannot be used post-auth

### Test Case 6: Role-Based Access Control

**Test**:
```bash
# Login as user with "Viewer" role (no upload permissions)
curl -X POST http://localhost:8088/data/logixemulator/upload \
  -u viewer:viewpass \
  -F "file=@test.L5K"
```

**Expected Result**:
- **Response Status**: 403 Forbidden
- **Response Body**: `{"error": "Insufficient permissions"}`
- **Required Role**: Designer or Admin

**Automated Test**: `FileUploadRoutesSecurityTest.testUploadRequiresDesignerRole()`

---

## Path Traversal Testing

### Background

Path traversal attacks allow attackers to access files outside the intended directory. Attackers could:
- Read sensitive files (`/etc/passwd`, `C:\Windows\System32\config\SAM`)
- Overwrite critical files
- Execute arbitrary code (if combined with other vulnerabilities)

### Fixed Implementation

**Location**: `gateway/src/main/java/com/inductiveautomation/logixemulator/gateway/web/FileUploadRoutes.java:859-923`

**Sanitization Functions**:
```java
private String sanitizeFilename(String filename) {
    if (filename == null || filename.trim().isEmpty()) {
        throw new IllegalArgumentException("Filename cannot be empty");
    }

    // Remove path separators
    String sanitized = filename.replaceAll("[/\\\\]", "_");

    // Remove path traversal sequences
    sanitized = sanitized.replaceAll("\\.\\.", "_");

    // Canonical path validation
    Path path = Paths.get(targetDirectory, sanitized);
    if (!path.normalize().startsWith(targetDirectory)) {
        throw new SecurityException("Path traversal detected");
    }

    return sanitized;
}
```

### Test Case 1: Unix Path Traversal

**Payloads**:
```
../../../etc/passwd
../../../../etc/shadow
../../../../../../etc/hosts
```

**Test**:
```bash
curl -X POST http://localhost:8088/data/logixemulator/upload \
  -u admin:password \
  -F "file=@test.L5K" \
  -F "filename=../../../etc/passwd"
```

**Expected Result**:
- **Response Status**: 400 Bad Request
- **Response Body**: `{"error": "Invalid filename: path traversal detected"}`
- **File NOT Created**: `/etc/passwd` NOT overwritten
- **Sanitized Filename**: Stored as `___.___etc_passwd` (if storage attempted)
- **Logs**: `Path traversal attempt detected: ../../../etc/passwd`

**Automated Test**: `FileUploadRoutesSecurityTest.testRejectPathTraversalFilename()`

### Test Case 2: Windows Path Traversal

**Payloads**:
```
..\\..\\..\\windows\\system32\\config\\SAM
..\\..\\..\\windows\\win.ini
C:\\Windows\\System32\\drivers\\etc\\hosts
```

**Test**:
```bash
curl -X POST http://localhost:8088/data/logixemulator/upload \
  -u admin:password \
  -F "file=@test.L5K" \
  -F "filename=..\\..\\..\\windows\\system32\\config\\SAM"
```

**Expected Result**:
- **Response Status**: 400 Bad Request
- **Error**: Path traversal detected
- **Windows Paths Rejected**: Backslashes sanitized

**Automated Test**: `FileUploadRoutesSecurityTest.testRejectWindowsPathTraversal()`

### Test Case 3: URL-Encoded Path Traversal

**Payloads**:
```
%2e%2e%2f%2e%2e%2f%2e%2e%2fetc%2fpasswd
%2e%2e%5c%2e%2e%5cwindows%5csystem32
..%2F..%2F..%2Fetc%2Fpasswd
```

**Test**:
```bash
curl -X POST http://localhost:8088/data/logixemulator/upload \
  -u admin:password \
  -F "file=@test.L5K" \
  -F "filename=%2e%2e%2f%2e%2e%2f%2e%2e%2fetc%2fpasswd"
```

**Expected Result**:
- **Response Status**: 400 Bad Request
- **URL Decoding Handled**: Decoded before validation
- **Path Traversal Detected**: Even after decoding

**Automated Test**: `FileUploadRoutesSecurityTest.testRejectURLEncodedTraversal()`

### Test Case 4: Absolute Path Injection

**Payloads**:
```
/etc/passwd
/etc/shadow
C:\Windows\System32\config\SAM
/var/log/ignition/wrapper.log
```

**Test**:
```bash
curl -X POST http://localhost:8088/data/logixemulator/upload \
  -u admin:password \
  -F "file=@test.L5K" \
  -F "filename=/etc/shadow"
```

**Expected Result**:
- **Response Status**: 400 Bad Request
- **Error**: Absolute paths rejected
- **Canonical Path Check**: Validates final path is within allowed directory

**Automated Test**: `FileUploadRoutesSecurityTest.testRejectAbsolutePathFilename()`

### Test Case 5: Null Byte Injection

**Payloads**:
```
test.L5K%00.txt
../../etc/passwd%00.L5K
malicious%00.exe
```

**Test**:
```bash
curl -X POST http://localhost:8088/data/logixemulator/upload \
  -u admin:password \
  -F "file=@test.L5K" \
  -F $'filename=test.L5K\x00.txt'
```

**Expected Result**:
- **Response Status**: 400 Bad Request
- **Null Bytes Stripped**: Java strings are null-byte safe
- **Extension Validation**: `.txt` extension rejected

### Test Case 6: Device Name Path Traversal

**Attack Vector**: Device names are used in OPC-UA node IDs

**Payload**:
```
../../devices
[ns=1;s=../../../system]
```

**Test**:
```bash
curl -X POST http://localhost:8088/data/logixemulator/upload \
  -u admin:password \
  -F "file=@test.L5K" \
  -F "deviceName=../../devices"
```

**Expected Result**:
- **Response Status**: 400 Bad Request
- **Error**: Invalid device name
- **OPC-UA Node NOT Created**: Prevents namespace pollution

**Automated Test**: `FileUploadRoutesSecurityTest.testRejectPathTraversalDeviceName()`

---

## Denial of Service Testing

### Background

DoS attacks attempt to exhaust server resources (memory, CPU, disk) to make the service unavailable.

### Test Case 1: File Size DoS

**Attack**: Upload extremely large file to exhaust memory

**Test**:
```bash
# Generate 15MB file (exceeds 10MB limit)
dd if=/dev/zero of=huge.L5K bs=1M count=15

# Attempt upload
curl -X POST http://localhost:8088/data/logixemulator/upload \
  -u admin:password \
  -H "Content-Type: multipart/form-data" \
  -F "file=@huge.L5K"
```

**Expected Result**:
- **Response Status**: 413 Payload Too Large
- **Response Time**: < 1 second (rejected before reading)
- **Memory Usage**: No spike in JVM heap
- **Logs**: `File size (15728640 bytes) exceeds maximum (10485760 bytes)`

**Implementation**:
```java
// Check Content-Length header BEFORE reading body
long contentLength = request.getContentLength();
if (contentLength > MAX_FILE_SIZE) {
    response.setStatus(413);
    return;
}
```

**Automated Test**: `FileValidatorTest.testRejectOversizedFile()`

### Test Case 2: Missing Content-Length Header

**Attack**: Omit Content-Length to bypass size check

**Test**:
```bash
curl -X POST http://localhost:8088/data/logixemulator/upload \
  -u admin:password \
  -H "Transfer-Encoding: chunked" \
  --data-binary @huge.L5K
```

**Expected Result**:
- **Response Status**: 411 Length Required
- **Error**: Content-Length header required
- **No Reading**: Request body not processed

**Automated Test**: `FileUploadRoutesSecurityTest.testRejectMissingContentLength()`

### Test Case 3: Slowloris Attack

**Attack**: Send file content very slowly to keep connections open

**Test**:
```bash
# Send 1 byte per second
(
  echo -n "POST /data/logixemulator/upload HTTP/1.1\r\n"
  echo -n "Host: localhost:8088\r\n"
  echo -n "Content-Length: 1000000\r\n"
  echo -n "\r\n"
  for i in {1..1000000}; do
    echo -n "A"
    sleep 1
  done
) | nc localhost 8088
```

**Expected Result**:
- **Connection Timeout**: Gateway closes slow connections (default 30s)
- **Resource Limits**: Tomcat limits concurrent connections
- **No Resource Exhaustion**: Server remains responsive

**Configuration** (Gateway web.xml):
```xml
<Connector connectionTimeout="30000" />
```

### Test Case 4: Recursive Tag Explosion

**Attack**: Craft L5K file with deeply nested UDTs to cause stack overflow

**Payload**: `recursive-udt.L5K`
```
UDT RecursiveType
  Member1 : RecursiveType;
  Member2 : RecursiveType;
  Member3 : RecursiveType;
END_UDT

TAG Root : RecursiveType;
```

**Expected Result**:
- **Parse Error**: Circular reference detected
- **Stack Safe**: No stack overflow
- **Limits**: Maximum recursion depth enforced (default 100 levels)

---

## Credential Management Testing

### Background

Hardcoded credentials in source code or configuration files pose a security risk. Credentials should:
- Be stored securely (encrypted, keystore)
- Never be committed to version control
- Use environment variables for CI/CD

### Test Case 1: Verify No Hardcoded Credentials

**Files to Check**:
```bash
# Search for potential hardcoded credentials
grep -r "password\s*=" logix-emulator-module/
grep -r "keyPassword" logix-emulator-module/
grep -r "keystorePassword" logix-emulator-module/
```

**Expected Result**:
- **gradle.properties**: Uses environment variables
```properties
keystorePassword=${env.KEYSTORE_PASSWORD}
keyPassword=${env.KEY_PASSWORD}
```

- **No Plain Text Passwords**: In any committed files
- **.gitignore**: Includes `gradle.properties`, `*.jks`, `*.p12`

### Test Case 2: Environment Variable Usage

**Verify Build**:
```bash
# Without environment variables (should fail with warning)
./gradlew clean build

# With environment variables (should succeed)
export KEYSTORE_PASSWORD=changeit
export KEY_PASSWORD=changeit
./gradlew clean build
```

**Expected Result**:
- **Build Succeeds**: With env vars set
- **Warning**: If env vars not set (unsigned module created)
- **No Credentials Exposed**: In build logs

### Test Case 3: Keystore Security

**Verify Keystore**:
```bash
# Check keystore file is not in version control
git ls-files | grep "\.jks$"
git ls-files | grep "\.p12$"
```

**Expected Result**:
- **No Output**: Keystores not committed
- **.gitignore**: Contains `*.jks` and `*.p12`

**Verify Keystore Permissions**:
```bash
ls -l module-keystore.jks
```

**Expected**: `-rw-------` (600) - Owner read/write only

---

## Security Regression Testing

### Pre-Release Security Checklist

Before releasing any new version, verify:

#### Automated Tests
- [ ] All 18 security tests pass
- [ ] XXE tests pass (L5XParserTest)
- [ ] Authentication tests pass (FileUploadRoutesSecurityTest)
- [ ] Path traversal tests pass (FileUploadRoutesSecurityTest)
- [ ] DoS tests pass (FileValidatorTest)

#### Manual Verification
- [ ] No hardcoded credentials in code
- [ ] Environment variables used for secrets
- [ ] Keystores not in version control
- [ ] `.gitignore` includes sensitive files
- [ ] SECURITY.md documentation updated
- [ ] CHANGELOG.md includes security fixes

#### Code Review Checklist
- [ ] All user input validated
- [ ] File paths canonicalized
- [ ] XML parsing uses secure configuration
- [ ] Authentication checked on all endpoints
- [ ] Error messages don't leak sensitive info
- [ ] Logging doesn't log passwords/tokens

#### Penetration Testing
- [ ] XXE attack blocked (manual test)
- [ ] Path traversal blocked (manual test)
- [ ] Authentication cannot be bypassed
- [ ] File size limits enforced
- [ ] SQL injection not applicable (no SQL)

### Regression Test Script

**Location**: `logix-emulator-module/security-test.sh`

```bash
#!/bin/bash
# Security Regression Test Script

echo "Running automated security tests..."
./gradlew test --tests FileUploadRoutesSecurityTest
./gradlew test --tests L5XParserTest

echo "Checking for hardcoded credentials..."
if grep -r "password\s*=\s*['\"]" logix-emulator-module/; then
    echo "ERROR: Hardcoded credentials found!"
    exit 1
fi

echo "Verifying .gitignore..."
if ! grep -q "*.jks" .gitignore; then
    echo "ERROR: .gitignore missing *.jks"
    exit 1
fi

echo "All security checks passed!"
```

---

## Reporting Security Issues

### Responsible Disclosure

If you discover a security vulnerability:

1. **DO NOT** open a public GitHub issue
2. **DO NOT** disclose vulnerability publicly before fix is released
3. **DO** email security report to: `security@yourcompany.com`
4. **DO** provide detailed reproduction steps
5. **DO** allow 90 days for fix before public disclosure

### Security Report Template

```
Subject: [SECURITY] Logix PLC Emulator - [Vulnerability Type]

Vulnerability Type: [XXE / Auth Bypass / Path Traversal / etc.]
Severity: [Critical / High / Medium / Low]
Affected Versions: [e.g., v5.4.0 - v5.4.8]
Fixed in Version: [e.g., v5.4.9]

Description:
[Detailed description of vulnerability]

Attack Scenario:
[Step-by-step exploitation scenario]

Proof of Concept:
[Code/commands demonstrating the vulnerability]

Impact:
[What an attacker could achieve]

Suggested Fix:
[Optional - your suggested remediation]

Discoverer:
[Your name and contact info - optional]
```

### Bug Bounty (If Applicable)

**Scope**: Logix PLC Emulator module v8.x

**In Scope**:
- XXE vulnerabilities
- Authentication bypass
- Path traversal
- Remote code execution
- SQL injection
- SSRF
- Sensitive data exposure

**Out of Scope**:
- DoS requiring >100 Mbps bandwidth
- Social engineering
- Physical attacks
- Vulnerabilities in Ignition platform (report to Inductive Automation)

**Rewards**:
- Critical: $500 - $1000
- High: $200 - $500
- Medium: $100 - $200
- Low: Recognition in CHANGELOG.md

---

## Version Information

**Module Version**: 10.1.0
**Security Review Date**: 2025-11-22
**Next Scheduled Review**: 2026-02-22 (quarterly)
**Security Contact**: security@yourcompany.com

---

## Additional Resources

- **OWASP Top 10**: https://owasp.org/www-project-top-ten/
- **CWE-611 (XXE)**: https://cwe.mitre.org/data/definitions/611.html
- **CWE-22 (Path Traversal)**: https://cwe.mitre.org/data/definitions/22.html
- **CWE-798 (Hardcoded Credentials)**: https://cwe.mitre.org/data/definitions/798.html
- **NIST Secure Configuration Guide**: https://nvd.nist.gov/
- **SECURITY.md** - Module security best practices
- **ARCHITECTURE.md** - Technical architecture with security design
- **TESTING.md** - General testing procedures
