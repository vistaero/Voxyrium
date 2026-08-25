package me.cortex.voxy.common.world;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.util.TrackedObject;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;

import java.util.List;
public class WorldEngine {
    public static final int MAX_LOD_LAYER = 4;

    public static final int UPDATE_TYPE_BLOCK_BIT = 1;
    public static final int UPDATE_TYPE_CHILD_EXISTENCE_BIT = 2;
    public static final int UPDATE_FLAGS = UPDATE_TYPE_BLOCK_BIT | UPDATE_TYPE_CHILD_EXISTENCE_BIT;

    public interface ISectionChangeCallback {void accept(WorldSection section, int updateFlags);}
    public interface ISectionSaveCallback {void save(WorldEngine engine, WorldSection section);}

    private final TrackedObject thisTracker = TrackedObject.createTrackedObject(this);

    public final SectionStorage storage;
    private final Mapper mapper;
    private final ActiveSectionTracker sectionTracker;
    private ISectionChangeCallback dirtyCallback;
    private ISectionSaveCallback saveCallback;
    private final int maxMipLevels;
    private volatile boolean isLive = true;

    public void setDirtyCallback(ISectionChangeCallback callback) {
        this.dirtyCallback = callback;
    }

    public void setSaveCallback(ISectionSaveCallback callback) {
        this.saveCallback = callback;
    }

    public Mapper getMapper() {return this.mapper;}
    public boolean isLive() {return this.isLive;}
    public WorldEngine(SectionStorage storage, int cacheCount) {
        this(storage, MAX_LOD_LAYER+1, cacheCount);//The +1 is because its from 1 not from 0
    }

    private WorldEngine(SectionStorage storage, int maxMipLayers, int cacheCount) {
        this.maxMipLevels = maxMipLayers;
        this.storage = storage;
        this.mapper = new Mapper(this.storage);
        //5 cache size bits means that the section tracker has 32 separate maps that it uses
        this.sectionTracker = new ActiveSectionTracker(10, storage::loadSection, cacheCount, this);
    }

    public WorldSection acquireIfExists(int lvl, int x, int y, int z) {
        if (!this.isLive) throw new IllegalStateException("World is not live");
        return this.sectionTracker.acquire(lvl, x, y, z, true);
    }

    public WorldSection acquire(int lvl, int x, int y, int z) {
        if (!this.isLive) throw new IllegalStateException("World is not live");
        return this.sectionTracker.acquire(lvl, x, y, z, false);
    }

    public WorldSection acquire(long pos) {
        if (!this.isLive) throw new IllegalStateException("World is not live");
        return this.sectionTracker.acquire(pos, false);
    }

    public WorldSection acquireIfExists(long pos) {
        if (!this.isLive) throw new IllegalStateException("World is not live");
        return this.sectionTracker.acquire(pos, true);
    }

    //TODO: Fixme/optimize, cause as the lvl gets higher, the size of x,y,z gets smaller so i can dynamically compact the format
    // depending on the lvl, which should optimize colisions and whatnot
    public static long getWorldSectionId(int lvl, int x, int y, int z) {
        return ((long)lvl<<60)|((long)(y&0xFF)<<52)|((long)(z&((1<<24)-1))<<28)|((long)(x&((1<<24)-1))<<4);//NOTE: 4 bits spare for whatever
    }

    public static int getLevel(long id) {
        return (int) ((id>>60)&0xf);
    }
    public static int getX(long id) {
        return (int) ((id<<36)>>40);
    }

    public static int getY(long id) {
        return (int) ((id<<4)>>56);
    }

    public static int getZ(long id) {
        return (int) ((id<<12)>>40);
    }

    public static String pprintPos(long pos) {
        return getLevel(pos)+"@["+getX(pos)+", "+getY(pos)+", " + getZ(pos)+"]";
    }

    //Marks a section as dirty, enqueuing it for saving and or render data rebuilding
    public void markDirty(WorldSection section) {
        this.markDirty(section, UPDATE_FLAGS);
    }

    public void markDirty(WorldSection section, int changeState) {
        if (!this.isLive) throw new IllegalStateException("World is not live");
        if (this.dirtyCallback != null) {
            this.dirtyCallback.accept(section, changeState);
        }
        if (this.saveCallback != null) {
            this.saveCallback.save(this, section);
        }
    }


    //TODO: move this to auxilery class  so that it can take into account larger than 4 mip levels
    //Executes an update to the world and automatically updates all the parent mip layers up to level 4 (e.g. where 1 chunk section is 1 block big)

    //NOTE: THIS RUNS ON THE THREAD IT WAS EXECUTED ON, when this method exits, the calling method may assume that VoxelizedSection is no longer needed
    public void insertUpdate(VoxelizedSection section) {//TODO: add a bitset of levels to update and if it should force update
        if (!this.isLive) throw new IllegalStateException("World is not live");
        boolean shouldCheckEmptiness = false;
        WorldSection previousSection = null;

        for (int lvl = 0; lvl < this.maxMipLevels; lvl++) {
            var worldSection = this.acquire(lvl, section.x >> (lvl + 1), section.y >> (lvl + 1), section.z >> (lvl + 1));

            int emptinessStateChange = 0;
            //Propagate the child existence state of the previous iteration to this section
            if (lvl != 0 && shouldCheckEmptiness) {
                emptinessStateChange = worldSection.updateEmptyChildState(previousSection);
                //We kept the previous section acquired, so we need to release it
                previousSection.release();
                previousSection = null;
            }


            int msk = (1<<(lvl+1))-1;
            int bx = (section.x&msk)<<(4-lvl);
            int by = (section.y&msk)<<(4-lvl);
            int bz = (section.z&msk)<<(4-lvl);

            int nonAirCountDelta = 0;
            boolean didStateChange = false;


            {//Do a bunch of funny math
                int baseVIdx = VoxelizedSection.getBaseIndexForLevel(lvl);
                int baseSec = bx | (bz << 5) | (by << 10);
                int secMsk = 0xF >> lvl;
                secMsk |= (secMsk << 5) | (secMsk << 10);
                var secD = worldSection.data;
                for (int i = 0; i <= 0xFFF >> (lvl * 3); i++) {
                    int secIdx = Integer.expand(i, secMsk)+baseSec;
                    long newId = section.section[baseVIdx+i];
                    long oldId = secD[secIdx]; secD[secIdx] = newId;
                    nonAirCountDelta += Mapper.isAir(oldId) == Mapper.isAir(newId) ? 0 : (Mapper.isAir(newId) ? -1 : 1);
                    didStateChange |= newId != oldId;
                }
            }

            if (nonAirCountDelta != 0) {
                worldSection.addNonEmptyBlockCount(nonAirCountDelta);
                if (lvl == 0) {
                    emptinessStateChange = worldSection.updateLvl0State() ? 2 : 0;
                }
            }

            if (didStateChange||(emptinessStateChange!=0)) {
                this.markDirty(worldSection, (didStateChange?UPDATE_TYPE_BLOCK_BIT:0)|(emptinessStateChange!=0?UPDATE_TYPE_CHILD_EXISTENCE_BIT:0));
            }

            //Need to release the section after using it
            if (didStateChange||(emptinessStateChange==2)) {
                if (emptinessStateChange==2) {
                    //Major state emptiness change, bubble up
                    shouldCheckEmptiness = true;
                    //Dont release the section, it will be released on the next loop
                    previousSection = worldSection;
                } else {
                    //Propagate up without state change
                    shouldCheckEmptiness = false;
                    previousSection = null;
                    worldSection.release();
                }
            } else {
                //If nothing changed just need to release, dont need to update parent mips
                worldSection.release();
                break;
            }
        }

        if (previousSection != null) {
            previousSection.release();
        }
    }

    public void addDebugData(List<String> debug) {
        debug.add("ACC/SCC: " + this.sectionTracker.getLoadedCacheCount()+"/"+this.sectionTracker.getSecondaryCacheSize());//Active cache count, Secondary cache counts
    }

    public int getActiveSectionCount() {
        return this.sectionTracker.getLoadedCacheCount();
    }


    public void free() {
        //Cannot free while there are loaded sections
        if (this.sectionTracker.getLoadedCacheCount() != 0) {
            throw new IllegalStateException();
        }

        this.thisTracker.free();
        this.isLive = false;
        try {this.mapper.close();} catch (Exception e) {Logger.error(e);}
        try {this.storage.flush();} catch (Exception e) {Logger.error(e);}
        //Shutdown in this order to preserve as much data as possible
        try {this.storage.close();} catch (Exception e) {Logger.error(e);}
    }
}
