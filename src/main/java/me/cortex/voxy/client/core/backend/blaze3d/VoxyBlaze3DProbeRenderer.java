package me.cortex.voxy.client.core.backend.blaze3d;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Vulkan-safe Voxy render path implemented through Blaze3D. It keeps per-section
 * GPU meshes so the configured Voxy render distance can be populated incrementally
 * without a long render-thread stall.
 */
public final class VoxyBlaze3DProbeRenderer {
    private static final VertexFormat LOD_VERTEX_FORMAT = DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR;
    private static final int MARKER_VERTEX_COUNT = 36;
    private static final int MARKER_BUFFER_SIZE = MARKER_VERTEX_COUNT * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize();
    private static final int LOG_INTERVAL_FRAMES = 600;
    private static final int SECTION_EDGE = 32;
    private static final int LOD_INITIAL_REFRESH_INTERVAL_FRAMES = 1;
    private static final int LOD_STEADY_REFRESH_INTERVAL_FRAMES = 120;
    private static final int LOD_INITIAL_MESHES_PER_REFRESH = 8;
    private static final long LOD_INITIAL_MESH_BUILD_BUDGET_NANOS = 4_000_000L;
    private static final int LOD_SELECTION_NODES_PER_FRAME = 512;
    private static final long LOD_SELECTION_BUDGET_NANOS = 4_000_000L;
    private static final double LOD_SELECTION_MOVEMENT_BLOCKS = 8.0;
    private static final float LOD_SELECTION_MATRIX_EPSILON = 0.001f;
    // Voxy's native shader deliberately ignores the far plane so configured LoD distance, not
    // Minecraft's projection distance, decides how far the hierarchy may render.
    private static final int VOXY_FRUSTUM_PLANE_MASK = FrustumIntersection.PLANE_MASK_NX
            | FrustumIntersection.PLANE_MASK_PX
            | FrustumIntersection.PLANE_MASK_NY
            | FrustumIntersection.PLANE_MASK_PY
            | FrustumIntersection.PLANE_MASK_NZ;
    private static final int MAX_LOD_QUAD_COUNT_PER_SECTION = 65536;
    private static final int LOD_VERTICES_PER_QUAD = 4;
    private static final int LOD_INDICES_PER_QUAD = 6;
    private static final int LOD_INDEX_BUFFER_SIZE = MAX_LOD_QUAD_COUNT_PER_SECTION * LOD_INDICES_PER_QUAD * Integer.BYTES;
    private static final float LOD_FAR_CLIP_BLOCKS = 16.0f * 3000.0f;
    private static final int PROJECTION_UNIFORM_BYTES = 16 * Float.BYTES;
    private static final int COMPOSITE_VERTEX_COUNT = 4;
    private static final int COMPOSITE_VERTEX_BUFFER_SIZE = COMPOSITE_VERTEX_COUNT * DefaultVertexFormat.POSITION.getVertexSize();
    private static final String LOD_GEOMETRY_BUDGET_PROPERTY = "voxy.blaze3d.geometryBudgetMiB";
    private static final long LOD_GEOMETRY_BUDGET_BYTES = readGeometryBudgetBytes();
    private static final int MATERIAL_LOG_LIMIT = 12;
    private static final long PERFORMANCE_LOG_INTERVAL_NANOS = 1_000_000_000L;
    private static final long PERFORMANCE_SLOW_FRAME_NANOS = 50_000_000L;
    private static final float LOD_MARKER_SIZE = 12.0f;
    private static final float[] NO_CORNER_OCCLUSION = {1.0f, 1.0f, 1.0f, 1.0f};
    private static final Identifier DIRT_SPRITE = Identifier.fromNamespaceAndPath("minecraft", "block/dirt");
    private static final float[][] UNIT_CUBE_FACE_POSITIONS = {
            {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1},
            {0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 0},
            {0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0},
            {1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1},
            {1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0},
            {0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1}
    };
    private static final RenderPipeline MARKER_PIPELINE = createTexturedMarkerPipeline();
    // Minecraft 26.2 uses reverse-Z depth: larger values are closer to the camera.
    private static final RenderPipeline LOD_PIPELINE = createTexturedTerrainPipeline(
            "blaze3d_lod_probe_terrain", CompareOp.GREATER_THAN, true, false);
    private static final RenderPipeline LOD_WATER_PIPELINE = createTexturedTerrainPipeline(
            "blaze3d_lod_probe_water", CompareOp.GREATER_THAN, true, false);
    private static final RenderPipeline LOD_OVERLAY_PIPELINE = createTexturedTerrainPipeline(
            "blaze3d_lod_probe_terrain_overlay", CompareOp.ALWAYS_PASS, false, false);
    private static final RenderPipeline LOD_WATER_OVERLAY_PIPELINE = createTexturedTerrainPipeline(
            "blaze3d_lod_probe_water_overlay", CompareOp.ALWAYS_PASS, false, true);
    private static final RenderPipeline LOD_COMPOSITE_PIPELINE = createCompositePipeline(
            "blaze3d_lod_composite", true, false);
    private static final RenderPipeline LOD_TRANSLUCENT_COMPOSITE_PIPELINE = createCompositePipeline(
            "blaze3d_lod_translucent_composite", false, true);

    private static RenderPipeline createTexturedMarkerPipeline() {
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("voxy", "blaze3d_lod_probe_marker"))
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withVertexShader("core/position_tex_color")
                .withFragmentShader("core/position_tex_color")
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN, false))
                .withCull(true)
                .build();
    }

    private static RenderPipeline createTexturedTerrainPipeline(String name, CompareOp depthTest, boolean writeDepth, boolean translucent) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("voxy", name))
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
            .withVertexShader(Identifier.fromNamespaceAndPath("voxy", "core/blaze3d_lod_terrain"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("voxy", "core/blaze3d_lod_terrain"))
            .withVertexBinding(0, LOD_VERTEX_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withDepthStencilState(new DepthStencilState(depthTest, writeDepth))
            .withCull(true);
        if (translucent) {
            builder.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT));
        }
        return builder.build();
    }

    private static RenderPipeline createCompositePipeline(String name, boolean writeDepth, boolean translucent) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("voxy", name))
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
                .withVertexShader(Identifier.fromNamespaceAndPath("voxy", "core/blaze3d_lod_composite"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxy", "core/blaze3d_lod_composite"))
                .withVertexBinding(0, DefaultVertexFormat.POSITION)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLE_STRIP)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN, writeDepth))
                .withCull(false);
        if (translucent) {
            builder.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT));
        }
        return builder.build();
    }

    private static GpuBuffer markerVertexBuffer;
    private static GpuBuffer lodIndexBuffer;
    private static GpuBuffer lodProjectionBuffer;
    private static GpuBuffer compositeVertexBuffer;
    private static GpuTexture lodColorTexture;
    private static GpuTextureView lodColorTextureView;
    private static GpuTexture lodDepthTexture;
    private static GpuTextureView lodDepthTextureView;
    private static final Map<Long, LodSectionMesh> lodMeshes = new HashMap<>();
    private static final Map<Long, Long> lodMeshFingerprints = new HashMap<>();
    private static final Map<Integer, BlockRenderDefinition> blockRenderDefinitions = new HashMap<>();
    private static final Map<Integer, Biome> voxyBiomes = new HashMap<>();
    private static final Map<TintKey, Integer> tintColors = new HashMap<>();
    // Sodium collects these on the render thread before its translucent pass.  The OpenGL path
    // rasterizes the same sections into a depth mask; Blaze3D currently consumes them while meshing.
    private static final Set<Long> visibleVanillaSections = new HashSet<>();
    private static final Set<Long> collectedVisibleVanillaSections = new HashSet<>();
    private static long visibleVanillaMaskRevision;
    private static boolean failed;
    private static boolean initialized;
    private static long frameCount;
    private static long lastLodRefreshFrame = Long.MIN_VALUE;
    private static boolean loggedMissingLodSection;
    private static LodRenderGrid lodRenderGrid;
    private static List<LodSectionCoordinate> selectedLodSections = List.of();
    private static Set<Long> selectedLodSectionKeys = Set.of();
    private static List<LodSectionCoordinate> transitionBuildSections = List.of();
    private static final Map<Long, Integer> transitionPendingChildren = new HashMap<>();
    private static final Set<Long> transitionParentKeys = new HashSet<>();
    private static final Set<Long> transitionCoverageReadyKeys = new HashSet<>();
    private static final FrustumIntersection currentDrawFrustum = new FrustumIntersection();
    private static boolean currentDrawFrustumValid;
    private static double currentDrawCameraX;
    private static double currentDrawCameraY;
    private static double currentDrawCameraZ;
    private static int nextLodSectionRefresh;
    private static int nextLodSectionValidation;
    private static LodSelectionTask pendingLodSelection;
    private static boolean lodSelectionTransitionPending;
    private static long lastLodSelectionFrame = Long.MIN_VALUE;
    private static double lastLodSelectionX = Double.NaN;
    private static double lastLodSelectionY = Double.NaN;
    private static double lastLodSelectionZ = Double.NaN;
    private static float lastSubdivisionSize = Float.NaN;
    private static int lastLodSelectionForcedLevel = Integer.MIN_VALUE;
    private static int lastVanillaRenderDistance = -1;
    private static int lastLodSelectionViewportWidth = -1;
    private static int lastLodSelectionViewportHeight = -1;
    private static Matrix4f lastLodSelectionViewProjection;
    private static VanillaRenderBoundary activeVanillaBoundary;
    private static volatile int sodiumRenderDistanceChunks = -1;
    private static volatile int vanillaTransitionChunks = 1;
    private static float sourceFogStart;
    private static float sourceFogEnd;
    private static float activeFogStart;
    private static float activeFogEnd;
    private static boolean activeDenseFog;
    private static VanillaBoundaryKey lastVanillaBoundaryKey;
    private static int loggedMaterialDefinitions;
    private static boolean lodGridInvalidated = true;
    private static boolean renderLodAboveTerrain;
    private static volatile boolean testCubeVisible;
    private static volatile boolean performanceProfiling;
    private static long lastPerformanceLogNanos;
    private static long profiledSelectionNanos;
    private static long profiledMeshBuildNanos;
    // These counters are intentionally independent of the performance profiler. They make an
    // empty LoD frame diagnosable without changing the renderer's scheduling behaviour.
    private static long lodMeshBuildAttempts;
    private static long lodMeshUploads;
    private static long lodMeshUnchanged;
    private static long lodMeshMissingSections;
    private static long lodMeshEmpty;
    private static long lodGeometryBytes;
    private static long peakLodGeometryBytes;
    private static long lodGeometryBudgetRejections;
    private static boolean lodGeometryBudgetExhausted;
    private static boolean loggedLodGeometryBudgetExhaustion;
    private static int lastOpaqueDrawCalls;
    private static int lastOpaqueVertices;
    private static int lastWaterDrawCalls;
    private static int lastWaterVertices;
    private static int lastSelectionFrustumCulledNodes;
    private static int lastSelectionScreenTestedNodes;
    private static long lodBranchHandoffs;
    private static long lodSelectionRuns;
    private static long lodTopologyChanges;
    private static long lodPriorityOnlyChanges;
    private static String lastSelectionReason = "none";
    // -1 uses Voxy's screen-space hierarchy. Non-negative values force one exact LoD layer.
    // Voxy follows the usual convention: L0 is the finest layer and MAX_LOD_LAYER is coarsest.
    private static volatile int forcedLodLevel = -1;

    private VoxyBlaze3DProbeRenderer() {
    }

    public static boolean toggleTestCube() {
        testCubeVisible = !testCubeVisible;
        Logger.info("Blaze3D test cube " + (testCubeVisible ? "enabled" : "disabled") + ".");
        return testCubeVisible;
    }

    public static boolean togglePerformanceProfiler() {
        performanceProfiling = !performanceProfiling;
        lastPerformanceLogNanos = 0;
        Logger.info("Blaze3D Voxy performance profiler " + (performanceProfiling ? "enabled" : "disabled") + ".");
        return performanceProfiling;
    }

    public static int setLodQualityLevel(int requestedLevel) {
        int clampedLevel = Math.max(-1, Math.min(WorldEngine.MAX_LOD_LAYER, requestedLevel));
        if (forcedLodLevel == clampedLevel) {
            return clampedLevel;
        }
        forcedLodLevel = clampedLevel;
        pendingLodSelection = null;
        lastLodRefreshFrame = Long.MIN_VALUE;
        lastLodSelectionFrame = Long.MIN_VALUE;
        if (clampedLevel < 0) {
            Logger.info("Blaze3D LoD mode changed to automatic screen-space selection.");
        } else {
            Logger.info("Blaze3D LoD level forced globally to L" + clampedLevel
                    + " (one voxel per " + voxelSize(clampedLevel) + " world blocks; L0 is finest).");
        }
        return clampedLevel;
    }

    public static String getLodDebugSummary() {
        String selectionState = pendingLodSelection == null
                ? "idle"
                : "pending=" + pendingLodSelection.pending().size() + ", processed=" + pendingLodSelection.processedNodes;
        return "LoD state: build=" + VoxyCommon.BUILD_ID
                + ", mode=" + lodModeDescription()
                + ", selected=" + selectedLodSections.size() + "[" + formatLevelHistogram(selectedLodSections) + "]"
                + ", ready=" + countReadySelectedSections() + "/" + selectedLodSections.size()
                + ", transitionScan=" + nextLodSectionRefresh + "/" + transitionBuildSections.size()
                + ", blockedParents=" + transitionPendingChildren.size()
                + ", branchHandoffs=" + lodBranchHandoffs
                + ", selectionRuns=" + lodSelectionRuns
                + ", topologyChanges=" + lodTopologyChanges
                + ", priorityOnly=" + lodPriorityOnlyChanges
                + ", lastReason=" + lastSelectionReason
                + ", meshes=" + lodMeshes.size()
                + ", builds=" + lodMeshBuildAttempts
                + ", uploads=" + lodMeshUploads
                + ", unchanged=" + lodMeshUnchanged
                + ", missing=" + lodMeshMissingSections
                + ", empty=" + lodMeshEmpty
                + ", geometry=" + formatBytes(lodGeometryBytes) + "/" + formatBytes(LOD_GEOMETRY_BUDGET_BYTES)
                + ", geometryPeak=" + formatBytes(peakLodGeometryBytes)
                + ", budgetRejects=" + lodGeometryBudgetRejections
                + ", opaqueDraw=" + lastOpaqueDrawCalls + "/" + lastOpaqueVertices
                + ", waterDraw=" + lastWaterDrawCalls + "/" + lastWaterVertices
                + ", frustumCulled=" + lastSelectionFrustumCulledNodes
                + ", screenTested=" + lastSelectionScreenTestedNodes
                + ", vanillaVisible=" + visibleVanillaSections.size()
                + ", sodiumDistance=" + sodiumRenderDistanceChunks
                + ", vanillaTransition=" + vanillaTransitionChunks + " chunks"
                + "[" + Math.max(0, sodiumRenderDistanceChunks - vanillaTransitionChunks) * 16
                + ".." + Math.max(1, sodiumRenderDistanceChunks) * 16 + " blocks]"
                + ", sodiumFog=" + Math.round(sourceFogStart) + ".." + Math.round(sourceFogEnd)
                + ", voxyFog=" + Math.round(activeFogStart) + ".." + Math.round(activeFogEnd)
                + (activeDenseFog ? "(dense)" : "(render)")
                + ", depthComposition=offscreen-reproject"
                + ", vanillaMaskRevision=" + visibleVanillaMaskRevision
                + ", selection=" + selectionState
                + ", overlay=" + renderLodAboveTerrain + ".";
    }

    public static void beginVisibleVanillaSectionCollection() {
        collectedVisibleVanillaSections.clear();
    }

    public static void recordVisibleVanillaSection(int sectionX, int sectionY, int sectionZ) {
        collectedVisibleVanillaSections.add(SectionPos.asLong(sectionX, sectionY, sectionZ));
    }

    /**
     * Renders opaque LoD terrain before Sodium starts its translucent terrain pass. This puts
     * the distant seabed in the depth buffer before Vanilla blends its water over the scene.
     */
    public static void renderOpaque(ChunkRenderMatrices matrices, GpuTextureView colorTarget, GpuTextureView depthTarget,
                                    CameraTransform camera, FogParameters fogParameters) {
        if (failed) {
            return;
        }

        try {
            long frameStart = profileNow();
            profiledSelectionNanos = 0;
            profiledMeshBuildNanos = 0;
            RenderSystem.assertOnRenderThread();
            initialize();
            updateCurrentDrawFrustum(matrices, camera);
            long maskStart = profileNow();
            commitVisibleVanillaSectionMask();
            long maskNanos = profileElapsed(maskStart);

            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            ensureOffscreenTargets(colorTarget);
            encoder.clearColorAndDepthTextures(lodColorTexture, new Vector4f(0.0f), lodDepthTexture, 0.0);
            uploadLodProjection(encoder, matrices.projection());
            if (testCubeVisible) {
                uploadMarker(encoder, camera);
            }
            long refreshStart = profileNow();
            refreshLodMeshes(encoder, matrices, camera, colorTarget.getWidth(0), colorTarget.getHeight(0));
            long refreshNanos = profileElapsed(refreshStart);
            long drawStart = profileNow();
            try (RenderPass pass = encoder.createRenderPass(
                    () -> "Voxy Blaze3D offscreen opaque",
                    lodColorTextureView,
                    Optional.empty(),
                    lodDepthTextureView,
                    OptionalDouble.empty())) {
                preparePass(pass, matrices, camera, null, false);
                if (testCubeVisible && markerVertexBuffer != null) {
                    TextureAtlas blockAtlas = getBlockAtlas();
                    pass.setPipeline(MARKER_PIPELINE);
                    pass.bindTexture("Sampler0", blockAtlas.getTextureView(), blockAtlas.getSampler());
                    pass.setVertexBuffer(0, markerVertexBuffer.slice());
                    pass.draw(MARKER_VERTEX_COUNT, 1, 0, 0);
                }
                preparePass(pass, matrices, camera, fogParameters, true);
                if (!renderLodAboveTerrain) {
                    drawOpaqueLods(pass);
                }
            }
            compositeOffscreen(encoder, matrices, colorTarget, depthTarget, false);
            long drawNanos = profileElapsed(drawStart);

            frameCount++;
            logPerformanceSample("opaque", profileElapsed(frameStart), maskNanos, refreshNanos, drawNanos, 0L);
            if (frameCount == 1 || frameCount % LOG_INTERVAL_FRAMES == 0) {
                Logger.info("Blaze3D probe frame " + frameCount
                        + " opaque pass submitted before Vanilla translucent terrain; color=" + colorTarget.getWidth(0) + "x" + colorTarget.getHeight(0)
                        + ", depth=" + depthTarget.getWidth(0) + "x" + depthTarget.getHeight(0)
                        + ", " + getLodDebugSummary());
            }
        } catch (RuntimeException exception) {
            failed = true;
            Logger.error("Blaze3D probe opaque pass failed on frame " + (frameCount + 1)
                    + "; disabling the Vulkan probe for this session.", exception);
        }
    }

    /**
     * Renders LoD water after Sodium has drawn Vanilla water. It has no depth write, preserving
     * foreground water while its alpha blends over the opaque LoD terrain submitted earlier.
     */
    public static void renderWater(ChunkRenderMatrices matrices, GpuTextureView colorTarget, GpuTextureView depthTarget,
                                   CameraTransform camera, FogParameters fogParameters) {
        if (failed || !initialized || lodMeshes.isEmpty()) {
            return;
        }

        try {
            long waterStart = profileNow();
            RenderSystem.assertOnRenderThread();
            updateCurrentDrawFrustum(matrices, camera);
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            ensureOffscreenTargets(colorTarget);
            encoder.clearColorAndDepthTextures(lodColorTexture, new Vector4f(0.0f), lodDepthTexture, 0.0);
            try (RenderPass pass = encoder.createRenderPass(
                    () -> "Voxy Blaze3D offscreen translucent",
                    lodColorTextureView,
                    Optional.empty(),
                    lodDepthTextureView,
                    OptionalDouble.empty())) {
                preparePass(pass, matrices, camera, fogParameters, true);
                if (renderLodAboveTerrain) {
                    drawOpaqueLods(pass);
                }
                drawWaterLods(pass, camera);
            }
            compositeOffscreen(encoder, matrices, colorTarget, depthTarget, true);
            logPerformanceSample("water", profileElapsed(waterStart), 0L, 0L, 0L, profileElapsed(waterStart));
        } catch (RuntimeException exception) {
            failed = true;
            Logger.error("Blaze3D probe water pass failed on frame " + frameCount
                    + "; disabling the Vulkan probe for this session.", exception);
        }
    }

    private static void preparePass(RenderPass pass, ChunkRenderMatrices matrices, CameraTransform camera,
                                    FogParameters fogParameters, boolean applyLodParameters) {
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("Projection", lodProjectionBuffer.slice());
        Matrix4f cameraRelativeModelView = new Matrix4f(matrices.modelView())
                .translate((float) -camera.x, (float) -camera.y, (float) -camera.z);
        if (applyLodParameters) {
            updateFogRange(fogParameters);
            pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(
                    cameraRelativeModelView,
                    new Vector4f(1.0f),
                    new Vector3f(activeDenseFog ? 1.0f : 0.0f, activeFogStart, activeFogEnd),
                    new Matrix4f()));
        } else {
            pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(cameraRelativeModelView));
        }
    }

    private static void compositeOffscreen(CommandEncoder encoder, ChunkRenderMatrices matrices,
                                           GpuTextureView colorTarget, GpuTextureView depthTarget,
                                           boolean translucent) {
        Matrix4f inverseLodProjection = createLodProjection(matrices.projection()).invert();
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        float transitionEnd = Math.max(16.0f, sodiumRenderDistanceChunks * 16.0f);
        float transitionStart = Math.max(0.0f, transitionEnd - vanillaTransitionChunks * 16.0f);
        try (RenderPass pass = encoder.createRenderPass(
                () -> translucent ? "Voxy Blaze3D translucent composite" : "Voxy Blaze3D opaque composite",
                colorTarget,
                Optional.empty(),
                depthTarget,
                OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(
                    inverseLodProjection,
                    new Vector4f(1.0f),
                    new Vector3f(zeroToOne ? 1.0f : 0.0f, transitionStart, transitionEnd),
                    new Matrix4f(matrices.projection())));
            pass.setPipeline(translucent ? LOD_TRANSLUCENT_COMPOSITE_PIPELINE : LOD_COMPOSITE_PIPELINE);
            pass.bindTexture("Sampler0", lodColorTextureView,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.bindTexture("Sampler1", lodDepthTextureView,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.setVertexBuffer(0, compositeVertexBuffer.slice());
            pass.draw(COMPOSITE_VERTEX_COUNT, 1, 0, 0);
        }
    }

    private static void ensureOffscreenTargets(GpuTextureView colorTarget) {
        int width = colorTarget.getWidth(0);
        int height = colorTarget.getHeight(0);
        GpuFormat colorFormat = colorTarget.texture().getFormat();
        if (lodColorTexture != null
                && !lodColorTexture.isClosed()
                && lodColorTexture.getWidth(0) == width
                && lodColorTexture.getHeight(0) == height
                && lodColorTexture.getFormat() == colorFormat) {
            return;
        }

        releaseOffscreenTargets();
        int usage = GpuTexture.USAGE_COPY_DST
                | GpuTexture.USAGE_COPY_SRC
                | GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_RENDER_ATTACHMENT;
        lodColorTexture = RenderSystem.getDevice().createTexture(
                "Voxy Blaze3D offscreen color", usage, colorFormat, width, height, 1, 1);
        lodColorTextureView = RenderSystem.getDevice().createTextureView(lodColorTexture);
        lodDepthTexture = RenderSystem.getDevice().createTexture(
                "Voxy Blaze3D offscreen depth", usage, GpuFormat.D32_FLOAT, width, height, 1, 1);
        lodDepthTextureView = RenderSystem.getDevice().createTextureView(lodDepthTexture);
        Logger.info("Blaze3D LoD offscreen targets allocated: " + width + "x" + height
                + ", color=" + colorFormat + ", depth=" + GpuFormat.D32_FLOAT + ".");
    }

    private static void releaseOffscreenTargets() {
        if (lodColorTextureView != null) lodColorTextureView.close();
        if (lodDepthTextureView != null) lodDepthTextureView.close();
        if (lodColorTexture != null) lodColorTexture.close();
        if (lodDepthTexture != null) lodDepthTexture.close();
        lodColorTextureView = null;
        lodDepthTextureView = null;
        lodColorTexture = null;
        lodDepthTexture = null;
    }

    private static void updateFogRange(FogParameters fogParameters) {
        sourceFogStart = 0.0f;
        sourceFogEnd = 0.0f;
        activeFogStart = 0.0f;
        activeFogEnd = 0.0f;
        activeDenseFog = false;
        if (!VoxyConfig.CONFIG.useEnvironmentalFog || fogParameters == null) {
            return;
        }

        float renderStart = fogParameters.renderStart();
        float renderEnd = fogParameters.renderEnd();
        sourceFogStart = renderStart;
        sourceFogEnd = renderEnd;
        float environmentalStart = fogParameters.environmentalStart();
        float environmentalEnd = fogParameters.environmentalEnd();
        activeDenseFog = Float.isFinite(environmentalEnd)
                && environmentalEnd > environmentalStart
                && (!Float.isFinite(renderEnd) || environmentalEnd < Math.min(renderEnd * 0.25f, 128.0f));
        if (activeDenseFog) {
            activeFogStart = environmentalStart;
            activeFogEnd = environmentalEnd;
        } else if (Float.isFinite(renderStart) && Float.isFinite(renderEnd) && renderEnd > renderStart) {
            // Sodium's values end at Sodium's own terrain horizon; they never include Voxy's
            // additional LoD distance. Preserve Sodium's transition shape, but scale it to the
            // actual Voxy horizon so a 2,048-chunk LoD view is not fogged after 20-56 chunks.
            float voxyFogEnd = Math.min(LOD_FAR_CLIP_BLOCKS,
                    Math.max(16.0f, VoxyConfig.CONFIG.sectionRenderDistance * 512.0f));
            float startRatio = clamp(renderStart / renderEnd, 0.0f, 0.999f);
            activeFogStart = voxyFogEnd * startRatio;
            activeFogEnd = voxyFogEnd;
        } else if (Float.isFinite(environmentalEnd) && environmentalEnd > environmentalStart) {
            activeFogStart = environmentalStart;
            activeFogEnd = environmentalEnd;
            activeDenseFog = true;
        }
    }

    public static void setSodiumRenderDistanceChunks(int renderDistanceChunks) {
        sodiumRenderDistanceChunks = Math.max(1, renderDistanceChunks);
    }

    public static int setVanillaTransitionChunks(int requestedChunks) {
        vanillaTransitionChunks = clamp(requestedChunks, 0, 4);
        Logger.info("Blaze3D Sodium/Voxy transition width changed to " + vanillaTransitionChunks + " chunks.");
        return vanillaTransitionChunks;
    }

    private static Matrix4f createViewProjection(ChunkRenderMatrices matrices) {
        return createLodProjection(matrices.projection()).mul(matrices.modelView());
    }

    /**
     * Voxy's native renderer preserves Minecraft's FOV and injected projection effects, but
     * replaces its clip planes. The configured Voxy distance can reach 32,768 blocks while
     * Minecraft's terrain projection is normally only a few hundred blocks deep.
     */
    private static Matrix4f createLodProjection(Matrix4fc baseProjection) {
        Matrix4fc rawMinecraftProjection = Minecraft.getInstance().gameRenderer.gameRenderState()
                .levelRenderState.cameraRenderState.projectionMatrix;
        Matrix4f extraProjection = new Matrix4f(rawMinecraftProjection).invert().mul(baseProjection);

        float near = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16 <= 32 ? 8.0f : 16.0f;
        float far = LOD_FAR_CLIP_BLOCKS;
        // Minecraft 26.2's standard terrain path uses reverse-Z. This pipeline deliberately uses
        // GREATER_THAN as well, so swap the clip distances before applying the same formula as the
        // native renderer.
        float temporary = near;
        near = far;
        far = temporary;
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        Matrix4f extendedMinecraftProjection = new Matrix4f(rawMinecraftProjection)
                .m22((zeroToOne ? far : far + near) / (near - far))
                .m32((zeroToOne ? far : far + far) * near / (near - far));
        return extraProjection.mulLocal(extendedMinecraftProjection);
    }

    private static void uploadLodProjection(CommandEncoder encoder, Matrix4fc baseProjection) {
        Matrix4f projection = createLodProjection(baseProjection);
        ByteBuffer data = MemoryUtil.memAlloc(PROJECTION_UNIFORM_BYTES);
        try {
            projection.get(data);
            data.position(0);
            data.limit(PROJECTION_UNIFORM_BYTES);
            if (lodProjectionBuffer == null || lodProjectionBuffer.isClosed()) {
                lodProjectionBuffer = RenderSystem.getDevice().createBuffer(
                        () -> "Voxy Blaze3D extended projection",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                        data);
            } else {
                encoder.writeToBuffer(lodProjectionBuffer.slice(), data);
            }
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static void updateCurrentDrawFrustum(ChunkRenderMatrices matrices, CameraTransform camera) {
        currentDrawFrustum.set(createViewProjection(matrices), false);
        currentDrawCameraX = camera.x;
        currentDrawCameraY = camera.y;
        currentDrawCameraZ = camera.z;
        currentDrawFrustumValid = true;
    }

    private static boolean isInsideCurrentDrawFrustum(LodSectionCoordinate coordinate) {
        return !currentDrawFrustumValid || !isOutsideVoxyFrustum(coordinate, currentDrawFrustum,
                currentDrawCameraX, currentDrawCameraY, currentDrawCameraZ);
    }

    private static void drawOpaqueLods(RenderPass pass) {
        lastOpaqueDrawCalls = 0;
        lastOpaqueVertices = 0;
        TextureAtlas blockAtlas = getBlockAtlas();
        pass.setPipeline(renderLodAboveTerrain ? LOD_OVERLAY_PIPELINE : LOD_PIPELINE);
        pass.setUniform("Fog", RenderSystem.getShaderFog());
        pass.bindTexture("Sampler0", blockAtlas.getTextureView(), blockAtlas.getSampler());
        pass.bindTexture("Sampler2", Minecraft.getInstance().gameRenderer.levelLightmap(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        pass.setIndexBuffer(lodIndexBuffer, IndexType.INT);
        for (LodSectionMesh mesh : lodMeshes.values()) {
            if (mesh.opaqueVertexCount() != 0
                    && isInsideCurrentDrawFrustum(mesh.coordinate())
                    && !isCoveredByCoarserMesh(mesh.coordinate())) {
                pass.setVertexBuffer(0, mesh.opaqueVertexBuffer().slice());
                pass.drawIndexed(indexCount(mesh.opaqueVertexCount()), 1, 0, 0, 0);
                lastOpaqueDrawCalls++;
                lastOpaqueVertices += mesh.opaqueVertexCount();
            }
        }
    }

    private static void drawWaterLods(RenderPass pass, CameraTransform camera) {
        lastWaterDrawCalls = 0;
        lastWaterVertices = 0;
        List<LodSectionMesh> waterMeshes = lodMeshes.values().stream()
                .filter(mesh -> mesh.waterVertexCount() != 0
                        && isInsideCurrentDrawFrustum(mesh.coordinate())
                        && !isCoveredByCoarserMesh(mesh.coordinate()))
                .sorted(Comparator.comparingDouble((LodSectionMesh mesh) -> distanceSquaredToCamera(mesh.coordinate(), camera)).reversed())
                .toList();
        if (waterMeshes.isEmpty()) {
            return;
        }

        TextureAtlas blockAtlas = getBlockAtlas();
        pass.setPipeline(renderLodAboveTerrain ? LOD_WATER_OVERLAY_PIPELINE : LOD_WATER_PIPELINE);
        pass.setUniform("Fog", RenderSystem.getShaderFog());
        pass.bindTexture("Sampler0", blockAtlas.getTextureView(), blockAtlas.getSampler());
        pass.bindTexture("Sampler2", Minecraft.getInstance().gameRenderer.levelLightmap(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        pass.setIndexBuffer(lodIndexBuffer, IndexType.INT);
        for (LodSectionMesh mesh : waterMeshes) {
            pass.setVertexBuffer(0, mesh.waterVertexBuffer().slice());
            pass.drawIndexed(indexCount(mesh.waterVertexCount()), 1, 0, 0, 0);
            lastWaterDrawCalls++;
            lastWaterVertices += mesh.waterVertexCount();
        }
    }

    private static long profileNow() {
        return performanceProfiling ? System.nanoTime() : 0L;
    }

    private static long profileElapsed(long start) {
        return start == 0L ? 0L : System.nanoTime() - start;
    }

    private static void logPerformanceSample(String stage,
                                             long totalNanos,
                                             long maskNanos,
                                             long refreshNanos,
                                             long drawNanos,
                                             long waterNanos) {
        if (!performanceProfiling || totalNanos < PERFORMANCE_SLOW_FRAME_NANOS) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastPerformanceLogNanos < PERFORMANCE_LOG_INTERVAL_NANOS) {
            return;
        }
        lastPerformanceLogNanos = now;
        Logger.warn("Blaze3D profiler slow " + stage + " pass: total=" + formatMillis(totalNanos)
                + ", mask=" + formatMillis(maskNanos)
                + ", selection=" + formatMillis(profiledSelectionNanos)
                + ", meshBuild=" + formatMillis(profiledMeshBuildNanos)
                + ", refresh=" + formatMillis(refreshNanos)
                + ", draw=" + formatMillis(drawNanos)
                + ", water=" + formatMillis(waterNanos)
                + ", meshes=" + lodMeshes.size()
                + ", selected=" + selectedLodSections.size()
                + ", visibleVanillaSections=" + visibleVanillaSections.size() + ".");
    }

    private static String formatMillis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1fms", nanos / 1_000_000.0);
    }

    private static String formatBytes(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1fMiB", bytes / (1024.0 * 1024.0));
    }

    public static void shutdown() {
        releaseBuffer(markerVertexBuffer, "marker");
        markerVertexBuffer = null;
        releaseBuffer(compositeVertexBuffer, "composite");
        compositeVertexBuffer = null;
        releaseBuffer(lodIndexBuffer, "shared LoD index");
        lodIndexBuffer = null;
        releaseBuffer(lodProjectionBuffer, "extended projection");
        lodProjectionBuffer = null;
        releaseOffscreenTargets();
        clearLodMeshes();
        lodMeshFingerprints.clear();
        blockRenderDefinitions.clear();
        voxyBiomes.clear();
        tintColors.clear();
        visibleVanillaSections.clear();
        collectedVisibleVanillaSections.clear();
        visibleVanillaMaskRevision = 0;
        initialized = false;
        failed = false;
        frameCount = 0;
        lastLodRefreshFrame = Long.MIN_VALUE;
        loggedMissingLodSection = false;
        lodRenderGrid = null;
        selectedLodSections = List.of();
        selectedLodSectionKeys = Set.of();
        transitionBuildSections = List.of();
        transitionPendingChildren.clear();
        transitionParentKeys.clear();
        transitionCoverageReadyKeys.clear();
        currentDrawFrustumValid = false;
        nextLodSectionRefresh = 0;
        nextLodSectionValidation = 0;
        pendingLodSelection = null;
        lodSelectionTransitionPending = false;
        lastLodSelectionFrame = Long.MIN_VALUE;
        lastLodSelectionX = Double.NaN;
        lastLodSelectionY = Double.NaN;
        lastLodSelectionZ = Double.NaN;
        lastSubdivisionSize = Float.NaN;
        lastLodSelectionForcedLevel = Integer.MIN_VALUE;
        lastVanillaRenderDistance = -1;
        lastLodSelectionViewportWidth = -1;
        lastLodSelectionViewportHeight = -1;
        lastLodSelectionViewProjection = null;
        activeVanillaBoundary = null;
        activeFogStart = 0.0f;
        activeFogEnd = 0.0f;
        activeDenseFog = false;
        lastVanillaBoundaryKey = null;
        loggedMaterialDefinitions = 0;
        lodGridInvalidated = true;
        renderLodAboveTerrain = false;
        resetLodDiagnostics();
    }

    private static void initialize() {
        if (initialized) {
            return;
        }

        initializeLodIndexBuffer();
        initializeCompositeVertexBuffer();
        var deviceInfo = RenderSystem.getDevice().getDeviceInfo();
        Logger.info("Initializing Blaze3D probe: build=" + VoxyCommon.BUILD_ID
                + ", buildNumber=" + VoxyCommon.BUILD_NUMBER
                + ", backend=" + deviceInfo.backendName()
                + ", device=" + deviceInfo.name()
                + ", vendor=" + deviceInfo.vendorName()
                + ", drawIndirect=" + deviceInfo.features().drawIndirect()
                + ", multiDrawIndirect=" + deviceInfo.features().multiDrawIndirect()
                + ", persistentMapping=" + deviceInfo.features().persistentMapping());
        Logger.info("Blaze3D LoD features: selector=exact-aabb-screen-area+five-plane-frustum, "
                + "occlusion=offscreen-depth-reproject, traversal=incremental-cpu, handoff=per-octree-branch, draws=direct-indexed, "
                + "lighting=live-lightmap+directional, fog=voxy-environmental, vanilla-mask=section-visibility, "
                + "opaque-order=after-sodium-cutout, farClip=" + Math.round(LOD_FAR_CLIP_BLOCKS)
                + ", sodiumDistance=" + sodiumRenderDistanceChunks + " chunks.");
        Logger.info("Blaze3D LoD geometry: vertexBytes=" + LOD_VERTEX_FORMAT.getVertexSize()
                + ", verticesPerQuad=" + LOD_VERTICES_PER_QUAD
                + ", sharedIndexBuffer=" + formatBytes(LOD_INDEX_BUFFER_SIZE)
                + ", retainedBudget=" + formatBytes(LOD_GEOMETRY_BUDGET_BYTES)
                + " (override with -D" + LOD_GEOMETRY_BUDGET_PROPERTY + "=<MiB>).");
        initialized = true;
    }

    private static void initializeCompositeVertexBuffer() {
        if (compositeVertexBuffer != null && !compositeVertexBuffer.isClosed()) {
            return;
        }
        try (ByteBufferBuilder byteBuffer = ByteBufferBuilder.exactlySized(COMPOSITE_VERTEX_BUFFER_SIZE)) {
            BufferBuilder builder = new BufferBuilder(byteBuffer, PrimitiveTopology.TRIANGLE_STRIP, DefaultVertexFormat.POSITION);
            builder.addVertex(-1.0f, -1.0f, 0.0f);
            builder.addVertex(1.0f, -1.0f, 0.0f);
            builder.addVertex(-1.0f, 1.0f, 0.0f);
            builder.addVertex(1.0f, 1.0f, 0.0f);
            try (MeshData mesh = builder.buildOrThrow()) {
                compositeVertexBuffer = RenderSystem.getDevice().createBuffer(
                        () -> "Voxy Blaze3D composite vertices",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                        mesh.vertexBuffer());
            }
        }
    }

    private static void initializeLodIndexBuffer() {
        if (lodIndexBuffer != null && !lodIndexBuffer.isClosed()) {
            return;
        }

        ByteBuffer indices = MemoryUtil.memAlloc(LOD_INDEX_BUFFER_SIZE);
        try {
            for (int quad = 0; quad < MAX_LOD_QUAD_COUNT_PER_SECTION; quad++) {
                int baseVertex = quad * LOD_VERTICES_PER_QUAD;
                indices.putInt(baseVertex);
                indices.putInt(baseVertex + 1);
                indices.putInt(baseVertex + 2);
                indices.putInt(baseVertex);
                indices.putInt(baseVertex + 2);
                indices.putInt(baseVertex + 3);
            }
            indices.flip();
            lodIndexBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "Voxy Blaze3D shared LoD index buffer",
                    GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST,
                    indices);
        } finally {
            MemoryUtil.memFree(indices);
        }
    }

    private static void commitVisibleVanillaSectionMask() {
        if (visibleVanillaSections.equals(collectedVisibleVanillaSections)) {
            return;
        }
        visibleVanillaSections.clear();
        visibleVanillaSections.addAll(collectedVisibleVanillaSections);
        visibleVanillaMaskRevision++;
        // The mask can change while Sodium performs visibility culling. The next normal refresh
        // incorporates it; forcing an immediate rebuild here causes one full 32^3 LoD mesh per frame.
    }

    private static void uploadMarker(CommandEncoder encoder, CameraTransform camera) {
        try (ByteBufferBuilder byteBuffer = ByteBufferBuilder.exactlySized(MARKER_BUFFER_SIZE)) {
            BufferBuilder builder = new BufferBuilder(byteBuffer, PrimitiveTopology.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
            addMarker(builder, camera, getBlockAtlas().getSprite(DIRT_SPRITE));

            try (MeshData mesh = builder.buildOrThrow()) {
                markerVertexBuffer = uploadMesh(markerVertexBuffer, encoder, "Voxy Blaze3D probe vertices", mesh);
            }
        }
    }

    private static void refreshLodMeshes(CommandEncoder encoder, ChunkRenderMatrices matrices, CameraTransform camera, int viewportWidth, int viewportHeight) {
        WorldEngine world = WorldIdentifier.ofEngineNullable(Minecraft.getInstance().level);
        if (world == null) {
            if (!loggedMissingLodSection) {
                loggedMissingLodSection = true;
                Logger.info("Blaze3D LoD probe is waiting for Voxy world data.");
            }
            clearLodMeshes();
            lodRenderGrid = null;
            selectedLodSections = List.of();
            selectedLodSectionKeys = Set.of();
            transitionBuildSections = List.of();
            transitionPendingChildren.clear();
            transitionParentKeys.clear();
            transitionCoverageReadyKeys.clear();
            lodSelectionTransitionPending = false;
            lastLodSelectionViewProjection = null;
            nextLodSectionValidation = 0;
            return;
        }

        loggedMissingLodSection = false;
        LodGridKey requestedGrid = createGridKey(camera);
        if (lodGridInvalidated || lodRenderGrid == null || !lodRenderGrid.key().equals(requestedGrid)) {
            boolean discardAllMeshes = lodGridInvalidated || lodRenderGrid == null
                    || lodRenderGrid.key().lodLevel() != requestedGrid.lodLevel()
                    || lodRenderGrid.key().radius() != requestedGrid.radius()
                    || lodRenderGrid.key().minY() != requestedGrid.minY()
                    || lodRenderGrid.key().maxY() != requestedGrid.maxY();
            if (discardAllMeshes) {
                clearLodMeshes();
                lodMeshFingerprints.clear();
                resetLodDiagnostics();
            }
            lodRenderGrid = new LodRenderGrid(requestedGrid, findStoredSections(world, requestedGrid));
            pendingLodSelection = null;
            lastLodSelectionFrame = Long.MIN_VALUE;
            lastLodSelectionViewProjection = null;
            lodGridInvalidated = false;
            lastLodRefreshFrame = Long.MIN_VALUE;
            if (discardAllMeshes || selectedLodSections.isEmpty()) {
                // Only bootstrap a new world with its stored roots. Recentring the same compatible
                // grid must preserve the refined hierarchy; publishing all L4 roots here caused
                // periodic 16x16x16-to-one-voxel quality resets while travelling.
                selectedLodSections = lodRenderGrid.sections();
                selectedLodSectionKeys = sectionKeys(selectedLodSections);
                transitionBuildSections = selectedLodSections;
                transitionPendingChildren.clear();
                transitionParentKeys.clear();
                transitionCoverageReadyKeys.clear();
                nextLodSectionRefresh = 0;
                nextLodSectionValidation = 0;
                lodSelectionTransitionPending = !selectedLodSections.isEmpty();
            }
            Logger.info("Blaze3D LoD grid rebuilt: level=" + requestedGrid.lodLevel()
                    + ", center=" + requestedGrid.centerX() + "," + requestedGrid.centerZ()
                    + ", radius=" + requestedGrid.radius() + " sections"
                    + ", vertical=" + requestedGrid.minY() + ".." + requestedGrid.maxY()
                    + ", storedSections=" + lodRenderGrid.sections().size()
                    + ", hierarchy=" + (discardAllMeshes ? "bootstrap" : "preserved")
                    + ", selectedSections=" + selectedLodSections.size()
                    + ", distance=" + Math.round(VoxyConfig.CONFIG.sectionRenderDistance * 512.0f) + " blocks.");
        }

        int configuredSodiumDistance = sodiumRenderDistanceChunks > 0
                ? sodiumRenderDistanceChunks
                : Minecraft.getInstance().options.renderDistance().get();
        int vanillaRenderDistance = configuredSodiumDistance * 16;
        activeVanillaBoundary = VanillaRenderBoundary.create(camera, vanillaRenderDistance);
        long selectionStart = profileNow();
        updateLodSelection(world, matrices, camera, viewportWidth, viewportHeight, activeVanillaBoundary);
        profiledSelectionNanos += profileElapsed(selectionStart);

        boolean initialPopulation = lodSelectionTransitionPending;
        int refreshInterval = initialPopulation ? LOD_INITIAL_REFRESH_INTERVAL_FRAMES : LOD_STEADY_REFRESH_INTERVAL_FRAMES;
        if (lastLodRefreshFrame != Long.MIN_VALUE && frameCount - lastLodRefreshFrame < refreshInterval) {
            return;
        }
        lastLodRefreshFrame = frameCount;

        if (selectedLodSections.isEmpty()) {
            return;
        }
        if (initialPopulation && lodGeometryBudgetExhausted) {
            return;
        }
        TextureAtlasSprite fallbackSprite = getBlockAtlas().getSprite(DIRT_SPRITE);
        long meshBuildStart = profileNow();
        if (initialPopulation) {
            int refreshedSections = 0;
            // The scheduling deadline must use a real clock even when the optional profiler is
            // disabled. profileNow() intentionally returns zero in that mode.
            long meshBuildDeadline = System.nanoTime() + LOD_INITIAL_MESH_BUILD_BUDGET_NANOS;
            while (nextLodSectionRefresh < transitionBuildSections.size()
                    && refreshedSections < LOD_INITIAL_MESHES_PER_REFRESH
                    && System.nanoTime() < meshBuildDeadline) {
                LodSectionCoordinate section = transitionBuildSections.get(nextLodSectionRefresh++);
                if (transitionCoverageReadyKeys.contains(section.key())) {
                    continue;
                }
                rebuildLodSection(world, section, fallbackSprite);
                if (lodMeshFingerprints.containsKey(section.key())) {
                    markTransitionCoverageReady(section.key());
                }
                refreshedSections++;
            }
        } else {
            LodSectionCoordinate section = selectedLodSections.get(nextLodSectionValidation);
            rebuildLodSection(world, section, fallbackSprite);
            nextLodSectionValidation = (nextLodSectionValidation + 1) % selectedLodSections.size();
        }
        profiledMeshBuildNanos += profileElapsed(meshBuildStart);
        if (initialPopulation && nextLodSectionRefresh == transitionBuildSections.size()) {
            int incompleteSection = findIncompleteSelectedSection();
            if (incompleteSection != -1) {
                // Retry the remaining unavailable leaf without making already-confirmed nodes
                // consume the eight-build frame quota again.
                nextLodSectionRefresh = 0;
                return;
            }
            if (lodSelectionTransitionPending) {
                retainMeshesInGrid(selectedLodSections);
                lodSelectionTransitionPending = false;
                nextLodSectionValidation = 0;
            }
            Logger.info("Blaze3D LoD grid population complete: meshes=" + lodMeshes.size()
                    + "/" + selectedLodSections.size()
                    + ", levels=" + formatLevelHistogram(selectedLodSections)
                    + ", branchHandoffs=" + lodBranchHandoffs
                    + ", mode=" + lodModeDescription() + ".");
        }
    }

    private static LodGridKey createGridKey(CameraTransform camera) {
        int lodLevel = WorldEngine.MAX_LOD_LAYER;
        int sectionSize = sectionSize(lodLevel);
        int centerX = Math.floorDiv((int) Math.floor(camera.x), sectionSize);
        int centerZ = Math.floorDiv((int) Math.floor(camera.z), sectionSize);
        int minY = Minecraft.getInstance().level.getMinSectionY() >> (lodLevel + 1);
        int maxY = (Minecraft.getInstance().level.getMaxSectionY() - 1) >> (lodLevel + 1);
        int radius = Math.max(1, (int) Math.ceil((VoxyConfig.CONFIG.sectionRenderDistance + 1.0f) * 512.0f / sectionSize));
        return new LodGridKey(lodLevel, centerX, centerZ, minY, maxY, radius);
    }

    private static void updateLodSelection(WorldEngine world,
                                           ChunkRenderMatrices matrices,
                                           CameraTransform camera,
                                           int viewportWidth,
                                           int viewportHeight,
                                           VanillaRenderBoundary vanillaBoundary) {
        int vanillaRenderDistance = vanillaBoundary.radius();
        if (pendingLodSelection != null) {
            advanceLodSelection(pendingLodSelection);
            return;
        }

        Matrix4f viewProjection = createViewProjection(matrices);
        String invalidationReason = "initial";
        if (lastLodSelectionFrame != Long.MIN_VALUE) {
            double dx = camera.x - lastLodSelectionX;
            double dy = camera.y - lastLodSelectionY;
            double dz = camera.z - lastLodSelectionZ;
            boolean cameraMoved = dx * dx + dy * dy + dz * dz >= LOD_SELECTION_MOVEMENT_BLOCKS * LOD_SELECTION_MOVEMENT_BLOCKS;
            boolean viewChanged = lastLodSelectionViewProjection == null
                    || viewParametersChanged(lastLodSelectionViewProjection, viewProjection);
            boolean viewportChanged = lastLodSelectionViewportWidth != viewportWidth
                    || lastLodSelectionViewportHeight != viewportHeight;
            boolean vanillaBoundaryChanged = lastVanillaRenderDistance != vanillaRenderDistance
                    || lastVanillaBoundaryKey == null
                    || !lastVanillaBoundaryKey.equals(vanillaBoundary.key());
            boolean settingsChanged = lastSubdivisionSize != VoxyConfig.CONFIG.subDivisionSize
                    || lastLodSelectionForcedLevel != forcedLodLevel;
            if (!cameraMoved && !viewChanged && !viewportChanged && !vanillaBoundaryChanged && !settingsChanged) {
                return;
            }
            List<String> reasons = new ArrayList<>(6);
            if (cameraMoved) reasons.add("position");
            if (viewChanged) reasons.add("view");
            if (viewportChanged) reasons.add("viewport");
            if (vanillaBoundaryChanged) reasons.add("vanilla-boundary");
            if (settingsChanged) reasons.add("settings");
            invalidationReason = String.join("+", reasons);
        }
        lastLodSelectionFrame = frameCount;
        lastLodSelectionX = camera.x;
        lastLodSelectionY = camera.y;
        lastLodSelectionZ = camera.z;
        lastSubdivisionSize = VoxyConfig.CONFIG.subDivisionSize;
        lastLodSelectionForcedLevel = forcedLodLevel;
        lastVanillaRenderDistance = vanillaRenderDistance;
        lastLodSelectionViewportWidth = viewportWidth;
        lastLodSelectionViewportHeight = viewportHeight;
        lastLodSelectionViewProjection = new Matrix4f(viewProjection);
        lastVanillaBoundaryKey = vanillaBoundary.key();

        LodSelectionTask selection = new LodSelectionTask(world, viewProjection, camera.x, camera.y, camera.z,
                viewportWidth, viewportHeight, vanillaBoundary,
                VoxyConfig.CONFIG.subDivisionSize * VoxyConfig.CONFIG.subDivisionSize,
                new ArrayDeque<>(), new ArrayList<>(), frameCount, invalidationReason);
        enqueueSelectionNodes(selection, lodRenderGrid.sections(), false);
        pendingLodSelection = selection;
        advanceLodSelection(pendingLodSelection);
    }

    private static void advanceLodSelection(LodSelectionTask selection) {
        long deadline = System.nanoTime() + LOD_SELECTION_BUDGET_NANOS;
        int processedThisFrame = 0;
        while (!selection.pending().isEmpty()
                && processedThisFrame < LOD_SELECTION_NODES_PER_FRAME
                && System.nanoTime() < deadline) {
            selectLodSection(selection, selection.pending().removeFirst());
            processedThisFrame++;
            selection.processedNodes++;
        }

        if (!selection.pending().isEmpty()) {
            return;
        }

        selection.selected().sort(Comparator.comparingDouble(section -> distanceSquaredToCamera(section, selection.cameraX(), selection.cameraY(), selection.cameraZ())));
        List<LodSectionCoordinate> selected = List.copyOf(selection.selected());
        lastSelectionFrustumCulledNodes = selection.frustumCulledNodes;
        lastSelectionScreenTestedNodes = selection.screenTestedNodes;
        lastSelectionReason = selection.invalidationReason();
        lodSelectionRuns++;
        pendingLodSelection = null;
        Set<Long> selectedKeys = sectionKeys(selected);
        boolean topologyChanged = !selectedKeys.equals(selectedLodSectionKeys);
        boolean priorityChanged = !selected.equals(selectedLodSections);
        if (topologyChanged) {
            lodTopologyChanges++;
            selectedLodSections = selected;
            selectedLodSectionKeys = selectedKeys;
            prepareLodTransition(selected);
            nextLodSectionRefresh = 0;
            nextLodSectionValidation = 0;
            lastLodRefreshFrame = Long.MIN_VALUE;
            lodSelectionTransitionPending = !selectedLodSections.isEmpty();
            if (!lodSelectionTransitionPending) {
                retainMeshesInGrid(selectedLodSections);
            }
            Logger.info("Blaze3D dynamic LoD selection changed: sections=" + selectedLodSections.size()
                    + ", levels=" + formatLevelHistogram(selectedLodSections)
                    + ", nodes=" + selection.processedNodes
                    + ", frames=" + (frameCount - selection.startedFrame() + 1)
                    + ", reason=" + selection.invalidationReason()
                    + ", camera=" + Math.round(selection.cameraX()) + "," + Math.round(selection.cameraY()) + "," + Math.round(selection.cameraZ())
                    + ", viewport=" + selection.viewportWidth() + "x" + selection.viewportHeight()
                    + ", frustumCulled=" + selection.frustumCulledNodes
                    + ", screenTested=" + selection.screenTestedNodes
                    + ", vanillaVisible=" + visibleVanillaSections.size()
                    + ", sodiumDistance=" + Math.round(selection.vanillaBoundary().radius() / 16.0f)
                    + ", buildQueue=" + transitionBuildSections.size()
                    + ", ready=" + countReadySelectedSections()
                    + ", blockedParents=" + transitionPendingChildren.size()
                    + ", mode=" + lodModeDescription()
                    + ", subdivision=" + Math.round(VoxyConfig.CONFIG.subDivisionSize) + " px^2.");
        } else if (priorityChanged) {
            // Camera motion can reorder the same leaves. Update future validation priority without
            // restarting a transition whose geometry is already complete or in flight.
            selectedLodSections = selected;
            lodPriorityOnlyChanges++;
        }
    }

    private static void selectLodSection(LodSelectionTask selection, LodSectionCoordinate coordinate) {
        WorldEngine world = selection.world();
        int lodLevel = WorldEngine.getLevel(coordinate.key());
        WorldSection section = world.acquireIfExists(coordinate.key());
        if (section == null) {
            return;
        }

        byte children;
        try {
            if (forcedLodLevel < 0 && isOutsideVoxyFrustum(coordinate, selection.frustum(),
                    selection.cameraX(), selection.cameraY(), selection.cameraZ())) {
                // Match the native traversal: a node outside the frustum is culled, not replaced
                // by its coarse parent. Keeping that parent made camera turns expose L4 blocks.
                selection.frustumCulledNodes++;
                return;
            }
            if (forcedLodLevel >= 0 && lodLevel <= forcedLodLevel) {
                selection.selected().add(coordinate);
                return;
            }
            if (forcedLodLevel < 0 && lodLevel == 0) {
                selection.selected().add(coordinate);
                return;
            }
            children = section.getNonEmptyChildren();
        } finally {
            section.release();
        }

        if (children == 0 || (forcedLodLevel < 0 && !shouldSubdivide(coordinate, selection))) {
            selection.selected().add(coordinate);
            return;
        }

        List<LodSectionCoordinate> childSections = new ArrayList<>(8);
        boolean hasMissingChild = false;
        int childLevel = lodLevel - 1;
        for (int childY = 0; childY < 2; childY++) {
            for (int childZ = 0; childZ < 2; childZ++) {
                for (int childX = 0; childX < 2; childX++) {
                    int childIndex = WorldSection.getChildIndex(childX, childY, childZ);
                    if ((children & 1 << childIndex) == 0) {
                        continue;
                    }
                    int childSectionX = coordinate.x() * 2 + childX;
                    int childSectionY = coordinate.y() * 2 + childY;
                    int childSectionZ = coordinate.z() * 2 + childZ;
                    long childKey = WorldEngine.getWorldSectionId(childLevel, childSectionX, childSectionY, childSectionZ);
                    WorldSection child = world.acquireIfExists(childKey);
                    if (child == null) {
                        hasMissingChild = true;
                        continue;
                    }
                    child.release();
                    childSections.add(new LodSectionCoordinate(childKey, childSectionX, childSectionY, childSectionZ));
                }
            }
        }
        if (hasMissingChild || childSections.isEmpty()) {
            // A parent is valid coverage until all of its advertised children are available.
            // Removing it creates the persistent sky holes seen during asynchronous imports.
            selection.selected().add(coordinate);
            return;
        }
        enqueueSelectionNodes(selection, childSections, true);
    }

    private static void enqueueSelectionNodes(LodSelectionTask selection,
                                              List<LodSectionCoordinate> coordinates,
                                              boolean atFront) {
        List<LodSectionCoordinate> prioritized = new ArrayList<>(coordinates);
        prioritized.sort(Comparator
                .comparingInt((LodSectionCoordinate coordinate) -> isOutsideVoxyFrustum(coordinate,
                        selection.frustum(), selection.cameraX(), selection.cameraY(), selection.cameraZ()) ? 1 : 0)
                .thenComparingDouble(coordinate -> distanceSquaredToCamera(coordinate,
                        selection.cameraX(), selection.cameraY(), selection.cameraZ())));
        if (atFront) {
            for (int index = prioritized.size() - 1; index >= 0; index--) {
                selection.pending().addFirst(prioritized.get(index));
            }
        } else {
            selection.pending().addAll(prioritized);
        }
    }

    private static boolean shouldSubdivide(LodSectionCoordinate coordinate, LodSelectionTask selection) {
        int sectionSize = sectionSize(WorldEngine.getLevel(coordinate.key()));
        double baseX = coordinate.x() * (double) sectionSize - selection.cameraX();
        double baseY = coordinate.y() * (double) sectionSize - selection.cameraY();
        double baseZ = coordinate.z() * (double) sectionSize - selection.cameraZ();
        Matrix4f mvp = selection.viewProjection();

        double baseClipX = mvp.m00() * baseX + mvp.m10() * baseY + mvp.m20() * baseZ + mvp.m30();
        double baseClipY = mvp.m01() * baseX + mvp.m11() * baseY + mvp.m21() * baseZ + mvp.m31();
        double baseClipW = mvp.m03() * baseX + mvp.m13() * baseY + mvp.m23() * baseZ + mvp.m33();
        double axisXClipX = mvp.m00() * sectionSize;
        double axisXClipY = mvp.m01() * sectionSize;
        double axisXClipW = mvp.m03() * sectionSize;
        double axisYClipX = mvp.m10() * sectionSize;
        double axisYClipY = mvp.m11() * sectionSize;
        double axisYClipW = mvp.m13() * sectionSize;
        double axisZClipX = mvp.m20() * sectionSize;
        double axisZClipY = mvp.m21() * sectionSize;
        double axisZClipW = mvp.m23() * sectionSize;

        double minimumW = Double.POSITIVE_INFINITY;
        double maximumW = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            double clipX = baseClipX;
            double clipY = baseClipY;
            double clipW = baseClipW;
            if ((corner & 1) != 0) {
                clipX += axisXClipX;
                clipY += axisXClipY;
                clipW += axisXClipW;
            }
            if ((corner & 2) != 0) {
                clipX += axisYClipX;
                clipY += axisYClipY;
                clipW += axisYClipW;
            }
            if ((corner & 4) != 0) {
                clipX += axisZClipX;
                clipY += axisZClipY;
                clipW += axisZClipW;
            }
            if (Math.abs(clipW) < 1.0e-7) {
                // The box crosses the camera plane. Treat it as maximally important instead of
                // allowing an undefined perspective divide to collapse the hierarchy.
                selection.screenTestedNodes++;
                return true;
            }
            minimumW = Math.min(minimumW, clipW);
            maximumW = Math.max(maximumW, clipW);
            selection.screenX[corner] = clipX / clipW * 0.5 + 0.5;
            selection.screenY[corner] = clipY / clipW * 0.5 + 0.5;
        }
        selection.screenTestedNodes++;
        if (minimumW < 0.0 && maximumW > 0.0) {
            return true;
        }

        double screenArea = projectedCornerArea(selection.screenX, selection.screenY, 0, 1, 2, 4)
                + projectedCornerArea(selection.screenX, selection.screenY, 7, 6, 5, 3);
        screenArea *= 0.5;
        return screenArea * selection.viewportWidth() * selection.viewportHeight() > selection.subdivisionArea();
    }

    private static double projectedCornerArea(double[] x, double[] y, int origin, int first, int second, int third) {
        double firstX = x[first] - x[origin];
        double firstY = y[first] - y[origin];
        double secondX = x[second] - x[origin];
        double secondY = y[second] - y[origin];
        double thirdX = x[third] - x[origin];
        double thirdY = y[third] - y[origin];
        return crossMagnitude(firstX, firstY, secondX, secondY)
                + crossMagnitude(firstX, firstY, thirdX, thirdY)
                + crossMagnitude(thirdX, thirdY, secondX, secondY);
    }

    private static double crossMagnitude(double firstX, double firstY, double secondX, double secondY) {
        return Math.abs(firstX * secondY - firstY * secondX);
    }

    private static boolean viewParametersChanged(Matrix4f previous, Matrix4f current) {
        // Ignore the final translation column: view bob and tiny temporal offsets do not warrant
        // rebuilding a 10k-node CPU selection. The projection scale and rotated axes still catch
        // FOV/aspect changes and real camera rotation.
        return Math.abs(previous.m00() - current.m00()) > LOD_SELECTION_MATRIX_EPSILON
                || Math.abs(previous.m01() - current.m01()) > LOD_SELECTION_MATRIX_EPSILON
                || Math.abs(previous.m02() - current.m02()) > LOD_SELECTION_MATRIX_EPSILON
                || Math.abs(previous.m03() - current.m03()) > LOD_SELECTION_MATRIX_EPSILON
                || Math.abs(previous.m10() - current.m10()) > LOD_SELECTION_MATRIX_EPSILON
                || Math.abs(previous.m11() - current.m11()) > LOD_SELECTION_MATRIX_EPSILON
                || Math.abs(previous.m12() - current.m12()) > LOD_SELECTION_MATRIX_EPSILON
                || Math.abs(previous.m13() - current.m13()) > LOD_SELECTION_MATRIX_EPSILON
                || Math.abs(previous.m20() - current.m20()) > LOD_SELECTION_MATRIX_EPSILON
                || Math.abs(previous.m21() - current.m21()) > LOD_SELECTION_MATRIX_EPSILON
                || Math.abs(previous.m22() - current.m22()) > LOD_SELECTION_MATRIX_EPSILON
                || Math.abs(previous.m23() - current.m23()) > LOD_SELECTION_MATRIX_EPSILON;
    }

    private static boolean isOutsideVoxyFrustum(LodSectionCoordinate coordinate,
                                                 FrustumIntersection frustum,
                                                 double cameraX,
                                                 double cameraY,
                                                 double cameraZ) {
        int size = sectionSize(WorldEngine.getLevel(coordinate.key()));
        float minimumX = (float) (coordinate.x() * (double) size - cameraX);
        float minimumY = (float) (coordinate.y() * (double) size - cameraY);
        float minimumZ = (float) (coordinate.z() * (double) size - cameraZ);
        return frustum.intersectAab(minimumX, minimumY, minimumZ,
                minimumX + size, minimumY + size, minimumZ + size, VOXY_FRUSTUM_PLANE_MASK) >= 0;
    }

    private static double distanceSquaredToCamera(LodSectionCoordinate coordinate, CameraTransform camera) {
        return distanceSquaredToCamera(coordinate, camera.x, camera.y, camera.z);
    }

    private static double distanceSquaredToCamera(LodSectionCoordinate coordinate, double cameraX, double cameraY, double cameraZ) {
        int sectionSize = sectionSize(WorldEngine.getLevel(coordinate.key()));
        double dx = (coordinate.x() + 0.5) * sectionSize - cameraX;
        double dy = (coordinate.y() + 0.5) * sectionSize - cameraY;
        double dz = (coordinate.z() + 0.5) * sectionSize - cameraZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private static List<LodSectionCoordinate> findStoredSections(WorldEngine world, LodGridKey grid) {
        List<LodSectionCoordinate> sections = new ArrayList<>();
        try {
            world.storage.iteratePositions(grid.lodLevel(), key -> {
                int sectionX = WorldEngine.getX(key);
                int sectionY = WorldEngine.getY(key);
                int sectionZ = WorldEngine.getZ(key);
                if (isInsideGrid(grid, sectionX, sectionY, sectionZ)) {
                    sections.add(new LodSectionCoordinate(key, sectionX, sectionY, sectionZ));
                }
            });
        } catch (RuntimeException exception) {
            Logger.warn("Blaze3D LoD storage index is unavailable; probing the configured render grid instead: " + exception.getMessage());
            for (int sectionY = grid.minY(); sectionY <= grid.maxY(); sectionY++) {
                for (int sectionZ = grid.centerZ() - grid.radius(); sectionZ <= grid.centerZ() + grid.radius(); sectionZ++) {
                    for (int sectionX = grid.centerX() - grid.radius(); sectionX <= grid.centerX() + grid.radius(); sectionX++) {
                        if (isInsideGrid(grid, sectionX, sectionY, sectionZ)) {
                            sections.add(new LodSectionCoordinate(
                                    WorldEngine.getWorldSectionId(grid.lodLevel(), sectionX, sectionY, sectionZ),
                                    sectionX, sectionY, sectionZ));
                        }
                    }
                }
            }
        }
        sections.sort(Comparator.comparingLong(section -> sectionDistanceSquared(grid, section)));
        return sections;
    }

    private static boolean isInsideGrid(LodGridKey grid, int sectionX, int sectionY, int sectionZ) {
        if (sectionY < grid.minY() || sectionY > grid.maxY()) {
            return false;
        }
        long dx = sectionX - (long) grid.centerX();
        long dz = sectionZ - (long) grid.centerZ();
        return dx * dx + dz * dz <= (long) grid.radius() * grid.radius();
    }

    private static long sectionDistanceSquared(LodGridKey grid, LodSectionCoordinate section) {
        long dx = section.x() - (long) grid.centerX();
        long dz = section.z() - (long) grid.centerZ();
        return dx * dx + dz * dz;
    }

    private static void rebuildLodSection(WorldEngine world, LodSectionCoordinate coordinate, TextureAtlasSprite fallbackSprite) {
        lodMeshBuildAttempts++;
        int lodLevel = WorldEngine.getLevel(coordinate.key());
        long fingerprint = getNeighborhoodFingerprint(world, lodLevel, coordinate.x(), coordinate.y(), coordinate.z());
        VanillaRenderBoundary vanillaBoundary = activeVanillaBoundary;
        if (fingerprint == Long.MIN_VALUE) {
            lodMeshMissingSections++;
            removeLodMesh(coordinate.key());
            lodMeshFingerprints.remove(coordinate.key());
            return;
        }
        if (Long.valueOf(fingerprint).equals(lodMeshFingerprints.get(coordinate.key()))) {
            lodMeshUnchanged++;
            return;
        }

        WorldSection section = world.acquireIfExists(coordinate.key());
        if (section == null) {
            lodMeshMissingSections++;
            removeLodMesh(coordinate.key());
            lodMeshFingerprints.remove(coordinate.key());
            return;
        }

        long[] data;
        try {
            data = section.copyData();
        } finally {
            section.release();
        }

        SectionNeighborhood neighborhood = new SectionNeighborhood(
                data,
                copySectionData(world, lodLevel, coordinate.x() - 1, coordinate.y(), coordinate.z()),
                copySectionData(world, lodLevel, coordinate.x() + 1, coordinate.y(), coordinate.z()),
                copySectionData(world, lodLevel, coordinate.x(), coordinate.y() - 1, coordinate.z()),
                copySectionData(world, lodLevel, coordinate.x(), coordinate.y() + 1, coordinate.z()),
                copySectionData(world, lodLevel, coordinate.x(), coordinate.y(), coordinate.z() - 1),
                copySectionData(world, lodLevel, coordinate.x(), coordinate.y(), coordinate.z() + 1));
        QuadCounts quadCounts = countModelQuads(neighborhood, world.getMapper(), fallbackSprite,
                coordinate, voxelSize(lodLevel), vanillaBoundary);
        if (quadCounts.isEmpty()) {
            lodMeshEmpty++;
            removeLodMesh(coordinate.key());
            lodMeshFingerprints.put(coordinate.key(), fingerprint);
            return;
        }

        long requestedGeometryBytes = geometryBytes(quadCounts.opaque(), quadCounts.water());
        if (lodGeometryBytes + requestedGeometryBytes > LOD_GEOMETRY_BUDGET_BYTES) {
            lodGeometryBudgetExhausted = true;
            lodGeometryBudgetRejections++;
            if (!loggedLodGeometryBudgetExhaustion) {
                loggedLodGeometryBudgetExhaustion = true;
                Logger.warn("Blaze3D LoD geometry budget reached at " + formatBytes(lodGeometryBytes)
                        + "/" + formatBytes(LOD_GEOMETRY_BUDGET_BYTES)
                        + "; keeping coarser parent coverage instead of allocating another "
                        + formatBytes(requestedGeometryBytes) + " for " + WorldEngine.pprintPos(coordinate.key()) + ".");
            }
            return;
        }

        GpuBuffer opaqueBuffer = null;
        GpuBuffer waterBuffer = null;
        try {
            opaqueBuffer = buildLodMesh(coordinate, neighborhood, world.getMapper(), fallbackSprite,
                    voxelSize(lodLevel), vanillaBoundary, false, quadCounts.opaque());
            waterBuffer = buildLodMesh(coordinate, neighborhood, world.getMapper(), fallbackSprite,
                    voxelSize(lodLevel), vanillaBoundary, true, quadCounts.water());
            replaceLodMesh(coordinate, opaqueBuffer, quadCounts.opaque() * LOD_VERTICES_PER_QUAD,
                    waterBuffer, quadCounts.water() * LOD_VERTICES_PER_QUAD);
        } catch (RuntimeException exception) {
            releaseLodMeshBuffer(opaqueBuffer, coordinate.key());
            releaseLodMeshBuffer(waterBuffer, coordinate.key());
            throw exception;
        }
        lodMeshUploads++;
        lodMeshFingerprints.put(coordinate.key(), fingerprint);
    }

    private static void resetLodDiagnostics() {
        lodMeshBuildAttempts = 0;
        lodMeshUploads = 0;
        lodMeshUnchanged = 0;
        lodMeshMissingSections = 0;
        lodMeshEmpty = 0;
        peakLodGeometryBytes = lodGeometryBytes;
        lodGeometryBudgetRejections = 0;
        lodGeometryBudgetExhausted = false;
        loggedLodGeometryBudgetExhaustion = false;
        lastOpaqueDrawCalls = 0;
        lastOpaqueVertices = 0;
        lastWaterDrawCalls = 0;
        lastWaterVertices = 0;
        lastSelectionFrustumCulledNodes = 0;
        lastSelectionScreenTestedNodes = 0;
        lodBranchHandoffs = 0;
        lodSelectionRuns = 0;
        lodTopologyChanges = 0;
        lodPriorityOnlyChanges = 0;
        lastSelectionReason = "none";
    }

    @Nullable
    private static GpuBuffer buildLodMesh(LodSectionCoordinate coordinate,
                                          SectionNeighborhood neighborhood,
                                          Mapper mapper,
                                          TextureAtlasSprite fallbackSprite,
                                          int voxelSize,
                                          VanillaRenderBoundary vanillaBoundary,
                                          boolean translucentOnly,
                                          int quadCount) {
        if (quadCount == 0) {
            return null;
        }
        int vertexCount = quadCount * LOD_VERTICES_PER_QUAD;
        int bufferSize = vertexCount * LOD_VERTEX_FORMAT.getVertexSize();
        try (ByteBufferBuilder byteBuffer = ByteBufferBuilder.exactlySized(bufferSize)) {
            BufferBuilder builder = new BufferBuilder(byteBuffer, PrimitiveTopology.TRIANGLES, LOD_VERTEX_FORMAT);
            int emittedQuads = emitModelQuads(builder, neighborhood, coordinate.x(), coordinate.y(), coordinate.z(), voxelSize,
                    quadCount, mapper, fallbackSprite, coordinate, vanillaBoundary, translucentOnly);
            if (emittedQuads != quadCount) {
                throw new IllegalStateException("LoD " + (translucentOnly ? "translucent" : "opaque")
                        + " mesh quad count changed while building: expected " + quadCount + ", got " + emittedQuads);
            }
            try (MeshData mesh = builder.buildOrThrow()) {
                return RenderSystem.getDevice().createBuffer(
                        () -> "Voxy Blaze3D LoD " + (translucentOnly ? "translucent " : "opaque ") + WorldEngine.pprintPos(coordinate.key()),
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                        mesh.vertexBuffer());
            }
        }
    }

    private static long getNeighborhoodFingerprint(WorldEngine world, int lodLevel, int sectionX, int sectionY, int sectionZ) {
        long center = getSectionRevision(world, lodLevel, sectionX, sectionY, sectionZ);
        if (center == Long.MIN_VALUE) {
            return Long.MIN_VALUE;
        }
        long fingerprint = mixRevision(0x9E3779B97F4A7C15L, center);
        fingerprint = mixRevision(fingerprint, getSectionRevision(world, lodLevel, sectionX - 1, sectionY, sectionZ));
        fingerprint = mixRevision(fingerprint, getSectionRevision(world, lodLevel, sectionX + 1, sectionY, sectionZ));
        fingerprint = mixRevision(fingerprint, getSectionRevision(world, lodLevel, sectionX, sectionY - 1, sectionZ));
        fingerprint = mixRevision(fingerprint, getSectionRevision(world, lodLevel, sectionX, sectionY + 1, sectionZ));
        fingerprint = mixRevision(fingerprint, getSectionRevision(world, lodLevel, sectionX, sectionY, sectionZ - 1));
        return mixRevision(fingerprint, getSectionRevision(world, lodLevel, sectionX, sectionY, sectionZ + 1));
    }

    private static long getSectionRevision(WorldEngine world, int lodLevel, int sectionX, int sectionY, int sectionZ) {
        WorldSection section = world.acquireIfExists(lodLevel, sectionX, sectionY, sectionZ);
        if (section == null) {
            return -1L;
        }
        try {
            return section.getRenderRevision();
        } finally {
            section.release();
        }
    }

    private static long mixRevision(long hash, long revision) {
        return (hash ^ revision) * 0x100000001B3L;
    }

    private static long[] copySectionData(WorldEngine world, int lodLevel, int sectionX, int sectionY, int sectionZ) {
        WorldSection section = world.acquireIfExists(lodLevel, sectionX, sectionY, sectionZ);
        if (section == null) {
            return null;
        }
        try {
            return section.copyData();
        } finally {
            section.release();
        }
    }

    private static QuadCounts countModelQuads(SectionNeighborhood neighborhood,
                                              Mapper mapper,
                                              TextureAtlasSprite fallbackSprite,
                                              LodSectionCoordinate coordinate,
                                              int voxelSize,
                                              VanillaRenderBoundary vanillaBoundary) {
        int opaqueQuads = 0;
        int waterQuads = 0;
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    long state = neighborhood.center()[WorldSection.getIndex(x, y, z)];
                    if (Mapper.isAir(state)) {
                        continue;
                    }
                    BlockRenderDefinition definition = getBlockRenderDefinition(mapper, Mapper.getBlockId(state), fallbackSprite);
                    opaqueQuads += countQuads(definition.unculledQuads(), false);
                    waterQuads += countQuads(definition.unculledQuads(), true);
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, state, Direction.DOWN, x, y - 1, z)) {
                        opaqueQuads += countQuads(definition.culledQuads(0), false);
                        waterQuads += countQuads(definition.culledQuads(0), true);
                    }
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, state, Direction.UP, x, y + 1, z)) {
                        opaqueQuads += countQuads(definition.culledQuads(1), false);
                        waterQuads += countQuads(definition.culledQuads(1), true);
                    }
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, state, Direction.WEST, x - 1, y, z)) {
                        opaqueQuads += countQuads(definition.culledQuads(2), false);
                        waterQuads += countQuads(definition.culledQuads(2), true);
                    }
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, state, Direction.EAST, x + 1, y, z)) {
                        opaqueQuads += countQuads(definition.culledQuads(3), false);
                        waterQuads += countQuads(definition.culledQuads(3), true);
                    }
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, state, Direction.NORTH, x, y, z - 1)) {
                        opaqueQuads += countQuads(definition.culledQuads(4), false);
                        waterQuads += countQuads(definition.culledQuads(4), true);
                    }
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, state, Direction.SOUTH, x, y, z + 1)) {
                        opaqueQuads += countQuads(definition.culledQuads(5), false);
                        waterQuads += countQuads(definition.culledQuads(5), true);
                    }
                    if (opaqueQuads + waterQuads >= MAX_LOD_QUAD_COUNT_PER_SECTION) {
                        return new QuadCounts(opaqueQuads, waterQuads);
                    }
                }
            }
        }
        return new QuadCounts(opaqueQuads, waterQuads);
    }

    private static int countQuads(List<ModelQuad> quads, boolean translucentOnly) {
        int count = 0;
        for (ModelQuad quad : quads) {
            if (quad.translucent() == translucentOnly) {
                count++;
            }
        }
        return count;
    }

    private static int emitModelQuads(BufferBuilder builder,
                                      SectionNeighborhood neighborhood,
                                      int sectionX, int sectionY, int sectionZ,
                                      int voxelSize,
                                      int maxQuads,
                                      Mapper mapper,
                                      TextureAtlasSprite fallbackSprite,
                                      LodSectionCoordinate coordinate,
                                      VanillaRenderBoundary vanillaBoundary,
                                      boolean translucentOnly) {
        int quads = 0;
        int sectionSize = SECTION_EDGE * voxelSize;
        float originX = sectionX * (float) sectionSize;
        float originY = sectionY * (float) sectionSize;
        float originZ = sectionZ * (float) sectionSize;
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    long state = neighborhood.center()[WorldSection.getIndex(x, y, z)];
                    if (Mapper.isAir(state)) {
                        continue;
                    }

                    float blockX = originX + x * voxelSize;
                    float blockY = originY + y * voxelSize;
                    float blockZ = originZ + z * voxelSize;
                    BlockRenderDefinition definition = getBlockRenderDefinition(mapper, Mapper.getBlockId(state), fallbackSprite);
                    quads = emitQuads(builder, definition.unculledQuads(), neighborhood, x, y, z, state, mapper, blockX, blockY, blockZ, voxelSize,
                            brightestLight(neighborhood, x, y, z, state), translucentOnly, quads, maxQuads);
                    if (quads == maxQuads) return quads;
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, state, Direction.DOWN, x, y - 1, z)) quads = emitQuads(builder, definition.culledQuads(0), neighborhood, x, y, z, state, mapper, blockX, blockY, blockZ, voxelSize,
                            visibleFaceLight(state, getState(neighborhood, x, y - 1, z)), translucentOnly, quads, maxQuads);
                    if (quads == maxQuads) return quads;
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, state, Direction.UP, x, y + 1, z)) quads = emitQuads(builder, definition.culledQuads(1), neighborhood, x, y, z, state, mapper, blockX, blockY, blockZ, voxelSize,
                            visibleFaceLight(state, getState(neighborhood, x, y + 1, z)), translucentOnly, quads, maxQuads);
                    if (quads == maxQuads) return quads;
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, state, Direction.WEST, x - 1, y, z)) quads = emitQuads(builder, definition.culledQuads(2), neighborhood, x, y, z, state, mapper, blockX, blockY, blockZ, voxelSize,
                            visibleFaceLight(state, getState(neighborhood, x - 1, y, z)), translucentOnly, quads, maxQuads);
                    if (quads == maxQuads) return quads;
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, state, Direction.EAST, x + 1, y, z)) quads = emitQuads(builder, definition.culledQuads(3), neighborhood, x, y, z, state, mapper, blockX, blockY, blockZ, voxelSize,
                            visibleFaceLight(state, getState(neighborhood, x + 1, y, z)), translucentOnly, quads, maxQuads);
                    if (quads == maxQuads) return quads;
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, state, Direction.NORTH, x, y, z - 1)) quads = emitQuads(builder, definition.culledQuads(4), neighborhood, x, y, z, state, mapper, blockX, blockY, blockZ, voxelSize,
                            visibleFaceLight(state, getState(neighborhood, x, y, z - 1)), translucentOnly, quads, maxQuads);
                    if (quads == maxQuads) return quads;
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, state, Direction.SOUTH, x, y, z + 1)) quads = emitQuads(builder, definition.culledQuads(5), neighborhood, x, y, z, state, mapper, blockX, blockY, blockZ, voxelSize,
                            visibleFaceLight(state, getState(neighborhood, x, y, z + 1)), translucentOnly, quads, maxQuads);
                    if (quads == maxQuads) return quads;
                }
            }
        }
        return quads;
    }

    private static boolean shouldEmitCulledFace(SectionNeighborhood neighborhood,
                                                Mapper mapper,
                                                BlockRenderDefinition current,
                                                TextureAtlasSprite fallbackSprite,
                                                long currentState,
                                                Direction direction,
                                                int x,
                                                int y,
                                                int z) {
        long neighborState = getState(neighborhood, x, y, z);
        if (Mapper.isAir(neighborState)) {
            return true;
        }
        BlockRenderDefinition neighbor = getBlockRenderDefinition(mapper, Mapper.getBlockId(neighborState), fallbackSprite);
        BlockState currentBlockState = mapper.getBlockStateFromBlockId(Mapper.getBlockId(currentState));
        BlockState neighborBlockState = mapper.getBlockStateFromBlockId(Mapper.getBlockId(neighborState));
        // Fluids occupy a separate surface within a block. They do not hide solid faces, but
        // adjacent fluid cells must still cull each other or every depth layer becomes visible.
        if (current.hasFluid() && neighbor.hasFluid()) {
            return false;
        }
        // Minecraft's own shape/skipRendering test handles adjacent full cubes as well as glass
        // and other same-material translucent blocks. It is much less conservative than the old
        // collision-cube approximation, which emitted nearly every underground interface.
        return Block.shouldRenderFace(currentBlockState, neighborBlockState, direction);
    }

    private static long getState(SectionNeighborhood neighborhood, int x, int y, int z) {
        if (x < 0) return getState(neighborhood.negativeX(), 31, y, z);
        if (x >= 32) return getState(neighborhood.positiveX(), 0, y, z);
        if (y < 0) return getState(neighborhood.negativeY(), x, 31, z);
        if (y >= 32) return getState(neighborhood.positiveY(), x, 0, z);
        if (z < 0) return getState(neighborhood.negativeZ(), x, y, 31);
        if (z >= 32) return getState(neighborhood.positiveZ(), x, y, 0);
        return neighborhood.center()[WorldSection.getIndex(x, y, z)];
    }

    private static long getState(long[] data, int x, int y, int z) {
        return data == null ? 0L : data[WorldSection.getIndex(x, y, z)];
    }

    private static int visibleFaceLight(long state, long neighborState) {
        // The original renderer assigns the adjacent voxel's light to exterior faces. Preserve
        // the block's contribution too for non-opaque and mip-mapped Voxy cells.
        return brightestLight(Mapper.getLightId(state), Mapper.getLightId(neighborState));
    }

    private static int brightestLight(SectionNeighborhood neighborhood, int x, int y, int z, long state) {
        int light = Mapper.getLightId(state);
        light = brightestLight(light, Mapper.getLightId(getState(neighborhood, x, y - 1, z)));
        light = brightestLight(light, Mapper.getLightId(getState(neighborhood, x, y + 1, z)));
        light = brightestLight(light, Mapper.getLightId(getState(neighborhood, x - 1, y, z)));
        light = brightestLight(light, Mapper.getLightId(getState(neighborhood, x + 1, y, z)));
        light = brightestLight(light, Mapper.getLightId(getState(neighborhood, x, y, z - 1)));
        return brightestLight(light, Mapper.getLightId(getState(neighborhood, x, y, z + 1)));
    }

    private static int brightestLight(int first, int second) {
        int blockLight = Math.max(first >>> 4 & 0xF, second >>> 4 & 0xF);
        int skyLight = Math.max(first & 0xF, second & 0xF);
        return blockLight << 4 | skyLight;
    }

    private static int emitQuads(BufferBuilder builder,
                                 List<ModelQuad> quads,
                                 SectionNeighborhood neighborhood,
                                 int cellX,
                                 int cellY,
                                 int cellZ,
                                 long state,
                                 Mapper mapper,
                                 float blockX,
                                 float blockY,
                                 float blockZ,
                                 int voxelSize,
                                 int packedLight,
                                 boolean translucentOnly,
                                 int emitted,
                                 int maxQuads) {
        for (ModelQuad quad : quads) {
            if (quad.translucent() != translucentOnly) {
                continue;
            }
            if (quad.fluidTint()) {
                quad = createFluidQuad(neighborhood, mapper, state, cellX, cellY, cellZ, quad.direction());
            }
            int color = quad.tinted()
                    ? resolveTintColor(mapper, Mapper.getBlockId(state), Mapper.getBiomeId(state), quad.tintIndex(), quad.fluidTint(), blockX, blockY, blockZ)
                    : 0xFFFFFF;
            color = shadeColor(color, quad.direction());
            addModelQuad(builder, quad, blockX, blockY, blockZ, voxelSize, color, quad.fluidTint() ? 204 : 255, packedLight,
                    quad.shaded() ? cornerAmbientOcclusion(neighborhood, mapper, cellX, cellY, cellZ, quad) : NO_CORNER_OCCLUSION);
            if (++emitted == maxQuads) {
                return emitted;
            }
        }
        return emitted;
    }

    private static BlockRenderDefinition getBlockRenderDefinition(Mapper mapper, int blockId, TextureAtlasSprite fallbackSprite) {
        BlockRenderDefinition cached = blockRenderDefinitions.get(blockId);
        if (cached != null) {
            return cached;
        }

        List<ModelQuad> unculled = new ArrayList<>();
        List<ModelQuad>[] culled = createQuadBuckets();
        BlockState state = null;
        try {
            state = mapper.getBlockStateFromBlockId(blockId);
            if (state.getBlock() instanceof LiquidBlock) {
                addFluidCube(culled, state);
            } else {
                if (state.getRenderShape() != RenderShape.INVISIBLE) {
                    var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
                    List<BlockStateModelPart> parts = new ArrayList<>();
                    model.collectParts(new SingleThreadedRandomSource(blockId * 0x9E3779B9L), parts);
                    for (BlockStateModelPart part : parts) {
                        for (int face = 0; face < 6; face++) {
                            for (BakedQuad quad : part.getQuads(faceToDirection(face))) {
                                culled[face].add(toModelQuad(quad));
                            }
                        }
                        for (BakedQuad quad : part.getQuads(null)) {
                            unculled.add(toModelQuad(quad));
                        }
                    }
                }
                // Waterlogged vegetation and decorations still own a fluid surface.
                if (!state.getFluidState().isEmpty()) {
                    addFluidCube(culled, state);
                }
            }
        } catch (RuntimeException exception) {
            Logger.warn("Blaze3D LoD model lookup failed for Voxy block id=" + blockId
                    + "; using the particle-texture cube: " + exception.getMessage());
        }

        if (unculled.isEmpty() && allBucketsEmpty(culled)) {
            addFallbackCube(culled, fallbackSprite);
        }
        boolean fullCubeModel = isFullCubeModel(unculled, culled);
        boolean occludesNeighbors = state != null && Minecraft.getInstance().level != null && state.getFluidState().isEmpty()
                && state.canOcclude() && state.isCollisionShapeFullBlock(Minecraft.getInstance().level, BlockPos.ZERO)
                && fullCubeModel;
        BlockRenderDefinition definition = new BlockRenderDefinition(unculled, culled,
                state != null && !state.getFluidState().isEmpty(), occludesNeighbors);
        blockRenderDefinitions.put(blockId, definition);
        logMaterialDefinition(blockId, state, definition);
        return definition;
    }

    @SuppressWarnings("unchecked")
    private static List<ModelQuad>[] createQuadBuckets() {
        List<ModelQuad>[] buckets = (List<ModelQuad>[]) new List<?>[6];
        for (int index = 0; index < buckets.length; index++) {
            buckets[index] = new ArrayList<>();
        }
        return buckets;
    }

    private static boolean allBucketsEmpty(List<ModelQuad>[] buckets) {
        for (List<ModelQuad> bucket : buckets) {
            if (!bucket.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFullCubeModel(List<ModelQuad> unculled, List<ModelQuad>[] culled) {
        if (!unculled.isEmpty()) {
            return false;
        }
        for (int face = 0; face < 6; face++) {
            boolean hasFullFace = false;
            for (ModelQuad quad : culled[face]) {
                if (isUnitCubeFace(quad.positions(), face)) {
                    hasFullFace = true;
                } else {
                    return false;
                }
            }
            if (!hasFullFace) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUnitCubeFace(float[] positions, int face) {
        float[] expected = UNIT_CUBE_FACE_POSITIONS[face];
        if (positions.length != expected.length) {
            return false;
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            int positionOffset = vertex * 3;
            boolean matchesCubeCorner = false;
            for (int expectedVertex = 0; expectedVertex < 4; expectedVertex++) {
                int expectedOffset = expectedVertex * 3;
                if (Math.abs(positions[positionOffset] - expected[expectedOffset]) <= 0.0001f
                        && Math.abs(positions[positionOffset + 1] - expected[expectedOffset + 1]) <= 0.0001f
                        && Math.abs(positions[positionOffset + 2] - expected[expectedOffset + 2]) <= 0.0001f) {
                    matchesCubeCorner = true;
                    break;
                }
            }
            if (!matchesCubeCorner) {
                return false;
            }
        }
        return true;
    }

    private static ModelQuad toModelQuad(BakedQuad quad) {
        float[] positions = new float[12];
        float[] uvs = new float[8];
        for (int index = 0; index < 4; index++) {
            var position = quad.position(index);
            positions[index * 3] = position.x();
            positions[index * 3 + 1] = position.y();
            positions[index * 3 + 2] = position.z();
            long packedUv = quad.packedUV(index);
            // FaceBakery has already translated these model UVs into atlas coordinates.
            uvs[index * 2] = UVPair.unpackU(packedUv);
            uvs[index * 2 + 1] = UVPair.unpackV(packedUv);
        }
        return new ModelQuad(positions, uvs, quad.materialInfo().isTinted(), quad.materialInfo().tintIndex(), quad.direction(),
                quad.materialInfo().layer() == ChunkSectionLayer.TRANSLUCENT, false, quad.materialInfo().shade());
    }

    private static void addFallbackCube(List<ModelQuad>[] culled, TextureAtlasSprite sprite) {
        for (int face = 0; face < 6; face++) {
            culled[face].add(cubeQuad(sprite, faceToDirection(face), UNIT_CUBE_FACE_POSITIONS[face]));
        }
    }

    private static void addFluidCube(List<ModelQuad>[] culled, BlockState state) {
        FluidModel fluidModel = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(state.getFluidState());
        TextureAtlasSprite stillSprite = fluidModel.stillMaterial().sprite();
        TextureAtlasSprite flowingSprite = fluidModel.flowingMaterial().sprite();
        // The cached definitions only classify fluid faces. Their sprite and the four top-edge
        // heights are resolved from the neighboring Voxy cells when the section is built.
        culled[0].add(cubeQuad(stillSprite, Direction.DOWN, true, 0, UNIT_CUBE_FACE_POSITIONS[0]));
        culled[1].add(cubeQuad(stillSprite, Direction.UP, true, 0, UNIT_CUBE_FACE_POSITIONS[1]));
        culled[2].add(cubeQuad(flowingSprite, Direction.WEST, true, 0, UNIT_CUBE_FACE_POSITIONS[2]));
        culled[3].add(cubeQuad(flowingSprite, Direction.EAST, true, 0, UNIT_CUBE_FACE_POSITIONS[3]));
        culled[4].add(cubeQuad(flowingSprite, Direction.NORTH, true, 0, UNIT_CUBE_FACE_POSITIONS[4]));
        culled[5].add(cubeQuad(flowingSprite, Direction.SOUTH, true, 0, UNIT_CUBE_FACE_POSITIONS[5]));
    }

    private static ModelQuad createFluidQuad(SectionNeighborhood neighborhood,
                                             Mapper mapper,
                                             long state,
                                             int cellX,
                                             int cellY,
                                             int cellZ,
                                             Direction direction) {
        FluidState fluidState = mapper.getBlockStateFromBlockId(Mapper.getBlockId(state)).getFluidState();
        FluidModel fluidModel = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluidState);
        float[] positions = UNIT_CUBE_FACE_POSITIONS[directionToFace(direction)].clone();
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * 3;
            if (positions[offset + 1] != 0.0f) {
                positions[offset + 1] = fluidCornerHeight(neighborhood, mapper, fluidState, cellX, cellY, cellZ,
                        positions[offset] == 0.0f ? 0 : 1, positions[offset + 2] == 0.0f ? 0 : 1);
            }
        }
        if (direction == Direction.UP) {
            return createFluidTopQuad(neighborhood, mapper, fluidState, cellX, cellY, cellZ, fluidModel, positions);
        }
        return cubeQuad(fluidModel.flowingMaterial().sprite(), direction, true, 0, positions);
    }

    private static ModelQuad createFluidTopQuad(SectionNeighborhood neighborhood,
                                                Mapper mapper,
                                                FluidState fluidState,
                                                int cellX,
                                                int cellY,
                                                int cellZ,
                                                FluidModel fluidModel,
                                                float[] positions) {
        Vec3 flow = fluidState.getFlow(new VoxyFluidGetter(neighborhood, mapper, cellX, cellY, cellZ), BlockPos.ZERO);
        if (flow.x == 0.0 && flow.z == 0.0) {
            return cubeQuad(fluidModel.stillMaterial().sprite(), Direction.UP, true, 0, positions);
        }

        // Match FluidRenderer: the flowing sprite is rotated around its centre by FluidState#getFlow.
        float angle = (float) Math.atan2(flow.z, flow.x) - (float) (Math.PI / 2.0);
        float sin = (float) Math.sin(angle) * 0.25f;
        float cos = (float) Math.cos(angle) * 0.25f;
        TextureAtlasSprite sprite = fluidModel.flowingMaterial().sprite();
        float[] uvs = {
                sprite.getU(0.5f - cos + sin), sprite.getV(0.5f + cos + sin),
                sprite.getU(0.5f + cos + sin), sprite.getV(0.5f + cos - sin),
                sprite.getU(0.5f + cos - sin), sprite.getV(0.5f - cos - sin),
                sprite.getU(0.5f - cos - sin), sprite.getV(0.5f - cos + sin)
        };
        return new ModelQuad(positions, uvs, true, 0, Direction.UP, true, true, false);
    }

    private static float fluidCornerHeight(SectionNeighborhood neighborhood,
                                           Mapper mapper,
                                           FluidState expectedFluid,
                                           int cellX,
                                           int cellY,
                                           int cellZ,
                                           int cornerX,
                                           int cornerZ) {
        int offsetX = cornerX == 0 ? -1 : 1;
        int offsetZ = cornerZ == 0 ? -1 : 1;
        float center = fluidHeight(neighborhood, mapper, expectedFluid, cellX, cellY, cellZ);
        if (center >= 1.0f) {
            return 1.0f;
        }
        float alongX = fluidHeight(neighborhood, mapper, expectedFluid, cellX + offsetX, cellY, cellZ);
        float alongZ = fluidHeight(neighborhood, mapper, expectedFluid, cellX, cellY, cellZ + offsetZ);
        if (alongX >= 1.0f || alongZ >= 1.0f) {
            return 1.0f;
        }

        // This is FluidRenderer#calculateAverageHeight.  Its high-water weighting is what keeps
        // a flowing surface joined to an adjacent source block instead of leaving a visible seam.
        float[] weighted = new float[2];
        if (alongX > 0.0f || alongZ > 0.0f) {
            float diagonal = fluidHeight(neighborhood, mapper, expectedFluid, cellX + offsetX, cellY, cellZ + offsetZ);
            if (diagonal >= 1.0f) {
                return 1.0f;
            }
            addWeightedFluidHeight(weighted, diagonal);
        }
        addWeightedFluidHeight(weighted, center);
        addWeightedFluidHeight(weighted, alongX);
        addWeightedFluidHeight(weighted, alongZ);
        return weighted[1] == 0.0f ? expectedFluid.getOwnHeight() : weighted[0] / weighted[1];
    }

    private static float fluidHeight(SectionNeighborhood neighborhood,
                                     Mapper mapper,
                                     FluidState expectedFluid,
                                     int cellX,
                                     int cellY,
                                     int cellZ) {
        BlockState state = mapper.getBlockStateFromBlockId(Mapper.getBlockId(getState(neighborhood, cellX, cellY, cellZ)));
        FluidState sample = state.getFluidState();
        if (expectedFluid.getType().isSame(sample.getType())) {
            FluidState above = mapper.getBlockStateFromBlockId(Mapper.getBlockId(
                    getState(neighborhood, cellX, cellY + 1, cellZ))).getFluidState();
            return expectedFluid.getType().isSame(above.getType()) ? 1.0f : sample.getOwnHeight();
        }
        return state.isSolid() ? -1.0f : 0.0f;
    }

    private static void addWeightedFluidHeight(float[] weighted, float height) {
        if (height >= 0.8f) {
            weighted[0] += height * 10.0f;
            weighted[1] += 10.0f;
        } else if (height >= 0.0f) {
            weighted[0] += height;
            weighted[1] += 1.0f;
        }
    }

    private static ModelQuad cubeQuad(TextureAtlasSprite sprite, Direction direction, float... positions) {
        return cubeQuad(sprite, direction, false, 0, positions);
    }

    private static ModelQuad cubeQuad(TextureAtlasSprite sprite,
                                      Direction direction,
                                      boolean tinted,
                                      int tintIndex,
                                      float... positions) {
        if (positions.length != 12) {
            throw new IllegalArgumentException("Expected four cube vertices for " + direction
                    + ", got " + positions.length + " position coordinates.");
        }
        return new ModelQuad(positions, new float[]{
                sprite.getU(0), sprite.getV(1), sprite.getU(1), sprite.getV(1),
                sprite.getU(1), sprite.getV(0), sprite.getU(0), sprite.getV(0)}, tinted, tintIndex, direction, tinted, tinted, false);
    }

    private static void logMaterialDefinition(int blockId, @Nullable BlockState state, BlockRenderDefinition definition) {
        if (loggedMaterialDefinitions >= MATERIAL_LOG_LIMIT) {
            return;
        }
        loggedMaterialDefinitions++;
        int quadCount = definition.unculledQuads().size();
        for (int face = 0; face < 6; face++) {
            quadCount += definition.culledQuads(face).size();
        }
        Logger.info("Blaze3D LoD material " + loggedMaterialDefinitions + "/" + MATERIAL_LOG_LIMIT
                + ": blockId=" + blockId
                + ", state=" + state
                + ", fluid=" + (state != null && state.getBlock() instanceof LiquidBlock)
                + ", quads=" + quadCount
                + ", uvSpace=atlas, alphaMode=" + (definition.hasFluid() ? "split-water" : "opaque") + ".");
    }

    private static Direction faceToDirection(int face) {
        return switch (face) {
            case 0 -> Direction.DOWN;
            case 1 -> Direction.UP;
            case 2 -> Direction.WEST;
            case 3 -> Direction.EAST;
            case 4 -> Direction.NORTH;
            case 5 -> Direction.SOUTH;
            default -> throw new IllegalArgumentException("Unknown cube face " + face);
        };
    }

    private static int directionToFace(Direction direction) {
        return switch (direction) {
            case DOWN -> 0;
            case UP -> 1;
            case WEST -> 2;
            case EAST -> 3;
            case NORTH -> 4;
            case SOUTH -> 5;
        };
    }

    private static void addModelQuad(BufferBuilder builder,
                                     ModelQuad quad,
                                     float blockX,
                                     float blockY,
                                     float blockZ,
                                     int voxelSize,
                                     int color,
                                     int alpha,
                                     int packedLight,
                                     float[] cornerOcclusion) {
        int red = color >> 16 & 0xFF;
        int green = color >> 8 & 0xFF;
        int blue = color & 0xFF;
        addModelVertex(builder, quad, 0, blockX, blockY, blockZ, voxelSize, red, green, blue, alpha, packedLight, cornerOcclusion[0]);
        addModelVertex(builder, quad, 1, blockX, blockY, blockZ, voxelSize, red, green, blue, alpha, packedLight, cornerOcclusion[1]);
        addModelVertex(builder, quad, 2, blockX, blockY, blockZ, voxelSize, red, green, blue, alpha, packedLight, cornerOcclusion[2]);
        addModelVertex(builder, quad, 3, blockX, blockY, blockZ, voxelSize, red, green, blue, alpha, packedLight, cornerOcclusion[3]);
    }

    private static void addModelVertex(BufferBuilder builder,
                                       ModelQuad quad,
                                       int vertex,
                                       float blockX,
                                       float blockY,
                                       float blockZ,
                                       int voxelSize,
                                       int red,
                                       int green,
                                       int blue,
                                       int alpha,
                                       int packedLight,
                                       float occlusion) {
        float[] positions = quad.positions();
        float[] uvs = quad.uvs();
        int positionOffset = vertex * 3;
        int uvOffset = vertex * 2;
        builder.addVertex(blockX + positions[positionOffset] * voxelSize,
                        blockY + positions[positionOffset + 1] * voxelSize,
                        blockZ + positions[positionOffset + 2] * voxelSize)
                .setUv(uvs[uvOffset], uvs[uvOffset + 1])
                .setUv2((packedLight >>> 4 & 0xF) << 4, (packedLight & 0xF) << 4)
                .setColor(Math.round(red * occlusion), Math.round(green * occlusion), Math.round(blue * occlusion), alpha);
    }

    private static int resolveTintColor(Mapper mapper,
                                        int blockId,
                                        int biomeId,
                                        int tintIndex,
                                        boolean fluidTint,
                                        float blockX,
                                        float blockY,
                                        float blockZ) {
        TintKey key = new TintKey(blockId, biomeId, tintIndex, fluidTint);
        Integer cached = tintColors.get(key);
        if (cached != null) {
            return cached;
        }
        int color = 0xFFFFFF;
        try {
            BlockState state = mapper.getBlockStateFromBlockId(blockId);
            BlockTintSource tintSource = fluidTint
                    ? Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(state.getFluidState()).tintSource()
                    : Minecraft.getInstance().getBlockColors().getTintSource(state, tintIndex);
            if (tintSource != null) {
                BlockPos position = BlockPos.containing(blockX, blockY, blockZ);
                int resolved = tintSource.colorInWorld(state, new VoxyTintGetter(state, resolveBiome(mapper, biomeId)), position);
                if (resolved != -1) {
                    color = resolved & 0xFFFFFF;
                }
            }
        } catch (RuntimeException exception) {
            Logger.warn("Blaze3D LoD tint lookup failed for Voxy block id=" + blockId + ": " + exception.getMessage());
        }
        tintColors.put(key, color);
        return color;
    }

    private static Biome resolveBiome(Mapper mapper, int biomeId) {
        Biome cached = voxyBiomes.get(biomeId);
        if (cached != null) {
            return cached;
        }
        Biome biome = null;
        Mapper.BiomeEntry[] entries = mapper.getBiomeEntries();
        if (biomeId >= 0 && biomeId < entries.length && entries[biomeId].biome != null) {
            biome = Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BIOME)
                    .getValue(Identifier.parse(entries[biomeId].biome));
        }
        if (biome == null) {
            biome = Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BIOME)
                    .getValue(net.minecraft.world.level.biome.Biomes.PLAINS);
        }
        voxyBiomes.put(biomeId, biome);
        return biome;
    }

    private static int shadeColor(int color, Direction direction) {
        float shade = switch (direction) {
            case DOWN -> 0.45f;
            case UP -> 1.0f;
            case NORTH, WEST -> 0.68f;
            default -> 0.82f;
        };
        int red = Math.round(((color >> 16) & 0xFF) * shade);
        int green = Math.round(((color >> 8) & 0xFF) * shade);
        int blue = Math.round((color & 0xFF) * shade);
        return red << 16 | green << 8 | blue;
    }

    private static float[] cornerAmbientOcclusion(SectionNeighborhood neighborhood,
                                                   Mapper mapper,
                                                   int cellX,
                                                   int cellY,
                                                   int cellZ,
                                                   ModelQuad quad) {
        float[] occlusion = new float[4];
        Direction face = quad.direction();
        for (int vertex = 0; vertex < 4; vertex++) {
            int positionOffset = vertex * 3;
            float[] positions = quad.positions();
            int signA;
            int signB;
            int tangentAX;
            int tangentAY;
            int tangentAZ;
            int tangentBX;
            int tangentBY;
            int tangentBZ;
            switch (face.getAxis()) {
                case Y -> {
                    signA = positions[positionOffset] == 0.0f ? -1 : 1;
                    signB = positions[positionOffset + 2] == 0.0f ? -1 : 1;
                    tangentAX = 1; tangentAY = 0; tangentAZ = 0;
                    tangentBX = 0; tangentBY = 0; tangentBZ = 1;
                }
                case X -> {
                    signA = positions[positionOffset + 1] == 0.0f ? -1 : 1;
                    signB = positions[positionOffset + 2] == 0.0f ? -1 : 1;
                    tangentAX = 0; tangentAY = 1; tangentAZ = 0;
                    tangentBX = 0; tangentBY = 0; tangentBZ = 1;
                }
                case Z -> {
                    signA = positions[positionOffset] == 0.0f ? -1 : 1;
                    signB = positions[positionOffset + 1] == 0.0f ? -1 : 1;
                    tangentAX = 1; tangentAY = 0; tangentAZ = 0;
                    tangentBX = 0; tangentBY = 1; tangentBZ = 0;
                }
                default -> throw new IllegalStateException("Unknown face axis " + face.getAxis());
            }
            int normalX = face.getStepX();
            int normalY = face.getStepY();
            int normalZ = face.getStepZ();
            boolean sideA = isAmbientOccluder(neighborhood, mapper,
                    cellX + normalX + tangentAX * signA,
                    cellY + normalY + tangentAY * signA,
                    cellZ + normalZ + tangentAZ * signA);
            boolean sideB = isAmbientOccluder(neighborhood, mapper,
                    cellX + normalX + tangentBX * signB,
                    cellY + normalY + tangentBY * signB,
                    cellZ + normalZ + tangentBZ * signB);
            boolean corner = isAmbientOccluder(neighborhood, mapper,
                    cellX + normalX + tangentAX * signA + tangentBX * signB,
                    cellY + normalY + tangentAY * signA + tangentBY * signB,
                    cellZ + normalZ + tangentAZ * signA + tangentBZ * signB);
            int blockers = sideA && sideB ? 3 : (sideA ? 1 : 0) + (sideB ? 1 : 0) + (corner ? 1 : 0);
            occlusion[vertex] = 1.0f - blockers * 0.18f;
        }
        return occlusion;
    }

    private static boolean isAmbientOccluder(SectionNeighborhood neighborhood, Mapper mapper, int x, int y, int z) {
        long stateId = getState(neighborhood, x, y, z);
        if (Mapper.isAir(stateId)) {
            return false;
        }
        BlockState state = mapper.getBlockStateFromBlockId(Mapper.getBlockId(stateId));
        return state.getFluidState().isEmpty() && state.canOcclude() && state.isSolid();
    }


    private static boolean isAir(SectionNeighborhood neighborhood, int x, int y, int z) {
        if (x < 0) return isAir(neighborhood.negativeX(), 31, y, z);
        if (x >= 32) return isAir(neighborhood.positiveX(), 0, y, z);
        if (y < 0) return isAir(neighborhood.negativeY(), x, 31, z);
        if (y >= 32) return isAir(neighborhood.positiveY(), x, 0, z);
        if (z < 0) return isAir(neighborhood.negativeZ(), x, y, 31);
        if (z >= 32) return isAir(neighborhood.positiveZ(), x, y, 0);
        return Mapper.isAir(neighborhood.center()[WorldSection.getIndex(x, y, z)]);
    }

    private static boolean isAir(long[] data, int x, int y, int z) {
        return data == null || Mapper.isAir(data[WorldSection.getIndex(x, y, z)]);
    }

    private record SectionNeighborhood(long[] center,
                                       long[] negativeX, long[] positiveX,
                                       long[] negativeY, long[] positiveY,
                                       long[] negativeZ, long[] positiveZ) {
    }

    private record ModelQuad(float[] positions,
                             float[] uvs,
                             boolean tinted,
                             int tintIndex,
                             Direction direction,
                             boolean translucent,
                             boolean fluidTint,
                             boolean shaded) {
    }

    private record BlockRenderDefinition(List<ModelQuad> unculledQuads,
                                         List<ModelQuad>[] culledQuadBuckets,
                                         boolean hasFluid,
                                         boolean occludesNeighbors) {
        private List<ModelQuad> culledQuads(int face) {
            return culledQuadBuckets[face];
        }
    }

    private record TintKey(int blockId, int biomeId, int tintIndex, boolean fluidTint) {
    }

    private static final class VoxyTintGetter implements BlockAndTintGetter {
        private final BlockState state;
        private final Biome biome;

        private VoxyTintGetter(BlockState state, Biome biome) {
            this.state = state;
            this.biome = biome;
        }

        @Override
        public int getBrightness(LightLayer type, BlockPos pos) {
            return 0;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
        }

        @Override
        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            return colorResolver.getColor(biome, pos.getX(), pos.getZ());
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return state;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return state.getFluidState();
        }

        @Override
        public int getHeight() {
            return 0;
        }

        @Override
        public int getMinY() {
            return 0;
        }
    }

    private static final class VoxyFluidGetter implements BlockGetter {
        private final SectionNeighborhood neighborhood;
        private final Mapper mapper;
        private final int cellX;
        private final int cellY;
        private final int cellZ;

        private VoxyFluidGetter(SectionNeighborhood neighborhood, Mapper mapper, int cellX, int cellY, int cellZ) {
            this.neighborhood = neighborhood;
            this.mapper = mapper;
            this.cellX = cellX;
            this.cellY = cellY;
            this.cellZ = cellZ;
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            long state = getState(neighborhood, cellX + pos.getX(), cellY + pos.getY(), cellZ + pos.getZ());
            return mapper.getBlockStateFromBlockId(Mapper.getBlockId(state));
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 0;
        }

        @Override
        public int getMinY() {
            return 0;
        }
    }

    private static GpuBuffer uploadMesh(GpuBuffer buffer, CommandEncoder encoder, String label, MeshData mesh) {
        int byteCount = mesh.vertexBuffer().remaining();
        if (buffer == null || buffer.isClosed() || buffer.size() != byteCount) {
            releaseBuffer(buffer, label);
            buffer = RenderSystem.getDevice().createBuffer(() -> label,
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    mesh.vertexBuffer());
            Logger.info("Blaze3D probe allocated a " + byteCount + " byte " + label + " buffer.");
        } else {
            encoder.writeToBuffer(buffer.slice(0, byteCount), mesh.vertexBuffer());
        }
        return buffer;
    }

    private static void replaceLodMesh(LodSectionCoordinate coordinate,
                                       @Nullable GpuBuffer opaqueBuffer,
                                       int opaqueVertexCount,
                                       @Nullable GpuBuffer waterBuffer,
                                       int waterVertexCount) {
        removeLodMesh(coordinate.key());
        LodSectionMesh mesh = new LodSectionMesh(coordinate, opaqueBuffer, opaqueVertexCount, waterBuffer, waterVertexCount);
        lodMeshes.put(coordinate.key(), mesh);
        lodGeometryBytes += geometryBytes(mesh);
        peakLodGeometryBytes = Math.max(peakLodGeometryBytes, lodGeometryBytes);
    }

    private static void removeLodMesh(long sectionKey) {
        LodSectionMesh previous = lodMeshes.remove(sectionKey);
        if (previous == null) {
            return;
        }
        lodGeometryBytes = Math.max(0L, lodGeometryBytes - geometryBytes(previous));
        releaseLodMeshBuffer(previous.opaqueVertexBuffer(), sectionKey);
        releaseLodMeshBuffer(previous.waterVertexBuffer(), sectionKey);
    }

    private static void clearLodMeshes() {
        int meshCount = lodMeshes.size();
        for (LodSectionMesh mesh : lodMeshes.values()) {
            releaseLodMeshBuffer(mesh.opaqueVertexBuffer(), mesh.coordinate().key());
            releaseLodMeshBuffer(mesh.waterVertexBuffer(), mesh.coordinate().key());
        }
        lodMeshes.clear();
        lodGeometryBytes = 0L;
        lodGeometryBudgetExhausted = false;
        loggedLodGeometryBudgetExhaustion = false;
        lodMeshFingerprints.clear();
        if (meshCount != 0) {
            Logger.info("Blaze3D probe released " + meshCount + " cached LoD meshes after " + frameCount + " frames.");
        }
    }

    private static void prepareLodTransition(List<LodSectionCoordinate> selected) {
        lodGeometryBudgetExhausted = false;
        LinkedHashMap<Long, LodSectionCoordinate> buildOrder = new LinkedHashMap<>();
        // First enqueue every desired leaf in camera-distance order.
        for (LodSectionCoordinate leaf : selected) {
            buildOrder.putIfAbsent(leaf.key(), leaf);
        }
        // Fallback ancestors come afterwards. Normally coverage propagated from the completed
        // leaves marks these ready before the scheduler reaches them, so they are never uploaded.
        for (LodSectionCoordinate leaf : selected) {
            int leafLevel = WorldEngine.getLevel(leaf.key());
            for (int level = leafLevel + 1; level <= WorldEngine.MAX_LOD_LAYER; level++) {
                int shift = level - leafLevel;
                int x = Math.floorDiv(leaf.x(), 1 << shift);
                int y = Math.floorDiv(leaf.y(), 1 << shift);
                int z = Math.floorDiv(leaf.z(), 1 << shift);
                long key = WorldEngine.getWorldSectionId(level, x, y, z);
                buildOrder.putIfAbsent(key, new LodSectionCoordinate(key, x, y, z));
            }
        }
        transitionBuildSections = List.copyOf(buildOrder.values());
        transitionPendingChildren.clear();
        transitionParentKeys.clear();
        transitionCoverageReadyKeys.clear();

        Map<Long, List<Long>> childrenByParent = new HashMap<>();
        for (LodSectionCoordinate child : transitionBuildSections) {
            int childLevel = WorldEngine.getLevel(child.key());
            if (childLevel >= WorldEngine.MAX_LOD_LAYER) {
                continue;
            }
            long parentKey = parentKey(child);
            if (buildOrder.containsKey(parentKey)) {
                childrenByParent.computeIfAbsent(parentKey, ignored -> new ArrayList<>(8)).add(child.key());
            }
        }

        // An existing node covers its branch immediately. A missing intermediate node is also
        // covered when all of its required direct child branches already have coverage.
        for (LodSectionCoordinate node : transitionBuildSections) {
            if (lodMeshFingerprints.containsKey(node.key())) {
                transitionCoverageReadyKeys.add(node.key());
            }
        }
        for (int level = 1; level <= WorldEngine.MAX_LOD_LAYER; level++) {
            for (LodSectionCoordinate node : transitionBuildSections) {
                if (WorldEngine.getLevel(node.key()) != level || selectedLodSectionKeys.contains(node.key())) {
                    continue;
                }
                List<Long> children = childrenByParent.get(node.key());
                if (children != null && children.stream().allMatch(transitionCoverageReadyKeys::contains)) {
                    transitionCoverageReadyKeys.add(node.key());
                }
            }
        }

        for (Map.Entry<Long, List<Long>> entry : childrenByParent.entrySet()) {
            long parentKey = entry.getKey();
            if (!selectedLodSectionKeys.contains(parentKey)) {
                transitionParentKeys.add(parentKey);
            }
            int pendingChildren = 0;
            for (long childKey : entry.getValue()) {
                if (!transitionCoverageReadyKeys.contains(childKey)) {
                    pendingChildren++;
                }
            }
            if (pendingChildren != 0) {
                transitionPendingChildren.put(parentKey, pendingChildren);
            }
        }

        // Collapse already-prepared branches from fine to coarse before scheduling new work.
        for (int level = 1; level <= WorldEngine.MAX_LOD_LAYER; level++) {
            List<Long> readyParents = new ArrayList<>();
            for (long parentKey : transitionParentKeys) {
                if (WorldEngine.getLevel(parentKey) == level
                        && !transitionPendingChildren.containsKey(parentKey)
                        && transitionCoverageReadyKeys.contains(parentKey)) {
                    readyParents.add(parentKey);
                }
            }
            for (long parentKey : readyParents) {
                releaseTransitionParent(parentKey);
            }
        }
    }

    private static void markTransitionCoverageReady(long sectionKey) {
        if (!transitionCoverageReadyKeys.add(sectionKey)) {
            releaseTransitionParent(sectionKey);
            return;
        }
        int level = WorldEngine.getLevel(sectionKey);
        if (level < WorldEngine.MAX_LOD_LAYER) {
            int x = WorldEngine.getX(sectionKey);
            int y = WorldEngine.getY(sectionKey);
            int z = WorldEngine.getZ(sectionKey);
            long parentKey = WorldEngine.getWorldSectionId(level + 1,
                    Math.floorDiv(x, 2), Math.floorDiv(y, 2), Math.floorDiv(z, 2));
            Integer remaining = transitionPendingChildren.get(parentKey);
            if (remaining != null) {
                if (remaining <= 1) {
                    transitionPendingChildren.remove(parentKey);
                    releaseTransitionParent(parentKey);
                    markTransitionCoverageReady(parentKey);
                } else {
                    transitionPendingChildren.put(parentKey, remaining - 1);
                }
            }
        }
        releaseTransitionParent(sectionKey);
    }

    private static void releaseTransitionParent(long parentKey) {
        if (!transitionParentKeys.contains(parentKey)
                || transitionPendingChildren.containsKey(parentKey)
                || !transitionCoverageReadyKeys.contains(parentKey)) {
            return;
        }
        transitionParentKeys.remove(parentKey);
        if (lodMeshFingerprints.containsKey(parentKey)) {
            removeLodMesh(parentKey);
            lodMeshFingerprints.remove(parentKey);
            lodBranchHandoffs++;
        }
    }

    private static long parentKey(LodSectionCoordinate coordinate) {
        int parentLevel = WorldEngine.getLevel(coordinate.key()) + 1;
        return WorldEngine.getWorldSectionId(parentLevel,
                Math.floorDiv(coordinate.x(), 2),
                Math.floorDiv(coordinate.y(), 2),
                Math.floorDiv(coordinate.z(), 2));
    }

    private static Set<Long> sectionKeys(List<LodSectionCoordinate> sections) {
        Set<Long> keys = new HashSet<>(sections.size());
        for (LodSectionCoordinate section : sections) {
            keys.add(section.key());
        }
        return Set.copyOf(keys);
    }

    private static int countReadySelectedSections() {
        int ready = 0;
        for (LodSectionCoordinate section : selectedLodSections) {
            if (lodMeshFingerprints.containsKey(section.key())) {
                ready++;
            }
        }
        return ready;
    }

    private static String formatLevelHistogram(List<LodSectionCoordinate> sections) {
        int[] counts = new int[WorldEngine.MAX_LOD_LAYER + 1];
        for (LodSectionCoordinate section : sections) {
            counts[WorldEngine.getLevel(section.key())]++;
        }
        StringBuilder histogram = new StringBuilder();
        for (int level = 0; level < counts.length; level++) {
            if (level != 0) histogram.append(',');
            histogram.append('L').append(level).append('=').append(counts[level]);
        }
        return histogram.toString();
    }

    private static String lodModeDescription() {
        return forcedLodLevel < 0 ? "automatic" : "forced-L" + forcedLodLevel;
    }

    private static void retainMeshesInGrid(List<LodSectionCoordinate> sections) {
        Set<Long> retainedKeys = new HashSet<>(sections.size());
        for (LodSectionCoordinate section : sections) {
            retainedKeys.add(section.key());
        }

        List<Long> removedMeshes = new ArrayList<>();
        for (long key : lodMeshes.keySet()) {
            if (!retainedKeys.contains(key)) {
                removedMeshes.add(key);
            }
        }
        for (long key : removedMeshes) {
            removeLodMesh(key);
        }
        lodMeshFingerprints.keySet().retainAll(retainedKeys);
        transitionPendingChildren.clear();
        transitionParentKeys.clear();
        transitionCoverageReadyKeys.clear();
        transitionBuildSections = sections;
        nextLodSectionRefresh = sections.size();
    }

    private static int findIncompleteSelectedSection() {
        for (int index = 0; index < selectedLodSections.size(); index++) {
            if (!lodMeshFingerprints.containsKey(selectedLodSections.get(index).key())) {
                return index;
            }
        }
        return -1;
    }

    /**
     * A parent stays visible while the CPU prepares all of its selected descendants. The child
     * buffers are uploaded but skipped here, then become visible together when retainMeshesInGrid
     * removes the parent. This gives the hierarchy the same no-overdraw hand-off as Voxy's GL
     * traversal without leaving unfilled regions during asynchronous world updates.
     */
    private static boolean isCoveredByCoarserMesh(LodSectionCoordinate coordinate) {
        int lodLevel = WorldEngine.getLevel(coordinate.key());
        for (int parentLevel = lodLevel + 1; parentLevel <= WorldEngine.MAX_LOD_LAYER; parentLevel++) {
            int shift = parentLevel - lodLevel;
            int parentX = Math.floorDiv(coordinate.x(), 1 << shift);
            int parentY = Math.floorDiv(coordinate.y(), 1 << shift);
            int parentZ = Math.floorDiv(coordinate.z(), 1 << shift);
            long parentKey = WorldEngine.getWorldSectionId(parentLevel, parentX, parentY, parentZ);
            if (lodMeshes.containsKey(parentKey)) {
                return true;
            }
        }
        return false;
    }

    private static void releaseBuffer(GpuBuffer buffer, String label) {
        if (buffer == null) {
            return;
        }
        try {
            buffer.close();
            Logger.info("Blaze3D probe " + label + " buffer released after " + frameCount + " frames.");
        } catch (RuntimeException exception) {
            Logger.error("Failed to release the Blaze3D probe " + label + " buffer.", exception);
        }
    }

    private static int indexCount(int vertexCount) {
        return vertexCount / LOD_VERTICES_PER_QUAD * LOD_INDICES_PER_QUAD;
    }

    private static long geometryBytes(int opaqueQuads, int waterQuads) {
        return (long) (opaqueQuads + waterQuads) * LOD_VERTICES_PER_QUAD * LOD_VERTEX_FORMAT.getVertexSize();
    }

    private static long geometryBytes(LodSectionMesh mesh) {
        long opaqueBytes = mesh.opaqueVertexBuffer() == null ? 0L : mesh.opaqueVertexBuffer().size();
        long waterBytes = mesh.waterVertexBuffer() == null ? 0L : mesh.waterVertexBuffer().size();
        return opaqueBytes + waterBytes;
    }

    private static long readGeometryBudgetBytes() {
        long configuredMiB = Long.getLong(LOD_GEOMETRY_BUDGET_PROPERTY, 2048L);
        long clampedMiB = Math.max(256L, Math.min(8192L, configuredMiB));
        return clampedMiB * 1024L * 1024L;
    }

    private static TextureAtlas getBlockAtlas() {
        return (TextureAtlas) Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
    }

    private static int sectionSize(int lodLevel) {
        return SECTION_EDGE << lodLevel;
    }

    private static int voxelSize(int lodLevel) {
        return 1 << lodLevel;
    }

    /** Camera-relative boundary used only to invalidate meshes near Sodium's real render range. */
    private record VanillaRenderBoundary(int cameraBlockX,
                                         int cameraBlockY,
                                         int cameraBlockZ,
                                         float cameraFractionX,
                                          float cameraFractionY,
                                          float cameraFractionZ,
                                          int radius,
                                          VanillaBoundaryKey key) {
        private static VanillaRenderBoundary create(CameraTransform camera, int radius) {
            int blockX = (int) Math.floor(camera.x);
            int blockY = (int) Math.floor(camera.y);
            int blockZ = (int) Math.floor(camera.z);
            return new VanillaRenderBoundary(blockX, blockY, blockZ,
                    (float) (camera.x - blockX), (float) (camera.y - blockY), (float) (camera.z - blockZ), radius,
                    new VanillaBoundaryKey(Math.floorDiv(blockX, 16), Math.floorDiv(blockY, 16), Math.floorDiv(blockZ, 16),
                            radius));
        }

        private boolean intersectsSection(LodSectionCoordinate coordinate) {
            int sectionSize = sectionSize(WorldEngine.getLevel(coordinate.key()));
            int minimumX = coordinate.x() * sectionSize;
            int minimumY = coordinate.y() * sectionSize;
            int minimumZ = coordinate.z() * sectionSize;
            float closestX = clamp(cameraBlockX + cameraFractionX, minimumX, minimumX + sectionSize);
            float closestY = clamp(cameraBlockY + cameraFractionY, minimumY, minimumY + sectionSize);
            float closestZ = clamp(cameraBlockZ + cameraFractionZ, minimumZ, minimumZ + sectionSize);
            float dx = closestX - cameraBlockX - cameraFractionX;
            float dy = closestY - cameraBlockY - cameraFractionY;
            float dz = closestZ - cameraBlockZ - cameraFractionZ;
            return dx * dx + dz * dz < (float) radius * radius && Math.abs(dy) < radius;
        }
    }

    private record VanillaBoundaryKey(int chunkX, int chunkY, int chunkZ, int radius) {
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record LodGridKey(int lodLevel, int centerX, int centerZ, int minY, int maxY, int radius) {
    }

    private record LodRenderGrid(LodGridKey key, List<LodSectionCoordinate> sections) {
    }

    private record LodSectionCoordinate(long key, int x, int y, int z) {
    }

    private static final class LodSelectionTask {
        private final WorldEngine world;
        private final Matrix4f viewProjection;
        private final FrustumIntersection frustum;
        private final double cameraX;
        private final double cameraY;
        private final double cameraZ;
        private final int viewportWidth;
        private final int viewportHeight;
        private final VanillaRenderBoundary vanillaBoundary;
        private final float subdivisionArea;
        private final ArrayDeque<LodSectionCoordinate> pending;
        private final List<LodSectionCoordinate> selected;
        private final long startedFrame;
        private final String invalidationReason;
        private final double[] screenX = new double[8];
        private final double[] screenY = new double[8];
        private int processedNodes;
        private int frustumCulledNodes;
        private int screenTestedNodes;

        private LodSelectionTask(WorldEngine world,
                                 Matrix4f viewProjection,
                                 double cameraX,
                                 double cameraY,
                                 double cameraZ,
                                 int viewportWidth,
                                 int viewportHeight,
                                 VanillaRenderBoundary vanillaBoundary,
                                 float subdivisionArea,
                                 ArrayDeque<LodSectionCoordinate> pending,
                                 List<LodSectionCoordinate> selected,
                                 long startedFrame,
                                 String invalidationReason) {
            this.world = world;
            this.viewProjection = new Matrix4f(viewProjection);
            this.frustum = new FrustumIntersection(this.viewProjection, false);
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.cameraZ = cameraZ;
            this.viewportWidth = viewportWidth;
            this.viewportHeight = viewportHeight;
            this.vanillaBoundary = vanillaBoundary;
            this.subdivisionArea = subdivisionArea;
            this.pending = pending;
            this.selected = selected;
            this.startedFrame = startedFrame;
            this.invalidationReason = invalidationReason;
        }

        private WorldEngine world() { return world; }
        private Matrix4f viewProjection() { return viewProjection; }
        private FrustumIntersection frustum() { return frustum; }
        private double cameraX() { return cameraX; }
        private double cameraY() { return cameraY; }
        private double cameraZ() { return cameraZ; }
        private int viewportWidth() { return viewportWidth; }
        private int viewportHeight() { return viewportHeight; }
        private VanillaRenderBoundary vanillaBoundary() { return vanillaBoundary; }
        private float subdivisionArea() { return subdivisionArea; }
        private ArrayDeque<LodSectionCoordinate> pending() { return pending; }
        private List<LodSectionCoordinate> selected() { return selected; }
        private long startedFrame() { return startedFrame; }
        private String invalidationReason() { return invalidationReason; }
    }

    private static void releaseLodMeshBuffer(@Nullable GpuBuffer buffer, long sectionKey) {
        if (buffer == null) {
            return;
        }
        try {
            buffer.close();
        } catch (RuntimeException exception) {
            Logger.error("Failed to release Blaze3D LoD mesh " + WorldEngine.pprintPos(sectionKey) + ".", exception);
        }
    }

    private record QuadCounts(int opaque, int water) {
        private boolean isEmpty() {
            return opaque == 0 && water == 0;
        }
    }

    private record LodSectionMesh(LodSectionCoordinate coordinate,
                                  @Nullable GpuBuffer opaqueVertexBuffer,
                                  int opaqueVertexCount,
                                  @Nullable GpuBuffer waterVertexBuffer,
                                  int waterVertexCount) {
    }

    private static void addMarker(BufferBuilder builder, CameraTransform camera, TextureAtlasSprite dirtSprite) {
        Vector3fc forward = Minecraft.getInstance().gameRenderer.mainCamera().forwardVector();
        float horizontalLength = (float) Math.sqrt(forward.x() * forward.x() + forward.z() * forward.z());
        float forwardX = horizontalLength > 0.0001f ? forward.x() / horizontalLength : 1.0f;
        float forwardZ = horizontalLength > 0.0001f ? forward.z() / horizontalLength : 0.0f;
        int vanillaRenderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
        int markerDistance = Math.max(24, Math.min(64, Math.max(24, vanillaRenderDistance / 2)));

        float centerX = (float) (camera.x + forwardX * markerDistance);
        float centerY = (float) camera.y;
        float centerZ = (float) (camera.z + forwardZ * markerDistance);
        float halfSize = LOD_MARKER_SIZE * 0.5f;

        int markerLodLevel = Math.max(0, forcedLodLevel);
        int sectionSize = sectionSize(markerLodLevel);
        int lodX = Math.floorDiv((int) Math.floor(centerX), sectionSize);
        int lodY = Math.floorDiv((int) Math.floor(centerY), sectionSize);
        int lodZ = Math.floorDiv((int) Math.floor(centerZ), sectionSize);

        float minX = centerX - halfSize;
        float minY = centerY - halfSize;
        float minZ = centerZ - halfSize;
        float maxX = centerX + halfSize;
        float maxY = centerY + halfSize;
        float maxZ = centerZ + halfSize;

        if (frameCount == 0) {
            Logger.info("Blaze3D probe marker: camera=" + camera.x + "," + camera.y + "," + camera.z
                    + ", level=" + markerLodLevel + " section=" + lodX + "," + lodY + "," + lodZ
                    + ", center=" + centerX + "," + centerY + "," + centerZ
                    + ", size=" + (halfSize * 2.0f) + ", distance=" + markerDistance);
        }

        addTexturedFace(builder, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, dirtSprite, 255, 255, 255);
        addTexturedFace(builder, maxX, minY, minZ, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, dirtSprite, 215, 215, 215);
        addTexturedFace(builder, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, dirtSprite, 190, 190, 190);
        addTexturedFace(builder, maxX, minY, maxZ, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, dirtSprite, 225, 225, 225);
        addTexturedFace(builder, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, minX, maxY, minZ, dirtSprite, 255, 255, 255);
        addTexturedFace(builder, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, dirtSprite, 115, 115, 115);
    }

    private static void addTexturedFace(BufferBuilder builder,
                                        float x1, float y1, float z1,
                                        float x2, float y2, float z2,
                                        float x3, float y3, float z3,
                                        float x4, float y4, float z4,
                                        TextureAtlasSprite sprite,
                                        int red, int green, int blue) {
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        addTexturedVertex(builder, x1, y1, z1, u0, v1, red, green, blue);
        addTexturedVertex(builder, x2, y2, z2, u1, v1, red, green, blue);
        addTexturedVertex(builder, x3, y3, z3, u1, v0, red, green, blue);
        addTexturedVertex(builder, x1, y1, z1, u0, v1, red, green, blue);
        addTexturedVertex(builder, x3, y3, z3, u1, v0, red, green, blue);
        addTexturedVertex(builder, x4, y4, z4, u0, v0, red, green, blue);
    }

    private static void addTexturedVertex(BufferBuilder builder, float x, float y, float z, float u, float v, int red, int green, int blue) {
        builder.addVertex(x, y, z).setUv(u, v).setColor(red, green, blue, 255);
    }
}
