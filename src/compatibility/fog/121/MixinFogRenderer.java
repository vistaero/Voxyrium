package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.config.LegacyVoxySupport;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BackgroundRenderer.class, priority = 900)
public final class MixinFogRenderer {
    @Inject(method = "applyFog", at = @At("RETURN"))
    private static void voxy$removeDistanceFog(Camera camera, BackgroundRenderer.FogType type,
                                                float viewDistance, boolean thickFog, float tickDelta,
                                                CallbackInfo ci) {
        if (type != BackgroundRenderer.FogType.FOG_TERRAIN ||
                !VoxyConfig.CONFIG.enabled || !VoxyConfig.CONFIG.enableRendering ||
                !LegacyVoxySupport.isRenderingSupported()) return;

        // Close environmental fog (fluid, blindness, etc.) is independent of the
        // terrain distance ramp and must remain visible in Fog modes.
        boolean closeFog = RenderSystem.getShaderFogEnd() < Math.min(10.0f, viewDistance);
        if (closeFog) return;

        // Sodium 0.6 still reads RenderSystem's terrain fog. Distinct endpoints
        // keep its smoothstep defined while Voxy renders its own fog and fade.
        RenderSystem.setShaderFogStart(1_000_000.0f);
        RenderSystem.setShaderFogEnd(1_000_001.0f);
    }
}
