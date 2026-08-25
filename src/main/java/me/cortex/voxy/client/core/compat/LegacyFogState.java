package me.cortex.voxy.client.core.compat;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Keeps the terrain fog range produced by Minecraft before Voxy removes it
 * from the shared RenderSystem state consumed by Sodium.
 */
public final class LegacyFogState {
    private static float terrainStart = Float.MAX_VALUE;
    private static float terrainEnd = Float.MAX_VALUE;
    private static boolean terrainFogCaptured;

    private LegacyFogState() {
    }

    public static void captureTerrainFog(float start, float end) {
        terrainStart = start;
        terrainEnd = end;
        terrainFogCaptured = true;
    }

    public static FogParameters createTerrainFogParameters(float[] colour) {
        float start = terrainFogCaptured ? terrainStart : RenderSystem.getShaderFogStart();
        float end = terrainFogCaptured ? terrainEnd : RenderSystem.getShaderFogEnd();
        return new FogParameters(colour[0], colour[1], colour[2], colour[3],
                start, end, start, end);
    }
}
