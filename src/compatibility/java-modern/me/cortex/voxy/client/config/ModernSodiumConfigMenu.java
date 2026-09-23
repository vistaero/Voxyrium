package me.cortex.voxy.client.config;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.lang.reflect.Field;

/** Sodium 0.8 config API integration used by Minecraft 1.21.1. */
public final class ModernSodiumConfigMenu implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        var options = builder.registerModOptions("voxy", "Voxy", "1.21.1");
        var page = builder.createOptionPage().setName(Text.translatable("voxy.config.title"));
        var general = builder.createOptionGroup().setName(Text.translatable("voxy.config.general"));
        addBoolean(builder, general, "enabled", "voxy.config.general.enabled");
        addInteger(builder, general, "serviceThreads", "voxy.config.general.serviceThreads", 1, 64);
        addBoolean(builder, general, "dontUseSodiumBuilderThreads", "voxy.config.general.useSodiumBuilder", true);
        addBoolean(builder, general, "ingestEnabled", "voxy.config.general.ingest");
        page.addOptionGroup(general);

        var rendering = builder.createOptionGroup().setName(Text.translatable("voxy.config.rendering"));
        addBoolean(builder, rendering, "enableRendering", "voxy.config.general.rendering");
        addInteger(builder, rendering, new String[]{"subDivisionSize", "renderQuality", "qualityScale"},
                "voxy.config.general.subDivisionSize", 28, 512);
        addInteger(builder, rendering, new String[]{"renderDistance", "sectionRenderDistance"},
                "voxy.config.general.renderDistance", 10, 2048);
        addEnum(builder, rendering, "fogMode", "voxy.config.general.environmental_fog", FogMode.class);
        addEnum(builder, rendering, "ssaoMode", "voxy.config.general.ssao_mode", SSAOMode.class);
        page.addOptionGroup(rendering);
        options.addPage(page);
    }

    private static void addBoolean(ConfigBuilder builder, OptionGroupBuilder group,
                                   String field, String key) {
        addBoolean(builder, group, field, key, false);
    }

    private static void addBoolean(ConfigBuilder builder, OptionGroupBuilder group,
                                   String field, String key, boolean inverted) {
        boolean renderingToggle = field.equals("enableRendering");
        group.addOption(builder.createBooleanOption(Identifier.of("voxy", field))
                .setName(Text.translatable(key))
                .setTooltip(Text.translatable(key + ".tooltip"))
                .setImpact(OptionImpact.MEDIUM)
                .setEnabled(!renderingToggle || LegacyVoxySupport.isRenderingSupported())
                .setDefaultValue(renderingToggle && !LegacyVoxySupport.isRenderingSupported()
                        ? false
                        : inverted != getBoolean(field, false))
                .setStorageHandler(VoxyConfig.CONFIG::save)
                .setBinding(value -> {
                    if (!renderingToggle || LegacyVoxySupport.isRenderingSupported()) {
                        set(field, inverted ? !value : value);
                    }
                }, () -> renderingToggle && !LegacyVoxySupport.isRenderingSupported()
                        ? false
                        : inverted != getBoolean(field, false)));
    }

    private static void addInteger(ConfigBuilder builder, OptionGroupBuilder group,
                                   String field, String key, int min, int max) {
        addInteger(builder, group, new String[]{field}, key, min, max);
    }

    private static void addInteger(ConfigBuilder builder, OptionGroupBuilder group,
                                   String[] fields, String key, int min, int max) {
        group.addOption(builder.createIntegerOption(Identifier.of("voxy", fields[0]))
                .setName(Text.translatable(key))
                .setTooltip(Text.translatable(key + ".tooltip"))
                .setImpact(OptionImpact.HIGH)
                .setRange(min, max, 1)
                .setValueFormatter(value -> Text.literal(Integer.toString(value)))
                .setDefaultValue(getNumber(fields, min))
                .setStorageHandler(VoxyConfig.CONFIG::save)
                .setBinding(value -> setNumber(fields, value), () -> getNumber(fields, min)));
    }

    private static <E extends Enum<E>> void addEnum(ConfigBuilder builder, OptionGroupBuilder group,
                                                     String field, String key, Class<E> type) {
        E defaultValue = enumValue(type, getString(field, type.getEnumConstants()[0].name()));
        group.addOption(builder.createEnumOption(Identifier.of("voxy", field), type)
                .setName(Text.translatable(key))
                .setTooltip(Text.translatable(key + ".tooltip"))
                .setElementNameProvider(value -> Text.translatable(key + "." + value.name().toLowerCase()))
                .setImpact(OptionImpact.MEDIUM)
                .setDefaultValue(defaultValue)
                .setStorageHandler(VoxyConfig.CONFIG::save)
                .setBinding(value -> set(field, value.name().toLowerCase()),
                        () -> enumValue(type, getString(field, defaultValue.name()))));
    }

    private static Field find(String name) {
        try { return VoxyConfig.class.getField(name); }
        catch (NoSuchFieldException ignored) { return null; }
    }

    private static boolean getBoolean(String name, boolean fallback) {
        try { return find(name).getBoolean(VoxyConfig.CONFIG); } catch (Exception ignored) { return fallback; }
    }

    private static int getInt(String name, int fallback) {
        try { return find(name).getInt(VoxyConfig.CONFIG); } catch (Exception ignored) { return fallback; }
    }

    private static int getNumber(String[] names, int fallback) {
        for (String name : names) {
            try {
                Field field = find(name);
                if (field != null) return Math.round(((Number) field.get(VoxyConfig.CONFIG)).floatValue());
            } catch (Exception ignored) { }
        }
        return fallback;
    }

    private static void setNumber(String[] names, int value) {
        for (String name : names) {
            try {
                Field field = find(name);
                if (field == null) continue;
                if (field.getType() == float.class) field.setFloat(VoxyConfig.CONFIG, value);
                else field.setInt(VoxyConfig.CONFIG, value);
                return;
            } catch (Exception ignored) { }
        }
    }

    private static String getString(String name, String fallback) {
        try {
            Object value = find(name).get(VoxyConfig.CONFIG);
            return value instanceof String string ? string : fallback;
        } catch (Exception ignored) { return fallback; }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try { return Enum.valueOf(type, value.toUpperCase()); }
        catch (Exception ignored) { return type.getEnumConstants()[0]; }
    }

    private static void set(String name, Object value) {
        try {
            Field field = find(name);
            if (field != null) field.set(VoxyConfig.CONFIG, value);
        } catch (Exception ignored) { }
    }

    private enum FogMode { FOG_AND_FADE, FOG, FADE, OFF }
    private enum SSAOMode { AUTO, BASIC, BETTER, BEST }
}
