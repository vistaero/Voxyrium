package me.cortex.voxy.client.core.compat;

/**
 * Version-neutral snapshot of the fog values consumed by Voxy's pipelines.
 * Sodium 0.5 does not expose the FogParameters record used by current dev.
 */
public record FogParameters(float red, float green, float blue, float alpha,
                            float environmentalStart, float environmentalEnd,
                            float renderStart, float renderEnd) {
}
