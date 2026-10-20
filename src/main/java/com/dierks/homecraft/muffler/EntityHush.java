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
 * decided by its main loop through the muffler's normal rules — its group (Minecarts, Bees) or a
 * single pick, so "Always play" on the loop overrides a silenced group like it does anywhere
 * else — or by a single Silent pick of one of its other loops (the rider's rumble, the angry buzz).
 */
public final class EntityHush {

    /** An entity whose own sound loops a muffler can stop. */
    public enum Kind {
        /**
         * Every minecart: plain, hopper, chest, furnace, TNT, spawner and command block. The
         * rumble bystanders hear decides; a rider hears the two "inside" loops.
         */
        MINECART("minecart", "minecraft:entity.minecart.",
                "minecraft:entity.minecart.riding",
                List.of("minecraft:entity.minecart.inside", "minecraft:entity.minecart.inside.underwater")),
        /** Bees: their buzz while flying decides; the angry buzz is the other loop. */
        BEE("bee", "minecraft:entity.bee.",
                "minecraft:entity.bee.loop",
                List.of("minecraft:entity.bee.loop_aggressive"));

        private final String noun;
        private final String prefix;
        private final String loop;
        private final List<String> otherLoops;

        Kind(String noun, String prefix, String loop, List<String> otherLoops) {
            this.noun = noun;
            this.prefix = prefix;
            this.loop = loop;
            this.otherLoops = otherLoops;
        }

        /** "minecart" / "bee", for menus. */
        public String noun() {
            return noun;
        }

        /** The sound whose level (its group, or a single pick) decides whether this kind is silenced. */
        public String loop() {
            return loop;
        }

        /** The kind's other loops: a single Silent pick of one of these silences the kind too. */
        public List<String> otherLoops() {
            return otherLoops;
        }

        /** The kind whose MAIN loop this sound is, or null. */
        public static Kind ofLoop(String key) {
            for (Kind k : values()) {
                if (k.loop.equals(key)) {
                    return k;
                }
            }
            return null;
        }

        /** The kind for which this sound is one of its loops (main or other), or null. */
        public static Kind ofAnyLoop(String key) {
            for (Kind k : values()) {
                if (k.loop.equals(key) || k.otherLoops.contains(key)) {
                    return k;
                }
            }
            return null;
        }

        /** The kind that makes this sound at all ({@code entity.bee.sting} → BEE), or null. */
        public static Kind ofKey(String key) {
            if (key == null) {
                return null;
            }
            for (Kind k : values()) {
                if (key.startsWith(k.prefix)) {
                    return k;
                }
            }
            return null;
        }
    }

    private EntityHush() {
    }

    /**
     * Whether this muffler silences entities of this kind (it must be switched on): the main loop
     * resolves to Silent (its group, or a single pick — so "Always play" on it is the exception to
     * a silenced group), or one of the other loops is picked Silent on its own.
     */
    public static boolean silences(Muffler m, Kind kind) {
        if (!m.enabled()) {
            return false;
        }
        if (m.levelFor(kind.loop()) == MuffleLevel.SILENT) {
            return true;
        }
        for (String other : kind.otherLoops()) {
            if (m.sounds().get(other) == MuffleLevel.SILENT) {
                return true;
            }
        }
        return false;
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
