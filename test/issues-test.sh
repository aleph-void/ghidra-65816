#!/usr/bin/env bash
# Upstream-issue regression test (test/issues/issues.s): the byte sequences from the
# achan1989/ghidra-65816 issue reports must decode as the "; expect:" comments say, no immediate
# operand may reference its own instruction bytes (issue #10), and the decompiler must see the
# immediates of `sub` as constants.
#   test/issues-test.sh [workdir]     (CA65/LD65 set: rebuild issues.bin from issues.s first)
set -euo pipefail
: "${GHIDRA_INSTALL_DIR:?set GHIDRA_INSTALL_DIR}"
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mkdir -p "${1:-${TMPDIR:-/tmp}/65816-issues}" && cd "${1:-${TMPDIR:-/tmp}/65816-issues}" && pwd)
PROJ=$(mktemp -d "${TMPDIR:-/tmp}/65816-iss.XXXXXX")
trap 'rm -rf "$PROJ"' EXIT
if [[ -n ${CA65:-} && -n ${LD65:-} ]]; then
	"$CA65" -g -o "$PROJ/issues.o" "$HERE/issues/issues.s"
	"$LD65" -C "$HERE/issues/raw8000.cfg" -Ln "$HERE/issues/issues.lbl" -o "$HERE/issues/issues.bin" "$PROJ/issues.o"
fi
SUB=$(awk '$3 == ".sub" { print substr($2, 3) }' "$HERE/issues/issues.lbl")
"$GHIDRA_INSTALL_DIR/support/analyzeHeadless" "$PROJ" p -import "$HERE/issues/issues.bin" \
	-loader BinaryLoader -loader-baseAddr 0x8000 -processor 65816:LE:16:default -noanalysis \
	-scriptPath "$HERE" -postScript G65816IssueCheck.java "$WORK/issues.txt" 8000 "$SUB" \
	-deleteProject >"$PROJ/log.txt" 2>&1 || { tail -40 "$PROJ/log.txt" >&2; exit 1; }
python3 - "$HERE/issues/issues.s" "$WORK/issues.txt" <<'PY'
import fnmatch, re, sys
src, out = sys.argv[1], open(sys.argv[2]).read()
listing, _, c = out.partition("=== decompile")
ins = [l.split(" ", 2) for l in listing.splitlines() if l.strip()]
exp = re.findall(r"; expect: (.+?)\s*$", open(src).read(), re.M)
ok = bad = 0
i = 0
for e in exp:
    j = next((k for k in range(i, len(ins)) if fnmatch.fnmatchcase(ins[k][2], e)), None)
    if j is None:
        bad += 1
        print(f"FAIL expected {e!r} after {ins[i - 1][0] if i else 'start'}; got {ins[i][2] if i < len(ins) else None!r}")
    else:
        ok += 1
        i = j + 1
selfref = [l for l in ins if l[1] != "selfrefs=0"]
for l in selfref:
    print("FAIL self reference:", " ".join(l))
ok, bad = ok + (not selfref), bad + bool(selfref)
const = "= 0x35;" in c and "= 0x13;" in c   # $1234 + $0101 stored a byte at a time
print("FAIL decompiler does not show the immediates of sub as constants:\n" + c) if not const else None
ok, bad = ok + const, bad + (not const)
print(f"issues: {ok} passed, {bad} failed")
sys.exit(1 if bad else 0)
PY
