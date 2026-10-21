package me.cortex.voxy.client.mixin.sodium;

//? if MC_26_3
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
//? else
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
//? endif
import me.cortex.voxy.client.VoxyClient;
//? if MC_26_2
import me.cortex.voxy.client.config.VoxyConfig;
//? endif
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.backend.VoxyGraphicsBackend;
//? if MC_26_2
import me.cortex.voxy.client.core.backend.blaze3d.VoxyBlaze3DProbeRenderer;
//? endif
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
//? if MC_26_3
import net.minecraft.client.renderer.oit.OitStage;
//? endif
//? if MC_26_3
import org.spongepowered.asm.mixin.Final;
//? endif
import org.spongepowered.asm.mixin.Mixin;
//? if MC_26_3
import org.spongepowered.asm.mixin.Shadow;
//? endif
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer extends ShaderChunkRenderer {

//? if MC_26_3
    @Shadow
    @Final
    private boolean[] shouldDraw;
//? endif

    public MixinDefaultChunkRenderer(ChunkVertexType vertexType) {
        super(vertexType);
    }

    @Inject(method = "render", at = @At(value = "HEAD"), cancellable = true)
//? if MC_26_3
    private void voxy$cancelThingie(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters parameters, boolean indexedRenderingEnabled, RenderPass pass, GpuSampler terrainSampler, GpuBufferSlice uniformData, GpuBuffer sectionTimeInfo, OitStage stage, CallbackInfo ci) {
//? else
    private void voxy$cancelThingie(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters parameters, boolean indexedRenderingEnabled, GpuSampler terrainSampler, GpuBufferSlice uniformData, GpuBuffer sectionTimeInfo, CallbackInfo ci) {
//? endif
        if (VoxyClient.disableSodiumChunkRender()) {
//? if MC_26_3
            super.begin(renderPass, parameters, terrainSampler, stage);
//? else
            super.begin(renderPass, parameters, terrainSampler);
//? endif
            this.doRender(matrices, renderPass, camera, parameters);
            super.end(renderPass);
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V", shift = At.Shift.BEFORE))
//? if MC_26_3
    private void voxy$injectRender(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters parameters, boolean indexedRenderingEnabled, RenderPass pass, GpuSampler terrainSampler, GpuBufferSlice uniformData, GpuBuffer sectionTimeInfo, OitStage stage, CallbackInfo ci) {
//? else
    private void voxy$injectRender(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters parameters, boolean indexedRenderingEnabled, GpuSampler terrainSampler, GpuBufferSlice uniformData, GpuBuffer sectionTimeInfo, CallbackInfo ci) {
        // Match the native renderer's ordering: Sodium's solid and cutout terrain must populate
        // the depth target before Voxy draws. Rendering at the head of SOLID made coincident LoD
        // surfaces fight with the blocks that Sodium rendered immediately afterwards.
        if (renderPass == DefaultTerrainRenderPasses.CUTOUT
                && VoxyConfig.CONFIG.isBlaze3dRenderingEnabled()
                && !IrisUtil.irisShadowActive()) {
            var target = renderPass.getTarget();
            VoxyBlaze3DProbeRenderer.renderOpaque(matrices, target.getColorTextureView(), target.getDepthTextureView(), camera, parameters);
        }
//? endif
        this.doRender(matrices, renderPass, camera, parameters);
    }

//? if MC_26_3
    @Inject(method = "prepare", at = @At(value = "TAIL"))
    private void voxy$forceOpaque(ChunkRenderListIterable renderLists, CameraTransform cameraTransform, boolean indexedRenderingEnabled, CallbackInfo ci) {
        var renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer != null) {
            this.shouldDraw[DefaultTerrainRenderPasses.getPassIndex(DefaultTerrainRenderPasses.CUTOUT)] = true;
        }
    }

//? endif
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
//? if MC_26_3
                        ((GlTextureView)target.getDepthTextureView()).glId(),
                        ((GlTextureView)target.getColorTextureView()).glId());
//? else
                        ((com.mojang.blaze3d.opengl.GlTextureView)target.getDepthTextureView()).glId(),
                        ((com.mojang.blaze3d.opengl.GlTextureView)target.getColorTextureView()).glId());
//? endif
            }
            return;
        }

//? if MC_26_2
        if (renderPass == DefaultTerrainRenderPasses.TRANSLUCENT
                && VoxyConfig.CONFIG.isBlaze3dRenderingEnabled()
                && !IrisUtil.irisShadowActive()) {
            var target = renderPass.getTarget();
            VoxyBlaze3DProbeRenderer.renderWater(matrices, target.getColorTextureView(), target.getDepthTextureView(), camera, fogParameters);
        }
//? endif
    }
}
