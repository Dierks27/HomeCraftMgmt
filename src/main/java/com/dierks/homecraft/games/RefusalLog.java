package com.dierks.homecraft.games;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * When a refusal told to a player is also written to the console: once a player and message a minute. A
 * refusal is a chat line, and on Bedrock the chat can sit hidden behind an open screen, so the owner reads
 * what a player was told in the console instead; a held click or a tap again and again repeats a refusal
 * many times, so the same line is not written again for {@link #EVERY_MS}.
 *
 * <p>Pure and main-thread only; it remembers at most {@link #KEPT} lines and forgets those older than a minute
 * first.
 */
public final class RefusalLog {

    /** The same player and message are logged at most once in this long. */
    public static final long EVERY_MS = 60_000;
    /** How many recent lines are remembered before the old ones are forgotten. */
    static final int KEPT = 256;

    private final Map<String, Long> logged = new HashMap<>();

    /** Whether {@code message} told to {@code player} at {@code nowMillis} is written to the console. */
    public boolean due(UUID player, String message, long nowMillis) {
        if (player == null || message == null || message.isEmpty()) {
            return false;
        }
        String key = player + "|" + message;
        Long last = logged.get(key);
        if (last != null && nowMillis - last < EVERY_MS) {
            return false;
        }
        if (logged.size() >= KEPT) {
            logged.values().removeIf(at -> nowMillis - at >= EVERY_MS);
            if (logged.size() >= KEPT) {
                logged.clear(); // a flood of different lines: start again rather than grow
            }
        }
        logged.put(key, nowMillis);
        return true;
    }
}
