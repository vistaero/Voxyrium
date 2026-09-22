package me.cortex.voxy.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;

/** Opens Voxy's Sodium config page when selected from Mod Menu. */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            var page = (OptionPage) ConfigManager.CONFIG.getModOptions().stream()
                    .filter(options -> options.configId().equals("voxy"))
                    .findFirst().orElseThrow().pages().get(0);
            return VideoSettingsScreen.createScreen(parent, page);
        };
    }
}
