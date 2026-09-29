package com.dierks.homecraft.games.gen.admin;

import com.dierks.homecraft.games.gen.api.Slots;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /hcm games gen} (GEN-SPEC §5.5): the words are parsed and the slot checked before the
 * engine is asked; destructive verbs need {@code confirm}; reroll, preview and promote are refused
 * near a restart; tier and mix go to the right kind of course; changes are logged with who made
 * them; a bug answers "That didn't work" and never reaches the framework; tab completion offers
 * the verbs, the slots and the next word.
 */
class GenAdminTest {

    /** The engine, as far as the command can tell: it records what it was asked. */
    private static final class Ops implements GenOps {
        final List<String> calls = new ArrayList<>();
        String restart;
        boolean throwOnStatus;

        @Override
        public List<String> status(String slot) {
            if (throwOnStatus) {
                throw new IllegalStateException("broken on purpose");
            }
            calls.add("status " + slot);
            return List.of("&fline");
        }

        @Override
        public String restartSoon() {
            return restart;
        }

        @Override
        public void plan(String slot, String arg, Consumer<String> report) {
            calls.add("plan " + slot + " " + arg);
        }

        @Override
        public void preview(String slot, String seed, Consumer<String> report) {
            calls.add("preview " + slot + " " + seed);
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
            calls.add("rebuild " + slot);
        }

        @Override
        public void enable(String slot, boolean on, Consumer<String> report) {
            calls.add((on ? "on " : "off ") + slot);
        }

        @Override
        public void tier(String slot, String tierOrMix, Consumer<String> report) {
            calls.add("tier " + slot + " " + tierOrMix);
        }

        @Override
        public void pin(String slot, String seedOrToday, int days, Consumer<String> report) {
            calls.add("pin " + slot + " " + seedOrToday + " " + days);
        }

        @Override
        public void unpin(String slot, Consumer<String> report) {
            calls.add("unpin " + slot);
        }

        @Override
        public Spot spot(String slot, boolean idle) {
            return null;
        }

        @Override
        public void claim(String slot, boolean confirm, Consumer<String> report) {
            calls.add("claim " + slot + " " + confirm);
        }

        @Override
        public void clear(String slot, Consumer<String> report) {
            calls.add("clear " + slot);
        }

        @Override
        public List<String> history(String slot, int page) {
            calls.add("history " + slot + " " + page);
            return List.of("&fHARD-1");
        }

        @Override
        public List<String> historyOf(String slot, GenArgs.Which which) {
            calls.add("historyOf " + slot + " " + which.typed());
            return List.of("&6HARD-1");
        }

        @Override
        public void recall(String classic, String slot, GenArgs.Which which, int days, boolean confirm,
                           Consumer<String> report) {
            calls.add("recall " + classic + " " + slot + " " + which.typed() + " " + days + " " + confirm);
        }

        @Override
        public void unrecall(String classic, boolean confirm, Consumer<String> report) {
            calls.add("unrecall " + classic + " " + confirm);
        }

        @Override
        public void keep(String slot, GenArgs.Which which, String id, String name, boolean freshBoard, boolean confirm,
                         Consumer<String> report) {
            calls.add("keep " + slot + " " + which.typed() + " " + id + " " + name + " " + freshBoard + " " + confirm);
        }

        @Override
        public List<String> plots() {
            calls.add("plots");
            return List.of("&6Kept courses");
        }

        @Override
        public void clearPlot(int n, boolean confirm, Consumer<String> report) {
            calls.add("clear-plot " + n + " " + confirm);
        }

        @Override
        public void claimPlot(int n, boolean confirm, Consumer<String> report) {
            calls.add("claim plot " + n + " " + confirm);
        }

        @Override
        public List<Integer> usedPlots() {
            return List.of(1, 3);
        }
    }

    private Ops ops;
    private GenAdmin admin;
    private final List<String> said = new ArrayList<>();
    private final List<LogRecord> logs = new ArrayList<>();
    private CommandSender console;

    @BeforeEach
    void setUp() {
        ops = new Ops();
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                logs.add(r);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        admin = new GenAdmin(() -> ops, log);
        console = (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{CommandSender.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "sendMessage" -> {
                        if (a[0] instanceof Component c) {
                            said.add(LegacyComponentSerializer.legacyAmpersand().serialize(c)
                                    .replaceAll("&[0-9a-fk-or]", ""));
                        }
                        yield null;
                    }
                    case "getName" -> "Console";
                    case "hasPermission" -> true;
                    default -> null;
                });
    }

    private void run(String line) {
        said.clear();
        admin.handle(console, line.isBlank() ? new String[0] : line.split(" "));
    }

    private String heard() {
        return String.join("\n", said);
    }

    @Test
    void theWordsAreParsedAndTheSlotCheckedBeforeTheEngineIsAsked() {
        run("status");
        assertEquals(List.of("status null"), ops.calls, "status of every slot");
        run("status Fresh_Tiny_Golf");
        assertEquals("status fresh_tiny_golf", ops.calls.get(1), "a slot in any case is read as its id");
        run("rebuild mystery");
        assertTrue(heard().contains("No Fresh Course called 'mystery'"), heard());
        assertEquals(2, ops.calls.size(), "an unknown slot never reaches the engine");
        run("plan fresh_golf next");
        run("plan fresh_golf tomorrow");
        run("plan fresh_golf 3F2A");
        run("plan fresh_golf nope");
        assertTrue(heard().contains("16 hex digits"), "a bad seed is refused: " + heard());
        assertEquals(List.of("status null", "status fresh_tiny_golf", "plan fresh_golf next",
                "plan fresh_golf tomorrow", "plan fresh_golf 3F2A"), ops.calls,
                "plans for the next set (tomorrow still reads as next) and a seed");
        run("wibble");
        assertTrue(heard().contains("Unknown: wibble"), heard());
        run("");
        assertTrue(heard().contains("/hcm games gen status"), "no words shows the help");
        run("on");
        assertTrue(heard().contains("Which course?"), "a verb without a slot asks which");
    }

    @Test
    void destructiveVerbsNeedConfirm() {
        run("reroll fresh_parkour_easy");
        assertTrue(heard().contains("reroll fresh_parkour_easy confirm"), "reroll asks for confirm: " + heard());
        run("clear fresh_rings");
        assertTrue(heard().contains("clear fresh_rings confirm"), "clear asks for confirm: " + heard());
        run("reroll all");
        assertTrue(heard().contains("reroll all confirm"), heard());
        assertTrue(ops.calls.isEmpty(), "nothing was done without confirm");
        run("reroll fresh_parkour_easy confirm");
        run("clear fresh_rings confirm");
        run("claim fresh_tiny_golf");
        run("claim fresh_tiny_golf confirm");
        run("promote fresh_golf");
        assertEquals(List.of("reroll fresh_parkour_easy", "clear fresh_rings", "claim fresh_tiny_golf false",
                "claim fresh_tiny_golf true", "promote fresh_golf false"), ops.calls,
                "with confirm they go; claim counts first; promote asks the engine (it knows the board)");
        ops.calls.clear();
        run("reroll all confirm");
        assertEquals(Slots.ALL.size(), ops.calls.size(), "reroll all rerolls every slot (nine, the droppers too)");
    }

    @Test
    void rerollPreviewAndPromoteAreRefusedNearARestartAndTheRestAreNot() {
        ops.restart = "&cA restart is coming at 4:00 PM - try after it.";
        run("reroll fresh_golf confirm");
        assertTrue(heard().contains("A restart is coming at 4:00 PM"), heard());
        run("preview fresh_golf");
        run("promote fresh_golf confirm");
        run("reroll all confirm");
        assertTrue(ops.calls.isEmpty(), "none of them reached the engine: " + ops.calls);
        run("rebuild fresh_golf");
        run("off fresh_golf");
        assertEquals(List.of("rebuild fresh_golf", "off fresh_golf"), ops.calls, "a repair and a switch still work");
    }

    @Test
    void tierAndMixGoToTheRightKindOfCourseAndPinsAreChecked() {
        run("tier fresh_golf hard");
        assertTrue(heard().contains("takes a mix"), "golf takes a mix: " + heard());
        run("mix fresh_rings EEM");
        assertTrue(heard().contains("takes a tier"), "rings take a tier: " + heard());
        run("tier fresh_rings extreme");
        assertTrue(heard().contains("easy, medium or hard"), heard());
        run("mix fresh_tiny_golf EEEE");
        assertTrue(heard().contains("1-3"), "tiny golf fits three holes: " + heard());
        run("tier fresh_rings Medium");
        run("mix fresh_tiny_golf emh");
        run("pin fresh_rings live 7");
        run("pin fresh_rings today 7");
        run("pin fresh_rings 3f2a91c07d1e55b0");
        run("pin fresh_rings 3f2a 400");
        assertTrue(heard().contains("1 to 365"), heard());
        run("pin fresh_rings now");
        assertTrue(heard().contains("16 hex digits"), heard());
        run("unpin fresh_rings");
        assertEquals(List.of("tier fresh_rings Medium", "tier fresh_tiny_golf emh",
                "pin fresh_rings live 7", "pin fresh_rings live 7", "pin fresh_rings 3f2a91c07d1e55b0 0",
                "unpin fresh_rings"), ops.calls, "the good ones reached the engine (today reads as live)");
    }

    @Test
    void changesAreLoggedWithWhoMadeThemAndABugNeverEscapes() {
        run("status");
        run("off fresh_tiny_golf");
        assertEquals(1, logs.size(), "only the change is logged: " + logs.stream().map(LogRecord::getMessage).toList());
        assertTrue(logs.get(0).getMessage().contains("Console ran /hcm games gen off fresh_tiny_golf"),
                logs.get(0).getMessage());
        ops.throwOnStatus = true;
        run("status");
        assertTrue(heard().contains("That didn't work - see the console."), "a bug is answered: " + heard());
        assertTrue(logs.stream().anyMatch(r -> r.getLevel() == java.util.logging.Level.SEVERE),
                "and logged, never thrown to the framework");
        GenAdmin off = new GenAdmin(() -> null, Logger.getAnonymousLogger());
        said.clear();
        off.handle(console, new String[]{"status"});
        assertTrue(heard().contains("isn't running"), "while Fresh Courses is off it says so: " + heard());
        run("tp fresh_golf");
        assertTrue(heard().contains("Only players"), "the console can't go anywhere");
    }

    @Test
    void theArchiveVerbsAreParsedBeforeTheEngineIsAsked() {
        run("history");
        run("history fresh_parkour_hard 2");
        run("history all 3");
        run("history HARD-40");
        run("history fresh_parkour_hard date 2026-10-05");
        run("history nowhere");
        assertTrue(heard().contains("No Fresh Course called 'nowhere'"), heard());
        run("plots");
        assertEquals(List.of("history null 1", "history fresh_parkour_hard 2", "history null 3",
                "historyOf fresh_parkour_hard HARD-40", "historyOf fresh_parkour_hard date 2026-10-05", "plots"),
                ops.calls, "pages and single courses go to the engine; a bad course never does");
        ops.calls.clear();
        run("recall HARD-40");
        run("recall hard-40 forever");
        run("recall parkour fresh_parkour_hard last 3 confirm");
        run("recall golf fresh_tiny_golf date 2026-10-05");
        run("recall fresh_classic_rings fresh_rings seed:3f2a9c01b7de");
        run("recall boat fresh_boat last");
        assertTrue(heard().contains("isn't a course code or a Classics slot"), heard());
        run("recall parkour fresh_parkour_hard");
        assertTrue(heard().contains("Which one"), heard());
        assertEquals(List.of("recall null null HARD-40 0 false", "recall null null HARD-40 -1 false",
                "recall fresh_classic_parkour fresh_parkour_hard last 3 true",
                "recall fresh_classic_golf fresh_tiny_golf date 2026-10-05 0 false",
                "recall fresh_classic_rings fresh_rings seed:3f2a9c01b7de 0 false"), ops.calls,
                "codes anywhere an edition is taken, kinds for Classics slots, durations and confirm");
        ops.calls.clear();
        run("keep HARD-40 dragon_run \"Dragon Run\" confirm");
        run("keep fresh_rings sky_loop --fresh-board");
        run("keep fresh_parkour last cliff_hop");
        run("keep");
        assertTrue(heard().contains("Which course"), heard());
        assertEquals(List.of("keep fresh_parkour_hard HARD-40 dragon_run Dragon Run false true",
                "keep fresh_rings current sky_loop null true false", "keep fresh_parkour last cliff_hop null false false"),
                ops.calls, "keep: a code or a course and which, the id, the name, the flag and confirm");
        ops.calls.clear();
        run("unrecall parkour");
        run("unrecall fresh_classic_golf confirm");
        run("unrecall fresh_parkour_hard");
        assertTrue(heard().contains("Which Classics slot?"), heard());
        run("clear-plot 3");
        run("clear-plot 3 confirm");
        run("clear-plot x");
        assertTrue(heard().contains("Which plot?"), heard());
        run("claim plot 2");
        run("claim plot 2 confirm");
        assertEquals(List.of("unrecall fresh_classic_parkour false", "unrecall fresh_classic_golf true",
                "clear-plot 3 false", "clear-plot 3 true", "claim plot 2 false", "claim plot 2 true"), ops.calls,
                "the engine is asked with confirm or without (it says what it would do)");
        ops.calls.clear();
        run("status fresh_classic_golf");
        run("rebuild fresh_classic_golf");
        run("reroll fresh_classic_golf confirm");
        assertTrue(heard().contains("No Fresh Course called"), "a Classics slot is never rerolled: " + heard());
        assertEquals(List.of("status fresh_classic_golf", "rebuild fresh_classic_golf"), ops.calls,
                "status and rebuild take a Classics slot");
    }

    @Test
    void recallAndKeepAreRefusedNearARestartAndChangesAreLogged() {
        ops.restart = "&cA restart is coming at 4:00 PM - try after it.";
        run("recall HARD-40");
        assertTrue(heard().contains("A restart is coming at 4:00 PM"), heard());
        run("keep HARD-40 dragon_run confirm");
        assertTrue(heard().contains("A restart is coming at 4:00 PM"), heard());
        assertTrue(ops.calls.isEmpty(), "neither reached the engine: " + ops.calls);
        run("history all");
        run("unrecall parkour confirm");
        run("clear-plot 1 confirm");
        assertEquals(List.of("history null 1", "unrecall fresh_classic_parkour true", "clear-plot 1 true"), ops.calls,
                "looking, closing and clearing still work");
        assertTrue(logs.stream().anyMatch(r -> r.getMessage().contains("ran /hcm games gen clear-plot 1 confirm")),
                "a change is logged with who made it");
        assertFalse(logs.stream().anyMatch(r -> r.getMessage().contains("gen history")), "a look isn't");
    }

    @Test
    void tabCompletionKnowsTheArchiveVerbs() {
        assertEquals(List.of("recall"), admin.tab(console, new String[]{"reca"}), "the verb");
        assertEquals(List.of("fresh_classic_parkour", "fresh_classic_rings", "fresh_classic_golf", "fresh_classic_dropper",
                "parkour", "rings", "golf", "dropper"), admin.tab(console, new String[]{"recall", ""}),
                "Classics slots and kinds");
        assertEquals(List.of("fresh_dropper_easy", "fresh_dropper"), admin.tab(console, new String[]{"recall",
                "dropper", ""}), "both droppers go into Classic Dropper");
        assertEquals(List.of("fresh_parkour_easy", "fresh_parkour", "fresh_parkour_hard"),
                admin.tab(console, new String[]{"recall", "parkour", ""}), "the courses a kind can hold");
        assertEquals(List.of("fresh_golf", "fresh_tiny_golf"), admin.tab(console, new String[]{"recall",
                "fresh_classic_golf", ""}), "both golf courses go into Classic Golf");
        assertEquals(List.of("last"), admin.tab(console, new String[]{"recall", "parkour", "fresh_parkour", "l"}),
                "which one");
        assertEquals(List.of("forever"), admin.tab(console, new String[]{"recall", "HARD-40", "f"}), "how long");
        assertEquals(List.of("fresh_classic_golf"), admin.tab(console, new String[]{"unrecall", "fresh_classic_g"}),
                "unrecall takes a Classics slot");
        assertEquals(List.of("current"), admin.tab(console, new String[]{"keep", "fresh_rings", "c"}), "keep which");
        assertEquals(List.of("--fresh-board"), admin.tab(console, new String[]{"keep", "HARD-40", "dragon_run", "--"}),
                "keep's flag");
        assertEquals(List.of("1", "3"), admin.tab(console, new String[]{"clear-plot", ""}), "the plots in use");
        assertEquals(List.of("confirm"), admin.tab(console, new String[]{"clear-plot", "3", ""}), "then confirm");
        assertTrue(admin.tab(console, new String[]{"history", ""}).contains("all"), "history all");
        assertTrue(admin.tab(console, new String[]{"claim", ""}).contains("plot"), "claim a plot");
        assertTrue(admin.tab(console, new String[]{"status", ""}).contains("fresh_classic_rings"),
                "status of a Classics slot");
        assertFalse(admin.tab(console, new String[]{"reroll", ""}).contains("fresh_classic_rings"),
                "but no reroll of one");
    }

    @Test
    void tabCompletionOffersTheVerbsTheSlotsAndTheNextWord() {
        assertEquals(List.of("plan", "preview", "promote", "pin", "plots"), admin.tab(console, new String[]{"p"}),
                "verbs by prefix");
        assertTrue(admin.tab(console, new String[]{"reroll", ""}).contains("all"), "reroll offers all");
        assertEquals(List.of("fresh_golf"), admin.tab(console, new String[]{"mix", "fresh_g"}), "mix offers golf only");
        assertEquals(List.of("fresh_golf", "fresh_tiny_golf", "fresh_dropper_easy", "fresh_dropper"),
                admin.tab(console, new String[]{"mix", ""}), "both golf courses and both droppers (EVENTS-DROPPER-SPEC §B.1.2)");
        assertFalse(admin.tab(console, new String[]{"tier", ""}).contains("fresh_dropper"), "tier offers no dropper");
        assertFalse(admin.tab(console, new String[]{"tier", ""}).contains("fresh_tiny_golf"), "tier offers no golf");
        assertEquals(List.of("next"), admin.tab(console, new String[]{"plan", "fresh_rings", ""}), "plan next");
        assertEquals(List.of("live", "idle"), admin.tab(console, new String[]{"tp", "fresh_rings", ""}), "tp where");
        assertEquals(List.of("confirm"), admin.tab(console, new String[]{"clear", "fresh_rings", "c"}),
                "clear confirm");
        assertEquals(List.of("EEE"), admin.tab(console, new String[]{"mix", "fresh_tiny_golf", ""}), "the shipped mix");
        assertEquals(List.of("live"), admin.tab(console, new String[]{"pin", "fresh_rings", "l"}), "pin live");
        assertEquals(List.of(), admin.tab(console, new String[]{"nonsense", ""}), "nothing for an unknown verb");
        assertEquals("gen", admin.name(), "it is /hcm games gen");
        assertEquals(GenAdmin.VERBS.size() - 2, admin.help().size(), "one help line per verb (on/off and pin/unpin share one)");
    }
}
