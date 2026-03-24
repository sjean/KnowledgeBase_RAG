package com.example.aikb.agent;

public final class AgentExecutionContextHolder {

    private static final InheritableThreadLocal<ExecutionContext> CONTEXT = new InheritableThreadLocal<>();

    private AgentExecutionContextHolder() {
    }

    public static void set(Long userId, String role) {
        CONTEXT.set(new ExecutionContext(userId, role));
    }

    public static Long userId() {
        ExecutionContext context = CONTEXT.get();
        if (context == null) {
            throw new IllegalStateException("No agent execution context");
        }
        return context.userId();
    }

    public static String role() {
        ExecutionContext context = CONTEXT.get();
        if (context == null) {
            throw new IllegalStateException("No agent execution context");
        }
        return context.role();
    }

    public static boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role());
    }

    public static void clear() {
        CONTEXT.remove();
    }

    private record ExecutionContext(Long userId, String role) {
    }
}
