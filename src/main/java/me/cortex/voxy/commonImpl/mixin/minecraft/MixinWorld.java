package me.cortex.voxy.commonImpl.mixin.minecraft;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.IVoxyWorld;
import net.minecraft.world.World;
import net.minecraft.world.block.NeighborUpdater;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(World.class)
public class MixinWorld implements IVoxyWorld {
    @Unique private WorldEngine voxyWorld;

    @Override
    public WorldEngine getWorldEngine() {
        return this.voxyWorld;
    }

    @Override
    public void setWorldEngine(WorldEngine engine) {
        if (engine != null && this.voxyWorld != null) {
            throw new IllegalStateException("WorldEngine not null");
        }
        this.voxyWorld = engine;
    }
}
