package me.cortex.voxy.client.core.backend.blaze3d;

import java.util.List;
import java.util.Set;
import java.util.function.LongUnaryOperator;

/** Device-free regression tests. Run with Gradle's verifyBlaze3dLodStreaming task. */
public final class Blaze3dLodStreamingTest {
    // A small octree: root 1, children 8..15, grandchildren 64..127.
    private static final LongUnaryOperator PARENT = key -> key < 8 ? key : key / 8;
    private static int checks;

    public static void main(String[] args) {
        coarseningKeepsCoverageUntilUpload();
        consecutiveSelectionsKeepCoverage();
        cachedAndEmptyReplacements();
        independentBranchesAndReset();
        uploadBudgetsMakeBoundedProgress();
        prefetchAndBuildOrder();
        intermediateFallbacks();
        System.out.println("Blaze3D streaming: " + checks + " checks passed.");
        Blaze3dQuadEncoderTest.run();
    }

    private static void intermediateFallbacks() {
        check(Blaze3dLodStreaming.skipIntermediate(3, 4, false, false, true, 0),
                "Near L3 can be virtual while a resident root covers its descendants");
        check(Blaze3dLodStreaming.skipIntermediate(2, 4, false, false, true, 512),
                "Covered L2 may also be bypassed within the near region");
        check(!Blaze3dLodStreaming.skipIntermediate(4, 4, false, false, false, 0),
                "Cold-start roots remain the coverage safety net");
        check(!Blaze3dLodStreaming.skipIntermediate(1, 4, false, false, true, 0),
                "L1 keeps progressive coverage before L0 is ready");
        check(!Blaze3dLodStreaming.skipIntermediate(2, 4, true, false, true, 0),
                "Selected leaves and coarsening targets must always be built");
        check(!Blaze3dLodStreaming.skipIntermediate(2, 4, false, true, true, 0),
                "Resident intermediate meshes stay eligible for dirty updates");
        check(!Blaze3dLodStreaming.skipIntermediate(3, 4, false, false, false, 0),
                "Never bypass the only available fallback in an uncovered branch");
        check(!Blaze3dLodStreaming.skipIntermediate(2, 4, false, false, true, 513),
                "Far terrain keeps ordinary progressive refinement");
    }

    private static void coarseningKeepsCoverageUntilUpload() {
        var coverage = new Blaze3dLodStreaming.CoarseningCoverage();
        coverage.retarget(List.of(64L, 65L, 72L), Set.of(8L, 9L), Set.of(), PARENT);
        check(coverage.size() == 3, "All old descendants remain drawable during coarsening");
        check(coverage.isReplacement(8L), "The replacement may use the atomic allocation reserve");
        coverage.ready(64L);
        check(coverage.size() == 3, "A descendant upload cannot retire its siblings");
        coverage.ready(8L);
        check(!coverage.retains(64L) && !coverage.retains(65L), "One parent replaces its entire branch atomically");
        check(coverage.retains(72L), "An unrelated pending branch stays visible");
        check(!coverage.isReplacement(8L), "Completed replacements release their reserve entitlement");
    }

    private static void consecutiveSelectionsKeepCoverage() {
        var coverage = new Blaze3dLodStreaming.CoarseningCoverage();
        coverage.retarget(List.of(64L, 65L), Set.of(8L), Set.of(), PARENT);
        coverage.retarget(List.of(64L, 65L), Set.of(1L), Set.of(), PARENT);
        coverage.ready(8L);
        check(coverage.size() == 2, "An obsolete target completing cannot remove current coverage");
        coverage.ready(1L);
        check(coverage.size() == 0, "The new target releases all retained descendants");

        coverage.retarget(List.of(64L, 65L), Set.of(8L), Set.of(), PARENT);
        coverage.retarget(List.of(64L, 65L), Set.of(64L, 65L), Set.of(64L, 65L), PARENT);
        check(coverage.size() == 0, "Reversing a transition makes fine meshes selected again");
    }

    private static void cachedAndEmptyReplacements() {
        var coverage = new Blaze3dLodStreaming.CoarseningCoverage();
        coverage.retarget(List.of(64L, 65L), Set.of(8L), Set.of(8L), PARENT);
        check(coverage.size() == 0, "A resident coarse mesh replaces fine geometry immediately without overlap");
        coverage.retarget(List.of(64L), Set.of(8L), Set.of(), PARENT);
        coverage.ready(8L); // The renderer also calls ready for a confirmed empty mesh.
        check(coverage.size() == 0, "Confirmed empty geometry removes stale descendants");
        coverage.retarget(List.of(1L), Set.of(8L), Set.of(), PARENT);
        check(coverage.size() == 0, "Refinement uses the existing parent handoff, not coarsening pins");
    }

    private static void independentBranchesAndReset() {
        var coverage = new Blaze3dLodStreaming.CoarseningCoverage();
        coverage.retarget(List.of(64L, 128L), Set.of(8L), Set.of(), PARENT);
        check(coverage.retains(64L) && !coverage.retains(128L), "Branches leaving render distance are not pinned");
        coverage.clear();
        check(coverage.size() == 0 && !coverage.isReplacement(8L), "World changes clear all coverage ownership");
        coverage.retarget(List.of(64L), Set.of(), Set.of(), PARENT);
        check(coverage.size() == 0, "Empty selections terminate ancestry traversal");
    }

    private static void uploadBudgetsMakeBoundedProgress() {
        var budget = new Blaze3dLodStreaming.UploadBudget(8, 100, 2_000, 10_000);
        check(budget.canUpload(250, 20_000), "One oversized mesh still progresses after setup overruns");
        budget.record(250);
        check(!budget.canUpload(1, 20_001), "Oversized first upload stops further uploads this frame");

        budget = new Blaze3dLodStreaming.UploadBudget(8, 100, 2_000, 10_000);
        budget.record(60);
        check(budget.canUpload(40, 11_999), "Exact byte limit is admissible before deadline");
        check(!budget.canUpload(41, 11_999), "Byte limit prevents a burst of large uploads");
        check(!budget.canUpload(1, 12_000), "Time limit prevents another allocation after the deadline");
        budget = new Blaze3dLodStreaming.UploadBudget(1, 100, 2_000, 10_000);
        budget.record(0);
        check(!budget.canUpload(0, 10_001), "Count limits also bound empty mesh processing");
        budget = new Blaze3dLodStreaming.UploadBudget(8, 100, 2_000, 30_000);
        check(budget.canUpload(250, 30_001), "Deferred oversized meshes make progress next frame");
    }

    private static void prefetchAndBuildOrder() {
        check(Blaze3dLodStreaming.buildPriority(4, 4, 4)
                        < Blaze3dLodStreaming.buildPriority(0, 4, 0),
                "Distant root coverage precedes near fine detail");
        check(Blaze3dLodStreaming.buildPriority(2, 4, 0)
                        < Blaze3dLodStreaming.buildPriority(2, 4, 2),
                "Near refinement precedes distant refinement");
        check(Blaze3dLodStreaming.selectionRing(0, 256, 4) == 0, "Camera-containing nodes stay in the near ring");
        check(Blaze3dLodStreaming.selectionRing(280, 256, 4) == 0, "Approaching a ring refines before crossing its boundary");
        check(Blaze3dLodStreaming.selectionRing(304, 256, 4) == 1, "Prefetch has a bounded spatial extent");
        check(Blaze3dLodStreaming.selectionRing(1e9, 256, 4) == 4, "Far selection never exceeds the root level");
        int previous = 0;
        for (int distance = 0; distance < 10000; distance++) {
            int ring = Blaze3dLodStreaming.selectionRing(distance, 256, 4);
            if (ring < previous || ring > 4) throw new AssertionError("Non-monotonic ring selection");
            previous = ring;
        }
        check(true, "Distance selection stays monotonic across every boundary");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
