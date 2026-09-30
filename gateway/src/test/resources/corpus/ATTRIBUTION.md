# Corpus attribution

Real-world Rockwell Studio 5000 export files vendored for fidelity/integration
testing of the address-space builder. Each file is licensed MIT or
Apache-2.0 by its source repository and retrieved at a pinned commit SHA
(a permalink, not a moving branch head); none carry obligations beyond the
attribution this file provides.

**Excluded from this corpus:** `CompactLogix1768-1768L45-fw20-drbitboy-UNLICENSED.L5X`
(source: [drbitboy/plc_rng](https://github.com/drbitboy/plc_rng)) has no
LICENSE file in its source repository. It is **not vendored, committed, or
redistributed** — local-testing-only per the licence-clean rule, and kept
outside version control.

## Catalogue

| File | Source repo / URL (commit-pinned) | Licence | Processor | Firmware |
| ---- | ---------------------------------- | ------- | --------- | -------- |
| `ControlLogix-1756L83E-fw36-L5Sharp.L5X` | [tnunnink/L5Sharp](https://github.com/tnunnink/L5Sharp/blob/8592fbf657f19e7fedac59cef7b616540cf6e6e3/tests/L5Sharp.Tests.Samples/Projects/Test.L5X) | MIT | 1756-L83E | 36.00 |
| `ControlLogix-1756L72-fw37-iotrustlab-controller.L5X` | [iotrustlab/sphere-usecases](https://github.com/iotrustlab/sphere-usecases/blob/bf95ea2f6cbfd66b55e6e0275c0975a06a003cca/sector-water/rovisys-treatment/usecases/p1-onboarding/implementations/rockwell/controller/Controller_PLC_V1.1.L5X) | MIT | 1756-L72 | 37.00 |
| `ControlLogix-1756L72-fw37-iotrustlab-controller.L5K` | [iotrustlab/sphere-usecases](https://github.com/iotrustlab/sphere-usecases/blob/bf95ea2f6cbfd66b55e6e0275c0975a06a003cca/sector-water/rovisys-treatment/usecases/p1-onboarding/implementations/rockwell/controller/Controller_PLC_V1.1.L5K) (same repo/dir as above, L5K twin of the same controller) | MIT | 1756-L72 | 37.00 (RSLogix 5000 v37.00, `IE_VER := 2.28`) |
| `ControlLogix-1756L72-fw37-iotrustlab-simulator.L5X` | [iotrustlab/sphere-usecases](https://github.com/iotrustlab/sphere-usecases/blob/bf95ea2f6cbfd66b55e6e0275c0975a06a003cca/sector-water/rovisys-treatment/usecases/p1-onboarding/implementations/rockwell/simulator/Simulator_PLC_V1.1.L5X) | MIT | 1756-L72 | 37.00 |
| `ControlLogix-1756L85E-fw38-RockwellAutomation.L5X` | [RockwellAutomation/ra-logix-designer-vcs-custom-tools](https://github.com/RockwellAutomation/ra-logix-designer-vcs-custom-tools/blob/d10fae3d35b240ca6bf38fe83ec633b02ae5b9dc/e2e_tests/fixtures/raC_Opr_HTTP_Client_deps.L5X) (official Rockwell repo) | MIT | 1756-L85E | 38.00 |
| `CompactLogix5380-5069L320ERM-fw34-dmroeder.L5X` | [dmroeder/panelview_running_file](https://github.com/dmroeder/panelview_running_file/blob/441485f5071ce904d94917af241fd6ae5c29ddc9/MER_Name.L5X) | Apache-2.0 | 5069-L320ERM | 34.01 |
| `CompactLogix5370-1769L33ER-fw33-NodeblueAI.L5X` | [Nodeblue-AI/studio5000-mcp-server](https://github.com/Nodeblue-AI/studio5000-mcp-server/blob/5c026482e5df10111e25cc98d2d8a440ddecc977/tests/fixtures/sample.l5x) | MIT | 1769-L33ER | 33.00 |
| `CompactLogix5370-1769L33ER-fw30-stellentus.L5X` | [stellentus/l5x](https://github.com/stellentus/l5x/blob/09460430d80ab2d20034ce5ec466dd545ea0b891/test.L5X) | MIT | 1769-L33ER | 30.00 |

## Processor types (per manifest catalogue)

All files above are Rockwell/Allen-Bradley Logix controller exports handled
by `L5XParser` (7 files) or `L5KParser` (1 file, the iotrustlab controller
`.L5K` twin). None require the JSON or CSV parser paths.

## Notes

- These files are vendored strictly for parser/address-space test fixtures;
  they are not used at runtime and carry no licence obligations beyond
  attribution, which this file provides.
- The L5K file is the only L5K sample in the corpus; L5X is the
  fully-verified Rockwell format.
