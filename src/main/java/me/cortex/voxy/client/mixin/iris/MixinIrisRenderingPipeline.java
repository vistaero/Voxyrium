package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.client.iris.IGetVoxyPatchData;
import me.cortex.voxy.client.iris.IrisShaderPatch;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import me.cortex.voxy.client.iris.IrisPipelineBuildHooks;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisRenderingPipeline implements IGetVoxyPatchData, IGetIrisVoxyPipelineData {
    @Shadow @Final private CustomUniforms customUniforms;
    @Shadow private ShaderStorageBufferHolder shaderStorageBufferHolder;
    @Unique IrisShaderPatch patchData;
    @Unique
    IrisVoxyRenderPipelineData pipeline;
    @Unique
    ProgramSet voxy$programSet;
    @Unique
    boolean voxy$buildingPipelineData;

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/pipeline/transform/ShaderPrinter;resetPrintState()V", shift = At.Shift.AFTER))
    private void voxy$injectPatchDataStore(ProgramSet programSet, CallbackInfo ci) {
        this.voxy$programSet = programSet;
        IrisPipelineBuildHooks.begin(this);
        if (IrisUtil.SHADER_SUPPORT) {
            this.patchData = ((IGetVoxyPatchData) programSet).voxy$getPatchData();
        }
    }

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;createSetupComputes([Lnet/irisshaders/iris/shaderpack/programs/ComputeSource;Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;Lnet/irisshaders/iris/shaderpack/texture/TextureStage;)[Lnet/irisshaders/iris/gl/program/ComputeProgram;"))
    private void voxy$injectPipeline(ProgramSet programSet, CallbackInfo ci) {
        try {
            this.voxy$buildPipelineData();
        } finally {
            IrisPipelineBuildHooks.end(this);
        }
    }

    @Unique
    private void voxy$buildPipelineData() {
        if (this.pipeline != null || this.voxy$buildingPipelineData) {
            return;
        }
        if (this.patchData == null && this.voxy$programSet != null && IrisUtil.SHADER_SUPPORT) {
            this.patchData = ((IGetVoxyPatchData)this.voxy$programSet).voxy$getPatchData();
        }
        if (this.patchData == null) {
            return;
        }

        boolean ownsBuildHook = IrisPipelineBuildHooks.current() != this;
        if (ownsBuildHook) {
            IrisPipelineBuildHooks.begin(this);
        }
        this.voxy$buildingPipelineData = true;
        try {
            this.pipeline = IrisVoxyRenderPipelineData.buildPipeline(
                    (IrisRenderingPipeline)(Object)this,
                    this.patchData,
                    this.customUniforms,
                    this.shaderStorageBufferHolder);
        } finally {
            this.voxy$buildingPipelineData = false;
            if (ownsBuildHook) {
                IrisPipelineBuildHooks.end(this);
            }
        }
    }

    @Override
    public IrisShaderPatch voxy$getPatchData() {
        return this.patchData;
    }

    @Override
    public IrisVoxyRenderPipelineData voxy$getPipelineData() {
        this.voxy$buildPipelineData();
        return this.pipeline;
    }
}
