package me.cortex.voxy.commonImpl.mixin.chunky;

import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;
import org.popcraft.chunky.platform.FabricWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(value = FabricWorld.class, remap = false)
public class MixinFabricWorld {
    @Shadow @Final private ServerWorld serverWorld;

    @Inject(method = "getChunkAtAsync", at = @At("RETURN"), cancellable = true)
    private void captureGeneratedChunk(int x, int z, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        cir.setReturnValue(cir.getReturnValue().thenRunAsync(() -> {
            var voxyInstance = VoxyCommon.getInstance();
            if (voxyInstance == null) {
                return;
            }

            try {
                WorldChunk chunk = this.serverWorld.getChunk(x, z);
                voxyInstance.getIngestService().enqueueIngest(chunk, true);
            } catch (Exception ignored) {
            }
        }, this.serverWorld.getServer()));
    }
}
