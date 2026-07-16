# Voxy Vulkan Backend — branch `vulkan-backend`, rebased on dev @ MC 26.2

## Grounded context (verified July 2026)
Vanilla MC Java 26.2 ("Chaos Cubed") ships an experimental Vulkan renderer:
opt-in Graphics API setting (Default / Prefer Vulkan / Prefer OpenGL), requires
Vulkan 1.2 + dynamic rendering + push descriptors, auto-fallback ladder to GL,
prefers discrete GPUs, and runs on macOS through MoltenVK officially. Iris is
GL-only (its VK successor "Aperture" is in development); Sodium is GL-only.
Therefore gating Iris/Sodium OFF on the VK path is correct, not a limitation.

## Two VK modes
1. HYBRID (Windows/Linux, MC on OpenGL): GL keeps traversal/culling/cmdgen
   compute bit-exact; buffers are VK-allocated + GL-imported
   (VK_KHR_external_memory <-> GL_EXT_memory_object); VK does the draws;
   GL composites. IMPLEMENTED (guarded off until geometry-SSBO sharing lands).
   IMPOSSIBLE ON macOS: MoltenVK has no external_memory_fd and Apple GL is 4.1.
2. PURE-VK / HOST MODE (all OSes incl. macOS, MC on "Prefer Vulkan"): Voxy
   adopts Minecraft's own VkDevice/queue/frame via the IVkHost seam and records
   LOD passes into MC's frame; no OpenGL anywhere. This is THE Mac path and the
   end-state on every platform. ARCHITECTURE LANDED; adapter mixin pending.

## macOS blockers — status after this commit set
| Blocker | Status |
|---|---|
| VK_KHR_portability_enumeration missing at instance creation (MoltenVK loader hides devices without it) | FIXED |
| VK_KHR_portability_subset must be enabled when exposed | FIXED |
| drawIndirectCount incorrectly inferred from apiVersion (MoltenVK reports 1.2 WITHOUT this feature) | FIXED — proper VkPhysicalDeviceVulkan12Features query |
| Hard dependency on draw-indirect-count | FIXED — fixed-count vkCmdDrawIndexedIndirect fallback (cmdgen zero-fills unused slots; instanceCount=0 draws are no-ops) |
| GL-interop unavailable on Metal | BY DESIGN — pure-VK host mode is the Mac path; gate refuses hybrid on macOS with a clear log |
| shaderc natives | already bundled (macos + macos-arm64) |
| Residual risk | MoltenVK SSBO/vertex-pulling perf & any portability-subset gaps in Voxy's shaders — only measurable on Apple hardware |

## What is REAL in code vs what REMAINS
Done (unverified — this sandbox cannot compile [maven blocked] or render [no GPU]):
seam/IRenderList; config toggle + capability + Iris + macOS gates (GL default);
private-device VK context w/ portability + discrete-GPU preference; interop
primitives; shaderc bridge; VK opaque pipeline + indirect draws + fallback;
IVkHost + MinecraftVkHost registry; VkComputePipeline for the compute ports.

Remaining for "complete, identical-experience" VK (in order):
1. `./gradlew build` fixups (authored offline against LWJGL 3.4.1 VK bindings).
2. Blaze3D-VK adapter mixin implementing IVkHost — REQUIRES the real 26.2
   mappings/jar; targets deliberately not guessed. Note MC's VK renderer runs on
   a dedicated render thread: the adapter must hand Voxy a command buffer at a
   defined sync point, this is the trickiest integration detail.
3. IDeviceBuffer abstraction under GlBuffer so geometry/metadata/ModelStore
   allocate VkBuffers natively in host mode (kills the last GL dependency).
4. Compute ports via VkComputePipeline (prep/cull/prefix/cmdgen, then the
   hierarchical traversal); occlusion vs MC's VK depth via IVkHost views.
   Switch graphics pipeline to dynamic rendering (host requires the ext anyway).
5. Translucents, temporal, SSAO parity; then perf work (persistent descriptor
   sets, device-generated-commands/metal ICBs where available).
6. Validation matrix: NVIDIA+AMD Windows/Linux (hybrid + host), Apple Silicon
   (host only). Parity = pixel-compare vs GL on same seed/camera; perf = frame
   times at 64/128/256 render distance.

## Invariants
GL/MDIC untouched and default; VK strictly behind config + capability gates;
Iris/Sodium gated out on VK, untouched on GL.
