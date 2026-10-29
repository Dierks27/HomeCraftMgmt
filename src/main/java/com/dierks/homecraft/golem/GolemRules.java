package com.dierks.homecraft.golem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Copper Golem Brush's rules (0.38.4; the owner: "a special tool ... take something like the brush and make it a
 * different colour ... lots of copper plus feathers and maybe a slime ball"): which container a golem with chests of
 * its own may use, how a tap on a golem opens, ends or clears a "pick the chests" session, when a held tap counts,
 * what the golem's name tag reads, and how its chests are packed into its own data. Pure, so every rule is tested
 * without a server ({@code GolemRulesTest}).
 */
public final class GolemRules {

    /** A "pick the chests" session lasts this long (ms). */
    public static final long SESSION_MS = 60_000L;
    /** Two shift-taps on the golem within this (ms) clear its chests. */
    public static final long CLEAR_MS = 2_000L;
    /** A golem keeps at most this many chest blocks (both halves of a double chest count). */
    public static final int MAX_CHESTS = 32;
    /** The name a golem gets when it has none. */
    public static final String DEFAULT_NAME = "Rusty";
    /** The wiki's search box, from where the golem stands: 65 wide (32 each way) ... */
    static final int REACH_XZ = 32;
    /** ... and it uses a chest at most one block above it ... */
    static final int REACH_UP = 1;
    /** ... or two below. */
    static final int REACH_DOWN = 2;

    private GolemRules() {
    }

    // ---- the filter ------------------------------------------------------------------------------

    /**
     * Whether a golem may use the container at {@code packed}: a copper chest always (it is the source, and Paper
     * runs the take-from and the drop-into search through the same check), any container while the golem has no
     * chests of its own, else only one of its own. The one decision the validate listener makes, O(1).
     */
    public static boolean allow(boolean copperChest, Set<Long> assigned, long packed) {
        return copperChest || assigned == null || assigned.isEmpty() || assigned.contains(packed);
    }

    // ---- packing ---------------------------------------------------------------------------------

    /** A block position in one long: 26 bits of x, 26 of z, 12 of y (the world's whole range), as Minecraft does. */
    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static int x(long p) {
        return (int) (p >> 38); // the top bits: an arithmetic shift sign-extends
    }

    public static int z(long p) {
        return (int) ((p << 26) >> 38);
    }

    public static int y(long p) {
        return (int) ((p << 52) >> 52);
    }

    /**
     * The stored LONG_ARRAY as a set in its stored order (the link order: #1 first), duplicates dropped and cut to
     * {@value #MAX_CHESTS}; null is no chests.
     */
    public static Set<Long> parse(long[] stored) {
        Set<Long> out = new LinkedHashSet<>();
        if (stored == null) {
            return out;
        }
        for (long p : stored) {
            if (out.size() >= MAX_CHESTS) {
                break;
            }
            out.add(p);
        }
        return out;
    }

    /** The set back as a LONG_ARRAY, in its order (so the numbers over the chests survive a restart). */
    public static long[] format(Set<Long> chests) {
        long[] out = new long[chests.size()];
        int i = 0;
        for (long p : chests) {
            out[i++] = p;
        }
        return out;
    }

    // ---- the session state machine -----------------------------------------------------------------

    /** What a tap on a golem does. */
    public enum Act { OPEN, CLOSE, SWITCH, ARM_CLEAR, CLEAR }

    /** One player's session: which golem, when it started (ms), when the last shift-tap on the golem was (0: none). */
    public record Session(UUID golem, long startedAt, long shiftAt) {
        public long endsAt() {
            return startedAt + SESSION_MS;
        }
    }

    /** Every open "pick the chests" session, by player. Plain state, so a test drives it; main thread. */
    public static final class Sessions {
        private final Map<UUID, Session> byPlayer = new HashMap<>();

        /**
         * A tap on {@code golem} by {@code player} at {@code now}: OPEN (no session, or one that has run out), CLOSE (a
         * plain tap on the session's golem), SWITCH (a tap on another golem: the old session closes, a new one
         * opens), ARM_CLEAR (a shift-tap on the session's golem: the next shift-tap within {@value #CLEAR_MS} ms
         * clears), CLEAR (that second shift-tap: the chests go and the session ends). A shift-tap with no session
         * OPENS, and arms; a late second shift-tap only re-arms. A single tap never clears anything.
         */
        public Act tap(UUID player, UUID golem, boolean sneaking, long now) {
            Session s = byPlayer.get(player);
            if (s != null && s.endsAt() <= now) {
                s = null; // ran out before the pass noticed: as no session
            }
            if (s == null) {
                byPlayer.put(player, new Session(golem, now, sneaking ? now : 0));
                return Act.OPEN;
            }
            if (!s.golem().equals(golem)) {
                byPlayer.put(player, new Session(golem, now, sneaking ? now : 0));
                return Act.SWITCH;
            }
            if (!sneaking) {
                byPlayer.remove(player);
                return Act.CLOSE;
            }
            if (s.shiftAt() > 0 && now - s.shiftAt() <= CLEAR_MS) {
                byPlayer.remove(player);
                return Act.CLEAR;
            }
            byPlayer.put(player, new Session(golem, s.startedAt(), now));
            return Act.ARM_CLEAR;
        }

        /** {@code player}'s session, or null. */
        public Session of(UUID player) {
            return byPlayer.get(player);
        }

        /** {@code player}'s session, ended (null when there was none). */
        public Session end(UUID player) {
            return byPlayer.remove(player);
        }

        /** The sessions whose {@value #SESSION_MS} ms are up at {@code now}, removed. */
        public List<Map.Entry<UUID, Session>> expired(long now) {
            List<Map.Entry<UUID, Session>> out = new ArrayList<>();
            for (Iterator<Map.Entry<UUID, Session>> it = byPlayer.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, Session> e = it.next();
                if (e.getValue().endsAt() <= now) {
                    out.add(Map.entry(e.getKey(), e.getValue()));
                    it.remove();
                }
            }
            return out;
        }

        /** Every player with a session on {@code golem} (it died): their sessions removed. */
        public List<UUID> onGolem(UUID golem) {
            List<UUID> out = new ArrayList<>();
            for (Iterator<Map.Entry<UUID, Session>> it = byPlayer.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, Session> e = it.next();
                if (e.getValue().golem().equals(golem)) {
                    out.add(e.getKey());
                    it.remove();
                }
            }
            return out;
        }

        /** Every player with a session: their sessions removed (the brush switched off, the plugin stopping). */
        public List<UUID> endAll() {
            List<UUID> out = new ArrayList<>(byPlayer.keySet());
            byPlayer.clear();
            return out;
        }

        public boolean isEmpty() {
            return byPlayer.isEmpty();
        }
    }

    // ---- held taps -------------------------------------------------------------------------------

    /**
     * When a brush tap counts (a held use button repeats about five times a second, on Java every 4 ticks and a held
     * Bedrock tap too): only when the last tap on the same thing (that golem, that chest) was {@value #GAP_MS} ms or
     * more before. Every tap restarts the gap, so a held tap is one toggle until it is let go, and a quick tap on
     * another chest counts at once. The Remote's rule ({@code RemoteRules.Taps}) without its My Show case.
     */
    public static final class Taps {
        /** The least time between two taps on one thing that both count. */
        static final long GAP_MS = 600;

        private record Tap(Object about, long at) {
        }

        private final Map<UUID, Tap> last = new HashMap<>();

        /** Whether {@code player}'s tap on {@code about} at {@code now} (ms) counts; it is the last tap either way. */
        public boolean counts(UUID player, Object about, long now) {
            Tap before = last.put(player, new Tap(about, now));
            return before == null || !before.about().equals(about) || now - before.at() >= GAP_MS;
        }

        /** The player quit: nothing of theirs is kept. */
        public void forget(UUID player) {
            last.remove(player);
        }
    }

    // ---- the name tag ----------------------------------------------------------------------------

    /** Our tags read "<name> -> ..." or "<name> - holding ...": never a name a golem was given. */
    private static final String ARROW = " -> ";
    private static final String HOLDING = " - holding ";

    /**
     * The golem's name tag: "Rusty -> 3 chests" ("Rusty -> no chests yet" with none), or "Rusty - holding
     * Cobblestone" while it holds something (vanilla keeps it in hand until one of its chests takes it). {@code base}
     * is the golem's own name, or {@link #DEFAULT_NAME}. Legacy colours: the name gold, the rest grey.
     */
    public static String tag(String base, int chests, String holding) {
        if (holding != null && !holding.isBlank()) {
            return "&6" + base + "&7" + HOLDING + holding;
        }
        return "&6" + base + "&7" + ARROW + (chests == 0 ? "no chests yet" : chests(chests));
    }

    /** "1 chest", "3 chests", "no chests". */
    public static String chests(int n) {
        return n == 0 ? "no chests" : n == 1 ? "1 chest" : n + " chests";
    }

    /** Whether a (plain, uncoloured) custom name is one of our tags. */
    public static boolean isTag(String plain) {
        return plain != null && (plain.contains(ARROW) || plain.contains(HOLDING));
    }

    /**
     * The base name: the custom name the golem has, if it isn't one of our tags (a name it was given with a name tag
     * is kept); else the base we stored when we first named it; else {@link #DEFAULT_NAME}.
     */
    public static String base(String customName, String storedBase) {
        if (customName != null && !customName.isBlank() && !isTag(customName)) {
            return customName;
        }
        if (storedBase != null && !storedBase.isBlank()) {
            return storedBase;
        }
        return DEFAULT_NAME;
    }

    /**
     * Whether a chest at {@code c} is in reach of a golem standing at {@code g}: the wiki's 65x17x65 search box
     * (32 blocks each way) and a chest no more than 1 up or 2 down from where it stands. For the brush's "that's a
     * long way" hint only; a chest is never dropped for being far (the golem walks).
     */
    public static boolean nearEnough(int gx, int gy, int gz, int cx, int cy, int cz) {
        return Math.abs(cx - gx) <= REACH_XZ && Math.abs(cz - gz) <= REACH_XZ
                && cy - gy <= REACH_UP && gy - cy <= REACH_DOWN;
    }

    /** "COBBLESTONE" as "Cobblestone", "OAK_LOG" as "Oak log": what the tag says the golem holds. */
    public static String pretty(String enumName) {
        if (enumName == null || enumName.isEmpty()) {
            return "";
        }
        String n = enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
