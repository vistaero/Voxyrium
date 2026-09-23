package me.cortex.voxy.client.config;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;

/** Capability gate for the OpenGL-only Voxy compatibility builds. */
public final class LegacyVoxySupport {
    private static volatile Boolean renderingSupported;

    private LegacyVoxySupport() {}

    public static boolean isRenderingSupported() {
        Boolean cached = renderingSupported;
        if (cached != null) {
            return cached;
        }

        try {
            GLCapabilities capabilities = GL.getCapabilities();
            boolean supported = hasRequiredFunctions(capabilities) && supportsRequiredShaderLanguage();
            renderingSupported = supported;
            return supported;
        } catch (IllegalStateException ignored) {
            // Sodium can construct config entries before an OpenGL context is
            // current. Do not cache that transient state.
            return false;
        }
    }

    private static boolean hasRequiredFunctions(GLCapabilities capabilities) {
        return capabilities.glDispatchCompute != 0
                && capabilities.glDispatchComputeIndirect != 0
                && capabilities.glMultiDrawElementsIndirect != 0
                && capabilities.glMultiDrawElementsIndirectCountARB != 0
                && capabilities.glCreateBuffers != 0
                && capabilities.glNamedBufferStorage != 0
                && capabilities.glMapNamedBufferRange != 0
                && capabilities.glCreateTextures != 0
                && capabilities.glTextureStorage2D != 0
                && capabilities.glCreateFramebuffers != 0
                && capabilities.glNamedFramebufferTexture != 0
                && capabilities.glCreateVertexArrays != 0
                && capabilities.glVertexArrayVertexBuffer != 0
                && capabilities.glBindTextureUnit != 0;
    }

    private static boolean supportsRequiredShaderLanguage() {
        int shader = 0;
        try {
            shader = GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER);
            if (shader == 0) {
                return false;
            }
            GL20C.glShaderSource(shader, "#version 460 core\nlayout(local_size_x=1) in; void main() {}");
            GL20C.glCompileShader(shader);
            return GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL20C.GL_TRUE;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            if (shader != 0) {
                GL20C.glDeleteShader(shader);
            }
        }
    }
}
