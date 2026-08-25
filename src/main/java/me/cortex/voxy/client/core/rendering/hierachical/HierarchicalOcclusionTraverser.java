package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.HiZBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import static me.cortex.voxy.client.core.rendering.PrintfDebugUtil.PRINTF_processor;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_UNPACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL12.GL_UNPACK_SKIP_IMAGES;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.*;

// TODO: swap to persistent gpu threads instead of dispatching MAX_ITERATIONS of compute layers
public class HierarchicalOcclusionTraverser {
    public static final boolean HIERARCHICAL_SHADER_DEBUG = System.getProperty("voxy.hierarchicalShaderDebug", "false").equals("true");

    public static final int REQUEST_QUEUE_SIZE = 50;

    private final NodeManager nodeManager;
    private final NodeCleaner nodeCleaner;

    private final GlBuffer requestBuffer;

    private final GlBuffer nodeBuffer;
    private final GlBuffer uniformBuffer = new GlBuffer(1024).zero();
    private final GlBuffer renderList = new GlBuffer(100_000 * 4 + 4).zero();//100k sections max to render, TODO: Maybe move to render service or somewhere else



    private final GlBuffer queueMetaBuffer = new GlBuffer(4*4*5).zero();
    private final GlBuffer scratchQueueA = new GlBuffer(100_000*4).zero();
    private final GlBuffer scratchQueueB = new GlBuffer(100_000*4).zero();

    private static final int LOCAL_WORK_SIZE_BITS = 5;
    private static final int MAX_ITERATIONS = 5;

    private static int BINDING_COUNTER = 1;
    private static final int SCENE_UNIFORM_BINDING = BINDING_COUNTER++;
    private static final int REQUEST_QUEUE_BINDING = BINDING_COUNTER++;
    private static final int RENDER_QUEUE_BINDING = BINDING_COUNTER++;
    private static final int NODE_DATA_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_INDEX_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_META_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_SOURCE_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_SINK_BINDING = BINDING_COUNTER++;
    private static final int RENDER_TRACKER_BINDING = BINDING_COUNTER++;

    private final HiZBuffer hiZBuffer = new HiZBuffer();
    private final int hizSampler = glGenSamplers();

    private final Shader traversal = Shader.make(PRINTF_processor)
            .defineIf("DEBUG", HIERARCHICAL_SHADER_DEBUG)
            .define("MAX_ITERATIONS", MAX_ITERATIONS)
            .define("LOCAL_SIZE_BITS", LOCAL_WORK_SIZE_BITS)
            .define("REQUEST_QUEUE_SIZE", REQUEST_QUEUE_SIZE)

            .define("HIZ_BINDING", 0)

            .define("SCENE_UNIFORM_BINDING", SCENE_UNIFORM_BINDING)
            .define("REQUEST_QUEUE_BINDING", REQUEST_QUEUE_BINDING)
            .define("RENDER_QUEUE_BINDING", RENDER_QUEUE_BINDING)
            .define("NODE_DATA_BINDING", NODE_DATA_BINDING)

            .define("NODE_QUEUE_INDEX_BINDING", NODE_QUEUE_INDEX_BINDING)
            .define("NODE_QUEUE_META_BINDING", NODE_QUEUE_META_BINDING)
            .define("NODE_QUEUE_SOURCE_BINDING", NODE_QUEUE_SOURCE_BINDING)
            .define("NODE_QUEUE_SINK_BINDING", NODE_QUEUE_SINK_BINDING)

            .define("RENDER_TRACKER_BINDING", RENDER_TRACKER_BINDING)

            .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/traversal_dev.comp")
            .compile();


    public HierarchicalOcclusionTraverser(NodeManager nodeManager, NodeCleaner nodeCleaner) {
        this.nodeCleaner = nodeCleaner;
        this.nodeManager = nodeManager;
        this.requestBuffer = new GlBuffer(REQUEST_QUEUE_SIZE*8L+8).zero();
        this.nodeBuffer = new GlBuffer(nodeManager.maxNodeCount*16L).zero();


        glSamplerParameteri(this.hizSampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_NEAREST);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
    }

    private void uploadUniform(Viewport<?> viewport) {
        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 1024);
        int sx = MathHelper.floor(viewport.cameraX)>>5;
        int sy = MathHelper.floor(viewport.cameraY)>>5;
        int sz = MathHelper.floor(viewport.cameraZ)>>5;

        new Matrix4f(viewport.projection).mul(viewport.modelView).getToAddress(ptr); ptr += 4*4*4;

        MemoryUtil.memPutInt(ptr, sx); ptr += 4;
        MemoryUtil.memPutInt(ptr, sy); ptr += 4;
        MemoryUtil.memPutInt(ptr, sz); ptr += 4;

        MemoryUtil.memPutFloat(ptr, viewport.width); ptr += 4;

        var innerTranslation = new Vector3f((float) (viewport.cameraX-(sx<<5)), (float) (viewport.cameraY-(sy<<5)), (float) (viewport.cameraZ-(sz<<5)));
        innerTranslation.getToAddress(ptr); ptr += 4*3;

        MemoryUtil.memPutFloat(ptr, viewport.height); ptr += 4;

        MemoryUtil.memPutInt(ptr, (int) (this.renderList.size()/4-1)); ptr += 4;


        final float screenspaceAreaDecreasingSize = VoxyConfig.CONFIG.subDivisionSize*VoxyConfig.CONFIG.subDivisionSize;
        //Screen space size for descending
        MemoryUtil.memPutFloat(ptr, (float) (screenspaceAreaDecreasingSize) /(viewport.width*viewport.height)); ptr += 4;


        //VisibilityId
        MemoryUtil.memPutInt(ptr, this.nodeCleaner.visibilityId); ptr += 4;

        /*
        //Very funny and cool thing that is possible
        if (MinecraftClient.getInstance().getCurrentFps() < 30) {
            VoxyConfig.CONFIG.subDivisionSize = Math.min(VoxyConfig.CONFIG.subDivisionSize + 5, 256);
        }

        if (60 < MinecraftClient.getInstance().getCurrentFps()) {
            VoxyConfig.CONFIG.subDivisionSize = Math.max(VoxyConfig.CONFIG.subDivisionSize - 1, 32);
        }*/
    }

    private void bindings() {
        glBindBufferBase(GL_UNIFORM_BUFFER, SCENE_UNIFORM_BINDING, this.uniformBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, REQUEST_QUEUE_BINDING, this.requestBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, RENDER_QUEUE_BINDING, this.renderList.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_DATA_BINDING, this.nodeBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_META_BINDING, this.queueMetaBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, RENDER_TRACKER_BINDING, this.nodeCleaner.visibilityBuffer.id);
        glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, this.queueMetaBuffer.id);

        //Bind the hiz buffer
        glBindSampler(0, this.hizSampler);
        glBindTextureUnit(0, this.hiZBuffer.getHizTextureId());
    }

    public void doTraversal(Viewport<?> viewport, int depthBuffer) {
        //Compute the mip chain
        this.hiZBuffer.buildMipChain(depthBuffer, viewport.width, viewport.height);

        this.uploadUniform(viewport);
        //UploadStream.INSTANCE.commit(); //Done inside traversal

        this.traversal.bind();
        this.bindings();
        PrintfDebugUtil.bind();


        this.traverseInternal(this.nodeManager.getTopLevelNodeIds().size());


        this.downloadResetRequestQueue();


        //Bind the hiz buffer
        glBindSampler(0, 0);
        glBindTextureUnit(0, 0);
    }

    private void traverseInternal(int initialQueueSize) {
        {
            //Fix mesa bug
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
        }

        //Clear the render output counter
        nglClearNamedBufferSubData(this.renderList.id, GL_R32UI, 0, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, 0);


        int firstDispatchSize = (initialQueueSize+(1<<LOCAL_WORK_SIZE_BITS)-1)>>LOCAL_WORK_SIZE_BITS;
        /*
        //prime the queue Todo: maybe move after the traversal? cause then it is more efficient work since it doesnt need to wait for this before starting?
        glClearNamedBufferData(this.queueMetaBuffer.id, GL_RGBA32UI, GL_RGBA, GL_UNSIGNED_INT, new int[]{0,1,1,0});//Prime the metadata buffer, which also contains

        //Set the first entry
        glClearNamedBufferSubData(this.queueMetaBuffer.id, GL_RGBA32UI, 0, 16, GL_RGBA, GL_UNSIGNED_INT, new int[]{firstDispatchSize,1,1,initialQueueSize});
         */
        {//TODO:FIXME: THIS IS BULLSHIT BY INTEL need to fix the clearing
            long ptr = UploadStream.INSTANCE.upload(this.queueMetaBuffer, 0, 16*5);
            MemoryUtil.memPutInt(ptr +  0, firstDispatchSize);
            MemoryUtil.memPutInt(ptr +  4, 1);
            MemoryUtil.memPutInt(ptr +  8, 1);
            MemoryUtil.memPutInt(ptr + 12, initialQueueSize);
            for (int i = 1; i < 5; i++) {
                MemoryUtil.memPutInt(ptr + (i*16)+ 0, 0);
                MemoryUtil.memPutInt(ptr + (i*16)+ 4, 1);
                MemoryUtil.memPutInt(ptr + (i*16)+ 8, 1);
                MemoryUtil.memPutInt(ptr + (i*16)+12, 0);
            }
            //TODO: Move the first queue to a persistent list so its not updated every frame

            ptr = UploadStream.INSTANCE.upload(this.scratchQueueA, 0, 4L*initialQueueSize);
            for (int i = 0; i < initialQueueSize; i++) {
                MemoryUtil.memPutInt(ptr + 4L*i, this.nodeManager.getTopLevelNodeIds().getInt(i));
            }

            UploadStream.INSTANCE.commit();
        }

        glUniform1ui(NODE_QUEUE_INDEX_BINDING, 0);

        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SOURCE_BINDING, this.scratchQueueA.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SINK_BINDING, this.scratchQueueB.id);

        //Dont need to use indirect to dispatch the first iteration
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_COMMAND_BARRIER_BIT);
        glDispatchCompute(firstDispatchSize, 1,1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_COMMAND_BARRIER_BIT);

        //Dispatch max iterations
        for (int iter = 1; iter < MAX_ITERATIONS; iter++) {
            glUniform1ui(NODE_QUEUE_INDEX_BINDING, iter);

            //Flipflop buffers
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SOURCE_BINDING, ((iter & 1) == 0 ? this.scratchQueueA : this.scratchQueueB).id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SINK_BINDING, ((iter & 1) == 0 ? this.scratchQueueB : this.scratchQueueA).id);

            //Dispatch and barrier
            glDispatchComputeIndirect(iter * 4 * 4);

            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);
        }
    }


    private void downloadResetRequestQueue() {
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        DownloadStream.INSTANCE.download(this.requestBuffer, this::forwardDownloadResult);
        DownloadStream.INSTANCE.commit();
        nglClearNamedBufferSubData(this.requestBuffer.id, GL_R32UI, 0, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, 0);
    }

    public GlBuffer getRenderListBuffer() {
        return this.renderList;
    }

    private void forwardDownloadResult(long ptr, long size) {
        int count = MemoryUtil.memGetInt(ptr);ptr += 8;//its 8 since we need to skip the second value (which is empty)
        if (count < 0 || count > 50000) {
            throw new IllegalStateException("Count unexpected extreme value: " + count);
        }
        if (count > (this.requestBuffer.size()>>3)-1) {
            //This should not break the synchonization between gpu and cpu as in the traversal shader is
            // `if (atomRes < REQUEST_QUEUE_SIZE) {` which forcefully clamps to the request size

            //Logger.warn("Count over max buffer size, clamping, got count: " + count + ".");

            count = (int) ((this.requestBuffer.size()>>3)-1);
        }
        //if (count > REQUEST_QUEUE_SIZE) {
        //    Logger.warn("Count larger than 'maxRequestCount', overflow captured. Overflowed by " + (count-REQUEST_QUEUE_SIZE));
        //}
        if (count != 0) {
            //this.nodeManager.processRequestQueue(count, ptr + 8);

            //It just felt more appropriate putting the loop here
            for (int requestIndex = 0; requestIndex < count; requestIndex++) {
                long pos = ((long)MemoryUtil.memGetInt(ptr))<<32; ptr += 4;
                pos |= Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr)); ptr += 4;
                this.nodeManager.processRequest(pos);
            }
        }
    }

    public GlBuffer getNodeBuffer() {
        return this.nodeBuffer;
    }

    public void free() {
        this.traversal.free();
        this.requestBuffer.free();
        this.hiZBuffer.free();
        this.nodeBuffer.free();
        this.uniformBuffer.free();
        this.renderList.free();
        this.queueMetaBuffer.free();
        this.scratchQueueA.free();
        this.scratchQueueB.free();
        glDeleteSamplers(this.hizSampler);
    }
}
