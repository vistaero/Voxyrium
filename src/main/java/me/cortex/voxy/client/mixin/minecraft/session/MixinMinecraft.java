package me.cortex.voxy.client.mixin.minecraft.session;

import me.cortex.voxy.client.ClientSessionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    @Inject(method = "setLevel", at = @At("TAIL"))
    private void voxy$injectWorldClose(ClientLevel level, CallbackInfo ci) {
        if (level == null && ClientSessionEvents.inSession) {
            ClientSessionEvents.sessionEnd();
        }
    }
}
