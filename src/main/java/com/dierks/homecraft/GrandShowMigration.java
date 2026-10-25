package com.dierks.homecraft;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.dierks.homecraft.HomeCraftManagement.WARN;

/**
 * Config revision 23: the Grand Firework Show and a gentler Candy Apple shower (the owner's requests, 25 Oct).
 *
 * <p>"She wanted to ask if we can make a little more expensive one that would be a bigger firework show (I think she
 * means longer)": the {@code firework_grand} row (15 seconds with a finale, 40 tokens) goes in right after
 * {@code firework_show}. "You can tune back the candy apples. I have about 60 of them at this point and we got the
 * bag": a shower every 60 minutes instead of 20 (every 30 instead of 10 on the last day), with 3 candy apples instead
 * of 6.
 *
 * <p><b>The rule</b> is every revision's ({@link EconomyMigration}, {@link ShopMigration}). The new row is added once,
 * only while no row has its id: right after {@code firework_show}, or at the end of the list when the owner deleted
 * that one (the counter groups rows by tab, so where a row sits never matters). An id the owner already uses for a
 * row of their own keeps their row, with one {@link HomeCraftManagement#WARN} and the shipped row as a line to paste.
 * An absent list is left to the backfill, which writes the bundled one. Each candy value moves only from what
 * 0.37.1-0.38.0 shipped ({@link #CANDY}); a value already at the new default stays; anything else is the owner's and
 * stays, with one WARN naming the key and the new default ({@link HomeCraftManagement#retune}'s contract). An absent
 * key is left to the backfill, which writes the new default.
 *
 * <p><b>The tables are data</b>, frozen as literals ({@link #GRAND}, {@link #CANDY}) and never read from the bundled
 * file, so a later release that edits the row or the candy can't change what revision 23 did. {@code
 * GrandShowMigrationTest} pins every old value to 0.38.0's config.yml and the new ones to this release's.
 *
 * <p>The one comment it makes stale (the Prize Counter's header, whose firework line said "three-second") is
 * {@link ShippedComments}' revision-23 row. A second run changes nothing.
 */
final class GrandShowMigration {

    /** The config revision this step brings: {@code HomeCraftManagement.CONFIG_REVISION} 23. */
    static final int REVISION = 23;

    /** The list the row lives in. */
    static final String PRIZES = ShopMigration.PRIZES;
    /** The row the Grand Firework Show goes in after. */
    static final String AFTER = "firework_show";

    /** The row revision 23 adds, as 0.38.1 ships it. */
    static final Map<String, Object> GRAND = row("id", "firework_grand", "tab", "COSMETICS",
            "display", "&6Grand Firework Show", "description", List.of("A long firework show", "with a big finale!"),
            "cost_tokens", 40, "icon", row("texture", "", "material", "FIREWORK_ROCKET"), "type", "firework",
            "seconds", 15);

    /**
     * One candy value revision 23 moves.
     *
     * @param path its full config key
     * @param old  what 0.37.1, 0.37.2, 0.37.3 and 0.38.0 shipped
     * @param now  the new default
     */
    record Step(String path, int old, int now) {
    }

    /** The candy values revision 23 moves, in config order. */
    static final List<Step> CANDY = List.of(
            new Step("arcade.events.candy.every_minutes", 20, 60),
            new Step("arcade.events.candy.last_day_every_minutes", 10, 30),
            new Step("arcade.events.candy.apples_per_shower", 6, 3));

    private GrandShowMigration() {
    }

    /** Run revision 23's two steps against {@code c} (defaults-free), appending what they did to {@code log}. */
    static void apply(FileConfiguration c, List<String> log) {
        grandShow(c, log);
        candy(c, log);
    }

    // ---- step 1: the Grand Firework Show --------------------------------------------------------

    private static void grandShow(FileConfiguration c, List<String> log) {
        if (!(c.get(PRIZES, null) instanceof List<?> raw)) {
            return; // absent: the backfill writes the whole bundled list
        }
        String id = ShopMigration.idOf(GRAND);
        int after = -1;
        Map<String, Object> theirs = null;
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> r = asRow(raw.get(i));
            if (r == null) {
                continue;
            }
            String rid = ShopMigration.idOf(r);
            if (rid.equals(id) && theirs == null) {
                theirs = r;
            } else if (rid.equals(AFTER) && after < 0) {
                after = i;
            }
        }
        if (theirs != null) {
            if (!ShopMigration.same(theirs, GRAND)) {
                log.add(WARN + "Config migration: kept your own " + PRIZES + " → " + id + " row: 0.38.1 ships a row"
                        + " with that id. To take 0.38.1's instead, put this line in place of yours:");
                log.add(WARN + "  " + ArcadeConfigMigration.flowRow(GRAND));
            }
            return;
        }
        List<Object> out = new ArrayList<>(raw);
        out.add(after < 0 ? out.size() : after + 1, ShopMigration.copy(GRAND));
        c.set(PRIZES, out);
        log.add("Config migration: Prize Counter - added the Grand Firework Show (" + id + ": 15 seconds of fireworks"
                + " with a big finale, 40 tokens)" + (after < 0 ? " at the end of the list" : " after the Firework Show")
                + ".");
    }

    // ---- step 2: the Candy Apple shower ---------------------------------------------------------

    private static void candy(FileConfiguration c, List<String> log) {
        List<String> moved = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (Step s : CANDY) {
            Object current = c.get(s.path(), null);
            if (current == null || is(current, s.now())) {
                continue; // absent: the backfill writes the new default; or already there
            }
            if (is(current, s.old())) {
                c.set(s.path(), s.now());
                moved.add(leaf(s.path()) + " " + s.old() + " → " + s.now());
            } else {
                kept.add(WARN + "Config migration: kept " + s.path() + " = " + current + " because you have changed it"
                        + " (the new default is " + s.now() + ").");
            }
        }
        if (!moved.isEmpty()) {
            log.add("Config migration: arcade.events.candy - " + String.join(", ", moved) + " (a gentler Candy Apple"
                    + " shower: a shower an hour, 3 candy apples each).");
        }
        log.addAll(kept);
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
