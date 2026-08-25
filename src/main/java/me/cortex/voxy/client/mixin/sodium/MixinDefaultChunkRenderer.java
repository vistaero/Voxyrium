package me.cortex.voxy.client.mixin.sodium;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.compat.LegacyFogState;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public final class MixinDefaultChunkRenderer {
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lme/jellysquid/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V",
            shift = At.Shift.BEFORE))
    private void voxy$renderLods(ChunkRenderMatrices matrices, CommandList commandList,
                                 ChunkRenderListIterable renderLists, TerrainRenderPass renderPass,
                                 CameraTransform camera, CallbackInfo ci) {
        if (renderPass != DefaultTerrainRenderPasses.CUTOUT || IrisUtil.irisShadowActive()) {
            return;
        }
        var renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer == null) {
            return;
        }

        float[] colour = RenderSystem.getShaderFogColor();
        var fog = LegacyFogState.createTerrainFogParameters(colour);
        var target = Minecraft.getInstance().getMainRenderTarget();
        var viewport = renderer.setupViewport(matrices.projection(), matrices.modelView(), fog,
                target.width, target.height, camera.x, camera.y, camera.z);
        renderer.renderOpaque(viewport, target.getDepthTextureId(), target.getColorTextureId());
    }
}
