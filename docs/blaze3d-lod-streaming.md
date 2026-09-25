# Blaze3D LOD streaming optimization

The mesh format, CPU, and memory improvements are described in
[quad instancing](blaze3d-quad-instancing.md), including their validation scope.

## Diagnosis and implementation

The original scheduler imposed global barriers between distance rings. A nearby
branch waiting for models, memory, or uploads prevented distant work from starting,
even when capacity was available. Prepared distant results returned to the RAM
cache while waiting. Coarsening also changed drawable keys immediately, without
retaining descendants until their replacement parent was ready.

Implemented changes:

1. **Coverage and priorities.** Roots take priority to establish coverage in all
   directions. Nearby detail follows, with siblings sharing priority. Ring barriers
   and transfers back to RAM solely to wait for an earlier ring are removed. Scans
   resume even while other work remains pending. Newly published selections exclude
   nodes whose coverage is already ready from the construction queue.
2. **Coarsening transitions.** Previously drawn descendants remain active and
   protected from eviction until their parent is uploaded or confirmed empty.
   Subsequent selections retarget that protection to the new replacement.
   Replacements may use the existing atomic replacement reserve without increasing
   the memory limit.
3. **Prefetch.** Distance-based refinement begins 48 blocks before a boundary.
   Selection includes a screen margin using an X/Y clip scale of 0.85; drawing
   retains the exact frustum. Large selections publish a partial frontier every
   eight frames as well as when rings complete. Render-distance changes preserve
   compatible meshes.
4. **Upload budgets.** The configurable mesh count and staging/VRAM limits remain.
   Additional limits of 8 MiB and 2 ms per frame govern starting new uploads. One
   initial mesh may exceed these limits to prevent starvation; a GPU allocation
   already in progress cannot be interrupted.
5. **Per-frame work.** Terrain and water reuse visibility results. `frameUploads`
   counts applied results, and diagnostics include `uploadMillis` and
   `coarseningFallbacks`.
6. **Missing data.** Missing revisions consistently use `Long.MIN_VALUE`. Previously,
   the reader returned `-1`, so the missing-data check never matched. Temporarily
   unavailable data preserves confirmed geometry; only a validated empty result
   can remove it.

## Automated validation

```sh
./gradlew build --offline
./gradlew verifyBlaze3dLodStreaming --offline
```

The streaming task is part of `check`. It compiles the policy and its tests without
Minecraft or native library dependencies. The original streaming suite contains
29 checks covering atomic replacement, independent branches, consecutive selection
changes, returning to fine detail, resident or empty parents, leaving render
range, cleanup, byte/time/count limits, oversized mesh progress, and prefetch
boundaries. The quad-instancing work adds further checks described in its document.
The build also verifies that the backend introduces no native graphics calls.

## Visual and performance comparison

These tests validate streaming policy, not final images or device performance.
No FPS, percentile, or in-game convergence measurements are included here.

Compare the previous and updated versions using the same imported world, position,
route, quality, resolution, and upload limit. Separate fresh sessions from repeated
runs with warm caches.

- Turn 180 degrees and back: inspect horizon appearance and mesh reconstruction.
- Cross several root-section boundaries at constant speed: record `ready`,
  `asyncPending`, `uploadReady`, `branchHandoffs`, and time to reach full detail.
- Enter and leave zoom, then change quality and distance while loading: check
  continuous coverage, water, and the absence of parent/child overlap.
- Repeat with `-Dvoxy.blaze3d.geometryBudgetMiB=256`: observe coverage retention,
  eviction, and quality reduction when the budget is reached.
- Repeat during terrain import: unavailable sections must not replace valid meshes
  with empty results.

Use `/voxyLodDebug` for counters and `/toggleVoxyProfiler` for slow-frame samples.
The current profiler only logs frames of at least 50 ms and does not provide a
percentile histogram. Capture complete frame times with a session profiler.

Prefetch margins can increase resident geometry and trigger earlier quality
reduction on constrained devices. If the budget falls below active geometry and
no replacement fits, existing coverage remains until capacity is available.
Transitions remain discrete per branch; these changes do not add geometry
interpolation or shader fades.
