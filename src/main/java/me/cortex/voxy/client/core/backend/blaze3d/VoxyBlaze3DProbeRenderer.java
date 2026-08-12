package me.cortex.voxy.client.core.backend.blaze3d;

import com.mojang.blaze3d.PrimitiveTopology;
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
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
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
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
    private static final int LOD_INITIAL_REFRESH_INTERVAL_FRAMES = 4;
    private static final int LOD_STEADY_REFRESH_INTERVAL_FRAMES = 120;
    private static final int LOD_SELECTION_INTERVAL_FRAMES = 20;
    private static final int LOD_SECTIONS_PER_REFRESH = 1;
    private static final double LOD_SELECTION_MOVEMENT_BLOCKS = 32.0;
    private static final int MAX_LOD_QUAD_COUNT_PER_SECTION = 65536;
    private static final int MATERIAL_LOG_LIMIT = 12;
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
            "blaze3d_lod_probe_water", CompareOp.GREATER_THAN, false, true);
    private static final RenderPipeline LOD_OVERLAY_PIPELINE = createTexturedTerrainPipeline(
            "blaze3d_lod_probe_terrain_overlay", CompareOp.ALWAYS_PASS, false, false);
    private static final RenderPipeline LOD_WATER_OVERLAY_PIPELINE = createTexturedTerrainPipeline(
            "blaze3d_lod_probe_water_overlay", CompareOp.ALWAYS_PASS, false, true);

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

    private static GpuBuffer markerVertexBuffer;
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
    private static int nextLodSectionRefresh;
    private static long lastLodSelectionFrame = Long.MIN_VALUE;
    private static double lastLodSelectionX = Double.NaN;
    private static double lastLodSelectionY = Double.NaN;
    private static double lastLodSelectionZ = Double.NaN;
    private static float lastSubdivisionSize = Float.NaN;
    private static int lastVanillaRenderDistance = -1;
    private static VanillaRenderBoundary activeVanillaBoundary;
    private static VanillaBoundaryKey lastVanillaBoundaryKey;
    private static int loggedMaterialDefinitions;
    private static boolean lodGridInvalidated = true;
    private static boolean renderLodAboveTerrain;
    private static volatile boolean testCubeVisible;
    private static volatile boolean fogEnabled = true;
    // Lowest detail layer allowed by the dynamic hierarchy; zero enables full refinement near the camera.
    private static volatile int lodQualityLevel = 0;

    private VoxyBlaze3DProbeRenderer() {
    }

    public static boolean toggleTestCube() {
        testCubeVisible = !testCubeVisible;
        Logger.info("Blaze3D test cube " + (testCubeVisible ? "enabled" : "disabled") + ".");
        return testCubeVisible;
    }

    public static boolean toggleFog() {
        fogEnabled = !fogEnabled;
        Logger.info("Blaze3D Voxy fog " + (fogEnabled ? "enabled" : "disabled") + ".");
        return fogEnabled;
    }

    public static boolean isFogEnabled() {
        return fogEnabled;
    }

    public static int setLodQualityLevel(int requestedLevel) {
        int clampedLevel = Math.max(0, Math.min(WorldEngine.MAX_LOD_LAYER, requestedLevel));
        if (lodQualityLevel == clampedLevel) {
            return clampedLevel;
        }
        lodQualityLevel = clampedLevel;
        lastLodRefreshFrame = Long.MIN_VALUE;
        lodGridInvalidated = true;
        Logger.info("Blaze3D LoD minimum level changed to " + clampedLevel
                + " (dynamic refinement stops at one voxel per " + voxelSize(clampedLevel) + " world blocks).");
        return clampedLevel;
    }

    public static boolean toggleLodOverlay() {
        renderLodAboveTerrain = !renderLodAboveTerrain;
        Logger.info("Blaze3D LoD terrain overlay " + (renderLodAboveTerrain ? "enabled" : "disabled") + ".");
        return renderLodAboveTerrain;
    }

    public static void beginVisibleVanillaSectionCollection() {
        collectedVisibleVanillaSections.clear();
    }

    public static void recordVisibleVanillaSection(int sectionX, int sectionY, int sectionZ) {
        collectedVisibleVanillaSections.add(SectionPos.asLong(sectionX, sectionY, sectionZ));
    }

    public static void render(ChunkRenderMatrices matrices, GpuTextureView colorTarget, GpuTextureView depthTarget, CameraTransform camera) {
        if (failed) {
            return;
        }

        try {
            RenderSystem.assertOnRenderThread();
            initialize();
            commitVisibleVanillaSectionMask();

            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            if (testCubeVisible) {
                uploadMarker(encoder, camera);
            }
            refreshLodMeshes(encoder, matrices, camera, colorTarget.getWidth(0), colorTarget.getHeight(0));

            try (RenderPass pass = encoder.createRenderPass(
                    () -> "Voxy Blaze3D probe",
                    colorTarget,
                    Optional.empty(),
                    depthTarget,
                    OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                Matrix4f cameraRelativeModelView = new Matrix4f(matrices.modelView())
                        .translate((float) -camera.x, (float) -camera.y, (float) -camera.z);
                pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(cameraRelativeModelView));
                if (testCubeVisible && markerVertexBuffer != null) {
                    TextureAtlas blockAtlas = getBlockAtlas();
                    pass.setPipeline(MARKER_PIPELINE);
                    pass.bindTexture("Sampler0", blockAtlas.getTextureView(), blockAtlas.getSampler());
                    pass.setVertexBuffer(0, markerVertexBuffer.slice());
                    pass.draw(MARKER_VERTEX_COUNT, 1, 0, 0);
                }
                if (!lodMeshes.isEmpty()) {
                    TextureAtlas blockAtlas = getBlockAtlas();
                    pass.setPipeline(renderLodAboveTerrain ? LOD_OVERLAY_PIPELINE : LOD_PIPELINE);
                    pass.setUniform("Fog", RenderSystem.getShaderFog());
                    pass.bindTexture("Sampler0", blockAtlas.getTextureView(), blockAtlas.getSampler());
                    pass.bindTexture("Sampler2", Minecraft.getInstance().gameRenderer.levelLightmap(),
                            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                    for (LodSectionMesh mesh : lodMeshes.values()) {
                        if (mesh.opaqueVertexCount() != 0) {
                            pass.setVertexBuffer(0, mesh.opaqueVertexBuffer().slice());
                            pass.draw(mesh.opaqueVertexCount(), 1, 0, 0);
                        }
                    }

                    List<LodSectionMesh> waterMeshes = lodMeshes.values().stream()
                            .filter(mesh -> mesh.waterVertexCount() != 0)
                            .sorted(Comparator.comparingDouble((LodSectionMesh mesh) -> distanceSquaredToCamera(mesh.coordinate(), camera)).reversed())
                            .toList();
                    if (!waterMeshes.isEmpty()) {
                        pass.setPipeline(renderLodAboveTerrain ? LOD_WATER_OVERLAY_PIPELINE : LOD_WATER_PIPELINE);
                        pass.setUniform("Fog", RenderSystem.getShaderFog());
                        pass.bindTexture("Sampler0", blockAtlas.getTextureView(), blockAtlas.getSampler());
                        pass.bindTexture("Sampler2", Minecraft.getInstance().gameRenderer.levelLightmap(),
                                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                        for (LodSectionMesh mesh : waterMeshes) {
                            pass.setVertexBuffer(0, mesh.waterVertexBuffer().slice());
                            pass.draw(mesh.waterVertexCount(), 1, 0, 0);
                        }
                    }
                }
            }

            frameCount++;
            if (frameCount == 1 || frameCount % LOG_INTERVAL_FRAMES == 0) {
                Logger.info("Blaze3D probe frame " + frameCount
                        + " submitted; color=" + colorTarget.getWidth(0) + "x" + colorTarget.getHeight(0)
                        + ", depth=" + depthTarget.getWidth(0) + "x" + depthTarget.getHeight(0));
            }
        } catch (RuntimeException exception) {
            failed = true;
            Logger.error("Blaze3D probe failed on frame " + (frameCount + 1)
                    + "; disabling the Vulkan probe for this session.", exception);
        }
    }

    public static void shutdown() {
        releaseBuffer(markerVertexBuffer, "marker");
        markerVertexBuffer = null;
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
        nextLodSectionRefresh = 0;
        lastLodSelectionFrame = Long.MIN_VALUE;
        lastLodSelectionX = Double.NaN;
        lastLodSelectionY = Double.NaN;
        lastLodSelectionZ = Double.NaN;
        lastSubdivisionSize = Float.NaN;
        lastVanillaRenderDistance = -1;
        activeVanillaBoundary = null;
        lastVanillaBoundaryKey = null;
        loggedMaterialDefinitions = 0;
        lodGridInvalidated = true;
        renderLodAboveTerrain = false;
    }

    private static void initialize() {
        if (initialized) {
            return;
        }

        var deviceInfo = RenderSystem.getDevice().getDeviceInfo();
        Logger.info("Initializing Blaze3D probe: backend=" + deviceInfo.backendName()
                + ", device=" + deviceInfo.name()
                + ", vendor=" + deviceInfo.vendorName()
                + ", multiDrawIndirect=" + deviceInfo.features().multiDrawIndirect()
                + ", persistentMapping=" + deviceInfo.features().persistentMapping());
        Logger.info("Blaze3D LoD features: testCube=off, custom-model-occlusion=disabled, "
                + "lighting=live-lightmap+directional, fog=voxy-environmental, vanilla-mask=section-visibility.");
        initialized = true;
    }

    private static void commitVisibleVanillaSectionMask() {
        if (visibleVanillaSections.equals(collectedVisibleVanillaSections)) {
            return;
        }
        visibleVanillaSections.clear();
        visibleVanillaSections.addAll(collectedVisibleVanillaSections);
        visibleVanillaMaskRevision++;
        // Rebuild incrementally using the same pacing as ordinary LoD changes.
        lastLodRefreshFrame = Long.MIN_VALUE;
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
            }
            lodRenderGrid = new LodRenderGrid(requestedGrid, findStoredSections(world, requestedGrid));
            selectedLodSections = List.of();
            nextLodSectionRefresh = 0;
            lastLodSelectionFrame = Long.MIN_VALUE;
            lodGridInvalidated = false;
            lastLodRefreshFrame = Long.MIN_VALUE;
            Logger.info("Blaze3D LoD grid rebuilt: level=" + requestedGrid.lodLevel()
                    + ", center=" + requestedGrid.centerX() + "," + requestedGrid.centerZ()
                    + ", radius=" + requestedGrid.radius() + " sections"
                    + ", vertical=" + requestedGrid.minY() + ".." + requestedGrid.maxY()
                    + ", storedSections=" + lodRenderGrid.sections().size()
                    + ", distance=" + Math.round(VoxyConfig.CONFIG.sectionRenderDistance * 512.0f) + " blocks.");
        }

        int vanillaRenderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
        activeVanillaBoundary = VanillaRenderBoundary.create(camera, vanillaRenderDistance);
        updateLodSelection(world, matrices, camera, viewportWidth, viewportHeight, activeVanillaBoundary);

        boolean initialPopulation = nextLodSectionRefresh < selectedLodSections.size();
        int refreshInterval = initialPopulation ? LOD_INITIAL_REFRESH_INTERVAL_FRAMES : LOD_STEADY_REFRESH_INTERVAL_FRAMES;
        if (lastLodRefreshFrame != Long.MIN_VALUE && frameCount - lastLodRefreshFrame < refreshInterval) {
            return;
        }
        lastLodRefreshFrame = frameCount;

        if (!initialPopulation) {
            nextLodSectionRefresh = 0;
        }
        int end = Math.min(nextLodSectionRefresh + LOD_SECTIONS_PER_REFRESH, selectedLodSections.size());
        TextureAtlasSprite fallbackSprite = getBlockAtlas().getSprite(DIRT_SPRITE);
        for (int index = nextLodSectionRefresh; index < end; index++) {
            rebuildLodSection(world, selectedLodSections.get(index), fallbackSprite);
        }
        nextLodSectionRefresh = end;
        if (initialPopulation && nextLodSectionRefresh == selectedLodSections.size()) {
            Logger.info("Blaze3D LoD grid population complete: meshes=" + lodMeshes.size()
                    + "/" + selectedLodSections.size() + ", minimumLevel=" + lodQualityLevel + ".");
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
        boolean vanillaBoundaryChanged = !vanillaBoundary.key().equals(lastVanillaBoundaryKey);
        if (lastLodSelectionFrame != Long.MIN_VALUE) {
            double dx = camera.x - lastLodSelectionX;
            double dy = camera.y - lastLodSelectionY;
            double dz = camera.z - lastLodSelectionZ;
            boolean cameraMoved = dx * dx + dy * dy + dz * dz >= LOD_SELECTION_MOVEMENT_BLOCKS * LOD_SELECTION_MOVEMENT_BLOCKS;
            boolean settingsChanged = lastSubdivisionSize != VoxyConfig.CONFIG.subDivisionSize
                    || lastVanillaRenderDistance != vanillaRenderDistance
                    || vanillaBoundaryChanged;
            if (!cameraMoved && !settingsChanged) {
                return;
            }
            if (!settingsChanged && frameCount - lastLodSelectionFrame < LOD_SELECTION_INTERVAL_FRAMES) {
                return;
            }
        }
        lastLodSelectionFrame = frameCount;
        lastLodSelectionX = camera.x;
        lastLodSelectionY = camera.y;
        lastLodSelectionZ = camera.z;
        lastSubdivisionSize = VoxyConfig.CONFIG.subDivisionSize;
        lastVanillaRenderDistance = vanillaRenderDistance;
        lastVanillaBoundaryKey = vanillaBoundary.key();

        List<LodSectionCoordinate> selected = new ArrayList<>();
        float subdivisionArea = VoxyConfig.CONFIG.subDivisionSize * VoxyConfig.CONFIG.subDivisionSize;
        for (LodSectionCoordinate root : lodRenderGrid.sections()) {
            selectLodSection(world, root, matrices, camera, viewportWidth, viewportHeight, subdivisionArea, vanillaBoundary, selected);
        }
        selected.sort(Comparator.comparingDouble(section -> distanceSquaredToCamera(section, camera)));
        if (vanillaBoundaryChanged || !selected.equals(selectedLodSections)) {
            selectedLodSections = List.copyOf(selected);
            retainMeshesInGrid(selectedLodSections);
            nextLodSectionRefresh = 0;
            lastLodRefreshFrame = Long.MIN_VALUE;
            Logger.info("Blaze3D dynamic LoD selection changed: sections=" + selectedLodSections.size()
                    + ", minimumLevel=" + lodQualityLevel
                    + ", subdivision=" + Math.round(VoxyConfig.CONFIG.subDivisionSize) + " px^2.");
        }
    }

    private static void selectLodSection(WorldEngine world,
                                         LodSectionCoordinate coordinate,
                                         ChunkRenderMatrices matrices,
                                         CameraTransform camera,
                                         int viewportWidth,
                                         int viewportHeight,
                                         float subdivisionArea,
                                         VanillaRenderBoundary vanillaBoundary,
                                         List<LodSectionCoordinate> selected) {
        int lodLevel = WorldEngine.getLevel(coordinate.key());
        if (vanillaBoundary.containsSection(coordinate)) {
            return;
        }
        if (lodLevel == 0) {
            // The final one-block layer can be clipped exactly against vanilla's render volume.
            selected.add(coordinate);
            return;
        }
        WorldSection section = world.acquireIfExists(coordinate.key());
        if (section == null) {
            return;
        }

        byte children;
        try {
            children = section.getNonEmptyChildren();
        } finally {
            section.release();
        }

        boolean crossesVanillaBoundary = vanillaBoundary.intersectsSection(coordinate);
        if ((lodLevel <= lodQualityLevel && !crossesVanillaBoundary) || children == 0
                || (!crossesVanillaBoundary && !shouldSubdivide(coordinate, matrices, camera, viewportWidth, viewportHeight, subdivisionArea))) {
            selected.add(coordinate);
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
            if (!crossesVanillaBoundary) {
                selected.add(coordinate);
            }
            return;
        }
        for (LodSectionCoordinate child : childSections) {
            selectLodSection(world, child, matrices, camera, viewportWidth, viewportHeight, subdivisionArea, vanillaBoundary, selected);
        }
    }

    private static boolean shouldSubdivide(LodSectionCoordinate coordinate,
                                           ChunkRenderMatrices matrices,
                                           CameraTransform camera,
                                           int viewportWidth,
                                           int viewportHeight,
                                           float subdivisionArea) {
        int sectionSize = sectionSize(WorldEngine.getLevel(coordinate.key()));
        double centerX = (coordinate.x() + 0.5) * sectionSize;
        double centerY = (coordinate.y() + 0.5) * sectionSize;
        double centerZ = (coordinate.z() + 0.5) * sectionSize;
        double distance = Math.max(1.0, Math.sqrt((centerX - camera.x) * (centerX - camera.x)
                + (centerY - camera.y) * (centerY - camera.y)
                + (centerZ - camera.z) * (centerZ - camera.z)) - sectionSize * 0.5);
        double projectedWidth = sectionSize * Math.abs(matrices.projection().m00()) * viewportWidth / (2.0 * distance);
        double projectedHeight = sectionSize * Math.abs(matrices.projection().m11()) * viewportHeight / (2.0 * distance);
        return projectedWidth * projectedHeight > subdivisionArea;
    }

    private static double distanceSquaredToCamera(LodSectionCoordinate coordinate, CameraTransform camera) {
        int sectionSize = sectionSize(WorldEngine.getLevel(coordinate.key()));
        double dx = (coordinate.x() + 0.5) * sectionSize - camera.x;
        double dy = (coordinate.y() + 0.5) * sectionSize - camera.y;
        double dz = (coordinate.z() + 0.5) * sectionSize - camera.z;
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
        int lodLevel = WorldEngine.getLevel(coordinate.key());
        long fingerprint = getNeighborhoodFingerprint(world, lodLevel, coordinate.x(), coordinate.y(), coordinate.z());
        VanillaRenderBoundary vanillaBoundary = activeVanillaBoundary;
        if (fingerprint != Long.MIN_VALUE && voxelSize(lodLevel) == 1 && vanillaBoundary != null
                && vanillaBoundary.intersectsSection(coordinate)) {
            fingerprint = mixRevision(fingerprint, vanillaBoundary.key().hashCode());
            fingerprint = mixRevision(fingerprint, visibleVanillaMaskRevision);
        }
        if (fingerprint == Long.MIN_VALUE) {
            removeLodMesh(coordinate.key());
            lodMeshFingerprints.remove(coordinate.key());
            return;
        }
        if (Long.valueOf(fingerprint).equals(lodMeshFingerprints.get(coordinate.key()))) {
            return;
        }

        WorldSection section = world.acquireIfExists(coordinate.key());
        if (section == null) {
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
            removeLodMesh(coordinate.key());
            lodMeshFingerprints.put(coordinate.key(), fingerprint);
            return;
        }

        GpuBuffer opaqueBuffer = buildLodMesh(coordinate, neighborhood, world.getMapper(), fallbackSprite,
                voxelSize(lodLevel), vanillaBoundary, false, quadCounts.opaque());
        GpuBuffer waterBuffer = buildLodMesh(coordinate, neighborhood, world.getMapper(), fallbackSprite,
                voxelSize(lodLevel), vanillaBoundary, true, quadCounts.water());
        replaceLodMesh(coordinate, opaqueBuffer, quadCounts.opaque() * 6, waterBuffer, quadCounts.water() * 6);
        lodMeshFingerprints.put(coordinate.key(), fingerprint);
    }

    @Nullable
    private static GpuBuffer buildLodMesh(LodSectionCoordinate coordinate,
                                          SectionNeighborhood neighborhood,
                                          Mapper mapper,
                                          TextureAtlasSprite fallbackSprite,
                                          int voxelSize,
                                          VanillaRenderBoundary vanillaBoundary,
                                          boolean waterOnly,
                                          int quadCount) {
        if (quadCount == 0) {
            return null;
        }
        int vertexCount = quadCount * 6;
        int bufferSize = vertexCount * LOD_VERTEX_FORMAT.getVertexSize();
        try (ByteBufferBuilder byteBuffer = ByteBufferBuilder.exactlySized(bufferSize)) {
            BufferBuilder builder = new BufferBuilder(byteBuffer, PrimitiveTopology.TRIANGLES, LOD_VERTEX_FORMAT);
            int emittedQuads = emitModelQuads(builder, neighborhood, coordinate.x(), coordinate.y(), coordinate.z(), voxelSize,
                    quadCount, mapper, fallbackSprite, coordinate, vanillaBoundary, waterOnly);
            if (emittedQuads != quadCount) {
                throw new IllegalStateException("LoD " + (waterOnly ? "water" : "opaque")
                        + " mesh quad count changed while building: expected " + quadCount + ", got " + emittedQuads);
            }
            try (MeshData mesh = builder.buildOrThrow()) {
                return RenderSystem.getDevice().createBuffer(
                        () -> "Voxy Blaze3D LoD " + (waterOnly ? "water " : "opaque ") + WorldEngine.pprintPos(coordinate.key()),
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
                    if (shouldHideLodCell(coordinate, voxelSize, x, y, z, vanillaBoundary)) {
                        continue;
                    }
                    long state = neighborhood.center()[WorldSection.getIndex(x, y, z)];
                    if (Mapper.isAir(state)) {
                        continue;
                    }
                    BlockRenderDefinition definition = getBlockRenderDefinition(mapper, Mapper.getBlockId(state), fallbackSprite);
                    opaqueQuads += countQuads(definition.unculledQuads(), false);
                    waterQuads += countQuads(definition.unculledQuads(), true);
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, x, y - 1, z)) {
                        opaqueQuads += countQuads(definition.culledQuads(0), false);
                        waterQuads += countQuads(definition.culledQuads(0), true);
                    }
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, x, y + 1, z)) {
                        opaqueQuads += countQuads(definition.culledQuads(1), false);
                        waterQuads += countQuads(definition.culledQuads(1), true);
                    }
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, x - 1, y, z)) {
                        opaqueQuads += countQuads(definition.culledQuads(2), false);
                        waterQuads += countQuads(definition.culledQuads(2), true);
                    }
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, x + 1, y, z)) {
                        opaqueQuads += countQuads(definition.culledQuads(3), false);
                        waterQuads += countQuads(definition.culledQuads(3), true);
                    }
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, x, y, z - 1)) {
                        opaqueQuads += countQuads(definition.culledQuads(4), false);
                        waterQuads += countQuads(definition.culledQuads(4), true);
                    }
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, x, y, z + 1)) {
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

    private static int countQuads(List<ModelQuad> quads, boolean waterOnly) {
        int count = 0;
        for (ModelQuad quad : quads) {
            if (quad.fluidTint() == waterOnly) {
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
                                      boolean waterOnly) {
        int quads = 0;
        int sectionSize = SECTION_EDGE * voxelSize;
        float originX = sectionX * (float) sectionSize;
        float originY = sectionY * (float) sectionSize;
        float originZ = sectionZ * (float) sectionSize;
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    if (shouldHideLodCell(coordinate, voxelSize, x, y, z, vanillaBoundary)) {
                        continue;
                    }
                    long state = neighborhood.center()[WorldSection.getIndex(x, y, z)];
                    if (Mapper.isAir(state)) {
                        continue;
                    }

                    float blockX = originX + x * voxelSize;
                    float blockY = originY + y * voxelSize;
                    float blockZ = originZ + z * voxelSize;
                    BlockRenderDefinition definition = getBlockRenderDefinition(mapper, Mapper.getBlockId(state), fallbackSprite);
                    quads = emitQuads(builder, definition.unculledQuads(), neighborhood, x, y, z, state, mapper, blockX, blockY, blockZ, voxelSize,
                            brightestLight(neighborhood, x, y, z, state), waterOnly, quads, maxQuads);
                    if (quads == maxQuads) return quads;
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, x, y - 1, z)) quads = emitQuads(builder, definition.culledQuads(0), neighborhood, x, y, z, state, mapper, blockX, blockY, blockZ, voxelSize,
                            visibleFaceLight(state, getState(neighborhood, x, y - 1, z)), waterOnly, quads, maxQuads);
                    if (quads == maxQuads) return quads;
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, x, y + 1, z)) quads = emitQuads(builder, definition.culledQuads(1), neighborhood, x, y, z, state, mapper, blockX, blockY, blockZ, voxelSize,
                            visibleFaceLight(state, getState(neighborhood, x, y + 1, z)), waterOnly, quads, maxQuads);
                    if (quads == maxQuads) return quads;
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, x - 1, y, z)) quads = emitQuads(builder, definition.culledQuads(2), neighborhood, x, y, z, state, mapper, blockX, blockY, blockZ, voxelSize,
                            visibleFaceLight(state, getState(neighborhood, x - 1, y, z)), waterOnly, quads, maxQuads);
                    if (quads == maxQuads) return quads;
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, x + 1, y, z)) quads = emitQuads(builder, definition.culledQuads(3), neighborhood, x, y, z, state, mapper, blockX, blockY, blockZ, voxelSize,
                            visibleFaceLight(state, getState(neighborhood, x + 1, y, z)), waterOnly, quads, maxQuads);
                    if (quads == maxQuads) return quads;
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, x, y, z - 1)) quads = emitQuads(builder, definition.culledQuads(4), neighborhood, x, y, z, state, mapper, blockX, blockY, blockZ, voxelSize,
                            visibleFaceLight(state, getState(neighborhood, x, y, z - 1)), waterOnly, quads, maxQuads);
                    if (quads == maxQuads) return quads;
                    if (shouldEmitCulledFace(neighborhood, mapper, definition, fallbackSprite, x, y, z + 1)) quads = emitQuads(builder, definition.culledQuads(5), neighborhood, x, y, z, state, mapper, blockX, blockY, blockZ, voxelSize,
                            visibleFaceLight(state, getState(neighborhood, x, y, z + 1)), waterOnly, quads, maxQuads);
                    if (quads == maxQuads) return quads;
                }
            }
        }
        return quads;
    }

    private static boolean shouldHideLodCell(LodSectionCoordinate coordinate,
                                             int voxelSize,
                                             int cellX,
                                             int cellY,
                                             int cellZ,
                                             VanillaRenderBoundary vanillaBoundary) {
        if (vanillaBoundary == null || voxelSize != 1) {
            return false;
        }
        int sectionSize = SECTION_EDGE * voxelSize;
        int worldX = coordinate.x() * sectionSize + cellX * voxelSize;
        int worldY = coordinate.y() * sectionSize + cellY * voxelSize;
        int worldZ = coordinate.z() * sectionSize + cellZ * voxelSize;
        if (!visibleVanillaSections.isEmpty()) {
            return visibleVanillaSections.contains(SectionPos.asLong(
                    Math.floorDiv(worldX, 16), Math.floorDiv(worldY, 16), Math.floorDiv(worldZ, 16)));
        }
        return vanillaBoundary.containsChunk(Math.floorDiv(worldX, 16), Math.floorDiv(worldZ, 16));
    }

    private static boolean shouldEmitCulledFace(SectionNeighborhood neighborhood,
                                                Mapper mapper,
                                                BlockRenderDefinition current,
                                                TextureAtlasSprite fallbackSprite,
                                                int x,
                                                int y,
                                                int z) {
        long neighborState = getState(neighborhood, x, y, z);
        if (Mapper.isAir(neighborState)) {
            return true;
        }
        BlockRenderDefinition neighbor = getBlockRenderDefinition(mapper, Mapper.getBlockId(neighborState), fallbackSprite);
        // Fluids occupy a separate surface within a block. They do not hide solid faces, but
        // adjacent fluid cells must still cull each other or every depth layer becomes visible.
        if (current.hasFluid() && neighbor.hasFluid()) {
            return false;
        }
        // Only a model that is a complete collision cube may hide an adjoining face. Decorations,
        // layers and any other custom geometry therefore leave the neighboring block visible.
        return !neighbor.occludesNeighbors();
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
                                 boolean waterOnly,
                                 int emitted,
                                 int maxQuads) {
        for (ModelQuad quad : quads) {
            if (quad.fluidTint() != waterOnly) {
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
        return new ModelQuad(positions, uvs, quad.materialInfo().isTinted(), quad.materialInfo().tintIndex(), quad.direction(), false,
                quad.materialInfo().shade());
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
        return new ModelQuad(positions, uvs, true, 0, Direction.UP, true, false);
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
                sprite.getU(1), sprite.getV(0), sprite.getU(0), sprite.getV(0)}, tinted, tintIndex, direction, tinted, false);
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
        addModelVertex(builder, quad, 0, blockX, blockY, blockZ, voxelSize, red, green, blue, alpha, packedLight, cornerOcclusion[0]);
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
        lodMeshes.put(coordinate.key(), new LodSectionMesh(coordinate, opaqueBuffer, opaqueVertexCount, waterBuffer, waterVertexCount));
    }

    private static void removeLodMesh(long sectionKey) {
        LodSectionMesh previous = lodMeshes.remove(sectionKey);
        if (previous == null) {
            return;
        }
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
        lodMeshFingerprints.clear();
        if (meshCount != 0) {
            Logger.info("Blaze3D probe released " + meshCount + " cached LoD meshes after " + frameCount + " frames.");
        }
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
    }

    private static void releaseBuffer(GpuBuffer buffer, String label) {
        if (buffer == null) {
            return;
        }
        try {
            buffer.close();
            Logger.info("Blaze3D probe " + label + " vertex buffer released after " + frameCount + " frames.");
        } catch (RuntimeException exception) {
            Logger.error("Failed to release the Blaze3D probe " + label + " vertex buffer.", exception);
        }
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

    /**
     * Mirrors the fallback circular bound used by Voxy's OpenGL BoundRenderer.
     * The OpenGL backend masks this on the GPU; the Blaze3D MVP clips level-zero
     * cells while its shader path is still being brought up.
     */
    private record VanillaRenderBoundary(int cameraBlockX,
                                         int cameraBlockZ,
                                         float cameraFractionX,
                                         float cameraFractionZ,
                                         int radius,
                                         VanillaBoundaryKey key) {
        private static VanillaRenderBoundary create(CameraTransform camera, int radius) {
            int blockX = (int) Math.floor(camera.x);
            int blockZ = (int) Math.floor(camera.z);
            return new VanillaRenderBoundary(blockX, blockZ,
                    (float) (camera.x - blockX), (float) (camera.z - blockZ), radius,
                    new VanillaBoundaryKey(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16), radius));
        }

        private boolean containsChunk(int chunkX, int chunkZ) {
            int relativeX = chunkX * 16 - cameraBlockX;
            int relativeZ = chunkZ * 16 - cameraBlockZ;
            float dx = nearestToZero(relativeX - 1, relativeX + 17) - cameraFractionX;
            float dz = nearestToZero(relativeZ - 1, relativeZ + 17) - cameraFractionZ;
            return dx * dx + dz * dz < (float) radius * radius;
        }

        private boolean containsSection(LodSectionCoordinate coordinate) {
            int sectionSize = sectionSize(WorldEngine.getLevel(coordinate.key()));
            int minimumChunkX = Math.floorDiv(coordinate.x() * sectionSize, 16);
            int minimumChunkZ = Math.floorDiv(coordinate.z() * sectionSize, 16);
            int maximumChunkX = Math.floorDiv(coordinate.x() * sectionSize + sectionSize - 1, 16);
            int maximumChunkZ = Math.floorDiv(coordinate.z() * sectionSize + sectionSize - 1, 16);
            return containsChunk(minimumChunkX, minimumChunkZ)
                    && containsChunk(maximumChunkX, minimumChunkZ)
                    && containsChunk(minimumChunkX, maximumChunkZ)
                    && containsChunk(maximumChunkX, maximumChunkZ);
        }

        private boolean intersectsSection(LodSectionCoordinate coordinate) {
            int sectionSize = sectionSize(WorldEngine.getLevel(coordinate.key()));
            int minimumChunkX = Math.floorDiv(coordinate.x() * sectionSize, 16);
            int minimumChunkZ = Math.floorDiv(coordinate.z() * sectionSize, 16);
            int maximumChunkX = Math.floorDiv(coordinate.x() * sectionSize + sectionSize - 1, 16);
            int maximumChunkZ = Math.floorDiv(coordinate.z() * sectionSize + sectionSize - 1, 16);
            int candidateChunkX = clamp(Math.floorDiv(cameraBlockX, 16), minimumChunkX, maximumChunkX);
            int candidateChunkZ = clamp(Math.floorDiv(cameraBlockZ, 16), minimumChunkZ, maximumChunkZ);
            return containsChunk(candidateChunkX, candidateChunkZ);
        }

        private static int nearestToZero(int minimum, int maximum) {
            if (minimum > 0) {
                return minimum;
            }
            if (maximum < 0) {
                return maximum;
            }
            return 0;
        }
    }

    private record VanillaBoundaryKey(int chunkX, int chunkZ, int radius) {
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record LodGridKey(int lodLevel, int centerX, int centerZ, int minY, int maxY, int radius) {
    }

    private record LodRenderGrid(LodGridKey key, List<LodSectionCoordinate> sections) {
    }

    private record LodSectionCoordinate(long key, int x, int y, int z) {
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

        int sectionSize = sectionSize(lodQualityLevel);
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
                    + ", level=" + lodQualityLevel + " section=" + lodX + "," + lodY + "," + lodZ
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
