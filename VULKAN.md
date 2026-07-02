# Voxy Vulkan Backend (branch: vulkan-backend, base: 2622 / MC 26.2)

## Model (DH-style backend swap)
MC 26.2 is a GL client, so "fully Vulkan" for a mod means: Voxy owns a private
VkInstance/VkDevice and renders LODs offscreen; resources cross the API boundary
via VK_KHR_external_memory + GL_EXT_memory_object and exported semaphores; GL
composites the result into MC's framebuffer. Toggle: `renderBackend` in the Voxy
config ("opengl" default | "vulkan"). VK engages ONLY if a device with the interop
extensions exists AND no Iris shaderpack is active; otherwise silent GL fallback.

## Phase-1 hybrid split (deliberate)
- GL, unchanged & bit-exact: hierarchical traversal, prep/cull(raster occlusion vs
  MC depth)/prefixsum/cmdgen compute, Sodium/Iris integration.
- VK: opaque terrain via vkCmdDrawIndexedIndirectCountKHR over shared draw buffers,
  into shared RGBA8/D32 targets. Same GLSL sources, shaderc->SPIR-V at runtime with
  VOXY_VULKAN defined.
- The raster occlusion cull tests against MC's GL depth buffer -> it must stay GL
  until/unless MC depth itself is shared; this is why phase-1 is a hybrid, not a
  design shortcut.

## Status — read this before flipping the toggle
| Piece | State |
|---|---|
| Seam (IRenderList, 8-line diff, MDIC untouched via covariant returns) | done, needs compile |
| Config toggle + capability gate + Iris gate + GL default | done, needs compile |
| VK context/device/queue + interop ext selection (win32/fd) | done, UNTESTED on hardware |
| SharedBuffer/SharedImage/SharedSemaphore interop | done, UNTESTED on hardware |
| shaderc GLSL->SPIR-V bridge | done; Voxy's GLSL will need set/binding fixups for Vulkan semantics — expect iteration |
| VK opaque draw pass (mirrors MDIC offsets/strides/clamps) | recorded+submitted, but **guarded OFF**: geometry/metadata/ModelStore SSBOs are plain GlBuffers; until they allocate via SharedBuffer when VK is active, renderOpaque logs once and draws nothing rather than corrupt |
| GL composite of shared color/depth into MC framebuffer | NOT YET (next step with geometry sharing) |
| Translucent / temporal / SSAO on VK | deferred, stubbed no-op |
| Descriptor sets binding shared SSBOs to the VK pipeline | pipeline layout is a placeholder pending geometry sharing |

## Next steps, in order
1. Compile (`./gradlew build`) — this tree was authored in an offline sandbox
   (maven blocked): expect LWJGL VK binding signature fixups.
2. Thread SharedBuffer allocation through BasicSectionGeometryManager + ModelStore
   behind `VulkanBackend.shouldUseVulkan(...)`; build the descriptor set layout;
   flip `geometryShared = true`.
3. GL composite pass (fullscreen quad sampling shared color+depth, writes
   gl_FragDepth, depth-tested vs MC) + glDone/vkDone signal points around cmdgen.
4. Validate on NVIDIA Linux/Windows first (best GL_EXT_memory_object support);
   run scripts/check_voxy_shader_contracts.sh (not in this repo — external) on the
   unchanged GL shaders to confirm bit-exactness.
5. Then translucents, temporal, and a VK-native traversal (phase-2, drops hybrid).

## Invariants preserved
GL/MDIC path byte-identical in behavior (seam is type-plumbing only); GL remains
default; Iris+Sodium untouched on GL and explicitly gated on VK.
