package com.superprogrammer.knowledge.service;

/** Request-local ranking configuration route. It stores only an immutable version identifier. */
public final class RagRankingRouteContext {
    private static final ThreadLocal<String> VERSION = new ThreadLocal<>();

    private RagRankingRouteContext() {}

    public static String currentVersion() { return VERSION.get(); }

    public static Scope open(String version) {
        String previous = VERSION.get();
        if (version == null || version.isBlank()) VERSION.remove(); else VERSION.set(version);
        return () -> { if (previous == null) VERSION.remove(); else VERSION.set(previous); };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable { @Override void close(); }
}
