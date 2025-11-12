#!/bin/bash
# Device API Testing Script
# Tests the /main/data/plcsimulator/* endpoints

GATEWAY_URL="${GATEWAY_URL:-http://localhost:9088}"
USERNAME="${GATEWAY_USERNAME:-admin}"
PASSWORD="${GATEWAY_PASSWORD:-password}"

echo "================================================"
echo "Device API Testing Script"
echo "================================================"
echo "Gateway URL: $GATEWAY_URL"
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test 1: Health Check (Public)
echo "Test 1: Health Check (Public endpoint)"
echo "----------------------------------------"
echo "Testing: GET $GATEWAY_URL/main/data/plcsimulator/health"
HEALTH_RESPONSE=$(curl -s -w "\n%{http_code}" "$GATEWAY_URL/main/data/plcsimulator/health")
HTTP_CODE=$(echo "$HEALTH_RESPONSE" | tail -1)
BODY=$(echo "$HEALTH_RESPONSE" | head -n -1)

if [ "$HTTP_CODE" = "200" ]; then
    echo -e "${GREEN}✓ PASS${NC} - Status: $HTTP_CODE"
    echo "Response: $BODY"
else
    echo -e "${RED}✗ FAIL${NC} - Status: $HTTP_CODE"
    echo "Response: $BODY"
fi
echo ""

# Test 2: Devices Endpoint (Without Auth)
echo "Test 2: Devices List (No authentication)"
echo "----------------------------------------"
echo "Testing: GET $GATEWAY_URL/main/data/plcsimulator/devices"
DEVICES_RESPONSE=$(curl -s -w "\n%{http_code}" "$GATEWAY_URL/main/data/plcsimulator/devices")
HTTP_CODE=$(echo "$DEVICES_RESPONSE" | tail -1)
BODY=$(echo "$DEVICES_RESPONSE" | head -n -1)

if [ "$HTTP_CODE" = "200" ]; then
    echo -e "${GREEN}✓ PASS${NC} - Status: $HTTP_CODE (route is public)"
    echo "Response: $BODY"

    # Parse device count
    DEVICE_COUNT=$(echo "$BODY" | grep -o '"count":[0-9]*' | grep -o '[0-9]*')
    if [ -n "$DEVICE_COUNT" ]; then
        if [ "$DEVICE_COUNT" -eq 0 ]; then
            echo -e "${YELLOW}⚠ WARNING${NC} - No devices found (count: $DEVICE_COUNT)"
            echo "  → Create a device in Gateway Config → OPC UA → Device Connections"
        else
            echo -e "${GREEN}✓${NC} Found $DEVICE_COUNT device(s)"
        fi
    fi
elif [ "$HTTP_CODE" = "401" ] || [ "$HTTP_CODE" = "403" ]; then
    echo -e "${YELLOW}⚠ INFO${NC} - Status: $HTTP_CODE (requires authentication)"
    echo "  → This is expected if routes require auth"
    echo "  → Will test with authentication next..."
else
    echo -e "${RED}✗ FAIL${NC} - Status: $HTTP_CODE"
    echo "Response: $BODY"
fi
echo ""

# Test 3: Login and Get Session
echo "Test 3: Gateway Login"
echo "----------------------------------------"
COOKIE_FILE=$(mktemp)
echo "Logging in as: $USERNAME"

LOGIN_RESPONSE=$(curl -s -c "$COOKIE_FILE" -w "\n%{http_code}" \
    -X POST "$GATEWAY_URL/system/login" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "username=$USERNAME&password=$PASSWORD")

HTTP_CODE=$(echo "$LOGIN_RESPONSE" | tail -1)

if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "302" ]; then
    echo -e "${GREEN}✓ PASS${NC} - Login successful (Status: $HTTP_CODE)"
    echo "Session cookies saved to: $COOKIE_FILE"

    # Show cookies
    if [ -s "$COOKIE_FILE" ]; then
        echo "Cookies:"
        cat "$COOKIE_FILE" | grep -v '^#'
    fi
else
    echo -e "${RED}✗ FAIL${NC} - Login failed (Status: $HTTP_CODE)"
    echo "Response: $(echo "$LOGIN_RESPONSE" | head -n -1)"
fi
echo ""

# Test 4: Devices Endpoint (With Auth)
echo "Test 4: Devices List (With authentication)"
echo "----------------------------------------"
echo "Testing: GET $GATEWAY_URL/main/data/plcsimulator/devices"
DEVICES_AUTH_RESPONSE=$(curl -s -b "$COOKIE_FILE" -w "\n%{http_code}" \
    "$GATEWAY_URL/main/data/plcsimulator/devices")
HTTP_CODE=$(echo "$DEVICES_AUTH_RESPONSE" | tail -1)
BODY=$(echo "$DEVICES_AUTH_RESPONSE" | head -n -1)

if [ "$HTTP_CODE" = "200" ]; then
    echo -e "${GREEN}✓ PASS${NC} - Status: $HTTP_CODE"
    echo "Response: $BODY"

    # Parse and display device info
    DEVICE_COUNT=$(echo "$BODY" | grep -o '"count":[0-9]*' | grep -o '[0-9]*')
    if [ -n "$DEVICE_COUNT" ]; then
        if [ "$DEVICE_COUNT" -eq 0 ]; then
            echo -e "${YELLOW}⚠ WARNING${NC} - No devices found (count: $DEVICE_COUNT)"
            echo ""
            echo "To create a device:"
            echo "  1. Log into Gateway: $GATEWAY_URL/web/home"
            echo "  2. Go to: Config → OPC UA → Device Connections"
            echo "  3. Click 'Create new Device...'"
            echo "  4. Select 'Enhanced PLC Simulator'"
            echo "  5. Configure and save"
        else
            echo -e "${GREEN}✓${NC} Found $DEVICE_COUNT device(s):"

            # Pretty print device names (basic parsing)
            echo "$BODY" | grep -o '"name":"[^"]*"' | sed 's/"name":"//g' | sed 's/"//g' | while read -r device; do
                echo "  - $device"
            done
        fi
    fi
else
    echo -e "${RED}✗ FAIL${NC} - Status: $HTTP_CODE"
    echo "Response: $BODY"
fi
echo ""

# Test 5: Check Gateway Logs
echo "Test 5: Gateway Logs Verification"
echo "----------------------------------------"
echo "Checking for route mounting logs..."

if command -v docker &> /dev/null; then
    ROUTE_LOGS=$(docker logs ignition-gateway 2>&1 | grep -i "route mount\|/devices route\|File upload routes" | tail -10)

    if [ -n "$ROUTE_LOGS" ]; then
        echo -e "${GREEN}✓${NC} Found route mounting logs:"
        echo "$ROUTE_LOGS"
    else
        echo -e "${YELLOW}⚠${NC} No route mounting logs found"
        echo "  → Check if module is loaded: $GATEWAY_URL/web/config/system.modules"
    fi

    echo ""
    echo "Checking for device registration logs..."
    DEVICE_LOGS=$(docker logs ignition-gateway 2>&1 | grep "Device registered:" | tail -5)

    if [ -n "$DEVICE_LOGS" ]; then
        echo -e "${GREEN}✓${NC} Found device registration logs:"
        echo "$DEVICE_LOGS"
    else
        echo -e "${YELLOW}⚠${NC} No device registration logs found"
        echo "  → No Enhanced PLC Simulator devices have been created yet"
    fi
else
    echo -e "${YELLOW}⚠${NC} Docker not found - skipping log check"
    echo "  → Manually check Gateway logs for route mounting"
fi

echo ""
echo "================================================"
echo "Test Summary"
echo "================================================"

# Cleanup
rm -f "$COOKIE_FILE"

echo ""
echo "Next Steps:"
echo ""
echo "1. Ensure at least one Enhanced PLC Simulator device exists"
echo "2. Access the upload page:"
echo "   $GATEWAY_URL/res/plcsimulator/simple-upload.html"
echo ""
echo "3. Open browser DevTools (F12) and check:"
echo "   - Console: No 401/403 errors"
echo "   - Network: Cookies being sent with fetch requests"
echo "   - Network: Status 200 for /main/data/plcsimulator/devices"
echo ""
echo "4. Verify device dropdown populates with devices"
echo ""
