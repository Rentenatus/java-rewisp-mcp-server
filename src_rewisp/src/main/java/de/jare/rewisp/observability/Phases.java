/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.observability;

/**
 * Timing of phases of a call (e.g. {@code snapshot-refresh}, {@code write}, {@code spoon-rebuild}), without the
 * backend knowing the call layer. Whoever tracks calls (the MCP binding) registers with
 * {@link #setListener(Listener)}; without a listener {@link #end} is a no-op.
 *
 * @author Janusch Rentenatus
 */
public final class Phases {

    /** Accepts a completed phase. */
    @FunctionalInterface
    public interface Listener {
        void phaseEnded(String phase, long startNanos);
    }

    private static volatile Listener listener;

    private Phases() {}

    /** Registers the receiver of the phase timings (replaces an existing one); {@code null} removes it. */
    public static void setListener(Listener newListener) {
        listener = newListener;
    }

    /** The currently registered listener or {@code null} (e.g. so tests can restore it). */
    public static Listener listener() {
        return listener;
    }

    /** Remembers the start time of a phase; counterpart: {@link #end}. */
    public static long start() {
        return System.nanoTime();
    }

    /** Reports the end of a phase to the listener (if present). */
    public static void end(String phase, long startNanos) {
        Listener l = listener;
        if (l != null) {
            l.phaseEnded(phase, startNanos);
        }
    }
}