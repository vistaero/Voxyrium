package me.cortex.voxy.commonImpl.mixin.chunky;

import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.popcraft.chunky.platform.FabricWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(value = FabricWorld.class, remap = false)
public final class MixinFabricWorld {
    @Shadow
    @Final
    private ServerLevel serverWorld;

    @Inject(method = "getChunkAtAsync", at = @At("RETURN"), cancellable = true)
    private void voxy$captureGeneratedChunk(int x, int z,
                                             CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        cir.setReturnValue(cir.getReturnValue().thenRunAsync(() -> {
            var instance = VoxyCommon.getInstance();
            if (instance == null) {
                return;
            }
            try {
                LevelChunk chunk = this.serverWorld.getChunk(x, z);
                var engine = WorldIdentifier.ofEngineNullable(this.serverWorld);
                if (engine != null) {
                    instance.getIngestService().enqueueIngest(engine, chunk);
                }
            } catch (Exception ignored) {
            }
        }, this.serverWorld.getServer()));
    }
}
