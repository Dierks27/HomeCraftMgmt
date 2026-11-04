package com.dierks.homecraft.command;

import com.dierks.homecraft.arcade.candy.CandyService;
import com.dierks.homecraft.command.HcmCommand.CandyState;
import com.dierks.homecraft.config.CandyConfig;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /hcm harvest} (0.38.6): its words ({@code hat} where candy has {@code bag}), who may use each one, the
 * refusals made before anything is read (walked through {@link HcmCommand#onCommand} with no plugin behind it, so
 * any step past the gate would throw), what it says, and the tab list.
 */
class HarvestCommandTest {

    private static final Set<String> PLAYER = Set.of("hcm.arcade.use");
    private static final Set<String> ADMIN = Set.of("hcm.admin", "hcm.arcade.use");
    private static final String DENIED = "You don't have permission.";

    private static CommandSender sender(Set<String> perms, List<String> told) {
        return (CommandSender) Proxy.newProxyInstance(HarvestCommandTest.class.getClassLoader(),
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

    private static List<String> tab(Set<String> perms, String... args) {
        return new HcmCommand(null).onTabComplete(sender(perms, new ArrayList<>()), null, "hcm", args);
    }

    @Test
    void theHelpListsThanksgivingsThreeCommandsForPlayersAndTheirWordsForAdmins() {
        List<String> player = run(PLAYER);
        assertTrue(player.contains("/hcm harvest - your Harvest Basket treats and the next shower"), player.toString());
        assertTrue(player.contains("/hcm turkey - the next turkey and your catches today"));
        assertTrue(player.contains("/hcm feast - the Family Feast table and your gifts"));
        String said = String.join("\n", player);
        assertFalse(said.contains("harvest now") || said.contains("turkey now") || said.contains("feast pot"),
                "the admin words are not in the player help");
        List<String> admin = run(ADMIN);
        assertTrue(admin.contains("/hcm harvest now [player]|status|clear|hat <player>|add <player> <n> - the Harvest "
                + "Basket"), admin.toString());
        assertTrue(admin.contains("/hcm turkey now [player]|status|clear - Catch the Turkey"));
        assertTrue(admin.contains("/hcm feast pot [clear]|status|test|reset confirm - the Family Feast"));
    }

    @Test
    void theWordsAreCandysWithHatForBag() {
        assertEquals(List.of("now", "status", "clear", "hat", "add"), HarvestCommand.VERBS);
        assertEquals("", HarvestCommand.verb(new String[]{"harvest"}), "no word: the player's own count");
        assertEquals("hat", HarvestCommand.verb(new String[]{"harvest", "HAT"}));
        assertNull(HarvestCommand.verb(new String[]{"harvest", "bag"}), "the basket has no bag");
        assertEquals("hcm.arcade.use", HarvestCommand.permission(""));
        for (String verb : HarvestCommand.VERBS) {
            assertEquals("hcm.admin", HarvestCommand.permission(verb), verb + " runs the basket");
        }
        assertTrue(HarvestCommand.USAGE.contains("now [player]|status|clear|hat <player>|add <player> <n>"));
    }

    @Test
    void aPlayerIsRefusedEveryAdminWordBeforeAnythingIsRead() {
        for (String[] args : List.of(new String[]{"harvest", "now"}, new String[]{"harvest", "now", "Ava"},
                new String[]{"harvest", "status"}, new String[]{"harvest", "clear"},
                new String[]{"harvest", "hat", "Ava"}, new String[]{"harvest", "add", "Ava", "30"})) {
            assertEquals(List.of(DENIED), run(PLAYER, args), String.join(" ", args) + ": refused, nothing else");
        }
        assertEquals(List.of(DENIED), run(Set.of(), "harvest"), "without the Arcade even the count is refused");
        assertEquals(List.of(DENIED), run(Set.of(), "harvest", "what"));
    }

    @Test
    void wrongWordsAreToldBeforeAnythingIsRead() {
        assertEquals(List.of("Usage: /hcm harvest"), run(PLAYER, "harvest", "bag"), "no admin words for a player");
        assertEquals(List.of(HarvestCommand.USAGE), run(ADMIN, "harvest", "bag"));
        assertEquals(List.of("Usage: /hcm harvest hat <player>"), run(ADMIN, "harvest", "hat"));
        assertEquals(List.of("Usage: /hcm harvest add <player> <n>"), run(ADMIN, "harvest", "add", "Ava"));
        assertEquals(List.of("Add 1-200 treats: /hcm harvest add <player> <n>"),
                run(ADMIN, "harvest", "add", "Ava", "0"));
        assertTrue(run(ADMIN, "harvest").get(0).startsWith("Only players collect treats."),
                "the console has no count of its own");
    }

    @Test
    void whatAPlayerIsTold() {
        LocalDate nov1 = LocalDate.of(2026, 11, 1);
        assertEquals(List.of("&6You've found &f3 &6treats. &f23 &6more for the Pilgrim Hat! &7Next shower at 7:40 PM."),
                HarvestCommand.mineLines(CandyState.OPEN, nov1, 3, 26, false, "at 7:40 PM", false));
        assertEquals(List.of("&6Treats start falling on 1 Nov!"),
                HarvestCommand.mineLines(CandyState.NOT_YET, nov1, 0, 26, false, null, false));
        assertEquals("&6You've found &f26 &6treats. &aYou got your Pilgrim Hat!",
                HarvestCommand.found(26, 26, true, true, false));
        assertTrue(HarvestCommand.found(26, 26, false, true, false).contains("make room on your head or in your bag"));
        assertTrue(HarvestCommand.found(26, 26, false, true, true).contains("back from the Games"));
        assertEquals("&6You found &f1 &6treat.", HarvestCommand.found(1, 26, false, false, false));
        for (String line : HarvestCommand.mineLines(CandyState.OPEN, nov1, 3, 26, false, "at 7:40 PM", false)) {
            assertTrue(line.chars().allMatch(c -> c < 0x80), "plain letters: " + line);
        }
    }

    @Test
    void whatAnAdminIsTold() {
        CandyConfig.Candy h = CandyConfig.HARVEST_DEFAULTS;
        assertEquals("The Harvest Basket starts on 1 Nov.", HarvestCommand.closed(CandyState.NOT_YET, h));
        assertEquals("The Harvest Basket is switched off in config.yml (arcade.events.harvest).",
                HarvestCommand.closed(CandyState.OFF, h));
        assertNull(HarvestCommand.closed(CandyState.OPEN, h));
        assertEquals("&eAva &7- found &f30&7/26, &f2 &7out, idle &f0 &7min, hat: &agiven",
                HarvestCommand.playerLine("Ava", 30, 26, true, 2, 0));
        assertTrue(HarvestCommand.playerLine("Ava", 26, 26, false, 0, 0).endsWith("hat: &6owed"));
        assertEquals("&aAva got their Pilgrim Hat.", HarvestCommand.addHatLine("Ava", CandyService.BagResult.GIVEN));
        assertEquals("&7Their Pilgrim Hat is waiting: Ava's bag is full.",
                HarvestCommand.addHatLine("Ava", CandyService.BagResult.FULL));
        assertNull(HarvestCommand.addHatLine("Ava", CandyService.BagResult.NOT_YET));
        assertNull(HarvestCommand.addHatLine("Ava", null));
    }

    @Test
    void tabOffersTheThanksgivingWords() {
        assertTrue(tab(PLAYER, "ha").contains("harvest"), "the first word, for a player");
        assertTrue(tab(ADMIN, "tu").contains("turkey"));
        assertTrue(tab(ADMIN, "fe").contains("feast"));
        assertEquals(List.of("now", "status", "clear", "hat", "add"), tab(ADMIN, "harvest", ""));
        assertEquals(List.of("hat"), tab(ADMIN, "harvest", "H"));
        assertEquals(List.of("now", "status", "clear"), tab(ADMIN, "turkey", ""));
        assertEquals(List.of("pot", "status", "test", "reset"), tab(ADMIN, "feast", ""));
        assertEquals(List.of(), tab(PLAYER, "harvest", ""), "a player has no words to complete");
        assertEquals(List.of("now", "status", "clear", "bag", "add"), tab(ADMIN, "candy", ""), "candy's as it was");
    }
}
