package com.dierks.homecraft.command;

import com.dierks.homecraft.command.GamesCheck.Course;
import com.dierks.homecraft.command.GamesCheck.Fresh;
import com.dierks.homecraft.command.GamesCheck.Line;
import com.dierks.homecraft.command.GamesCheck.MvInv;
import com.dierks.homecraft.command.GamesCheck.Region;
import com.dierks.homecraft.command.GamesCheck.SlotFact;
import com.dierks.homecraft.command.GamesCheck.Status;
import com.dierks.homecraft.command.GamesCheck.Web;
import com.dierks.homecraft.games.gen.api.GenCopy;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /hcm games check} (EXTRAS E1): with every fact good it says "All good"; each thing wrong is
 * one WARN or FAIL naming it with the fix in plain words; what can't be read says what to look at by
 * hand; a section that throws fails only itself; Multiverse-Inventories' own files are read for both
 * its versions; and it only ever reads.
 */
class GamesCheckTest {

    /** A server where everything is set up right; each test breaks one thing. */
    private static class Good implements GamesCheck.Facts {
        boolean serviceUp = true;
        boolean enabled = true;
        List<String> economy = List.of("world", "hub");
        List<String> games = List.of("games");
        Set<String> loaded = Set.of("world", "hub", "games");
        Map<String, String> modes = new LinkedHashMap<>(Map.of("games", "ADVENTURE"));
        MvInv inv = new MvInv(Map.of("default", List.of("world", "hub"), "games", List.of("games")), false);
        List<Object> restarts = List.of("04:00", "16:00");
        Fresh fresh = fresh(null);
        List<Course> courses = List.of(new Course("hill", false, "games", true, true, List.of()),
                new Course("green", true, "games", true, true, List.of()));
        Web web = new Web(true, true, null, 14, 5120);

        static Fresh fresh(String problem) {
            List<Region> regions = List.of(
                    new Region("fresh_parkour_easy", "Easy Parkour", false, true, List.of(), "x 4096..4255"),
                    new Region("fresh_boat", "Ice Boat", false, false, List.of("it is on top of fresh_rings"), "x 1"),
                    new Region("fresh_classic_golf", "Classic Golf", true, true, List.of(), "x 4352"));
            List<SlotFact> slots = List.of(
                    new SlotFact("fresh_parkour_easy", "Easy Parkour", false, true, problem, problem == null, true, true,
                            false, null, false, null),
                    new SlotFact("fresh_boat", "Ice Boat", false, false, null, false, false, false, false, null, false,
                            null),
                    new SlotFact("fresh_classic_golf", "Classic Golf", true, false, null, true, true, true, false, null,
                            false, "HARD-40 (Golf of the Week, week of 5 Oct)"));
            return new Fresh(true, "weekly", "New courses every Monday", "games", true, true, regions, slots,
                    "Mon 5 Oct 4:00 AM (in 6d 14h)", null, "x 4096..4959, 24 plots");
        }

        @Override
        public boolean serviceUp() {
            return serviceUp;
        }

        @Override
        public boolean gamesEnabled() {
            return enabled;
        }

        @Override
        public List<String> economyWorlds() {
            return economy;
        }

        @Override
        public List<String> gamesWorlds() {
            return games;
        }

        @Override
        public boolean worldLoaded(String world) {
            return loaded.contains(world);
        }

        @Override
        public String gameMode(String world) {
            return modes.get(world);
        }

        @Override
        public MvInv mvInventories() {
            return inv;
        }

        @Override
        public List<Object> restartTimes() {
            return restarts;
        }

        @Override
        public String restartStatus() {
            return "Next restart: 4:00 PM (new runs held from 3:55 PM)";
        }

        @Override
        public Fresh fresh() {
            return fresh;
        }

        @Override
        public List<Course> courses() {
            return courses;
        }

        @Override
        public Web web() {
            return web;
        }
    }

    private static List<Line> bad(List<Line> lines) {
        return lines.stream().filter(l -> l.status() != Status.OK).toList();
    }

    private static Line only(List<Line> lines) {
        List<Line> bad = bad(lines);
        assertEquals(1, bad.size(), "exactly one thing is wrong: " + bad);
        return bad.get(0);
    }

    @Test
    void aServerSetUpRightIsAllGood() {
        List<Line> lines = GamesCheck.run(new Good());
        assertEquals(List.of(), bad(lines), "nothing to fix");
        List<String> out = GamesCheck.render(lines);
        assertEquals("&6Games check &7(it only looks, it changes nothing)", out.get(0), "the header");
        assertEquals("&aAll good.", out.get(out.size() - 1), "the summary");
        String all = String.join("\n", out);
        for (String says : List.of("games.enabled is true", "Economy world 'world' is loaded",
                "Games world 'games' is loaded, not an economy world, in adventure mode",
                "Games world 'games' has a Multiverse-Inventories group of its own ('games')",
                "game-mode share handling is off", "Next restart: 4:00 PM", "Fresh Courses is on: new courses weekly",
                "Easy Parkour: area fits, claimed, live", "Classic Golf: area fits, claimed, holds HARD-40",
                "Next change: Mon 5 Oct", "Keep area: x 4096..4959, 24 plots", "Course 'hill' is ready",
                "Golf course 'green' is ready", "/api/arcade builds (14 game entries, 5120 bytes)",
                "/lp user <player> permission set hcm.games.chance false")) {
            assertTrue(all.contains(says), "it says \"" + says + "\":\n" + all);
        }
        assertFalse(all.contains("Ice Boat"), "a course switched off is not checked");
    }

    @Test
    void theSwitchTheWorldsAndTheGameMode() {
        Good g = new Good();
        g.enabled = false;
        Line l = only(GamesCheck.run(g));
        assertEquals(Status.WARN, l.status(), "games off is a warning, not a failure");
        assertEquals("set games.enabled: true, then /hcm reload", l.fix());

        g = new Good();
        g.serviceUp = false;
        assertEquals(Status.FAIL, only(GamesCheck.run(g)).status(), "the games failed to start");

        g = new Good();
        g.economy = List.of("world", "hub", "games");
        l = only(GamesCheck.run(g));
        assertEquals("Games world 'games' is also an economy world", l.what());
        assertTrue(l.fix().startsWith("take it out of worlds.economy_enabled"), l.fix());

        g = new Good();
        g.economy = List.of("world", "hubb");
        g.inv = new MvInv(Map.of("games", List.of("games")), false);
        l = only(GamesCheck.run(g));
        assertEquals("Economy world 'hubb' doesn't exist (or isn't loaded)", l.what(), "a typo in the list");

        g = new Good();
        g.modes.clear();
        l = only(GamesCheck.run(g));
        assertEquals(Status.WARN, l.status());
        assertEquals("Games world 'games': can't tell its game mode", l.what());
        assertTrue(l.fix().startsWith("check /mv info games"), "what to look at by hand: " + l.fix());

        g = new Good();
        g.modes.put("games", "SURVIVAL");
        l = only(GamesCheck.run(g));
        assertEquals(Status.FAIL, l.status());
        assertEquals("/mv modify games set gamemode adventure", l.fix());

        g = new Good();
        g.loaded = Set.of("world", "hub");
        l = only(GamesCheck.run(g).stream().filter(x -> x.what().startsWith("Games world")).toList());
        assertTrue(l.what().contains("isn't loaded"), l.what());
    }

    @Test
    void multiverseInventoriesGroupsAndGameModes() {
        Good g = new Good();
        g.inv = null;
        assertEquals(List.of(), bad(GamesCheck.run(g)), "not installed: nothing to check");

        g = new Good();
        g.inv = new MvInv(Map.of("default", List.of("world", "hub", "games")), false);
        Line l = only(GamesCheck.run(g));
        assertEquals(Status.FAIL, l.status());
        assertEquals("Games world 'games' shares things with the economy world 'world' (group 'default')", l.what());

        g = new Good();
        g.inv = new MvInv(Map.of("default", List.of("world", "hub")), false);
        assertTrue(only(GamesCheck.run(g)).what().contains("isn't in a Multiverse-Inventories group"), "no group");

        g = new Good();
        g.games = List.of("games", "games_fresh");
        g.loaded = Set.of("world", "hub", "games", "games_fresh");
        g.modes.put("games_fresh", "ADVENTURE");
        g.inv = new MvInv(Map.of("default", List.of("world", "hub"), "games", List.of("games", "games_fresh")), false);
        assertEquals(List.of(), bad(GamesCheck.run(g)), "the Games worlds may share their own group");

        g = new Good();
        g.inv = new MvInv(null, null);
        List<Line> warn = bad(GamesCheck.run(g));
        assertEquals(2, warn.size(), "groups and the game-mode setting unreadable: " + warn);
        assertTrue(warn.stream().allMatch(x -> x.status() == Status.WARN && x.fix().startsWith("check by hand")),
                "each says what to check by hand: " + warn);

        g = new Good();
        g.inv = new MvInv(Map.of("games", List.of("games")), true);
        l = only(GamesCheck.run(g));
        assertEquals(Status.FAIL, l.status());
        assertTrue(l.fix().contains("enable-gamemode-share-handling: false"), l.fix());
    }

    @Test
    void multiverseInventoriesFilesOfBothVersionsAreRead() throws Exception {
        YamlConfiguration groups5 = new YamlConfiguration();
        groups5.loadFromString("""
                groups:
                  default:
                    worlds: [world, world_nether]
                    shares: [all]
                  games:
                    worlds: [games]
                """);
        YamlConfiguration config5 = new YamlConfiguration();
        config5.loadFromString("share-handling:\n  enable-gamemode-share-handling: false\n");
        MvInv five = GamesCheck.readMvInv(groups5, config5);
        assertEquals(List.of("world", "world_nether"), five.groups().get("default"), "5.x groups.yml");
        assertEquals(List.of("games"), five.groups().get("games"));
        assertEquals(Boolean.FALSE, five.gamemodeShare(), "5.x share handling");

        YamlConfiguration config4 = new YamlConfiguration();
        config4.loadFromString("""
                settings:
                  use_game_mode_profiles: true
                groups:
                  survival:
                    worlds: [world, games]
                """);
        MvInv four = GamesCheck.readMvInv(null, config4);
        assertEquals(List.of("world", "games"), four.groups().get("survival"), "4.x groups in config.yml");
        assertEquals(Boolean.TRUE, four.gamemodeShare(), "4.x game-mode profiles");

        MvInv none = GamesCheck.readMvInv(null, null);
        assertEquals(null, none.groups(), "unreadable: can't tell");
        assertEquals(null, none.gamemodeShare(), "unreadable: can't tell");
    }

    @Test
    void restartTimesThatDontReadAreNamed() {
        Good g = new Good();
        g.restarts = List.of("04:00", 240, "4pm");
        List<Line> bad = bad(GamesCheck.run(g));
        assertEquals(2, bad.size(), "each bad time is one line: " + bad);
        assertEquals("games.restart_times has 240, which isn't a time", bad.get(0).what(), "an unquoted 04:00");
        assertTrue(bad.get(0).fix().contains("in quotes"), bad.get(0).fix());
        assertEquals("games.restart_times has \"4pm\", which isn't a time", bad.get(1).what());

        g = new Good();
        g.restarts = List.of();
        String all = String.join("\n", GamesCheck.render(GamesCheck.run(g)));
        assertTrue(all.contains("No restart times set, so nothing is held"), all);
    }

    @Test
    void freshCoursesOffForeignBlocksAreasAndBuilds() {
        Good g = new Good();
        g.fresh = new Fresh(false, "weekly", "", "", false, false, List.of(), null, null, null, "");
        List<Line> fresh = GamesCheck.run(g).stream().filter(l -> l.what().contains("Fresh Courses")).toList();
        assertEquals(1, fresh.size(), "off: one line and nothing else: " + fresh);
        assertEquals(Status.OK, fresh.get(0).status());

        g = new Good();
        g.fresh = Good.fresh("Region has 1,234 blocks that aren't Fresh Courses' (first at 4100,170,4100) - "
                + "/hcm games gen claim fresh_parkour_easy confirm clears them");
        Line l = only(GamesCheck.run(g));
        assertEquals("Easy Parkour: 1,234 blocks that aren't Fresh Courses' are in its area", l.what(), "the count");
        assertTrue(l.fix().startsWith("/hcm games gen claim fresh_parkour_easy confirm clears them"), l.fix());

        g = new Good();
        Fresh f = Good.fresh(null);
        List<Region> regions = new ArrayList<>(f.regions());
        regions.set(0, new Region("fresh_parkour_easy", "Easy Parkour", false, true,
                List.of("it reaches past the world border (x 4096..4255)"), "x"));
        g.fresh = new Fresh(true, "weekly", "New courses every Monday", "games", true, true, regions, f.slots(),
                f.nextChange(), null, f.keepArea());
        l = only(GamesCheck.run(g));
        assertEquals("Easy Parkour: it reaches past the world border (x 4096..4255)", l.what());
        assertEquals("move it with games.fresh.slots.fresh_parkour_easy.origin", l.fix());

        g = new Good();
        List<SlotFact> slots = new ArrayList<>(f.slots());
        slots.set(0, new SlotFact("fresh_parkour_easy", "Easy Parkour", false, true, null, true, false, false, false,
                "the plan failed its check", false, null));
        g.fresh = new Fresh(true, "weekly", "s", "games", true, true, f.regions(), slots, f.nextChange(),
                "it overlaps a Fresh Courses area", f.keepArea());
        List<Line> bad = bad(GamesCheck.run(g));
        assertEquals(2, bad.size(), "the failed build and the keep area: " + bad);
        assertEquals("Easy Parkour has no course yet: the plan failed its check", bad.get(0).what());
        assertEquals("The keep area can't be used: it overlaps a Fresh Courses area", bad.get(1).what());

        g = new Good();
        g.fresh = new Fresh(true, "weekly", "s", "games", true, true, f.regions(), null, null, null, "k");
        l = only(GamesCheck.run(g));
        assertEquals(Status.WARN, l.status(), "not running: its courses can't be checked");

        g = new Good();
        g.fresh = new Fresh(true, "weekly", "s", "games_fresh", true, false, f.regions(), f.slots(), null, null, "k");
        assertEquals("Fresh Courses' world 'games_fresh' isn't in games.worlds", only(GamesCheck.run(g)).what());
    }

    @Test
    void handBuiltCoursesUseTheirOwnValidatorsWords() {
        Good g = new Good();
        g.courses = List.of(
                new Course("hill", false, "games", true, true, List.of("it has no finish yet (/hcm games course hill finish)")),
                new Course("wip", false, "games", false, true, List.of("it has no start yet (/hcm games course wip start)")),
                new Course("green", true, "old_games", true, false, List.of("its world old_games is not in games.worlds")));
        List<Line> bad = bad(GamesCheck.run(g));
        assertEquals(3, bad.size(), bad.toString());
        assertEquals(Status.FAIL, bad.get(0).status(), "an open course that can't be finished fails");
        assertEquals(Status.WARN, bad.get(1).status(), "one switched off while it's being built is a warning");
        assertEquals("Golf course 'green': its world old_games is not in games.worlds; its world 'old_games' isn't loaded",
                bad.get(2).what(), "the validator's words, and the world");

        g = new Good();
        g.courses = List.of();
        assertEquals(List.of(), bad(GamesCheck.run(g)), "no courses is fine");
    }

    @Test
    void theWebsiteFeed() {
        Good g = new Good();
        g.web = new Web(false, false, null, 0, 0);
        assertEquals(List.of(), bad(GamesCheck.run(g)), "the dashboard off: nothing to check");

        g = new Good();
        g.web = new Web(true, false, null, 3, 900);
        assertEquals(Status.WARN, only(GamesCheck.run(g)).status(), "no token: anyone can read it");

        g = new Good();
        g.web = new Web(true, true, "snake's entry failed (java.lang.IllegalStateException: boom)", 2, 0);
        Line l = only(GamesCheck.run(g));
        assertEquals(Status.FAIL, l.status());
        assertEquals("/api/arcade doesn't build: snake's entry failed (java.lang.IllegalStateException: boom)", l.what());
    }

    @Test
    void aSectionThatThrowsFailsOnlyItself() {
        Good g = new Good() {
            @Override
            public MvInv mvInventories() {
                throw new IllegalStateException("plugin half loaded");
            }
        };
        List<Line> lines = GamesCheck.run(g);
        Line l = only(lines);
        assertTrue(l.what().startsWith("Couldn't check Multiverse-Inventories"), l.what());
        assertTrue(lines.stream().anyMatch(x -> x.what().startsWith("Next restart")), "the rest still ran");
    }

    @Test
    void theSummaryCountsEveryWarningAndFailure() {
        assertEquals("&aAll good.", GamesCheck.summary(List.of(Line.ok("a"))));
        assertEquals("&e1 thing to fix.", GamesCheck.summary(List.of(Line.ok("a"), Line.warn("b", "c"))));
        assertEquals("&e2 things to fix.", GamesCheck.summary(List.of(Line.fail("a", "x"), Line.warn("b", "c"))));
        List<String> out = GamesCheck.render(List.of(Line.ok("fine"), Line.warn("meh", "do this"), Line.fail("bad", "do that")));
        assertEquals(List.of("&6Games check &7(it only looks, it changes nothing)", "&a[OK] &ffine",
                "&e[WARN] &fmeh &7- do this", "&c[FAIL] &fbad &7- do that", "&e2 things to fix."), out);
    }

    @Test
    void itOnlyReadsAndItsWordsAreClean() throws Exception {
        for (Method m : GamesCheck.Facts.class.getDeclaredMethods()) {
            assertFalse(m.getReturnType() == void.class, m.getName() + ": every fact is a question, never an action");
            String n = m.getName().toLowerCase(Locale.ROOT);
            for (String verb : List.of("set", "write", "save", "clear", "claim", "delete", "reset", "fix")) {
                assertFalse(n.startsWith(verb), m.getName() + " sounds like it changes something");
            }
        }
        Good broken = new Good();
        broken.enabled = false;
        broken.modes.clear();
        broken.inv = new MvInv(null, true);
        broken.restarts = List.of(240);
        broken.fresh = Good.fresh("Region has 3 blocks that aren't Fresh Courses'");
        broken.web = new Web(true, false, "x", 0, 0);
        for (String line : GamesCheck.render(GamesCheck.run(broken))) {
            assertEquals(List.of(), GenCopy.copyProblems(line), "no banned word, nothing Bedrock can't draw: " + line);
        }
    }
}
