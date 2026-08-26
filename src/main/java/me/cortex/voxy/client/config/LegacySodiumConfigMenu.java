package me.cortex.voxy.client.config;

import com.google.common.collect.ImmutableList;
import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlValueFormatter;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.network.chat.Component;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Adds Voxy's settings to the Sodium 0.4/0.5 options screen. Those Sodium
 * releases predate the public config API used by current Voxy.
 */
public final class LegacySodiumConfigMenu {
    private static final LegacyOptionStorage STORAGE = new LegacyOptionStorage();

    private static final class LegacyOptionStorage implements OptionStorage<VoxyConfig> {
        private boolean enabledAtOpen;
        private boolean renderingAtOpen;
        private NormalRenderPipeline.FogMode fogModeAtOpen;

        public void beginEditing() {
            var config = VoxyConfig.CONFIG;
            this.enabledAtOpen = config.enabled;
            this.renderingAtOpen = config.enableRendering;
            this.fogModeAtOpen = config.getFogMode();
        }

        @Override
        public VoxyConfig getData() {
            return VoxyConfig.CONFIG;
        }

        @Override
        public void save() {
            var config = VoxyConfig.CONFIG;
            var currentFogMode = config.getFogMode();
            boolean reloadRuntimeState = this.enabledAtOpen != config.enabled
                    || this.renderingAtOpen != config.enableRendering;
            boolean reloadFogPipeline = this.fogModeAtOpen != currentFogMode;
            config.save();
            this.enabledAtOpen = config.enabled;
            this.renderingAtOpen = config.enableRendering;
            this.fogModeAtOpen = currentFogMode;

            if (reloadRuntimeState) {
                var holder = IVoxyRenderSystemHolder.getNullableHolder();
                if (holder != null) {
                    holder.voxy$shutdownRenderer();
                }

                if (!config.enabled) {
                    VoxyCommon.shutdownInstance();
                } else if (ClientSessionEvents.inSession && VoxyCommon.getInstance() == null) {
                    VoxyCommon.createInstance();
                }

                // Match dev's renderer-transition contract: Iris must rebuild its
                // VOXY defines after the old renderer is detached and before the
                // newly enabled renderer is constructed.
                IrisUtil.reload();

                if (holder != null && config.enabled && config.enableRendering) {
                    holder.voxy$createRenderer();
                }
            } else if (reloadFogPipeline) {
                var holder = IVoxyRenderSystemHolder.getNullableHolder();
                if (holder != null && holder.voxy$getRenderSystem() != null) {
                    holder.voxy$shutdownRenderer();
                    holder.voxy$createRenderer();
                }
            }
        }
    }

    private LegacySodiumConfigMenu() {
    }

    public static OptionPage createPage() {
        STORAGE.beginEditing();
        int coreCount = Math.max(1, CpuLayout.getCoreCount());

        var general = OptionGroup.createBuilder()
                .add(booleanOption("voxy.config.general.enabled",
                        (config, value) -> config.enabled = value,
                        config -> config.enabled))
                .add(booleanOption("voxy.config.general.rendering",
                        (config, value) -> config.enableRendering = value,
                        config -> config.enableRendering))
                .add(booleanOption("voxy.config.general.ingest",
                        (config, value) -> config.ingestEnabled = value,
                        config -> config.ingestEnabled))
                .add(booleanOption("voxy.config.general.useSodiumBuilder",
                        (config, value) -> config.dontUseSodiumBuilderThreads = !value,
                        config -> !config.dontUseSodiumBuilderThreads))
                .add(integerOption("voxy.config.general.serviceThreads", 1, coreCount, 1,
                        ControlValueFormatter.number(),
                        (config, value) -> config.serviceThreads = value,
                        config -> config.serviceThreads,
                        OptionImpact.LOW))
                .build();

        var rendering = OptionGroup.createBuilder()
                .add(integerOption("voxy.config.general.renderDistance", 32, 2048, 1,
                        ControlValueFormatter.translateVariable("options.chunks"),
                        (config, value) -> config.sectionRenderDistance = value / 32.0f,
                        config -> Math.round(config.sectionRenderDistance * 32.0f),
                        OptionImpact.HIGH))
                .add(integerOption("voxy.config.general.subDivisionSize", 28, 256, 1,
                        ControlValueFormatter.number(),
                        (config, value) -> config.subDivisionSize = value,
                        config -> Math.round(config.subDivisionSize),
                        OptionImpact.HIGH))
                .add(enumOption("voxy.config.general.environmental_fog",
                        NormalRenderPipeline.FogMode.class,
                        new Component[]{
                                Component.translatable("voxy.config.general.environmental_fog.fog_and_fade"),
                                Component.translatable("voxy.config.general.environmental_fog.fog"),
                                Component.translatable("voxy.config.general.environmental_fog.fade"),
                                Component.translatable("voxy.config.general.environmental_fog.off")
                        },
                        VoxyConfig::setFogMode,
                        VoxyConfig::getFogMode))
                .add(enumOption("voxy.config.general.ssao_mode",
                        SSAO.SSAOMode.class,
                        new Component[]{
                                Component.translatable("voxy.config.general.ssao_mode.auto"),
                                Component.translatable("voxy.config.general.ssao_mode.basic"),
                                Component.translatable("voxy.config.general.ssao_mode.better"),
                                Component.translatable("voxy.config.general.ssao_mode.best")
                        },
                        VoxyConfig::setSSAOMode,
                        VoxyConfig::getSSAOMode))
                .build();

        return new OptionPage(Component.literal("Voxy"), ImmutableList.of(general, rendering));
    }

    private static OptionImpl<VoxyConfig, Boolean> booleanOption(String translationKey,
                                                                  BiConsumer<VoxyConfig, Boolean> setter,
                                                                  Function<VoxyConfig, Boolean> getter) {
        return OptionImpl.createBuilder(Boolean.class, STORAGE)
                .setName(Component.translatable(translationKey))
                .setTooltip(Component.translatable(translationKey + ".tooltip"))
                .setControl(TickBoxControl::new)
                .setBinding(setter, getter)
                .setImpact(OptionImpact.LOW)
                .setEnabled(true)
                .build();
    }

    private static OptionImpl<VoxyConfig, Integer> integerOption(String translationKey,
                                                                  int minimum,
                                                                  int maximum,
                                                                  int interval,
                                                                  ControlValueFormatter formatter,
                                                                  BiConsumer<VoxyConfig, Integer> setter,
                                                                  Function<VoxyConfig, Integer> getter,
                                                                  OptionImpact impact) {
        return OptionImpl.createBuilder(Integer.class, STORAGE)
                .setName(Component.translatable(translationKey))
                .setTooltip(Component.translatable(translationKey + ".tooltip"))
                .setControl(option -> new SliderControl(option, minimum, maximum, interval, formatter))
                .setBinding(setter, getter)
                .setImpact(impact)
                .setEnabled(true)
                .build();
    }

    private static <T extends Enum<T>> OptionImpl<VoxyConfig, T> enumOption(String translationKey,
                                                                             Class<T> enumClass,
                                                                             Component[] names,
                                                                             BiConsumer<VoxyConfig, T> setter,
                                                                             Function<VoxyConfig, T> getter) {
        return OptionImpl.createBuilder(enumClass, STORAGE)
                .setName(Component.translatable(translationKey))
                .setTooltip(Component.translatable(translationKey + ".tooltip"))
                .setControl(option -> new CyclingControl<>(option, enumClass, names))
                .setBinding(setter, getter)
                .setImpact(OptionImpact.LOW)
                .setEnabled(true)
                .build();
    }
}
