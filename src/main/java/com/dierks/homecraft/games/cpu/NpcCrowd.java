package com.dierks.homecraft.games.cpu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * Who of the NPC players joins a world game, and when (stage 2). Pure, so every rule is tested without a server.
 *
 * <ul>
 *   <li><b>Only when wanted.</b> NPCs join a game only while every real player in it has NPCs on ({@link #welcome}):
 *       one who has them off never gets NPCs in their game, and never sees an NPC's body.</li>
 *   <li><b>When a real player waits alone.</b> A real player who opened a race, a round or a party and has been the
 *       only real one in it for {@code games.cpu.join_after_seconds} gets company ({@link Alone}); then one more
 *       now and then, up to {@link #MAX_IN_GAME}.</li>
 *   <li><b>They give way.</b> An NPC never takes a place a real player wants: a game's places go to real players
 *       first, and NPCs have only what is left ({@link #room}).</li>
 * </ul>
 */
public final class NpcCrowd {

    /** The most NPCs in one world game at once. */
    public static final int MAX_IN_GAME = 3;
    /** After the first joins, the next joins about this long after (ms). */
    public static final long NEXT_AFTER_MS = 8_000;

    private NpcCrowd() {
    }

    /** Whether NPCs are welcome among {@code reals}: at least one real player, and every one of them wants NPCs. */
    public static boolean welcome(Collection<UUID> reals, Predicate<UUID> wants) {
        if (reals == null || reals.isEmpty()) {
            return false;
        }
        for (UUID r : reals) {
            if (!wants.test(r)) {
                return false;
            }
        }
        return true;
    }

    /**
     * How many NPCs may be in a game with {@code reals} real players in it and {@code places} places in all (real
     * players first), never more than {@link #MAX_IN_GAME}.
     */
    public static int room(int reals, int places) {
        return Math.max(0, Math.min(MAX_IN_GAME, places - Math.max(0, reals)));
    }

    /**
     * Up to {@code n} NPCs from {@code roster} that aren't in {@code busy}, picked at random (so the same few
     * don't always come).
     */
    public static List<CpuPlayer> pick(List<CpuPlayer> roster, Set<UUID> busy, int n, RandomGenerator rng) {
        List<CpuPlayer> free = new ArrayList<>();
        for (CpuPlayer c : roster) {
            if (!busy.contains(c.id())) {
                free.add(c);
            }
        }
        List<CpuPlayer> out = new ArrayList<>();
        while (out.size() < n && !free.isEmpty()) {
            out.add(free.remove(rng.nextInt(free.size())));
        }
        return out;
    }

    /**
     * The clock of one game's wait: how long its real player has been there alone. NPCs are due once it has run
     * {@code joinAfterMs}, and then one more every {@link #NEXT_AFTER_MS}.
     */
    public static final class Alone {

        private long since = -1;
        private long nextAt = -1;

        /**
         * Whether another NPC may join now.
         *
         * @param alone       a real player waits there with no other real player, and NPCs are welcome
         * @param npcs        how many NPCs are in already
         * @param now         the time (ms)
         * @param joinAfterMs {@code games.cpu.join_after_seconds}
         */
        public boolean due(boolean alone, int npcs, long now, long joinAfterMs) {
            if (!alone) {
                if (npcs == 0) {
                    since = -1;
                }
                nextAt = -1;
                return false;
            }
            if (since < 0) {
                since = now;
            }
            if (npcs == 0) {
                return now - since >= joinAfterMs;
            }
            if (nextAt < 0) {
                nextAt = now + NEXT_AFTER_MS;
            }
            return now >= nextAt;
        }

        /** An NPC just joined: the next one waits {@link #NEXT_AFTER_MS}. */
        public void joined(long now) {
            nextAt = now + NEXT_AFTER_MS;
        }

        /** Start again (the game ended, or someone with NPCs off came). */
        public void reset() {
            since = -1;
            nextAt = -1;
        }
    }
}
