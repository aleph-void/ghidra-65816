#!/usr/bin/env python3
"""Synthetic SNES cartridge images for the loader test (test/loader-test.sh): one per board the
SNES ROM loader maps beyond plain LoROM/HiROM (ExHiROM, SA-1, SuperFX, HiROM with SRAM). Each has
a valid internal header, a reset routine, and marker bytes at chosen file offsets; checks.txt
lists, per image, the CPU addresses where the loader must show each marker, the blocks it must
create and the reset vector target. Nothing here comes from a commercial cartridge.
   make_roms.py <outdir>"""
import os, struct, sys

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
checks = []

def header(rom, at, title, mode, chip, romsz, ramsz, reset):
    h = bytearray(0x40)
    h[0:21] = title.ljust(21).encode()
    h[0x15], h[0x16], h[0x17], h[0x18] = mode, chip, romsz, ramsz
    struct.pack_into("<HHHHHH", h, 0x24, 0, 0, reset, reset, reset, reset)  # native COP..NMI
    struct.pack_into("<HHHHHH", h, 0x34, reset, 0, reset, reset, reset, reset)  # emu COP..IRQ
    rom[at:at + 0x40] = h
    s = sum(rom) & 0xffff  # checksum with the complement pair still zero, then fix it up
    struct.pack_into("<HH", rom, at + 0x1c, s ^ 0xffff, s)
    s = (sum(rom) - sum(rom[at + 0x1c:at + 0x20]) + 0x1fe) & 0xffff
    struct.pack_into("<HH", rom, at + 0x1c, s ^ 0xffff, s)

# reset: sei; clc; xce; rep #$30; lda #$1234; stp
CODE = bytes.fromhex("7818fbc230a93412db")

def image(name, size, hdr_off, mode, chip, ramsz, code_off, reset, markers, blocks):
    rom = bytearray(size)
    rom[code_off:code_off + len(CODE)] = CODE
    for off, addr in markers:
        rom[off:off + 4] = struct.pack(">I", 0xC0DE0000 | (off >> 8 & 0xffff))
    header(rom, hdr_off, name.upper(), mode, chip, 0x0c, ramsz, reset)
    open(f"{out}/{name}.sfc", "wb").write(rom)
    for off, addr in markers:
        checks.append(f"{name} mem {addr:06x} {0xC0DE0000 | (off >> 8 & 0xffff):08x}")
    for b in blocks:
        checks.append(f"{name} block {b}")
    checks.append(f"{name} reset {reset:04x} SEI")

# ExHiROM (map mode $25): 4 MB + 64 KB; header at $40FFC0, the CPU's bank 0 upper half is file
# $408000-$40FFFF. First 4 MB at $C0-$FF (upper halves also $80-$BF), the rest at $40+.
image("exhirom", 0x410000, 0x40ffc0, 0x35, 0x02, 3, 0x408000, 0x8000,
      [(0x000000, 0xc00000), (0x008000, 0x808000), (0x3f0000, 0xff0000), (0x400000, 0x400000),
       (0x408100, 0x008100)],
      ["sram_20 206000", "sram_A0 a06000"])
# HiROM with 8 KB SRAM (ram size 3)
image("hirom_sram", 0x20000, 0xffc0, 0x21, 0x02, 3, 0x8000, 0x8000,
      [(0x000000, 0xc00000), (0x010000, 0x410000), (0x018000, 0x018000)],
      ["sram_20 206000", "sram_A0 a06000"])
# SA-1 (map mode $23, chipset $35): 1.25 MB. Reset MMC state: $C0-$FF linear, LoROM views of
# the 1 MB blocks at $00-$1F, $20-$3F, $80-$9F, $A0-$BF. BW-RAM 64 KB (ram size 6), I-RAM.
image("sa1", 0x140000, 0x7fc0, 0x23, 0x35, 6, 0x0000, 0x8000,
      [(0x000100, 0x008100), (0x000200, 0xc00200), (0x108000, 0x218000), (0x130000, 0xd30000)],
      ["bwram 400000", "iram 003000", "io_2200 002200"])
# SuperFX (map mode $20, chipset $15): 1 MB. $40-$5F linear, LoROM view at $00-$3F and $80-$BF.
image("superfx", 0x100000, 0x7fc0, 0x20, 0x15, 0, 0x0000, 0x8000,
      [(0x000100, 0x008100), (0x000200, 0x400200), (0x018000, 0x038000), (0x0f8000, 0x9f8000)],
      ["gsuram 700000", "io_3000 003000"])
open(f"{out}/checks.txt", "w").write("\n".join(checks) + "\n")
