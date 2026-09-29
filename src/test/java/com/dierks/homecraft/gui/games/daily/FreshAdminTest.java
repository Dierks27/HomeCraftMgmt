package com.dierks.homecraft.gui.games.daily;

import com.dierks.homecraft.games.cup.live.CupLink;
import com.dierks.homecraft.games.gen.admin.GenAdmin;
import com.dierks.homecraft.games.gen.admin.GenOps;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Tier;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.gui.games.trial.CourseMenu;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The owner's Fresh Courses tools on a course's screen (WP-ADM): only an admin sees them, only on a
 * Fresh slot's own course; they sit on a slot of their own; their NAMEs say what each does (for
 * Bedrock), with the regenerate and promote lore warning that players on it finish first and a
 * running Weekly Cup is called off and refunded; the preview's three come once a preview stands (golf
 * is walked, not test-run); and each click reaches its {@code /hcm games gen} command exactly once,
 * through a confirm screen where the command needs {@code confirm}, and from there the engine's
 * one call.
 */
class FreshAdminTest {

    private static final Slots.Def PARKOUR = Slots.of("fresh_parkour");
    private static final Slots.Def GOLF = Slots.of("fresh_golf");
    private static final Pattern BANNED = Pattern.compile(
            "\\b(bet|bets|wager|gamble|gambling|casino|lucky|almost|sink)\\b|so close", Pattern.CASE_INSENSITIVE);

    private static GenOps.Tools state(boolean preview, boolean chosen) {
        return new GenOps.Tools(true, false, 7, preview ? 0x3f2aL : null, preview, chosen ? 0x3f2aL : null,
                chosen ? "Mon 5 Oct-Sun 11 Oct" : null, false, false);
    }

    /** A preview of the set up now, nothing picked. */
    private static GenOps.Tools thisSet() {
        return new GenOps.Tools(true, false, 7, 0x3f2aL, false, null, null, false, false);
    }

    private static List<String> names(List<FreshAdmin.Tool> tools) {
        return tools.stream().map(FreshAdmin.Tool::name).toList();
    }

    private static FreshAdmin.Tool tool(List<FreshAdmin.Tool> tools, FreshAdmin.Kind kind) {
        return tools.stream().filter(t -> t.kind() == kind).findFirst().orElseThrow();
    }

    @Test
    void onlyAnAdminSeesTheToolsAndOnlyOnAFreshSlotsOwnCourse() {
        assertFalse(FreshAdmin.shown(false, "fresh_parkour"), "a player never sees them");
        assertTrue(FreshAdmin.shown(true, "fresh_parkour"), "an admin does, on a Fresh course");
        assertTrue(FreshAdmin.shown(true, "fresh_golf"), "and on golf");
        assertFalse(FreshAdmin.shown(true, "fresh_classic_parkour"), "not on a Classic (it holds a recalled course)");
        assertFalse(FreshAdmin.shown(true, "lava_leap"), "not on a hand-built course");
        assertFalse(FreshAdmin.shown(true, null), "not on nothing");
        assertEquals("hcm.games.admin", FreshAdmin.PERMISSION, "the games' admin permission");
    }

    @Test
    void theToolsItemHasASlotOfItsOwnOnBothCourseScreens() {
        assertEquals(18, FreshAdmin.SLOT, "the bottom row's left end (26 is the Clubhouse's Take a rider)");
        assertFalse(CourseMenu.FIXED_SLOTS.contains(FreshAdmin.SLOT), "clear of every course screen item");
        assertTrue(FreshAdmin.SLOT != CourseMenu.PARTY_SLOT && FreshAdmin.SLOT != CupLink.SLOT
                        && FreshAdmin.SLOT != CourseMenu.RIDER_SLOT,
                "clear of Race with friends (20), the Weekly Cup (24) and Take a rider (26): on a Fresh boat course an"
                        + " admin sees all of them at once");
        assertFalse(List.of(4, 10, 11, 12, 13, 14, 15, 16, 22).contains(FreshAdmin.SLOT),
                "clear of the golf screen's items and its way out");
        assertTrue(FreshAdmin.SLOT >= 18 && FreshAdmin.SLOT < 27, "on the 27-slot screens' bottom row");
    }

    @Test
    void theToolsAreTheOwnersInItemNamesWithThePreviewsOnceOneStands() {
        List<FreshAdmin.Tool> none = FreshAdmin.tools(PARKOUR, state(false, false));
        assertEquals(List.of("&cAdmin: Make a new course now (regenerate)", "&eAdmin: Build one to try (preview)",
                "&eAdmin: Build next week's to try (preview next)"), names(none), "before a preview: these three");
        List<FreshAdmin.Tool> all = FreshAdmin.tools(PARKOUR, state(true, true));
        assertEquals(List.of("&cAdmin: Make a new course now (regenerate)", "&eAdmin: Build one to try (preview)",
                "&eAdmin: Build next week's to try (preview next)", "&aAdmin: Try next week's preview (test run)",
                "&6Admin: Use it next week (choose)", "&7Admin: Cancel next week's pick (unchoose)"), names(all),
                "once next week's preview stands: try it, use it next week; and cancel a pick (fix2-D: next week's"
                        + " preview has no Use it now, which the command refuses)");
        List<FreshAdmin.Tool> thisSet = FreshAdmin.tools(PARKOUR, thisSet());
        assertEquals(List.of("&cAdmin: Make a new course now (regenerate)", "&eAdmin: Build one to try (preview)",
                "&eAdmin: Build next week's to try (preview next)", "&aAdmin: Try this week's preview (test run)",
                "&6Admin: Use it now (promote)", "&6Admin: Use it next week (choose)"), names(thisSet),
                "a preview of this set: try it, use it now, use it next week");
        all = new ArrayList<>(all);
        all.add(tool(thisSet, FreshAdmin.Kind.PROMOTE));
        for (FreshAdmin.Kind k : List.of(FreshAdmin.Kind.REGENERATE, FreshAdmin.Kind.PROMOTE)) {
            String lore = String.join(" ", tool(all, k).lore());
            assertTrue(lore.contains("Players on it finish first. &7A running Weekly Cup here is called off and refunded."),
                    k + " says who finishes and what happens to the Cup: " + lore);
        }
        List<Integer> slots = all.stream().map(FreshAdmin.Tool::slot).toList();
        assertEquals(slots.size(), slots.stream().distinct().count(), "each on its own slot: " + slots);
        assertFalse(slots.contains(4) || slots.contains(22), "clear of the header and the way out");
        for (FreshAdmin.Tool t : all) {
            for (String line : concat(t.name(), t.lore())) {
                assertFalse(BANNED.matcher(line).find(), "kid-safe words: " + line);
                assertTrue(line.codePoints().allMatch(c -> c <= 0xFFFF), "nothing above U+FFFF: " + line);
            }
        }

        List<FreshAdmin.Tool> golf = FreshAdmin.tools(GOLF, state(true, false));
        assertTrue(names(golf).contains("&aAdmin: Walk next week's preview (go there)"), "golf's preview is walked: "
                + names(golf));
        assertFalse(names(golf).contains("&aAdmin: Try next week's preview (test run)"), "not test-run");
        assertFalse(String.join(" ", tool(golf, FreshAdmin.Kind.REGENERATE).lore()).contains("Weekly Cup"),
                "and golf runs no Weekly Cup, so it doesn't mention one");
        assertEquals(List.of(), FreshAdmin.tools(PARKOUR, null), "nothing while Fresh Courses isn't running");

        GenOps.Tools daily = new GenOps.Tools(true, false, 1, 1L, true, null, null, false, false);
        assertTrue(names(FreshAdmin.tools(PARKOUR, daily)).contains("&6Admin: Use it tomorrow (choose)"),
                "the words follow the cadence: " + names(FreshAdmin.tools(PARKOUR, daily)));
        assertTrue(FreshAdmin.state(state(true, true)).contains("&6Picked for Mon 5 Oct-Sun 11 Oct: seed 0000000000003f2a"),
                "the item says what is picked: " + FreshAdmin.state(state(true, true)));
    }

    private static List<String> concat(String first, List<String> rest) {
        List<String> out = new ArrayList<>();
        out.add(first);
        out.addAll(rest);
        return out;
    }

    // ---- each click reaches its command once ----------------------------------------------------------

    /** The engine, as far as the command can tell: what it was asked. */
    private static final class Ops implements GenOps {
        final List<String> calls = new ArrayList<>();

        @Override
        public List<String> status(String slot) {
            return List.of();
        }

        @Override
        public String restartSoon() {
            return null;
        }

        @Override
        public void plan(String slot, String seedOrTomorrow, Consumer<String> report) {
            calls.add("plan");
        }

        @Override
        public void preview(String slot, String seed, Consumer<String> report) {
            calls.add("preview " + slot);
        }

        @Override
        public void promote(String slot, boolean confirm, Consumer<String> report) {
            calls.add("promote " + slot + " " + confirm);
        }

        @Override
        public void reroll(String slot, Consumer<String> report) {
            calls.add("reroll " + slot);
        }

        @Override
        public void rebuild(String slot, Consumer<String> report) {
            calls.add("rebuild");
        }

        @Override
        public void enable(String slot, boolean on, Consumer<String> report) {
            calls.add("enable");
        }

        @Override
        public void tier(String slot, String tierOrMix, Consumer<String> report) {
            calls.add("tier");
        }

        @Override
        public void pin(String slot, String seedOrToday, int days, Consumer<String> report) {
            calls.add("pin");
        }

        @Override
        public void unpin(String slot, Consumer<String> report) {
            calls.add("unpin");
        }

        @Override
        public Spot spot(String slot, boolean idle) {
            calls.add("spot " + slot + " " + idle);
            return null;
        }

        @Override
        public void claim(String slot, boolean confirm, Consumer<String> report) {
            calls.add("claim");
        }

        @Override
        public void clear(String slot, Consumer<String> report) {
            calls.add("clear");
        }

        @Override
        public void previewNext(String slot, String seed, Consumer<String> report) {
            calls.add("previewNext " + slot + " " + seed);
        }

        @Override
        public void choose(String slot, boolean confirm, Consumer<String> report) {
            calls.add("choose " + slot + " " + confirm);
        }

        @Override
        public void unchoose(String slot, Consumer<String> report) {
            calls.add("unchoose " + slot);
        }

        @Override
        public PreviewRun previewRun(String slot) {
            calls.add("previewRun " + slot);
            return new PreviewRun(new Course(slot, TrialKind.PARKOUR, "Parkour", Tier.MEDIUM, "games",
                    new Course.Spot(0.5, 140, 0.5, 0f, 0f), List.of(), new Course.Mark(20.5, 140, 0.5, 2), null, null,
                    true, false, 1), null);
        }
    }

    private static Player admin() {
        UUID id = new UUID(4, 2);
        return (Player) Proxy.newProxyInstance(FreshAdminTest.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "getName" -> "Owner";
                    case "getUniqueId" -> id;
                    case "hasPermission" -> true;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == a[0];
                    default -> zero(m.getReturnType());
                });
    }

    @Test
    void eachToolReachesItsCommandOnceThroughAConfirmScreenWhereTheCommandNeedsConfirm() {
        Map<FreshAdmin.Kind, List<String>> words = Map.of(
                FreshAdmin.Kind.REGENERATE, List.of("regenerate", "fresh_parkour", "confirm"),
                FreshAdmin.Kind.PREVIEW, List.of("preview", "fresh_parkour"),
                FreshAdmin.Kind.PREVIEW_NEXT, List.of("preview", "fresh_parkour", "next"),
                FreshAdmin.Kind.TEST, List.of("test", "fresh_parkour"),
                FreshAdmin.Kind.PROMOTE, List.of("promote", "fresh_parkour", "confirm"),
                FreshAdmin.Kind.CHOOSE, List.of("choose", "fresh_parkour", "confirm"),
                FreshAdmin.Kind.UNCHOOSE, List.of("unchoose", "fresh_parkour"),
                FreshAdmin.Kind.WALK, List.of("tp", "fresh_golf", "idle"));
        Map<FreshAdmin.Kind, String> engine = Map.of(
                FreshAdmin.Kind.REGENERATE, "reroll fresh_parkour",
                FreshAdmin.Kind.PREVIEW, "preview fresh_parkour",
                FreshAdmin.Kind.PREVIEW_NEXT, "previewNext fresh_parkour null",
                FreshAdmin.Kind.TEST, "previewRun fresh_parkour",
                FreshAdmin.Kind.PROMOTE, "promote fresh_parkour true",
                FreshAdmin.Kind.CHOOSE, "choose fresh_parkour true",
                FreshAdmin.Kind.UNCHOOSE, "unchoose fresh_parkour",
                FreshAdmin.Kind.WALK, "spot fresh_golf true");
        List<FreshAdmin.Tool> tools = new ArrayList<>(FreshAdmin.tools(PARKOUR, state(true, true)));
        tools.add(tool(FreshAdmin.tools(PARKOUR, thisSet()), FreshAdmin.Kind.PROMOTE)); // a this-set preview's
        tools.add(tool(FreshAdmin.tools(GOLF, state(true, false)), FreshAdmin.Kind.WALK));
        assertEquals(words.keySet(), tools.stream().map(FreshAdmin.Tool::kind).collect(java.util.stream.Collectors.toSet()),
                "every tool is here");

        Ops ops = new Ops();
        List<String> tested = new ArrayList<>();
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        GenAdmin command = new GenAdmin(() -> ops, log, (p, c, again) -> tested.add(c.id()));
        Player owner = admin();
        for (FreshAdmin.Tool t : tools) {
            List<FreshAdmin.Tool> asked = new ArrayList<>();
            List<String[]> ran = new ArrayList<>();
            FreshAdmin.click(t, asked::add, ran::add);
            if (t.confirm()) {
                assertEquals(List.of(t), asked, t.kind() + " asks first (its command needs confirm)");
                assertEquals(0, ran.size(), t.kind() + " runs nothing before Yes");
                assertTrue(t.yes() != null && t.yes().startsWith("&aYes: "), t.kind() + "'s Yes says what it does: "
                        + t.yes());
                FreshAdmin.yes(t, ran::add);
            } else {
                assertEquals(List.of(), asked, t.kind() + " doesn't ask: its command needs no confirm");
            }
            assertEquals(1, ran.size(), t.kind() + " runs its command once");
            assertEquals(words.get(t.kind()), Arrays.asList(ran.get(0)), t.kind() + "'s command");
            ops.calls.clear();
            command.handle(owner, ran.get(0)); // the command's own path, as the admin who clicked
            assertEquals(List.of(engine.get(t.kind())), ops.calls, t.kind() + " reaches the engine exactly once");
        }
        assertEquals(List.of("fresh_parkour"), tested, "and the test run started once, on the preview");
    }

    /** What a proxy answers for a method nobody asked about: nothing, or a primitive's zero. */
    private static Object zero(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }
}
