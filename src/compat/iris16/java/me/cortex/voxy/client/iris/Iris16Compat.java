package me.cortex.voxy.client.iris;

import com.google.common.collect.ImmutableSet;
import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.gl.buffer.ShaderStorageBuffer;
import net.coderbot.iris.gl.buffer.ShaderStorageBufferHolder;
import net.coderbot.iris.gl.image.ImageHolder;
import net.coderbot.iris.gl.sampler.SamplerHolder;
import net.coderbot.iris.gl.uniform.DynamicLocationalUniformHolder;
import net.coderbot.iris.gl.uniform.UniformUpdateFrequency;
import net.coderbot.iris.pipeline.CustomTextureManager;
import net.coderbot.iris.pipeline.newshader.NewWorldRenderingPipeline;
import net.coderbot.iris.rendertarget.RenderTarget;
import net.coderbot.iris.rendertarget.RenderTargets;
import net.coderbot.iris.samplers.IrisSamplers;
import net.coderbot.iris.shaderpack.texture.TextureStage;
import net.coderbot.iris.shadows.ShadowRenderTargets;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/** API bridge for the net.coderbot Iris 1.6 releases used by Minecraft 1.20 and 1.20.2. */
public final class Iris16Compat {
    private static final Field RENDER_TARGETS = field(NewWorldRenderingPipeline.class, "renderTargets");
    private static final Field FLIPPED_AFTER_PREPARE = field(NewWorldRenderingPipeline.class, "flippedAfterPrepare");
    private static final Field CUSTOM_TEXTURE_MANAGER = field(NewWorldRenderingPipeline.class, "customTextureManager");
    private static final Field WHITE_PIXEL = field(NewWorldRenderingPipeline.class, "whitePixel");
    private static final Field SHADOW_TARGETS_SUPPLIER = field(NewWorldRenderingPipeline.class, "shadowTargetsSupplier");
    private static final Field SSBO_BUFFERS = field(ShaderStorageBufferHolder.class, "buffers");
    private static final Method GET_OR_CREATE_RENDER_TARGET = optionalMethod(RenderTargets.class, "getOrCreate", int.class);
    private static final Method UNIFORM3I = optionalMethod(DynamicLocationalUniformHolder.class, "uniform3i",
            UniformUpdateFrequency.class, String.class, Supplier.class);
    private static final Vector3d CURRENT_CAMERA = new Vector3d();
    private static final Vector3d PREVIOUS_CAMERA = new Vector3d();
    private static int cameraFrame = Integer.MIN_VALUE;

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
    }

    /** Supplies uniforms added after Iris 1.6, plus Voxy matrices that its custom-uniform bridge drops. */
    public static void addMissingUniforms(DynamicLocationalUniformHolder uniforms) {
        VoxyUniforms.addUniforms(uniforms);
        if (UNIFORM3I != null) {
            invoke(UNIFORM3I, uniforms, UniformUpdateFrequency.PER_FRAME, "cameraPositionInt",
                    (Supplier<Vector3i>)Iris16Compat::cameraPositionInt);
        }
        uniforms.uniform3f("cameraPositionFract", Iris16Compat::cameraPositionFract, null);
        uniforms.uniform3f("previousCameraPositionFract", Iris16Compat::previousCameraPositionFract, null);
    }

    public static RenderTarget getRenderTarget(RenderTargets renderTargets, int index) {
        if (GET_OR_CREATE_RENDER_TARGET != null) {
            return invoke(GET_OR_CREATE_RENDER_TARGET, renderTargets, index);
        }
        return renderTargets.get(index);
    }

    private static Vector3i cameraPositionInt() {
        Vector3d camera = updateCamera(false);
        return new Vector3i(floorToInt(camera.x), floorToInt(camera.y), floorToInt(camera.z));
    }

    private static Vector3f cameraPositionFract() {
        Vector3d camera = updateCamera(false);
        return new Vector3f(fraction(camera.x), fraction(camera.y), fraction(camera.z));
    }

    private static Vector3f previousCameraPositionFract() {
        Vector3d camera = updateCamera(true);
        return new Vector3f(fraction(camera.x), fraction(camera.y), fraction(camera.z));
    }

    private static synchronized Vector3d updateCamera(boolean previous) {
        var renderSystem = IVoxyRenderSystemHolder.getNullable();
        if (renderSystem == null) {
            return previous ? new Vector3d(PREVIOUS_CAMERA) : new Vector3d(CURRENT_CAMERA);
        }

        var viewport = renderSystem.getViewport();
        if (viewport.frameId != cameraFrame) {
            PREVIOUS_CAMERA.set(CURRENT_CAMERA);
            CURRENT_CAMERA.set(viewport.cameraX, viewport.cameraY, viewport.cameraZ);
            if (cameraFrame == Integer.MIN_VALUE) {
                PREVIOUS_CAMERA.set(CURRENT_CAMERA);
            }
            cameraFrame = viewport.frameId;
        }
        return previous ? new Vector3d(PREVIOUS_CAMERA) : new Vector3d(CURRENT_CAMERA);
    }

    private static int floorToInt(double value) {
        return (int)Math.floor(value);
    }

    private static float fraction(double value) {
        return (float)(value - Math.floor(value));
    }

    public static int getBufferIndex(ShaderStorageBufferHolder holder, int irisIndex) {
        ShaderStorageBuffer[] buffers = get(SSBO_BUFFERS, holder, ShaderStorageBuffer[].class);
        if (irisIndex < 0 || irisIndex >= buffers.length || buffers[irisIndex] == null) {
            throw new IllegalArgumentException("Unknown Iris shader storage buffer index " + irisIndex);
        }
        return getInt(fieldInHierarchy(buffers[irisIndex].getClass(), "id"), buffers[irisIndex]);
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

    private static Field fieldInHierarchy(Class<?> owner, String name) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new ExceptionInInitializerError("Missing Iris field " + owner.getName() + "." + name);
    }

    private static Method optionalMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            Method method = owner.getMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Method method, Object owner, Object... arguments) {
        try {
            return (T)method.invoke(owner, arguments);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not invoke Iris 1.6 compatibility method", exception);
        }
    }

    private static int getInt(Field field, Object owner) {
        try {
            return field.getInt(owner);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not access Iris 1.6 buffer state", exception);
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
