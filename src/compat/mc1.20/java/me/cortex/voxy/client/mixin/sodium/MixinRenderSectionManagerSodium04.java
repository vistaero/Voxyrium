package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.passes.BlockRenderPassManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSectionManager.class, remap = false)
public final class MixinRenderSectionManagerSodium04 {
    @Unique
    private static final boolean VOXY$BOBBY_INSTALLED = FabricLoader.getInstance().isModLoaded("bobby");

    @Unique
    private ClientLevel voxy$world;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$captureWorld(SodiumWorldRenderer worldRenderer,
                                   BlockRenderPassManager renderPassManager,
                                   ClientLevel world, int renderDistance,
                                   CommandList commandList, CallbackInfo ci) {
        this.voxy$world = world;
    }

    @Inject(method = "onChunkAdded", at = @At("HEAD"))
    private void voxy$ingestOnAdd(int x, int z, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.ingestEnabled && this.voxy$world != null) {
            VoxelIngestService.tryAutoIngestChunk(this.voxy$world.getChunk(x, z));
        }
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void voxy$ingestBeforeRemoval(int x, int z, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.ingestEnabled && !VOXY$BOBBY_INSTALLED) {
            VoxelIngestService.tryAutoIngestChunk(this.voxy$world.getChunk(x, z));
        }
    }
}
