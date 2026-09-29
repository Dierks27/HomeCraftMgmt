package com.dierks.homecraft.games.gen.admin;

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
        run("status Tiny_Golf");
        assertEquals("status tiny_golf", ops.calls.get(1), "a slot in any case is read as its id");
        run("rebuild mystery");
        assertTrue(heard().contains("No daily course called 'mystery'"), heard());
        assertEquals(2, ops.calls.size(), "an unknown slot never reaches the engine");
        run("plan daily_golf tomorrow");
        run("plan daily_golf 3F2A");
        run("plan daily_golf nope");
        assertTrue(heard().contains("16 hex digits"), "a bad seed is refused: " + heard());
        assertEquals(List.of("status null", "status tiny_golf", "plan daily_golf tomorrow", "plan daily_golf 3F2A"),
                ops.calls, "plans for tomorrow and for a seed");
        run("wibble");
        assertTrue(heard().contains("Unknown: wibble"), heard());
        run("");
        assertTrue(heard().contains("/hcm games gen status"), "no words shows the help");
        run("on");
        assertTrue(heard().contains("Which course?"), "a verb without a slot asks which");
    }

    @Test
    void destructiveVerbsNeedConfirm() {
        run("reroll daily_parkour_easy");
        assertTrue(heard().contains("reroll daily_parkour_easy confirm"), "reroll asks for confirm: " + heard());
        run("clear sky_rings");
        assertTrue(heard().contains("clear sky_rings confirm"), "clear asks for confirm: " + heard());
        run("reroll all");
        assertTrue(heard().contains("reroll all confirm"), heard());
        assertTrue(ops.calls.isEmpty(), "nothing was done without confirm");
        run("reroll daily_parkour_easy confirm");
        run("clear sky_rings confirm");
        run("claim tiny_golf");
        run("claim tiny_golf confirm");
        run("promote daily_golf");
        assertEquals(List.of("reroll daily_parkour_easy", "clear sky_rings", "claim tiny_golf false",
                "claim tiny_golf true", "promote daily_golf false"), ops.calls,
                "with confirm they go; claim counts first; promote asks the engine (it knows the board)");
        ops.calls.clear();
        run("reroll all confirm");
        assertEquals(7, ops.calls.size(), "reroll all rerolls every slot");
    }

    @Test
    void rerollPreviewAndPromoteAreRefusedNearARestartAndTheRestAreNot() {
        ops.restart = "&cA restart is coming at 4:00 PM - try after it.";
        run("reroll daily_golf confirm");
        assertTrue(heard().contains("A restart is coming at 4:00 PM"), heard());
        run("preview daily_golf");
        run("promote daily_golf confirm");
        run("reroll all confirm");
        assertTrue(ops.calls.isEmpty(), "none of them reached the engine: " + ops.calls);
        run("rebuild daily_golf");
        run("off daily_golf");
        assertEquals(List.of("rebuild daily_golf", "off daily_golf"), ops.calls, "a repair and a switch still work");
    }

    @Test
    void tierAndMixGoToTheRightKindOfCourseAndPinsAreChecked() {
        run("tier daily_golf hard");
        assertTrue(heard().contains("takes a mix"), "golf takes a mix: " + heard());
        run("mix sky_rings EEM");
        assertTrue(heard().contains("takes a tier"), "rings take a tier: " + heard());
        run("tier sky_rings extreme");
        assertTrue(heard().contains("easy, medium or hard"), heard());
        run("mix tiny_golf EEEE");
        assertTrue(heard().contains("1-3"), "tiny golf fits three holes: " + heard());
        run("tier sky_rings Medium");
        run("mix tiny_golf emh");
        run("pin sky_rings today 7");
        run("pin sky_rings 3f2a91c07d1e55b0");
        run("pin sky_rings 3f2a 400");
        assertTrue(heard().contains("1 to 365"), heard());
        run("pin sky_rings now");
        assertTrue(heard().contains("16 hex digits"), heard());
        run("unpin sky_rings");
        assertEquals(List.of("tier sky_rings Medium", "tier tiny_golf emh",
                "pin sky_rings today 7", "pin sky_rings 3f2a91c07d1e55b0 0", "unpin sky_rings"), ops.calls,
                "the good ones reached the engine");
    }

    @Test
    void changesAreLoggedWithWhoMadeThemAndABugNeverEscapes() {
        run("status");
        run("off tiny_golf");
        assertEquals(1, logs.size(), "only the change is logged: " + logs.stream().map(LogRecord::getMessage).toList());
        assertTrue(logs.get(0).getMessage().contains("Console ran /hcm games gen off tiny_golf"),
                logs.get(0).getMessage());
        ops.throwOnStatus = true;
        run("status");
        assertTrue(heard().contains("That didn't work - see the console."), "a bug is answered: " + heard());
        assertTrue(logs.stream().anyMatch(r -> r.getLevel() == java.util.logging.Level.SEVERE),
                "and logged, never thrown to the framework");
        GenAdmin off = new GenAdmin(() -> null, Logger.getAnonymousLogger());
        said.clear();
        off.handle(console, new String[]{"status"});
        assertTrue(heard().contains("isn't running"), "while Daily Courses is off it says so: " + heard());
        run("tp daily_golf");
        assertTrue(heard().contains("Only players"), "the console can't go anywhere");
    }

    @Test
    void tabCompletionOffersTheVerbsTheSlotsAndTheNextWord() {
        assertEquals(List.of("plan", "preview", "promote", "pin"), admin.tab(console, new String[]{"p"}),
                "verbs by prefix");
        assertTrue(admin.tab(console, new String[]{"reroll", ""}).contains("all"), "reroll offers all");
        assertEquals(List.of("daily_golf"), admin.tab(console, new String[]{"mix", "d"}), "mix offers golf only");
        assertFalse(admin.tab(console, new String[]{"tier", ""}).contains("tiny_golf"), "tier offers no golf");
        assertEquals(List.of("tomorrow"), admin.tab(console, new String[]{"plan", "sky_rings", ""}), "plan tomorrow");
        assertEquals(List.of("live", "idle"), admin.tab(console, new String[]{"tp", "sky_rings", ""}), "tp where");
        assertEquals(List.of("confirm"), admin.tab(console, new String[]{"clear", "sky_rings", "c"}), "clear confirm");
        assertEquals(List.of("EEE"), admin.tab(console, new String[]{"mix", "tiny_golf", ""}), "the shipped mix");
        assertEquals(List.of("today"), admin.tab(console, new String[]{"pin", "sky_rings", "t"}), "pin today");
        assertEquals(List.of(), admin.tab(console, new String[]{"nonsense", ""}), "nothing for an unknown verb");
        assertEquals("gen", admin.name(), "it is /hcm games gen");
        assertEquals(GenAdmin.VERBS.size() - 2, admin.help().size(), "one help line per verb (on/off and pin/unpin share one)");
    }
}
