package me.cortex.voxy.client.core;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.*;
import me.cortex.voxy.client.core.rendering.building.RenderDataFactory4;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.post.PostProcessing;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.saver.ContextSelectionSystem;
import me.cortex.voxy.client.taskbar.Taskbar;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import me.cortex.voxy.common.thread.ServiceThreadPool;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.lwjgl.opengl.GL30C.*;

//Core class that ingests new data from sources and updates the required systems

//3 primary services:
// ingest service: this takes in unloaded chunk events from the client, processes the chunk and critically also updates the lod view of the world
// render data builder service: this service builds the render data from build requests it also handles the collecting of build data for the selected region (only axis aligned single lod tasks)
// serialization service: serializes changed world data and ensures that the database and any loaded data are in sync such that the database can never be more updated than loaded data, also performs compression on serialization

//there are multiple subsystems
//player tracker system (determines what lods are loaded and used by the player)
//updating system (triggers render data rebuilds when something from the ingest service causes an LOD change)
//the render system simply renders what data it has, its responsable for gpu memory layouts in arenas and rendering in an optimal way, it makes no requests back to any of the other systems or services, it just applies render data updates

//There is strict forward only dataflow
//Ingest -> world engine -> raw render data -> render data



//REDESIGN THIS PIECE OF SHIT SPAGETTY SHIT FUCK
// like Get rid of interactor and renderer being seperate just fucking put them together
// fix the callback bullshit spagetti
//REMOVE setRenderGen like holy hell
public class VoxelCore {
    private final WorldEngine world;
    public final ServiceThreadPool serviceThreadPool;
    public final WorldImportWrapper importer;

    public VoxelCore(ContextSelectionSystem.Selection worldSelection) {
        var cfg = worldSelection.getConfig();
        this.serviceThreadPool = new ServiceThreadPool(VoxyConfig.CONFIG.serviceThreads);

        this.world = null;//worldSelection.createEngine(this.serviceThreadPool);
        Logger.info("Initializing voxy core");

        this.importer = new WorldImportWrapper(this.serviceThreadPool, this.world);

        Logger.info("Voxy core initialized");

        //this.verifyTopNodeChildren(0,0,0);

        //this.testMeshingPerformance();

        //this.testDbPerformance();
        //this.testFullMesh();
    }

    public void addDebugInfo(List<String> debug) {
        debug.add("");
        debug.add("");
        /*
        debug.add("Ingest service tasks: " + this.world.ingestService.getTaskCount());
        debug.add("Saving service tasks: " + this.world.savingService.getTaskCount());
        debug.add("Render service tasks: " + this.renderGen.getTaskCount());
         */
        this.world.addDebugData(debug);

    }

    //Note: when doing translucent rendering, only need to sort when generating the geometry, or when crossing into the center zone
    // cause in 99.99% of cases the sections dont need to be sorted
    // since they are AABBS crossing the normal is impossible without one of the axis being equal

    public void shutdown() {

        //if (Thread.currentThread() != this.shutdownThread) {
        //    Runtime.getRuntime().removeShutdownHook(this.shutdownThread);
        //}

        //this.world.getMapper().forceResaveStates();
        this.importer.shutdown();
        Logger.info("Shutting down world engine");
        try {this.world.free();} catch (Exception e) {Logger.error("Error shutting down world engine", e);}
        Logger.info("Shutting down service thread pool");
        this.serviceThreadPool.shutdown();
        Logger.info("Voxel core shut down");
    }

    public WorldEngine getWorldEngine() {
        return this.world;
    }





    private void verifyTopNodeChildren(int X, int Y, int Z) {
        for (int lvl = 0; lvl < 5; lvl++) {
            for (int y = (Y<<5)>>lvl; y < ((Y+1)<<5)>>lvl; y++) {
                for (int x = (X<<5)>>lvl; x < ((X+1)<<5)>>lvl; x++) {
                    for (int z = (Z<<5)>>lvl; z < ((Z+1)<<5)>>lvl; z++) {
                        if (lvl == 0) {
                            var own = this.world.acquire(lvl, x, y, z);
                            if ((own.getNonEmptyChildren() != 0) ^ (own.getNonEmptyBlockCount() != 0)) {
                                Logger.error("Lvl 0 node not marked correctly " + WorldEngine.pprintPos(own.key));
                            }
                            own.release();
                        } else {
                            byte msk = 0;
                            for (int child = 0; child < 8; child++) {
                                var section = this.world.acquire(lvl-1, (child&1)+(x<<1), ((child>>2)&1)+(y<<1), ((child>>1)&1)+(z<<1));
                                msk |= (byte) (section.getNonEmptyBlockCount()!=0?(1<<child):0);
                                section.release();
                            }
                            var own = this.world.acquire(lvl, x, y, z);
                            if (own.getNonEmptyChildren() != msk) {
                                Logger.error("Section empty child mask not correct " + WorldEngine.pprintPos(own.key) + " got: " + String.format("%8s", Integer.toBinaryString(Byte.toUnsignedInt(own.getNonEmptyChildren()))).replace(' ', '0') + " expected: " + String.format("%8s", Integer.toBinaryString(Byte.toUnsignedInt(msk))).replace(' ', '0'));
                            }
                            own.release();
                        }
                    }
                }
            }
        }
    }






    private void testMeshingPerformance() {
        var modelService = new ModelBakerySubsystem(this.world.getMapper());
        var factory = new RenderDataFactory4(this.world, modelService.factory, false);

        List<WorldSection> sections = new ArrayList<>();

        System.out.println("Loading sections");
        for (int x = -17; x <= 17; x++) {
            for (int z = -17; z <= 17; z++) {
                for (int y = -1; y <= 4; y++) {
                    var section = this.world.acquire(0, x, y, z);

                    int nonAir = 0;
                    for (long state : section.copyData()) {
                        nonAir += Mapper.isAir(state)?0:1;
                        modelService.requestBlockBake(Mapper.getBlockId(state));
                    }

                    if (nonAir > 500 && Math.abs(x) <= 16 && Math.abs(z) <= 16) {
                        sections.add(section);
                    } else {
                        section.release();
                    }
                }
            }
        }

        System.out.println("Baking models");
        {
            //Bake everything
            while (!modelService.areQueuesEmpty()) {
                modelService.tick();
                glFinish();
            }
        }

        System.out.println("Ready!");

        {
            int iteration = 0;
            while (true) {
                long start = System.currentTimeMillis();
                for (var section : sections) {
                    var mesh = factory.generateMesh(section);

                    mesh.free();
                }
                long delta = System.currentTimeMillis() - start;
                System.out.println("Iteration: " + (iteration++) + " took " + delta + "ms, for an average of " + ((float)delta/sections.size()) + "ms per section");
                //System.out.println("Quad count: " + factory.quadCount);
            }
        }

    }


    private void testDbPerformance() {
        Random r = new Random(123456);
        r.nextLong();
        long start = System.currentTimeMillis();
        int c = 0;
        for (int i = 0; i < 500_000; i++) {
            if (i == 20_000) {
                c = 0;
                start = System.currentTimeMillis();
            }
            c++;
            int x = (r.nextInt(256*2+2)-256)>>1;//-32
            int z = (r.nextInt(256*2+2)-256)>>1;//-32
            int y = 0;
            int lvl = 0;//r.nextInt(5);
            this.world.acquire(WorldEngine.getWorldSectionId(lvl, x>>lvl, y>>lvl, z>>lvl)).release();
        }
        long delta = System.currentTimeMillis() - start;
        System.out.println("Total "+delta+"ms " + ((double)delta/c) + "ms average" );
    }



    private void testFullMesh() {
        var modelService = new ModelBakerySubsystem(this.world.getMapper());
        var completedCounter = new AtomicInteger();
        var generationService = new RenderGenerationService(this.world, modelService, this.serviceThreadPool, a-> {completedCounter.incrementAndGet(); a.free();}, false);


        var r = new Random(12345);
        {
            for (int i = 0; i < 10_000; i++) {
                int x = (r.nextInt(256*2+2)-256)>>1;//-32
                int z = (r.nextInt(256*2+2)-256)>>1;//-32
                int y = r.nextInt(10)-2;
                int lvl = 0;//r.nextInt(5);
                long key = WorldEngine.getWorldSectionId(lvl, x>>lvl, y>>lvl, z>>lvl);
                generationService.enqueueTask(key);
            }
            int i = 0;
            while (true) {
                modelService.tick();
                if (i++%5000==0)
                    System.out.println(completedCounter.get());
                glFinish();
                List<String> a = new ArrayList<>();
                generationService.addDebugData(a);
                if (a.getFirst().endsWith(" 0")) {
                    break;
                }
            }
        }

        System.out.println("Running benchmark");
        while (true)
        {
            completedCounter.set(0);
            long start = System.currentTimeMillis();
            int C = 200_000;
            for (int i = 0; i < C; i++) {
                int x = (r.nextInt(256 * 2 + 2) - 256) >> 1;//-32
                int z = (r.nextInt(256 * 2 + 2) - 256) >> 1;//-32
                int y = r.nextInt(10) - 2;
                int lvl = 0;//r.nextInt(5);
                long key = WorldEngine.getWorldSectionId(lvl, x >> lvl, y >> lvl, z >> lvl);
                generationService.enqueueTask(key);
            }
            //int i = 0;
            while (true) {
                //if (i++%5000==0)
                //    System.out.println(completedCounter.get());
                modelService.tick();
                glFinish();
                List<String> a = new ArrayList<>();
                generationService.addDebugData(a);
                if (a.getFirst().endsWith(" 0")) {
                    break;
                }
            }
            long delta = (System.currentTimeMillis()-start);
            System.out.println("Time "+delta+"ms count: " + completedCounter.get() + " avg per mesh: " + ((double)delta/completedCounter.get()) + "ms");
            if (false)
                break;
        }
        generationService.shutdown();
        modelService.shutdown();
    }
}
