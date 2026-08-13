# Voxyrium

A fork of Voxy with native OpenGL/Vulkan renderers and an experimental Blaze3D compatibility renderer. Select the renderer from Voxy's Rendering settings; Auto prefers a supported native backend and falls back to Blaze3D.

## Changes

* **Full Vulkan support**, based on [MCRcortex/voxy#614](https://github.com/MCRcortex/voxy/pull/614) by `cochcoder`.
* **Intel Mac support** in addition to Apple Silicon.
* Additional **MoltenVK-specific fixes**.
* Fixed gaps between blocks in **flowing water and flowing lava** in Voxy chunks.
* Added an alternative **Blaze3D-only renderer**, written from scratch without direct OpenGL or Vulkan calls.

## Blaze3D Renderer

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

## Credits

Original project: [MCRcortex/voxy](https://github.com/MCRcortex/voxy)

Vulkan support is based on [PR #614](https://github.com/MCRcortex/voxy/pull/614) by `cochcoder`.

See [LICENSE.md](LICENSE.md) for licensing information.

