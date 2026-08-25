package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.config.LegacySodiumConfigMenu;
import me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(SodiumOptionsGUI.class)
public abstract class MixinSodiumOptionsGUI {
    @Shadow
    @Final
    @Mutable
    private List<OptionPage> pages;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void voxy$appendOptionsPage(Screen previousScreen, CallbackInfo ci) {
        var extendedPages = new ArrayList<>(this.pages);
        extendedPages.add(LegacySodiumConfigMenu.createPage());
        this.pages = extendedPages;
    }
}
