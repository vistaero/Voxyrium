package me.cortex.voxy.client.config;

import com.google.common.collect.ImmutableList;
import net.caffeinemc.mods.sodium.client.gui.options.OptionGroup;
import net.caffeinemc.mods.sodium.client.gui.options.OptionImpl;
import net.caffeinemc.mods.sodium.client.gui.options.OptionImpact;
import net.caffeinemc.mods.sodium.client.gui.options.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlValueFormatter;
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
                .add(booleanOption("voxy.config.general.ingest", "ingestEnabled"));

        var rendering = OptionGroup.createBuilder();
        addIntIfPresent(general, "voxy.config.general.serviceThreads", "serviceThreads", 1, 64,
                ControlValueFormatter.number(), OptionImpact.MEDIUM);
        addIntIfPresent(rendering, "voxy.config.general.renderDistance", "renderDistance", 32, 2048,
                ControlValueFormatter.translateVariable("options.chunks"), OptionImpact.HIGH);
        addIntIfPresent(rendering, "voxy.config.general.quality", "renderQuality", 32, 512,
                ControlValueFormatter.number(), OptionImpact.HIGH);
        addIntIfPresent(rendering, "voxy.config.general.subDivisionSize", "subDivisionSize", 28, 256,
                ControlValueFormatter.number(), OptionImpact.HIGH);

        return page = new OptionPage(Text.translatable("voxy.config.title"),
                ImmutableList.of(general.build(), rendering.build()));
    }

    public static OptionPage getPage() {
        return page != null ? page : createPage();
    }

    private static OptionImpl<VoxyConfig, Boolean> booleanOption(String key, String field) {
        return OptionImpl.createBuilder(boolean.class, STORAGE)
                .setName(Text.translatable(key))
                .setTooltip(Text.translatable(key + ".tooltip"))
                .setControl(TickBoxControl::new)
                .setBinding((config, value) -> set(config, field, value), config -> get(config, field, false))
                .setImpact(OptionImpact.MEDIUM)
                .build();
    }

    private static void addIntIfPresent(OptionGroup.Builder builder, String key, String field,
                                        int min, int max, ControlValueFormatter formatter,
                                        OptionImpact impact) {
        if (find(field) == null) return;
        builder.add(OptionImpl.createBuilder(int.class, STORAGE)
                .setName(Text.translatable(key))
                .setTooltip(Text.translatable(key + ".tooltip"))
                .setControl(option -> new SliderControl(option, min, max, 1, formatter))
                .setBinding((config, value) -> set(config, field, value), config -> get(config, field, min))
                .setImpact(impact)
                .build());
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

    private static void set(VoxyConfig config, String name, Object value) {
        try { find(name).set(config, value); } catch (Exception ignored) { }
    }
}
