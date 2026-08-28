package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PipelineManager.class, remap = false)
public class MixinIrisPipelineManager {
    @Unique
    private boolean voxy$recreateRendererAfterPrepare;

    @Inject(method = "destroyPipeline", at = @At("HEAD"))
    private void voxy$beforeDestroyPipeline(CallbackInfo ci) {
        var holder = IVoxyRenderSystemHolder.getNullableHolder();
        if (holder != null && holder.voxy$getRenderSystem() != null) {
            // Voxy pipeline data contains live suppliers backed by this exact
            // Iris pipeline. Detach it before Iris destroys its render targets.
            holder.voxy$shutdownRenderer();
            this.voxy$recreateRendererAfterPrepare = true;
        }
    }

    @Inject(method = "preparePipeline", at = @At("RETURN"))
    private void voxy$afterPreparePipeline(CallbackInfoReturnable<WorldRenderingPipeline> cir) {
        if (!this.voxy$recreateRendererAfterPrepare) {
            return;
        }

        this.voxy$recreateRendererAfterPrepare = false;
        var holder = IVoxyRenderSystemHolder.getNullableHolder();
        if (holder != null && holder.voxy$getRenderSystem() == null) {
            holder.voxy$createRenderer();
        }
    }
}
