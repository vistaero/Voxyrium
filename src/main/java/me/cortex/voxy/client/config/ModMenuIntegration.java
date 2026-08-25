package me.cortex.voxy.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Cloth Config bridge for Sodium 0.5, which predates Sodium's config API. */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ModMenuIntegration::buildScreen;
    }

    private static Screen buildScreen(Screen parent) {
        VoxyConfig config = VoxyConfig.CONFIG;
        VoxyConfig defaults = new VoxyConfig();
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("voxy.config.title"));
        var category = builder.getOrCreateCategory(Component.translatable("voxy.config.general"));
        var entries = builder.entryBuilder();

        category.addEntry(entries.startBooleanToggle(
                        Component.translatable("voxy.config.general.enabled"), config.enabled)
                .setDefaultValue(defaults.enabled)
                .setSaveConsumer(value -> config.enabled = value)
                .build());
        category.addEntry(entries.startBooleanToggle(
                        Component.translatable("voxy.config.general.rendering"), config.enableRendering)
                .setDefaultValue(defaults.enableRendering)
                .setSaveConsumer(value -> config.enableRendering = value)
                .build());
        category.addEntry(entries.startBooleanToggle(
                        Component.translatable("voxy.config.general.ingest"), config.ingestEnabled)
                .setDefaultValue(defaults.ingestEnabled)
                .setSaveConsumer(value -> config.ingestEnabled = value)
                .build());
        category.addEntry(entries.startIntSlider(
                        Component.translatable("voxy.config.general.renderDistance"),
                        Math.round(config.sectionRenderDistance * 32.0f), 32, 2048)
                .setDefaultValue(Math.round(defaults.sectionRenderDistance * 32.0f))
                .setSaveConsumer(value -> config.sectionRenderDistance = value / 32.0f)
                .build());
        category.addEntry(entries.startIntSlider(
                        Component.translatable("voxy.config.general.subDivisionSize"),
                        Math.round(config.subDivisionSize), 28, 256)
                .setDefaultValue(Math.round(defaults.subDivisionSize))
                .setSaveConsumer(value -> config.subDivisionSize = value)
                .build());
        category.addEntry(entries.startIntSlider(
                        Component.translatable("voxy.config.general.serviceThreads"),
                        config.serviceThreads, 1, CpuLayout.getCoreCount())
                .setDefaultValue(defaults.serviceThreads)
                .setSaveConsumer(value -> config.serviceThreads = value)
                .build());
        category.addEntry(entries.startBooleanToggle(
                        Component.translatable("voxy.config.general.useSodiumBuilder"),
                        !config.dontUseSodiumBuilderThreads)
                .setDefaultValue(!defaults.dontUseSodiumBuilderThreads)
                .setSaveConsumer(value -> config.dontUseSodiumBuilderThreads = !value)
                .build());
        category.addEntry(entries.startEnumSelector(
                        Component.translatable("voxy.config.general.environmental_fog"),
                        NormalRenderPipeline.FogMode.class, config.getFogMode())
                .setDefaultValue(defaults.getFogMode())
                .setEnumNameProvider(value -> Component.translatable(
                        "voxy.config.general.environmental_fog." + value.name().toLowerCase(Locale.ROOT)))
                .setSaveConsumer(config::setFogMode)
                .build());
        category.addEntry(entries.startEnumSelector(
                        Component.translatable("voxy.config.general.ssao_mode"),
                        SSAO.SSAOMode.class, config.getSSAOMode())
                .setDefaultValue(defaults.getSSAOMode())
                .setEnumNameProvider(value -> Component.translatable(
                        "voxy.config.general.ssao_mode." + value.name().toLowerCase(Locale.ROOT)))
                .setSaveConsumer(config::setSSAOMode)
                .build());

        builder.setSavingRunnable(config::save);
        return builder.build();
    }
}
