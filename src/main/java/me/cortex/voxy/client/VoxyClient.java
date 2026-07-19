package me.cortex.voxy.client;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.vk.MinecraftVkHost;
import me.cortex.voxy.client.core.vk.VulkanBackend;
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

public class VoxyClient implements ClientModInitializer {
    private static final HashSet<String> FREX = new HashSet<>();
    private static FileLock EXCLUSIVE_LOCK;
    public static void initVoxyClient() {
        //When MC itself presents through its 26.2 Vulkan backend there is no GL
        // context on the render thread. Nothing GL-backed may be touched here —
        // above all not Capabilities, whose <clinit> calls GL.getCapabilities()
        // and throws. Everything below this branch classloads or queries GL and
        // is only valid when MC is on OpenGL.
        if (MinecraftVkHost.isMinecraftOnVulkan()) {
            initVoxyClientVulkan();
            return;
        }

        Capabilities.init();//Ensure clinit is called

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        boolean systemSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters && !Capabilities.INSTANCE.hasBrokenDepthSampler;
        if (!systemSupported) {
             Logger.error("Voxy is unsupported on your system.");
        }

        if (systemSupported && System.getProperty("voxy.exclusiveLock", "false").equalsIgnoreCase("true")) {
            if (!acquireExclusiveLock()) {
                systemSupported = false;
            }
        }

        if (systemSupported) {

            SharedIndexBuffer.INSTANCE().id();

            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }

        }
    }

    //Vulkan init: MC is presenting through its own Vulkan backend, so Voxy adopts
    // that device (VulkanBackend) and never runs the GL capability probes. This
    // method — and everything it reaches — must stay free of any GL classload.
    // If VK cannot be adopted (host adapter not registered, missing bindings)
    // Voxy disables itself: falling back to GL is impossible (no GL context).
    private static void initVoxyClientVulkan() {
        if (!VulkanBackend.shouldUseVulkan()) {
            Logger.error("Voxy is unsupported on your system. Minecraft is on Vulkan but the Voxy Vulkan backend could not be used (" + VulkanBackend.statusLine() + ")");
            return;
        }

        if (System.getProperty("voxy.exclusiveLock", "false").equalsIgnoreCase("true")) {
            if (!acquireExclusiveLock()) {
                return;
            }
        }

        VoxyCommon.setInstanceFactory(VoxyClientInstance::new);
        Logger.info("Voxy initialised on the Vulkan backend (" + VulkanBackend.statusLine() + ")");
    }

    //Acquire the cross-process exclusive lock file. Backend-agnostic (pure file
    // IO, no GPU work). Returns false and logs on failure so callers can disable Voxy.
    private static boolean acquireExclusiveLock() {
        var vf = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy");
        if (!vf.toFile().isDirectory()) {
            vf.toFile().mkdir();
        }
        try {
            FileOutputStream fis = new FileOutputStream(vf.resolve("voxy.lock").toFile());
            EXCLUSIVE_LOCK = fis.getChannel().lock(0, Long.MAX_VALUE, false);
            return true;
        } catch (NonWritableChannelException | IOException e) {
            Logger.error("Failed to acquire exclusive voxy lock file, mod will be disabled");
            return false;
        }
    }

    @Override
    public void onInitializeClient() {
        DebugEntries.init();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            if (VoxyCommon.isAvailable()) {
                dispatcher.register(VoxyCommands.register());
            }
        });

        FabricLoader.getInstance()
                .getEntrypoints("frex_flawless_frames", Consumer.class)
                .forEach(api -> ((Consumer<Function<String,Consumer<Boolean>>>)api).accept(name->active->{if (active) {
                    FREX.add(name);
                } else {
                    FREX.remove(name);
                }}));
    }

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return false;// getOcclusionDebugState() != 0;
    }
}