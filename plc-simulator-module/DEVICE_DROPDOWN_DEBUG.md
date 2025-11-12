# Device Dropdown Debugging Guide - v2.0.6

## The Problem

Device dropdown was empty in `/res/plcsimulator/simple-upload.html` even though routes were correctly mounted.

## Root Cause: Session Authentication

The issue was **NOT** with the URL paths - those were correct:
- Routes mounted at: `/main/data/plcsimulator/*` ✓
- HTML accessed at: `/res/plcsimulator/simple-upload.html` ✓

The ACTUAL problem: **Cross-context authentication**

### Why It Failed

1. **Static resources** (`/res/*`) are served without requiring authentication
2. **Dynamic routes** (`/main/data/*`) require Ignition Gateway session authentication
3. **Browser behavior**: Fetch calls from `/res/` pages don't automatically include session cookies for `/main/data/` routes
4. Result: 401/403 errors (often silent in browser)

## The Fix Applied

Added `credentials: 'include'` to all fetch calls to force cookie inclusion:

```javascript
// Before (line 548):
const response = await fetch('/main/data/plcsimulator/devices');

// After:
const response = await fetch('/main/data/plcsimulator/devices', {
    credentials: 'include'  // Include session cookies for authentication
});
```

This tells the browser: "Include authentication cookies with this request, even though the page is served from a different path."

## URL Path Architecture (For Reference)

Understanding Ignition SDK routing:

### Module Configuration

From `module.xml`:
- Module ID: `com.inductiveautomation.opcua.drivers.plcsimulator`

From `SimulatorModuleHook.java`:
- `getMountPathAlias()` returns `"plcsimulator"`

### How Ignition SDK Resolves Paths

According to Ignition SDK documentation:
> "getMountPathAlias() is used by the mounting underneath /res/module-id/* and /main/data/module-id/* as an alternate mounting path instead of your module id, if present."

Result:
- Without alias: `/main/data/com.inductiveautomation.opcua.drivers.plcsimulator/*`
- With alias: `/main/data/plcsimulator/*` ✓ (cleaner!)

### RouteGroup Mounting

When `mountRouteHandlers(RouteGroup routes)` is called:
- The `routes` parameter is already scoped to `/main/data/plcsimulator/`
- Calling `routes.newRoute("/devices")` creates `/main/data/plcsimulator/devices`

This is correct and was working - the issue was purely authentication.

## Testing the Fix

### 1. Test Health Endpoint (Public)

```bash
curl http://192.168.7.111:9088/main/data/plcsimulator/health
```

Expected:
```json
{"status":"ok","service":"plc-file-upload"}
```

### 2. Test Devices Endpoint (Requires Auth)

```bash
# This will fail (no session):
curl http://192.168.7.111:9088/main/data/plcsimulator/devices

# Get gateway session first, then test:
curl -c cookies.txt -X POST http://192.168.7.111:9088/system/login \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=admin&password=password"

curl -b cookies.txt http://192.168.7.111:9088/main/data/plcsimulator/devices
```

Expected (with devices):
```json
{
  "success": true,
  "devices": [
    {
      "name": "MyDevice",
      "status": "Connected",
      "enabled": true,
      "fileName": "program.L5K",
      "parserType": "Rockwell L5K",
      "simulationEnabled": false
    }
  ],
  "count": 1
}
```

Expected (no devices):
```json
{
  "success": true,
  "devices": [],
  "count": 0
}
```

### 3. Browser Console Test

Open the upload page, press F12, go to Console:

```javascript
// Test with credentials
fetch('/main/data/plcsimulator/devices', {credentials: 'include'})
    .then(r => r.json())
    .then(data => console.log('Devices:', data))
    .catch(err => console.error('Error:', err));
```

### 4. Network Tab Inspection

1. Open DevTools (F12) → Network tab
2. Refresh the upload page
3. Find the request to `/main/data/plcsimulator/devices`
4. Check:
   - Status: Should be **200 OK** (not 401/403)
   - Cookies: Should show session cookies being sent
   - Response: Should show device JSON

### 5. Verify Device Registration

Check gateway logs to ensure devices are actually being registered:

```bash
docker logs ignition-gateway 2>&1 | grep "Device registered:"
```

Expected output:
```
Device registered: MyDevice
```

If no output: Create a device in Gateway Config → OPC UA → Device Connections → Enhanced PLC Simulator

## Common Issues

### Issue: Still Getting Empty Dropdown

**Possible Causes:**

1. **No devices created**
   - Solution: Create a device in Gateway Config
   - Verify with: `docker logs ignition-gateway | grep "Device registered"`

2. **Browser cache**
   - Solution: Hard refresh (Ctrl+Shift+R) or clear cache

3. **Session expired**
   - Solution: Log into Gateway at `/web/home` first

4. **CORS/Security policy blocking**
   - Check browser console for CORS errors
   - Ensure browser allows credentials

### Issue: 401/403 Errors

**Symptoms:** Network tab shows 401 Unauthorized or 403 Forbidden

**Solutions:**

1. Ensure user is logged into Gateway
2. Check if `credentials: 'include'` was added to fetch call
3. Verify routes are using `RouteAccess.GRANTED` in FileUploadRoutes.java

### Issue: Module Not Loading

**Check:**
```bash
docker logs ignition-gateway | grep "File upload routes"
```

Should show:
```
✓ /upload route mounted (requires authentication)
✓ /devices route mounted (requires authentication)
✓ /device/{name}/status route mounted (requires authentication)
✓ /health route mounted (public)
File upload routes mounted successfully
```

## Alternative Solutions Considered

### Option 1: Make Routes Public (Not Recommended)

Change FileUploadRoutes.java to make all routes public:

```java
routes.newRoute("/devices")
    .handler(this::handleListDevices)
    .accessControl(req -> RouteAccess.GRANTED)  // Public access
    .mount();
```

**Pros:** Works without session
**Cons:** Security risk - anyone can access device list

### Option 2: Serve HTML from Gateway Context (Complex)

Create a route handler to serve HTML instead of static resource:

```java
routes.newRoute("/upload-page")
    .handler(this::serveUploadPage)
    .accessControl(this::checkAuthenticated)
    .mount();
```

**Pros:** Guaranteed session
**Cons:** More complex, requires template rendering

### Option 3: Use credentials: 'include' (Chosen)

Add credentials flag to fetch calls in HTML.

**Pros:**
- Simple one-line fix
- Follows best practices
- Maintains security

**Cons:**
- Requires browser to support credentials mode
- Must have valid session

## Verification Checklist

After deploying the fix:

- [ ] Health endpoint returns 200 OK
- [ ] Devices endpoint returns 200 OK (when logged in)
- [ ] Browser console shows no 401/403 errors
- [ ] Network tab shows cookies being sent
- [ ] Device dropdown populates on page load
- [ ] Devices appear in dropdown list
- [ ] Upload functionality works end-to-end

## Version History

- **v2.0.6**: Fixed device dropdown with `credentials: 'include'`
- **v2.0.5**: Initial release with device dropdown (non-functional)
- **v1.2.0**: File upload feature introduced

## See Also

- [FILE_UPLOAD_GUIDE.md](FILE_UPLOAD_GUIDE.md) - General file upload documentation
- [KNOWN_ISSUES.md](KNOWN_ISSUES.md) - Current limitations
- [FileUploadRoutes.java](gateway/src/main/java/com/inductiveautomation/plcsimulator/gateway/web/FileUploadRoutes.java) - Route implementation
