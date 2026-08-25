package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
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

// RETURN injectors execute in reverse application order at the same return site. Applying this
// mixin after Sodium makes this callback run first, before Sodium snapshots the modified FogData.
@Mixin(value = FogRenderer.class, priority = 900)
public class MixinFogRenderer {
    @Unique
    private static final float voxy$disabledFogDistance = 999999999.0F;

    @Unique
    private static boolean voxy$loggedBlaze3dFogReplacement;

    @Unique
    private static boolean voxy$loggedMinecraftFogSuppression;

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void voxy$modifyFog(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker, float darkenWorldAmount, ClientLevel level, CallbackInfoReturnable<FogData> cir) {
        boolean blaze3dProbeActive = VoxyConfig.CONFIG.isBlaze3dRenderingEnabled()
                && VoxyBlaze3DProbeRenderer.canApplyGlobalFog();

        var data = cir.getReturnValue();
        if (blaze3dProbeActive) {
            // Preserve Minecraft/Sodium's original values for diagnostics and dense-fog
            // classification before replacing the short terrain ramp below.
            VoxyBlaze3DProbeRenderer.captureVanillaRenderFogRange(
                    data.renderDistanceStart, data.renderDistanceEnd);
            if (!voxy$loggedBlaze3dFogReplacement) {
                voxy$loggedBlaze3dFogReplacement = true;
                Logger.info("Blaze3D renderer replacing per-geometry render fog with one depth-based world pass: environmental="
                        + data.environmentalStart + ".." + data.environmentalEnd
                        + ", render-distance=" + data.renderDistanceStart + ".." + data.renderDistanceEnd);
            }
        }

        if (!voxy$loggedMinecraftFogSuppression) {
            voxy$loggedMinecraftFogSuppression = true;
            Logger.info("Minecraft/Sodium fog disabled globally; fog settings now affect only Voxy.");
        }

        // Minecraft and Sodium must never bake fog into their geometry. Voxy owns any optional
        // fog pass independently, so changing Voxy's fog settings cannot re-enable vanilla fog.
        data.environmentalStart = voxy$disabledFogDistance;
        data.environmentalEnd = voxy$disabledFogDistance;
        data.renderDistanceStart = voxy$disabledFogDistance;
        data.renderDistanceEnd = voxy$disabledFogDistance;
        data.skyEnd = voxy$disabledFogDistance;
        data.cloudEnd = voxy$disabledFogDistance;
    }
}
