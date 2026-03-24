package com.example.aikb.agent;

public final class ToolTrackingHolder {

    private static final InheritableThreadLocal<String> LAST_TOOL = new InheritableThreadLocal<>();

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
