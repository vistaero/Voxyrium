# Voxy Vulkan Backend

Voxy follows Minecraft's own graphics API: when MC 26.2 runs on its Vulkan
backend, Voxy renders through Vulkan; when MC is on OpenGL, Voxy uses its
OpenGL (MDIC) backend. There is no user-facing toggle and no fallback either
way — a GL context cannot exist in a MC-on-Vulkan process, so "falling back to
OpenGL" is impossible, and forcing a cross-API split is incoherent with the
identical-experience goal.

## Architecture

When MC is on Vulkan, Voxy adopts MC's own `VkDevice`/queue via the Blaze3D
adapter mixin (`MixinVulkanDevice` registers a `MinecraftVkHostAdapter` at
device init) and records all of its GPU work into MC's frame command buffer
from `MixinSodiumOpaqueVkFrame` (TAIL of `SodiumWorldRenderer.drawChunkLayer`
for the OPAQUE group) — right after Sodium's opaque terrain, render pass
closed, frame command buffer recording. Per frame:

1. **SETUP** (`VkCompositor`): clear Voxy's offscreen colour + D32S8
   depth-stencil (stencil=1); fullscreen pass copies MC's depth in
   (projection-transformed) writing stencil=0 where vanilla terrain exists.
2. **Opaque LOD terrain**: `vkCmdDrawIndexedIndirectCount`, stencil==1 test
   (draw calls generated last frame — same latency model as GL).
3. **HiZ pyramid** (`VkHiZ`, R32F mips, conservative REDUCTION) +
   `AsyncNodeManager` sync (`VkNodeGpuOps` scatter/multi-memcpy computes) +
   `VkNodeCleaner` + hierarchical traversal (`VkTraversal`: 12 flip-flop indirect
   dispatches, HiZ-tested, request readback via `VkDownloadStream`).
4. **Draw-call build** (`VkTerrainRenderer`): prep -> raster box cull
   (depth-only, early-fragment-test visibility writes) -> cmdgen (indirect
   dispatch) -> translucency prefix-sort + build.
5. Temporal + translucent draws.
6. **COMPOSITE**: alpha-blend into MC's colour/depth attachments (dynamic
   rendering, LOAD/STORE), fragment emits vanilla-space depth + env fog (fog
   params sourced from MC's `CameraRenderState.fogData`).
7. Streams tick (staging recycled on VkEvent frame retirement), model bakery
   tick (atlas mips via `vkCmdCopyBufferToImage`), render-distance tracking.

Shared with GL (zero duplication): `NodeManager`/`AsyncNodeManager`, mesh
generation, model bakery CPU pipeline, render-distance tracker, viewport
math, and all shader sources — the GLSL is single-source with
`#ifdef VOXY_VULKAN` guards (push constants replace default-block uniforms,
sampler bindings remapped to the unified VK namespace,
`gl_VertexID`/`gl_InstanceID`/`gl_BaseInstance` aliased, u16 cube indices
replace u8). Seams: `IDeviceBuffer`, `AbstractUploadStream`/
`AbstractDownloadStream` (backend-settable singletons), `INodeGpuOps`,
`INodeCleaner`, `IBasicGeometryData`, `IModelStore`, `IAtlasTextureReader`.

## MoltenVK / macOS

- `drawIndirectCount` is a 1.2 *feature*, not implied by `apiVersion` —
  MoltenVK reports 1.2 without it. `VulkanContext.hasDrawIndirectCount` is
  queried and `VkTerrainRenderer.renderTerrain` branches on it: the
  fixed-count fallback issues `vkCmdDrawIndexedIndirect` with a clamped
  `maxDrawCount` and zeroes the three `drawCallBuffer` slices (opaque /
  temporal / translucent) before cmdgen so stale trailing slots never read as
  ghost draws.
- The fixed-count budget tracks the last-read real per-pass draw count (async
  readback via `VkDownloadStream`, no stall), so looking at the sky actually
  reduces encoded Metal draws (MoltenVK emulates multi-draw-indirect as one
  Metal draw per slot — a view-independent cap encoded ~sectionCount*6.4
  no-op draws per frame and hid all culling wins).
- `VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT` + a `VkImageFormatListCreateInfo` is
  used on the D32S8 offscreen depth so a depth-only aspect view can alias the
  packed image (without it MoltenVK may synthesise a separate staging texture
  for depth-only sampling).
- macOS natives for `lwjgl-lmdb` and `lwjgl-zstd` must be bundled or section
  storage throws `UnsatisfiedLinkError` on save and no geometry is persisted
  (the bound-renderer AABBs leak through as the only visible geometry).

## Invariants

- GL/MDIC byte-identical when MC is on GL (seam refactors are lazy-init only).
- VK strictly follows MC's own API; never falls back to GL under
  MC-on-Vulkan; gates no mod off as "GL-only".
- No GL classloads can occur on the VK path — `VoxyClient.initVoxyClient`
  branches on `MinecraftVkHost.isMinecraftOnVulkan()` before the first GL
  touch (`Capabilities`'s `<clinit>` runs `GL.getCapabilities()` and throws
  with no GL context). The streams, the shared index buffer, and the atlas
  readback are lazily backend-selected, so their GL implementations never
  classload on the VK path.
- `VkRenderCore.shutdown()` is idempotent and device-alive-gated
  (`MinecraftVkHost.get() != null` skips GPU teardown if MC already tore its
  device down on full-game exit). CPU stop (node/gen thread joins, callback
  detach, world `releaseRef`) always runs. `modelService.shutdown()` owns the
  `VkModelStore` lifetime (single `vkDestroySampler`) and runs after
  `frameCtx.waitIdleRetireAll()`.

## Feature parity

VK visual output matches GL: depth-space transform in setup/composite,
stencil mask correctness, fog ramp, lightmap sampling (vertex-stage), model
atlas mips, SSAO (`VkSSAO`), depth-bound culling (`VkBoundRenderer`), and
view-bob tracking (the frame hook feeds Sodium's bobbed `ChunkRenderMatrices`
+ camera offset into `renderFrame`).

No-op by design on the VK path: FREX integration, shader printf debugging,
GPU-timing markers (all GL-debug-only paths).

## A/B comparison logging

`-Dvoxy.cmplog=<path>` makes both backends emit the same per-frame semantic
quantities (section/geometry counts, traversal request counts) as
tab-separated records via `CmpLog`. For a stationary camera the values
converge to identical integers when the VK translation is faithful, so a
script can flag any divergence without the rounding noise of a pixel diff.
Every method is a no-op when the property is unset.