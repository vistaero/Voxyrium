package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {
    @Shadow @Final private ClientWorld world;

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void injectIngest(int x, int z, CallbackInfo ci) {
        //TODO: Am not quite sure if this is right
        var instance = VoxyCommon.getInstance();
        if (instance != null && VoxyConfig.CONFIG.ingestEnabled) {
            var chunk = this.world.getChunk(x, z);
            var world = chunk.getWorld();
            if (world instanceof ClientWorld cw) {
                var engine = ((VoxyClientInstance)instance).getOrMakeRenderWorld(cw);
                if (engine != null) {
                    instance.getIngestService().enqueueIngest(engine, chunk);
                }
            }
        }
    }
}
