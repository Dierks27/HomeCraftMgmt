package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.MegaShow.Spot;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.DyeColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The Firework Remote's own rules (0.38.2; the owner: "a launcher tool that we can assign the launchers that we want
 * to use in the show. That way I can build launchers around the city, leave them there, and when I buy the 5 launcher
 * show or the 4 launcher show or the 3 launcher show I can then simply tag/click on the launchers I want to use", and
 * "a way to tag it so I can see what launcher is what on a map or in real life"). Pure, so the stored set, the
 * toggling, every line and the held-tap rule are tested with no server; {@link RemoteService} asks them.
 *
 * <p><b>A player's show set</b> is up to {@value #MAX_LINKED} launchers (the Ultimate show's size), in the order they
 * were tapped: #1 fires first, the first lane of every show. A tap on one in the set takes it off, and the ones after
 * it move up. It is one {@code game_prefs} row ({@value #PREF}), {@code "v1|x,y,z,world|..."} ({@link #format},
 * {@link #parse}).
 *
 * <p><b>Telling them apart</b>: each number has its own colour ({@link #NUMBER_DYES}, ten distinct dyes and chat
 * colours), the same on the hologram over the launcher, the Show Map's flag, the My Show tile and the Remote's lore,
 * so a Bedrock player who sees no map caption still matches them by colour.
 */
public final class RemoteRules {

    /** The most launchers a set holds: the biggest show's ({@link MegaShow#MAX_LAUNCHERS}). */
    public static final int MAX_LINKED = MegaShow.MAX_LAUNCHERS;
    /** The {@code game_prefs} row of a player's set. */
    public static final String PREF = "arcade.launcher.linked";
    /** The {@code game_prefs} row of a player's Show Map: its map id. */
    public static final String MAP_PREF = "arcade.launcher.show_map";
    /** The stored set's version (its first field). */
    static final String VERSION = "v1";
    /** The Remote's use line, under the row's description. */
    public static final String USE_LINE = "&7Tap a launcher to add it or take it off. Tap anywhere else for My Show.";
    /** An 11th tap. */
    public static final String FULL = "&7Your show has " + MAX_LINKED + " launchers already. Tap one of them to take it"
            + " off first!";
    /** A set emptied with My Show's Clear all. */
    public static final String CLEARED = "&7Your show is cleared: a show uses the launchers nearest you again.";
    /** A pref write that failed: nothing changed. */
    public static final String NOT_SAVED = "&cCouldn't save that - try again.";
    /** A set with nothing in it, on the Remote's lore. */
    static final String NOTHING_LINKED = "&8Nothing linked yet.";

    /** #1-#10's colours: ten distinct banner dyes (the Show Map's flags) and wools (My Show's tiles). */
    public static final List<DyeColor> NUMBER_DYES = List.of(DyeColor.RED, DyeColor.ORANGE, DyeColor.YELLOW,
            DyeColor.LIME, DyeColor.GREEN, DyeColor.CYAN, DyeColor.LIGHT_BLUE, DyeColor.BLUE, DyeColor.PURPLE,
            DyeColor.MAGENTA);
    /** #1-#10's chat colours, the nearest to each dye: ten distinct codes. */
    static final List<String> CODES = List.of("&c", "&6", "&e", "&a", "&2", "&3", "&b", "&9", "&5", "&d");

    /** The eight ways, clockwise from north (-Z). */
    private static final String[] WAYS = {"north", "north-east", "east", "south-east", "south", "south-west", "west",
            "north-west"};
    /** Nearer than this (blocks, across) is "right here". */
    static final double HERE = 3.0;
    /** How far a stored x or z may be (the world border's), and y. */
    private static final int MAX_XZ = 30_000_000;
    private static final int MIN_Y = -2048;
    private static final int MAX_Y = 2047;

    private RemoteRules() {
    }

    // ---- numbers and colours -------------------------------------------------------------------------

    /** Number {@code n}'s (1-10) dye. */
    public static DyeColor dye(int n) {
        return NUMBER_DYES.get(Math.floorMod(n - 1, NUMBER_DYES.size()));
    }

    /** Number {@code n}'s (1-10) chat colour code. */
    public static String code(int n) {
        return CODES.get(Math.floorMod(n - 1, CODES.size()));
    }

    /** Number {@code n}'s wool, as a {@code Material} name (the runtime looks it up). */
    public static String wool(int n) {
        return dye(n).name() + "_WOOL";
    }

    /** "#3" in number 3's colour. */
    public static String tag(int n) {
        return code(n) + "#" + n;
    }

    // ---- the stored set ------------------------------------------------------------------------------

    /** A stored set, read: its launchers in link order, and whether anything was dropped on the way. */
    public record Parsed(List<Spot> spots, boolean cleaned, boolean newer) {
        public Parsed {
            spots = List.copyOf(spots);
        }
    }

    /**
     * A set as stored: {@code "v1"}, then {@code "|x,y,z,world"} a launcher in link order (the world last: it may hold
     * a comma), or null for an empty set (the row is removed). A world whose name holds {@code |} can't be read back,
     * so its launcher is left out, with one WARN.
     */
    public static String format(List<Spot> spots, Consumer<String> warn) {
        if (spots == null || spots.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(VERSION);
        for (Spot s : spots) {
            if (s.world().indexOf('|') >= 0) {
                if (warn != null) {
                    warn.accept("A Firework Remote can't keep a launcher in the world '" + s.world() + "' (its name has"
                            + " a |) - left out of the show.");
                }
                continue;
            }
            sb.append('|').append(s.x()).append(',').append(s.y()).append(',').append(s.z()).append(',')
                    .append(s.world());
        }
        return sb.length() == VERSION.length() ? null : sb.toString();
    }

    /**
     * A stored set ({@link #format}'s): null or blank is an empty set. Another version (a newer HomeCraft wrote it) is
     * read as empty and {@code newer}, and left as it is until the player changes their set. An entry that can't be
     * read (not three whole numbers and a world, or a number off the world), a second copy of one, or one past the
     * {@value #MAX_LINKED}th is dropped, and {@code cleaned} says so: the caller writes the clean set back.
     */
    public static Parsed parse(String value) {
        if (value == null || value.isBlank()) {
            return new Parsed(List.of(), false, false);
        }
        String[] parts = value.trim().split("\\|", -1);
        if (!parts[0].equals(VERSION)) {
            return new Parsed(List.of(), false, true);
        }
        Set<Spot> out = new LinkedHashSet<>();
        boolean cleaned = false;
        for (int i = 1; i < parts.length; i++) {
            Spot s = entry(parts[i]);
            if (s == null || out.size() >= MAX_LINKED || !out.add(s)) {
                cleaned = true;
            }
        }
        return new Parsed(new ArrayList<>(out), cleaned, false);
    }

    /** One stored launcher, or null. */
    private static Spot entry(String e) {
        String[] f = e.split(",", 4);
        if (f.length != 4 || f[3].isBlank()) {
            return null;
        }
        try {
            int x = Integer.parseInt(f[0].trim());
            int y = Integer.parseInt(f[1].trim());
            int z = Integer.parseInt(f[2].trim());
            if (Math.abs(x) > MAX_XZ || Math.abs(z) > MAX_XZ || y < MIN_Y || y > MAX_Y) {
                return null;
            }
            return new Spot(f[3], x, y, z);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ---- linking and unlinking -----------------------------------------------------------------------

    /** What a tap with the Remote did. */
    public enum Result {
        /** it went on the end of the set */
        LINKED,
        /** it was in the set and came off; the ones after it moved up */
        UNLINKED,
        /** the set is full: nothing changed */
        FULL
    }

    /** A tap's result, the set after it, and the number it had or now has (0 when full). */
    public record Toggle(Result result, List<Spot> set, int number) {
        public Toggle {
            set = List.copyOf(set);
        }
    }

    /** A tap on {@code s} with the Remote: in the set, off it; else on the end, unless the set is full. */
    public static Toggle toggle(List<Spot> set, Spot s) {
        int at = set.indexOf(s);
        if (at >= 0) {
            List<Spot> out = new ArrayList<>(set);
            out.remove(at);
            return new Toggle(Result.UNLINKED, out, at + 1);
        }
        if (set.size() >= MAX_LINKED) {
            return new Toggle(Result.FULL, set, 0);
        }
        List<Spot> out = new ArrayList<>(set);
        out.add(s);
        return new Toggle(Result.LINKED, out, out.size());
    }

    /** {@code s}'s number in {@code set} (1 fires first), or 0 when it isn't in it. */
    public static int linkedAs(List<Spot> set, Spot s) {
        return set == null ? 0 : set.indexOf(s) + 1;
    }

    /** "✔ Linked as #2! (2 linked)" */
    public static String linkedLine(int n, int count) {
        return "&a✔ Linked as " + tag(n) + "&a! &7(" + count + " linked)";
    }

    /** "Took #2 off your show. The ones after it moved up. (3 linked)" */
    public static String unlinkedLine(int n, int count, boolean last) {
        return "&7Took #" + n + " off your show." + (last ? "" : " The ones after it moved up.") + " &8(" + count
                + " linked)";
    }

    // ---- where one is ------------------------------------------------------------------------------

    /**
     * The way to a launcher {@code dx}, {@code dz} blocks off (north is -Z): one of the eight, or "right here" within
     * {@value #HERE} blocks.
     */
    public static String direction(double dx, double dz) {
        if (Math.hypot(dx, dz) < HERE) {
            return "right here";
        }
        double deg = Math.toDegrees(Math.atan2(dx, -dz));
        return WAYS[Math.floorMod((int) Math.round(deg / 45.0), WAYS.length)];
    }

    /** How far across, in whole blocks. */
    public static int blocks(double dx, double dz) {
        return (int) Math.round(Math.hypot(dx, dz));
    }

    /** "north-east, 45 blocks", or "right here", from a viewer at ({@code vx}, {@code vz}). */
    public static String whereFrom(Spot s, double vx, double vz) {
        double dx = s.x() + 0.5 - vx;
        double dz = s.z() + 0.5 - vz;
        String way = direction(dx, dz);
        return way.equals("right here") ? way : way + ", " + blocks(dx, dz) + " blocks";
    }

    /**
     * A launcher's line on the Remote: "#3 · north-east, 45 blocks · x 120 y 70 z -340" from a viewer at ({@code vx},
     * {@code vz}) in {@code viewerWorld}, or "#4 · in world_nether" in another world.
     */
    public static String loreLine(int n, Spot s, String viewerWorld, double vx, double vz) {
        if (!s.world().equals(viewerWorld)) {
            return tag(n) + " &7· in " + s.world();
        }
        return tag(n) + " &7· " + whereFrom(s, vx, vz) + " · x " + s.x() + " y " + s.y() + " z " + s.z();
    }

    /**
     * Linked launcher {@code n}'s My Show tile name (review REMOTE-MYSHOW-5): every fact in the NAME, because Bedrock
     * shows lore only on a long press, and My Show is the daughter's screen. "#3 · north-east, 45 blocks · x 120 y 70
     * z -340 · yours"; "#4 · not loaded (300 blocks north) · x .. · Name's" when it can't fire now ({@code ready}
     * false: the show's own test, loaded and entity-ticking); "#5 · in world_nether · x .. · yours" in another world.
     * {@code whose} is "yours", "Name's", or null when its record couldn't be read (then nothing is said).
     */
    public static String tileName(int n, Spot s, String viewerWorld, double vx, double vz, boolean ready,
                                  String whose) {
        String where;
        if (!s.world().equals(viewerWorld)) {
            where = "in " + s.world();
        } else if (!ready) {
            double dx = s.x() + 0.5 - vx;
            double dz = s.z() + 0.5 - vz;
            String way = direction(dx, dz);
            where = "not loaded (" + (way.equals("right here") ? way : blocks(dx, dz) + " blocks " + way) + ")";
        } else {
            where = whereFrom(s, vx, vz);
        }
        return tag(n) + " &7· " + where + " · x " + s.x() + " y " + s.y() + " z " + s.z()
                + (whose == null ? "" : " · " + whose);
    }

    /**
     * Whether a tap on a launcher is the off hand's Firework Remote (review REMOTE-TAP-3): nothing of ours in the
     * hand that tapped ({@code tapping}, the main hand's kind) and a Remote in the off hand. A Java tap on a dropper
     * is used up by the main hand (the client predicts the dropper opening), so no off-hand tap is ever sent: the
     * launcher's own tap is the Remote's then, as the holograms already show for a Remote in either hand.
     */
    public static boolean offHandTap(PrizeType tapping, PrizeType offHand) {
        return tapping == null && offHand == PrizeType.REMOTE;
    }

    /** The Remote's name: the facts in the NAME, because Bedrock shows lore only on a long press. */
    public static String remoteName(String display, int count) {
        return (display == null || display.isBlank() ? "&eFirework Remote" : display) + " &7(" + count + " linked)";
    }

    /** The Remote's lore: its row's description, "Your show:" and a line a launcher, the use line, "Arcade prize". */
    public static List<String> remoteLore(List<String> description, List<String> launcherLines) {
        List<String> out = new ArrayList<>();
        for (String line : description) {
            out.add("&7" + line);
        }
        out.add("&7Your show:");
        if (launcherLines.isEmpty()) {
            out.add(NOTHING_LINKED);
        } else {
            out.addAll(launcherLines);
        }
        out.add(USE_LINE);
        out.add("&8Arcade prize");
        return out;
    }

    /**
     * The hologram over linked launcher {@code n}: "#3" in its colour, and "Name's" under it when it is someone
     * else's ({@code owner}, null when it is the viewer's own).
     */
    public static List<String> markerLines(int n, String owner) {
        String number = code(n) + "&l#" + n; // the colour first: a colour code ends the bold before it
        return owner == null ? List.of(number) : List.of(number, "&7" + owner + "'s");
    }

    // ---- held taps ------------------------------------------------------------------------------------

    /**
     * When a Remote tap counts (a held use button repeats about five times a second, on Java every 4 ticks and a held
     * Bedrock tap too): only when the last tap on the same thing (that launcher, or {@link #SCREEN}) was
     * {@value #GAP_MS} ms or more before. Every tap restarts the gap, so a held tap is one toggle until it is let go,
     * and a quick tap on another launcher counts at once. My Show never opens within the gap of any tap (a Java tap
     * on a plain block also sends a tap at the air). Plain state, so a test drives it; main thread.
     */
    static final class Taps {
        /** The least time between two taps on one thing that both count. */
        static final long GAP_MS = 600;
        /** What a tap that opens My Show is about. */
        static final Object SCREEN = "screen";

        private record Tap(Object about, long at) {
        }

        private final Map<UUID, Tap> last = new HashMap<>();

        /** Whether {@code player}'s tap on {@code about} at {@code now} (ms) counts; it is the last tap either way. */
        boolean counts(UUID player, Object about, long now) {
            Tap before = last.put(player, new Tap(about, now));
            if (about.equals(SCREEN) && before != null && now - before.at() < GAP_MS) {
                return false; // the tap that just linked one, sent again at the air, or a held tap on the ground
            }
            return before == null || !before.about().equals(about) || now - before.at() >= GAP_MS;
        }

        /** The player quit: nothing of theirs is kept. */
        void forget(UUID player) {
            last.remove(player);
        }
    }

    /**
     * When a set that couldn't be read is read again (review REMOTE-PREF-6): the marker pass asks for a holder's set
     * twice a second, so a database that is locked or down would be a query and a WARN twice a second for each. After
     * a failed read, that player's set isn't read again for {@value #RETRY_MS} ms (it is empty for now, and a tap
     * says "Couldn't save that - try again."), and only the first failure since a good read warns. Plain state, so a
     * test drives it; main thread.
     */
    static final class Retries {
        /** How long after a failed read a player's set is read again. */
        static final long RETRY_MS = 5_000;

        private final Map<UUID, Long> until = new HashMap<>();

        /** Whether {@code player}'s set may be read at {@code now} (ms): never failed, or the pause is over. */
        boolean due(UUID player, long now) {
            Long u = until.get(player);
            return u == null || now >= u;
        }

        /** A read of {@code player}'s set failed at {@code now}: whether to warn (the first failure in a row). */
        boolean failed(UUID player, long now) {
            return until.put(player, now + RETRY_MS) == null;
        }

        /** A read worked: the next failure warns again. */
        void ok(UUID player) {
            until.remove(player);
        }
    }
}
