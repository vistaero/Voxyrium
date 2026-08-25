package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.util.function.LongConsumer;

import static me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_BLOCK_BIT;

public class SectionUpdateRouter implements ISectionWatcher {
    private static final int SLICES = 1<<3;
    public interface IChildUpdate {void accept(WorldSection section);}

    private final Long2ByteOpenHashMap[] slices = new Long2ByteOpenHashMap[SLICES];
    {
        for (int i = 0; i < this.slices.length; i++) {
            this.slices[i] = new Long2ByteOpenHashMap();
        }
    }

    private LongConsumer renderMeshGen;
    private IChildUpdate childUpdateCallback;

    public void setCallbacks(LongConsumer renderMeshGen, IChildUpdate childUpdateCallback) {
        if (this.renderMeshGen != null) {
            throw new IllegalStateException();
        }
        this.renderMeshGen = renderMeshGen;
        this.childUpdateCallback = childUpdateCallback;
    }

    public boolean watch(int lvl, int x, int y, int z, int types) {
        return this.watch(WorldEngine.getWorldSectionId(lvl, x, y, z), types);
    }

    public boolean watch(long position, int types) {
        var set = this.slices[getSliceIndex(position)];
        byte delta = 0;
        synchronized (set) {
            byte current = 0;
            if (set.containsKey(position)) {
                current = set.get(position);
            }
            delta = (byte) (current&types);
            current |= (byte) types;
            delta ^= (byte) (current&types);
            set.put(position, current);
        }
        if ((delta&UPDATE_TYPE_BLOCK_BIT)!=0) {
            //If we added it, immediately invoke for an update
            this.renderMeshGen.accept(position);
        }
        return delta!=0;
    }

    public boolean unwatch(int lvl, int x, int y, int z, int types) {
        return this.unwatch(WorldEngine.getWorldSectionId(lvl, x, y, z), types);
    }

    public boolean unwatch(long position, int types) {
        var set = this.slices[getSliceIndex(position)];
        synchronized (set) {
            if (!set.containsKey(position)) {
                throw new IllegalStateException("Section pos not in map!! " + WorldEngine.pprintPos(position));
            }
            byte current = set.get(position);
            byte delta = (byte) (current&types);
            current &= (byte) ~types;
            if (current == 0) {
                set.remove(position);
                return true;
            }
            set.put(position, current);
            return false;
        }
    }

    public int get(long position) {
        var set = this.slices[getSliceIndex(position)];
        synchronized (set) {
            return set.getOrDefault(position, (byte) 0);
        }
    }

    public void forwardEvent(WorldSection section, int type) {
        final long position = section.key;
        var set = this.slices[getSliceIndex(position)];
        byte types = 0;
        synchronized (set) {
            types = set.getOrDefault(position, (byte)0);
        }
        if (types!=0) {
            if ((type&WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT)!=0) {
                this.childUpdateCallback.accept(section);
            }
            if ((type& UPDATE_TYPE_BLOCK_BIT)!=0) {
                this.renderMeshGen.accept(section.key);
            }
        }
    }

    private static int getSliceIndex(long value) {
        value = (value ^ value >>> 30) * -4658895280553007687L;
        value = (value ^ value >>> 27) * -7723592293110705685L;
        return (int) ((value ^ value >>> 31)&(SLICES-1));
    }
}
