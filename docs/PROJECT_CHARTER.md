# Project Charter — Logix PLC Emulator Module

This document is the authoritative statement of what this project is for, what
"done" means, and what will never be built. A release is justified **only** by
the Maintenance Policy below.

## 1. Purpose

Ignition projects are built against the tag structures of real PLCs — but the
real PLC is usually unavailable: it's running a plant, it's on a customer site,
or it doesn't exist yet. Generic OPC simulators don't help, because the hard
part isn't producing changing values; it's matching the **exact** tag tree the
project will bind to — UDTs, AOIs, arrays and all.

**This module turns the PLC's own program export into the test PLC.** Feed it
the L5K file from Studio 5000 and it emulates that controller's complete
tag structure through Ignition's OPC-UA server — every UDT and AOI expanded,
with configurable simulated values (RAMP/SINE/RANDOM/TOGGLE/STATIC) and
hot-reload when the export changes. Projects can be developed, demonstrated,
and acceptance-tested (with the Ignition Toolbox) against realistic tags with
zero PLC hardware and zero risk to a running plant.

## 2. Definition of Done

On a clean Ignition 8.3 gateway with a signed `.modl`:

1. Install → `Logix PLC Emulator` device type available, zero manual config
2. Upload a real-world L5K export via the web UI → full tag tree
   (UDTs/AOIs expanded) browsable in OPC-UA within seconds; JSON/CSV also
   accepted, `.l5x` rejected with actionable guidance (L5X support removed
   11.0.0). Every emitted NodeId path matches Ignition's native Logix driver
   addressing for the same tag, so a project developed against the emulator
   binds unchanged when the device is swapped for the real PLC
3. Assign simulation patterns to tags → values change accordingly in tag browser
4. Re-upload a modified export → hot-reload updates tags without device restart
5. Tag writes from Ignition round-trip correctly
6. Upload/read/write endpoints validated + rate-limited; XML parsing XXE-proof
7. File version manager retains the last 5 uploads and can revert

The checklist is verified on a clean Ignition gateway with the signed `.modl`
and a genuine Studio 5000 export before each release.

## 3. Maintenance Policy

After §2 passes, a release is justified only by: a defect in a §2 workflow, a
security issue, compatibility with a new Ignition version, a Logix export
construct that fails to parse, or a deliberately chosen candidate feature
(one at a time).

## 4. Won't-Do list (permanent)

| Item | Why not |
| ---- | ------- |
| Executing PLC logic (ladder/ST/FBD semantics) | This emulates tag structures, not a controller runtime |
| EtherNet/IP / CIP wire-protocol emulation | Ignition binds via OPC-UA; protocol emulation is a different product |
| Other PLC families as shipped features (Siemens, Codesys, …) | Designed-for but not built: the parser/addressing boundary (`AddressPolicy` seam) must stay vendor-pluggable, but no new family is implemented until a real export file and a real device are available to verify against |
| Physics/process simulation models | Pattern generators are the boundary; process sims belong outside |
| Rockwell L5X (Studio 5000 XML export) as a shipped format | L5K is the one supported Rockwell format — every real acceptance file is L5K, and shipping a second, unverified format misdirects users into an untested path. `L5XParser` is retained internally as the reference implementation the L5K parser is held to (`AoiCrossFormatEquivalenceTest`), not as a user-facing format |

---

*A change to this charter requires the maintainer's explicit decision, recorded here.*

§2.2 requires swap-compatibility — every NodeId path the emulator emits must
match Ignition's native Logix driver addressing for the same tag, so bindings
survive replacing the emulated device with the real PLC.
