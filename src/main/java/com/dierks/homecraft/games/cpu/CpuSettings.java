package com.dierks.homecraft.games.cpu;

import com.dierks.homecraft.config.GamesConfig;

import java.util.List;

/**
 * {@code games.cpu}: the NPC players. {@link #defaults()} is the single source of the shipped values: the bundled
 * config.yml block parses to exactly it without a WARN ({@code GamesConfigTest}).
 *
 * @param enabled         the NPC players' switch (they also need {@code games.enabled}); false = none anywhere
 * @param regulars        how many NPCs hang out in the Clubhouse at once while a real player is visiting (0-6)
 * @param joinAfterSeconds how long a real player sits alone at a table before an NPC joins them (5-600)
 */
public record CpuSettings(boolean enabled, int regulars, int joinAfterSeconds) {

    /** The leaves under {@code games.cpu}, in config order. */
    public static final List<String> KEYS = List.of("enabled", "regulars", "join_after_seconds");

    public static CpuSettings defaults() {
        return new CpuSettings(true, 3, 20);
    }

    /** Read {@code games.cpu} over {@code d}; never throws. */
    public static CpuSettings parse(GamesConfig.Node n, CpuSettings d) {
        boolean enabled = n.enabled(d.enabled());
        int regulars = n.whole("regulars", d.regulars(), 0, CpuRoster.ALL.size());
        int joinAfter = n.whole("join_after_seconds", d.joinAfterSeconds(), 5, 600);
        return new CpuSettings(enabled, regulars, joinAfter);
    }
}
