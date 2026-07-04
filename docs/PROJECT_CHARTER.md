# Project Charter — Logix PLC Emulator Module

**Adopted:** 2026-07-04 · Approved by the maintainer. Modelled on the Python 3
module's charter.

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
the L5K/L5X file from Studio 5000 and it emulates that controller's complete
tag structure through Ignition's OPC-UA server — every UDT and AOI expanded,
with configurable simulated values (RAMP/SINE/RANDOM/TOGGLE/STATIC) and
hot-reload when the export changes. Projects can be developed, demonstrated,
and acceptance-tested (with the Ignition Toolbox) against realistic tags with
zero PLC hardware and zero risk to a running plant.

## 2. Definition of Done

On a clean Ignition 8.3 gateway with a signed `.modl`:

1. Install → `Logix PLC Emulator` device type available, zero manual config
2. Upload a real-world L5K or L5X export via the web UI → full tag tree
   (UDTs/AOIs expanded) browsable in OPC-UA within seconds; JSON/CSV also accepted
3. Assign simulation patterns to tags → values change accordingly in tag browser
4. Re-upload a modified export → hot-reload updates tags without device restart
5. Tag writes from Ignition round-trip correctly
6. Upload/read/write endpoints validated + rate-limited; XML parsing XXE-proof
7. File version manager retains the last 5 uploads and can revert

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
| Other PLC families (Siemens, Codesys, …) | Logix-shaped scope; a new family is a new module |
| Physics/process simulation models | Pattern generators are the boundary; process sims belong outside |

---

*Change to this charter requires the maintainer's explicit decision, recorded here with a date.*
