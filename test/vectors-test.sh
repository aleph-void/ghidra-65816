#!/usr/bin/env bash
# Vector entry-point test (test/vectors/vectors.s): import a raw image with auto-analysis and no
# entry point given; every vector target must be disassembled with the right operand widths.
#   test/vectors-test.sh [workdir]     (CA65/LD65 set: rebuild vectors.bin from vectors.s first)
set -euo pipefail
: "${GHIDRA_INSTALL_DIR:?set GHIDRA_INSTALL_DIR}"
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mkdir -p "${1:-${TMPDIR:-/tmp}/65816-vectors}" && cd "${1:-${TMPDIR:-/tmp}/65816-vectors}" && pwd)
PROJ=$(mktemp -d "${TMPDIR:-/tmp}/65816-vec.XXXXXX")
trap 'rm -rf "$PROJ"' EXIT
if [[ -n ${CA65:-} && -n ${LD65:-} ]]; then
	"$CA65" -o "$PROJ/vectors.o" "$HERE/vectors/vectors.s"
	"$LD65" -C "$HERE/vectors/raw8000.cfg" -o "$HERE/vectors/vectors.bin" "$PROJ/vectors.o"
fi
"$GHIDRA_INSTALL_DIR/support/analyzeHeadless" "$PROJ" p -import "$HERE/vectors/vectors.bin" \
	-loader BinaryLoader -loader-baseAddr 0x8000 -processor 65816:LE:16:default \
	-scriptPath "$HERE" -postScript G65816Listing.java "$WORK/vectors.txt" \
	-deleteProject >"$PROJ/log.txt" 2>&1 || { tail -40 "$PROJ/log.txt" >&2; exit 1; }
python3 - "$HERE/vectors/vectors.s" "$WORK/vectors.txt" <<'PY'
import re, sys
exp = [m.groups() for m in re.finditer(r"^\s*(?:\w+:)?\s*(\w+) #\$([0-9a-f]+)\s*; expect (\w+?)(8|16)\b",
                                       open(sys.argv[1]).read(), re.M)]
got = {}
for l in open(sys.argv[2]):
    p = l.split()   # addr bytes M X E MNEMONIC operand
    if len(p) > 6 and p[6].startswith("#"):
        got[(p[5].lower(), int(p[6].lstrip("#$").replace("0x", ""), 16))] = l.strip()
ok = bad = 0
for mn, imm, _, width in exp:
    line = got.get((mn, int(imm, 16)))
    if line and len(line.split()[1]) // 2 == (2 if width == "8" else 3):
        ok += 1
    else:
        bad += 1
        print(f"FAIL {mn} #${imm} expected {width}-bit: {line}")
print(f"vectors: {ok} passed, {bad} failed")
sys.exit(1 if bad else 0)
PY
