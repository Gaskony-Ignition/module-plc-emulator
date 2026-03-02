# Security Best Practices

## Module Signing Credentials

### ⚠️ CRITICAL SECURITY REQUIREMENT

**NEVER commit signing credentials to version control!**

### Development Setup

1. **Copy the template file:**
   ```bash
   cp gradle.properties.template gradle.properties
   ```

2. **Fill in your development credentials** in `gradle.properties`
   - The file is in `.gitignore` and will NOT be committed

3. **Or use environment variables** (recommended):
   ```bash
   export IGNITION_KEYSTORE_PASSWORD="your-dev-password"
   export IGNITION_CERT_PASSWORD="your-dev-password"
   ./gradlew build
   ```

### Production/CI/CD Setup

**ALWAYS use environment variables for production builds:**

```bash
# Set in CI/CD secrets (GitHub Actions, Jenkins, etc.)
export IGNITION_KEYSTORE_PASSWORD="${{ secrets.PROD_KEYSTORE_PASSWORD }}"
export IGNITION_CERT_PASSWORD="${{ secrets.PROD_CERT_PASSWORD }}"

# Build the module
./gradlew build
```

### Generating New Certificates

For production use, generate new certificates with private passwords:

```bash
# See SIGNING.md for the full keytool commands to generate new certificates.
# Use STRONG passwords and store them in your organization's secret management system.
```

### Current Certificates

The development certificates in this repository:
- **keystore.jks** - Development self-signed certificate
- **certificate.der** - Development public certificate
- **Password**: [stored in CI secrets]

These certificates are:
- ✅ Safe for development and testing
- ✅ Allow immediate clone-and-build (with credentials from secret management)
- ❌ **NOT for production use**
- ❌ Passwords must never be committed to version control

### Security Checklist

Before deploying to production:

- [ ] Generate new production certificates
- [ ] Use strong passwords (20+ characters, random)
- [ ] Store passwords in secrets management (Vault, AWS Secrets, etc.)
- [ ] Configure CI/CD to inject secrets as environment variables
- [ ] Verify `gradle.properties` is in `.gitignore`
- [ ] Never commit real credentials to version control
- [ ] Rotate certificates annually

### If Credentials Are Accidentally Committed

If production credentials are accidentally committed:

1. **Immediately revoke the certificates**
2. **Generate new certificates** with new passwords
3. **Remove from git history**:
   ```bash
   # Use git-filter-repo or BFG Repo-Cleaner
   git filter-repo --path gradle.properties --invert-paths
   git push --force
   ```
4. **Audit**: Check if compromised certificates were used
5. **Notify**: Inform security team and stakeholders

### Contact

Security issues: [Report here]
