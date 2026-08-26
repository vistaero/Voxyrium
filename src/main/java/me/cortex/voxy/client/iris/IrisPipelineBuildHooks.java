package me.cortex.voxy.client.iris;

/** Tracks the Iris pipeline currently being constructed on the render thread. */
public final class IrisPipelineBuildHooks {
    private static final ThreadLocal<Object> CURRENT = new ThreadLocal<>();

    private IrisPipelineBuildHooks() {
    }

    public static void begin(Object pipeline) {
        CURRENT.set(pipeline);
    }

    public static Object current() {
        return CURRENT.get();
    }

    public static void end(Object pipeline) {
        if (CURRENT.get() == pipeline) {
            CURRENT.remove();
        }
    }
}
