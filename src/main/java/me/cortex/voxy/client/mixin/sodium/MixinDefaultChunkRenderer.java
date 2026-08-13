package me.cortex.voxy.client.mixin.sodium;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.backend.VoxyGraphicsBackend;
import me.cortex.voxy.client.core.backend.blaze3d.VoxyBlaze3DProbeRenderer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer extends ShaderChunkRenderer {

    public MixinDefaultChunkRenderer(ChunkVertexType vertexType) {
        super(vertexType);
    }

    @Inject(method = "render", at = @At(value = "HEAD"), cancellable = true)
    private void voxy$cancelThingie(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters parameters, boolean indexedRenderingEnabled, GpuSampler terrainSampler, GpuBufferSlice uniformData, GpuBuffer sectionTimeInfo, CallbackInfo ci) {
        if (VoxyClient.disableSodiumChunkRender()) {
            super.begin(renderPass, parameters, terrainSampler);
            this.doRender(matrices, renderPass, camera, parameters);
            super.end(renderPass);
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V", shift = At.Shift.BEFORE))
    private void voxy$injectRender(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters parameters, boolean indexedRenderingEnabled, GpuSampler terrainSampler, GpuBufferSlice uniformData, GpuBuffer sectionTimeInfo, CallbackInfo ci) {
        // Match the native renderer's ordering: Sodium's solid and cutout terrain must populate
        // the depth target before Voxy draws. Rendering at the head of SOLID made coincident LoD
        // surfaces fight with the blocks that Sodium rendered immediately afterwards.
        if (renderPass == DefaultTerrainRenderPasses.CUTOUT
                && VoxyConfig.CONFIG.isBlaze3dRenderingEnabled()) {
            var target = renderPass.getTarget();
            VoxyBlaze3DProbeRenderer.renderOpaque(matrices, target.getColorTextureView(), target.getDepthTextureView(), camera, parameters);
        }
        this.doRender(matrices, renderPass, camera, parameters);
    }

    @Unique
    private void doRender(ChunkRenderMatrices matrices, TerrainRenderPass renderPass, CameraTransform camera, FogParameters fogParameters) {
        if (renderPass == DefaultTerrainRenderPasses.CUTOUT) {
            if (VoxyGraphicsBackend.usesBlaze3dRenderer()) {
                return;
            }
            var renderer = IVoxyRenderSystemHolder.getNullable();
            if (renderer != null) {
                if (renderer.isVulkanBackend()) {
                    //Vulkan backend: Voxy renders via its own frame hook
                    // (MixinSodiumOpaqueVkFrame). Sodium 0.9.1 also renders through MC's
                    // Vulkan device, so target.get*TextureView() returns a
                    // VulkanGpuTextureView with no glId() — the GL-interop path below
                    // must not run (the cast would ClassCastException before
                    // renderOpaque's own VK early-return is reached).
                    return;
                }
                Viewport<?> viewport = null;
                var target = renderPass.getTarget();
                if (IrisUtil.USED_IRIS_VIEWPORT) {
                    viewport = renderer.getViewport();
                    IrisUtil.USED_IRIS_VIEWPORT = false;
                } else {
                    viewport = renderer.setupViewport(matrices.projection(), matrices.modelView(), fogParameters, target.width, target.height, camera.x, camera.y, camera.z);
                }
                renderer.renderOpaque(viewport,
                        ((com.mojang.blaze3d.opengl.GlTextureView)target.getDepthTextureView()).glId(),
                        ((com.mojang.blaze3d.opengl.GlTextureView)target.getColorTextureView()).glId());
            }
            return;
        }

        if (renderPass == DefaultTerrainRenderPasses.TRANSLUCENT
                && VoxyConfig.CONFIG.isBlaze3dRenderingEnabled()) {
            var target = renderPass.getTarget();
            VoxyBlaze3DProbeRenderer.renderWater(matrices, target.getColorTextureView(), target.getDepthTextureView(), camera, fogParameters);
        }
    }
}
