package com.dierks.homecraft;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.dierks.homecraft.HomeCraftManagement.WARN;

/**
 * Config revision 24: the Firework Remote and the bigger Mega shows (the owner's requests, 25 Oct).
 *
 * <p>"Let's make it so we can chain together up to 5 launchers!", "Or really as many launchers as you feel like you
 * could do!", and "when I buy the 5 launcher show or the 4 launcher show or the 3 launcher show I can then simply
 * tag/click on the launchers I want to use": the {@code firework_super} row (a minute from 4 Firework Launchers, 100
 * tokens), the {@code firework_ultra} row (5, 125 tokens) and the {@code firework_ultimate} row (a minute and a half
 * from 10, 200 tokens) go in right after {@code firework_mega}, and the {@code firework_remote} row (the Firework
 * Remote, 10 tokens) after them. "Each player can have up to 10 launchers" (the biggest show's size; placed at once,
 * 15, which the owner confirmed): {@code arcade.launcher.per_player} moves from 0.38.1's 3 to 15.
 *
 * <p><b>The rule</b> is every revision's ({@link GrandShowMigration}'s, which revision 23 keeps as it is). Each new
 * row ({@link #ADDED}, in order) is added once, only while no row has its id: right after the row it follows, or at
 * the end of the list when there is none (the owner deleted {@code firework_mega}; the counter groups rows by tab, so
 * where a row sits never matters). An id the owner already uses for a row of their own keeps their row, with one
 * {@link HomeCraftManagement#WARN} and the shipped row as a line to paste, and the rows after it still follow it. One
 * INFO line names what was added and where. An absent list is left to the backfill, which writes the bundled one.
 * {@code per_player} moves only from what 0.38.1 shipped ({@link #STEP}); a value already at 15 stays; anything else is
 * the owner's and stays, with one WARN naming the key and the new default ({@link HomeCraftManagement#retune}'s
 * contract); an absent key is left to the backfill, which writes 15. (0.38.1's {@code launcher} is a flow map, which
 * loads as a section, so the path works; it is written back in block style, as any backfilled section.)
 *
 * <p><b>The tables are data</b>, frozen as literals ({@link #ADDED}, {@link #STEP}) and never read from the bundled
 * file, so a later release that edits a row can't change what revision 24 did. {@code FireworkRemoteMigrationTest}
 * pins every old value to 0.38.1's config.yml and the new ones to this release's.
 *
 * <p>The new {@code arcade.holiday_shows} section needs no step: the backfill writes it, with its comment, at every
 * start. The two comments this revision makes stale (the Prize Counter's header, whose firework line said
 * "launchers (1-3)" and named no {@code remote}, and {@code arcade.launcher}'s, which said "(1-10, admins too)" and
 * named only the Mega show) are {@link ShippedComments}' revision-24 rows. A second run changes nothing.
 */
final class FireworkRemoteMigration {

    /** The config revision this step brings: {@code HomeCraftManagement.CONFIG_REVISION} 24. */
    static final int REVISION = 24;

    /** The list the rows live in. */
    static final String PRIZES = ShopMigration.PRIZES;
    /** The row the Super Firework Show goes in after. */
    static final String AFTER = "firework_mega";

    /** The Super Firework Show's row, as 0.38.2 ships it. */
    static final Map<String, Object> SUPER = row("id", "firework_super", "tab", "COSMETICS",
            "display", "&bSuper Firework Show", "description",
            List.of("A whole minute of fireworks", "from 4 Firework Launchers!"),
            "cost_tokens", 100, "icon", row("texture", "", "material", "FIREWORK_ROCKET"), "type", "firework",
            "seconds", 60, "launchers", 4);
    /** The Ultra Firework Show's row, as 0.38.2 ships it. */
    static final Map<String, Object> ULTRA = row("id", "firework_ultra", "tab", "COSMETICS",
            "display", "&6Ultra Firework Show", "description",
            List.of("A whole minute of fireworks", "from 5 Firework Launchers!"),
            "cost_tokens", 125, "icon", row("texture", "", "material", "FIREWORK_ROCKET"), "type", "firework",
            "seconds", 60, "launchers", 5);
    /** The Ultimate Firework Show's row, as 0.38.2 ships it. */
    static final Map<String, Object> ULTIMATE = row("id", "firework_ultimate", "tab", "COSMETICS",
            "display", "&dUltimate Firework Show", "description",
            List.of("A minute and a half of fireworks", "from 10 Firework Launchers!"),
            "cost_tokens", 200, "icon", row("texture", "", "material", "FIREWORK_ROCKET"), "type", "firework",
            "seconds", 90, "launchers", 10);
    /** The Firework Remote's row, as 0.38.2 ships it. */
    static final Map<String, Object> REMOTE = row("id", "firework_remote", "tab", "COSMETICS",
            "display", "&eFirework Remote", "description",
            List.of("Tap your Firework Launchers", "to pick them for your show!"),
            "cost_tokens", 10, "icon", row("texture", "", "material", "RECOVERY_COMPASS"), "type", "remote");

    /**
     * One row revision 24 adds.
     *
     * @param row       the row, as 0.38.2 ships it
     * @param after     the id of the row it goes in after (at the end of the list when there is none)
     * @param name      what the INFO line calls it
     * @param afterName what the INFO line calls the row it goes in after
     * @param what      what the INFO line says it is
     */
    record Added(Map<String, Object> row, String after, String name, String afterName, String what) {
    }

    /** The rows revision 24 adds, in the order it adds them: each right after the one before it. */
    static final List<Added> ADDED = List.of(
            new Added(SUPER, AFTER, "the Super Firework Show", "the Mega Firework Show",
                    "a minute of fireworks from 4 Firework Launchers, 100 tokens"),
            new Added(ULTRA, "firework_super", "the Ultra Firework Show", "the Super Firework Show",
                    "a minute of fireworks from 5 Firework Launchers, 125 tokens"),
            new Added(ULTIMATE, "firework_ultra", "the Ultimate Firework Show", "the Ultra Firework Show",
                    "a minute and a half of fireworks from 10 Firework Launchers, 200 tokens"),
            new Added(REMOTE, "firework_ultimate", "the Firework Remote", "the Ultimate Firework Show",
                    "tap launchers to pick them for your shows, 10 tokens"));

    /**
     * The value revision 24 moves.
     *
     * @param path its full config key
     * @param old  what 0.38.1 shipped
     * @param now  the new default
     * @param says what the new default means, for the INFO line
     */
    record Step(String path, int old, int now, String says) {
    }

    /** Placed at once a player: 0.38.1's 3, now 15. */
    static final Step STEP = new Step("arcade.launcher.per_player", 3, 15,
            "each player may have 15 Firework Launchers placed at once, enough for the Ultimate show's 10");

    private FireworkRemoteMigration() {
    }

    /** Run revision 24's two steps against {@code c} (defaults-free), appending what they did to {@code log}. */
    static void apply(FileConfiguration c, List<String> log) {
        shows(c, log);
        launchers(c, log);
    }

    // ---- step 1: the Super, Ultra and Ultimate shows and the Remote ---------------------------------------

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
                    log.add(WARN + "Config migration: kept your own " + PRIZES + " → " + id + " row: 0.38.2 ships a"
                            + " row with that id. To take 0.38.2's instead, put this line in place of yours:");
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

    // ---- step 2: 15 launchers a player --------------------------------------------------------------------

    private static void launchers(FileConfiguration c, List<String> log) {
        Object current = c.get(STEP.path(), null);
        if (current == null || is(current, STEP.now())) {
            return; // absent: the backfill writes the new default; or already there
        }
        if (is(current, STEP.old())) {
            c.set(STEP.path(), STEP.now());
            log.add("Config migration: arcade.launcher - per_player " + STEP.old() + " → " + STEP.now() + " ("
                    + STEP.says() + ").");
        } else {
            log.add(WARN + "Config migration: kept " + STEP.path() + " = " + current + " because you have changed it"
                    + " (the new default is " + STEP.now() + ").");
        }
    }

    /** A number with exactly the value {@code n}. */
    private static boolean is(Object v, int n) {
        return v instanceof Number num && num.doubleValue() == n;
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
