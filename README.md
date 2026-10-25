> **Ghidra-Rare fork.** This branch adds tested fixes, a SNES ROM loader, analyzers for M/X
> width and data-bank tracking, the 65802 variant, release builds for the three newest Ghidra
> releases and test suites; see [GHIDRA-RARE.md](GHIDRA-RARE.md) for what was verified and how,
> and [CREDITS.md](CREDITS.md) for every change. Prebuilt extension zips are on the
> [Releases page](https://github.com/aleph-void/ghidra-65816/releases). The original README
> follows; text that this fork made wrong is updated in place and marked *(fork)*.

# Ghidra 65816

A processor module for the WDC 65816 CPU in Ghidra.

This is an experimental community continuation of the original
[ghidra-65816 project](https://github.com/achan1989/ghidra-65816). It is useful for
disassembly and early analysis, but it is **not a cycle-accurate CPU emulator**. Verify important
results against known-good 65816 documentation or test programs.

*(fork)* This fork is verified against MAME's 65816 disassembler, the cc65 assembler,
SingleStepTests/65816 (all 25,600 sampled tests pass) and homebrew SNES ROMs with MAME execution
traces; [GHIDRA-RARE.md](GHIDRA-RARE.md) has the numbers. It targets Ghidra 12.1.3, 12.1.4 and
12.2.

## Install

*(fork)* Download the zip for your Ghidra version from the
[Releases page](https://github.com/aleph-void/ghidra-65816/releases), then in Ghidra use
**File > Install Extensions**, **+**, pick the zip, and restart. (Copying the repository into
`Ghidra/Processors/65816` still gives the language, but not the SNES ROM loader and the analyzers,
which are Java; build them with `gradle buildExtension`, see [GHIDRA-RARE.md](GHIDRA-RARE.md).)

Then import your ROM or binary. *(fork)* A SNES cartridge image (`.sfc`/`.smc`) is recognised by
the **SNES ROM** loader, which picks the language, maps ROM, mirrors, RAM, cartridge RAM and I/O
(LoROM, HiROM, ExHiROM, SA-1, SuperFX) and disassembles the reset and interrupt vectors. For a raw
binary, choose **65816** (or **65802**) as the language and set up the memory map; the
"65816 Vector Entry Points" analyzer then disassembles the vectors if bank 0's `$FFE4-$FFFF` is
loaded.

## Set the initial CPU state

For each code entry point, set the processor context and registers to match the program.

| Setting | Meaning |
| --- | --- |
| `MF` | Accumulator width: 1 = 8-bit, 0 = 16-bit |
| `XF` | Index-register width: 1 = 8-bit, 0 = 16-bit |
| `EF` | Emulation mode |
| `PBR` | Program bank |
| `DBR` | Data bank |
| `DP` | Direct-page base |
| `SP` | Stack pointer |
| `DF` | Decimal mode flag |

In the CodeBrowser, right-click the first instruction and use **Processor Options...** for
`MF`, `XF`, and `EF`. Use **Set Register Values...** for registers such as `PBR`, `DBR`,
`DP`, and `SP`.

*(fork)* The context register fields are `ctx_MF`, `ctx_XF` and `ctx_EF`. To disassemble or patch
a region at another accumulator/index width (upstream issue #8), select it, use **Set Register
Values...** on `ctx_MF`/`ctx_XF`, clear the code there and disassemble again; the width then
follows REP/SEP, XCE, PHP/PLP and calls from there. Vector entry points get the CPU's state at
reset/interrupt automatically.

## Current status

| Area | Status |
| --- | --- |
| Instruction decoding | Broad coverage; verify unusual bank-boundary cases. *(fork)* All 256 opcodes in every M/X/E mode match MAME (34,096 records per mode) and re-assemble with ca65 |
| P-code semantics | Improving; not fully validated against a CPU emulator. *(fork)* Validated: SingleStepTests/65816, 25,600/25,600 sampled tests |
| Automatic mode tracking | Limited; changes to `MF`, `XF`, and `EF` may need manual context. *(fork)* Follows REP/SEP, XCE after CLC/SEC/REP/SEP, PHP...PLP, PEA+PLP, calls, and REP/SEP in callees; see the remaining limitations below |
| Interrupt entry points | Vector symbols exist, but entry points may need to be added manually. *(fork)* Fixed: the SNES ROM loader and the vector analyzer disassemble them |
| 65802 variant | Not supported. *(fork)* Supported: language `65802:LE:16:default` |

## Known limitations

*(fork)* Each item is marked **fixed** or **remaining** in this fork, with the reason.

- Instructions and operands that cross the end of a program bank may be analysed incorrectly.
  **Partly fixed / remaining:** data accesses, branches, `BRL`, `JSR`/`RTS` and `JSL`/`RTL`
  wrap as the CPU does (SingleStepTests), but an instruction whose own bytes run past `$xx:FFFF`,
  or the fall-through after one ending there, continues at `$xx+1:0000` in Ghidra: Ghidra reads
  instruction bytes and computes fall-through linearly in one address space, which a SLEIGH
  spec cannot change. Assemblers do not place code across a bank end.
- Immediate operands are modelled through references to instruction bytes, which can limit constant propagation in analysis.
  **Fixed** (upstream issue #10): immediates are constants; no self-references, and the
  decompiler shows the values.
- `PLP` and `RTI` restore status bits at runtime, but Ghidra cannot automatically use the restored
  `MF` and `XF` bits to select the correct decode context.
  **Partly fixed:** `PLP` after a `PHP` on the same flow path, or right after `PEA`, restores the
  saved widths. `RTI` does not fall through, so its pulled status never decides a decode.
  **Remaining:** a `PLP` of a byte the code did not push itself (e.g. a status passed in by a
  caller) is run-time stack data Ghidra cannot see.
- `XCE` changes emulation/native mode at runtime. Static disassembly context cannot always follow
  a mode change that depends on the carry flag.
  **Partly fixed:** the carry is known after `CLC`, `SEC`, `REP #$01` or `SEP #$01` just before
  `XCE`. **Remaining:** a carry computed at run time; native mode is assumed.
- Interrupt vectors are not automatically created as code entry points.
  **Fixed:** the SNES ROM loader and the "65816 Vector Entry Points" analyzer.
- Passing the test suite and compiling SLEIGH do not prove that every instruction behaves exactly
  like real hardware. **Remaining as a caveat**, but the fork checks against independent
  references: SingleStepTests/65816 (25,600/25,600), MAME and ca65 on every encoding, and MAME
  execution traces of 13 homebrew ROMs.

Further gaps found and closed in this fork (REP/SEP in a callee, DBR tracking, the loader's board
types, WAI/STP in the emulator) and the ones that remain are listed in
[GHIDRA-RARE.md](GHIDRA-RARE.md).

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md) for the small local test suite and SLEIGH compiler check.
*(fork)* The full verification suite is `test/run-tests.sh`, described in
[GHIDRA-RARE.md](GHIDRA-RARE.md).
## Supported scope

This repository targets the WDC 65C816 instruction set and Ghidra's static analysis model. SNES ROM loading and memory mapping are platform integrations, not CPU-core semantics; they should be maintained as a separate optional layer. The 65802 variant remains unsupported until it has a dedicated compatibility matrix and tests.
*(fork)* This fork adds the 65802 as `65802:LE:16:default` with its own emulator tests
(`test/emu65802`), and includes a SNES ROM loader in the same extension. See [the support scope](docs/SUPPORT.md) for the current boundaries and acceptance criteria.
