package me.cortex.voxy.client.iris;

import com.google.common.collect.ImmutableSet;
import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.gl.buffer.ShaderStorageBuffer;
import net.coderbot.iris.gl.buffer.ShaderStorageBufferHolder;
import net.coderbot.iris.gl.image.ImageHolder;
import net.coderbot.iris.gl.sampler.SamplerHolder;
import net.coderbot.iris.pipeline.CustomTextureManager;
import net.coderbot.iris.pipeline.newshader.NewWorldRenderingPipeline;
import net.coderbot.iris.rendertarget.RenderTargets;
import net.coderbot.iris.samplers.IrisSamplers;
import net.coderbot.iris.shaderpack.texture.TextureStage;
import net.coderbot.iris.shadows.ShadowRenderTargets;
import net.minecraft.client.renderer.texture.AbstractTexture;

import java.lang.reflect.Field;
import java.util.function.Supplier;

/** Small API bridge for the final net.coderbot Iris release used by Minecraft 1.20.2. */
public final class Iris16Compat {
    private static final Field RENDER_TARGETS = field(NewWorldRenderingPipeline.class, "renderTargets");
    private static final Field FLIPPED_AFTER_PREPARE = field(NewWorldRenderingPipeline.class, "flippedAfterPrepare");
    private static final Field CUSTOM_TEXTURE_MANAGER = field(NewWorldRenderingPipeline.class, "customTextureManager");
    private static final Field WHITE_PIXEL = field(NewWorldRenderingPipeline.class, "whitePixel");
    private static final Field SHADOW_TARGETS_SUPPLIER = field(NewWorldRenderingPipeline.class, "shadowTargetsSupplier");
    private static final Field SSBO_BUFFERS = field(ShaderStorageBufferHolder.class, "buffers");

    private Iris16Compat() {
    }

    @SuppressWarnings("unchecked")
    public static ImmutableSet<Integer> getFlippedAfterPrepare(NewWorldRenderingPipeline pipeline) {
        return get(FLIPPED_AFTER_PREPARE, pipeline, ImmutableSet.class);
    }

    public static void addGbufferOrShadowSamplers(NewWorldRenderingPipeline pipeline,
                                                   SamplerHolder samplers,
                                                   ImageHolder images,
                                                   Supplier<ImmutableSet<Integer>> flipped,
                                                   boolean shadowPass,
                                                   boolean hasTexture,
                                                   boolean hasLightmap,
                                                   boolean hasOverlay) {
        RenderTargets renderTargets = get(RENDER_TARGETS, pipeline, RenderTargets.class);
        CustomTextureManager textures = get(CUSTOM_TEXTURE_MANAGER, pipeline, CustomTextureManager.class);
        AbstractTexture whitePixel = get(WHITE_PIXEL, pipeline, AbstractTexture.class);

        IrisSamplers.addRenderTargetSamplers(samplers, flipped, renderTargets, false);
        IrisSamplers.addCustomTextures(samplers, textures.getCustomTextureIdMap(TextureStage.GBUFFERS_AND_SHADOW));
        IrisSamplers.addCustomTextures(samplers, textures.getIrisCustomTextures());
        IrisSamplers.addLevelSamplers(samplers, pipeline, whitePixel,
                new InputAvailability(hasTexture, hasLightmap, hasOverlay));
        IrisSamplers.addWorldDepthSamplers(samplers, renderTargets);
        IrisSamplers.addNoiseSampler(samplers, textures.getNoiseTexture());

        if (shadowPass || IrisSamplers.hasShadowSamplers(samplers)) {
            Supplier<ShadowRenderTargets> shadowTargets = get(SHADOW_TARGETS_SUPPLIER, pipeline, Supplier.class);
            IrisSamplers.addShadowSamplers(samplers, shadowTargets.get(), null, false);
        }
        VoxySamplers.addSamplers(pipeline, samplers);
    }

    public static int getBufferIndex(ShaderStorageBufferHolder holder, int irisIndex) {
        ShaderStorageBuffer[] buffers = get(SSBO_BUFFERS, holder, ShaderStorageBuffer[].class);
        if (irisIndex < 0 || irisIndex >= buffers.length || buffers[irisIndex] == null) {
            throw new IllegalArgumentException("Unknown Iris shader storage buffer index " + irisIndex);
        }
        return buffers[irisIndex].getId();
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static <T> T get(Field field, Object owner, Class<T> type) {
        try {
            return type.cast(field.get(owner));
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not access Iris 1.6 pipeline state", exception);
        }
    }
}
