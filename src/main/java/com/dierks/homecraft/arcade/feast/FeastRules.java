package com.dierks.homecraft.arcade.feast;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Family Feast's rules (0.38.6), pure: no server, no clock, no randomness, so each one is pinned by
 * {@code FeastRulesTest}.
 *
 * <p><b>The food.</b> Plain cooked food is worth points (bread 1, cooked meat 2, pumpkin pie 3, cake 6); anything
 * else is refused and stays in the hand. A Harvest Basket treat is welcome at its food's points, checked before the
 * prize and name checks, so her basket pies count. Candy apples, other Arcade prizes, a game kit's items and anything
 * else with its own name are refused, so nobody's collection can vanish into the pot. Creative is never a refusal.
 *
 * <p><b>Fair.</b> One tap hands in one item, so a child never loses a whole stack; a sneak-tap (for adults, on Java
 * only) hands in the stack, up to what is left of the day's points. Points are added first and the items taken
 * after, rounded up from what was really added, so nothing is ever counted without food leaving the hand. Every
 * helper gets the same reward, once, however much they gave.
 *
 * <p><b>Names.</b> Everything kept is under the edition (config {@code edition}), never a literal year: its boards,
 * its markers and its reward.
 */
public final class FeastRules {

    /** The {@code game_scores} and {@code game_rewards} game the Feast's rows are kept under. */
    public static final String GAME = "feast";
    /** The plates on the Clubhouse tables. */
    public static final int CELLS = 20;
    /** The least time between two taps on the pot that both count (a Bedrock tap repeats while held). */
    public static final long TAP_GAP_MS = 600;
    /** The room's ceremony waits this long before it plays again for a newcomer. */
    public static final long ROOM_COOL_DOWN_MS = 60_000L;
    /** How long the room's ceremony fires its bursts. */
    public static final long ROOM_SHOW_MS = 20_000L;
    /** How long the fill's show fires over the pot. */
    public static final int HOME_SHOW_SECONDS = 30;
    /** How long the family bar stays up after a tap. */
    public static final long BAR_MS = 5_000L;
    /** The squares in the progress bar. */
    public static final int BAR_SQUARES = 20;
    /** Every key the Feast keeps in {@code hcm_meta} starts with this. */
    public static final String META_PREFIX = "feast.";
    /** The pot's block: {@code world,x,y,z}. */
    public static final String POT_KEY = META_PREFIX + "pot";

    /** Points for each food, by material name. Anything not here is not food for the Feast. */
    static final Map<String, Integer> POINTS = Map.ofEntries(
            Map.entry("BREAD", 1), Map.entry("BAKED_POTATO", 1), Map.entry("COOKIE", 1), Map.entry("APPLE", 1),
            Map.entry("CARROT", 1), Map.entry("COOKED_COD", 1), Map.entry("COOKED_SALMON", 1),
            Map.entry("MELON_SLICE", 1),
            Map.entry("COOKED_CHICKEN", 2), Map.entry("COOKED_BEEF", 2), Map.entry("COOKED_PORKCHOP", 2),
            Map.entry("COOKED_MUTTON", 2), Map.entry("COOKED_RABBIT", 2), Map.entry("MUSHROOM_STEW", 2),
            Map.entry("BEETROOT_SOUP", 2), Map.entry("GOLDEN_CARROT", 2),
            Map.entry("PUMPKIN_PIE", 3), Map.entry("RABBIT_STEW", 3),
            Map.entry("CAKE", 6));

    /** What the plates show, cell by cell: a feast of every kind. */
    static final List<String> PLATES = List.of("PUMPKIN_PIE", "BREAD", "COOKED_CHICKEN", "BAKED_POTATO", "CAKE",
            "COOKED_BEEF", "APPLE", "CARROT", "COOKIE", "MELON_SLICE");

    private FeastRules() {
    }

    // ---- the food ---------------------------------------------------------------------------------

    /** A food's points, or 0 for anything that isn't food for the Feast. */
    public static int points(String material) {
        Integer p = material == null ? null : POINTS.get(material);
        return p == null ? 0 : p;
    }

    /** Why the pot says no, or {@link Refusal#NONE} when it takes the item. */
    public enum Refusal {
        /** It takes it. */
        NONE,
        /** The Feast isn't open (or no pot is set). */
        CLOSED,
        /** Not food for the Feast. */
        NOT_FOOD,
        /** A candy apple: hers to keep. */
        CANDY,
        /** A game kit's item. */
        KIT,
        /** Another Arcade prize. */
        PRIZE,
        /** Something with its own name. */
        NAMED
    }

    /**
     * Whether the pot takes an item.
     *
     * @param material     the item's material name
     * @param harvestItem  a Harvest Basket treat: welcome at its food's points, before the prize and name checks
     * @param tokenPrize   any other Arcade prize (it carries the prize tag)
     * @param candy        a candy apple
     * @param kit          a game kit's item
     * @param customNamed  it has a name of its own
     * @param open         the Feast is open and the pot is set
     */
    public static Refusal refusal(String material, boolean harvestItem, boolean tokenPrize, boolean candy,
                                  boolean kit, boolean customNamed, boolean open) {
        if (!open) {
            return Refusal.CLOSED;
        }
        if (points(material) <= 0) {
            return Refusal.NOT_FOOD;
        }
        if (harvestItem) {
            return Refusal.NONE;
        }
        if (candy) {
            return Refusal.CANDY;
        }
        if (kit) {
            return Refusal.KIT;
        }
        if (tokenPrize) {
            return Refusal.PRIZE;
        }
        return customNamed ? Refusal.NAMED : Refusal.NONE;
    }

    /**
     * Whether a tap hands in the whole stack: a sneak-tap on Java only. On Bedrock's touch controls crouch is a toggle
     * that stays on, and a small child turns it on without knowing, so there every tap hands in one item.
     */
    public static boolean wholeStack(boolean sneaking, boolean bedrock) {
        return sneaking && !bedrock;
    }

    /**
     * How many items a tap hands in: one, or the whole stack when sneaking, never more than the day has points left
     * for ({@code leftToday} below 0: no limit). 0 means "full for today" (the soft note), and nothing is taken.
     *
     * @param held      the items in the hand
     * @param each      one item's points
     * @param sneaking  a sneak-tap: the stack
     * @param leftToday the player's points left today, or -1 for no limit
     */
    public static int want(int held, int each, boolean sneaking, long leftToday) {
        if (held <= 0 || each <= 0) {
            return 0;
        }
        long n = sneaking ? held : 1;
        if (leftToday >= 0) {
            n = Math.min(n, leftToday / each);
        }
        return (int) Math.max(0, n);
    }

    /** The items taken for {@code added} points at {@code each} a piece: rounded up, never fewer than counted. */
    public static int taken(long added, int each) {
        if (added <= 0 || each <= 0) {
            return 0;
        }
        return (int) ((added + each - 1) / each);
    }

    // ---- the table --------------------------------------------------------------------------------

    /** Whether {@code total} fills a table of {@code goal}. */
    public static boolean filled(long total, int goal) {
        return goal > 0 && total >= goal;
    }

    /** How many of {@code cells} plates have food: one more as the total passes each {@code 1/cells} of the goal. */
    public static int plates(long total, int goal, int cells) {
        if (total <= 0 || goal <= 0 || cells <= 0) {
            return 0;
        }
        if (total >= goal) {
            return cells;
        }
        return (int) Math.min(cells, total * cells / goal);
    }

    /** The food on plate {@code cell}, by material name: every kind round the table. */
    public static String kindAt(int cell) {
        return PLATES.get(Math.floorMod(cell, PLATES.size()));
    }

    /** "&amp;6Family Feast &amp;f137 / 150": the table's line. */
    public static String line(long total, int goal) {
        return "&6Family Feast &f" + Math.min(total, goal) + " / " + goal;
    }

    /** The bar of squares under the line: green as it fills, grey still to go. Plain ASCII, for Bedrock too. */
    public static String bar(long total, int goal) {
        int on = plates(total, goal, BAR_SQUARES);
        return "&a" + "|".repeat(on) + "&8" + "|".repeat(BAR_SQUARES - on);
    }

    /** The chime's pitch for a tap: it rises as the table fills, 0.6 empty to 2.0 full. */
    public static float pitch(long total, int goal) {
        if (goal <= 0) {
            return 1.0f;
        }
        double f = Math.max(0.0, Math.min(1.0, (double) total / goal));
        return (float) (0.6 + 1.4 * f);
    }

    /** "A", "A and B", "A, B and C": the helpers' names, in the order given. */
    public static String thankful(List<String> names) {
        List<String> n = new ArrayList<>();
        for (String s : names == null ? List.<String>of() : names) {
            if (s != null && !s.isBlank()) {
                n.add(s.trim());
            }
        }
        if (n.isEmpty()) {
            return "";
        }
        if (n.size() == 1) {
            return n.get(0);
        }
        return String.join(", ", n.subList(0, n.size() - 1)) + " and " + n.get(n.size() - 1);
    }

    /** "&amp;6Thankful for: &amp;fDad, Sam, Lee and Lily!": the line everyone sees. */
    public static String thankfulLine(List<String> names) {
        return "&6Thankful for: &f" + thankful(names) + "&6!";
    }

    /**
     * The helpers in the order of their first gift: {@code order} first (as written), then any helper it doesn't
     * name (a first gift whose order line couldn't be written), as {@code helpers} gives them.
     */
    public static List<UUID> ordered(List<UUID> order, Collection<UUID> helpers) {
        Set<UUID> h = new LinkedHashSet<>(helpers == null ? List.of() : helpers);
        List<UUID> out = new ArrayList<>();
        for (UUID id : order == null ? List.<UUID>of() : order) {
            if (h.remove(id)) {
                out.add(id);
            }
        }
        out.addAll(h);
        return out;
    }

    /** The order list as stored: the ids joined by commas. */
    public static String orderText(List<UUID> order) {
        List<String> s = new ArrayList<>();
        for (UUID id : order) {
            s.add(id.toString());
        }
        return String.join(",", s);
    }

    /** The order list read back; anything that isn't an id is skipped, and each id is kept once. */
    public static List<UUID> orderOf(String text) {
        Set<UUID> out = new LinkedHashSet<>();
        if (text != null) {
            for (String part : text.split(",")) {
                try {
                    out.add(UUID.fromString(part.trim()));
                } catch (IllegalArgumentException e) {
                    // not an id: skipped
                }
            }
        }
        return new ArrayList<>(out);
    }

    // ---- the moments ------------------------------------------------------------------------------

    /**
     * Whether the room's ceremony plays now: the room is open, a helper who hasn't seen it is inside, the room isn't
     * closing for a restart, no podium photo is waiting, and the last one was a minute ago or more.
     */
    public static boolean roomCeremonyTurn(boolean roomOpen, boolean unseenHelperInside, boolean restartHold,
                                           boolean photoPending, boolean cooledDown) {
        return roomOpen && unseenHelperInside && !restartHold && !photoPending && cooledDown;
    }

    /** Whether a helper gets their own moment now: the tables are full, they helped, haven't seen it, and are home. */
    public static boolean personalMomentDue(boolean done, boolean isHelper, boolean seen, boolean home) {
        return done && isHelper && !seen && home;
    }

    /**
     * Whether a helper is given their Turkey Mini now: the tables are full, they helped, they haven't had it, they are
     * home, and the edition has a Mini at all ({@link #miniId}).
     */
    public static boolean miniDue(boolean done, boolean isHelper, boolean given, boolean home, boolean hasMini) {
        return done && isHelper && !given && home && hasMini;
    }

    /** Whether a helper is paid: always, unless Creative helpers aren't and they never gave outside Creative. */
    public static boolean paid(boolean creativeHelpersPaid, long nonCreativeGifts) {
        return creativeHelpersPaid || nonCreativeGifts > 0;
    }

    // ---- the edition's names ----------------------------------------------------------------------

    /** A real Feast's edition: four digits, a year. Its reward can't be reset, and it gives a Turkey Mini. */
    public static boolean yearEdition(String edition) {
        return edition != null && edition.matches("[0-9]{4}");
    }

    /** The Turkey Mini of a year's edition ({@code turkey_mini_<year>}), or null: a test edition gives none. */
    public static String miniId(String edition) {
        return yearEdition(edition) ? "turkey_mini_" + edition : null;
    }

    /**
     * Whether {@code /hcm feast reset confirm} may wipe the edition: a test one always, a year's only before it gave
     * anything. Pay alone is not enough to look at: with {@code tokens: 0}, or Creative helpers not paid, a full table
     * writes no reward row, yet each helper got a Turkey Mini, and a reset would wipe its mark and give a second.
     *
     * @param rewardRows the edition's reward rows
     * @param done whether its table has filled (the {@code done} marker, written before any pay or Mini)
     * @param minisGiven whether any helper's Turkey Mini mark is set
     */
    public static boolean resetAllowed(String edition, int rewardRows, boolean done, boolean minisGiven) {
        return !yearEdition(edition) || (rewardRows <= 0 && !done && !minisGiven);
    }

    /** The edition's points board: {@code feast:<ed>}. */
    public static String board(String edition) {
        return "feast:" + edition;
    }

    /** The day's own points board, under the edition: {@code feast:<ed>:d<epochDay>}. */
    public static String dayBoard(String edition, long epochDay) {
        return board(edition) + ":d" + epochDay;
    }

    /** The prefix of every counter the edition keeps per player. */
    public static String counterPrefix(String edition) {
        return "feast:" + edition + ":";
    }

    /** A helper has had their own moment. */
    public static String seenKey(String edition) {
        return counterPrefix(edition) + "seen";
    }

    /** A helper has seen the room's ceremony. */
    public static String roomSeenKey(String edition) {
        return counterPrefix(edition) + "room_seen";
    }

    /** A helper gave something outside Creative (counts every such gift). */
    public static String survivalKey(String edition) {
        return counterPrefix(edition) + "survival";
    }

    /** A helper has had their Turkey Mini (written before it is minted). */
    public static String miniKey(String edition) {
        return counterPrefix(edition) + "mini";
    }

    /** The reward's event id: {@code feast-<ed>} (its ref is {@code event:feast-<ed>}). */
    public static String eventId(String edition) {
        return "feast-" + edition;
    }

    /** The edition's markers' prefix in {@code hcm_meta}: {@code feast.<ed>.}. */
    public static String metaPrefix(String edition) {
        return META_PREFIX + edition + ".";
    }

    /** The tables were filled (written before anything is paid or shown). */
    public static String doneKey(String edition) {
        return metaPrefix(edition) + "done";
    }

    /** The fill's show over the pot was given. */
    public static String homeShownKey(String edition) {
        return metaPrefix(edition) + "home_shown";
    }

    /** The helpers in the order of their first gift. */
    public static String orderKey(String edition) {
        return metaPrefix(edition) + "order";
    }

    // ---- what the Clubhouse reads -----------------------------------------------------------------

    /**
     * The Feast as the Clubhouse's tables draw it, from memory (never a database read).
     *
     * @param open    the Feast is open now (outside its window the tables are empty)
     * @param total   the family's points
     * @param goal    the points that fill the tables
     * @param plates  the plates with food, of {@link #CELLS}
     * @param done    the tables were filled
     * @param helpers how many gave something
     */
    public record FeastView(boolean open, long total, int goal, int plates, boolean done, int helpers) {

        /** Nothing to draw: the Feast isn't running. */
        public static final FeastView CLOSED = new FeastView(false, 0, 0, 0, false, 0);
    }

    // ---- the pot ----------------------------------------------------------------------------------

    /** A block's place: a world and three whole numbers. */
    public record Spot(String world, int x, int y, int z) {

        /** {@code world,x,y,z}: as kept under {@link #POT_KEY}. */
        public String text() {
            return world + "," + x + "," + y + "," + z;
        }

        /** {@code text()} read back, or null for anything else. */
        public static Spot of(String text) {
            if (text == null) {
                return null;
            }
            String[] p = text.split(",");
            if (p.length != 4 || p[0].isBlank()) {
                return null;
            }
            try {
                return new Spot(p[0].trim(), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()),
                        Integer.parseInt(p[3].trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** Whether a block at (x, y, z) of {@code worldName} is this one (the world by name, any case). */
        public boolean is(String worldName, int bx, int by, int bz) {
            return worldName != null && worldName.equalsIgnoreCase(world) && bx == x && by == y && bz == z;
        }
    }
}
