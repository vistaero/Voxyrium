package me.cortex.voxy.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.commonImpl.IVoxyWorld;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class VoxyConfigScreenFactory implements ModMenuApi {
    private static VoxyConfig DEFAULT;

    private static boolean ON_SAVE_RELOAD_ALL = false;
    private static boolean ON_SAVE_RELOAD_RENDERER = false;

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> buildConfigScreen(parent, VoxyConfig.CONFIG);
    }

    private static Screen buildConfigScreen(Screen parent, VoxyConfig config) {
        if (DEFAULT == null) {
            DEFAULT = new VoxyConfig();
        }
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.translatable("voxy.config.title"));


        addGeneralCategory(builder, config);
        //addThreadsCategory(builder, config);
        //addStorageCategory(builder, config);

        builder.setSavingRunnable(() -> {
            //After saving the core should be reloaded/reset
            var worldRenderer = MinecraftClient.getInstance().worldRenderer;
            var world = ((IVoxyWorld) MinecraftClient.getInstance().world);
            if (worldRenderer != null && (ON_SAVE_RELOAD_ALL||ON_SAVE_RELOAD_RENDERER)) {
                //Shudown renderer
                ((IGetVoxyRenderSystem) worldRenderer).shutdownRenderer();
            }
            //Shutdown world
            if (world != null && ON_SAVE_RELOAD_ALL) {
                //This is a hack inserted for the client world thing
                //TODO: FIXME: MAKE BETTER
                var engine = world.getWorldEngine();
                if (engine != null) {
                    VoxyCommon.getInstance().stopWorld(engine);
                }
                world.setWorldEngine(null);
            }
            //Shutdown instance
            if (ON_SAVE_RELOAD_ALL) {
                VoxyCommon.shutdownInstance();

                //Create instance
                if (VoxyConfig.CONFIG.enabled)
                    VoxyCommon.createInstance();
            }

            if (worldRenderer != null && (ON_SAVE_RELOAD_ALL||ON_SAVE_RELOAD_RENDERER)) {
                //Create renderer
                ((IGetVoxyRenderSystem) worldRenderer).createRenderer();
            }

            ON_SAVE_RELOAD_RENDERER = false;
            ON_SAVE_RELOAD_ALL = false;
            VoxyConfig.CONFIG.save();
        });

        return builder.build();//
    }

    private static void reloadAll() {
        ON_SAVE_RELOAD_ALL = true;
    }

    private static void reloadRender() {
        ON_SAVE_RELOAD_RENDERER = true;
    }

    private static void addGeneralCategory(ConfigBuilder builder, VoxyConfig config) {
        ConfigCategory category = builder.getOrCreateCategory(Text.translatable("voxy.config.general"));
        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        category.addEntry(entryBuilder.startBooleanToggle(Text.translatable("voxy.config.general.enabled"), config.enabled)
                .setTooltip(Text.translatable("voxy.config.general.enabled.tooltip"))
                .setSaveConsumer(val -> {if (config.enabled != val) reloadAll(); config.enabled = val;})
                .setDefaultValue(DEFAULT.enabled)
                .build());

        category.addEntry(entryBuilder.startBooleanToggle(Text.translatable("voxy.config.general.ingest"), config.ingestEnabled)
                .setTooltip(Text.translatable("voxy.config.general.ingest.tooltip"))
                .setSaveConsumer(val -> config.ingestEnabled = val)
                .setDefaultValue(DEFAULT.ingestEnabled)
                .build());

        category.addEntry(entryBuilder.startBooleanToggle(Text.translatable("voxy.config.general.rendering"), config.enableRendering)
                .setTooltip(Text.translatable("voxy.config.general.rendering.tooltip"))
                .setSaveConsumer(val -> {if (config.enableRendering != val) reloadRender(); config.enableRendering = val;})
                .setDefaultValue(DEFAULT.enableRendering)
                .build());

        category.addEntry(entryBuilder.startIntSlider(Text.translatable("voxy.config.general.subDivisionSize"), (int) config.subDivisionSize, 25, 256)
                .setTooltip(Text.translatable("voxy.config.general.subDivisionSize.tooltip"))
                .setSaveConsumer(val -> config.subDivisionSize = val)
                .setDefaultValue((int) DEFAULT.subDivisionSize)
                .build());

        //category.addEntry(entryBuilder.startIntSlider(Text.translatable("voxy.config.general.lruCacheSize"), config.secondaryLruCacheSize, 16, 1<<13)
        //        .setTooltip(Text.translatable("voxy.config.general.lruCacheSize.tooltip"))
        //        .setSaveConsumer(val ->{if (config.secondaryLruCacheSize != val) reload(); config.secondaryLruCacheSize = val;})
        //        .setDefaultValue(DEFAULT.secondaryLruCacheSize)
        //        .build());

        category.addEntry(entryBuilder.startIntSlider(Text.translatable("voxy.config.general.serviceThreads"), config.serviceThreads, 1, Runtime.getRuntime().availableProcessors())
                .setTooltip(Text.translatable("voxy.config.general.serviceThreads.tooltip"))
                .setSaveConsumer(val ->{if (config.serviceThreads != val) reloadAll(); config.serviceThreads = val;})
                .setDefaultValue(DEFAULT.serviceThreads)
                .build());
    }
}
