package me.cortex.voxy.client.config;

import com.google.common.collect.ImmutableList;
import net.caffeinemc.mods.sodium.client.gui.options.OptionGroup;
import net.caffeinemc.mods.sodium.client.gui.options.OptionImpl;
import net.caffeinemc.mods.sodium.client.gui.options.OptionImpact;
import net.caffeinemc.mods.sodium.client.gui.options.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.options.OptionFlag;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlValueFormatter;
import net.caffeinemc.mods.sodium.client.gui.options.control.CyclingControl;
import net.caffeinemc.mods.sodium.client.gui.options.control.SliderControl;
import net.caffeinemc.mods.sodium.client.gui.options.control.TickBoxControl;
import net.caffeinemc.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.text.Text;

import java.lang.reflect.Field;

/** Sodium options page used by the 1.21-1.21.4 compatibility sources. */
public final class LegacySodiumConfigMenu {
    private static OptionPage page;
    private static final OptionStorage<VoxyConfig> STORAGE = new OptionStorage<>() {
        @Override public VoxyConfig getData() { return VoxyConfig.CONFIG; }
        @Override public void save() { VoxyConfig.CONFIG.save(); }
    };

    private LegacySodiumConfigMenu() {}

    public static OptionPage createPage() {
        var general = OptionGroup.createBuilder()
                .add(booleanOption("voxy.config.general.enabled", "enabled"))
                .add(intOption("voxy.config.general.serviceThreads", 1, 64,
                        ControlValueFormatter.number(), OptionImpact.MEDIUM,
                        "serviceThreads", "renderThreads"))
                .add(booleanOption("voxy.config.general.useSodiumBuilder", "dontUseSodiumBuilderThreads", true))
                .add(booleanOption("voxy.config.general.ingest", "ingestEnabled"));

        var rendering = OptionGroup.createBuilder()
                .add(booleanOption("voxy.config.general.rendering", "enableRendering"))
                .add(intOption("voxy.config.general.subDivisionSize", 28, 512,
                        ControlValueFormatter.number(), OptionImpact.HIGH,
                        "subDivisionSize", "renderQuality", "qualityScale"))
                .add(distanceOption())
                .add(enumOption("voxy.config.general.environmental_fog", FogMode.class, "fogMode"))
                .add(enumOption("voxy.config.general.ssao_mode", SSAOMode.class, "ssaoMode"));

        return page = new OptionPage(Text.translatable("voxy.config.title"),
                ImmutableList.of(general.build(), rendering.build()));
    }

    public static OptionPage getPage() {
        return page != null ? page : createPage();
    }

    private static OptionImpl<VoxyConfig, Boolean> booleanOption(String key, String field) {
        return booleanOption(key, field, false);
    }

    private static OptionImpl<VoxyConfig, Boolean> booleanOption(String key, String field, boolean inverted) {
        boolean renderingToggle = field.equals("enableRendering");
        return OptionImpl.createBuilder(boolean.class, STORAGE)
                .setName(Text.translatable(key))
                .setTooltip(Text.translatable(key + ".tooltip"))
                .setControl(TickBoxControl::new)
                .setEnabled(() -> !renderingToggle || LegacyVoxySupport.isRenderingSupported())
                .setBinding((config, value) -> {
                    if (!renderingToggle || LegacyVoxySupport.isRenderingSupported()) {
                        set(config, field, inverted ? !value : value);
                    }
                }, config -> renderingToggle && !LegacyVoxySupport.isRenderingSupported()
                        ? false
                        : inverted != get(config, field, false))
                .setImpact(OptionImpact.MEDIUM)
                .build();
    }

    private static OptionImpl<VoxyConfig, Integer> intOption(String key, int min, int max,
                                                              ControlValueFormatter formatter,
                                                              OptionImpact impact, String... fields) {
        return OptionImpl.createBuilder(int.class, STORAGE)
                .setName(Text.translatable(key))
                .setTooltip(Text.translatable(key + ".tooltip"))
                .setControl(option -> new SliderControl(option, min, max, 1, formatter))
                .setBinding((config, value) -> setNumber(config, fields, value),
                        config -> getNumber(config, fields, min))
                .setImpact(impact)
                .build();
    }

    private static OptionImpl<VoxyConfig, Integer> distanceOption() {
        return OptionImpl.createBuilder(int.class, STORAGE)
                .setName(Text.translatable("voxy.config.general.renderDistance"))
                .setTooltip(Text.translatable("voxy.config.general.renderDistance.tooltip"))
                .setControl(option -> new SliderControl(option, 10, 2048, 1,
                        ControlValueFormatter.translateVariable("options.chunks")))
                .setBinding((config, value) -> {
                    if (find("renderDistance") != null) {
                        set(config, "renderDistance", value);
                    } else {
                        setNumber(config, new String[]{"sectionRenderDistance"}, Math.max(1, Math.round(value / 32.0f)));
                    }
                }, config -> find("renderDistance") != null
                        ? getNumber(config, new String[]{"renderDistance"}, 128)
                        : getNumber(config, new String[]{"sectionRenderDistance"}, 16) * 32)
                .setImpact(OptionImpact.HIGH)
                .build();
    }

    private static <E extends Enum<E>> OptionImpl<VoxyConfig, E> enumOption(
            String key, Class<E> type, String field) {
        Text[] names = new Text[type.getEnumConstants().length];
        for (int i = 0; i < names.length; i++) {
            names[i] = Text.translatable(key + "." + type.getEnumConstants()[i].name().toLowerCase());
        }
        return OptionImpl.createBuilder(type, STORAGE)
                .setName(Text.translatable(key))
                .setTooltip(Text.translatable(key + ".tooltip"))
                .setControl(option -> new CyclingControl<>(option, type, names))
                .setBinding((config, value) -> {
                            set(config, field, value.name().toLowerCase());
                            if (value instanceof FogMode fog) {
                                set(config, "useEnvironmentalFog", fog == FogMode.FOG_AND_FADE || fog == FogMode.FOG);
                                set(config, "renderVanillaFog", false);
                            }
                        },
                        config -> enumValue(type, getString(config, field, type.getEnumConstants()[0].name())))
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .setImpact(OptionImpact.MEDIUM)
                .build();
    }

    private static Field find(String name) {
        try { return VoxyConfig.class.getField(name); }
        catch (NoSuchFieldException ignored) { return null; }
    }

    private static boolean get(VoxyConfig config, String name, boolean fallback) {
        try { return find(name).getBoolean(config); } catch (Exception ignored) { return fallback; }
    }

    private static int get(VoxyConfig config, String name, int fallback) {
        try { return find(name).getInt(config); } catch (Exception ignored) { return fallback; }
    }

    private static int getNumber(VoxyConfig config, String[] names, int fallback) {
        for (String name : names) {
            try {
                Field field = find(name);
                if (field != null) return Math.round(((Number) field.get(config)).floatValue());
            } catch (Exception ignored) { }
        }
        return fallback;
    }

    private static void setNumber(VoxyConfig config, String[] names, int value) {
        for (String name : names) {
            try {
                Field field = find(name);
                if (field == null) continue;
                if (field.getType() == float.class) field.setFloat(config, value);
                else field.setInt(config, value);
                if (name.equals("serviceThreads")) {
                    set(config, "ingestThreads", value);
                    set(config, "savingThreads", value);
                    set(config, "renderThreads", value);
                }
                return;
            } catch (Exception ignored) { }
        }
    }

    private static String getString(VoxyConfig config, String name, String fallback) {
        try {
            Object value = find(name).get(config);
            return value instanceof String string ? string : fallback;
        } catch (Exception ignored) { return fallback; }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try { return Enum.valueOf(type, value.toUpperCase()); }
        catch (Exception ignored) { return type.getEnumConstants()[0]; }
    }

    private static void set(VoxyConfig config, String name, Object value) {
        try {
            Field field = find(name);
            if (field != null) field.set(config, value);
        } catch (Exception ignored) { }
    }

    private enum FogMode { FOG_AND_FADE, FOG, FADE, OFF }
    private enum SSAOMode { AUTO, BASIC, BETTER, BEST }
}
