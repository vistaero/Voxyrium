package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.section.geometry.IBasicGeometryData;
import me.cortex.voxy.client.core.rendering.util.AbstractUploadStream;
import me.cortex.voxy.client.core.rendering.util.IDeviceBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.ARBUniformBufferObject.glBindBufferBase;
import static org.lwjgl.opengl.GL30C.glBindBufferRange;
import static org.lwjgl.opengl.GL30C.glUniform1ui;
import static org.lwjgl.opengl.GL42C.GL_UNIFORM_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.*;

//OpenGL implementation of the node-manager GPU ops (moved verbatim from
// AsyncNodeManager).
public class GlNodeGpuOps implements INodeGpuOps {
    private final Shader scatterWrite = Shader.make()
            .define("INPUT_BUFFER_BINDING", 0)
            .define("OUTPUT_BUFFER1_BINDING", 1)
            .define("OUTPUT_BUFFER2_BINDING", 2)
            .add(ShaderType.COMPUTE, "voxy:util/scatter.comp")
            .compile();

    private final Shader multiMemcpy = Shader.make()
            .define("INPUT_HEADER_BUFFER_BINDING", 0)
            .define("INPUT_DATA_BUFFER_BINDING", 1)
            .define("OUTPUT_BUFFER_BINDING", 2)
            .add(ShaderType.COMPUTE, "voxy:util/memcpy.comp")
            .compile();

    @Override
    public void multiMemcpy(long headerPtr, int copies, long dataPtr, int dataSize, IBasicGeometryData geometry) {
        var stream = AbstractUploadStream.INSTANCE();
        int upCopies = stream.alignUpAlloc(copies * 16);
        int upScratchSize = stream.alignUpAlloc(dataSize);
        long ptr = stream.rawUploadAddress(upScratchSize + upCopies);
        UnsafeUtil.memcpy(headerPtr, stream.getBaseAddress() + ptr, copies * 16L);
        UnsafeUtil.memcpy(dataPtr, stream.getBaseAddress() + ptr + upCopies, dataSize);
        stream.commit();//Commit the buffer

        this.multiMemcpy.bind();
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, stream.getRawBufferId(), ptr, upCopies);
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 1, stream.getRawBufferId(), ptr + upCopies, upScratchSize);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ((GlBuffer) geometry.geometryBufferHandle()).id);

        if (copies > 500) {
            Logger.warn("Large amount of copies, lag will probably happen: " + copies);
        }

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        glDispatchCompute(copies, 1, 1);//Execute the copies
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    }

    @Override
    public void scatterWrite(long chunksPtr, int count, IDeviceBuffer nodeBuffer, IBasicGeometryData geometry) {
        var stream = AbstractUploadStream.INSTANCE();
        int chunks = (count + 3) / 4;
        int streamSize = chunks * 80;//80 bytes per chunk, it is guaranteed the buffer is big enough
        long ptr = stream.rawUploadAddress(streamSize);//Internally implicitly aligned alloc
        MemoryUtil.memCopy(chunksPtr, stream.getBaseAddress() + ptr, streamSize);
        stream.commit();//Commit the buffer

        this.scatterWrite.bind();
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, stream.getRawBufferId(), ptr, stream.alignUpAlloc(streamSize));
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ((GlBuffer) nodeBuffer).id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ((GlBuffer) geometry.metadataBufferHandle()).id);
        glUniform1ui(0, count);
        glMemoryBarrier(GL_UNIFORM_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        glDispatchCompute((count + 127) / 128, 1, 1);
        glMemoryBarrier(GL_UNIFORM_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
    }

    @Override
    public void free() {
        this.scatterWrite.free();
        this.multiMemcpy.free();
    }
}
