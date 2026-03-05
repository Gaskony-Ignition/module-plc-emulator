# PLC Emulator - Learnings

Lessons learned during development. Reference this to avoid repeating past mistakes.

## Build System
- Flatten from logix-emulator-module/ subdirectory to root required updating all CI paths
- Version catalog (libs.versions.toml) is the preferred dependency management approach
- Module signing auto-skips when keystore unavailable

## Ignition SDK
- OPC-UA device driver APIs require dependency on com.inductiveautomation.opcua module
- Device extension points must register config record class with correct annotations
- Resource bundles must be registered in startup() for i18n display names
- AddressSpaceBuilder must handle incremental updates for hot-reload

## Security
- Plaintext passwords in docs must be replaced with "[stored in CI secrets]"
- File upload validation must check size, type, and path traversal
- Rate limiting must be per-user AND per-IP with separate thresholds
- XXE prevention required on all XML parsing (L5X files)

## Parser Development
- L5K format is line-based text; L5X is XML
- UDT/AOI expansion must handle circular references
- CSV parser must handle quoted fields with commas
- ParserFactory auto-detection checks file extension first, then content sniffing

## Common Mistakes to Avoid
- Never hardcode passwords in documentation or code
- Always validate canonical paths to prevent traversal
- Simulation engine patterns must be thread-safe (use AtomicReference)
- FileWatcher must handle rapid successive changes (debounce)
- Gateway nav entry name should match user expectation ("Devices" not "Connection Browser")
