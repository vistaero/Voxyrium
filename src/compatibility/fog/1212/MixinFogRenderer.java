package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.LegacyVoxySupport;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Fog;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BackgroundRenderer.class, priority = 900)
public final class MixinFogRenderer {
    @Inject(method = "applyFog", at = @At("RETURN"), cancellable = true)
    private static void voxy$removeDistanceFog(Camera camera, BackgroundRenderer.FogType type,
                                                Vector4f colour, float viewDistance, boolean thickFog,
                                                float tickDelta, CallbackInfoReturnable<Fog> cir) {
        if (type != BackgroundRenderer.FogType.FOG_TERRAIN ||
                !VoxyConfig.CONFIG.enabled || !VoxyConfig.CONFIG.enableRendering ||
                !LegacyVoxySupport.isRenderingSupported()) return;

        Fog fog = cir.getReturnValue();
        if (fog.end() < Math.min(10.0f, viewDistance)) return;

        // Preserve Minecraft's colour and shape while moving its distance ramp
        // out of Sodium's range. Voxy composites fog and fade from its setting.
        cir.setReturnValue(new Fog(1_000_000.0f, 1_000_001.0f, fog.shape(),
                fog.red(), fog.green(), fog.blue(), fog.alpha()));
    }
}
