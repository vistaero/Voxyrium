package me.cortex.voxy.common.util;

public final class Java17Compat {
    private Java17Compat() {
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public static int compress(int value, int mask) {
        value &= mask;
        int zeroCount = ~mask << 1;
        for (int shift = 0; shift < 5; shift++) {
            int prefix = suffixParity(zeroCount);
            int movedMask = prefix & mask;
            mask = (mask ^ movedMask) | (movedMask >>> (1 << shift));
            int movedValue = value & movedMask;
            value = (value ^ movedValue) | (movedValue >>> (1 << shift));
            zeroCount &= ~prefix;
        }
        return value;
    }

    public static int expand(int value, int mask) {
        int originalMask = mask;
        int zeroCount = ~mask << 1;

        int prefix = suffixParity(zeroCount);
        int move1 = prefix & mask;
        mask = (mask ^ move1) | (move1 >>> 1);
        zeroCount &= ~prefix;

        prefix = suffixParity(zeroCount);
        int move2 = prefix & mask;
        mask = (mask ^ move2) | (move2 >>> 2);
        zeroCount &= ~prefix;

        prefix = suffixParity(zeroCount);
        int move4 = prefix & mask;
        mask = (mask ^ move4) | (move4 >>> 4);
        zeroCount &= ~prefix;

        prefix = suffixParity(zeroCount);
        int move8 = prefix & mask;
        mask = (mask ^ move8) | (move8 >>> 8);
        zeroCount &= ~prefix;

        int move16 = suffixParity(zeroCount) & mask;

        value = (value & ~move16) | ((value << 16) & move16);
        value = (value & ~move8) | ((value << 8) & move8);
        value = (value & ~move4) | ((value << 4) & move4);
        value = (value & ~move2) | ((value << 2) & move2);
        value = (value & ~move1) | ((value << 1) & move1);
        return value & originalMask;
    }

    public static long compress(long value, long mask) {
        value &= mask;
        long zeroCount = ~mask << 1;
        for (int shift = 0; shift < 6; shift++) {
            long prefix = suffixParity(zeroCount);
            long movedMask = prefix & mask;
            mask = (mask ^ movedMask) | (movedMask >>> (1 << shift));
            long movedValue = value & movedMask;
            value = (value ^ movedValue) | (movedValue >>> (1 << shift));
            zeroCount &= ~prefix;
        }
        return value;
    }

    public static long expand(long value, long mask) {
        long originalMask = mask;
        long zeroCount = ~mask << 1;

        long prefix = suffixParity(zeroCount);
        long move1 = prefix & mask;
        mask = (mask ^ move1) | (move1 >>> 1);
        zeroCount &= ~prefix;

        prefix = suffixParity(zeroCount);
        long move2 = prefix & mask;
        mask = (mask ^ move2) | (move2 >>> 2);
        zeroCount &= ~prefix;

        prefix = suffixParity(zeroCount);
        long move4 = prefix & mask;
        mask = (mask ^ move4) | (move4 >>> 4);
        zeroCount &= ~prefix;

        prefix = suffixParity(zeroCount);
        long move8 = prefix & mask;
        mask = (mask ^ move8) | (move8 >>> 8);
        zeroCount &= ~prefix;

        prefix = suffixParity(zeroCount);
        long move16 = prefix & mask;
        mask = (mask ^ move16) | (move16 >>> 16);
        zeroCount &= ~prefix;

        long move32 = suffixParity(zeroCount) & mask;

        value = (value & ~move32) | ((value << 32) & move32);
        value = (value & ~move16) | ((value << 16) & move16);
        value = (value & ~move8) | ((value << 8) & move8);
        value = (value & ~move4) | ((value << 4) & move4);
        value = (value & ~move2) | ((value << 2) & move2);
        value = (value & ~move1) | ((value << 1) & move1);
        return value & originalMask;
    }

    private static int suffixParity(int value) {
        value ^= value << 1;
        value ^= value << 2;
        value ^= value << 4;
        value ^= value << 8;
        return value ^ (value << 16);
    }

    private static long suffixParity(long value) {
        value ^= value << 1;
        value ^= value << 2;
        value ^= value << 4;
        value ^= value << 8;
        value ^= value << 16;
        return value ^ (value << 32);
    }
}
