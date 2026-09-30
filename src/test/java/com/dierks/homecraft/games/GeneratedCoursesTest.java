package com.dierks.homecraft.games;

import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.games.GamesKit.Fake;
import com.dierks.homecraft.games.GamesKit.Host;
import com.dierks.homecraft.games.GamesKit.SkillSettings;
import com.dierks.homecraft.games.GamesKit.TestGame;
import com.dierks.homecraft.games.gen.api.GenTag;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The framework's contract with Daily Courses (GEN-SPEC §8.1): until its engine is installed — and
 * whenever {@code daily} is off — {@link GeneratedCourses#NONE} keeps every generated course closed
 * and not standing while hand-built courses are untouched; the service hands out whatever is
 * installed, but NONE whenever the {@code daily} game is closed or failed; "your courses changed" reaches the right game inside its guard;
 * the new game, screen and feed defaults change nothing for anyone who doesn't override them.
 */
class GeneratedCoursesTest {

    private static final GenTag TAG = new GenTag("fresh_parkour_easy", "parkour", 1, 20725, 0, 1L, 'A', "abc", 1, 2,
            3, List.of(), List.of(), 0);

    private Host host;
    private TestGame trials;
    private TestGame golf;
    private GamesService games;

    @BeforeEach
    void setUp() {
        host = new Host(GamesKit.at(2026, 9, 29, 15, 0));
        trials = new TestGame("test_trials", GameKind.TRIAL, "Test Trials", TokenService.Source.GAMES_PARKOUR);
        golf = new TestGame("test_golf", GameKind.GOLF, "Test Golf", TokenService.Source.GAMES_GOLF);
        games = GamesKit.service(host, List.of(GamesKit.spec(trials, new SkillSettings(true, 4), null),
                GamesKit.spec(golf, new SkillSettings(true, 4), null)));
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    @Test
    void withoutTheEngineNothingGeneratedIsLiveOrStanding() {
        GeneratedCourses none = GeneratedCourses.NONE;
        assertTrue(none.live("river_run", null), "a hand-built course is not the gate's business");
        assertFalse(none.live("fresh_parkour_easy", TAG), "a generated one is closed");
        assertFalse(none.standing(TAG), "its layout doesn't stand, so an old run's finish follows the old rule");
        assertFalse(none.standing(null), "nothing stands for a hand-built course either");
        assertFalse(none.inArea("games", 4100, 170, 4100), "no area is kept");
        assertEquals(-1, none.nextChangeAt(), "nothing is scheduled");
        assertEquals("&7Easy Parkour is closed for now.", none.closedLine("fresh_parkour_easy"),
                "a closed slot is named");
        assertEquals("&7That course is closed for now.", none.closedLine("mystery"), "anything else isn't");
        assertNull(none.half(TAG), "no engine knows no half (Race Night then has no Fresh stand)");
        assertNull(none.half(null), "nor for no tag");
    }

    @Test
    void theServiceHandsOutWhatIsInstalled() {
        assertSame(GeneratedCourses.NONE, games.generated(), "NONE until Daily Courses installs its engine");
        GeneratedCourses mine = new GeneratedCourses() {
            @Override
            public boolean live(String courseId, GenTag tag) {
                return true;
            }

            @Override
            public boolean standing(GenTag tag) {
                return true;
            }

            @Override
            public String closedLine(String courseId) {
                return "";
            }

            @Override
            public long nextChangeAt() {
                return 1;
            }

            @Override
            public boolean inArea(String world, int x, int y, int z) {
                return true;
            }
        };
        games.generated(mine);
        assertSame(mine, games.generated(), "the installed engine");
        games.generated(null);
        assertSame(GeneratedCourses.NONE, games.generated(), "removing it goes back to NONE");

        TestGame daily = new TestGame("fresh_courses", GameKind.TRIAL, "Fresh Courses",
                TokenService.Source.GAMES_DAILY);
        GamesService withDaily = GamesKit.service(host, List.of(GamesKit.spec(daily, new SkillSettings(true, 2),
                null)));
        withDaily.generated(mine);
        assertSame(mine, withDaily.generated(), "while daily is open its engine answers");
        daily.open = false;
        assertSame(GeneratedCourses.NONE, withDaily.generated(), "daily switched off: the gate is shut at once");
        daily.open = true;
        withDaily.fail(daily, new IllegalStateException("broken on purpose"));
        assertSame(GeneratedCourses.NONE, withDaily.generated(),
                "daily failed: shut too, whatever its own stop managed to do");
    }

    @Test
    void coursesChangedReachesTheRightGameInsideItsGuard() {
        games.coursesChanged("test_trials");
        games.coursesChanged(" TEST_GOLF ");
        games.coursesChanged("nothing");
        games.coursesChanged(null);
        assertEquals(1, trials.coursesChanged, "the trials heard once");
        assertEquals(1, golf.coursesChanged, "golf too, by id in any case");
        golf.throwOnCoursesChanged = true;
        games.coursesChanged("test_golf");
        assertTrue(games.failed(golf), "a game that throws is switched off, as always");
        assertFalse(games.failed(trials), "and nobody else is");
    }

    @Test
    void theNewDefaultsChangeNothing() {
        Game plain = new Game() {
            @Override
            public String id() {
                return "plain";
            }

            @Override
            public GameKind kind() {
                return GameKind.CABINET;
            }

            @Override
            public String name() {
                return "Plain";
            }

            @Override
            public TokenService.Source source() {
                return TokenService.Source.GAMES_SNAKE;
            }

            @Override
            public boolean configEnabled() {
                return true;
            }

            @Override
            public List<String> rules() {
                return List.of();
            }

            @Override
            public org.bukkit.inventory.ItemStack tile(Player viewer) {
                return null;
            }

            @Override
            public void open(Player player, Runnable back) {
            }
        };
        assertEquals(List.of(), plain.statusLines(), "no extra status lines");
        plain.coursesChanged(); // nothing to forget, nothing thrown
        Fake alex = new Fake("Alex");
        GamesScreens.NONE.today(alex.player, null);
        GamesScreens.NONE.parkourTiers(alex.player, null);
        assertEquals("Coming soon!\nComing soon!", alex.heard(),
                "Today's Courses and the tier picker say Coming soon! until they are built");
    }

    @Test
    void theFeedsDailyOverloadsWriteThePlainEntryUntilTheFeedKnowsMore() {
        List<String> calls = new ArrayList<>();
        FeedWriter out = new FeedWriter() {
            @Override
            public void chance(String id, String name, List<Integer> stakes, Map<Integer, Double> rtpByStake,
                               Integer dailyLimit, List<PayRow> paytable, String rules, Map<String, ?> extra) {
                calls.add("chance");
            }

            @Override
            public void cabinet(String id, String name, String board, String unit, boolean lowerIsBetter, Long best,
                                String holder) {
                calls.add("cabinet");
            }

            @Override
            public void course(String id, String name, String kind, String tier, Long recordMs, Long recordAt,
                               String holder) {
                calls.add("course " + id + " " + kind + " " + tier + " " + recordMs + " " + recordAt + " " + holder);
            }

            @Override
            public void golf(String id, String name, int holes, int par, Integer recordStrokes, Long recordAt,
                             String holder) {
                calls.add("golf " + id + " " + holes + " " + par + " " + recordStrokes + " " + recordAt + " " + holder);
            }
        };
        FeedWriter.Daily d = new FeedWriter.Daily("2026-09-29", 5L, 76_000L, 114_000L);
        out.course("fresh_parkour_easy", "Easy Parkour", "parkour", "easy", 40_000L, 7L, null, d);
        out.golf("fresh_tiny_golf", "Tiny Golf", 3, 9, 10, 8L, null, d);
        out.starChart("2026-09-28", 14L, null);
        assertEquals(List.of("course fresh_parkour_easy parkour easy 40000 7 null",
                "golf fresh_tiny_golf 3 9 10 8 null"), calls,
                "a daily entry is the plain entry until the feed learns the daily part; the Star Chart is nothing");
    }
}
