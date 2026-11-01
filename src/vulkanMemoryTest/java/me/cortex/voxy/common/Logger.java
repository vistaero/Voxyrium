package me.cortex.voxy.common;

public final class Logger {
    public static String info(Object... message) { return ""; }
    public static void warn(Object... message) {}
    public static void error(Object... message) { System.err.println(java.util.Arrays.toString(message)); }
}
