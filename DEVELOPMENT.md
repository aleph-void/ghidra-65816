# Development

## Run checks

| Check | Command / CI step | What it proves |
| --- | --- | --- |
| Source regression tests | `python3 -m unittest discover --start-directory tests --verbose` | Known source mistakes and language metadata stay covered |
| SLEIGH compile | CI uses the pinned Ghidra 12.1.4 compiler | Language syntax and constructors compile |
| Repeat compile | Built-in CI check | Identical inputs produce identical `.sla` files |
| Runtime p-code tests | Gradle/JUnit with Ghidra's `PcodeEmulator` | Selected instructions execute with expected register/flag results |

## Runtime tests

The CI workflow compiles the language into a temporary external Ghidra module, then runs the small
JUnit suite against that module. It currently tests:

| Instruction | Case |
| --- | --- |
| `XBA` | Byte exchange, plus N/Z flags for negative and zero results |
| `CMP abs` | Unsigned carry for less-than and equal operands |
| `CPY abs` | Correct comparison width in 8-bit and 16-bit index modes |

The tests use Ghidra's own p-code emulator and do not require a separate CPU emulator.

## Run locally

Use Java 21 and Gradle 8.5 or newer. Point the commands at an extracted Ghidra 12.1.4 distribution:

```sh
export GHIDRA_INSTALL_DIR=/path/to/ghidra_12.1.4_PUBLIC
export GHIDRA_EXTERNAL_MODULES="$PWD/build/harness-modules/ghidra-65816"
mkdir -p "$GHIDRA_EXTERNAL_MODULES"
cp Module.manifest "$GHIDRA_EXTERNAL_MODULES/Module.manifest"
bash scripts/ci/compile-sleigh.sh "$GHIDRA_INSTALL_DIR" "$GHIDRA_EXTERNAL_MODULES/data/languages"
gradle test
```

The compile script also works with one argument; in that form it uses a temporary directory and only
checks compilation and repeatability.

## Update Ghidra

CI pins the official release URL and SHA-256 hash. When updating versions, change
`GHIDRA_VERSION`, `GHIDRA_RELEASE_DATE`, and `GHIDRA_SHA256` together in
`.github/workflows/sleigh.yml`. Check the release's required Java and Gradle versions.

A passing compile is necessary, but it is not proof of full CPU correctness. Keep runtime tests small,
add one regression at a time, and compare disputed behavior with trusted 65816 test vectors or an
independent implementation.
