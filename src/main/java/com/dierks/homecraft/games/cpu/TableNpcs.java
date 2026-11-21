package com.dierks.homecraft.games.cpu;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * What a table or a race asks about NPC players ({@link CpuPlayers} in play, a fake in tests). Nothing here moves
 * anything real: it only says who may sit down, how they play and what they are called.
 */
public interface TableNpcs {

    /** Whether {@code real} has NPC players on (their own switch, or the one a parent or admin locked). */
    boolean wants(UUID real);

    /** The level {@code npc} plays at in {@code gameId} with these real players ({@link CpuLevels#forGame}). */
    CpuLevel level(CpuPlayer npc, Collection<UUID> reals, String gameId);

    /** The NPC's name with its gear and stars, as a screen shows it ({@link CpuRoster#label}). */
    String label(CpuPlayer npc);

    /** The NPCs to pick from, in roster order. */
    List<CpuPlayer> roster();

    /** How long a real player sits alone at a table before an NPC joins them. */
    long joinAfterMs();

    /** Whether anyone is about to see a show (someone stands in the Game Parlor). */
    boolean audience();

    /** The generator for choices and timings. */
    RandomGenerator rng();
}
