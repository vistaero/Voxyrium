package me.cortex.voxy.client.mixin.sodium;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.compat.FogParameters;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkCameraContext;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.RegionChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.passes.BlockRenderPass;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RegionChunkRenderer.class, remap = false)
public final class MixinRegionChunkRenderer {
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/render/chunk/ShaderChunkRenderer;end()V",
            shift = At.Shift.BEFORE))
    private void voxy$renderLods(ChunkRenderMatrices matrices, CommandList commandList,
                                 ChunkRenderList renderLists, BlockRenderPass renderPass,
                                 ChunkCameraContext camera, CallbackInfo ci) {
        if (renderPass != BlockRenderPass.CUTOUT || IrisUtil.irisShadowActive()) {
            return;
        }
        var renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer == null) {
            return;
        }

        float[] colour = RenderSystem.getShaderFogColor();
        float start = RenderSystem.getShaderFogStart();
        float end = RenderSystem.getShaderFogEnd();
        var fog = new FogParameters(colour[0], colour[1], colour[2], colour[3],
                start, end, start, end);
        var target = Minecraft.getInstance().getMainRenderTarget();
        var viewport = renderer.setupViewport(matrices.projection(), matrices.modelView(), fog,
                target.width, target.height, camera.posX, camera.posY, camera.posZ);
        renderer.renderOpaque(viewport, target.getDepthTextureId(), target.getColorTextureId());
    }
}
