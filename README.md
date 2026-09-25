# Voxyrium

A fork of Voxy with native OpenGL/Vulkan renderers and an experimental Blaze3D compatibility renderer. Select the renderer from Voxy's Rendering settings.

## Changes

* **Full Vulkan support**, based on [MCRcortex/voxy#614](https://github.com/MCRcortex/voxy/pull/614) by `cochcoder`.
* **Intel Mac support** in addition to Apple Silicon.
* Additional **MoltenVK-specific fixes**.
* Fixed gaps between blocks in **flowing water and flowing lava** in Voxy chunks.
* Added an alternative **Blaze3D-only renderer**, written from scratch without direct OpenGL or Vulkan calls.

## Blaze3D Renderer (Experimental)

The alternative renderer uses only Minecraft 26.2's public **Blaze3D API**. It should therefore run on any device capable of Minecraft's minimum graphics requirements, including **OpenGL 3.3** hardware.

Its goal is to reproduce Voxy's rendering and performance as closely as possible while remaining completely independent of the underlying graphics API.

There is, however, a fundamental performance ceiling: Blaze3D does not expose enough of the GPU to reproduce Voxy's native GPU-driven pipeline.

| GPU feature                       | Blaze3D 26.2 | Limitation                                                            |
| --------------------------------- | ------------ | --------------------------------------------------------------------- |
| Vertex/index/uniform/copy buffers | ✅            | Fully usable                                                          |
| Indirect command buffers          | ⚠️           | `drawIndexedIndirect(...)`, but one command per call                  |
| Multi-draw indirect               | ❌            | No `glMultiDraw*Indirect` / Vulkan equivalent                         |
| GPU-generated draw count          | ❌            | No `glMultiDraw*IndirectCount` / `vkCmdDraw*IndirectCount` equivalent |
| Compute shaders                   | ❌            | No compute pipelines, `dispatch` or `dispatchIndirect`                |
| SSBO / storage buffers            | ❌            | No public shader-storage buffer abstraction                           |
| Storage images                    | ❌            | No image load/store abstraction                                       |
| Explicit synchronization          | ❌            | No programmable compute/storage/indirect barriers                     |
| GPU-driven Hi-Z traversal         | ❌            | Hi-Z can be raster-generated, but cannot drive a compute traversal    |

Consequently, the Blaze3D renderer cannot reproduce several techniques available to Voxy's native **OpenGL 4.6** and **Vulkan** backends:

* GPU compute culling and traversal
* GPU-generated indirect draw lists
* Multi-draw indirect submission
* GPU-generated draw counts
* SSBO-based scene/visibility data
* Storage-image compute workloads
* Explicit synchronization between compute, transfer and indirect rendering stages

These limitations require more work to remain on the CPU and/or more individual draw submissions.

The Blaze3D backend should therefore be considered a **portable compatibility renderer**, not a replacement for the native backends. It aims to approach native Voxy performance and visual fidelity as closely as Blaze3D permits.

## Rendering Backends

| Backend     | Requirements                               | GPU-driven capabilities | Expected performance          |
| ----------- | ------------------------------------------ | ----------------------- | ----------------------------- |
| **OpenGL**  | Voxy native requirements                   | Full                    | Best                          |
| **Vulkan**  | Vulkan / MoltenVK                          | Full                    | Best                          |
| **Blaze3D** | Minecraft-compatible GPU, including GL 3.3 | Limited by Blaze3D      | Lower, compatibility-oriented |

## Compatibility Builds and Testing

The scripts in [`scripts/`](scripts/) make it easier to backport new game features continuously to the Minecraft **1.20.x and 1.21.x** branches. They build the selected branch/version combinations, prepare matching Fabric test profiles, and keep the runtime dependencies aligned so changes can be checked across the supported versions as they are developed.

### Getting started

Run the interactive menu from the repository root:

```text
Windows: scripts\Run-Scripts-Windows.bat
macOS:   scripts/Run-Scripts-macOS.command
Other:   python3 scripts/Run-Scripts.py
```

The menu launches the available capitalized Python scripts. Python 3 and Git are required; compilation also requires the appropriate JDKs (Java 17 for older 1.20.x targets and Java 21 for newer targets). `InstallJdkVersions.py` can install the toolchains used by the compatibility builds. The build/profile scripts can also be run directly with Python; use `--help` to see their options.

### Main workflows

| Script | Purpose |
| --- | --- |
| `Build-CompatibilityMatrix.py` | Main workflow. Selects `compile` to build the chosen versions, `compile-online` to also prepare toolchains and refresh dependencies, `profiles` to create/update test profiles, `dependencies` to update their mods and shaders, or `jdks` to install toolchains. Without `--action`, it offers an interactive choice. |
| `CreateUpdateProfiles.py` | Creates or refreshes launcher profiles and their version-specific runtime setup; can also build and install artifacts as part of profile preparation. |
| `InstallJdkVersions.py` | Installs required Java toolchains for the compatibility builds. |
| `UpdateProfileDependencies.py` | Updates runtime mods and shader packs for existing test profiles. |
| `Find-Latest-Iris-Sodium.py` | Finds the newest mutually compatible Iris/Sodium pair for each 1.21.x release and writes the result to `latest-iris-sodium-1.21.txt`. This checks published metadata and manifests, not in-game behavior. |
| `Start-MinecraftOffline.py` | Launches one or more prepared `voxy-test-*` profiles without Minecraft account authentication. |
| `ToggleVoxyFromProfiles.py` | Enables or disables Voxy in all test profiles; `--dry-run` previews the file changes. |
| `test_runtime_updater_json_compat.py` | Runs focused Python unit tests for dependency metadata and profile-updater behavior; it does not launch Minecraft. |

> **Important:** `Build-CompatibilityMatrix.py` builds each target from a separate Git worktree created at the selected branch's current commit. Commit every source change you want included in the build to that branch before running it. Uncommitted edits and untracked files in your current working directory are not copied into the build worktree, so they will be missing from the resulting artifacts.

For example, to build and install Voxy artifacts for a focused set of versions, run:

```bash
python scripts/Build-CompatibilityMatrix.py --action compile --versions 1.20.1 1.20.6 1.21.1 1.21.11
```

Then update the runtime mods and shaders for those versions, and run the unit tests with:

```bash
python scripts/UpdateProfileDependencies.py --versions 1.20.1 1.20.6 1.21.1 1.21.11
python -m unittest scripts/test_runtime_updater_json_compat.py
```

Use `--branches` when you want to select source branches explicitly, and `--minecraft-directory` or `--profiles-directory` to use a non-default Minecraft installation/profile location. By default, build artifacts and logs are stored under `compatibility-builds/`, while launcher test profiles are kept in the Minecraft directory's `voxy-test-profiles/` folder.

Compilation and the Python unit tests provide build-level checks. To check rendering and runtime compatibility, launch the relevant prepared profile with `Start-MinecraftOffline.py` and test it in game; a successful build or metadata match alone does not prove in-game compatibility.

## Credits

Original project: [MCRcortex/voxy](https://github.com/MCRcortex/voxy)

Vulkan support is based on [PR #614](https://github.com/MCRcortex/voxy/pull/614) by `cochcoder`.

See [LICENSE.md](LICENSE.md) for licensing information.

