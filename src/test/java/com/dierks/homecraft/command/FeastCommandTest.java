package com.dierks.homecraft.command;

import com.dierks.homecraft.arcade.feast.FeastRules;
import com.dierks.homecraft.config.FeastConfig;
import com.dierks.homecraft.storage.FeastDao;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /hcm feast} (0.38.6): its words, who may use each one, the refusals made before anything is read (walked
 * through {@link HcmCommand#onCommand} with no plugin behind it, so any step past the gate would throw), and what a
 * player and an admin are told.
 */
class FeastCommandTest {

    private static final Set<String> PLAYER = Set.of("hcm.arcade.use");
    private static final Set<String> ADMIN = Set.of("hcm.admin", "hcm.arcade.use");
    private static final String DENIED = "You don't have permission.";

    private static CommandSender sender(Set<String> perms, List<String> told) {
        return (CommandSender) Proxy.newProxyInstance(FeastCommandTest.class.getClassLoader(),
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
        new HcmCommand(null).onCommand(sender(perms, told), null, "hcm", args);
        return told;
    }

    @Test
    void theWords() {
        assertEquals(List.of("pot", "status", "test", "reset"), FeastCommand.VERBS);
        assertEquals(FeastCommand.VERBS, HcmCommand.THANKSGIVING_VERBS.get("feast"), "the tab list offers the same");
        assertEquals("", FeastCommand.verb(new String[]{"feast"}), "no word: the family's line");
        assertEquals("pot", FeastCommand.verb(new String[]{"feast", "POT"}));
        assertNull(FeastCommand.verb(new String[]{"feast", "basket"}));
        assertEquals("hcm.arcade.use", FeastCommand.permission(""));
        for (String verb : FeastCommand.VERBS) {
            assertEquals("hcm.admin", FeastCommand.permission(verb), verb + " runs the Feast");
        }
    }

    @Test
    void aPlayerIsRefusedEveryAdminWordBeforeAnythingIsRead() {
        for (String[] args : List.of(new String[]{"feast", "pot"}, new String[]{"feast", "pot", "clear"},
                new String[]{"feast", "status"}, new String[]{"feast", "test"},
                new String[]{"feast", "reset", "confirm"})) {
            assertEquals(List.of(DENIED), run(PLAYER, args), String.join(" ", args) + ": refused, nothing else");
        }
        assertEquals(List.of(DENIED), run(Set.of(), "feast"), "without the Arcade even the line is refused");
    }

    @Test
    void wrongWordsAreToldBeforeAnythingIsRead() {
        assertEquals(List.of("Usage: /hcm feast"), run(PLAYER, "feast", "basket"));
        assertEquals(List.of(FeastCommand.USAGE), run(ADMIN, "feast", "basket"));
        assertTrue(run(ADMIN, "feast", "reset").get(0).contains("/hcm feast reset confirm"),
                "a reset needs its confirm");
        assertEquals(List.of("Usage: /hcm feast pot [clear]"), run(ADMIN, "feast", "pot", "here"));
        assertTrue(run(ADMIN, "feast", "pot").get(0).startsWith("Only a player can do that."), "the console can't look");
        assertTrue(run(ADMIN, "feast").get(0).startsWith("Only a player can do that."));
    }

    @Test
    void whatAPlayerIsTold() {
        FeastConfig.Feast c = FeastConfig.Feast.DEFAULTS;
        List<String> open = FeastCommand.mineLines(new FeastRules.FeastView(true, 75, 150, 10, false, 3), 9, c, false);
        assertEquals(FeastRules.line(75, 150) + " " + FeastRules.bar(75, 150), open.get(0));
        assertEquals("&7You gave &f9 &7points.", open.get(1));
        assertEquals("&7Tap the Feast Pot holding food to put it in.", open.get(2));
        assertEquals("&aThe tables are full! &7Thank you for helping.",
                FeastCommand.mineLines(new FeastRules.FeastView(true, 150, 150, 20, true, 4), 1, c, false).get(2));
        assertEquals("&7The Family Feast opens on 2026-11-22.",
                FeastCommand.mineLines(FeastRules.FeastView.CLOSED, 0, c, false).get(2));
        assertEquals("&7The Family Feast is over.", FeastCommand.mineLines(FeastRules.FeastView.CLOSED, 0, c, true)
                .get(2));
        for (String line : open) {
            assertTrue(line.chars().allMatch(ch -> ch < 0x80), "plain letters: " + line);
        }
    }

    @Test
    void whatAResetSays() {
        assertTrue(FeastCommand.resetLine("2026", new FeastDao.Reset(true, 0, 0, 0, 0)).contains("can't be reset"));
        assertEquals("&aThe Family Feast test is reset: 3 score rows, 2 markers, 4 player marks, 2 test rewards "
                        + "(their tokens stay paid).",
                FeastCommand.resetLine("test", new FeastDao.Reset(false, 3, 2, 4, 2)));
    }
}
