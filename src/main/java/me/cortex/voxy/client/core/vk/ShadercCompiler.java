package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;

import java.nio.ByteBuffer;

import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * Runtime GLSL -> SPIR-V so Voxy's shaders stay single-source. Compiles the
 * SAME .glsl assets the GL path uses, with VOXY_VULKAN defined and vulkan
 * semantics enabled (auto bind/set mapping for the existing layout(binding=N)
 * declarations). Requires the lwjgl-shaderc natives added in build.gradle.
 */
public final class ShadercCompiler {
    public static ByteBuffer compile(String source, ShaderType type, String name) {
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        try {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_auto_bind_uniforms(options, true);
            shaderc_compile_options_set_auto_map_locations(options, true);
            shaderc_compile_options_add_macro_definition(options, "VOXY_VULKAN", "1");
            int kind = switch (type) {
                case VERTEX -> shaderc_vertex_shader;
                case FRAGMENT -> shaderc_fragment_shader;
                case COMPUTE -> shaderc_compute_shader;
                default -> throw new IllegalArgumentException("Unsupported stage for VK: " + type);
            };
            long result = shaderc_compile_into_spv(compiler, source, kind, name, "main", options);
            try {
                if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                    throw new IllegalStateException("SPIR-V compile failed for " + name + ":\n"
                            + shaderc_result_get_error_message(result));
                }
                ByteBuffer spv = shaderc_result_get_bytes(result);
                ByteBuffer copy = ByteBuffer.allocateDirect(spv.remaining());
                copy.put(spv).flip();
                return copy;
            } finally {
                shaderc_result_release(result);
            }
        } finally {
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }
    private ShadercCompiler() {}
}
