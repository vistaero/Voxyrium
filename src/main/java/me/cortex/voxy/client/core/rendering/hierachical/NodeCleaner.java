package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.PrintfInjector;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.ARBDirectStateAccess.glCopyNamedBufferSubData;
import static org.lwjgl.opengl.GL20.glUniform1i;
import static org.lwjgl.opengl.GL30C.glBindBufferRange;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.*;

//Uses compute shaders to compute the last 256 rendered section (64x64 workgroup size maybe)
// done via warp level sort, then workgroup sort (shared memory), (/w sorting network)
// then use bubble sort (/w fast path going to middle or 2 subdivisions deep) the bubble it up
// can do incremental sorting pass aswell, so only scan and sort a rolling sector of sections
// (over a few frames to not cause lag, maybe)


//TODO : USE THIS IN HierarchicalOcclusionTraverser instead of other shit
public class NodeCleaner {
    //TODO: use batch_visibility_set to clear visibility data when nodes are removed!! (TODO: nodeManager will need to forward info to this)


    private static final int SORTING_WORKER_SIZE = 64;
    private static final int OUTPUT_COUNT = 256;


    private static final int BATCH_SET_SIZE = 2048;


    private final AutoBindingShader sorter = Shader.makeAuto(PrintfDebugUtil.PRINTF_processor)
            .define("WORK_SIZE", SORTING_WORKER_SIZE)
            .define("OUTPUT_SIZE", OUTPUT_COUNT)
            .define("VISIBILITY_BUFFER_BINDING", 1)
            .define("OUTPUT_BUFFER_BINDING", 2)
            .define("NODE_DATA_BINDING", 3)
            .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/cleaner/sort_visibility.comp")
            .compile();

    private final AutoBindingShader resultTransformer = Shader.makeAuto()
            .define("OUTPUT_SIZE", OUTPUT_COUNT)
            .define("MIN_ID_BUFFER_BINDING", 0)
            .define("NODE_BUFFER_BINDING", 1)
            .define("OUTPUT_BUFFER_BINDING", 2)
            .define("VISIBILITY_BUFFER_BINDING", 3)
            .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/cleaner/result_transformer.comp")
            .compile();

    private final AutoBindingShader batchClear = Shader.makeAuto()
            .define("VISIBILITY_BUFFER_BINDING", 0)
            .define("LIST_BUFFER_BINDING", 1)
            .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/cleaner/batch_visibility_set.comp")
            .compile();


    final GlBuffer visibilityBuffer;
    private final GlBuffer outputBuffer = new GlBuffer(OUTPUT_COUNT*4+OUTPUT_COUNT*8);//Scratch + output
    private final GlBuffer scratchBuffer = new GlBuffer(BATCH_SET_SIZE*4);//Scratch buffer for setting ids with

    private final IntOpenHashSet allocIds = new IntOpenHashSet();
    private final IntOpenHashSet freeIds = new IntOpenHashSet();

    private final NodeManager nodeManager;
    int visibilityId = 0;


    public NodeCleaner(NodeManager nodeManager) {
        this.nodeManager = nodeManager;
        this.visibilityBuffer = new GlBuffer(nodeManager.maxNodeCount*4L).zero();
        this.visibilityBuffer.fill(-1);

        this.batchClear
                .ssbo("VISIBILITY_BUFFER_BINDING", this.visibilityBuffer)
                .ssbo("LIST_BUFFER_BINDING", this.scratchBuffer);

        this.sorter
                .ssbo("VISIBILITY_BUFFER_BINDING", this.visibilityBuffer)
                .ssbo("OUTPUT_BUFFER_BINDING", this.outputBuffer);

        this.nodeManager.setClear(new NodeManager.ICleaner() {
            @Override
            public void alloc(int id) {
                NodeCleaner.this.allocIds.add(id);
                NodeCleaner.this.freeIds.remove(id);
            }

            @Override
            public void move(int from, int to) {
                NodeCleaner.this.allocIds.remove(to);
                glCopyNamedBufferSubData(NodeCleaner.this.visibilityBuffer.id, NodeCleaner.this.visibilityBuffer.id, 4L*from, 4L*to, 4);
            }

            @Override
            public void free(int id) {
                NodeCleaner.this.freeIds.add(id);
                NodeCleaner.this.allocIds.remove(id);
            }
        });
    }


    public void tick(GlBuffer nodeDataBuffer) {
        this.visibilityId++;

        this.setIds(this.allocIds, this.visibilityId);
        this.setIds(this.freeIds, -1);

        if (this.shouldCleanGeometry()) {
            var gm = this.nodeManager.getGeometryManager();

            int c = (int) (((((double) gm.getUsedCapacity() / gm.geometryCapacity) - 0.75) * 4 * 10) + 1);
            c = 1;
            for (int i = 0; i < c; i++) {
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                this.outputBuffer.fill(this.nodeManager.maxNodeCount - 2);//TODO: maybe dont set to zero??


                this.sorter.bind();
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, nodeDataBuffer.id);


                //TODO: choose whether this is in nodeSpace or section/geometryId space
                //
                glDispatchCompute((this.nodeManager.getCurrentMaxNodeId() + SORTING_WORKER_SIZE - 1) / SORTING_WORKER_SIZE, 1, 1);
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

                this.resultTransformer.bind();
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, this.outputBuffer.id, 0, 4 * OUTPUT_COUNT);
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, nodeDataBuffer.id);
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 2, this.outputBuffer.id, 4 * OUTPUT_COUNT, 8 * OUTPUT_COUNT);
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, this.visibilityBuffer.id);
                glUniform1ui(0, this.visibilityId);

                glDispatchCompute(1, 1, 1);
                //glFinish();

                DownloadStream.INSTANCE.download(this.outputBuffer, 4 * OUTPUT_COUNT, 8 * OUTPUT_COUNT, this::onDownload);
                //glFinish();
            }
        }
    }

    private boolean shouldCleanGeometry() {
        //// if there is less than 200mb of space, clean
        //return this.nodeManager.getGeometryManager().getRemainingCapacity() < 1_000_000_000L;

        //If used more than 75% of geometry buffer
        return 3<((double)this.nodeManager.getGeometryManager().getUsedCapacity())/((double)this.nodeManager.getGeometryManager().getRemainingCapacity());
    }

    private void onDownload(long ptr, long size) {
        //StringBuilder b = new StringBuilder();
        //Long2IntOpenHashMap aa = new Long2IntOpenHashMap();
        for (int i = 0; i < OUTPUT_COUNT; i++) {
            long pos = Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr + 8 * i))<<32;
            pos     |= Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr + 8 * i + 4));
            //aa.addTo(pos, 1);
            if (pos == -1) {
                //TODO: investigate how or what this happens
                continue;
            }
            //if (WorldEngine.getLevel(pos) == 4 && WorldEngine.getX(pos)<-32) {
            //    int a = 0;
            //}
            this.nodeManager.removeNodeGeometry(pos);
            //b.append(", ").append(WorldEngine.pprintPos(pos));//.append(((int)((pos>>32)&0xFFFFFFFFL)));//
        }
        int a = 0;

        //System.out.println(b);
    }

    private void setIds(IntOpenHashSet collection, int setTo) {
        if (!collection.isEmpty()) {
            this.batchClear.bind();
            var iter = collection.iterator();
            while (iter.hasNext()) {
                int cnt = Math.min(collection.size(), BATCH_SET_SIZE);
                long ptr = UploadStream.INSTANCE.upload(this.scratchBuffer, 0, cnt * 4L);
                for (int i = 0; i < cnt; i++) {
                    MemoryUtil.memPutInt(ptr + i * 4, iter.nextInt());
                    iter.remove();
                }
                UploadStream.INSTANCE.commit();
                glUniform1ui(0, cnt);
                glUniform1ui(1, setTo);
                glDispatchCompute((cnt+127)/128, 1, 1);
            }
        }
    }

    public void free() {
        this.sorter.free();
        this.visibilityBuffer.free();
        this.outputBuffer.free();
        this.scratchBuffer.free();
        this.batchClear.free();
        this.resultTransformer.free();
    }
}
