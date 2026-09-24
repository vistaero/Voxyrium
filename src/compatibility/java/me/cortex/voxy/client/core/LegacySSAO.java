package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.ARBComputeShader.glDispatchCompute;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.glBindImageTexture;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL14C.GL_TEXTURE_COMPARE_MODE;
import static org.lwjgl.opengl.GL15C.GL_READ_WRITE;
import static org.lwjgl.opengl.GL20C.nglUniformMatrix4fv;
import static org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL45.glGetNamedFramebufferAttachmentParameteri;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;
import static org.lwjgl.opengl.GL45C.glCreateSamplers;

/** Backport of the configurable SSAO implementation for the 1.21.7-1.21.10 render pipeline. */
public final class LegacySSAO {
    public enum Mode { AUTO, BASIC, BETTER, BEST }

    private final Shader shader;
    private final boolean improved;
    private final int depthSampler;

    public LegacySSAO(String configuredMode) {
        Mode mode;
        try {
            mode = Mode.valueOf(configuredMode == null ? "AUTO" : configuredMode.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            mode = Mode.AUTO;
        }
        if (mode == Mode.AUTO) {
            mode = Capabilities.INSTANCE.canQueryGpuMemory
                    && Capabilities.INSTANCE.totalDedicatedMemory >= 7_000_000_000L
                    ? Mode.BEST
                    : Capabilities.INSTANCE.isIntel ? Mode.BASIC : Mode.BETTER;
        }

        this.improved = mode == Mode.BETTER || mode == Mode.BEST;
        int samples = mode == Mode.BEST ? 24 : 12;
        var builder = Shader.make().add(ShaderType.COMPUTE, "voxy:post/ssao.comp");
        if (this.improved) {
            builder.define("BETTER_SSAO").define("SSAO_STEPS", samples);
        }
        this.shader = builder.compile();

        this.depthSampler = glCreateSamplers();
        glSamplerParameteri(this.depthSampler, GL_TEXTURE_MIN_FILTER,
                this.improved ? GL_NEAREST_MIPMAP_NEAREST : GL_LINEAR);
        glSamplerParameteri(this.depthSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.depthSampler, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        glSamplerParameteri(this.depthSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(this.depthSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    public void compute(Viewport<?> viewport, GlTexture output, GlTexture colour,
                        GlTexture depth, int sourceFramebuffer) {
        this.shader.bind();
        try (var stack = MemoryStack.stackPush()) {
            long pointer = stack.nmalloc(4 * 4 * 4);
            var scratch = new Matrix4f();
            if (this.improved) {
                viewport.projection.getToAddress(pointer);
                nglUniformMatrix4fv(4, 1, false, pointer);
                viewport.projection.invert(scratch).getToAddress(pointer);
                nglUniformMatrix4fv(5, 1, false, pointer);
                viewport.modelView.getToAddress(pointer);
                nglUniformMatrix4fv(6, 1, false, pointer);
                viewport.vanillaProjection.invert(scratch).getToAddress(pointer);
                nglUniformMatrix4fv(7, 1, false, pointer);
            } else {
                viewport.MVP.getToAddress(pointer);
                nglUniformMatrix4fv(3, 1, false, pointer);
                viewport.MVP.invert(scratch).getToAddress(pointer);
                nglUniformMatrix4fv(4, 1, false, pointer);
            }
        }

        glBindImageTexture(0, output.id, 0, false, 0, GL_READ_WRITE, GL_RGBA8);
        glBindTextureUnit(1, colour.id);
        glBindSampler(1, 0);
        glBindTextureUnit(2, depth.id);
        glBindSampler(2, this.depthSampler);
        if (this.improved) {
            int sourceDepth = glGetNamedFramebufferAttachmentParameteri(
                    sourceFramebuffer, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            glBindTextureUnit(3, sourceDepth);
            glBindSampler(3, this.depthSampler);
        }
        glDispatchCompute((viewport.width + 7) / 8, (viewport.height + 7) / 8, 1);
        // Image writes feed both texture sampling and framebuffer blending in the next pass.
        org.lwjgl.opengl.GL42C.glMemoryBarrier(org.lwjgl.opengl.GL42C.GL_TEXTURE_FETCH_BARRIER_BIT
                | org.lwjgl.opengl.GL42C.GL_FRAMEBUFFER_BARRIER_BIT);
        for (int unit = 1; unit <= 3; unit++) {
            glBindTextureUnit(unit, 0);
            glBindSampler(unit, 0);
        }
    }

    public void free() {
        glDeleteSamplers(this.depthSampler);
        this.shader.free();
    }
}
