package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.GameContext;
import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The golf admin tool with no server behind it: a bug in a command is answered with a short red
 * line and never thrown, since it runs inside the game's guard, where a throw would switch golf
 * off and end every round.
 *
 * <p>And Daily Courses (GEN-SPEC §2.4, §5.5): Daily Golf and Tiny Golf allow only info and tp
 * here; and no tee, cup or bound of a hand-built hole goes inside a Daily Courses half or within 16
 * blocks of one.
 */
class GolfAdminTest {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /** A console that keeps what it was told, in {@code &}-codes. */
    private static CommandSender console(List<String> told) {
        return (CommandSender) Proxy.newProxyInstance(GolfAdminTest.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, m, a) -> {
                    if (m.getName().equals("sendMessage") && a != null && a.length == 1 && a[0] instanceof Component c) {
                        told.add(LEGACY.serialize(c));
                    }
                    return switch (m.getName()) {
                        case "getName" -> "CONSOLE";
                        case "hasPermission", "isOp" -> true;
                        default -> null;
                    };
                });
    }

    @Test
    void aBrokenCommandIsAnsweredInRedNotThrown() {
        GameAdmin admin = new MiniGolf(new GameContext(null, null)).admin(); // no framework: every lookup fails
        List<String> told = new ArrayList<>();
        assertDoesNotThrow(() -> admin.handle(console(told), new String[]{"list"}), "the tool catches its own bugs");
        assertEquals(1, told.size(), "one line back: " + told);
        assertTrue(told.get(0).startsWith("&c"), "in red: " + told);
    }

    @Test
    void helpNeedsNothingBehindIt() {
        GameAdmin admin = new MiniGolf(new GameContext(null, null)).admin();
        List<String> told = new ArrayList<>();
        admin.handle(console(told), new String[0]);
        assertEquals(admin.help().size(), told.size(), "the help lines, and no error: " + told);
    }

    @Test
    void autoIsTheFeatureCommandsWordAndNoCoursesId() {
        assertTrue(GolfAdmin.OWN_WORDS.contains("auto"),
                "a golf course called auto could never be pinned as today's pick, so the id is refused");
    }

    // ---- Daily Courses -------------------------------------------------------------------------

    private static GolfCourse dailyGolf() {
        GolfCourse.Hole h = new GolfCourse.Hole(new GolfCourse.Tee(4870.5, 164, 4100.5, 0f),
                new GolfCourse.Spot(4870, 162, 4115), 3, new GolfCourse.Spot(4866, 161, 4097),
                new GolfCourse.Spot(4874, 168, 4119));
        return new GolfCourse("daily_golf", "Daily Golf", "games", true, 4, List.of(h),
                new GenTag("daily_golf", "golf", 1, 20_725, 0, 1L, 'A', "abcabcabcabc", 0, 0, 0, List.of(0),
                        List.of(), 1L));
    }

    @Test
    void dailyGolfAllowsOnlyLookingAndGoingThere() {
        GolfCourse c = dailyGolf();
        for (String verb : List.of("info", "list", "tp")) {
            assertNull(GolfAdmin.dailyRefusal(c, verb), verb + " is allowed on a daily course");
        }
        for (String verb : List.of("hole", "name", "enable", "disable", "delete", "junk")) {
            assertEquals(GenCopy.MADE_BY_DAILY, GolfAdmin.dailyRefusal(c, verb), verb + " points to /hcm games gen");
        }
        assertEquals(GenCopy.MADE_BY_DAILY, GolfAdmin.dailyRefusal(c.withGen(null), "hole"),
                "a row with a slot's id is Daily Courses' even without its tag");
        GolfCourse handBuilt = GolfCourse.create("meadow", "Meadow Links", "games");
        for (String verb : List.of("info", "tp", "hole", "name", "enable", "disable", "delete")) {
            assertNull(GolfAdmin.dailyRefusal(handBuilt, verb), "a hand-built course is untouched: " + verb);
        }
    }

    @Test
    void aTeeCupOrBoundNearADailyAreaIsRefused() {
        Box half = Slots.TINY_GOLF.half('B'); // x 5216-5279, y 160-175, z 4096-4143
        GeneratedCourses g = new GeneratedCourses() {
            @Override
            public boolean live(String courseId, GenTag tag) {
                return true;
            }

            @Override
            public boolean standing(GenTag tag) {
                return false;
            }

            @Override
            public String closedLine(String courseId) {
                return "";
            }

            @Override
            public long nextChangeAt() {
                return -1;
            }

            @Override
            public boolean inArea(String world, int x, int y, int z) {
                return "games".equals(world) && half.contains(x, y, z);
            }
        };
        assertEquals(GenCopy.EDITOR_REFUSED, GolfAdmin.areaRefusal(g, "games", 5220, 161, 4100), "inside");
        assertEquals(GenCopy.EDITOR_REFUSED, GolfAdmin.areaRefusal(g, "games", 5200, 191, 4159),
                "16 out on every side at once, the half being only 16 high");
        assertNull(GolfAdmin.areaRefusal(g, "games", 5199, 170, 4100), "17 west: fine");
        assertNull(GolfAdmin.areaRefusal(g, "games", 5220, 192, 4100), "17 above: fine");
        assertNull(GolfAdmin.areaRefusal(g, "games", 5220, 143, 4100), "17 below: fine");
        assertNull(GolfAdmin.areaRefusal(GeneratedCourses.NONE, "games", 5220, 161, 4100), "no engine: no areas");
    }
}
