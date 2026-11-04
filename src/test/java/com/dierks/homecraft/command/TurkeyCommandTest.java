package com.dierks.homecraft.command;

import com.dierks.homecraft.config.TurkeyConfig.Turkey;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /hcm turkey}: its words, who may use each, the refusals made before the plugin is touched (walked through
 * {@link TurkeyCommand#handle} with no plugin behind it, so any step past a gate would throw), and what it says,
 * including that a run outside the window is a test run.
 */
class TurkeyCommandTest {

    private static final Set<String> PLAYER = Set.of("hcm.arcade.use");
    private static final Set<String> ADMIN = Set.of("hcm.admin", "hcm.arcade.use");
    private static final String DENIED = "You don't have permission.";

    /** A console-like sender (not a player) with exactly these permissions; what it is told lands in {@code told}. */
    private static CommandSender sender(Set<String> perms, List<String> told) {
        return (CommandSender) Proxy.newProxyInstance(TurkeyCommandTest.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, m, a) -> switch (m.getName()) {
                    case "hasPermission" -> a[0] instanceof String node && perms.contains(node);
                    case "getName" -> "Tester";
                    case "sendMessage" -> {
                        if (a[0] instanceof Component c) {
                            told.add(Text.plain(LegacyComponentSerializer.legacyAmpersand().serialize(c)));
                        } else if (a[0] instanceof String s) {
                            told.add(Text.plain(s));
                        }
                        yield null;
                    }
                    case "equals" -> proxy == a[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Tester";
                    default -> m.getReturnType() == boolean.class ? false : null;
                });
    }

    private static List<String> run(Set<String> perms, String... args) {
        List<String> told = new ArrayList<>();
        TurkeyCommand.handle(null, sender(perms, told), args);
        return told;
    }

    @Test
    void theWords() {
        assertEquals("", TurkeyCommand.verb(new String[]{"turkey"}), "no word: the player's own line");
        assertEquals("", TurkeyCommand.verb(null));
        for (String verb : List.of("now", "status", "clear")) {
            assertEquals(verb, TurkeyCommand.verb(new String[]{"turkey", verb.toUpperCase()}), verb + ", any case");
        }
        assertNull(TurkeyCommand.verb(new String[]{"turkey", "spawn"}));
        assertEquals(List.of("now", "status", "clear"), TurkeyCommand.VERBS);
        assertEquals("hcm.arcade.use", TurkeyCommand.permission(""));
        for (String verb : TurkeyCommand.VERBS) {
            assertEquals("hcm.admin", TurkeyCommand.permission(verb), verb + " is the admins' (no new node)");
        }
    }

    @Test
    void aPlayerIsRefusedEveryAdminWordBeforeAnythingIsRead() {
        for (String[] args : List.of(new String[]{"turkey", "now"}, new String[]{"turkey", "now", "Ava"},
                new String[]{"turkey", "status"}, new String[]{"turkey", "clear"})) {
            assertEquals(List.of(DENIED), run(PLAYER, args), String.join(" ", args));
        }
        assertEquals(List.of(DENIED), run(Set.of(), "turkey"), "no Arcade: not even your own line");
        assertEquals(List.of(DENIED), run(Set.of(), "turkey", "spawn"), "an unknown word with no Arcade");
    }

    @Test
    void anUnknownWordShowsTheUsage() {
        assertEquals(List.of("Usage: /hcm turkey"), run(PLAYER, "turkey", "spawn"));
        assertEquals(List.of(TurkeyCommand.USAGE), run(ADMIN, "turkey", "spawn"), "admins see every word");
    }

    @Test
    void theConsoleHasNoTurkeysOfItsOwn() {
        List<String> told = run(ADMIN, "turkey");
        assertEquals(1, told.size());
        assertTrue(told.get(0).startsWith("Only players catch turkeys."), told.get(0));
    }

    private static final Turkey T = Turkey.DEFAULTS;

    @Test
    void whereItStands() {
        assertEquals(TurkeyCommand.State.NOT_YET, TurkeyCommand.state(T, true, LocalDate.of(2026, 11, 21)));
        assertEquals(TurkeyCommand.State.OPEN, TurkeyCommand.state(T, true, LocalDate.of(2026, 11, 22)));
        assertEquals(TurkeyCommand.State.OPEN, TurkeyCommand.state(T, true, LocalDate.of(2026, 11, 29)));
        assertEquals(TurkeyCommand.State.OVER, TurkeyCommand.state(T, true, LocalDate.of(2026, 11, 30)));
        assertEquals(TurkeyCommand.State.OFF, TurkeyCommand.state(T.withEnabled(false), true, LocalDate.of(2026, 11, 24)));
        assertEquals(TurkeyCommand.State.ARCADE_OFF, TurkeyCommand.state(T, false, LocalDate.of(2026, 11, 24)));
        for (TurkeyCommand.State s : TurkeyCommand.State.values()) {
            String text = Text.plain(TurkeyCommand.stateText(s));
            assertTrue(s == TurkeyCommand.State.OPEN || text.contains("now is a test run"),
                    s + ": the status says now is a test run outside the window");
        }
    }

    @Test
    void aRunOutsideTheWindowIsATestRun() {
        assertTrue(TurkeyCommand.TEST_LINE.contains("No claim, no feather, no tokens"));
        assertEquals("&aAva &7- turkey", TurkeyCommand.nowLine("Ava", null));
        assertEquals("&eAva &7- in a game", TurkeyCommand.nowLine("Ava", "in a game"));
    }

    @Test
    void whatItSays() {
        assertEquals("Window: 22 Nov - 29 Nov 2026 (UTC). A run every 240 min from 00:50, 3 min each, and a welcome "
                + "turkey.", Text.plain(TurkeyCommand.windowText(T, ZoneOffset.UTC)));
        assertTrue(Text.plain(TurkeyCommand.windowText(T, ZoneId.of("America/Chicago"))).contains("(America/Chicago)"));
        LocalDateTime now = LocalDateTime.of(2026, 11, 24, 15, 0);
        assertEquals("at 4:50 PM", TurkeyCommand.when(now, LocalDateTime.of(2026, 11, 24, 16, 50)));
        assertEquals("tomorrow at 12:50 AM", TurkeyCommand.when(now, LocalDateTime.of(2026, 11, 25, 0, 50)));
        assertEquals("on 27 Nov at 12:50 AM", TurkeyCommand.when(now, LocalDateTime.of(2026, 11, 27, 0, 50)));
        assertEquals(List.of("&6Turkeys caught today: &f2 &7Next turkey at 4:50 PM."),
                TurkeyCommand.mineLines(TurkeyCommand.State.OPEN, T.from(), 2, "at 4:50 PM"));
        assertEquals(List.of("&6Turkeys caught today: &f1 &7That was the last turkey this year!"),
                TurkeyCommand.mineLines(TurkeyCommand.State.OPEN, T.from(), 1, null));
        assertEquals(List.of("&6Turkeys come on 22 Nov! Tap one to catch it."),
                TurkeyCommand.mineLines(TurkeyCommand.State.NOT_YET, T.from(), 0, null));
        assertEquals("Run s1 - 42s left, 2 out, caught by: Ava, Ben",
                Text.plain(TurkeyCommand.runLine("s1", false, false, 42, 2, List.of("Ava", "Ben"))));
        assertEquals("Run t9 (test) - 10s left, 6 out (cap), caught by: nobody yet",
                Text.plain(TurkeyCommand.runLine("t9", false, true, 10, 6, List.of())));
        for (TurkeyCommand.State s : TurkeyCommand.State.values()) {
            for (String line : TurkeyCommand.mineLines(s, T.from(), 3, "at 4:50 PM")) {
                assertTrue(line.chars().allMatch(ch -> ch < 128), line + " is ASCII (Bedrock)");
            }
        }
    }
}
