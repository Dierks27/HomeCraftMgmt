package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.admin.GenAdmin;
import com.dierks.homecraft.games.gen.admin.GenOps;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Planner;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenKit.FakePlanner;
import com.dierks.homecraft.games.gen.engine.GenKit.Host;
import com.dierks.homecraft.gui.games.daily.FreshAdmin;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-2 audit, G2 #1: an admin tool's "Sure?" screen acts on what it showed, never on whatever stands
 * when Yes is pressed. The screen can stay open for as long as a whole new preview takes: another admin
 * (or the console) builds one, or the admin's own new preview was still being planned when they opened
 * the tools, which still showed the old one. Yes then promoted, or chose, a course nobody had tried.
 *
 * <p>With the engine, a fake clock and world and the real database ({@link GenKit}), the real command
 * ({@link GenAdmin}) and the real tools ({@link FreshAdmin}): a tool is painted from the engine's view,
 * its Sure screen opened, something changes, and its Yes runs exactly the words the button sends.
 */
class SureScreenBindingTest {

    private static final String SLOT = "fresh_parkour_easy";
    private static final Slots.Def DEF = Slots.DAILY_PARKOUR_EASY;
    private static final long TRIED = 0x3f2aL;
    private static final long UNTRIED = 0x5eedL;

    private Host host;
    private GenService gen;
    private GenAdmin admin;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new Host(GenKit.at(2026, 9, 29, 4, 0) + 40_000, SLOT);
        host.settings = GenKit.weekly(SLOT);
        Map<String, Planner> planners = new LinkedHashMap<>();
        for (String id : List.of(Slots.PARKOUR, Slots.RINGS, Slots.GOLF, Slots.BOAT)) {
            planners.put(id, new FakePlanner(id));
        }
        gen = new GenService(host, planners);
        gen.start();
        gen.worldsReady();
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        admin = new GenAdmin(() -> gen, log);
        drive(70);
        assertNotNull(gen.liveTag(SLOT), "the week's set is up");
    }

    @AfterEach
    void tearDown() throws Exception {
        host.connection.close();
    }

    /** Twenty ticks and a check per second, 50 ms a tick. */
    private void drive(int seconds) {
        for (int s = 0; s < seconds; s++) {
            for (int t = 0; t < 20; t++) {
                gen.tick();
                host.now += 50;
            }
            gen.check();
        }
    }

    private String heard() {
        return String.join("\n", said);
    }

    /** The admin who pressed the buttons, as the command sees them. */
    private CommandSender owner() {
        return (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{CommandSender.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "sendMessage" -> {
                        said.add(a[0] instanceof Component c ? net.kyori.adventure.text.serializer.plain
                                .PlainTextComponentSerializer.plainText().serialize(c) : String.valueOf(a[0]));
                        yield null;
                    }
                    case "getName" -> "Owner";
                    case "hasPermission" -> true;
                    case "hashCode" -> 7;
                    case "equals" -> proxy == a[0];
                    default -> m.getReturnType() == boolean.class ? false : null;
                });
    }

    /** The tools screen as the engine shows the slot now, and its {@code kind} tool. */
    private FreshAdmin.Tool painted(FreshAdmin.Kind kind) {
        GenOps.Tools t = gen.tools(SLOT);
        return FreshAdmin.tools(DEF, t).stream().filter(x -> x.kind() == kind).findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " tool on " + t));
    }

    /** A click on {@code tool}: it asks "Sure?" first (the screen it opens), and nothing runs yet. */
    private void opensSure(FreshAdmin.Tool tool) {
        List<FreshAdmin.Tool> asked = new ArrayList<>();
        FreshAdmin.click(tool, asked::add, words -> {
            throw new AssertionError(tool.kind() + " must ask first");
        });
        assertEquals(List.of(tool), asked, tool.kind() + " opens its Sure screen");
    }

    /** Yes on the Sure screen of {@code tool}: its words through the real command, as the admin. */
    private void yes(FreshAdmin.Tool tool) {
        said.clear();
        FreshAdmin.yes(tool, words -> admin.handle(owner(), words));
    }

    /** Another admin (or the console) builds a new preview of the set up now, and it finishes. */
    private void someoneElsePreviews(long seed) {
        List<String> theirs = new ArrayList<>();
        gen.preview(SLOT, Long.toHexString(seed), theirs::add);
        drive(10);
        assertEquals(seed, gen.tools(SLOT).previewSeed(), "their preview stands now: " + theirs);
    }

    @Test
    void aSureScreenLeftOpenNeverPromotesAPreviewThatCameAfterIt() {
        GenTag week = gen.liveTag(SLOT);
        List<String> mine = new ArrayList<>();
        gen.preview(SLOT, Long.toHexString(TRIED), mine::add);
        drive(10);
        assertEquals(TRIED, gen.tools(SLOT).previewSeed(), "the admin's preview stands: " + mine);

        FreshAdmin.Tool promote = painted(FreshAdmin.Kind.PROMOTE);
        opensSure(promote);
        someoneElsePreviews(UNTRIED);

        yes(promote);
        drive(10);
        assertNotEquals(UNTRIED, gen.liveTag(SLOT).seed(), "the preview nobody tried is not put up: " + heard());
        assertEquals(week.seed(), gen.liveTag(SLOT).seed(), "the live course is untouched: " + heard());
        assertTrue(heard().contains("changed"), "the admin reads that the preview changed and looks again: "
                + heard());

        FreshAdmin.Tool again = painted(FreshAdmin.Kind.PROMOTE);
        opensSure(again);
        yes(again);
        drive(10);
        assertEquals(UNTRIED, gen.liveTag(SLOT).seed(), "a Sure screen painted from the preview that stands now"
                + " promotes it: " + heard());
    }

    @Test
    void aSureScreenLeftOpenNeverChoosesAPreviewThatCameAfterIt() throws Exception {
        List<String> mine = new ArrayList<>();
        gen.previewNext(SLOT, Long.toHexString(TRIED), mine::add);
        drive(10);
        assertEquals(TRIED, gen.tools(SLOT).previewSeed(), "the admin's next-week preview stands: " + mine);

        FreshAdmin.Tool choose = painted(FreshAdmin.Kind.CHOOSE);
        opensSure(choose);
        List<String> theirs = new ArrayList<>();
        gen.previewNext(SLOT, Long.toHexString(UNTRIED), theirs::add);
        drive(10);
        assertEquals(UNTRIED, gen.tools(SLOT).previewSeed(), "someone else's next-week preview stands: " + theirs);

        yes(choose);
        assertNull(host.store.meta(GenAdminKeys.choose(SLOT)), "nothing is chosen from a screen that showed another"
                + " preview: " + heard());
        assertTrue(heard().contains("changed"), "and the admin is told why: " + heard());
    }

    @Test
    void aChooseSureScreenThatShowedNoPickNeverReplacesOneMadeMeanwhile() throws Exception {
        List<String> mine = new ArrayList<>();
        gen.previewNext(SLOT, Long.toHexString(TRIED), mine::add);
        drive(10);
        FreshAdmin.Tool first = painted(FreshAdmin.Kind.CHOOSE);
        assertTrue(first.yes().startsWith("&aYes: use "), "its Yes names no pick to replace: " + first.yes());
        opensSure(first);

        // meanwhile: someone else builds, tries and chooses another course, then previews this seed again
        List<String> theirs = new ArrayList<>();
        gen.previewNext(SLOT, Long.toHexString(UNTRIED), theirs::add);
        drive(10);
        gen.choose(SLOT, false, theirs::add);
        assertNotNull(host.store.meta(GenAdminKeys.choose(SLOT)), "their pick is stored: " + theirs);
        gen.previewNext(SLOT, Long.toHexString(TRIED), theirs::add);
        drive(10);
        assertEquals(TRIED, gen.tools(SLOT).previewSeed(), "the preview is this seed again: " + theirs);

        yes(first);
        assertTrue(host.store.meta(GenAdminKeys.choose(SLOT)).startsWith("0000000000005eed:"), "the pick the Sure"
                + " screen never showed is not replaced without a word: " + heard());
        assertTrue(heard().contains("already has seed 0000000000005eed chosen"), "the command's own warning says"
                + " so: " + heard());
    }
}
