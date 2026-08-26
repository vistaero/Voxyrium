package me.cortex.voxy.client.core.compat;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Keeps the terrain fog range produced by Minecraft before Voxy removes it
 * from the shared RenderSystem state consumed by Sodium.
 */
public final class LegacyFogState {
    private static float terrainRenderStart = Float.MAX_VALUE;
    private static float terrainRenderEnd = Float.MAX_VALUE;
    private static float terrainEnvironmentalStart = Float.MAX_VALUE;
    private static float terrainEnvironmentalEnd = Float.MAX_VALUE;
    private static boolean terrainFogCaptured;

    private LegacyFogState() {
    }

    public static void captureTerrainFog(float start, float end, boolean denseEnvironmentalFog) {
        terrainRenderStart = start;
        terrainRenderEnd = end;
        terrainEnvironmentalStart = denseEnvironmentalFog ? start : Float.MAX_VALUE;
        terrainEnvironmentalEnd = denseEnvironmentalFog ? end : Float.MAX_VALUE;
        terrainFogCaptured = true;
    }

    public static FogParameters createTerrainFogParameters(float[] colour) {
        float renderStart = terrainFogCaptured ? terrainRenderStart : RenderSystem.getShaderFogStart();
        float renderEnd = terrainFogCaptured ? terrainRenderEnd : RenderSystem.getShaderFogEnd();
        float environmentalStart = terrainFogCaptured ? terrainEnvironmentalStart : Float.MAX_VALUE;
        float environmentalEnd = terrainFogCaptured ? terrainEnvironmentalEnd : Float.MAX_VALUE;
        return new FogParameters(colour[0], colour[1], colour[2], colour[3],
                environmentalStart, environmentalEnd, renderStart, renderEnd);
    }
}
