# Module Signing Configuration

This document describes the module signing configuration for the PLC Simulator module.

## Overview

The PLC Simulator module is signed with a **self-signed certificate** for development and testing purposes. This follows the same approach as the [ignition-module-python3-java](https://github.com/nigelgwork/ignition-module-python3-java) project.

## Signing Files

The following files are used for module signing:

| File | Purpose | Included in Git |
|------|---------|----------------|
| `keystore.jks` | Keystore containing the private key | ✅ Yes (dev only) |
| `certificate.der` | Public certificate in DER format | ✅ Yes |
| `gradle.properties` | Signing configuration | ✅ Yes |
| `sign.props` | Alternative signing config (optional) | ✅ Yes |

## Certificate Details

```
Owner: CN=PLC Simulator Module, OU=Development, O=Gaskony, L=Folsom, ST=CA, C=US
Issuer: CN=PLC Simulator Module, OU=Development, O=Gaskony, L=Folsom, ST=CA, C=US
Algorithm: RSA 2048-bit
Validity: 10 years (2025-2035)
Alias: plcsimulator
Password: ***REDACTED*** (PUBLIC - development only)
```

## Security Warning ⚠️

**THESE CERTIFICATES ARE FOR DEVELOPMENT/TESTING ONLY**

The keystore password (`***REDACTED***`) is **PUBLIC** and committed to the repository. This is intentional for:
- Reproducible builds across different environments
- CI/CD compatibility without secret management
- Immediate clone-and-build capability for developers

**DO NOT use these certificates for production deployments!**

For production:
1. Generate new certificates with private passwords
2. Store passwords in CI/CD secrets or environment variables
3. NEVER commit production credentials to version control
4. Consider submitting to Inductive Automation for official signing

## Configuration

### gradle.properties
```properties
# Signing configuration
ignition.signing.keystoreFile=keystore.jks
ignition.signing.keystorePassword=***REDACTED***
ignition.signing.certFile=certificate.der
ignition.signing.certAlias=plcsimulator
ignition.signing.certPassword=***REDACTED***
```

### build.gradle.kts
```kotlin
ignitionModule {
    // ... other config ...

    // Enable module signing
    skipModlSigning.set(false)
}
```

## Generating New Certificates

To regenerate certificates (e.g., for new organization or expiration):

```bash
# Remove old certificates
rm -f keystore.jks certificate.der

# Generate new keystore with self-signed certificate
keytool -genkeypair \
    -alias plcsimulator \
    -keyalg RSA \
    -keysize 2048 \
    -validity 3650 \
    -keystore keystore.jks \
    -storepass ***REDACTED*** \
    -keypass ***REDACTED*** \
    -dname "CN=PLC Simulator Module, OU=Development, O=Gaskony, L=Folsom, ST=CA, C=US" \
    -ext "SAN=DNS:localhost,IP:127.0.0.1"

# Export certificate in DER format
keytool -exportcert \
    -alias plcsimulator \
    -keystore keystore.jks \
    -storepass ***REDACTED*** \
    -file certificate.der \
    -rfc

# Verify certificate
keytool -printcert -file certificate.der
```

Or use the provided script:
```bash
./generate-signing-certs.sh
```

## Build Process

When you run `./gradlew clean build`, the following happens:

1. Code is compiled and packaged into JARs
2. JARs and resources are assembled into a .modl file
3. Module is signed using the keystore and certificate
4. Two module files are created:
   - `PLCSimulator-1.0.0.modl` - **Signed** (use this)
   - `PLCSimulator-1.0.0.unsigned.modl` - Unsigned backup

The signed module includes a `signatures.properties` file inside the .modl archive.

## Verification

To verify a module is signed:

```bash
# Check for signatures.properties file
unzip -l build/PLCSimulator-1.0.0.modl | grep signatures.properties

# Expected output:
#   1852  2025-11-06 11:47   signatures.properties
```

## Installation

Ignition Gateway will accept the self-signed certificate without additional configuration:

1. Upload `PLCSimulator-1.0.0.modl` (signed version) to Gateway
2. Install normally - no special settings needed
3. Gateway accepts the Gaskony development certificate

## Production Deployment

For production use, you have two options:

### Option 1: Use Your Own Certificate
1. Generate production certificates with private passwords:
   ```bash
   keytool -genkeypair -alias production -keyalg RSA -keysize 4096 \
       -validity 3650 -keystore production.jks \
       -storepass <SECURE_PASSWORD> -keypass <SECURE_PASSWORD> \
       -dname "CN=Your Company, OU=Production, O=Your Company, C=US"

   keytool -exportcert -alias production \
       -keystore production.jks -storepass <SECURE_PASSWORD> \
       -file production.der -rfc
   ```

2. Update `gradle.properties` (or use environment variables in CI/CD):
   ```properties
   ignition.signing.keystoreFile=production.jks
   ignition.signing.keystorePassword=${KEYSTORE_PASSWORD}
   ignition.signing.certFile=production.der
   ignition.signing.certAlias=production
   ignition.signing.certPassword=${CERT_PASSWORD}
   ```

3. Build: `./gradlew clean build`

4. **NEVER commit production keystore or passwords to git**

### Option 2: Internal Distribution
1. Build with dev certificate for testing
2. For production, use your own production certificate
3. Distribute the signed module internally
4. Users may need to trust your certificate in Ignition Gateway settings

## CI/CD Integration

For GitHub Actions or other CI/CD:

```yaml
- name: Build Signed Module
  env:
    KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
    CERT_PASSWORD: ${{ secrets.CERT_PASSWORD }}
  run: |
    echo "ignition.signing.keystorePassword=$KEYSTORE_PASSWORD" >> gradle.properties
    echo "ignition.signing.certPassword=$CERT_PASSWORD" >> gradle.properties
    ./gradlew clean build
```

Store production passwords as repository secrets, not in code.

## Comparison with Reference Project

This configuration mirrors the [ignition-module-python3-java](https://github.com/nigelgwork/ignition-module-python3-java) project:

| Aspect | Python3 Module | PLC Simulator |
|--------|---------------|---------------|
| Keystore file | `keystore.jks` | `keystore.jks` |
| Certificate file | `certificate.der` | `certificate.der` |
| Alias | `gaskony` | `plcsimulator` |
| Password | `***REDACTED***` | `***REDACTED***` |
| Organization | Gaskony | Gaskony |
| Signing enabled | ✅ Yes | ✅ Yes |
| Config file | `gradle.properties` | `gradle.properties` |
| Public passwords | ✅ Yes (dev only) | ✅ Yes (dev only) |

## References

- [Ignition Module SDK Documentation](https://github.com/inductiveautomation/ignition-sdk-examples)
- [Java Keytool Documentation](https://docs.oracle.com/en/java/javase/17/docs/specs/man/keytool.html)
- [Reference Project: ignition-module-python3-java](https://github.com/nigelgwork/ignition-module-python3-java)

## FAQ

### Why are the passwords committed to git?
This is a **development-only** configuration. The passwords are public to enable:
- Easy onboarding for new developers
- Reproducible builds across environments
- CI/CD without complex secret management

Production deployments should use private certificates stored securely.

### Will Ignition Gateway accept self-signed certificates?
Yes. Ignition Gateway accepts self-signed module certificates without additional configuration. However, best practice for production is to use officially-signed modules.

### Can I use the unsigned module instead?
Yes, but you may need to enable "Allow unsigned modules" in Gateway Config > System > Security. The signed module is recommended and works without any Gateway configuration changes.

### How long are the certificates valid?
10 years (2025-2035). You can regenerate with different validity using the `-validity` parameter to keytool.

### What if my certificate expires?
Simply regenerate using the commands above or run `./generate-signing-certs.sh`. Then rebuild the module.
