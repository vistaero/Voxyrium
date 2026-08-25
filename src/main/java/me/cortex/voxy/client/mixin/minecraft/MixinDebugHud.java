package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.DebugHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(DebugHud.class)
public class MixinDebugHud {
    @Inject(method = "getRightText", at = @At("TAIL"))
    private void injectDebug(CallbackInfoReturnable<List<String>> cir) {
        var ret = cir.getReturnValue();
        var instance = VoxyCommon.getInstance();
        if (instance != null) {
            ret.add("");
            ret.add("");
            instance.addDebug(ret);
        }
        var renderer = ((IGetVoxyRenderSystem) MinecraftClient.getInstance().worldRenderer).getVoxyRenderSystem();
        if (renderer != null) {
            renderer.addDebugInfo(ret);
        }
    }
}
