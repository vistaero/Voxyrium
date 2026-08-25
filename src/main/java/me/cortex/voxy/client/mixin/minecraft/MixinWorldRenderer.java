package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.VoxyRenderSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.IVoxyWorld;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.render.*;
import net.minecraft.client.world.ClientWorld;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class MixinWorldRenderer implements IGetVoxyRenderSystem {
    @Shadow private Frustum frustum;
    @Shadow private @Nullable ClientWorld world;
    @Unique private VoxyRenderSystem renderer;

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;setupTerrain(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/Frustum;ZZ)V", shift = At.Shift.AFTER))
    private void injectSetup(float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f positionMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        if (this.renderer != null) {
            this.renderer.renderSetup(this.frustum, camera);
        }
    }

    @Override
    public VoxyRenderSystem getVoxyRenderSystem() {
        return this.renderer;
    }

    @Inject(method = "reload()V", at = @At("TAIL"))
    private void reloadVoxyRenderer(CallbackInfo ci) {
        this.shutdownRenderer();
        if (this.world != null) {
            this.createRenderer();
        }
    }

    @Inject(method = "setWorld", at = @At("HEAD"))
    private void voxy$captureSetWorld(ClientWorld world, CallbackInfo ci) {
        if (this.world != world) {
            this.shutdownRenderer();

            if (this.world != null) {
                var engine = ((IVoxyWorld)this.world).getWorldEngine();
                if (engine != null) {
                    VoxyCommon.getInstance().stopWorld(engine);
                }
                ((IVoxyWorld)this.world).setWorldEngine(null);
            }
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void injectClose(CallbackInfo ci) {
        this.shutdownRenderer();
    }

    @Override
    public void shutdownRenderer() {
        if (this.renderer != null) {
            this.renderer.shutdown();
            this.renderer = null;
        }
    }

    @Override
    public void createRenderer() {
        if (this.renderer != null) throw new IllegalStateException("Cannot have multiple renderers");
        if ((!VoxyConfig.CONFIG.enableRendering)||(!VoxyConfig.CONFIG.enabled)) {
            Logger.info("Not creating renderer due to disabled");
            return;
        }
        if (this.world == null) {
            Logger.error("Not creating renderer due to null world");
            return;
        }
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            Logger.error("Not creating renderer due to null instance");
            return;
        }
        WorldEngine world = instance.getOrMakeRenderWorld(this.world);
        if (world == null) {
            Logger.error("Null world selected");
            return;
        }
        this.renderer = new VoxyRenderSystem(world, instance.getThreadPool());
    }
}
