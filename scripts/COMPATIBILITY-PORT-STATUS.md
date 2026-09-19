# Compatibility port in progress

## Scope and user decisions

- Build all twelve Minecraft targets from 1.21 through 1.21.11 using the Python matrix.
- Add 26.3, preserving the menu entries for 26.2, 26.1.2 and 26.1.1.
- The experimental Voxy Blaze3D renderer may stay exclusively on dev. Prioritize the rest of the code. Minecraft's own Blaze3D API is a separate concern.
- User performs visual testing. A successful Java compilation alone is not full build validation.

## Current state

- Main checkout: branch `mc_1.21-1.21.11`, based on dev, with upstream/12111 sources imported in commit 867a0402.
- Commit 4014f453 adds target-specific Fabric API, Sodium, Iris and Mod Menu dependencies, exact target metadata, 26.3 menu entry, and per-build logs.
- The twelve-target matrix has been launched from 4014f453 with four concurrent builds and four Gradle workers per build. Main log: `/private/tmp/voxy-121-matrix.log`. Individual logs: `compatibility-builds/build-logs/`. Inspect running processes before starting another matrix.
- No twelve-version success has been established. Java sources still need conditional adaptations for older targets. Optional integrations still use 1.21.11-era compile dependencies and require examination for older versions.
- Separate checkout: `compatibility-builds/port-26.3`, branch `mc_26.3` based on dev. Merge of upstream/263 is in progress. Three textual conflicts were resolved in working files, but the merge is not staged/committed. Preserve these resolutions and remaining dev integrations.
- First 26.3 compile failed; full log `/private/tmp/voxy-263.log`. Many errors are package moves from Blaze3D to RenderPearl. The experimental renderer should now be excluded along with its call sites rather than ported, per latest user decision.

## Next work

The Python entry point now has separate profiles/dependencies/jdks/compile actions.
Compilation defaults to offline and never installs a missing JDK or bootstraps
an uncached Gradle distribution. During port development, explicitly use
`--action compile --allow-build-downloads` to populate dependencies when needed.
The first twelve-target run from 4014f453 finished with twelve failures, recorded
in the per-build logs. One regression to address is the removed Lithium compile
dependency while WorldConversionFactory still imports a Lithium interface.

1. Collect actual exit status and diagnostics of the twelve-target matrix. Fix configuration errors first, then implement source selection or preprocessing and targeted API adaptations. Do not replace missing APIs with silent nonfunctional stubs.
2. For 26.3, remove experimental renderer integration from this port while keeping the supported renderers. Adapt API references using the actual cached Minecraft jar. Finish merge only after reviewing changes.
3. Commit local build inputs before running the matrix: it checks out branch commits, not uncommitted files.
4. Validate all final JARs and their exact Minecraft/Sodium requirements. Inspect mixin targets where compilation cannot validate them. Record remaining visual checks honestly.
5. Keep native library packaging in mind: upstream/12111 removes macOS RocksDB natives and lacks the dev macOS LMDB/Zstd additions. Preserve applicable platform fixes before final handoff.

An active thread heartbeat named "Portar Voxyrium 1.21 y 26.3" continues this work every 30 minutes. Do not create duplicates. Notify only meaningful verified progress or blockers requiring user action; disable it when the requested work is complete.
