package com.dierks.homecraft;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.dierks.homecraft.HomeCraftManagement.WARN;

/**
 * Config revision 23: the Grand Firework Show, the Mega Firework Show and a gentler Candy Apple shower (the owner's
 * requests, 25 Oct).
 *
 * <p>"She wanted to ask if we can make a little more expensive one that would be a bigger firework show (I think she
 * means longer)": the {@code firework_grand} row (15 seconds with a finale, 40 tokens) goes in right after
 * {@code firework_show}. "Make one more level at like 75 that is even bigger: 3 droppers that I have to set up, a full
 * blown show with 3 firing locations", a minute long "for those special events": the {@code firework_launcher} row (a
 * Firework Launcher, 5 tokens) and the {@code firework_mega} row (a minute from 3 launchers, 75 tokens) go in right
 * after it. "You can tune back the candy apples. I have about 60 of them at this point and we got the bag": a shower
 * every 60 minutes instead of 20 (every 30 instead of 10 on the last day), with 3 candy apples instead of 6.
 *
 * <p><b>The rule</b> is every revision's ({@link EconomyMigration}, {@link ShopMigration}). Each new row ({@link #ADDED},
 * in order) is added once, only while no row has its id: right after the row it follows, or at the end of the list
 * when there is none (the owner deleted {@code firework_show}; the counter groups rows by tab, so where a row sits
 * never matters). An id the owner already uses for a row of their own keeps their row, with one
 * {@link HomeCraftManagement#WARN} and the shipped row as a line to paste, and the rows after it still follow it. One
 * INFO line names what was added and where.
 * An absent list is left to the backfill, which writes the bundled one. Each candy value moves only from what
 * 0.37.1-0.38.0 shipped ({@link #CANDY}); a value already at the new default stays; anything else is the owner's and
 * stays, with one WARN naming the key and the new default ({@link HomeCraftManagement#retune}'s contract). An absent
 * key is left to the backfill, which writes the new default. The INFO line says what the values that moved now mean,
 * and only those (review R23-CANDY-INFO); when the last day's interval moves to 30 past an owner's own shorter
 * {@code every_minutes}, one more WARN says the last day now gets fewer showers than the others.
 *
 * <p><b>The tables are data</b>, frozen as literals ({@link #ADDED}, {@link #CANDY}) and never read from the bundled
 * file, so a later release that edits a row or the candy can't change what revision 23 did. {@code
 * GrandShowMigrationTest} pins every old value to 0.38.0's config.yml and the new ones to this release's.
 *
 * <p>The one comment it makes stale (the Prize Counter's header, whose firework line said "three-second", and which
 * now names {@code launchers} and the {@code firework_launcher} block) is {@link ShippedComments}' revision-23 row. A
 * second run changes nothing.
 */
final class GrandShowMigration {

    /** The config revision this step brings: {@code HomeCraftManagement.CONFIG_REVISION} 23. */
    static final int REVISION = 23;

    /** The list the rows live in. */
    static final String PRIZES = ShopMigration.PRIZES;
    /** The row the Grand Firework Show goes in after. */
    static final String AFTER = "firework_show";

    /** The Grand Firework Show's row, as 0.38.1 ships it. */
    static final Map<String, Object> GRAND = row("id", "firework_grand", "tab", "COSMETICS",
            "display", "&6Grand Firework Show", "description", List.of("A long firework show", "with a big finale!"),
            "cost_tokens", 40, "icon", row("texture", "", "material", "FIREWORK_ROCKET"), "type", "firework",
            "seconds", 15);
    /** The Firework Launcher's row, as 0.38.1 ships it. */
    static final Map<String, Object> LAUNCHER = row("id", "firework_launcher", "tab", "COSMETICS",
            "display", "&6Firework Launcher", "description", List.of("Set up 3 of them", "for a Mega Firework Show!"),
            "cost_tokens", 5, "icon", row("texture", "", "material", "DROPPER"), "type", "block",
            "block", "firework_launcher");
    /** The Mega Firework Show's row, as 0.38.1 ships it. */
    static final Map<String, Object> MEGA = row("id", "firework_mega", "tab", "COSMETICS",
            "display", "&dMega Firework Show", "description",
            List.of("A whole minute of fireworks", "from your Firework Launchers!"),
            "cost_tokens", 75, "icon", row("texture", "", "material", "FIREWORK_ROCKET"), "type", "firework",
            "seconds", 60, "launchers", 3);

    /**
     * One row revision 23 adds.
     *
     * @param row       the row, as 0.38.1 ships it
     * @param after     the id of the row it goes in after (at the end of the list when there is none)
     * @param name      what the INFO line calls it
     * @param afterName what the INFO line calls the row it goes in after
     * @param what      what the INFO line says it is
     */
    record Added(Map<String, Object> row, String after, String name, String afterName, String what) {
    }

    /** The rows revision 23 adds, in the order it adds them: each right after the one before it. */
    static final List<Added> ADDED = List.of(
            new Added(GRAND, AFTER, "the Grand Firework Show", "the Firework Show",
                    "15 seconds of fireworks with a big finale, 40 tokens"),
            new Added(LAUNCHER, "firework_grand", "the Firework Launcher", "the Grand Firework Show",
                    "set up 3 for a Mega Firework Show, 5 tokens"),
            new Added(MEGA, "firework_launcher", "the Mega Firework Show", "the Firework Launcher",
                    "a minute of fireworks from 3 Firework Launchers, 75 tokens"));

    /**
     * One candy value revision 23 moves.
     *
     * @param path its full config key
     * @param old  what 0.37.1, 0.37.2, 0.37.3 and 0.38.0 shipped
     * @param now  the new default
     * @param says what the new default means, for the INFO line when this value moved
     */
    record Step(String path, int old, int now, String says) {
    }

    /** The candy values revision 23 moves, in config order. */
    static final List<Step> CANDY = List.of(
            new Step("arcade.events.candy.every_minutes", 20, 60, "a shower an hour"),
            new Step("arcade.events.candy.last_day_every_minutes", 10, 30, "every half hour on the last day"),
            new Step("arcade.events.candy.apples_per_shower", 6, 3, "3 candy apples each"));
    /** The every-day interval and the last day's, by {@link #CANDY}'s paths (the last-day check). */
    private static final Step EVERY = CANDY.get(0);
    private static final Step LAST_DAY = CANDY.get(1);

    private GrandShowMigration() {
    }

    /** Run revision 23's two steps against {@code c} (defaults-free), appending what they did to {@code log}. */
    static void apply(FileConfiguration c, List<String> log) {
        shows(c, log);
        candy(c, log);
    }

    // ---- step 1: the Grand and Mega Firework Shows ----------------------------------------------

    private static void shows(FileConfiguration c, List<String> log) {
        if (!(c.get(PRIZES, null) instanceof List<?> raw)) {
            return; // absent: the backfill writes the whole bundled list
        }
        List<Object> out = new ArrayList<>(raw);
        List<String> added = new ArrayList<>();
        for (Added a : ADDED) {
            String id = ShopMigration.idOf(a.row());
            int after = -1;
            Map<String, Object> theirs = null;
            for (int i = 0; i < out.size(); i++) {
                Map<String, Object> r = asRow(out.get(i));
                if (r == null) {
                    continue;
                }
                String rid = ShopMigration.idOf(r);
                if (rid.equals(id) && theirs == null) {
                    theirs = r;
                } else if (rid.equals(a.after()) && after < 0) {
                    after = i;
                }
            }
            if (theirs != null) {
                if (!ShopMigration.same(theirs, a.row())) {
                    log.add(WARN + "Config migration: kept your own " + PRIZES + " → " + id + " row: 0.38.1 ships a"
                            + " row with that id. To take 0.38.1's instead, put this line in place of yours:");
                    log.add(WARN + "  " + ArcadeConfigMigration.flowRow(a.row()));
                }
                continue;
            }
            out.add(after < 0 ? out.size() : after + 1, ShopMigration.copy(a.row()));
            added.add(a.name() + " (" + id + ": " + a.what() + ")"
                    + (after < 0 ? " at the end of the list" : " after " + a.afterName()));
        }
        if (added.isEmpty()) {
            return;
        }
        c.set(PRIZES, out);
        log.add("Config migration: Prize Counter - added " + joined(added) + ".");
    }

    /** "a", "a and b", "a, b and c". */
    private static String joined(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    // ---- step 2: the Candy Apple shower ---------------------------------------------------------

    private static void candy(FileConfiguration c, List<String> log) {
        List<String> moved = new ArrayList<>();
        List<String> says = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        boolean lastDayMoved = false;
        for (Step s : CANDY) {
            Object current = c.get(s.path(), null);
            if (current == null || is(current, s.now())) {
                continue; // absent: the backfill writes the new default; or already there
            }
            if (is(current, s.old())) {
                c.set(s.path(), s.now());
                moved.add(leaf(s.path()) + " " + s.old() + " → " + s.now());
                says.add(s.says());
                lastDayMoved = lastDayMoved || s == LAST_DAY;
            } else {
                kept.add(WARN + "Config migration: kept " + s.path() + " = " + current + " because you have changed it"
                        + " (the new default is " + s.now() + ").");
            }
        }
        if (!moved.isEmpty()) {
            log.add("Config migration: arcade.events.candy - " + String.join(", ", moved) + " (a gentler Candy Apple"
                    + " shower: " + String.join(", ", says) + ").");
        }
        log.addAll(kept);
        // An owner's own every_minutes under the last day's new 30 would make the last day the quieter one.
        if (lastDayMoved && c.get(EVERY.path(), null) instanceof Number every && every.intValue() > 0
                && every.intValue() < LAST_DAY.now()) {
            log.add(WARN + "Config migration: " + LAST_DAY.path() + " is now " + LAST_DAY.now() + ", more than your "
                    + leaf(EVERY.path()) + " = " + every + ", so the last day gets fewer showers than the others: set it"
                    + " to " + every.intValue() + " or less (0 = as " + leaf(EVERY.path()) + ") for more.");
        }
    }

    /** A number with exactly the value {@code n}. */
    private static boolean is(Object v, int n) {
        return v instanceof Number num && num.doubleValue() == n;
    }

    private static String leaf(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static Map<String, Object> asRow(Object o) {
        List<Map<String, Object>> rows = ArcadeConfigMigration.mapRows(Collections.singletonList(o));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }
}
