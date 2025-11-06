#!/bin/bash
#
# Generate self-signed certificates for PLC Simulator module signing
# This creates a keystore and certificate for development/testing purposes
#
# Usage: ./generate-signing-certs.sh
#
# Output files:
#   - keystore.jks: Keystore containing private key
#   - certificate.der: Public certificate
#

set -e  # Exit on error

# Configuration
KEYSTORE_FILE="keystore.jks"
CERT_FILE="certificate.der"
ALIAS="plcsimulator"
PASSWORD="REDACTED-DEV-PASSWORD"
VALIDITY_DAYS=3650  # 10 years

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}==================================${NC}"
echo -e "${GREEN}PLC Simulator Certificate Generator${NC}"
echo -e "${GREEN}==================================${NC}"
echo ""

# Check if files already exist
if [ -f "$KEYSTORE_FILE" ] || [ -f "$CERT_FILE" ]; then
    echo -e "${YELLOW}Warning: Certificate files already exist${NC}"
    echo -e "  - $KEYSTORE_FILE"
    echo -e "  - $CERT_FILE"
    echo ""
    read -p "Do you want to regenerate them? This will overwrite existing files. (y/n): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo -e "${YELLOW}Cancelled. Using existing certificates.${NC}"
        exit 0
    fi

    echo ""
    echo -e "${YELLOW}Removing existing certificate files...${NC}"
    rm -f "$KEYSTORE_FILE" "$CERT_FILE"
fi

# Check for keytool
if ! command -v keytool &> /dev/null; then
    echo -e "${RED}Error: keytool not found${NC}"
    echo "keytool is part of the Java JDK. Please install Java JDK 11 or higher."
    exit 1
fi

echo -e "${GREEN}Step 1: Generating keystore and self-signed certificate...${NC}"
echo ""

# Generate keystore with self-signed certificate
keytool -genkeypair \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity $VALIDITY_DAYS \
    -keystore "$KEYSTORE_FILE" \
    -storepass "$PASSWORD" \
    -keypass "$PASSWORD" \
    -dname "CN=PLC Simulator Module, OU=Development, O=Inductive Automation, L=Folsom, ST=CA, C=US" \
    -ext "SAN=DNS:localhost,IP:127.0.0.1"

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Keystore created successfully: $KEYSTORE_FILE${NC}"
else
    echo -e "${RED}✗ Failed to create keystore${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}Step 2: Exporting certificate...${NC}"
echo ""

# Export certificate in DER format
keytool -exportcert \
    -alias "$ALIAS" \
    -keystore "$KEYSTORE_FILE" \
    -storepass "$PASSWORD" \
    -file "$CERT_FILE" \
    -rfc

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Certificate exported successfully: $CERT_FILE${NC}"
else
    echo -e "${RED}✗ Failed to export certificate${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}Step 3: Verifying certificate...${NC}"
echo ""

# Display certificate information
keytool -printcert \
    -file "$CERT_FILE" \
    -v | head -20

echo ""
echo -e "${GREEN}==================================${NC}"
echo -e "${GREEN}Certificate Generation Complete!${NC}"
echo -e "${GREEN}==================================${NC}"
echo ""
echo -e "Generated files:"
echo -e "  ${GREEN}✓${NC} $KEYSTORE_FILE (private key)"
echo -e "  ${GREEN}✓${NC} $CERT_FILE (public certificate)"
echo ""
echo -e "Configuration details:"
echo -e "  Alias:        $ALIAS"
echo -e "  Password:     $PASSWORD"
echo -e "  Validity:     $VALIDITY_DAYS days (~10 years)"
echo -e "  Algorithm:    RSA 2048-bit"
echo ""
echo -e "${YELLOW}⚠️  SECURITY WARNING ⚠️${NC}"
echo -e "These certificates are for ${YELLOW}DEVELOPMENT/TESTING ONLY${NC}"
echo -e ""
echo -e "For production use:"
echo -e "  1. Generate certificates with private passwords"
echo -e "  2. Store passwords in CI/CD secrets or environment variables"
echo -e "  3. NEVER commit production credentials to version control"
echo ""
echo -e "${GREEN}Next steps:${NC}"
echo -e "  1. Update gradle.properties with signing configuration"
echo -e "  2. Set skipModlSigning.set(false) in build.gradle.kts"
echo -e "  3. Run ./gradlew clean build to create signed module"
echo ""
echo -e "${GREEN}Done!${NC}"
