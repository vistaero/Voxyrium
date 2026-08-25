package me.cortex.voxy.client;

import com.mojang.blaze3d.systems.GpuDevice;
import me.cortex.voxy.client.compat.IrisBackendCompat;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.backend.VoxyGraphicsBackend;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.core.vk.VulkanBackend;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

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
    private static String pendingRendererNotice;
    private static boolean pendingBlaze3dIrisConfirmation;
    private static boolean pendingBlaze3dSelectionAtWorldEntry;
    private static boolean blaze3dIrisWarningShownThisSession;

    public static void initVoxyClient(GpuDevice device) {
        VoxyGraphicsBackend.initialize(device);
        VoxyGraphicsBackend.RendererMode configuredRenderer = VoxyConfig.CONFIG.getRendererBackendMode();
        VoxyCommon.setInstanceFactory(VoxyClientInstance::new);

        if (configuredRenderer == VoxyGraphicsBackend.RendererMode.BLAZE3D) {
            // Iris only exposes the active shaderpack after the world becomes available. Keep
            // Voxy rendererless until then so neither Native nor Blaze3D starts prematurely.
            pendingBlaze3dSelectionAtWorldEntry = true;
            VoxyGraphicsBackend.deferRendererSelection();
            return;
        }

        selectRenderer(configuredRenderer, false);

        if (VoxyGraphicsBackend.current() == VoxyGraphicsBackend.VULKAN) {
            if (VoxyGraphicsBackend.usesNativeRenderer()) {
                initVoxyClientVulkan();
            } else {
                initBlaze3dOrReportUnavailable();
            }
            return;
        }

        if (VoxyGraphicsBackend.usesNativeRenderer()) {
            initVoxyClientOpenGl();
        } else {
            initBlaze3dOrReportUnavailable();
        }
    }

    public static void selectRenderer(VoxyGraphicsBackend.RendererMode rendererMode) {
        selectRenderer(rendererMode, false);
    }

    /** Called by the renderer setting so an active Iris shaderpack never silently overrides it. */
    public static void requestRendererSelection(VoxyGraphicsBackend.RendererMode rendererMode) {
        if (rendererMode == VoxyGraphicsBackend.RendererMode.BLAZE3D
                && IrisBackendCompat.shouldAvoidBlaze3dRenderer()) {
            showBlaze3dIrisConfirmation();
            return;
        }
        applyRendererSelection(rendererMode, false);
    }

    private static void showBlaze3dIrisConfirmation() {
        Minecraft minecraft = Minecraft.getInstance();
        // ConfirmScreen passes true for its first (recommended Native) button.
        minecraft.setScreenAndShow(new IrisBlaze3dConfirmScreen(keepNative -> {
            minecraft.setScreenAndShow(null);
            applyRendererSelection(keepNative
                    ? VoxyGraphicsBackend.RendererMode.NATIVE
                    : VoxyGraphicsBackend.RendererMode.BLAZE3D, !keepNative);
        }, Component.translatable("voxy.confirm.iris_blaze3d.title"),
                Component.translatable("voxy.confirm.iris_blaze3d.message"),
                Component.translatable("voxy.confirm.iris_blaze3d.native"),
                Component.translatable("voxy.confirm.iris_blaze3d.blaze3d")));
    }

    private static final class IrisBlaze3dConfirmScreen extends ConfirmScreen {
        private IrisBlaze3dConfirmScreen(it.unimi.dsi.fastutil.booleans.BooleanConsumer callback,
                                         Component title, Component message,
                                         Component nativeButton, Component blaze3dButton) {
            super(callback, title, message, nativeButton, blaze3dButton);
        }

        @Override
        protected void addButtons(LinearLayout layout) {
            super.addButtons(layout);
            layout.addChild(Button.builder(Component.translatable("voxy.confirm.iris_blaze3d.dont_show_again"), button -> {
                VoxyConfig.CONFIG.showBlaze3dIrisWarning = false;
                VoxyConfig.CONFIG.save();
                // The second normal button is Blaze3D, so preserve that explicit choice.
                this.callback.accept(false);
            }).width(Button.BIG_WIDTH).build());
        }
    }

    private static void applyRendererSelection(VoxyGraphicsBackend.RendererMode rendererMode,
                                               boolean allowBlaze3dWithIris) {
        VoxyGraphicsBackend.ActiveRenderer previousRenderer = VoxyGraphicsBackend.activeRenderer();
        boolean previouslyUsingNative = VoxyGraphicsBackend.usesNativeRenderer();
        VoxyConfig.CONFIG.setRendererBackendMode(rendererMode);
        VoxyConfig.CONFIG.save();
        selectRenderer(rendererMode, allowBlaze3dWithIris);
        VoxyGraphicsBackend.ActiveRenderer selectedRenderer = VoxyGraphicsBackend.activeRenderer();
        if (previousRenderer == selectedRenderer) {
            return;
        }
        var rendererHolder = IVoxyRenderSystemHolder.getNullableHolder();
        if (rendererHolder != null) {
            rendererHolder.voxy$shutdownRenderer();
        }
        // Iris builds Voxy's shader patch, draw targets and uniforms only while the Native
        // renderer is selected. Rebuild its pipeline after the old renderer is detached and
        // before the new one is created; otherwise a live Blaze3D -> Native transition leaves
        // Voxy rendering against the shaderpack pipeline compiled without Voxy support.
        if (previouslyUsingNative != VoxyGraphicsBackend.usesNativeRenderer()) {
            IrisUtil.reload();
        }
        if (rendererHolder != null) {
            if (VoxyConfig.CONFIG.enableRendering) {
                rendererHolder.voxy$createRenderer();
            }
        }
    }

    private static void selectRenderer(VoxyGraphicsBackend.RendererMode rendererMode,
                                       boolean allowBlaze3dWithIris) {
        VoxyGraphicsBackend.RendererMode requestedMode = rendererMode == null
                ? VoxyGraphicsBackend.RendererMode.NATIVE
                : rendererMode;
        VoxyGraphicsBackend.RendererMode effectiveMode = requestedMode;

        if (requestedMode == VoxyGraphicsBackend.RendererMode.BLAZE3D
                && !allowBlaze3dWithIris
                && IrisBackendCompat.shouldAvoidBlaze3dRenderer()) {
            effectiveMode = VoxyGraphicsBackend.RendererMode.NATIVE;
            pendingRendererNotice = "Iris shaders are active, but Voxy's Blaze3D renderer does not yet support shaderpack G-buffers; using Native until shaders are disabled.";
            Logger.warn(pendingRendererNotice);
        }

        boolean nativeAvailable = false;
        if (effectiveMode != VoxyGraphicsBackend.RendererMode.BLAZE3D) {
            nativeAvailable = switch (VoxyGraphicsBackend.current()) {
                case VULKAN -> VulkanBackend.shouldUseVulkan();
                case OPENGL -> probeNativeOpenGlRenderer();
                case UNKNOWN -> false;
            };
        }
        VoxyGraphicsBackend.resolveRenderer(effectiveMode, nativeAvailable);
        Logger.info("Selected Voxy renderer: " + VoxyGraphicsBackend.statusLine());
    }

    private static boolean probeNativeOpenGlRenderer() {
        try {
            Capabilities.init();//Ensure clinit is called while the GL context is current
            boolean supported = Capabilities.INSTANCE.compute
                    && Capabilities.INSTANCE.indirectParameters
                    && !Capabilities.INSTANCE.hasBrokenDepthSampler;
            if (!supported) {
                Logger.warn("Voxy's native OpenGL renderer is unavailable; required OpenGL 4.6 capabilities are missing.");
            }
            return supported;
        } catch (Throwable throwable) {
            Logger.error("Voxy's native OpenGL capability probe failed; the Blaze3D renderer remains available.", throwable);
            return false;
        }
    }

    private static void initVoxyClientOpenGl() {
        if (System.getProperty("voxy.exclusiveLock", "false").equalsIgnoreCase("true")
                && !acquireExclusiveLock()) {
            return;
        }

        SharedIndexBuffer.INSTANCE().id();

        if (!Capabilities.INSTANCE.subgroup) {
            Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
        }
        Logger.info("Voxy initialised on the native OpenGL renderer (" + VoxyGraphicsBackend.statusLine() + ")");
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

        Logger.info("Voxy initialised on the Vulkan backend (" + VulkanBackend.statusLine() + ")");
    }

    private static void initBlaze3dOrReportUnavailable() {
        if (VoxyGraphicsBackend.usesBlaze3dRenderer()) {
            Logger.info("Voxy initialised on the alternative Blaze3D renderer ("
                    + VoxyGraphicsBackend.statusLine() + ")");
        } else {
            Logger.error("Voxy rendering is disabled because the native renderer was requested but is unavailable ("
                    + VoxyGraphicsBackend.statusLine() + ")");
        }
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

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingBlaze3dSelectionAtWorldEntry && client.player != null) {
                pendingBlaze3dSelectionAtWorldEntry = false;
                if (VoxyConfig.CONFIG.showBlaze3dIrisWarning && IrisBackendCompat.shouldAvoidBlaze3dRenderer()) {
                    pendingBlaze3dIrisConfirmation = true;
                } else {
                    applyRendererSelection(VoxyGraphicsBackend.RendererMode.BLAZE3D, true);
                }
            }
            if (!blaze3dIrisWarningShownThisSession
                    && !pendingBlaze3dIrisConfirmation
                    && client.player != null
                    && VoxyConfig.CONFIG.showBlaze3dIrisWarning
                    && VoxyConfig.CONFIG.getRendererBackendMode() == VoxyGraphicsBackend.RendererMode.BLAZE3D
                    && VoxyGraphicsBackend.usesBlaze3dRenderer()
                    && IrisBackendCompat.shouldAvoidBlaze3dRenderer()) {
                pendingBlaze3dIrisConfirmation = true;
            }
            if (pendingBlaze3dIrisConfirmation && client.player != null) {
                pendingBlaze3dIrisConfirmation = false;
                blaze3dIrisWarningShownThisSession = true;
                showBlaze3dIrisConfirmation();
            }
            if (pendingRendererNotice != null && client.player != null) {
                String notice = pendingRendererNotice;
                pendingRendererNotice = null;
                Logger.showInHUD(notice);
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            if (VoxyCommon.isAvailable()) {
                dispatcher.register(VoxyCommands.register());
                dispatcher.register(VoxyCommands.registerToggleTestCube());
                dispatcher.register(VoxyCommands.registerSetMinimumVoxyLod());
                dispatcher.register(VoxyCommands.registerToggleVoxyProfiler());
                dispatcher.register(VoxyCommands.registerVoxyLodDebug());
                dispatcher.register(VoxyCommands.registerSetVoxyVanillaTransition());
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
