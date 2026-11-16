package com.mineberry.essentials;

/**
 * Monotonic millisecond clock for measuring durations (cooldowns, combat windows, animations).
 * Unlike System.currentTimeMillis() it is not affected by the player changing the system time.
 * The value starts one day "in the past", so a field that still holds its default 0 always counts as "long ago".
 * Do not use it for timestamps that are shown to the player.
 */
public final class Clock {
    private static final long START = System.nanoTime();
    private static final long OFFSET_MS = 86_400_000L;

    private Clock() {}

    public static long ms() {
        return (System.nanoTime() - START) / 1_000_000L + OFFSET_MS;
    }
}
