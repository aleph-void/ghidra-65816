# WDC 65816 (SNES main CPU)

Ghidra processor module for the WDC W65C816S (65816) as used in the Super Nintendo: SLEIGH
disassembly with M/X/E width tracking, p-code semantics, a SNES ROM loader (LoROM, HiROM,
ExHiROM, SA-1, SuperFX) and analyzers for vectors, callee width changes and the data bank.
Language IDs `65816:LE:16:default` and `65802:LE:16:default`.

This is a community module: achan1989's
[ghidra-65816](https://github.com/achan1989/ghidra-65816) as continued by kaayzouee
([ghidra-65816](https://github.com/kaayzouee/ghidra-65816)), MIT. See [CREDITS.md](CREDITS.md)
for the authors and every change Ghidra-Rare made (a fork commit per change).

## What it covers

- All 256 opcodes in emulation and native mode, with the accumulator (M) and index (X) widths
  as decode context. The context follows `REP`/`SEP`, `XCE` after `CLC`/`SEC`/`REP #$01`/
  `SEP #$01`, `PHP`...`PLP` (one level, along branches and calls) and `PEA`; `PLP`. Callees
  inherit the caller's context; after a call the caller keeps its own, unless the callee sets a
  width on every return path ("65816 Callee Width Changes" analyzer).
- Immediate operands are constants (no references to the instruction's own bytes).
- `WAI` falls through and `STP` is a self-loop (halt); neither is a pcodeop, so the emulator
  runs them.
- The program counter is the 24-bit `PBR:PC`. Absolute `JMP`/`JSR` target the instruction's bank.
- SNES ROM loader (`SNES ROM`): finds the internal header, maps ROM where the CPU sees it (LoROM
  banks `$00-$7D:8000`, FastROM mirrors at `$80+`; HiROM `$C0+` with `$40+` and `$00/$80:8000`
  mirrors), WRAM `$7E0000-$7FFFFF`, the low-RAM mirror and the I/O pages (volatile), and turns
  the vectors into entry points: RESET and the emulation vectors in emulation mode, the native
  vectors with M=X=1; RESET also gets the reset state's DBR=0. ExHiROM (map mode `$25`), SA-1
  (`$23`, the reset MMC map, BW-RAM, I-RAM, SA-1 registers) and SuperFX (GSU chipset bytes; GSU RAM
  and registers) are mapped too, and cartridge SRAM from the header (LoROM `$70-$7D:0000`,
  HiROM/ExHiROM `$20-$3F:6000`, with mirrors).
- Analyzers (Java, in the extension): "65816 Vector Entry Points" (vectors of images not loaded
  as SNES ROMs, e.g. raw binaries), "65816 Callee Width Changes", "65816 Far Functions" (RTL
  functions get `__far`), "65816 DBR Propagation" (a callee whose callers all have the same DBR
  gets it at entry, so its absolute operands get references) and "65816 Contextless Code Cleanup"
  (code Ghidra finds at speculative pointers without any M/X/E context is removed again).
- 65802 (`65802:LE:16:default`): the same instruction set with a 16-bit address bus; every
  computed bus address is masked to bank 0.
- cspec: `__near` (JSR/RTS, default) and `__far` (JSL/RTL) prototypes.

## Results (Ghidra 12.1.4)

Before = the first verified state (`3f79a62`), after = this branch.

| Check | Before | After |
|---|---|---|
| Decode vs MAME `g65816` (every opcode x 16 operand patterns + 30,000 random words), each of M/X/E = 000, 010, 100, 110, 111 | 100% semantic, 96.8% exact (34,096 records per mode; every difference a spelling alias) | same |
| ca65 re-assembles Ghidra's text to the same bytes | 100% (34,096 x 5) | same |
| Every opcode decodes in every mode | 1,280/1,280 | same |
| Context tracking (`test/ctx`) | 20/20 | 23/23 (3 new XCE-after-REP/SEP cases; 22/23 on the old spec) |
| Emulator, hand-computed cases (`test/emu`) | 45/45 | 45/45 |
| 65802 emulator cases (`test/emu65802`; 0/8 when run on the 65816 language) | n/a | 8/8 |
| Upstream-issue regressions (`test/issues`: achan1989 #1-#4, #8, #10, #12, callee widths, decompiler constants) | 26/44 on the old spec | 44/44 |
| Vector entry points of a raw image (`test/vectors`) | 0/8 | 8/8 |
| Loader boards: synthetic ExHiROM, HiROM+SRAM, SA-1, SuperFX (`test/loader`) | n/a | 29/29 |
| Upstream's JUnit P-code tests (`gradle test`) | did not run (build.gradle replaced) | 37/37 |
| SingleStepTests/65816, 50 tests per opcode and mode | 25,400/25,600 (`WAI`/`STP` pcodeops) | 25,600/25,600 |
| Real ROMs: 13 homebrew ROMs (10 PVSnesLib C/WLA-DX incl. HiROM, 3 ca65), loader picked automatically | 45,803 instructions, 0 decode differences vs MAME, 5 bad-instruction bookmarks, 7 decompiler timeouts (30 s) in 530 functions | 45,803 instructions, 0 differences, 5 bad-instruction bookmarks, 0 decompiler timeouts |
| Real ROMs vs MAME execution traces (10 s each) | right width for 100% of executed instructions Ghidra disassembled; 82-100% of executed PCs reached (40% `mapandobjects`) | same |
| Data references of executed absolute/direct-page operands vs the DB/D registers in the MAME trace | n/a | 892/892 use a DBR/DP the CPU had there |
| Absolute operands with a data reference (breakout.sfc) | 155 of 723 | 386 of 722 |

The decode oracle is MAME's g65816 disassembler built with M and X as options
(`test/build-mame-dis.sh`; `unidasm` hard-codes M=X=0, and the driver reproduces its output
exactly in that mode). Execution traces come from MAME's `snes` driver with an independent
SPC700 boot ROM (`test/trace/make_ipl.py`), so the copyrighted IPL is never needed.

## How to test

```sh
export GHIDRA_INSTALL_DIR=/opt/ghidra_12.1.4_PUBLIC
CC65_BIN=/path/to/cc65/bin SINGLESTEP=1 REAL_SAMPLES=/path/to/samples \
  test/run-tests.sh /path/to/workdir
```

`CC65_BIN` (ca65/ld65) enables the assembler round trip and rebuilds the test programs;
`GRADLE_GHIDRA=<a Ghidra 12.1.4 without this module installed>` also runs upstream's JUnit tests;
`SINGLESTEP=1` fetches SingleStepTests once (about 3 GB downloaded, 13 MB kept);
`REAL_SAMPLES` fetches the homebrew ROMs by pinned URL and SHA-256
(`test/fetch-real-samples.sh`) and, if `mame` is installed, traces each one. Nothing fetched is
committed.

## Known gaps

Fixed in the limitations pass (each with a test that fails before the change):

- Immediates referenced their own instruction bytes (achan1989#10, #8 listings): constants now.
- Vectors of non-SNES images were not entry points: "65816 Vector Entry Points" analyzer.
- 65802 not supported: `65802:LE:16:default`.
- `WAI`/`STP` were pcodeops the emulator could not run: flow (fall-through / halt self-loop).
- `XCE` after `REP #$01`/`SEP #$01` was decoded with an unknown carry.
- `REP`/`SEP` in a callee did not reach the caller: "65816 Callee Width Changes".
- The DBR was not tracked: RESET seeds DBR=0, "65816 DBR Propagation" carries it into callees;
  every executed reference checked against MAME's DB register.
- Loader: ExHiROM, SA-1, SuperFX and cartridge SRAM.
- Upstream's `gradle test` no longer ran (our build.gradle had replaced theirs): restored.

Remaining, and why:

- Widths that only run-time data decides: a `PLP` of a status byte the code did not push itself,
  `XCE` with a computed carry (native mode is assumed), a callee whose return width depends on
  its caller's. These are stack/flag contents at run time, which static decode context cannot
  know; where the evidence is ambiguous nothing is forced (as upstream's SUPPORT.md requires).
- Code run under two different widths (PVSnesLib's sound upload loop runs the same `LDA [dp],Y`
  with M=0 and M=1): Ghidra's listing has one instruction per address. It gets a
  "Multiple flows produced inconsistent instruction prototype" bookmark.
- The p-code reads M/X/E from the decode context, not from a run-time flag: an instruction's
  length depends on them, and SLEIGH must know it when decoding. Emulating across a width change
  works when the context at the next instruction is right.
- Bank wrap of the instruction stream: an instruction whose bytes run past `$xx:FFFF`, or the
  fall-through after one ending there, continues at `$xx+1:0000`. Ghidra reads instruction bytes
  and computes fall-through linearly in the address space; a spec cannot change that. Data
  accesses, branches, `JSR`/`RTS` and `JSL`/`RTL` wrap correctly (SingleStepTests).
- Indirect jumps and calls through run-time data (function pointers passed on the stack, e.g.
  PVSnesLib's `pea $0000; pea $da5e; jsl nmiSet`, or `main` in `mapandobjects`) are not followed:
  which data words are code pointers is not in the code. Following code-looking pointers is the
  guesswork the context-less code cleanup removes, because without a decode context such code is
  decoded at the wrong widths.
- The direct page is not carried into callees: compiled code moves DP as a frame pointer, so
  callers' DP values are stack-relative.
- Loader: the SA-1 and SuperFX co-processor programs (SA-1 code is 65816 but runs on the second
  CPU with its own vectors; GSU code is another ISA) are not separated; SA-1 MMC bank switching
  after reset is not followed (run-time register writes). Only synthetic images test these maps:
  no freely licensed ExHiROM, SA-1 or SuperFX cartridge was found.

## Upstream limitations and issues

| Item | Outcome |
|---|---|
| README: bank-boundary crossing | Partly fixed (data, branches, calls); instruction-stream wrap remains (see above) |
| README: immediates as references | Fixed (`e541cb9`) |
| README: PLP/RTI and MF/XF | PLP after PHP or PEA restores widths; RTI never falls through; other PLPs remain (run-time stack) |
| README: XCE and the carry | Carry from CLC/SEC/REP/SEP just before; computed carries remain |
| README: interrupt vectors not entry points | Fixed (loader, vector analyzer) |
| README: tests do not prove hardware behaviour | Caveat kept; SingleStepTests 25,600/25,600, MAME traces |
| README: 65802 not supported | Fixed (`f7c2db8`) |
| achan1989#8 Base accumulator mode should be changeable | It is (Set Register Values on `ctx_MF`/`ctx_XF`, documented in the README); widths now also follow vectors, XCE, PLP and callees |
| achan1989#9 Unknown prototype attribute: type | Fixed by `2d2f11f`; the decompiler runs (issue test) |
| achan1989#10 Immediates treated as offsets | Fixed (`e541cb9`); DP/DBR references: DBR now tracked, DP see above; JSR in the current bank fixed by `7985af3` |
| achan1989#14 can't read .sla (Ghidra 10.1.2) | The spec compiles on 12.1.3/12.1.4/12.2 (fixed upstream by PR #13; unused labels) |
| achan1989#1-#4, #12 (closed: JSR decode, LDA/CMP/LDX/RTI widths) | Regression cases in `test/issues` |
| achan1989#6 (closed: needs a SNES loader) | The SNES ROM loader |
| achan1989#5, #7, #11 (closed: question, not a bug, fixed upstream) | Nothing to do |
| kaayzouee#9 native RTI test with X clear | Added (`fc1f918`) |
| TODO/FIXME comments | None in the sources |

## Reference-tool bugs found

- MAME `g65816` prints stack-relative offsets as signed (`lda -$1,s` for `$ff,s`) and prints
  `$80` as `-$0`; branch/`BRL`/`PER` displacements of `$80`/`$8000` also print as `-$0`.
- MAME prints `JML [abs]` (`$DC`) as `jml ($1234)` and `JML long` (`$5C`) as `jmp`.
