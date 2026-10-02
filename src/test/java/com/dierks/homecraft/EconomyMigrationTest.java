package com.dierks.homecraft;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.GameCatalog;
import com.dierks.homecraft.games.GameSpec;
import com.dierks.homecraft.games.TokenBalance;
import com.dierks.homecraft.games.arena.FallingFloors;
import com.dierks.homecraft.games.arena.FallingFloorsSettings;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.gen.DailyCourses;
import com.dierks.homecraft.games.gen.DailySettings;
import com.dierks.homecraft.games.gen.LayoutFixtures;
import com.dierks.homecraft.games.gen.LayoutGuard;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config revision 20, the whole-arcade token balance ({@link EconomyMigration}, BALANCE-SPEC §5.3),
 * against verbatim copies of 0.35.0's and 0.36.0's bundled config.yml.
 *
 * <p>Pinned here:
 * <ul>
 *   <li>a 0.36 file (the owner's) moves every value to the new default with no WARN, and after the
 *       backfill is exactly a fresh install's file; a 0.35 file moves every value too, and reads as the
 *       shipped token settings with no WARN;</li>
 *   <li>a value the owner changed is kept, with one WARN each naming the key and the new default
 *       (a number, a list and a Scratch Ticket row), and keeps its comment;</li>
 *   <li>the Scratch Ticket is one unit (the v4 audit, ECON00): a ticket retuned through its price or
 *       jackpot keeps its 0.36 prizes, with one WARN naming the key and what the new prizes would give
 *       back with it (99.4% on a 9-token ticket, 104.5% with a seed of 200), and keeps its comment;</li>
 *   <li>an absent key stays absent, and the backfill writes the new default;</li>
 *   <li>a file already at revision 20 is left alone, and the step run twice changes nothing more;</li>
 *   <li>the frozen table: every "old" is what 0.35 and 0.36 shipped, every "new" what this release
 *       ships, 64 values, each "new" a literal in {@link TokenBalance}'s source, never a constant (ECON04),
 *       and the ticket's companions what 0.35, 0.36 and this release ship;</li>
 *   <li>a moved key takes the bundled comment ("Keep each at or under 4" and "(each 0-10)" are gone),
 *       and each section gets one INFO line;</li>
 *   <li>a bare {@code games: false} skips the step and the backfill fills in the new defaults;</li>
 *   <li>revision 19's layout mark still runs on a 0.35 file, before it.</li>
 * </ul>
 */
class EconomyMigrationTest {

    /** Every value under {@code s} that isn't a section, by path. */
    private static Map<String, Object> leaves(ConfigurationSection s) {
        Map<String, Object> out = new LinkedHashMap<>();
        s.getValues(true).forEach((k, v) -> {
            if (!(v instanceof ConfigurationSection)) {
                out.put(k, v);
            }
        });
        return out;
    }

    /** A group of steps as the log shows it: {@code 5/10/20/40 → 10/15/25/50}, read from the table. */
    private static String group(String parent) {
        List<String> olds = new ArrayList<>();
        List<String> nows = new ArrayList<>();
        for (EconomyMigration.Step s : EconomyMigration.STEPS) {
            if (s.path().substring(0, s.path().lastIndexOf('.')).equals(parent)) {
                olds.add(String.valueOf(s.old()));
                nows.add(String.valueOf(s.now()));
            }
        }
        return String.join("/", olds) + " → " + String.join("/", nows);
    }

    private static List<String> warns(List<String> log) {
        return log.stream().filter(l -> l.startsWith(HomeCraftManagement.WARN)).toList();
    }

    /** The token settings of {@code p}: every game's that holds no place, and the token fields of those that do. */
    private static void assertShippedTokens(GamesConfig.Parsed p, String why) {
        assertEquals(GamesConfig.Common.defaults(), p.common(), why + ": the common keys");
        for (GameSpec<?> spec : GameCatalog.SPECS) {
            if (spec == DailyCourses.SPEC || spec == FallingFloors.SPEC || spec == Clubhouse.SPEC) {
                continue; // they hold a place, which 0.35 stood elsewhere: their token fields below
            }
            assertEquals(spec.defaults(), p.settings(spec), why + ": " + spec.id());
        }
        DailySettings fresh = p.settings(DailyCourses.SPEC);
        DailySettings d = DailySettings.defaults();
        assertEquals(d.rewards(), fresh.rewards(), why + ": the Fresh first finishes");
        assertEquals(d.goals(), fresh.goals(), why + ": the Star Chart goals");
        assertEquals(d.dailyCap(), fresh.dailyCap(), why + ": the Star Chart cap");
        FallingFloorsSettings floors = p.settings(FallingFloors.SPEC);
        assertEquals(FallingFloorsSettings.defaults().rewards(), floors.rewards(), why + ": Falling Floors' rewards");
        assertEquals(FallingFloorsSettings.defaults().dailyCap(), floors.dailyCap(), why + ": Falling Floors' cap");
    }

    // ---- (a) the files that shipped ----------------------------------------------------------------

    @Test
    void a036FileMovesEveryValueAndEndsExactlyAsAFreshInstall() {
        YamlConfiguration c = LayoutFixtures.v036();
        assertEquals(19, c.getInt("config_revision"), "fixture: 0.36 is revision 19");
        List<String> log = HomeCraftManagement.migrateConfig(c, "world");
        assertEquals(List.of(), warns(log), "nothing the owner changed: no WARN");
        for (EconomyMigration.Step s : EconomyMigration.STEPS) {
            assertTrue(EconomyMigration.same(c.get(s.path()), s.now()), s.path() + " is the new default: " + c.get(s.path()));
        }
        assertEquals(HomeCraftManagement.CONFIG_REVISION, c.getInt("config_revision"), "stamped 20");
        HomeCraftManagement.backfillConfig(c, LayoutFixtures.bundled());
        YamlConfiguration after = LayoutFixtures.reread(c);
        YamlConfiguration bundled = LayoutFixtures.bundled();
        assertEquals(leaves(bundled), leaves(after), "every value as a fresh install of this release has it");
        List<String> gamesWarns = new ArrayList<>();
        GamesConfig.Parsed p = GamesConfig.parse(after, gamesWarns::add, null);
        assertEquals(List.of(), gamesWarns, "the games read it without a WARN");
        for (GameSpec<?> spec : GameCatalog.SPECS) {
            assertEquals(spec.defaults(), p.settings(spec), spec.id() + " reads as shipped");
        }
        assertShippedTokens(p, "0.36");
    }

    @Test
    void a035FileMovesEveryValueToo() {
        YamlConfiguration c = LayoutFixtures.v035();
        assertEquals(18, c.getInt("config_revision"), "fixture: 0.35 is revision 18");
        List<String> log = HomeCraftManagement.migrateConfig(c, "world");
        assertEquals(List.of(), warns(log), "nothing the owner changed: no WARN");
        assertTrue(LayoutGuard.pending(c), "revision 19 still marks a 0.35 file for the layout guard");
        for (EconomyMigration.Step s : EconomyMigration.STEPS) {
            assertTrue(EconomyMigration.same(c.get(s.path()), s.now()), s.path() + " is the new default: " + c.get(s.path()));
        }
        HomeCraftManagement.backfillConfig(c, LayoutFixtures.bundled());
        List<String> gamesWarns = new ArrayList<>();
        GamesConfig.Parsed p = GamesConfig.parse(LayoutFixtures.reread(c), gamesWarns::add, null);
        // where 0.35's places stand is the layout guard's to settle once the database is open
        // (LayoutGuardUpgradeTest); until then a Classic's spot may crowd another, which isn't a token matter
        assertEquals(List.of(), gamesWarns.stream().filter(w -> !w.contains(" blocks from ")).toList(),
                "the games read the tokens without a WARN");
        assertShippedTokens(p, "0.35");
    }

    // ---- (b) the owner's own values ----------------------------------------------------------------

    @Test
    void theOwnersValuesAreKeptWithOneWarnEachAndTheirComments() {
        YamlConfiguration c = LayoutFixtures.v036();
        List<String> prizeComments = c.getComments("games.race_night.prizes");
        c.set("games.cup.entry", 7);
        c.set("games.race_night.prizes", List.of(6, 4, 2));
        List<Map<String, Object>> ticket = new ArrayList<>();
        for (Map<?, ?> row : c.getMapList("arcade.lotto.payouts")) {
            Map<String, Object> r = new LinkedHashMap<>();
            row.forEach((k, v) -> r.put(String.valueOf(k), v));
            ticket.add(r);
        }
        ticket.get(2).put("tokens", 9);
        c.set("arcade.lotto.payouts", ticket);
        c.setComments("games.race_night.prizes", prizeComments);
        String lottoComment = String.join("\n", c.getComments("arcade.lotto"));

        List<String> log = HomeCraftManagement.migrateConfig(c, "world");
        assertEquals(List.of(
                HomeCraftManagement.WARN + "Config migration: kept games.cup.entry = 7 because you have changed it "
                        + "(the new default is " + TokenBalance.CUP_ENTRY + ").",
                HomeCraftManagement.WARN + "Config migration: kept games.race_night.prizes = [6, 4, 2] because you "
                        + "have changed it (the new default is " + TokenBalance.RACE_PRIZES + ").",
                HomeCraftManagement.WARN + "Config migration: kept arcade.lotto.payouts = [0×25, 3×40, 9×22, 20×9, "
                        + "50×3, jackpot×1] because you have changed it (the new default is "
                        + EconomyMigration.show(TokenBalance.TICKET_PAYOUTS) + ")."), warns(log),
                "one WARN each, naming the key and the new default");
        assertEquals(7, c.getInt("games.cup.entry"), "the owner's entry stays");
        assertEquals(List.of(6, 4, 2), c.getIntegerList("games.race_night.prizes"), "and their prizes");
        assertEquals(9, ((Number) c.getMapList("arcade.lotto.payouts").get(2).get("tokens")).intValue(),
                "and their ticket");
        assertEquals(TokenBalance.CUP_TOPUP, c.getInt("games.cup.server_topup"), "the top-up they didn't change moves");
        assertEquals(TokenBalance.RACE_FINISHER_PRIZE, c.getInt("games.race_night.finisher_prize"),
                "and so does the finisher prize");
        assertEquals(prizeComments, c.getComments("games.race_night.prizes"), "a kept key keeps its comment");
        assertEquals(lottoComment, String.join("\n", c.getComments("arcade.lotto")),
                "and the ticket's section comment stays: the table is the owner's");
        assertEquals(LayoutFixtures.bundled().getComments("games.fresh.rewards"), c.getComments("games.fresh.rewards"),
                "while a section that moved whole takes the new comment");
        List<String> gamesWarns = new ArrayList<>();
        HomeCraftManagement.backfillConfig(c, LayoutFixtures.bundled());
        GamesConfig.parse(LayoutFixtures.reread(c), gamesWarns::add, null);
        assertEquals(List.of(), gamesWarns, "the owner's values load without a WARN");
    }

    /** A 0.36 file whose ticket the owner retuned through {@code key}, migrated: its log. */
    private static List<String> retunedTicket(YamlConfiguration c, String key, Object value) {
        c.set(key, value);
        return HomeCraftManagement.migrateConfig(c, "world");
    }

    @Test
    void aTicketRetunedThroughItsPriceOrJackpotKeepsItsPrizesWithOneWarnAndItsComment() {
        // the 0.36 README asked the owner to bring the ticket into 85-95; a key beside the prizes does it
        // in one number. The new prizes on top of it would leave the band, or pay out more than it takes.
        String old = EconomyMigration.show(TokenBalance.row(EconomyMigration.TICKET).old());
        String now = EconomyMigration.show(TokenBalance.TICKET_PAYOUTS);
        Object[][] cases = {
                {"arcade.lotto.ticket_tokens", 9, "86.2%", "99.4%"},
                {"arcade.lotto.ticket_tokens", 8, "97.0%", "111.8%"},
                {"arcade.lotto.jackpot.seed", 200, "92.6%", "104.5%"},
                {"arcade.lotto.jackpot.per_ticket", 2, "87.6%", "99.5%"},
        };
        for (Object[] k : cases) {
            YamlConfiguration c = LayoutFixtures.v036();
            String lottoComment = String.join("\n", c.getComments("arcade.lotto"));
            String shipped = String.valueOf(TokenBalance.TICKET_SHIPPED.get((String) k[0]));
            List<String> log = retunedTicket(c, (String) k[0], k[1]);
            assertEquals(List.of(HomeCraftManagement.WARN + "Config migration: kept arcade.lotto.payouts = " + old
                            + " because you have changed " + k[0] + " = " + k[1] + " (shipped " + shipped + "), and the"
                            + " ticket's return is all of them together: yours gives back " + k[2] + ", and the new"
                            + " prizes " + now + " would give back " + k[3] + " (the house band is 85-95)."), warns(log),
                    k[0] + " " + k[1] + ": one WARN, naming the key and the return either way");
            assertTrue(EconomyMigration.same(c.get(EconomyMigration.TICKET),
                    TokenBalance.row(EconomyMigration.TICKET).old()), k[0] + ": the prizes stay as they were");
            assertEquals(k[1], c.get((String) k[0]), "and the owner's own value");
            assertEquals(lottoComment, String.join("\n", c.getComments("arcade.lotto")),
                    k[0] + ": the ticket's section comment stays, it describes the owner's ticket");
            for (EconomyMigration.Step s : EconomyMigration.STEPS) {
                if (!s.path().equals(EconomyMigration.TICKET)) {
                    assertTrue(EconomyMigration.same(c.get(s.path()), s.now()), s.path() + " moved as usual");
                }
            }
            assertTrue(log.stream().noneMatch(l -> l.startsWith("Config migration: arcade.lotto - ")),
                    "no line says the ticket moved: " + log);
        }

        // two keys at once: one WARN naming both
        YamlConfiguration both = LayoutFixtures.v036();
        both.set("arcade.lotto.jackpot.cap", 100);
        List<String> log = retunedTicket(both, "arcade.lotto.ticket_tokens", 9);
        assertEquals(1, warns(log).size(), "one WARN: " + log);
        assertTrue(warns(log).get(0).contains("arcade.lotto.ticket_tokens = 9 (shipped 10) and arcade.lotto.jackpot.cap"
                + " = 100 (shipped 1000)"), "naming both, in table order: " + log);
    }

    @Test
    void aTopUpTheOwnerKeptBelowTwiceTheNewEntryIsNamedWhenTheGamesLoad() {
        // the v4 audit, ECON01: 15 on top of 0.36's 5 kept the family rule; on top of 0.37's 10 it doesn't
        YamlConfiguration c = LayoutFixtures.v036();
        c.set("games.cup.server_topup", 15);
        List<String> log = HomeCraftManagement.migrateConfig(c, "world");
        assertEquals(1, warns(log).size(), "the kept top-up's one WARN: " + log);
        assertEquals(TokenBalance.CUP_ENTRY, c.getInt("games.cup.entry"), "the entry moved to 10");
        HomeCraftManagement.backfillConfig(c, LayoutFixtures.bundled());
        List<String> gamesWarns = new ArrayList<>();
        GamesConfig.parse(LayoutFixtures.reread(c), gamesWarns::add, null);
        assertEquals(List.of("games.cup.server_topup 15 is less than twice games.cup.entry 10, so in a Cup of 3 where "
                + "everyone sets a time, third gets back 9 of the 10 they paid - set server_topup to at least 20 (or "
                + "lower the entry) so nobody in a Cup of 2 or 3 loses"), gamesWarns,
                "and the games say what it costs each time they load");
    }

    @Test
    void aTicketWhoseCompanionsStillHoldTheShippedValuesMoves() {
        YamlConfiguration c = LayoutFixtures.v036();
        c.set("arcade.lotto.ticket_tokens", 10.0); // the same number, written another way
        c.set("arcade.lotto.jackpot", null);      // absent: what 0.36 ships is read
        List<String> log = HomeCraftManagement.migrateConfig(c, "world");
        assertEquals(List.of(), warns(log), "nothing the owner changed: " + log);
        assertTrue(EconomyMigration.same(c.get(EconomyMigration.TICKET), TokenBalance.TICKET_PAYOUTS),
                "the prizes move into the band");
    }

    @Test
    void aValueAlreadyAtTheNewDefaultStaysQuiet() {
        YamlConfiguration c = LayoutFixtures.v036();
        c.set("games.skill_daily_cap", TokenBalance.SKILL_DAILY_CAP);
        List<String> log = HomeCraftManagement.migrateConfig(c, "world");
        assertEquals(List.of(), warns(log), "the new default is neither the owner's nor the old value: nothing to say");
        assertTrue(log.stream().anyMatch(l -> l.startsWith("Config migration: games - featured_bonus 1 → "
                + TokenBalance.FEATURED_BONUS + " (")), "the common section's line names only what moved: " + log);
    }

    // ---- (c) an absent key -------------------------------------------------------------------------

    @Test
    void anAbsentKeyStaysAbsentAndTheBackfillWritesTheNewDefault() {
        YamlConfiguration c = LayoutFixtures.v036();
        c.set("games.trials.daily_cap", null);
        List<String> log = HomeCraftManagement.migrateConfig(c, "world");
        assertNull(c.get("games.trials.daily_cap", null), "the step doesn't write it");
        String trials = log.stream().filter(l -> l.startsWith("Config migration: games.trials - ")).findFirst()
                .orElseThrow();
        assertFalse(trials.contains("daily_cap"), "nor says it moved: " + trials);
        assertTrue(trials.contains("first_clear " + group("games.trials.first_clear")), "while the rest of trials moved: "
                + trials);
        HomeCraftManagement.backfillConfig(c, LayoutFixtures.bundled());
        assertEquals(TokenBalance.TRIALS_DAILY_CAP, c.getInt("games.trials.daily_cap"), "the backfill writes the new default");
        assertEquals(LayoutFixtures.bundled().getComments("games.trials.daily_cap"),
                c.getComments("games.trials.daily_cap"), "with its comment");
    }

    // ---- (d) once only -----------------------------------------------------------------------------

    @Test
    void aFileAtRevisionTwentyIsLeftAloneAndTheStepIsIdempotent() {
        YamlConfiguration fresh = LayoutFixtures.bundled();
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(fresh, "world"), "a fresh install migrates nothing");

        YamlConfiguration stamped = LayoutFixtures.v036();
        stamped.set("config_revision", HomeCraftManagement.CONFIG_REVISION);
        String before = stamped.saveToString();
        assertEquals(List.of(), HomeCraftManagement.migrateConfig(stamped, "world"),
                "a file already at the newest revision is never migrated again");
        assertEquals(before, stamped.saveToString(), "not a byte changes, the old values included");

        // A file already at 20 still takes the later steps (21's areas), but never 20's again.
        YamlConfiguration at20 = LayoutFixtures.v036();
        at20.set("config_revision", EconomyMigration.REVISION);
        HomeCraftManagement.migrateConfig(at20, "world");
        for (EconomyMigration.Step s : EconomyMigration.STEPS) {
            assertTrue(EconomyMigration.same(at20.get(s.path()), s.old()),
                    s.path() + ": a file already at 20 is never dragged through 20 again, so it keeps " + s.old()
                            + ", not " + at20.get(s.path()));
        }

        YamlConfiguration c = LayoutFixtures.v036();
        List<String> first = new ArrayList<>();
        EconomyMigration.apply(c, first);
        assertFalse(first.isEmpty(), "the first run moves the values");
        String once = c.saveToString();
        List<String> second = new ArrayList<>();
        EconomyMigration.apply(c, second);
        assertEquals(List.of(), second, "a second run has nothing to do");
        assertEquals(once, c.saveToString(), "and changes nothing");
    }

    // ---- (e) the frozen table ----------------------------------------------------------------------

    @Test
    void theFrozenTableIsWhat035And036ShippedAndWhatThisReleaseShips() {
        YamlConfiguration v035 = LayoutFixtures.v035();
        YamlConfiguration v036 = LayoutFixtures.v036();
        YamlConfiguration bundled = LayoutFixtures.bundled();
        Set<String> paths = new HashSet<>();
        for (EconomyMigration.Step s : EconomyMigration.STEPS) {
            assertTrue(paths.add(s.path()), s.path() + " once");
            assertTrue(EconomyMigration.same(v035.get(s.path()), s.old()), s.path() + ": 0.35 shipped " + s.old());
            assertTrue(EconomyMigration.same(v036.get(s.path()), s.old()), s.path() + ": 0.36 shipped " + s.old());
            assertTrue(EconomyMigration.same(bundled.get(s.path()), s.now()), s.path() + ": revision 20 moves it to "
                    + s.now() + " (frozen), but config.yml ships " + bundled.get(s.path()) + ". A retune after 0.37.0"
                    + " adds a config revision whose old value is " + s.now() + "; revision 20's row never changes");
            assertFalse(EconomyMigration.same(s.old(), s.now()), s.path() + " moves");
        }
        assertEquals(64, EconomyMigration.STEPS.size(), "the 64 values of BALANCE-SPEC §5.3");
        for (Map.Entry<String, Object> e : TokenBalance.TICKET_SHIPPED.entrySet()) {
            assertTrue(EconomyMigration.same(v035.get(e.getKey()), e.getValue()), e.getKey() + ": 0.35 shipped " + e.getValue());
            assertTrue(EconomyMigration.same(v036.get(e.getKey()), e.getValue()), e.getKey() + ": 0.36 shipped " + e.getValue());
            assertTrue(EconomyMigration.same(bundled.get(e.getKey()), e.getValue()), e.getKey() + ": and this release too");
        }
        assertEquals(TokenBalance.TICKET_SHIPPED, TokenBalance.row(EconomyMigration.TICKET).with(),
                "the ticket's prizes move only with its price and jackpot");
        assertEquals(1, EconomyMigration.STEPS.stream().filter(s -> !s.with().isEmpty()).count(),
                "and every other value moves on its own");
        assertEquals(TokenBalance.ROWS.size(), EconomyMigration.STEPS.size(), "the steps are the token balance's rows");
        assertEquals(20, EconomyMigration.REVISION, "it is revision 20");
        assertTrue(HomeCraftManagement.CONFIG_REVISION >= EconomyMigration.REVISION,
                "and this release stamps it (21, the v4 areas, comes after it)");
        assertEquals(19, LayoutGuard.REVISION, "the layout keeps its own revision, 19");
    }

    @Test
    void revision20sTargetsAreLiteralNumbersNotTheConstantsARetuneEdits() throws Exception {
        // V4-DECISIONS (05:31) and ECONOMY-V2-SPEC §6.1: before release, freeze the targets, so a later
        // retune of TokenBalance and config.yml fails the pin above instead of rewriting revision 20.
        String code = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/dierks/homecraft/games/TokenBalance.java"));
        String rows = code.substring(code.indexOf("private static List<Row> rows() {"),
                code.indexOf("private static Map<String, Row> byPath() {"));
        int seen = 0;
        for (String line : rows.split("\n")) {
            if (!line.contains("new Row(")) {
                continue;
            }
            seen++;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b[A-Z][A-Z0-9_]{2,}\\b").matcher(line);
            while (m.find()) {
                assertEquals("TICKET_SHIPPED", m.group(), "a row reads a constant, so revision 20 would move with "
                        + "a retune: " + line.trim());
            }
        }
        assertEquals(47, seen, "every row's line: 42 of their own, and five in the cabinets' loops (6 x 3 + 2 x 2)");
        // the values themselves: what this release's constants are
        assertEquals(TokenBalance.SKILL_DAILY_CAP, TokenBalance.row("games.skill_daily_cap").now(), "for 0.37.0");
        assertEquals(TokenBalance.GOLF_DAILY_CAP, TokenBalance.row("games.golf.daily_cap").now(), "for 0.37.0");
        assertEquals(TokenBalance.RACE_PRIZES, TokenBalance.row("games.race_night.prizes").now(), "for 0.37.0");
        assertEquals(TokenBalance.TICKET_PAYOUTS, TokenBalance.row(EconomyMigration.TICKET).now(), "for 0.37.0");
    }

    @Test
    void noQuestAchievementOrGameOfChanceValueIsInTheTable() {
        for (EconomyMigration.Step s : EconomyMigration.STEPS) {
            assertFalse(s.path().startsWith("arcade.quests") || s.path().startsWith("arcade.achievements"),
                    s.path() + ": quests and achievements are unchanged (D7)");
            for (GameSpec<?> spec : GameCatalog.SPECS) {
                if (spec.kind().chance()) {
                    assertFalse(s.path().startsWith("games." + GamesConfig.block(spec.id()) + "."),
                            s.path() + ": a game of chance is unchanged (D5)");
                }
            }
            assertFalse(s.path().equals("games.chance_daily_tokens") || s.path().equals("games.max_payout"),
                    s.path() + ": the chance limits are unchanged");
        }
    }

    // ---- (f) comments and the log ------------------------------------------------------------------

    @Test
    void aMovedKeyTakesTheBundledCommentAndEachSectionGetsOneLine() {
        YamlConfiguration c = LayoutFixtures.v036();
        assertTrue(c.saveToString().contains("Keep each at or under 4"), "fixture: 0.36 says it");
        List<String> log = HomeCraftManagement.migrateConfig(c, "world");
        YamlConfiguration bundled = LayoutFixtures.bundled();
        for (EconomyMigration.Step s : EconomyMigration.STEPS) {
            assertEquals(bundled.getComments(s.path()), c.getComments(s.path()), s.path() + " has the bundled comment");
        }
        for (String section : EconomyMigration.COMMENT_SECTIONS) {
            assertEquals(bundled.getComments(section), c.getComments(section), section + " has the bundled comment");
        }
        String text = LayoutFixtures.reread(c).saveToString();
        for (String stale : List.of("at or under 4", "(each 0-10)", "~78%", "(4) and skill_daily_cap (6)")) {
            assertFalse(text.contains(stale), "no \"" + stale + "\" is left in the migrated file");
        }
        String tail = " (the 2 Oct token balance: about a token a minute of play).";
        List<String> lines = log.stream().filter(l -> l.startsWith("Config migration: ") && l.contains(" → ")
                && l.endsWith(tail)).toList();
        long sections = EconomyMigration.STEPS.stream().map(s -> EconomyMigration.section(s.path())).distinct().count();
        assertEquals(sections, lines.size(), "one line per section: the common keys, 8 cabinets, trials, golf, cup, "
                + "fresh, race night, floors and the ticket: " + lines);
        assertTrue(lines.contains("Config migration: games.trials - first_clear " + group("games.trials.first_clear")
                + ", weekly_best_bonus 5 → " + TokenBalance.TRIALS_WEEKLY_BEST + ", course_of_week_bonus 2 → "
                + TokenBalance.TRIALS_COURSE_OF_WEEK + ", daily_cap 4 → " + TokenBalance.TRIALS_DAILY_CAP + tail),
                "a group that moved whole is one entry: " + lines);
        assertTrue(lines.contains("Config migration: games.fresh - daily_cap 2 → " + TokenBalance.FRESH_DAILY_CAP
                + ", rewards.clear_weekly " + group("games.fresh.rewards.clear_weekly") + ", rewards.clear_daily "
                + group("games.fresh.rewards.clear_daily") + ", star_goals.weekly_tokens [1, 2] → "
                + TokenBalance.STAR_WEEKLY_TOKENS + ", star_goals.daily_tokens [1, 1] → "
                + TokenBalance.STAR_DAILY_TOKENS + tail), "lists stay whole: " + lines);
        assertTrue(lines.contains("Config migration: arcade.lotto - payouts [0×25, 3×40, 8×22, 20×9, 50×3, jackpot×1] → "
                + EconomyMigration.show(TokenBalance.TICKET_PAYOUTS) + tail), "the ticket as rows: " + lines);
    }

    // ---- (g) the other paths in ----------------------------------------------------------------------

    @Test
    void aBareGamesSwitchSkipsTheStepAndTheBackfillFillsInTheNewDefaults() {
        YamlConfiguration c = LayoutFixtures.v036();
        c.set("games", false);
        List<String> log = HomeCraftManagement.migrateConfig(c, "world");
        assertTrue(log.stream().noneMatch(l -> l.contains("kept games.")), "nothing under a switch to keep: " + log);
        assertEquals(Boolean.FALSE, c.get("games.enabled"), "the switch became games.enabled: false");
        HomeCraftManagement.backfillConfig(c, LayoutFixtures.bundled());
        assertEquals(TokenBalance.SKILL_DAILY_CAP, c.getInt("games.skill_daily_cap"), "the backfill writes the new defaults");
        assertEquals(TokenBalance.RACE_PRIZES, c.getIntegerList("games.race_night.prizes"), "every one of them");
        assertTrue(EconomyMigration.same(c.get("arcade.lotto.payouts"), EconomyMigration.STEPS
                .get(EconomyMigration.STEPS.size() - 1).now()), "and the ticket outside games still moved");
    }

    @Test
    void aNumberIsComparedByValueAndARowFieldByField() {
        assertTrue(EconomyMigration.same(5.0, 5), "5.0 is 5");
        assertFalse(EconomyMigration.same(5.5, 5), "5.5 is the owner's");
        assertFalse(EconomyMigration.same("5", 5), "a word is the owner's");
        assertTrue(EconomyMigration.same(List.of(5L, 3L, 2L), List.of(5, 3, 2)), "a list element by element");
        assertFalse(EconomyMigration.same(List.of(5, 3), List.of(5, 3, 2)), "a shorter list is the owner's");
        Map<String, Object> jackpot = new LinkedHashMap<>();
        jackpot.put("jackpot", true);
        jackpot.put("weight", 1);
        Object rows = EconomyMigration.STEPS.get(EconomyMigration.STEPS.size() - 1).old();
        List<Object> mine = new ArrayList<>((List<?>) rows);
        mine.set(5, jackpot);
        assertTrue(EconomyMigration.same(mine, rows), "an equal row in another map is the same");
        Map<String, Object> extra = new LinkedHashMap<>(jackpot);
        extra.put("note", "mine");
        mine.set(5, extra);
        assertFalse(EconomyMigration.same(mine, rows), "a row with a field of its own is the owner's");
        assertNotNull(EconomyMigration.show(rows), "and it can be shown");
    }
}
