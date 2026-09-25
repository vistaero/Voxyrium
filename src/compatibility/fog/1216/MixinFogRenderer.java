package me.cortex.voxy.client.mixin.minecraft;

import com.llamalad7.mixinextras.sugar.Local;
import me.cortex.voxy.client.config.LegacyVoxySupport;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.fog.FogData;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.world.ClientWorld;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = FogRenderer.class, priority = 900)
public final class MixinFogRenderer {
    @Inject(method = "applyFog", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDevice()Lcom/mojang/blaze3d/systems/GpuDevice;", remap = false))
    private void voxy$removeDistanceFog(Camera camera, int viewDistance, boolean thickFog,
                                        RenderTickCounter tracker, float tickDelta, ClientWorld world,
                                        CallbackInfoReturnable<Vector4f> cir, @Local(type = FogData.class) FogData data) {
        if (!VoxyConfig.CONFIG.enabled || !VoxyConfig.CONFIG.enableRendering ||
                !LegacyVoxySupport.isRenderingSupported()) return;

        String mode = VoxyConfig.CONFIG.fogMode;
        boolean hasFog = mode == null || mode.equalsIgnoreCase("fog_and_fade") || mode.equalsIgnoreCase("fog");
        boolean closeFog = data.environmentalEnd < 10.0f;
        if (!hasFog && !closeFog) {
            data.environmentalStart = 999_999_999.0f;
            data.environmentalEnd = 999_999_999.0f;
        }
        data.renderDistanceStart = 999_999_999.0f;
        data.renderDistanceEnd = 999_999_999.0f;
    }
}
