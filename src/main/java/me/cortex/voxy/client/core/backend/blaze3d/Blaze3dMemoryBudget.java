package me.cortex.voxy.client.core.backend.blaze3d;

import java.util.Locale;
import me.cortex.voxy.common.Logger;
import oshi.SystemInfo;

/**
 * Estimates the working set and residency budget of the Blaze3D renderer. Each quad uses a
 * 32-byte instance instead of four 28-byte vertices. Keep the previous residency headroom to
 * spend the savings on more detail, cached branches and longer render distances.
 */
public final class Blaze3dMemoryBudget {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;
    private static final long MIN_GEOMETRY_BUDGET = 512L * MIB;
    private static final long MAX_GEOMETRY_BUDGET = 8L * GIB;
    private static final long MIN_STAGING_BUDGET = 128L * MIB;
    private static final long MAX_STAGING_BUDGET = 768L * MIB;
    // Full 65,536-model RGBA atlas including the four allocated mip levels, plus render targets,
    // the shared index buffer and small fixed GPU resources.
    private static final long FIXED_VRAM_BYTES = 576L * MIB;
    // Captured Minecraft atlas, baked-model upload results and CPU metadata during population.
    private static final long FIXED_RAM_BYTES = 256L * MIB;

    private Blaze3dMemoryBudget() {
    }

    /** Total VRAM is a capacity hint, not a measurement of currently free device memory. */
    public static long detectGeometryLimit(String deviceName) {
        long limit = 512L * MIB;
        String active = normalizeDeviceName(deviceName);
        try {
            long capacity = Long.MAX_VALUE;
            for (var card : new SystemInfo().getHardware().getGraphicsCards()) {
                String name = normalizeDeviceName(card.getName());
                if (!name.isEmpty() && !active.isEmpty()
                        && (active.contains(name) || name.contains(active)) && card.getVRam() > 0L) {
                    capacity = Math.min(capacity, card.getVRam());
                }
            }
            if (capacity != Long.MAX_VALUE) {
                // Leave half to Minecraft, Sodium, shader packs and the desktop. Our atlas and
                // render targets consume part of the remaining half before any geometry fits.
                limit = clamp(capacity / 2L - FIXED_VRAM_BYTES, 64L * MIB, MAX_GEOMETRY_BUDGET);
            }
        } catch (RuntimeException | LinkageError exception) {
            Logger.warn("Unable to determine Blaze3D device capacity; using a 512 MiB geometry limit: "
                    + exception.getMessage());
        }
        Logger.info("Blaze3D geometry safety limit for " + deviceName + ": " + formatBytes(limit)
                + " (capacity estimate; allocation failures reduce it further).");
        return limit;
    }

    private static String normalizeDeviceName(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public static long detectMeshCacheLimit() {
        long limit = 256L * MIB;
        try {
            long available = new SystemInfo().getHardware().getMemory().getAvailable();
            Runtime runtime = Runtime.getRuntime();
            long heapGrowth = Math.max(0L, runtime.maxMemory() - runtime.totalMemory());
            // Packed meshes use native RAM, outside the JVM heap. Reserve future heap growth
            // and most remaining memory for the game, meshing workers and other applications.
            limit = clamp(Math.max(0L, available - heapGrowth) / 4L, 0L, 2L * GIB);
        } catch (RuntimeException | LinkageError exception) {
            Logger.warn("Unable to determine RAM headroom for Blaze3D mesh cache: " + exception.getMessage());
        }
        Long configuredMiB = Long.getLong("voxy.blaze3d.meshCacheMiB");
        if (configuredMiB != null) limit = Math.min(limit, clamp(configuredMiB, 0L, 8192L) * MIB);
        Logger.info("Blaze3D nearby mesh RAM cache budget: " + formatBytes(limit));
        return limit;
    }

    public static Estimate estimate(float sectionRenderDistance, float subdivisionSize) {
        double renderDistanceChunks = Math.max(20.0, sectionRenderDistance * 32.0);
        double safeSubdivisionSize = Math.max(28.0, subdivisionSize);

        // Calibrated against a 2,592-block (162 chunk), 64-pixel capture. Distance grows slower
        // than area because progressively farther rings select progressively coarser Cortex LoDs.
        // Keep this 112-byte-quad baseline for budgets; scale only the working-set estimate below.
        double distanceFactor = Math.pow(renderDistanceChunks / 162.0, 0.80);
        double qualityFactor = Math.pow(64.0 / safeSubdivisionSize, 1.35);
        long steadyGeometryBytes = clamp(Math.round(2.60 * GIB * distanceFactor * qualityFactor),
                256L * MIB, 32L * GIB);

        // Branch hand-offs temporarily retain a coarser parent while its descendants finish.
        long requestedGeometryBudget = Math.round(steadyGeometryBytes * 1.20);
        long geometryBudgetBytes = clamp(requestedGeometryBudget, MIN_GEOMETRY_BUDGET, MAX_GEOMETRY_BUDGET);
        long stagingBudgetBytes = clamp(Math.round(steadyGeometryBytes * 0.12),
                MIN_STAGING_BUDGET, MAX_STAGING_BUDGET);
        // Scale the estimated working set, not the allocation budget: spare capacity is useful
        // for warm geometry and transitions. This is an estimate, not measured free VRAM.
        long compactWorkingSet = Math.round(requestedGeometryBudget * (Blaze3dQuadEncoder.STRIDE / 112.0));
        long requiredVramBytes = compactWorkingSet + FIXED_VRAM_BYTES;
        long requiredRamBytes = stagingBudgetBytes + FIXED_RAM_BYTES;
        return new Estimate(requiredRamBytes, requiredVramBytes, geometryBudgetBytes, stagingBudgetBytes,
                compactWorkingSet > MAX_GEOMETRY_BUDGET);
    }

    public static String formatBytes(long bytes) {
        if (bytes >= GIB) {
            return String.format(Locale.ROOT, "%.1f GiB", bytes / (double) GIB);
        }
        return Math.round(bytes / (double) MIB) + " MiB";
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record Estimate(long requiredRamBytes,
                           long requiredVramBytes,
                           long geometryBudgetBytes,
                           long stagingBudgetBytes,
                           boolean exceedsSafetyLimit) {
        public String shortDescription() {
            return "~" + formatBytes(this.requiredVramBytes) + " VRAM / ~"
                    + formatBytes(this.requiredRamBytes) + " RAM";
        }
    }
}
