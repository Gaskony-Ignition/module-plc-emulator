# API Reference - Logix PLC Emulator

**Version**: 9.2.14
**Last Updated**: 2026-07-10 (auth/routes/limits corrected — defect B7; file version list/revert
routes added — defect B5; v10.0.0 fidelity plan)
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
| **Module API** | `/data/logixemulator/*` | Yes (Gateway session) |
| **Web UI** | `/res/logixemulator/*` | No (public resources) |
| **Platform device config** | `/data/api/v1/resources/com.inductiveautomation.opcua/device` | Yes (Gateway session + `x-csrf-token`) |

### Supported Content Types

- `application/json`
- `text/plain` (the `/upload` endpoint takes the raw file content as the request body — see [File Upload API](#file-upload-api))

---

## Authentication

> **HTTP Basic Authentication does NOT work against this module.** This section previously
> documented `Authorization: Basic ...` as the recommended method; that was never correct.
> `GatewayAuthHelper.isGatewayAuthenticated()` — the only authentication check the module's
> endpoints perform — looks solely at the Gateway HTTP session (`user` session attribute set by
> the Gateway login page) and the request actor. There is no code path that inspects an
> `Authorization` header. Verified against a live 8.3 gateway during the v10.0.0 DoD run
> (`plc-dod/device-creation-blocker.txt`, `plc-dod/csrf-finding.txt`).

### Real authentication flow: session cookie via Gateway login

All `/data/logixemulator/*` endpoints require an authenticated Gateway session:

1. Log in through the Gateway's normal login flow (the web UI, or its underlying `POST` to the
   Gateway's login endpoint) and retain the session cookie the Gateway sets.
2. Send that cookie with every subsequent request to `/data/logixemulator/*`.

**Example (cURL)**:
```bash
# Log in and persist the session cookie
curl -c cookies.txt -d "username=admin&password=password" \
  http://localhost:8088/system/login

# Reuse the cookie for module API calls
curl -b cookies.txt -H "X-Requested-With: XMLHttpRequest" \
  --data-binary @tags.json -H "X-Filename: tags.json" \
  "http://localhost:8088/data/logixemulator/upload?device=MyDevice"
```

### Module endpoints: session + `X-Requested-With` only

State-changing module endpoints (`/upload`, `/device/:name/delete`, tag-write and simulation
routes) additionally require the module's own lightweight CSRF check —
`GatewayAuthHelper.requireCSRFToken()` — which only checks for the header:

```
X-Requested-With: XMLHttpRequest
```

Module endpoints do **not** need the platform's `x-csrf-token` header — that header only applies
to the separate platform config-resource API described below.

### Platform device-creation calls additionally need `x-csrf-token`

Creating the device itself (Config → OPC UA → Device Connections, or scripting the same call) goes
through Ignition 8.3's **generic platform config-resource API**
(`POST /data/api/v1/resources/com.inductiveautomation.opcua/device`), which is not part of this
module and enforces the platform's own CSRF scheme: every state-changing request must carry an
`x-csrf-token` header whose value is obtained from the authenticated session (a real browser's SPA
reads it at load time; a scripted client must fetch it separately). Omitting it returns a bare
Jetty `403 Forbidden` with no JSON body and no application-level log line — this is what made the
device-creation blocker in the v10.0.0 DoD run hard to diagnose (`plc-dod/csrf-finding.txt`).
Driving the real Gateway UI (e.g. with Playwright) sidesteps this token entirely and is the more
robust path for scripted device creation.

### Authentication Errors

**401 Unauthorized** - No authenticated Gateway session
```json
{
  "success": false,
  "error": "Authentication required"
}
```

**403 Forbidden** - Authenticated, but the module's `X-Requested-With` CSRF check failed
```json
{
  "success": false,
  "error": "CSRF validation failed — X-Requested-With header required"
}
```

---

## REST API Endpoints

### Endpoint Summary

This is the real, non-phantom route list mounted by `FileUploadRoutes`/`Routes.java` (20 routes
total, all under `/data/logixemulator/`). This document covers upload, device listing, per-device
status, delete, file versions, and system stats; the remaining tag/simulation/log/auth-check
routes are not documented here yet.

| Method | Endpoint | Description | Auth Required |
|--------|----------|-------------|---------------|
| `POST` | `/data/logixemulator/upload` | Upload a PLC file and apply it to an **existing** device (see prerequisite below) | Yes |
| `GET` | `/data/logixemulator/devices` | List all registered devices | Yes |
| `GET` | `/data/logixemulator/device/:name/status` | Get status/file info for one device | Yes |
| `DELETE` | `/data/logixemulator/device/:name/delete` | Delete a device's uploaded file | Yes |
| `GET` | `/data/logixemulator/device/:name/versions` | List retained file versions for a device (defect B5) | Yes |
| `POST` | `/data/logixemulator/device/:name/versions/revert` | Revert a device's file to a retained version (defect B5) | Yes |
| `GET` | `/data/logixemulator/system/stats` | Get module/system stats (CPU, RAM, device count) | Yes |

> **Phantom routes corrected**: earlier revisions of this document described `GET`/`DELETE
> /data/logixemulator/devices/{name}` and `GET /data/logixemulator/status` — neither exists.
> `Routes.java` defines `DEVICE_STATUS = "/device/:name/status"`, `DEVICE_DELETE =
> "/device/:name/delete"`, and `SYSTEM_STATS = "/system/stats"`.

---

## File Upload API

### POST /data/logixemulator/upload

Upload a PLC file and apply it to a device.

> **Prerequisite: the device must already exist.** `/upload` does **not** create a device. It looks
> the target device up by name (`DeviceController.processDeviceUpload()` →
> `deviceManager.findDeviceByName()`) and returns `404 Device not found` with a hint if it doesn't
> exist yet. Create the device first via **Config → OPC UA → Device Connections** (device type
> "Logix PLC Emulator" / `com.gaskony.logixemulator.LogixEmulator`), *then* upload a file to it.
> A caller who has never created the device will get a 404 on every upload attempt, no matter how
> correct the request otherwise is (`plc-dod/device-creation-blocker.txt`).

#### Request

**Method**: `POST`

**URL**: `/data/logixemulator/upload?device=<deviceName>`

**Content-Type**: not multipart — the request body **is** the raw file content
(`GatewayAuthHelper.readRequestContent()` reads the body directly; there is no multipart form
parsing on this endpoint).

**Headers**:
```
Cookie: JSESSIONID=<session cookie from Gateway login>
X-Requested-With: XMLHttpRequest
X-Filename: tags.json
Content-Length: <byte length of the file>
```

**Query Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|--------------|
| `device` | String | No (see note) | Target device name. If omitted, the file is validated and echoed back but not applied to any device. |

**Headers (request)**:

| Header | Required | Description | Constraints |
|--------|----------|--------------|-------------|
| `X-Filename` | No | Original filename, used for format auto-detection (`.l5k`, `.l5x`, `.json`, `.csv`) | Sanitised via `PathSecurity.sanitizeFileName()`; defaults to `uploaded_file.txt` if absent |

The parser format is **not** selected by an explicit `parserType` field — `ParserFactory` picks
the parser automatically from the `X-Filename` extension.

#### Examples

Requires the device (`Building1_PLC` below) to already exist — see the prerequisite note above.

**cURL - Upload a JSON tag file to an existing device**:
```bash
curl -X POST "http://localhost:8088/data/logixemulator/upload?device=Building1_PLC" \
  -b cookies.txt \
  -H "X-Requested-With: XMLHttpRequest" \
  -H "X-Filename: tags.json" \
  --data-binary @/path/to/tags.json
```

**JavaScript (Browser, e.g. the module's own web UI)**:
```javascript
const fileContent = await file.text();

fetch(`/data/logixemulator/upload?device=${encodeURIComponent(deviceName)}`, {
  method: 'POST',
  headers: {
    'X-Requested-With': 'XMLHttpRequest',
    'X-Filename': file.name
  },
  credentials: 'same-origin', // send the existing Gateway session cookie
  body: fileContent
})
  .then(response => response.json())
  .then(data => console.log('Result:', data))
  .catch(error => console.error('Error:', error));
```

**Python (requests library, with a pre-authenticated session)**:
```python
import requests

session = requests.Session()
# ... perform the Gateway login flow with `session` first ...

url = 'http://localhost:8088/data/logixemulator/upload'
with open('/path/to/tags.json', 'rb') as f:
    content = f.read()

response = session.post(
    url,
    params={'device': 'Building1_PLC'},
    headers={'X-Requested-With': 'XMLHttpRequest', 'X-Filename': 'tags.json'},
    data=content
)
print(response.json())
```

#### Response

**Success (200 OK)** — file saved and the device reloaded/rebuilt without error:
```json
{
  "success": true,
  "filename": "tags.json",
  "size": 512,
  "device": "Building1_PLC",
  "message": "File uploaded and applied to device successfully",
  "status": "Running"
}
```

**Error (422 Unprocessable Entity)** — file saved, but the device failed to apply it (defect B4:
this used to incorrectly report `success: true`/HTTP 200 for this case):
```json
{
  "success": false,
  "filename": "tags.json",
  "size": 512,
  "device": "Building1_PLC",
  "error": "File saved but failed to apply to device: Error: Hot reload failed - ...",
  "status": "Error: Hot reload failed - ..."
}
```

**Error (404 Not Found)** — the device does not exist yet (the most common failure for a
first-time caller — see the prerequisite note above):
```json
{
  "success": false,
  "error": "Device not found: Building1_PLC",
  "hint": "Create the device in Config → OPC UA → Device Connections first"
}
```

**Error (400 Bad Request)** — content/format validation failed (`FileValidator`):
```json
{
  "success": false,
  "error": "Invalid L5X file - missing <RSLogix5000Content> root element. This is not a valid Studio 5000 XML export file."
}
```

**Error (401 Unauthorized)**:
```json
{
  "success": false,
  "error": "Authentication required"
}
```

**Error (413 Payload Too Large)** — over the 50MB limit:
```json
{
  "success": false,
  "error": "File too large: 60 MB exceeds max 50 MB"
}
```

**Error (429 Too Many Requests)** — upload rate limit exceeded (100/hr/user, 1000/hr/IP — see
[Rate Limiting](#rate-limiting)):
```json
{
  "success": false,
  "error": "Rate limit exceeded: user limit of 100 uploads per hour",
  "retryAfter": 3529
}
```

#### Security

- **Authentication**: Required (Gateway session; see [Authentication](#authentication))
- **File Size Limit**: 50 MB (`FileValidator.DEFAULT_MAX_SIZE_MB`; earlier revisions of this document said 10MB, which was never correct)
- **Path Traversal Protection**: Filenames and device names sanitized
- **XXE Protection**: XML parsing with 6 security features enabled
- **Content-Length Validation**: Required header, validated before reading body
- **Allowed Extensions**: `.L5K`, `.L5X`, `.JSON`, `.CSV` only

---

## Device Management API

### GET /data/logixemulator/devices

List all registered Logix PLC Emulator devices.

#### Request

**Method**: `GET`

**URL**: `/data/logixemulator/devices`

**Headers**:
```
Cookie: JSESSIONID=<session cookie from Gateway login>
```

#### Examples

**cURL**:
```bash
curl -b cookies.txt http://localhost:8088/data/logixemulator/devices
```

**Python (with a pre-authenticated session)**:
```python
url = 'http://localhost:8088/data/logixemulator/devices'
response = session.get(url)
print(response.json())
```

#### Response

**Success (200 OK)** — matches `DeviceController.handleListDevices()`:
```json
{
  "success": true,
  "devices": [
    {
      "name": "Building1_PLC",
      "status": "Running",
      "enabled": true,
      "fileName": "plc.L5K",
      "parserType": "Rockwell L5K",
      "simulationEnabled": false
    },
    {
      "name": "TestDevice",
      "status": "Ready - Waiting for file upload",
      "enabled": true,
      "fileName": null,
      "parserType": "JSON",
      "simulationEnabled": false
    }
  ],
  "count": 2
}
```

> **Phantom route corrected**: there is no `GET /data/logixemulator/devices/{name}` endpoint —
> per-device detail is `GET /device/:name/status` below.

### GET /data/logixemulator/device/:name/status

Get status and file info for a specific device.

#### Request

**Method**: `GET`

**URL**: `/data/logixemulator/device/:name/status` (e.g. `/device/Building1_PLC/status`)

**Path Parameters**:
- `name` - Device name (URL-encoded if it contains special characters)

#### Examples

**cURL**:
```bash
curl -b cookies.txt http://localhost:8088/data/logixemulator/device/Building1_PLC/status
```

#### Response

**Success (200 OK)** — matches `DeviceController.handleDeviceStatus()`:
```json
{
  "success": true,
  "deviceName": "Building1_PLC",
  "status": "Running",
  "fileName": "plc.L5K",
  "hasFile": true,
  "fileSize": 123456,
  "lastModified": 1732270200000,
  "filePath": "Building1_PLC_plc.L5K",
  "parserType": "Rockwell L5K",
  "enabled": true,
  "simulationEnabled": false
}
```

**Error (404 Not Found)**:
```json
{
  "success": false,
  "error": "Device not found: NonExistentDevice"
}
```

### DELETE /data/logixemulator/device/:name/delete

Delete a device's uploaded file (does not delete the OPC UA device connection itself — that is
managed through Config → OPC UA → Device Connections).

#### Request

**Method**: `DELETE`

**URL**: `/data/logixemulator/device/:name/delete` (e.g. `/device/TestDevice/delete`)

**Headers**:
```
Cookie: JSESSIONID=<session cookie from Gateway login>
X-Requested-With: XMLHttpRequest
```

#### Examples

**cURL**:
```bash
curl -X DELETE -b cookies.txt -H "X-Requested-With: XMLHttpRequest" \
  http://localhost:8088/data/logixemulator/device/TestDevice/delete
```

#### Response

**Success (200 OK)** — matches `DeviceController.handleDeleteFile()`:
```json
{
  "success": true,
  "message": "File deleted successfully",
  "fileName": "TestDevice_plc.L5K"
}
```

**Error (404 Not Found)**:
```json
{
  "success": false,
  "error": "Device not found: NonExistentDevice"
}
```

> **Phantom route corrected**: there is no `DELETE /data/logixemulator/devices/{name}` endpoint —
> the real route is `/device/:name/delete` (note singular `device`, and the `/delete` suffix).

### GET /data/logixemulator/device/:name/versions

List the file versions retained for a device (charter §2.7: "retains the last 5 uploads and can
revert"), newest first, flagging which entry is the device's current live file. Read endpoint —
subject to the **read** rate limiter (see [Rate Limiting](#rate-limiting)), not the write limiter.

> **Defect B5, fixed 10/07/2026**: this route did not exist at all before v10.0.0 —
> `FileVersionManager.saveVersion()` was never called on the REST upload path, so no versions were
> ever written, and `getVersions()`/`restoreVersion()` had zero callers anywhere in the codebase
> (`plc-dod/item7-versioning-FAIL.txt`). A successful `/upload` (see above) now calls `saveVersion`
> after the file is confirmed to have parsed and built; a failed upload does not consume a
> retention slot.

#### Request

**Method**: `GET`

**URL**: `/data/logixemulator/device/:name/versions` (e.g. `/device/DodPLC1/versions`)

**Headers**:
```
Cookie: JSESSIONID=<session cookie from Gateway login>
```

#### Examples

**cURL**:
```bash
curl -b cookies.txt http://localhost:8088/data/logixemulator/device/DodPLC1/versions
```

#### Response

**Success (200 OK)** — matches `VersionController.handleListVersions()`:
```json
{
  "success": true,
  "deviceName": "DodPLC1",
  "versions": [
    { "filename": "DodPLC1_ver.csv", "size": 1024, "timestamp": 1752100000000, "current": true },
    { "filename": "ver_20260710_120000.csv", "size": 998, "timestamp": 1752099000000, "current": false }
  ],
  "count": 2,
  "maxVersions": 5
}
```

**Error (404 Not Found)**:
```json
{
  "success": false,
  "error": "Device not found: NonExistentDevice"
}
```

### POST /data/logixemulator/device/:name/versions/revert

Restore a device's current file from one of its retained versions, then reload the device through
the **same code path a REST upload uses** (`DeviceFileManager.reloadDevice()`), so the reverted
content is actually re-parsed and re-applied — not just copied to disk. Write endpoint — subject
to CSRF (`X-Requested-With`) and the **write** rate limiter (see [Rate Limiting](#rate-limiting)).

A successful revert is itself treated as a new upload for versioning purposes: it becomes the
newest retained snapshot, so the version just reverted-from is not immediately pushed out of the
5-version retention window by its own restore.

#### Request

**Method**: `POST`

**URL**: `/data/logixemulator/device/:name/versions/revert` (e.g. `/device/DodPLC1/versions/revert`)

**Headers**:
```
Cookie: JSESSIONID=<session cookie from Gateway login>
Content-Type: application/json
X-Requested-With: XMLHttpRequest
```

**Body**:
```json
{ "filename": "ver_20260710_120000.csv" }
```

`filename` must be one of the `filename` values previously returned by
`GET /device/:name/versions` — it is matched against that same listing server-side (never used to
build a file path directly), so an arbitrary or path-traversal filename cannot be restored.

#### Examples

**cURL**:
```bash
curl -X POST -b cookies.txt -H "Content-Type: application/json" \
  -H "X-Requested-With: XMLHttpRequest" \
  -d '{"filename":"ver_20260710_120000.csv"}' \
  http://localhost:8088/data/logixemulator/device/DodPLC1/versions/revert
```

#### Response

**Success (200 OK)** — matches `VersionController.handleRevertVersion()`:
```json
{
  "success": true,
  "deviceName": "DodPLC1",
  "restoredFrom": "ver_20260710_120000.csv",
  "status": "Running"
}
```

**Error (404 Not Found)** — unknown version filename:
```json
{
  "success": false,
  "error": "Unknown version: does-not-exist.csv"
}
```

**Error (409 Conflict)** — device has never had a file uploaded (nothing to revert into):
```json
{
  "success": false,
  "error": "Device has no current file to revert - upload a file first"
}
```

**Error (422 Unprocessable Entity)** — same honesty convention as `/upload` (defect B4): the
version was restored to disk, but the reload that followed failed to parse/build it:
```json
{
  "success": false,
  "deviceName": "DodPLC1",
  "restoredFrom": "ver_20260710_120000.csv",
  "error": "Version restored to disk but failed to apply to device: Error: Hot reload failed - ...",
  "status": "Error: Hot reload failed - ..."
}
```

### GET /data/logixemulator/system/stats

Get module/system stats (CPU, RAM, registered device count).

#### Request

**Method**: `GET`

**URL**: `/data/logixemulator/system/stats`

#### Examples

**cURL**:
```bash
curl -b cookies.txt http://localhost:8088/data/logixemulator/system/stats
```

#### Response

**Success (200 OK)** — matches `SystemController.handleSystemStats()`:
```json
{
  "success": true,
  "cpuUsage": 4.2,
  "ramUsage": 536870912,
  "ramTotal": 2147483648,
  "moduleVersion": "9.2.14",
  "deviceCount": 2
}
```

> **Phantom route corrected**: there is no `GET /data/logixemulator/status` endpoint — the real
> route is `/system/stats`, and its response has no `totalTags`/`supportedParsers`/
> `securityFeatures`/`maxFileSize` fields; those were never implemented.

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

**Raw body, not multipart** — `DeviceController.handleFileUpload()` reads the request body
directly (`GatewayAuthHelper.readRequestContent()`); there is no multipart/form-data parsing on
this endpoint:

```http
POST /data/logixemulator/upload?device=Building1_PLC HTTP/1.1
Host: localhost:8088
Cookie: JSESSIONID=<session cookie>
X-Requested-With: XMLHttpRequest
X-Filename: plc.l5k
Content-Length: 512

[raw file content - the entire body]
```

### Success Response Format

Every endpoint returns a flat JSON object with a `success` boolean plus whatever fields are
relevant to that call — there is no shared `data`/`timestamp` envelope. See each endpoint's own
Response section above for its exact fields.

**Example** (`/upload`):
```json
{
  "success": true,
  "filename": "plc.L5K",
  "size": 512,
  "device": "Building1_PLC",
  "message": "File uploaded and applied to device successfully",
  "status": "Running"
}
```

### Error Response Format

Error responses are likewise a flat object: `success: false`, a human-readable `error` string, and
occasionally a `hint` (e.g. the "device not found" case). There is no `errorCode` or `timestamp`
field anywhere in the codebase — those were aspirational, never implemented.

**Example** (`/upload`, device not found):
```json
{
  "success": false,
  "error": "Device not found: Building1_PLC",
  "hint": "Create the device in Config → OPC UA → Device Connections first"
}
```

---

## Error Codes

### HTTP Status Codes

| Code | Name | Description | Retry? |
|------|------|-------------|--------|
| 200 | OK | Request succeeded | - |
| 400 | Bad Request | Invalid request (malformed/unsupported file content) | No |
| 401 | Unauthorized | No authenticated Gateway session | Yes (after logging in) |
| 403 | Forbidden | Authenticated, but the module's `X-Requested-With` CSRF check failed (or, for the separate platform config API, a missing `x-csrf-token`) | Yes (with the header) |
| 404 | Not Found | Device not found | No |
| 413 | Payload Too Large | File exceeds the 50MB limit | No |
| 422 | Unprocessable Entity | File saved but the device failed to apply it (defect B4) | No |
| 429 | Too Many Requests | Rate limit exceeded (see [Rate Limiting](#rate-limiting)) | Yes (after `Retry-After`) |
| 500 | Internal Server Error | Server error (check logs) | Yes (after investigation) |

> This module does not emit `411`/`503`, or a structured application `errorCode` — earlier
> revisions of this document described both; neither exists in the codebase.

---

## Rate Limiting

Rate limiting is implemented (`RateLimiter`, wired up per-endpoint in `FileUploadRoutes`) — three
independent limiters, each tracking per-user and per-IP counts over a rolling 1-hour window:

| Limiter | Applies to | Per-user limit | Per-IP limit |
|---------|-----------|-----------------|--------------|
| Upload | `/upload` | 100/hour | 1000/hour |
| Read | tag/status/list reads, `versions` list | 300/hour | 3000/hour |
| Write | tag writes, simulation toggles, delete, `versions/revert` | 60/hour | 600/hour |

> The module's `CLAUDE.md` previously said "file upload (60/hr)" — that is actually the **write**
> limiter's per-user figure; upload is 100/hour/user, confirmed both by the `RateLimiter`
> constructor calls in `FileUploadRoutes.java` and empirically (429 at request #93 in a hammer-loop
> that had already consumed ~7-8 units of quota — see `plc-dod/rate-limit-results.txt` and
> `item6-validation.txt`).

### Rate Limit Headers

**Response Headers** (set by `GatewayAuthHelper.rateLimitResponse()`):
```http
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1732273729000
Retry-After: 3529
```

**Rate Limit Exceeded (429 Too Many Requests)**:
```json
{
  "success": false,
  "error": "Rate limit exceeded: user limit of 100 uploads per hour",
  "retryAfter": 3529
}
```

---

## Code Examples

> **These four samples predate the B7 documentation corrections above and have not been rewritten
> line-by-line.** They still show `Authorization: Basic` auth, multipart `file`/`deviceName`
> upload fields, and `/devices/{name}` routes — none of which work against the real module (see
> [Authentication](#authentication), [File Upload API](#file-upload-api) and
> [Device Management API](#device-management-api) above for the corrected, verified behaviour).
> Treat the snippets below as illustrative of *shape* (a client class wrapping the API) rather than
> copy-pasteable working code.

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
