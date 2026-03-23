package com.example.aikb.agent;

public final class ToolTrackingHolder {

    private static final ThreadLocal<String> LAST_TOOL = new ThreadLocal<>();

    private ToolTrackingHolder() {
    }

    public static void set(String toolName) {
        LAST_TOOL.set(toolName);
    }

    public static String get() {
        return LAST_TOOL.get();
    }

    public static void clear() {
        LAST_TOOL.remove();
    }
}
