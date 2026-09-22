package me.cortex.voxy.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI;
import net.minecraft.client.gui.screen.Screen;

import java.lang.reflect.Field;

/** Opens Voxy's page inside Sodium when selected from Mod Menu. */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            var screen = (SodiumOptionsGUI) SodiumOptionsGUI.createScreen(parent);
            try {
                Field currentPage = SodiumOptionsGUI.class.getDeclaredField("currentPage");
                currentPage.setAccessible(true);
                currentPage.set(screen, LegacySodiumConfigMenu.getPage());
            } catch (ReflectiveOperationException ignored) { }
            return screen;
        };
    }
}
