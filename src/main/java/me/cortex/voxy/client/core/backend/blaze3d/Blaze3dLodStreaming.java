package me.cortex.voxy.client.core.backend.blaze3d;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongUnaryOperator;

/** Render-thread streaming policy, independent of the graphics device. */
final class Blaze3dLodStreaming {
    private Blaze3dLodStreaming() {}

    static int buildPriority(int level, int rootLevel, int ring) {
        // Establish an omnidirectional safety net before refining individual rings.
        return level == rootLevel ? 0 : ring + 1;
    }

    static int selectionRing(double distance, double width, int maxLevel) {
        // Start the next refinement 48 blocks before its distance boundary is reached.
        distance = Math.max(0.0, distance - 48.0);
        int ring = 0;
        while (ring < maxLevel && distance >= width) {
            ring++;
            width *= 2.0;
        }
        return ring;
    }

    static boolean skipIntermediate(int level, int rootLevel, boolean selected,
                                    boolean resident, boolean covered, double distance) {
        // Keep roots as the cold-start safety net and L1 as the last coarse fallback.
        // Virtual L2/L3 nodes still participate in child-coverage accounting.
        return level >= 2 && level < rootLevel && !selected && !resident
                && covered && distance <= 512.0;
    }

    static final class UploadBudget {
        private final int maxCount;
        private final long maxBytes;
        private final long maxNanos;
        private final long startedNanos;
        private int count;
        private long bytes;

        UploadBudget(int maxCount, long maxBytes, long maxNanos, long startedNanos) {
            this.maxCount = maxCount;
            this.maxBytes = maxBytes;
            this.maxNanos = maxNanos;
            this.startedNanos = startedNanos;
        }

        boolean canUpload(long nextBytes, long now) {
            // A single oversized mesh must still make progress. GPU allocations cannot be
            // interrupted; the time limit prevents starting another one after an overrun.
            return count < maxCount && (count == 0
                    || (nextBytes <= maxBytes - bytes && now - startedNanos < maxNanos));
        }

        void record(long uploadedBytes) {
            count++;
            bytes += uploadedBytes;
        }
    }

    static final class CoarseningCoverage {
        private final Map<Long, Long> replacements = new HashMap<>();
        private final Set<Long> replacementKeys = new HashSet<>();

        void retarget(Collection<Long> drawn, Set<Long> selected, Set<Long> ready,
                      LongUnaryOperator parent) {
            clear();
            for (long key : drawn) {
                if (selected.contains(key)) continue;
                long ancestor = key;
                while (true) {
                    long next = parent.applyAsLong(ancestor);
                    if (next == ancestor) break;
                    ancestor = next;
                    if (selected.contains(ancestor)) {
                        if (!ready.contains(ancestor)) {
                            replacements.put(key, ancestor);
                            replacementKeys.add(ancestor);
                        }
                        break;
                    }
                }
            }
        }

        boolean retains(long key) { return replacements.containsKey(key); }
        Set<Long> retainedKeys() { return replacements.keySet(); }
        boolean isReplacement(long key) { return replacementKeys.contains(key); }
        int size() { return replacements.size(); }
        void clear() {
            replacements.clear();
            replacementKeys.clear();
        }

        void ready(long key) {
            if (!replacementKeys.remove(key)) return;
            // Includes confirmed empty meshes: all old descendants disappear atomically.
            replacements.values().removeIf(replacement -> replacement == key);
        }
    }
}
