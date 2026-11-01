# Vulkan allocation failure regression

Run the deterministic regression for either supported target:

```powershell
.\gradlew.bat verifyVulkanMemory --offline '-Pminecraft_version=26.2'
.\gradlew.bat verifyVulkanMemory --offline '-Pminecraft_version=26.3'
```

`check` also runs `verifyVulkanMemory`, including builds made by
`scripts/Build-CompatibilityMatrix.py`.

The test executes the production `VkBuffer`, `VkSectionGeometryData`,
`VkFrameCtx`, `VkUtil` and `TrackedObject` classes. Only the Vulkan driver,
device wrapper and Minecraft logging/configuration boundary are replaced.
Real LWJGL structures and native host-memory helpers remain in use. Test
doubles are confined to the `vulkanMemoryTest` source set and are never packaged.

Nine scenarios cover failed buffer creation, memory-type selection,
allocation and binding; deferred destruction; repeated 2 GiB -> 1 GiB
geometry fallback; exhausted geometry allocation; failed initialization;
and context teardown with allocations left by an aborted subsystem.
Each scenario checks live native handles, allocated bytes and Voxy's counters.
The normal destruction scenario also checks that resources survive until
retirement and repeated context teardown does not destroy them twice.

## Results on 2026-11-01

Against the original `a7323721` sources, 7 of 9 scenarios failed. Examples:

| Scenario | Resources left pending |
| --- | --- |
| 100 failed memory allocations | 100 buffer handles |
| 100 failed memory bindings | 100 buffers and 100 allocations (409,600 bytes) |
| 20 geometry fallbacks | 20 failed-attempt buffer handles |
| Failed geometry initialization | 2 buffers and 268,435,968 allocated bytes |
| 20 aborted subsystem initializations | 80 buffers and 60 allocations |

The byte counts are simulated driver bookkeeping, not physical GPU allocations.

The fix destroys resources acquired by a failed buffer constructor, releases
geometry allocations when initialization aborts, and retains buffer ownership
in the frame context until explicit release or final teardown. Render-core
initialization rolls back completed components, stops CPU services, clears
callbacks/singletons and drains the context after failure.

After the fix, all 9 scenarios passed for both Minecraft 26.2 and 26.3 with
zero remaining buffers or allocated bytes. Both complete compatibility builds
and the existing project checks also passed.

Artifacts were built from an isolated snapshot of the base plus these changes;
the main checkout was not committed. This avoids the matrix script building
the older branch tip while the fix is still uncommitted. Original evidence is
in `build/vulkan-memory/original-final-test.log`. Complete build logs and
installation hashes are under `compatibility-builds/vulkan-memory-20261101/`.

This proves the allocation/teardown defects and their regression coverage.
It does not measure a live GPU or establish that every cause of high RAM/VRAM
usage in issues #1 and #2 is resolved. A long gameplay/reconnection session
on the reporting hardware remains a separate validation step.
