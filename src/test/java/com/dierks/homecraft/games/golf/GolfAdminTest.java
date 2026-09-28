package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.GameAdmin;
import com.dierks.homecraft.games.GameContext;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The golf admin tool with no server behind it: a bug in a command is answered with a short red
 * line and never thrown, since it runs inside the game's guard, where a throw would switch golf
 * off and end every round.
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
}
