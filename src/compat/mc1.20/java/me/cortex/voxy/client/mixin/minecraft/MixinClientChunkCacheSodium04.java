package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium 0.4 receives its removal notification after Minecraft has already
 * started dropping the chunk. Capture the real cached chunk at the source,
 * while its block and lighting data are still stable.
 */
@Mixin(ClientChunkCache.class)
public class MixinClientChunkCacheSodium04 implements ICheekyClientChunkCache {
    @Shadow
    private volatile ClientChunkCache.Storage storage;

    @Unique private static int voxy$dropCount;
    @Unique private static int voxy$chunksFound;
    @Unique private static int voxy$ingestAccepted;

    @Override
    public @Nullable LevelChunk voxy$cheekyGetChunk(int x, int z) {
        var chunk = this.storage.getChunk(this.storage.getIndex(x, z));
        if (chunk != null && chunk.getPos().x == x && chunk.getPos().z == z) {
            return chunk;
        }
        return null;
    }

    @Inject(method = "drop", at = @At("HEAD"))
    private void voxy$ingestBeforeMinecraftDrop(int x, int z, CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.ingestEnabled) {
            return;
        }

        int drops = ++voxy$dropCount;
        var chunk = this.voxy$cheekyGetChunk(x, z);
        if (chunk != null) {
            voxy$chunksFound++;
            if (VoxelIngestService.tryAutoIngestChunk(chunk)) {
                voxy$ingestAccepted++;
            }
        }

        if (drops == 1 || drops == 32 || (drops & 255) == 0) {
            Logger.info("Minecraft 1.20 pre-drop ingest diagnostics: drops=" + drops
                    + ", chunksFound=" + voxy$chunksFound
                    + ", accepted=" + voxy$ingestAccepted
                    + ", rejected=" + (voxy$chunksFound - voxy$ingestAccepted)
                    + ", missing=" + (drops - voxy$chunksFound)
                    + ", lastChunk=" + x + "," + z);
        }
    }
}
