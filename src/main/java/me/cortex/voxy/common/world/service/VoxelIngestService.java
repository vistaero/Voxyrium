package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.thread.ServiceSlice;
import me.cortex.voxy.common.thread.ServiceThreadPool;
import me.cortex.voxy.commonImpl.IVoxyWorld;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.concurrent.ConcurrentLinkedDeque;

public class VoxelIngestService {
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private final ServiceSlice threads;
    private record IngestSection(int cx, int cy, int cz, WorldEngine world, ChunkSection section, ChunkNibbleArray blockLight, ChunkNibbleArray skyLight){}
    private final ConcurrentLinkedDeque<IngestSection> ingestQueue = new ConcurrentLinkedDeque<>();

    public VoxelIngestService(ServiceThreadPool pool) {
        this.threads = pool.createServiceNoCleanup("Ingest service", 100, ()-> this::processJob);
    }

    private void processJob() {
        var task = this.ingestQueue.pop();
        var section = task.section;
        var vs = SECTION_CACHE.get().setPosition(task.cx, task.cy, task.cz);

        if (section.isEmpty() && task.blockLight==null && task.skyLight==null) {//If the chunk section has lighting data, propagate it
            task.world.insertUpdate(vs.zero());
        } else {
            ILightingSupplier supplier = (x,y,z) -> (byte) 0;
            var sla = task.skyLight;
            var bla = task.blockLight;
            boolean sl = sla != null && !sla.isUninitialized();
            boolean bl = bla != null && !bla.isUninitialized();
            if (sl || bl) {
                if (sl && bl) {
                    supplier = (x,y,z)-> {
                        int block = Math.min(15,bla.get(x, y, z));
                        int sky = Math.min(15,sla.get(x, y, z));
                        return (byte) (sky|(block<<4));
                    };
                } else if (bl) {
                    supplier = (x,y,z)-> {
                        int block = Math.min(15,bla.get(x, y, z));
                        int sky = 0;
                        return (byte) (sky|(block<<4));
                    };
                } else {
                    supplier = (x,y,z)-> {
                        int block = 0;
                        int sky = Math.min(15,sla.get(x, y, z));
                        return (byte) (sky|(block<<4));
                    };
                }
            }
            VoxelizedSection csec = WorldConversionFactory.convert(
                    SECTION_CACHE.get(),
                    task.world.getMapper(),
                    section.getBlockStateContainer(),
                    section.getBiomeContainer(),
                    supplier
            );
            WorldConversionFactory.mipSection(csec, task.world.getMapper());
            task.world.insertUpdate(csec);
        }
    }

    private static boolean shouldIngestSection(ChunkSection section, int cx, int cy, int cz) {
        return true;
    }

    public void enqueueIngest(WorldChunk chunk, boolean ignoreOnNullWorld) {
        var engine = ((IVoxyWorld)chunk.getWorld()).getWorldEngine();
        if (engine == null) {
            if (!ignoreOnNullWorld) {
                Logger.error("Could not ingest chunk as does not have world engine");
            }
            return;
        }
        this.enqueueIngest(engine, chunk);
    }

    public void enqueueIngest(WorldEngine engine, WorldChunk chunk) {
        if (!engine.isLive()) {
            throw new IllegalStateException("Tried inserting chunk into WorldEngine that was not alive");
        }
        var lightingProvider = chunk.getWorld().getLightingProvider();
        var blp = lightingProvider.get(LightType.BLOCK);
        var slp = lightingProvider.get(LightType.SKY);

        int i = chunk.getBottomSectionCoord() - 1;
        for (var section : chunk.getSectionArray()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            //if (section.isEmpty()) continue;
            var pos = ChunkSectionPos.from(chunk.getPos(), i);
            var bl = blp.getLightSection(pos);
            if (!(bl == null || bl.isUninitialized())) {
                bl = bl.copy();
            } else {
                bl = null;
            }
            var sl = slp.getLightSection(pos);
            if (!(sl == null || sl.isUninitialized())) {
                sl = sl.copy();
            } else {
                sl = null;
            }

            if ((bl == null && sl == null) && section.isEmpty()) {
                continue;
            }

            this.ingestQueue.add(new IngestSection(chunk.getPos().x, i, chunk.getPos().z, engine, section, bl, sl));
            this.threads.execute();
        }
    }

    public int getTaskCount() {
        return this.threads.getJobCount();
    }

    public void shutdown() {
        this.threads.shutdown();
    }
}
