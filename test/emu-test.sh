#!/usr/bin/env bash
# Run the p-code emulation test (test/emu/emu_cases.txt).   test/emu-test.sh [out.txt]
# If CA65/LD65 are set, emu.bin and emu.lbl are rebuilt from emu.s first.
# EMU_DIR=emu65802 EMU_LANG=65802:LE:16:default runs the 65802 variant's cases instead.
set -euo pipefail
: "${GHIDRA_INSTALL_DIR:?set GHIDRA_INSTALL_DIR}"
HERE=$(cd "$(dirname "$0")" && pwd)
D=$HERE/${EMU_DIR:-emu}
LANG_ID=${EMU_LANG:-65816:LE:16:default}
OUT=$(readlink -m "${1:-${TMPDIR:-/tmp}/65816-emu.txt}")
PROJ=$(mktemp -d "${TMPDIR:-/tmp}/65816-emu.XXXXXX")
trap 'rm -rf "$PROJ"' EXIT
if [[ -n ${CA65:-} && -n ${LD65:-} ]]; then
	"$CA65" -g -o "$PROJ/emu.o" "$D/emu.s"
	"$LD65" -C "$D/raw8000.cfg" -Ln "$D/emu.lbl" -o "$D/emu.bin" "$PROJ/emu.o"
fi
"$GHIDRA_INSTALL_DIR/support/analyzeHeadless" "$PROJ" p -import "$D/emu.bin" \
	-loader BinaryLoader -loader-baseAddr 0x8000 -processor "$LANG_ID" -noanalysis \
	-scriptPath "$HERE" -postScript G65816EmuTest.java "$D/emu_cases.txt" "$D/emu.lbl" "$OUT" \
	-deleteProject >"$PROJ/log.txt" 2>&1 || { tail -40 "$PROJ/log.txt" >&2; exit 1; }
cat "$OUT"
grep -q " 0 failed" "$OUT"
