package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.core.compat.LegacyFogState;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FogRenderer.class, priority = 900)
public final class MixinLegacyFogRenderer {
    @Unique
    private static final float VOXY$DISABLED_FOG_START = 1_000_000.0F;

    @Unique
    private static final float VOXY$DISABLED_FOG_END = 1_000_001.0F;

    @Unique
    private static boolean voxy$loggedTerrainFogSuppression;

    @Inject(method = "setupFog", at = @At("RETURN"))
    private static void voxy$replaceTerrainFog(Camera camera, FogRenderer.FogMode fogMode,
                                                float viewDistance, boolean thickFog,
                                                float partialTick, CallbackInfo ci) {
        if (fogMode != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }

        LegacyFogState.captureTerrainFog(
                RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(),
                thickFog || camera.getFluidInCamera() != FogType.NONE);

        // Sodium 0.4/0.5 reads these values when binding its chunk shader. Keep the
        // original range above for Voxy, then prevent vanilla terrain geometry from
        // applying the same fog/fade a second time in front of the LoD render. This
        // must not depend on the Voxy world renderer already existing: FogRenderer
        // runs while that renderer is still being attached during world entry.
        // Sodium 0.4 evaluates smoothstep(fogEnd, fogStart, distance). Equal
        // endpoints make that expression undefined and some drivers return the
        // fog colour for every vanilla fragment, so keep a distinct far-away
        // interval instead of collapsing the range to one value.
        RenderSystem.setShaderFogStart(VOXY$DISABLED_FOG_START);
        RenderSystem.setShaderFogEnd(VOXY$DISABLED_FOG_END);

        if (!voxy$loggedTerrainFogSuppression) {
            voxy$loggedTerrainFogSuppression = true;
            Logger.info("Minecraft/Sodium terrain fog disabled; Voxy now owns fog and distance fade.");
        }
    }
}
