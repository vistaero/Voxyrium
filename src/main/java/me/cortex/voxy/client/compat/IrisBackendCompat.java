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
            // Voxy selects its renderer before Iris finishes creating the active
            // pipeline. The config flag therefore covers startup, while
            // isShaderPackInUse() covers renderer changes later in the session.
            return api.isShaderPackInUse() || api.getConfig().areShadersEnabled();
        } catch (Throwable throwable) {
            // An unknown Iris state must not select the known-incompatible path.
            Logger.warn("Could not query Iris shader state before selecting Voxy's renderer; assuming shaders are active and avoiding Blaze3D.", throwable);
            return true;
        }
    }
}
