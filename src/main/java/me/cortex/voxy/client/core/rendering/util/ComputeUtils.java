package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import org.lwjgl.system.MemoryUtil;

import java.util.function.Supplier;

import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL20.glUniform1i;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL30.glUniform1ui;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.glDispatchCompute;

//Utilities for common operations not suited for basic gl functions
// such as sparse memory setting

//TODO CLEAN THIS SHIT UP
public class ComputeUtils {
    private ComputeUtils() {}
    public static ComputeUtils INSTANCE = new ComputeUtils();
    private static final int SETTING_BUFFER_BINDING = 1;
    private static final int ENTRY_BUFFER_BINDING = 2;

    //TODO: FIXME! This should itself be just a raw streaming buffer/mapped ptr (probably)
    private final GlBuffer SCRATCH = new GlBuffer(1<<20);//1 MB scratch buffer... this should be enough.. right?

    private int maxCount;
    private int count;
    private long ptr;

    private final Supplier<Shader> uintSetShader = makeCacheSetShader("uint");
    public void prepSetUint(int maxCount) {
        if (this.count != 0 || this.maxCount != 0 || this.ptr != 0) {
            throw new IllegalStateException();
        }
        this.ptr = UploadStream.INSTANCE.upload(SCRATCH, 0, maxCount*8L);
        this.maxCount = maxCount;
    }

    public void pushSetUint(int index, int value) {
        //For uint it goes
        // {uint value; uint index;}
        if (this.maxCount <= this.count++) {
            throw new IllegalStateException("Pushed to many values to prepared set");
        }
        MemoryUtil.memPutInt(this.ptr, value); this.ptr += 4;
        MemoryUtil.memPutInt(this.ptr, index); this.ptr += 4;
    }

    public void finishSetUint(GlBuffer dst) {
        UploadStream.INSTANCE.commit();
        this.uintSetShader.get().bind();
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, SETTING_BUFFER_BINDING, dst.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, ENTRY_BUFFER_BINDING, this.SCRATCH.id);
        glUniform1i(0, this.count);
        glDispatchCompute((this.count+127)/128, 1, 1);
        this.ptr = 0;
        this.maxCount = 0;
        this.count = 0;
    }





    private static Supplier<Shader> makeCacheSetShader(String type) {
        return makeAndCache(()->makeSetShader(type));
    }

    private static Shader makeSetShader(String type) {
        return Shader.make()
                .define("TYPE", type)
                .define("SETTING_BUFFER_BINDING", SETTING_BUFFER_BINDING)
                .define("ENTRY_BUFFER_BINDING", ENTRY_BUFFER_BINDING)
                .add(ShaderType.COMPUTE, "voxy:util/set.comp")
                .compile();
    }

    private static <T> Supplier<T> makeAndCache(Supplier<T> maker) {
        Object[] value = new Object[1];
        boolean[] hasSet = new boolean[1];
        return ()->{
            if (hasSet[0]) {
                return (T) value[0];
            } else {
                var val = maker.get();
                hasSet[0] = true;
                value[0] = val;
                return val;
            }
        };
    }
}
