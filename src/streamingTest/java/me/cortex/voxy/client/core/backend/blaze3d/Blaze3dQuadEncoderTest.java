package me.cortex.voxy.client.core.backend.blaze3d;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/** Validates the CPU/GPU binary ABI without booting Minecraft. Does not execute the shader. */
final class Blaze3dQuadEncoderTest {
    private static int checks;

    static void run() {
        ByteBuffer out = ByteBuffer.allocate(96).order(ByteOrder.nativeOrder());
        out.position(16);
        long quad = 5L | (15L << 3) | (7L << 7) | (31L << 11) | (19L << 16)
                | (17L << 21) | (0xBEEFL << 26) | (0xBL << 42) | (511L << 46) | (0xA7L << 55);
        Blaze3dQuadEncoder.put(out, -512, 1024, -16384, quad, 0x03FFFFFF,
                0xFF112233, 77, 31, 4);
        check(out.position() == 48, "One quad is exactly 32 bytes, even when appended to a buffer");
        check(out.getFloat(16) == -512 && out.getFloat(20) == 1024 && out.getFloat(24) == -16384,
                "Section origin preserves negative and positive world coordinates");
        int low = out.getInt(28);
        int high = out.getInt(32);
        check((Integer.toUnsignedLong(low) | (Integer.toUnsignedLong(high) << 32)) == quad,
                "Quad words are lossless and ordered independently of native long layout");
        check((low >>> 26 | (high & 1023) << 6) == 0xBEEF, "Model id crossing the 32-bit boundary survives");
        check(((high >>> 23) & 255) == 0xA7, "Both light channels survive high-word extraction");
        check(((low >>> 3) & 255 | ((high >>> 10) & 15) << 8) == 0xB7F,
                "Fluid corner heights spanning both words survive");
        check((low & 7) == 5 && ((low >>> 21) & 31) == 17
                        && ((low >>> 16) & 31) == 19 && ((low >>> 11) & 31) == 31,
                "Face and all three local axes preserve their original bit positions");
        check(out.getInt(36) == 0x03FFFFFF, "Partial-face bounds, depth, cutout and tint flags remain intact");
        check((out.get(40) & 255) == 0x11 && (out.get(41) & 255) == 0x22
                        && (out.get(42) & 255) == 0x33 && (out.get(43) & 255) == 77,
                "RGBA stores tint RGB and directional shade in the vertex attribute order");
        check(out.getInt(44) == (31 | 4 << 8), "Material and LOD scale occupy independent bits");
        check(out.getInt(12) == 0 && out.getInt(48) == 0, "Packing does not overwrite neighbouring records");

        // Property checks include the sign bit and model/light extrema, so a truncation or
        // signed conversion in a future packing change cannot silently corrupt an entire biome.
        Random random = new Random(0xB1A2E3D);
        for (int i = 0; i < 10000; i++) {
            out.clear();
            long value = random.nextLong();
            int level = i % 5;
            Blaze3dQuadEncoder.put(out, 0, 0, 0, value, 0, -1, 255, 31, level);
            int lo = out.getInt(12);
            int hi = out.getInt(16);
            if ((Integer.toUnsignedLong(lo) | (Integer.toUnsignedLong(hi) << 32)) != value
                    || (lo >>> 26 | (hi & 1023) << 6) != (int) ((value >>> 26) & 65535)
                    || ((hi >>> 23) & 255) != (int) ((value >>> 55) & 255)
                    || ((out.getInt(28) >>> 8) & 7) != level) {
                throw new AssertionError("Packed GPU attributes do not match the source quad at " + i);
            }
        }
        check(true, "Randomized quad and LOD round trips");
        System.out.println("Blaze3D quad ABI: " + checks + " checks passed (including 10000 round trips).");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
