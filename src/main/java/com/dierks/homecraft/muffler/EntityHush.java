package com.dierks.homecraft.muffler;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Sounds no packet carries, that a muffler can still switch off.
 *
 * <p>A rolling minecart's rumble and a bee's buzz in flight are started by each player's own game
 * the moment it sees the entity, so the packet listener never gets to touch them. What the game
 * does respect is the entity's "silent" flag, which the server sets and sends: a silent minecart
 * does not rumble and a silent bee does not buzz. So for these two the muffler marks the entities
 * inside its box silent ({@code EntityHusher}) and clears the mark when they leave.
 *
 * <p>Silent is all or nothing for an entity — there is no volume to turn down — so only
 * {@link MuffleLevel#SILENT} does this, and it quiets everything that entity makes. Each kind is
 * decided by ONE sound, the loop it exists to stop, through the muffler's normal rules: its group
 * (Minecarts, Bees) or a single pick of that sound, so "Always play" on the loop overrides a
 * silenced group like it does anywhere else.
 */
public final class EntityHush {

    /** An entity whose own sound loop a muffler can stop. */
    public enum Kind {
        /** Every minecart: plain, hopper, chest, furnace, TNT, spawner and command block. */
        MINECART("minecraft:entity.minecart.riding"),
        /** Bees: their buzz while flying. */
        BEE("minecraft:entity.bee.loop");

        private final String loop;

        Kind(String loop) {
            this.loop = loop;
        }

        /** The sound whose level decides whether this kind is silenced. */
        public String loop() {
            return loop;
        }

        /** The kind whose loop this sound is, or null. */
        public static Kind ofLoop(String key) {
            for (Kind k : values()) {
                if (k.loop.equals(key)) {
                    return k;
                }
            }
            return null;
        }
    }

    private EntityHush() {
    }

    /** Whether this muffler silences entities of this kind (it must be switched on). */
    public static boolean silences(Muffler m, Kind kind) {
        return m.enabled() && m.levelFor(kind.loop()) == MuffleLevel.SILENT;
    }

    /** Every kind this muffler silences; empty for most mufflers. */
    public static Set<Kind> kindsSilencedBy(Muffler m) {
        Set<Kind> out = EnumSet.noneOf(Kind.class);
        for (Kind k : Kind.values()) {
            if (silences(m, k)) {
                out.add(k);
            }
        }
        return out;
    }

    /**
     * Whether an entity of this kind standing at (x, y, z) should be silent: some muffler whose
     * box holds the point silences its kind. Where boxes overlap, any one is enough — the same
     * "strongest wins" as for every other sound.
     */
    public static boolean shouldSilence(List<Muffler> zones, Kind kind, double x, double y, double z) {
        for (Muffler m : zones) {
            if (m.contains(x, y, z) && silences(m, kind)) {
                return true;
            }
        }
        return false;
    }
}
