#!/usr/bin/env bash
# SNES ROM loader test for the boards beyond plain LoROM/HiROM: synthetic ExHiROM, HiROM+SRAM,
# SA-1 and SuperFX images (test/loader/make_roms.py) are imported without naming a loader; the
# memory map must put each marker where the CPU sees it, create the RAM blocks, and disassemble
# the reset vector.     test/loader-test.sh [workdir]
set -euo pipefail
: "${GHIDRA_INSTALL_DIR:?set GHIDRA_INSTALL_DIR}"
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mkdir -p "${1:-${TMPDIR:-/tmp}/65816-loader}" && cd "${1:-${TMPDIR:-/tmp}/65816-loader}" && pwd)
python3 "$HERE/loader/make_roms.py" "$WORK"
: > "$WORK/loader.txt"
for img in exhirom hirom_sram sa1 superfx; do
	PROJ=$(mktemp -d "${TMPDIR:-/tmp}/65816-ldr.XXXXXX")
	"$GHIDRA_INSTALL_DIR/support/analyzeHeadless" "$PROJ" p -import "$WORK/$img.sfc" \
		-scriptPath "$HERE" -postScript G65816LoaderCheck.java "$WORK/checks.txt" "$img" "$WORK/$img.txt" \
		-deleteProject >"$PROJ/log.txt" 2>&1 || { tail -40 "$PROJ/log.txt" >&2; exit 1; }
	rm -rf "$PROJ"
	cat "$WORK/$img.txt" >> "$WORK/loader.txt"
done
grep -v "^PASS" "$WORK/loader.txt" | grep -v "^loader SNES ROM$" || true
pass=$(grep -c "^PASS" "$WORK/loader.txt" || true); fail=$(grep -c "^FAIL" "$WORK/loader.txt" || true)
snes=$(grep -c "^loader SNES ROM$" "$WORK/loader.txt" || true)
echo "loader: $pass passed, $fail failed ($snes/4 images loaded by the SNES ROM loader)"
[[ $fail -eq 0 && $snes -eq 4 ]]
