package me.cortex.voxy.common;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

//Opt-in structured logging for GL<-->VK A/B parity comparison.
//
//Enable with -Dvoxy.cmplog=<path>. Both the OpenGL and Vulkan render paths then
// emit the SAME semantic per-frame quantities (section/geometry counts, traversal
// request counts, ...) as tab-separated records. For a stationary camera at a
// fixed viewpoint these values converge to identical integers when the VK
// translation is faithful, so ab_compare.py compare-logs can flag any divergence
// exactly, without the rounding noise that muddies a pixel diff.
//
//When the property is unset every method is a cheap no-op (one static-field
// read), so this stays compiled into normal builds at negligible cost.
//
//Record format (one per line): VOXYCMP\t<frame>\t<phase>\t<key>\t<value>
public final class CmpLog {
    private static final String PATH = System.getProperty("voxy.cmplog");
    /** True when {@code -Dvoxy.cmplog=<path>} was supplied. */
    public static final boolean ENABLED = PATH != null && !PATH.isEmpty();

    /** Set by each render core so the log header records which backend produced it. */
    public static volatile String backend = "unknown";

    private static BufferedWriter writer;
    private static boolean broken = false;
    private static int frame = 0;

    private CmpLog() {}

    private static BufferedWriter writer() {
        if (writer == null && !broken) {
            try {
                Path p = Path.of(PATH).toAbsolutePath();
                if (p.getParent() != null) {
                    Files.createDirectories(p.getParent());
                }
                writer = Files.newBufferedWriter(p, StandardCharsets.UTF_8);
                writer.write("#VOXYCMP backend=" + backend + "\n");
                writer.flush();
                Logger.info("CmpLog: writing A/B comparison log to " + PATH
                        + " (backend=" + backend + ")");
            } catch (IOException e) {
                broken = true;
                Logger.error("CmpLog: failed to open " + PATH, e);
            }
        }
        return writer;
    }

    /** Advance to the next frame and flush; call once per rendered frame. */
    public static void nextFrame() {
        if (!ENABLED) return;
        synchronized (CmpLog.class) {
            frame++;
            if (writer != null) {
                try { writer.flush(); } catch (IOException e) { broken = true; }
            }
        }
    }

    /** Record a semantic quantity for the current frame. */
    public static void rec(String phase, String key, long value) {
        if (!ENABLED) return;
        synchronized (CmpLog.class) {
            var w = writer();
            if (w == null) return;
            try {
                w.write("VOXYCMP\t" + frame + "\t" + phase + "\t" + key + "\t" + value + "\n");
            } catch (IOException e) {
                broken = true;
            }
        }
    }
}
