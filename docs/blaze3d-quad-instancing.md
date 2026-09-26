# Blaze3D quad instancing and CPU optimization

## Design

The previous mesh format already omitted normals. It stored position, UV, model,
flags, tint, and directional shading, so there were no smooth normals to remove.
Its avoidable cost was expanding each Cortex quad into four CPU vertices before
uploading it.

The new format uses one 32-byte record per quad. Small shared buffers provide the
four corners and six indices. The vertex shader reconstructs positions, UVs, and
fluid heights. It requires no SSBOs, compute shaders, graphics extensions, or extra
model-atlas reads: the record carries the necessary metadata.

The API was checked against the installed Minecraft 26.2 classes: vertex formats
with a step rate of one, integer attributes, and two vertex bindings. Corners use
an explicit attribute, avoiding reliance on vertex-ID translation between backends.

| Geometry cost | Before | After |
| --- | ---: | ---: |
| Bytes per quad in staging, RAM cache, and VRAM | 112 | 32 |
| Bytes for one million quads | 112 MB | 32 MB |
| Allocations/uploads per section containing opaque geometry and water | 2 | 1 |
| Shared index buffer | 1,572,864 bytes | 24 bytes |
| Shared corner buffer | None | 32 bytes |

The geometry payload is reduced by approximately 71.4%. This does not imply the
same reduction in total VRAM or the same increase in FPS: atlases and render
targets still exist, and the shader performs more arithmetic. Draw calls per
section and pass remain unchanged. Actual performance depends on the balance
between CPU work, transfers, vertices, and fragments on each GPU.

## Memory and CPU

- Residency, staging, and cache budgets are preserved. The saved space can retain
  up to 3.5 times as many quads within the same geometry capacity. Configured render
  distance and quality are not increased automatically.
- Working-set estimates account for the compact format while keeping the previous
  headroom for caches and transitions.
- Opaque geometry and water share one buffer per section, with separate slices
  computed when the mesh is created. Water sorting remains per section.
- Packing computes the origin once per section and reuses consecutive lookups for
  the same model and biome.
- Worker-prepared textures are stored directly in native memory, eliminating the
  intermediate `byte[]` and its later copy/allocation on the render thread. Queued
  buffers are freed on upload or world shutdown, with publication synchronized
  against shutdown. Texture uploads are limited to 64 models and 2 ms per frame,
  always allowing an initial model to make progress.
- The RAM cache uses access-order LRU without scanning the entire cache for the
  farthest mesh on every eviction. VRAM coverage protection remains independent.
- Culling visits active keys rather than the entire GPU cache. The water list is
  reused instead of allocating a new stream result each frame.
- Change notifications retain priority. Periodic neighborhood fingerprint audits
  decrease from 32 to eight per frame.
- Opaque fragments without cutout or conditional tint skip the mip-zero read.
  Other fragments share that read between alpha and tint classification.

## Intermediate LODs

New L2/L3 meshes within 512 blocks are skipped when an active resident ancestor
already covers the branch. Intermediate nodes remain in coverage accounting and
become ready once all required children are ready. They do not need their own
mesh to complete that handoff.

L4 remains the initial coverage fallback when no alternative exists, and L1 remains
the final fallback before L0. Selected leaves, coarsening replacements, and updates
to resident meshes are never skipped. This avoids building every quality level
when coverage already exists while preserving the fallback that prevents pop-in.

## Validation

Code, the binary contract, and local API signatures were reviewed during
implementation. Compilation, automated execution, and in-game validation of the
quad-instancing changes were left to the maintainer. The prepared checks run with:

```sh
./gradlew verifyBlaze3dLodStreaming --offline
./gradlew build --offline
```

The suite includes the original 29 checks, eight cases for safely skipping
intermediate LODs, and twelve binary-format checks, including 10,000 randomized
quad round trips. These verify offsets, size, tint, lighting, coordinates, LOD,
and fields crossing 32-bit word boundaries. They do not execute or certify the
shader.

In-game checks should focus on sloped water/lava, partial faces, transparency,
biome tint, negative coordinates, zoom, turns, and memory pressure. Compare both
initial loading and stationary scenes with all meshes resident to distinguish
streaming improvements from steady-state drawing cost.

`/voxyLodDebug` adds `quadBytes=32`, `bypassedIntermediate`, and
`bufferCreateMillis`. The last counter measures CPU time inside the buffer
creation/upload call, not GPU transfer time. `uploadMillis` also includes result
validation and management. Compare both alongside `frameUploads`, queue sizes,
geometry bytes, and frame times.

If queues are ready but repeatedly hit the eight-mesh limit, compare
`/setVoxyLodUploadsPerFrame 8` and `/setVoxyLodUploadsPerFrame 16` along the same
route. Time and byte limits still apply. The default count has not been increased
without first measuring the cost in a game session.
