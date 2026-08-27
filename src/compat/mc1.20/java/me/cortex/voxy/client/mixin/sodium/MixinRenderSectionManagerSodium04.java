package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public final class MixinRenderSectionManagerSodium04 {
    @Unique
    private static final boolean VOXY$BOBBY_INSTALLED = FabricLoader.getInstance().isModLoaded("bobby");

    @Shadow
    private ClientLevel world;

    @Unique private static int voxy$ingestAttempts;
    @Unique private static int voxy$ingestAccepted;

    @Inject(method = "onChunkLightAdded", at = @At("TAIL"))
    private void voxy$ingestWhenLightingIsReady(int x, int z, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.ingestEnabled && this.world != null) {
            boolean accepted = VoxelIngestService.tryAutoIngestChunk(this.world.getChunk(x, z));
            int attempts = ++voxy$ingestAttempts;
            if (accepted) {
                voxy$ingestAccepted++;
            }
            if (attempts == 1 || attempts == 32 || (attempts & 255) == 0) {
                Logger.info("Sodium 0.4 light-ready ingest diagnostics: attempts=" + attempts
                        + ", accepted=" + voxy$ingestAccepted
                        + ", rejected=" + (attempts - voxy$ingestAccepted)
                        + ", lastChunk=" + x + "," + z);
            }
        }
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void voxy$ingestBeforeRemoval(int x, int z, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.ingestEnabled && !VOXY$BOBBY_INSTALLED) {
            VoxelIngestService.tryAutoIngestChunk(this.world.getChunk(x, z));
        }
    }
}
