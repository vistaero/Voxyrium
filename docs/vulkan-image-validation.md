# Vulkan image validation

The tests compare actual compiled production barrier logic, without merging PR #7,
changing installed JARs, or editing test profiles.

## Run

From the repository root, with JDK 25 and cached Gradle/Minecraft dependencies:

```powershell
git fetch origin pull/7/head:refs/codex/review-pr7
python scripts/Verify-VulkanImages.py --base-ref 0e716551 --include-working-tree
# Also attempt real Vulkan image transitions with core and synchronization validation:
python scripts/Verify-VulkanImages.py --base-ref 0e716551 --include-working-tree --gpu
```

`--base-ref`, `--pr-ref` and `--versions 26.2 26.3` select the input commits and
Minecraft APIs. Each run creates an isolated directory under
`build/vulkan-image-validation/` with source snapshots, logs and `report.json`.
The base is a Git commit; unrelated working-tree edits are excluded. The latest
test harness and its Gradle configuration are copied into each snapshot.
Source hashes and resolved commit IDs are recorded.

Individual tasks on the current checkout:

```powershell
.\gradlew.bat verifyVulkanImageBarriers --offline '-Pminecraft_version=26.2'
.\gradlew.bat verifyVulkanImageGpu --offline '-Pminecraft_version=26.3'
# This switch has an effect only in a source variant implementing the PR option:
.\gradlew.bat verifyVulkanImageBarriers --offline '-Pminecraft_version=26.3' '-PmcImagesGeneral=true'
```

`check` runs the deterministic barrier and SSAO wiring contracts. Native tests
remain opt-in because they require a Vulkan device and the SDK validation layer.
Historical variants intentionally fail their contracts; a comparison run therefore
returns nonzero even when the working-tree correction passes.

## Compared variants

- **baseline:** the selected base commit, retaining its memory fixes.
- **depth-only:** baseline with only the format-dependent aspect correction.
- **pr-experimental:** baseline with the four Vulkan source files from the PR;
  tested in separate JVMs with GENERAL off and on.
- **reference-control:** a laboratory positive control using correct aspects and
  conservative fragment+compute stage coverage. It is not applied to production.
- **working-tree** (opt-in): the current four Vulkan source files, including
  uncommitted fixes, over the selected base. The base `0e716551` preserves the
  original defects and the earlier memory fixes for reproducible comparisons.

The depth-only fixture checks its expected source text and stops on drift rather
than silently editing a different implementation.

## What is checked

`VulkanImageTest` loads the compiled `VkFrameHost` in an isolated class loader.
ASM replaces only Minecraft image/format accessors and the native barrier-call
boundary. It preserves the production aspect, layout, stage and access-mask
calculations. The injected accessors permit deterministic coverage of D16,
X8_D24, D32, three combined depth/stencil formats, and RGBA8.

There are 144 barrier assertions per configuration, covering both transition directions,
image identity, aspect masks, producer writes, fragment reads, compute reads
(the contract needed by SSAO BETTER/BEST), attachment reuse and sampled descriptor
layout selection. The corrected API also enables five checks of the actual
compiled SSAO call sites: both MC depth transitions must request COMPUTE;
offscreen depth must cover early/late tests; output reuse must cover translucency
writes and compositor reads. Both directions are checked even when GENERAL maps them to
the same layout; synchronization must still cover the consumer.

`verifyVulkanImageGpu` creates a native Vulkan instance requesting
`VK_LAYER_KHRONOS_validation` and synchronization validation. It creates and binds
small real images, initializes their layouts, submits the production transitions
and captures validation messages. For depth formats, a render pass clears the
depth to 0.25, a real compute shader samples it into a host-readable buffer, the
attachment is reused with depth 0.75, and compute samples it again. Readback must
match both values within depth-format quantization tolerance. Shader-access
heuristic validation is explicitly enabled to catch descriptor sampling hazards.
It records the device/driver and every executed
format. Unsupported formats are explicitly skipped, and coverage must include
depth-only, combined depth/stencil and colour images. The GENERAL fixture starts
host images in GENERAL, so it tests that assumption rather than proving Minecraft
uses that layout.

If the Khronos layer is absent, the native task reports `UNAVAILABLE` and exits
nonzero. Install/discover the Vulkan SDK validation layer before rerunning. It
never treats lack of native execution as a pass. The harness selects the LWJGL
system allocator so it does not require Minecraft's jemalloc native library.

For a negative control that intentionally recreates the old fragment-only barrier
while compute really reads the image (expected failure):

```powershell
.\gradlew.bat verifyVulkanImageGpu --offline '-Pminecraft_version=26.3' '-PforceFragmentStage=true'
```

The comparison runner returns 1 for contract/validation failures, 2 for incomplete
infrastructure or a harness error, and 0 only when all requested runs pass.

## Interpretation

On baseline `0e716551`, depth-only images get an incorrect stencil aspect and
compute consumers are missing from the barriers. The depth correction should
remove the aspect failures. PR #7 still leaves compute-stage failures in both
layout modes. The reference control must pass, demonstrating that the assertions
can distinguish corrected masks from the known defects.

The production correction selects aspects by format and accepts the actual
sampling stage. The existing fragment overload remains for the compositor;
SSAO explicitly requests COMPUTE in both directions. It also synchronizes early
and late offscreen depth writes and includes both previous-frame colour writes
and compositor sampling before overwriting the SSAO output.

On 2026-11-01, the correction passed 149 deterministic assertions and 109 native
assertions per Minecraft target on the AMD Radeon RX 6950 XT, with zero validation
errors and correct 0.25/0.75 readback. Four supported depth formats were sampled;
X8_D24 and D24_S8 were explicitly skipped by the driver capability check. RGBA8
transitions were also exercised. The fragment-only negative control produced
native READ_AFTER_WRITE and WRITE_AFTER_READ hazards. Detailed comparisons are in
`build/vulkan-image-validation/run-0ql85cbo/report.json` and final standalone logs
under `build/vulkan-ssao-*.log`.

This suite does **not** run Minecraft scenes, bind actual Minecraft descriptors,
execute the full SSAO algorithm, compare screenshots, or benchmark rendering. Native
transition success is therefore only one part of acceptance. The report explicitly
leaves game/descriptor/visual equivalence unexecuted. Game-level validation must
still check actual attachment/lightmap layouts, SSAO, transparency, resizing,
resource reloads and reconnects; performance should be measured without validation
layers enabled.
