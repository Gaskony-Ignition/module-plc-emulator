# API Reference - Logix PLC Emulator

**Version**: 8.1.0
**Last Updated**: 2026-02-11
**Base URL**: `http://your-gateway:8088`

---

## Table of Contents

1. [Overview](#overview)
2. [Authentication](#authentication)
3. [REST API Endpoints](#rest-api-endpoints)
4. [File Upload API](#file-upload-api)
5. [Device Management API](#device-management-api)
6. [Web UI Routes](#web-ui-routes)
7. [Request/Response Formats](#requestresponse-formats)
8. [Error Codes](#error-codes)
9. [Rate Limiting](#rate-limiting)
10. [Code Examples](#code-examples)

---

## Overview

The Logix PLC Emulator exposes several APIs for:
- Uploading PLC files (L5K, L5X, JSON, CSV)
- Managing devices
- Retrieving device status
- Accessing web UI pages

### API Categories

| Category | Base Path | Authentication Required |
|----------|-----------|-------------------------|
| **File Upload** | `/data/logixemulator/*` | Yes (Designer/Admin role) |
| **Web UI** | `/res/logixemulator/*` | No (public resources) |
| **Device Config** | `/config/opc-ua/devices/*` | Yes (Admin role) |

### Supported Content Types

- `application/json`
- `multipart/form-data`
- `application/x-www-form-urlencoded`
- `text/plain`

---

## Authentication

All `/data/logixemulator/*` endpoints require authentication using Ignition Gateway credentials.

### Authentication Methods

#### 1. HTTP Basic Authentication (Recommended for API)

**Format**: `Authorization: Basic base64(username:password)`

**Example**:
```bash
curl -u admin:password http://localhost:8088/data/logixemulator/upload
```

**Header**:
```
Authorization: Basic YWRtaW46cGFzc3dvcmQ=
```

#### 2. Session Cookies (Web UI)

**Steps**:
1. Login via Gateway web UI
2. Browser stores `JSESSIONID` cookie
3. Cookie included in subsequent requests

**Example**:
```bash
# Login and save cookie
curl -c cookies.txt -d "username=admin&password=password" \
  http://localhost:8088/system/login

# Use cookie for API call
curl -b cookies.txt -F "file=@test.L5K" \
  http://localhost:8088/data/logixemulator/upload
```

### Required Roles

| Endpoint | Required Role |
|----------|---------------|
| File Upload | Designer or Administrator |
| Device Config | Administrator |
| Web UI (read-only) | Any authenticated user |

### Authentication Errors

**401 Unauthorized** - No credentials provided or invalid credentials
```json
{
  "error": "Authentication required"
}
```

**403 Forbidden** - Valid credentials but insufficient permissions
```json
{
  "error": "Insufficient permissions. Designer or Administrator role required."
}
```

---

## REST API Endpoints

### Endpoint Summary

| Method | Endpoint | Description | Auth Required |
|--------|----------|-------------|---------------|
| `POST` | `/data/logixemulator/upload` | Upload PLC file and apply to device | Yes |
| `GET` | `/data/logixemulator/devices` | List all simulator devices | Yes |
| `GET` | `/data/logixemulator/devices/{name}` | Get device details | Yes |
| `DELETE` | `/data/logixemulator/devices/{name}` | Delete device | Yes |
| `GET` | `/data/logixemulator/status` | Get module status | Yes |

---

## File Upload API

### POST /data/logixemulator/upload

Upload a PLC file and apply it to a specific device.

#### Request

**Method**: `POST`

**URL**: `/data/logixemulator/upload`

**Content-Type**: `multipart/form-data`

**Headers**:
```
Authorization: Basic <base64-credentials>
Content-Type: multipart/form-data; boundary=----WebKitFormBoundary...
```

**Form Fields**:

| Field | Type | Required | Description | Constraints |
|-------|------|----------|-------------|-------------|
| `file` | File | Yes | PLC file to upload | Max 10MB, extensions: .L5K, .L5X, .JSON, .CSV |
| `deviceName` | String | Yes | Target device name | 1-50 chars, alphanumeric + underscores |
| `parserType` | String | No | Parser to use | Values: `ROCKWELL_L5K`, `ROCKWELL_L5X`, `JSON`, `CSV` |
| `autoApply` | Boolean | No | Auto-apply to device | Default: `true` |

#### Examples

**cURL - Upload L5K File**:
```bash
curl -X POST http://localhost:8088/data/logixemulator/upload \
  -u admin:password \
  -F "file=@/path/to/plc.L5K" \
  -F "deviceName=Building1_PLC" \
  -F "parserType=ROCKWELL_L5K"
```

**cURL - Upload JSON File**:
```bash
curl -X POST http://localhost:8088/data/logixemulator/upload \
  -u admin:password \
  -F "file=@/path/to/tags.json" \
  -F "deviceName=TestDevice" \
  -F "parserType=JSON"
```

**JavaScript (Browser)**:
```javascript
const formData = new FormData();
formData.append('file', fileInput.files[0]);
formData.append('deviceName', 'Building1_PLC');
formData.append('parserType', 'ROCKWELL_L5K');

fetch('http://localhost:8088/data/logixemulator/upload', {
  method: 'POST',
  headers: {
    'Authorization': 'Basic ' + btoa('admin:password')
  },
  body: formData
})
  .then(response => response.json())
  .then(data => console.log('Success:', data))
  .catch(error => console.error('Error:', error));
```

**Python (requests library)**:
```python
import requests

url = 'http://localhost:8088/data/logixemulator/upload'
auth = ('admin', 'password')

files = {'file': open('/path/to/plc.L5K', 'rb')}
data = {
    'deviceName': 'Building1_PLC',
    'parserType': 'ROCKWELL_L5K'
}

response = requests.post(url, auth=auth, files=files, data=data)
print(response.json())
```

**Java (HttpClient)**:
```java
HttpClient client = HttpClient.newHttpClient();

String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
String auth = Base64.getEncoder().encodeToString("admin:password".getBytes());

HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("http://localhost:8088/data/logixemulator/upload"))
    .header("Authorization", "Basic " + auth)
    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
    .POST(HttpRequest.BodyPublishers.ofFile(Paths.get("/path/to/plc.L5K")))
    .build();

HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
System.out.println(response.body());
```

#### Response

**Success (200 OK)**:
```json
{
  "success": true,
  "message": "File uploaded and applied successfully",
  "filename": "plc.L5K",
  "fileSize": 123456,
  "deviceName": "Building1_PLC",
  "parserType": "ROCKWELL_L5K",
  "tagsCreated": 42,
  "parseTime": 1234,
  "timestamp": "2025-11-22T10:30:00Z"
}
```

**Error (400 Bad Request)** - Invalid file:
```json
{
  "success": false,
  "error": "File size exceeds maximum allowed size (10MB)",
  "filename": "huge.L5K",
  "fileSize": 15728640,
  "maxSize": 10485760
}
```

**Error (400 Bad Request)** - Path traversal:
```json
{
  "success": false,
  "error": "Invalid filename: path traversal detected",
  "filename": "../../../etc/passwd"
}
```

**Error (401 Unauthorized)**:
```json
{
  "error": "Authentication required"
}
```

**Error (413 Payload Too Large)**:
```json
{
  "error": "File size exceeds maximum allowed size (10MB)",
  "providedSize": 15728640,
  "maxSize": 10485760
}
```

**Error (422 Unprocessable Entity)** - Parse error:
```json
{
  "success": false,
  "error": "Failed to parse L5K file",
  "details": "Missing CONTROLLER declaration at line 1",
  "filename": "malformed.L5K"
}
```

#### Security

- **Authentication**: Required (Designer or Admin role)
- **File Size Limit**: 10 MB (10,485,760 bytes)
- **Path Traversal Protection**: Filenames and device names sanitized
- **XXE Protection**: XML parsing with 6 security features enabled
- **Content-Length Validation**: Required header, validated before reading body
- **Allowed Extensions**: `.L5K`, `.L5X`, `.JSON`, `.CSV` only

---

## Device Management API

### GET /data/logixemulator/devices

List all Logix PLC Emulator devices.

#### Request

**Method**: `GET`

**URL**: `/data/logixemulator/devices`

**Headers**:
```
Authorization: Basic <base64-credentials>
```

#### Examples

**cURL**:
```bash
curl -u admin:password http://localhost:8088/data/logixemulator/devices
```

**Python**:
```python
import requests

url = 'http://localhost:8088/data/logixemulator/devices'
auth = ('admin', 'password')

response = requests.get(url, auth=auth)
print(response.json())
```

#### Response

**Success (200 OK)**:
```json
{
  "devices": [
    {
      "name": "Building1_PLC",
      "status": "Running",
      "tagCount": 42,
      "parserType": "ROCKWELL_L5K",
      "lastFileUpload": "2025-11-22T10:30:00Z",
      "fileName": "plc.L5K"
    },
    {
      "name": "TestDevice",
      "status": "Ready - Waiting for file upload",
      "tagCount": 0,
      "parserType": "JSON",
      "lastFileUpload": null,
      "fileName": null
    }
  ],
  "totalDevices": 2
}
```

### GET /data/logixemulator/devices/{name}

Get details for a specific device.

#### Request

**Method**: `GET`

**URL**: `/data/logixemulator/devices/{name}`

**Path Parameters**:
- `name` - Device name (URL-encoded if contains special characters)

#### Examples

**cURL**:
```bash
curl -u admin:password http://localhost:8088/data/logixemulator/devices/Building1_PLC
```

#### Response

**Success (200 OK)**:
```json
{
  "name": "Building1_PLC",
  "status": "Running",
  "tagCount": 42,
  "parserType": "ROCKWELL_L5K",
  "fileName": "plc.L5K",
  "fileSize": 123456,
  "lastFileUpload": "2025-11-22T10:30:00Z",
  "lastParseTime": 1234,
  "tags": [
    {
      "name": "Motor1_Speed",
      "dataType": "REAL",
      "scope": "Controller:Global",
      "opcPath": "[ns=1;s=Building1_PLC]/Controller:Global/Motor1_Speed"
    },
    {
      "name": "Motor1_Running",
      "dataType": "BOOL",
      "scope": "Controller:Global",
      "opcPath": "[ns=1;s=Building1_PLC]/Controller:Global/Motor1_Running"
    }
  ]
}
```

**Error (404 Not Found)**:
```json
{
  "error": "Device not found",
  "deviceName": "NonExistentDevice"
}
```

### DELETE /data/logixemulator/devices/{name}

Delete a device and all its tags.

#### Request

**Method**: `DELETE`

**URL**: `/data/logixemulator/devices/{name}`

**Headers**:
```
Authorization: Basic <base64-credentials>
```

#### Examples

**cURL**:
```bash
curl -X DELETE -u admin:password \
  http://localhost:8088/data/logixemulator/devices/TestDevice
```

**Python**:
```python
import requests

url = 'http://localhost:8088/data/logixemulator/devices/TestDevice'
auth = ('admin', 'password')

response = requests.delete(url, auth=auth)
print(response.json())
```

#### Response

**Success (200 OK)**:
```json
{
  "success": true,
  "message": "Device deleted successfully",
  "deviceName": "TestDevice",
  "tagsRemoved": 42
}
```

**Error (404 Not Found)**:
```json
{
  "error": "Device not found",
  "deviceName": "NonExistentDevice"
}
```

### GET /data/logixemulator/status

Get module status and statistics.

#### Request

**Method**: `GET`

**URL**: `/data/logixemulator/status`

#### Examples

**cURL**:
```bash
curl -u admin:password http://localhost:8088/data/logixemulator/status
```

#### Response

**Success (200 OK)**:
```json
{
  "moduleVersion": "8.1.0",
  "moduleStatus": "Running",
  "totalDevices": 2,
  "runningDevices": 1,
  "waitingDevices": 1,
  "totalTags": 42,
  "supportedParsers": [
    "ROCKWELL_L5K",
    "ROCKWELL_L5X",
    "JSON",
    "CSV"
  ],
  "maxFileSize": 10485760,
  "securityFeatures": {
    "authenticationEnabled": true,
    "xxeProtectionEnabled": true,
    "pathTraversalProtectionEnabled": true,
    "fileSizeLimitEnabled": true
  }
}
```

---

## Web UI Routes

### GET /res/logixemulator/

Landing page for the module.

**URL**: `http://localhost:8088/res/logixemulator/`

**Authentication**: No (public resource)

**Description**: Displays module information, quick start guide, and links to documentation.

### GET /res/logixemulator/edit-program.html

Edit Program page with drag-and-drop file upload.

**URL**: `http://localhost:8088/res/logixemulator/edit-program.html`

**Authentication**: No (public resource, but uploads require auth)

**Description**: User-friendly interface for uploading PLC files with:
- Drag-and-drop file upload
- Step-by-step instructions
- Quick links to device configuration
- File format detection

**Example**:
```html
<!DOCTYPE html>
<html>
<head>
  <title>Edit Program - Logix PLC Emulator</title>
</head>
<body>
  <h1>Upload PLC File</h1>
  <div id="drop-zone">
    Drag and drop your L5K, L5X, JSON, or CSV file here
  </div>
  <script src="/res/logixemulator/plc-file-upload.js"></script>
</body>
</html>
```

### GET /data/logixemulator/connection-browser

Connection Browser - unified tag browsing and file upload page.

**URL**: `http://localhost:8088/data/logixemulator/connection-browser`

**Authentication**: Yes (requires Gateway login)

**Description**: Combined interface for browsing device tags and managing PLC files:
- Device selector with tag tree/flat view
- Drag-and-drop file upload zone
- Live tag values with write support
- Simulation controls
- File status and management

---

## Request/Response Formats

### File Upload Request Format

**Multipart Form Data**:
```http
POST /data/logixemulator/upload HTTP/1.1
Host: localhost:8088
Authorization: Basic YWRtaW46cGFzc3dvcmQ=
Content-Type: multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW
Content-Length: 123456

------WebKitFormBoundary7MA4YWxkTrZu0gW
Content-Disposition: form-data; name="file"; filename="plc.L5K"
Content-Type: application/octet-stream

[binary file content]
------WebKitFormBoundary7MA4YWxkTrZu0gW
Content-Disposition: form-data; name="deviceName"

Building1_PLC
------WebKitFormBoundary7MA4YWxkTrZu0gW
Content-Disposition: form-data; name="parserType"

ROCKWELL_L5K
------WebKitFormBoundary7MA4YWxkTrZu0gW--
```

### Success Response Format

**Structure**:
```json
{
  "success": true,
  "message": "Human-readable success message",
  "data": {
    "key": "value"
  },
  "timestamp": "ISO 8601 timestamp"
}
```

**Example**:
```json
{
  "success": true,
  "message": "File uploaded successfully",
  "data": {
    "filename": "plc.L5K",
    "tagsCreated": 42,
    "parseTime": 1234
  },
  "timestamp": "2025-11-22T10:30:00Z"
}
```

### Error Response Format

**Structure**:
```json
{
  "success": false,
  "error": "Human-readable error message",
  "errorCode": "ERROR_CODE",
  "details": "Additional error details",
  "timestamp": "ISO 8601 timestamp"
}
```

**Example**:
```json
{
  "success": false,
  "error": "File size exceeds maximum allowed size (10MB)",
  "errorCode": "FILE_TOO_LARGE",
  "details": "Provided size: 15728640 bytes, Maximum: 10485760 bytes",
  "timestamp": "2025-11-22T10:30:00Z"
}
```

---

## Error Codes

### HTTP Status Codes

| Code | Name | Description | Retry? |
|------|------|-------------|--------|
| 200 | OK | Request succeeded | - |
| 400 | Bad Request | Invalid request (malformed file, path traversal, etc.) | No |
| 401 | Unauthorized | Authentication required or invalid credentials | Yes (with valid credentials) |
| 403 | Forbidden | Valid credentials but insufficient permissions | No |
| 404 | Not Found | Device not found | No |
| 411 | Length Required | Content-Length header missing | No |
| 413 | Payload Too Large | File exceeds 10MB limit | No |
| 422 | Unprocessable Entity | File parsing failed | No |
| 500 | Internal Server Error | Server error (check logs) | Yes (after investigation) |
| 503 | Service Unavailable | Gateway starting or shutting down | Yes (after delay) |

### Application Error Codes

| Error Code | HTTP Status | Description |
|------------|-------------|-------------|
| `AUTH_REQUIRED` | 401 | Authentication credentials not provided |
| `AUTH_INVALID` | 401 | Invalid username or password |
| `INSUFFICIENT_PERMISSIONS` | 403 | User lacks required role |
| `DEVICE_NOT_FOUND` | 404 | Specified device does not exist |
| `FILE_TOO_LARGE` | 413 | File exceeds 10MB limit |
| `FILE_EMPTY` | 400 | Uploaded file is empty (0 bytes) |
| `INVALID_FILE_TYPE` | 400 | File extension not supported |
| `PATH_TRAVERSAL` | 400 | Path traversal attempt detected |
| `INVALID_DEVICE_NAME` | 400 | Device name contains invalid characters |
| `PARSE_ERROR` | 422 | File parsing failed |
| `XXE_DETECTED` | 400 | XXE attack attempt detected |
| `MISSING_CONTENT_LENGTH` | 411 | Content-Length header required |

---

## Rate Limiting

### Current Implementation

**Status**: Not implemented in v8.1.0

**Planned for future release**:
- 100 uploads per hour per user
- 1000 uploads per hour per IP address
- Configurable limits in Gateway Config

### Rate Limit Headers (Planned)

**Response Headers**:
```http
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 95
X-RateLimit-Reset: 1637582400
```

**Rate Limit Exceeded (429 Too Many Requests)**:
```json
{
  "error": "Rate limit exceeded",
  "limit": 100,
  "remaining": 0,
  "resetTime": "2025-11-22T11:00:00Z"
}
```

---

## Code Examples

### Complete Upload Workflow (Python)

```python
import requests
import json
from pathlib import Path

class PLCSimulatorClient:
    def __init__(self, base_url, username, password):
        self.base_url = base_url
        self.auth = (username, password)

    def upload_file(self, file_path, device_name, parser_type='ROCKWELL_L5K'):
        """Upload PLC file to device"""
        url = f'{self.base_url}/data/logixemulator/upload'

        files = {'file': open(file_path, 'rb')}
        data = {
            'deviceName': device_name,
            'parserType': parser_type
        }

        response = requests.post(url, auth=self.auth, files=files, data=data)
        return response.json()

    def get_devices(self):
        """List all devices"""
        url = f'{self.base_url}/data/logixemulator/devices'
        response = requests.get(url, auth=self.auth)
        return response.json()

    def get_device(self, device_name):
        """Get device details"""
        url = f'{self.base_url}/data/logixemulator/devices/{device_name}'
        response = requests.get(url, auth=self.auth)
        return response.json()

    def delete_device(self, device_name):
        """Delete device"""
        url = f'{self.base_url}/data/logixemulator/devices/{device_name}'
        response = requests.delete(url, auth=self.auth)
        return response.json()

    def get_status(self):
        """Get module status"""
        url = f'{self.base_url}/data/logixemulator/status'
        response = requests.get(url, auth=self.auth)
        return response.json()

# Usage
client = PLCSimulatorClient('http://localhost:8088', 'admin', 'password')

# Upload file
result = client.upload_file('/path/to/plc.L5K', 'Building1_PLC')
print(f"Uploaded: {result['tagsCreated']} tags created")

# List devices
devices = client.get_devices()
print(f"Total devices: {devices['totalDevices']}")

# Get device details
device = client.get_device('Building1_PLC')
print(f"Device status: {device['status']}")

# Get module status
status = client.get_status()
print(f"Module version: {status['moduleVersion']}")
```

### Upload with Error Handling (JavaScript)

```javascript
class PLCSimulatorAPI {
  constructor(baseURL, username, password) {
    this.baseURL = baseURL;
    this.auth = btoa(`${username}:${password}`);
  }

  async uploadFile(file, deviceName, parserType = 'ROCKWELL_L5K') {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('deviceName', deviceName);
    formData.append('parserType', parserType);

    try {
      const response = await fetch(`${this.baseURL}/data/logixemulator/upload`, {
        method: 'POST',
        headers: {
          'Authorization': `Basic ${this.auth}`
        },
        body: formData
      });

      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.error || `HTTP ${response.status}`);
      }

      return data;
    } catch (error) {
      console.error('Upload failed:', error);
      throw error;
    }
  }

  async getDevices() {
    const response = await fetch(`${this.baseURL}/data/logixemulator/devices`, {
      headers: {
        'Authorization': `Basic ${this.auth}`
      }
    });

    return response.json();
  }
}

// Usage
const api = new PLCSimulatorAPI('http://localhost:8088', 'admin', 'password');

// Upload file with error handling
const fileInput = document.getElementById('fileInput');
fileInput.addEventListener('change', async (event) => {
  const file = event.target.files[0];

  try {
    const result = await api.uploadFile(file, 'Building1_PLC');
    console.log(`Success! Created ${result.tagsCreated} tags`);
  } catch (error) {
    if (error.message.includes('401')) {
      alert('Authentication failed. Please check your credentials.');
    } else if (error.message.includes('413')) {
      alert('File too large. Maximum size is 10MB.');
    } else {
      alert(`Upload failed: ${error.message}`);
    }
  }
});
```

### Batch Upload Multiple Files (Bash)

```bash
#!/bin/bash
# Upload multiple PLC files to different devices

BASE_URL="http://localhost:8088"
USERNAME="admin"
PASSWORD="password"

# Array of files and device names
declare -A files
files["/data/plc1.L5K"]="Building1_PLC"
files["/data/plc2.L5K"]="Building2_PLC"
files["/data/plc3.L5K"]="Building3_PLC"

# Upload each file
for file in "${!files[@]}"; do
  device="${files[$file]}"

  echo "Uploading $file to $device..."

  response=$(curl -s -w "\n%{http_code}" -u "$USERNAME:$PASSWORD" \
    -F "file=@$file" \
    -F "deviceName=$device" \
    -F "parserType=ROCKWELL_L5K" \
    "$BASE_URL/data/logixemulator/upload")

  http_code=$(echo "$response" | tail -n1)
  body=$(echo "$response" | head -n-1)

  if [ "$http_code" -eq 200 ]; then
    tags=$(echo "$body" | jq -r '.tagsCreated')
    echo "✓ Success: $tags tags created for $device"
  else
    error=$(echo "$body" | jq -r '.error')
    echo "✗ Failed: $error (HTTP $http_code)"
  fi
done

echo "Upload complete!"
```

### Java Client Library

```java
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.Base64;
import com.google.gson.*;

public class PLCSimulatorClient {
    private final String baseURL;
    private final String authHeader;
    private final HttpClient httpClient;
    private final Gson gson;

    public PLCSimulatorClient(String baseURL, String username, String password) {
        this.baseURL = baseURL;
        String auth = username + ":" + password;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(auth.getBytes());
        this.httpClient = HttpClient.newHttpClient();
        this.gson = new Gson();
    }

    public UploadResult uploadFile(Path filePath, String deviceName, String parserType)
            throws IOException, InterruptedException {
        String boundary = "----Boundary" + System.currentTimeMillis();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseURL + "/data/logixemulator/upload"))
            .header("Authorization", authHeader)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(buildMultipartBody(filePath, deviceName, parserType, boundary))
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        return gson.fromJson(response.body(), UploadResult.class);
    }

    public DeviceList getDevices() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseURL + "/data/logixemulator/devices"))
            .header("Authorization", authHeader)
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        return gson.fromJson(response.body(), DeviceList.class);
    }

    private HttpRequest.BodyPublisher buildMultipartBody(Path filePath,
            String deviceName, String parserType, String boundary) throws IOException {
        // Implementation omitted for brevity
        // Build multipart form data body with file content and fields
    }

    // Data classes
    public static class UploadResult {
        public boolean success;
        public String message;
        public int tagsCreated;
    }

    public static class DeviceList {
        public Device[] devices;
        public int totalDevices;
    }

    public static class Device {
        public String name;
        public String status;
        public int tagCount;
    }
}

// Usage
PLCSimulatorClient client = new PLCSimulatorClient(
    "http://localhost:8088", "admin", "password");

UploadResult result = client.uploadFile(
    Paths.get("/data/plc.L5K"), "Building1_PLC", "ROCKWELL_L5K");

System.out.println("Tags created: " + result.tagsCreated);
```

---

## Versioning

**API Version**: v1

**Breaking Changes Policy**:
- Major version bump (v1 → v2) for breaking changes
- Minor version bump (v1.0 → v1.1) for new features
- Patch version bump (v1.0.0 → v1.0.1) for bug fixes

**Deprecation Notice**:
- Deprecated endpoints will be supported for 2 major versions
- Deprecation warnings included in response headers:
  ```
  X-API-Deprecated: true
  X-API-Sunset: 2026-01-01
  ```

---

## Additional Resources

- **QUICK_START.md** - User installation and usage guide
- **TESTING.md** - Testing procedures and validation
- **SECURITY_TESTING.md** - Security testing guide
- **ARCHITECTURE.md** - Technical architecture
- **TAG_CREATION_FLOW.md** - Tag creation process documentation
- **CHANGELOG.md** - Version history and changes

---

## Support

For API support:
- **Documentation**: See links above
- **Issues**: GitHub Issues (if available)
- **Forum**: Inductive Automation Exchange
- **Email**: support@yourcompany.com
