package me.cortex.voxy.client.core.gl.shader;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.common.util.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL30.glBindBufferRange;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;


//TODO: rewrite the entire shader builder system
public class AutoBindingShader extends Shader {
    private record BufferBinding(int target, int index, GlBuffer buffer, long offset, long size) {}

    private final Map<String, String> defines;
    private final List<BufferBinding> bindings = new ArrayList<>();

    AutoBindingShader(Shader.Builder<AutoBindingShader> builder, int program) {
        super(program);
        this.defines = builder.defines;
    }

    public AutoBindingShader ssbo(int index, GlBuffer binding) {
        return this.ssbo(index, binding, 0);
    }

    public AutoBindingShader ssbo(String define, GlBuffer binding) {
        return this.ssbo(Integer.parseInt(this.defines.get(define)), binding, 0);
    }

    public AutoBindingShader ssbo(int index, GlBuffer buffer, long offset) {
        this.bindings.add(new BufferBinding(GL_SHADER_STORAGE_BUFFER, index, buffer, offset, -1));
        return this;
    }

    public AutoBindingShader ubo(int index, GlBuffer buffer) {
        return this.ubo(index, buffer, 0);
    }

    public AutoBindingShader ubo(int index, GlBuffer buffer, long offset) {
        this.bindings.add(new BufferBinding(GL_UNIFORM_BUFFER, index, buffer, offset, -1));
        return this;
    }

    @Override
    public void bind() {
        super.bind();
        for (var binding : this.bindings) {
            if (binding.offset == 0 && binding.size == -1) {
                glBindBufferBase(binding.target, binding.index, binding.buffer.id);
            } else {
                glBindBufferRange(binding.target, binding.index, binding.buffer.id, binding.offset, binding.size);
            }
        }
    }
}
