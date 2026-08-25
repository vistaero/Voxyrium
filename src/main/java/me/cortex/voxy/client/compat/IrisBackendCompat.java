package me.cortex.voxy.client.compat;

import me.cortex.voxy.common.Logger;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;

/**
 * Minimal, backend-neutral Iris query used before Voxy chooses a renderer.
 *
 * <p>This intentionally does not depend on Voxy's regular Iris helpers: those
 * also reference Iris' OpenGL rendering classes and must not be loaded while
 * Minecraft is starting on Vulkan.</p>
 */
public final class IrisBackendCompat {
    private static final boolean IRIS_INSTALLED = FabricLoader.getInstance().isModLoaded("iris");

    private IrisBackendCompat() {}

    public static boolean shouldAvoidBlaze3dRenderer() {
        if (!IRIS_INSTALLED) {
            return false;
        }

        try {
            IrisApi api = IrisApi.getInstance();
            // Iris being installed, or merely having shaders enabled in its config,
            // does not imply an active shaderpack. Blaze3D only needs to step aside
            // while Iris is actually rendering one.
            return api.isShaderPackInUse();
        } catch (Throwable throwable) {
            Logger.warn("Could not query whether an Iris shaderpack is active; leaving the selected Voxy renderer unchanged.", throwable);
            return false;
        }
    }
}
