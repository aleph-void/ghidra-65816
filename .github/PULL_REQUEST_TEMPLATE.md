## Summary

<!-- What does this change, and why? -->

## Change type

- [ ] Bug fix
- [ ] CPU semantics / P-code
- [ ] Instruction decoding or disassembly
- [ ] Tests / CI
- [ ] Documentation
- [ ] Build / developer tooling
- [ ] Other

## Technical details

<!-- Mention relevant instructions, addressing modes, registers, flags, memory effects, and control-flow changes. -->

## Validation

- [ ] `python3 -m unittest discover --start-directory tests --verbose`
- [ ] SLEIGH compiled with the Ghidra version pinned in CI
- [ ] `gradle test` / runtime P-code tests run (when applicable)
- [ ] Added or updated focused regression tests
- [ ] Checked the diff for unintended changes

**Test results / CI link:**

<!-- Include commands run and results. If a check was not run, explain why. -->

## CPU-semantics evidence (when applicable)

<!-- Cite the W65C816 reference, trusted test vectors, or an independent emulator. Avoid claiming correctness from compilation alone. -->

**Reference / test-vector links:**

## Compatibility and scope

<!-- Describe user-visible behavior changes, limitations, or follow-up work. -->

- [ ] Documentation updated where needed
- [ ] No generated build artifacts or unrelated files included
- [ ] Security and trust-boundary implications considered, if applicable
