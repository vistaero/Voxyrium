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
        addBoolean(builder, general, "ingestEnabled", "voxy.config.general.ingest");
        addInteger(builder, general, "serviceThreads", "voxy.config.general.serviceThreads", 1, 64);
        page.addOptionGroup(general);

        var rendering = builder.createOptionGroup().setName(Text.translatable("voxy.config.rendering"));
        addInteger(builder, rendering, "renderDistance", "voxy.config.general.renderDistance", 32, 2048);
        addInteger(builder, rendering, "renderQuality", "voxy.config.general.quality", 32, 512);
        addInteger(builder, rendering, "subDivisionSize", "voxy.config.general.subDivisionSize", 28, 256);
        page.addOptionGroup(rendering);
        options.addPage(page);
    }

    private static void addBoolean(ConfigBuilder builder, OptionGroupBuilder group,
                                   String field, String key) {
        if (find(field) == null) return;
        group.addOption(builder.createBooleanOption(Identifier.of("voxy", field))
                .setName(Text.translatable(key))
                .setTooltip(Text.translatable(key + ".tooltip"))
                .setImpact(OptionImpact.MEDIUM)
                .setBinding(value -> set(field, value), () -> getBoolean(field, false)));
    }

    private static void addInteger(ConfigBuilder builder, OptionGroupBuilder group,
                                   String field, String key, int min, int max) {
        if (find(field) == null) return;
        group.addOption(builder.createIntegerOption(Identifier.of("voxy", field))
                .setName(Text.translatable(key))
                .setTooltip(Text.translatable(key + ".tooltip"))
                .setImpact(OptionImpact.HIGH)
                .setRange(min, max, 1)
                .setBinding(value -> set(field, value), () -> getInt(field, min)));
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

    private static void set(String name, Object value) {
        try { find(name).set(VoxyConfig.CONFIG, value); } catch (Exception ignored) { }
    }
}
