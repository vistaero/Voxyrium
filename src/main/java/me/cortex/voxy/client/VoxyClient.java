package me.cortex.voxy.client;

import me.cortex.voxy.client.core.backend.VoxyGraphicsBackend;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;

/** Minecraft 1.20.6 bootstrap for the current native OpenGL renderer. */
public final class VoxyClient implements ClientModInitializer {
    private static final HashSet<String> FREX = new HashSet<>();
    private static FileLock exclusiveLock;

    private static void initializeRenderer() {
        VoxyGraphicsBackend.initialize();
        boolean nativeAvailable = probeNativeOpenGlRenderer();
        VoxyGraphicsBackend.resolveRenderer(VoxyGraphicsBackend.RendererMode.NATIVE, nativeAvailable);
        if (!nativeAvailable) {
            Logger.error("Voxy rendering is disabled because the native OpenGL renderer is unavailable ("
                    + VoxyGraphicsBackend.statusLine() + ")");
            return;
        }
        if (System.getProperty("voxy.exclusiveLock", "false").equalsIgnoreCase("true")
                && !acquireExclusiveLock()) {
            return;
        }
        SharedIndexBuffer.INSTANCE().id();
        if (!Capabilities.INSTANCE.subgroup) {
            Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
        }
        Logger.info("Voxy initialised on the native OpenGL renderer ("
                + VoxyGraphicsBackend.statusLine() + ")");
    }

    private static boolean probeNativeOpenGlRenderer() {
        try {
            Capabilities.init();
            boolean supported = Capabilities.INSTANCE.compute
                    && Capabilities.INSTANCE.indirectParameters
                    && !Capabilities.INSTANCE.hasBrokenDepthSampler;
            if (!supported) {
                Logger.warn("Voxy's native OpenGL renderer is unavailable; required OpenGL capabilities are missing.");
            }
            return supported;
        } catch (Throwable throwable) {
            Logger.error("Voxy's native OpenGL capability probe failed.", throwable);
            return false;
        }
    }

    private static boolean acquireExclusiveLock() {
        var directory = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy");
        if (!directory.toFile().isDirectory()) {
            directory.toFile().mkdir();
        }
        try {
            FileOutputStream stream = new FileOutputStream(directory.resolve("voxy.lock").toFile());
            exclusiveLock = stream.getChannel().lock(0, Long.MAX_VALUE, false);
            return true;
        } catch (NonWritableChannelException | IOException exception) {
            Logger.error("Failed to acquire exclusive Voxy lock file; the renderer will be disabled", exception);
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onInitializeClient() {
        VoxyCommon.setInstanceFactory(VoxyClientInstance::new);
        initializeRenderer();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            if (VoxyCommon.isAvailable()) {
                dispatcher.register(VoxyCommands.register());
            }
        });

        FabricLoader.getInstance()
                .getEntrypoints("frex_flawless_frames", Consumer.class)
                .forEach(api -> ((Consumer<Function<String, Consumer<Boolean>>>) api).accept(name -> active -> {
                    if (active) {
                        FREX.add(name);
                    } else {
                        FREX.remove(name);
                    }
                }));
    }

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return false;
    }
}
