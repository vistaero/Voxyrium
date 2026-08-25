package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.client.core.backend.VoxyGraphicsBackend;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class VoxyConfig {
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static VoxyConfig CONFIG = loadOrCreate();

    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    public float sectionRenderDistance = 16;
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount()/1.5, 1);
    public float subDivisionSize = 64;
    public String fogMode;
    public boolean dontUseSodiumBuilderThreads = false;
    public String rendererBackend = "native";
    public boolean showBlaze3dIrisWarning = true;

    public VoxyGraphicsBackend.RendererMode getRendererBackendMode() {
        if (this.rendererBackend == null) return VoxyGraphicsBackend.RendererMode.NATIVE;
        try {
            return VoxyGraphicsBackend.RendererMode.valueOf(this.rendererBackend.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return VoxyGraphicsBackend.RendererMode.NATIVE;
        }
    }

    public void setRendererBackendMode(VoxyGraphicsBackend.RendererMode mode) {
        this.rendererBackend = mode.name().toLowerCase(Locale.ROOT);
    }

    public String ssaoMode;

    public SSAO.SSAOMode getSSAOMode() {
        var DEFAULT = SSAO.SSAOMode.AUTO;
        if (this.ssaoMode == null) return DEFAULT;
        try {
            return SSAO.SSAOMode.valueOf(this.ssaoMode.toUpperCase(Locale.ROOT));
        } catch (Exception e) { return DEFAULT; }
    }

    public void setSSAOMode(SSAO.SSAOMode mode) {
        this.ssaoMode = mode.name().toLowerCase(Locale.ROOT);
    }


    public NormalRenderPipeline.FogMode getFogMode() {
        var DEFAULT = NormalRenderPipeline.FogMode.FOG_AND_FADE;
        if (this.fogMode == null) return DEFAULT;
        try {
            return NormalRenderPipeline.FogMode.valueOf(this.fogMode.toUpperCase(Locale.ROOT));
        } catch (Exception e) { return DEFAULT;}
    }

    public void setFogMode(NormalRenderPipeline.FogMode mode) {
        this.fogMode = mode.name().toLowerCase(Locale.ROOT);
    }


    private static VoxyConfig loadOrCreate() {
        //The client config is initialized before Voxy registers its instance
        //factory, so isAvailable() is still false at this point. Config I/O is
        //valid for the Minecraft client regardless of renderer init order.
        if (canAccessClientConfig()) {
            var path = getConfigPath();
            if (Files.exists(path)) {
                try (FileReader reader = new FileReader(path.toFile())) {
                    var conf = GSON.fromJson(reader, VoxyConfig.class);
                    if (conf != null) {
                        // Auto used to mean Native when available. Keep that effective default
                        // while removing the redundant choice from existing configuration files.
                        if (conf.rendererBackend == null || conf.rendererBackend.equalsIgnoreCase("auto")) {
                            conf.rendererBackend = "native";
                        }
                        conf.save();
                        return conf;
                    } else {
                        Logger.error("Failed to load voxy config, resetting");
                    }
                } catch (IOException e) {
                    Logger.error("Could not load config", e);
                } catch (JsonParseException e) {
                    Logger.error("Could not parse config", e);
                }
                Logger.info("Error during config loading, creating new");
            } else {
                Logger.info("Config file doesnt exist, creating new");
            }
            var config = new VoxyConfig();
            config.save();
            return config;
        } else {
            var config = new VoxyConfig();
            config.enabled = false;
            config.enableRendering = false;
            return config;
        }
    }

    public void save() {
        if (!canAccessClientConfig()) {
            Logger.info("Not saving config since voxy is unavalible");
            return;
        }

        try {
            Files.writeString(getConfigPath(), GSON.toJson(this));
        } catch (IOException e) {
            Logger.error("Failed to write config file", e);
        }
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance()
                .getConfigDir()
                .resolve("voxy-config.json");
    }

    private static boolean canAccessClientConfig() {
        return VoxyCommon.IS_IN_MINECRAFT && !VoxyCommon.IS_DEDICATED_SERVER;
    }

    public boolean isRenderingEnabled() {
        return VoxyCommon.isAvailable()
                && VoxyGraphicsBackend.usesNativeRenderer()
                && this.enabled
                && this.enableRendering;
    }

    public boolean isBlaze3dRenderingEnabled() {
        return VoxyCommon.isAvailable()
                && VoxyGraphicsBackend.usesBlaze3dRenderer()
                && this.enabled
                && this.enableRendering;
    }
}
