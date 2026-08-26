package me.cortex.voxy.client.core.util;

import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gl.IrisRenderSystem;

import java.io.IOException;
import java.util.function.Supplier;

/** Compatibility boundary for the Iris 1.7 API on legacy Minecraft releases. */
public final class IrisUtil {
    public static final boolean IRIS_INSTALLED = FabricLoader.getInstance().isModLoaded("iris");
    public static final boolean SHADER_SUPPORT = true;

    private IrisUtil() {
    }

    public static boolean irisShadowActive() {
        if (!IRIS_INSTALLED) {
            return false;
        }
        try {
            return IrisApi.getInstance().isRenderingShadowPass();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean irisShaderPackEnabled() {
        if (!IRIS_INSTALLED) {
            return false;
        }
        try {
            return IrisApi.getInstance().isShaderPackInUse();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean irisShaderpackActiveSafe() {
        return irisShaderPackEnabled();
    }

    public static boolean irisShadersEnabledInConfig() {
        return irisShaderPackEnabled();
    }

    public static <T> T runWithoutVertexFormatExtension(Supplier<T> action) {
        return action.get();
    }

    public static void clearIrisSamplers() {
        if (!IRIS_INSTALLED) {
            return;
        }
        for (int unit = 0; unit < 16; unit++) {
            IrisRenderSystem.bindSamplerToUnit(unit, 0);
        }
    }

    public static void reload() {
        if (!IRIS_INSTALLED) {
            return;
        }
        try {
            if (IrisApi.getInstance().isShaderPackInUse()
                    || IrisApi.getInstance().getConfig().areShadersEnabled()) {
                Iris.reload();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to reload Iris for the Voxy renderer transition", e);
        }
    }

    public static void disableIrisShaders() {
        if (IRIS_INSTALLED) {
            IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);
        }
    }
}
