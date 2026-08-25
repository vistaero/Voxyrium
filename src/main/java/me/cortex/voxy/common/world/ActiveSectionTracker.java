package me.cortex.voxy.common.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.common.util.VolatileHolder;
import me.cortex.voxy.common.world.other.Mapper;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

public class ActiveSectionTracker {

    //Deserialize into the supplied section, returns true on success, false on failure
    public interface SectionLoader {int load(WorldSection section);}

    //Loaded section world cache, TODO: get rid of VolatileHolder and use something more sane

    private final Long2ObjectOpenHashMap<VolatileHolder<WorldSection>>[] loadedSectionCache;
    private final ReentrantLock[] locks;
    private final SectionLoader loader;

    private final int maxLRUSectionPerSlice;
    private final Long2ObjectLinkedOpenHashMap<WorldSection>[] lruSecondaryCache;//TODO: THIS NEEDS TO BECOME A GLOBAL STATIC CACHE
    @Nullable
    public final WorldEngine engine;

    public ActiveSectionTracker(int numSlicesBits, SectionLoader loader, int cacheSize) {
        this(numSlicesBits, loader, cacheSize, null);
    }

    @SuppressWarnings("unchecked")
    public ActiveSectionTracker(int numSlicesBits, SectionLoader loader, int cacheSize, WorldEngine engine) {
        this.engine = engine;

        this.loader = loader;
        this.loadedSectionCache = new Long2ObjectOpenHashMap[1<<numSlicesBits];
        this.lruSecondaryCache = new Long2ObjectLinkedOpenHashMap[1<<numSlicesBits];
        this.locks = new ReentrantLock[1<<numSlicesBits];
        this.maxLRUSectionPerSlice = (cacheSize+(1<<numSlicesBits)-1)/(1<<numSlicesBits);
        for (int i = 0; i < this.loadedSectionCache.length; i++) {
            this.loadedSectionCache[i] = new Long2ObjectOpenHashMap<>(1024);
            this.lruSecondaryCache[i] = new Long2ObjectLinkedOpenHashMap<>(this.maxLRUSectionPerSlice);
            this.locks[i] = new ReentrantLock();
        }
    }

    public WorldSection acquire(int lvl, int x, int y, int z, boolean nullOnEmpty) {
        return this.acquire(WorldEngine.getWorldSectionId(lvl, x, y, z), nullOnEmpty);
    }

    public WorldSection acquire(long key, boolean nullOnEmpty) {
        int index = this.getCacheArrayIndex(key);
        var cache = this.loadedSectionCache[index];
        final var lock = this.locks[index];
        VolatileHolder<WorldSection> holder = null;
        boolean isLoader = false;
        WorldSection section;

        lock.lock();
        {
            holder = cache.get(key);
            if (holder == null) {
                holder = new VolatileHolder<>();
                cache.put(key, holder);
                isLoader = true;
            }
            section = holder.obj;
            if (section != null) {
                section.acquire();
                lock.unlock();
                return section;
            }
            if (isLoader) {
                section = this.lruSecondaryCache[index].remove(key);
            }
        }
        lock.unlock();

        //If this thread was the one to create the reference then its the thread to load the section
        if (isLoader) {
            int status = 0;
            if (section == null) {//Secondary cache miss
                section = new WorldSection(WorldEngine.getLevel(key),
                        WorldEngine.getX(key),
                        WorldEngine.getY(key),
                        WorldEngine.getZ(key),
                        this);

                status = this.loader.load(section);

                if (status < 0) {
                    //TODO: Instead if throwing an exception do something better, like attempting to regen
                    //throw new IllegalStateException("Unable to load section: ");
                    System.err.println("Unable to load section " + section.key + " setting to air");
                    status = 1;
                }

                //TODO: REWRITE THE section tracker _again_ to not be so shit and jank, and so that Arrays.fill is not 10% of the execution time
                if (status == 1) {
                    //We need to set the data to air as it is undefined state
                    Arrays.fill(section.data, 0);
                }
            } else {
                section.primeForReuse();
            }

            section.acquire();
            holder.obj = section;
            if (nullOnEmpty && status == 1) {//If its air return null as stated, release the section aswell
                section.release();
                return null;
            }
            return section;
        } else {
            while ((section = holder.obj) == null)
                Thread.yield();

            //lock.lock();
            {//Dont think need to lock here
                if (section.tryAcquire()) {
                    return section;
                }
            }
            //lock.unlock();
            return this.acquire(key, nullOnEmpty);
        }
    }

    void tryUnload(WorldSection section) {
        int index = this.getCacheArrayIndex(section.key);
        final var cache = this.loadedSectionCache[index];
        WorldSection prev = null;
        WorldSection lruEntry = null;
        final var lock = this.locks[index];
        lock.lock();
        {
            if (section.trySetFreed()) {
                var cached = cache.remove(section.key);
                var obj = cached.obj;
                if (obj != section) {
                    throw new IllegalStateException("Removed section not the same as the referenced section in the cache: cached: " + obj + " got: " + section + " A: " + WorldSection.ATOMIC_STATE_HANDLE.get(obj) + " B: " +WorldSection.ATOMIC_STATE_HANDLE.get(section));
                }

                //Add section to secondary cache while primary is locked
                var lruCache = this.lruSecondaryCache[index];
                prev = lruCache.put(section.key, section);
                //If cache is bigger than its ment to be, remove the least recently used and free it
                if (this.maxLRUSectionPerSlice < lruCache.size()) {
                    lruEntry = lruCache.removeFirst();
                }
            }
        }
        lock.unlock();

        if (prev != null) {
            prev._releaseArray();
        }
        if (lruEntry != null) {
            lruEntry._releaseArray();
        }
    }

    private int getCacheArrayIndex(long pos) {
        return (int) (mixStafford13(pos) & (this.loadedSectionCache.length-1));
    }

    public static long mixStafford13(long seed) {
        seed = (seed ^ seed >>> 30) * -4658895280553007687L;
        seed = (seed ^ seed >>> 27) * -7723592293110705685L;
        return seed ^ seed >>> 31;
    }

    public int getLoadedCacheCount() {
        int res = 0;
        for (var cache : this.loadedSectionCache) {
            res += cache.size();
        }
        return res;
    }

    public int getSecondaryCacheSize() {
        int res = 0;
        for (var cache : this.lruSecondaryCache) {
            res += cache.size();
        }
        return res;
    }

    public static void main(String[] args) {
        var tracker = new ActiveSectionTracker(1, a->0, 1<<10);

        var section = tracker.acquire(0,0,0,0, false);
        section.acquire();
        var section2 = tracker.acquire(0,0,0,0, false);
        section.release();
        section.release();
        section = tracker.acquire(0,0,0,0, false);
        section.release();

    }
}
