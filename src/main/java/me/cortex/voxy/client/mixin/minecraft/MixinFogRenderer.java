package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.backend.VoxyGraphicsBackend;
import me.cortex.voxy.client.core.backend.blaze3d.VoxyBlaze3DProbeRenderer;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = FogRenderer.class, priority = 900)//We must execute before sodium
public class MixinFogRenderer {
    @Unique
    private static boolean voxy$loggedBlaze3dFogSuppression;

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void voxy$modifyFog(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker, float darkenWorldAmount, ClientLevel level, CallbackInfoReturnable<FogData> cir) {
        boolean blaze3dProbeActive = VoxyGraphicsBackend.current().supportsBlaze3dProbe()
                && VoxyConfig.CONFIG.enabled
                && VoxyConfig.CONFIG.enableRendering;
        if (!VoxyConfig.CONFIG.isRenderingEnabled() && !blaze3dProbeActive) return;

        var data = cir.getReturnValue();
        if (blaze3dProbeActive) {
            if (!VoxyBlaze3DProbeRenderer.isFogEnabled()) {
                data.environmentalStart = 999999999;
                data.environmentalEnd = 999999999;
                data.renderDistanceStart = 999999999;
                data.renderDistanceEnd = 999999999;
                return;
            }
            if (!voxy$loggedBlaze3dFogSuppression) {
                voxy$loggedBlaze3dFogSuppression = true;
                Logger.info("Blaze3D probe restoring Voxy-style environmental fog on Vulkan: environmental="
                        + data.environmentalStart + ".." + data.environmentalEnd
                        + ", render-distance=" + data.renderDistanceStart + ".." + data.renderDistanceEnd);
            }
            boolean fogIsDamnClose = data.environmentalEnd < 10;
            if (!VoxyConfig.CONFIG.useEnvironmentalFog && !fogIsDamnClose) {
                data.environmentalStart = 99999999;
                data.environmentalEnd = 99999999;
            }
            // Match the OpenGL backend: Voxy owns environmental fog and suppresses only the
            // vanilla render-distance component so its extended terrain is not clipped twice.
            data.renderDistanceStart = 999999999;
            data.renderDistanceEnd = 999999999;
            return;
        }

        var vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs == null) return;
        boolean fogIsDamnClose = data.environmentalEnd<10;
        if (!VoxyConfig.CONFIG.useEnvironmentalFog && !fogIsDamnClose) {
            data.environmentalStart = 99999999;
            data.environmentalEnd = 99999999;
        }

        data.renderDistanceStart = 999999999;
        data.renderDistanceEnd = 999999999;
    }
}
