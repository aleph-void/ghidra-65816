# Development

## Run tests

Run the Python regression tests:

```sh
python3 -m unittest discover --start-directory tests --verbose
```

Compile the processor definition with the same Ghidra release used by CI (currently Ghidra
12.1.4):

```sh
bash scripts/ci/compile-sleigh.sh /path/to/ghidra_12.1.4_PUBLIC
```

The script compiles a temporary copy of `data/languages` twice and checks that the generated
`65816.sla` files match. It does not add generated files to the repository.

## What the checks mean

| Check | Confirms | Does not confirm |
| --- | --- | --- |
| Python tests | Selected source patterns and XML metadata stay as expected | Full instruction correctness |
| SLEIGH compile | The language compiles with the pinned Ghidra version | Runtime CPU behaviour |
| Repeat compile | Identical inputs produce identical output | Correctness of the output |

## Update the Ghidra version

The CI workflow pins the official Ghidra download and its SHA-256 hash. To update it, change
`GHIDRA_VERSION`, `GHIDRA_RELEASE_DATE`, and `GHIDRA_SHA256` together in
`.github/workflows/sleigh.yml`. Use the official
[Ghidra releases](https://github.com/NationalSecurityAgency/ghidra/releases) page and check
which Java version the release requires.

## Before changing CPU semantics

1. Check the 65816 reference documentation.
2. Add a focused regression test.
3. Compile the language and run the tests.
4. For behaviour that depends on runtime state, validate against trusted test vectors or an
   independent emulator before claiming it is correct.

Keep changes small. A passing build is necessary, but it is not proof of correct emulation.
