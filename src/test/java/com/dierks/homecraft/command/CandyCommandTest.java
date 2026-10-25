package com.dierks.homecraft.command;

import com.dierks.homecraft.arcade.Collectors;
import com.dierks.homecraft.arcade.candy.CandyRules;
import com.dierks.homecraft.arcade.candy.CandyService;
import com.dierks.homecraft.config.CandyConfig.Candy;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.games.RestartHold;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /hcm candy} (HALLOWEEN-SPEC D19): its words, who may use each one, the checks made before anything
 * is read, what it says, and that the help, the tab list and plugin.yml know it.
 *
 * <p>The gates are walked through {@link HcmCommand#onCommand} itself, with no plugin behind it: a refusal
 * must come before the command reaches for the shower, so any step past the gate would throw here.
 */
class CandyCommandTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final Set<String> PLAYER = Set.of("hcm.arcade.use");
    private static final Set<String> ADMIN = Set.of("hcm.admin", "hcm.arcade.use");
    private static final String DENIED = "You don't have permission.";
    /**
     * The cadence 0.37.1-0.38.0 shipped (every 20 minutes from :10, every 10 on the last day, 6 apples): the slot and
     * restart-hold cases below are about that cadence; 0.38.1's gentler one is {@link Candy#DEFAULTS}.
     */
    private static final Candy TWENTY = new Candy(true, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), 20, 10,
            10, 6, 4, 5, 10, 31, false);

    /** A console-like sender (not a player) with exactly these permissions; what it is told lands in {@code told}. */
    private static CommandSender sender(Set<String> perms, List<String> told) {
        return (CommandSender) Proxy.newProxyInstance(CandyCommandTest.class.getClassLoader(),
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

    /** {@code /hcm <args>} from a sender with {@code perms}, with no plugin behind the command: what it said. */
    private static List<String> run(Set<String> perms, String... args) {
        List<String> told = new ArrayList<>();
        new HcmCommand(null).onCommand(sender(perms, told), null, "hcm", args);
        return told;
    }

    private static List<String> tab(Set<String> perms, String... args) {
        return new HcmCommand(null).onTabComplete(sender(perms, new ArrayList<>()), null, "hcm", args);
    }

    private static String plain(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            out.add(Text.plain(line));
        }
        return String.join(" | ", out);
    }

    // ---- the words and who may use them ---------------------------------------------------

    @Test
    void theWordIsTheSecondAndUnknownWordsAreNull() {
        assertEquals("", HcmCommand.candyVerb(new String[]{"candy"}), "no word: the player's own count");
        assertEquals("", HcmCommand.candyVerb(new String[]{"candy", " "}));
        assertEquals("", HcmCommand.candyVerb(null));
        for (String verb : List.of("now", "status", "clear", "bag", "add")) {
            assertEquals(verb, HcmCommand.candyVerb(new String[]{"candy", verb.toUpperCase()}), verb + ", any case");
        }
        assertNull(HcmCommand.candyVerb(new String[]{"candy", "spawn"}), "not a candy word");
        assertEquals(List.of("now", "status", "clear", "bag", "add"), HcmCommand.CANDY_VERBS);
    }

    @Test
    void theCountIsForEveryoneWithTheArcadeAndEveryOtherWordIsAdminOnly() {
        assertEquals("hcm.arcade.use", HcmCommand.candyPermission(""), "default true: every player");
        for (String verb : HcmCommand.CANDY_VERBS) {
            assertEquals("hcm.admin", HcmCommand.candyPermission(verb), verb + " runs the shower (no new node)");
        }
    }

    @Test
    void aPlayerIsRefusedEveryAdminWordBeforeAnythingIsRead() {
        for (String[] args : List.of(new String[]{"candy", "now"}, new String[]{"candy", "now", "Ava"},
                new String[]{"candy", "status"}, new String[]{"candy", "clear"}, new String[]{"candy", "bag", "Ava"},
                new String[]{"candy", "add", "Ava", "30"}, new String[]{"candy", "ADD", "Ava", "lots"})) {
            assertEquals(List.of(DENIED), run(PLAYER, args), String.join(" ", args) + ": refused, nothing else");
        }
    }

    @Test
    void withoutTheArcadeEvenTheCountIsRefused() {
        assertEquals(List.of(DENIED), run(Set.of(), "candy"));
        assertEquals(List.of(DENIED), run(Set.of(), "candy", "what"), "an unknown word is no way round it");
    }

    @Test
    void anUnknownWordShowsTheUsageForWhoeverIsAsking() {
        assertEquals(List.of("Usage: /hcm candy"), run(PLAYER, "candy", "spawn"), "a player never sees the admin words");
        assertEquals(List.of(HcmCommand.CANDY_USAGE), run(ADMIN, "candy", "spawn"));
        assertTrue(HcmCommand.CANDY_USAGE.contains("now [player]|status|clear|bag <player>|add <player> <n>"));
    }

    @Test
    void anAdminsWrongWordsAreRefusedBeforeAnythingIsRead() {
        assertEquals(List.of("Usage: /hcm candy bag <player>"), run(ADMIN, "candy", "bag"));
        assertEquals(List.of("Usage: /hcm candy add <player> <n>"), run(ADMIN, "candy", "add"));
        assertEquals(List.of("Usage: /hcm candy add <player> <n>"), run(ADMIN, "candy", "add", "Ava"));
        for (String n : List.of("0", "201", "-5", "7.5", "lots", "")) {
            assertEquals(List.of("Add 1-200 candy apples: /hcm candy add <player> <n>"),
                    run(ADMIN, "candy", "add", "Ava", n), "'" + n + "' is not 1-200");
        }
    }

    @Test
    void theConsoleHasNoCandyOfItsOwn() {
        List<String> told = run(ADMIN, "candy");
        assertEquals(1, told.size(), told.toString());
        assertTrue(told.get(0).startsWith("Only players collect candy apples."), told.get(0));
    }

    @Test
    void anAdminWithGoodWordsGetsPastTheGate() {
        // Past the gate the command reaches for the shower; with no plugin behind it here, that throws.
        for (String[] args : List.of(new String[]{"candy", "now"}, new String[]{"candy", "status"},
                new String[]{"candy", "clear"}, new String[]{"candy", "bag", "Ava"},
                new String[]{"candy", "add", "Ava", "1"}, new String[]{"candy", "add", "Ava", " 200 "})) {
            assertThrows(NullPointerException.class, () -> run(ADMIN, args), String.join(" ", args));
        }
    }

    @Test
    void theAddNumberIsWholeAndOneToTwoHundred() {
        assertEquals(1, HcmCommand.candyAddAmount("1"));
        assertEquals(30, HcmCommand.candyAddAmount(" 30 "), "the test list's /hcm candy add <you> 30");
        assertEquals(200, HcmCommand.candyAddAmount("200"));
        for (String bad : new String[]{"0", "201", "-1", "1.5", "thirty", "", null}) {
            assertEquals(-1, HcmCommand.candyAddAmount(bad), "'" + bad + "'");
        }
        assertNull(HcmCommand.candyArgsError("add", new String[]{"candy", "add", "Ava", "30"}));
        assertNull(HcmCommand.candyArgsError("now", new String[]{"candy", "now"}), "now needs no player");
        assertNull(HcmCommand.candyArgsError("", new String[]{"candy"}));
    }

    // ---- where the shower stands (D18) -----------------------------------------------------

    @Test
    void theStateFollowsTheWindowTheSwitchAndTheArcade() {
        Candy c = Candy.DEFAULTS;
        assertEquals(HcmCommand.CandyState.NOT_YET, HcmCommand.candyState(c, true, LocalDate.of(2026, 9, 30)));
        assertEquals(HcmCommand.CandyState.OPEN, HcmCommand.candyState(c, true, LocalDate.of(2026, 10, 1)));
        assertEquals(HcmCommand.CandyState.OPEN, HcmCommand.candyState(c, true, LocalDate.of(2026, 10, 31)),
                "the whole of 31 October");
        assertEquals(HcmCommand.CandyState.OVER, HcmCommand.candyState(c, true, LocalDate.of(2026, 11, 1)));
        assertEquals(HcmCommand.CandyState.OFF, HcmCommand.candyState(c.withEnabled(false), true,
                LocalDate.of(2026, 10, 15)));
        assertEquals(HcmCommand.CandyState.OVER, HcmCommand.candyState(c.withEnabled(false), true,
                LocalDate.of(2026, 11, 2)), "after the window it is over, switched off or not");
        assertEquals(HcmCommand.CandyState.ARCADE_OFF, HcmCommand.candyState(c, false, LocalDate.of(2026, 10, 15)));
        Candy backwards = new Candy(false, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 10, 1), 20, 10, 10, 6, 4, 5,
                10, 31, false);
        assertEquals(HcmCommand.CandyState.OFF, HcmCommand.candyState(backwards, true, LocalDate.of(2026, 12, 1)),
                "a window the wrong way round is off (the config WARNed), not \"over\"");
    }

    @Test
    void nowRefusesOutsideTheWindowInTheSpecsWords() {
        Candy c = Candy.DEFAULTS;
        assertNull(HcmCommand.candyClosed(HcmCommand.CandyState.OPEN, c));
        assertEquals("The Candy Apple shower is over for this year.",
                HcmCommand.candyClosed(HcmCommand.CandyState.OVER, c));
        assertEquals("The Candy Apple shower starts on 1 Oct.", HcmCommand.candyClosed(HcmCommand.CandyState.NOT_YET, c));
        assertTrue(HcmCommand.candyClosed(HcmCommand.CandyState.OFF, c).contains("arcade.events.candy"));
        assertTrue(HcmCommand.candyClosed(HcmCommand.CandyState.ARCADE_OFF, c).contains("arcade.enabled"));
    }

    // ---- what a player is told --------------------------------------------------------------

    @Test
    void aPlayerHearsTheirCountWhatIsLeftAndTheNextShower() {
        assertEquals("You've found 17 candy apples. 14 more for the Trick-or-Treat Bag! Next shower at 7:30 PM.",
                plain(HcmCommand.candyMineLines(HcmCommand.CandyState.OPEN, null, 17, 31, false, "at 7:30 PM")), "D19's line, word for word");
        assertEquals("You've found 1 candy apple. 30 more for the Trick-or-Treat Bag! Next shower tomorrow at 12:10 AM.",
                plain(HcmCommand.candyMineLines(HcmCommand.CandyState.OPEN, null, 1, 31, false,
                        "tomorrow at 12:10 AM")));
        assertEquals("You've found 0 candy apples. 31 more for the Trick-or-Treat Bag! That was the last shower this year!",
                plain(HcmCommand.candyMineLines(HcmCommand.CandyState.OPEN, null, 0, 31, false, null)));
    }

    @Test
    void atTheGoalTheBagIsGotOrWaiting() {
        assertEquals("You've found 35 candy apples. You got your Trick-or-Treat Bag! Next shower at 7:30 PM.",
                plain(HcmCommand.candyMineLines(HcmCommand.CandyState.OPEN, null, 35, 31, true, "at 7:30 PM")));
        assertEquals("You've found 31 candy apples. Your Trick-or-Treat Bag is waiting: make room in your bag! "
                        + "Next shower at 7:30 PM.",
                plain(HcmCommand.candyMineLines(HcmCommand.CandyState.OPEN, null, 31, 31, false, "at 7:30 PM")));
        assertEquals("You've found 3 candy apples. You got your Trick-or-Treat Bag! Next shower at 7:30 PM.",
                plain(HcmCommand.candyMineLines(HcmCommand.CandyState.OPEN, null, 3, 31, true, "at 7:30 PM")),
                "a bag an admin gave back counts as got");
        assertEquals("You've found 31 candy apples. Your Trick-or-Treat Bag is waiting: it comes when you're back from "
                        + "the Games or another world! Next shower at 7:30 PM.",
                plain(HcmCommand.candyMineLines(HcmCommand.CandyState.OPEN, null, 31, 31, false, "at 7:30 PM", true)),
                "owed while in the Games world: room isn't what it waits for");
    }

    @Test
    void outsideTheWindowAPlayerIsToldWhy() {
        assertEquals("Candy apples start falling on 1 Oct!",
                plain(HcmCommand.candyMineLines(HcmCommand.CandyState.NOT_YET, LocalDate.of(2026, 10, 1), 0, 31,
                        false, null)));
        assertEquals("The Candy Apple shower is over for this year.",
                plain(HcmCommand.candyMineLines(HcmCommand.CandyState.OVER, null, 0, 31, false, null)));
        assertEquals("The Candy Apple shower is over for this year. | You found 30 candy apples.",
                plain(HcmCommand.candyMineLines(HcmCommand.CandyState.OVER, null, 30, 31, false, null)),
                "no \"1 more\" once none can fall");
        assertEquals("The Candy Apple shower is over for this year. | You found 31 candy apples. Your Trick-or-Treat "
                        + "Bag is waiting: make room in your bag!",
                plain(HcmCommand.candyMineLines(HcmCommand.CandyState.OVER, null, 31, 31, false, null)),
                "an owed bag can still be claimed after the window (D18)");
        assertEquals("You've found 5 candy apples. 26 more for the Trick-or-Treat Bag! | No candy apples are falling "
                        + "right now.",
                plain(HcmCommand.candyMineLines(HcmCommand.CandyState.OFF, null, 5, 31, false, null)));
    }

    @Test
    void theNextShowerIsSaidFromNow() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 20, 19, 12);
        assertEquals("at 7:30 PM", HcmCommand.showerWhen(now, LocalDateTime.of(2026, 10, 20, 19, 30)));
        assertEquals("tomorrow at 12:10 AM", HcmCommand.showerWhen(now, LocalDateTime.of(2026, 10, 21, 0, 10)));
        assertEquals("on 1 Oct at 12:10 AM",
                HcmCommand.showerWhen(LocalDateTime.of(2026, 9, 20, 9, 0), LocalDateTime.of(2026, 10, 1, 0, 10)));
    }

    /** The shipped restart hold (04:00 and 16:00, 5 minutes), read as the shower reads it. */
    private static Predicate<LocalDateTime> shippedHold() {
        RestartHold hold = new RestartHold(List.of(LocalTime.of(4, 0), LocalTime.of(16, 0)), CHICAGO, 5);
        return t -> CandyRules.heldByRestart(CandyRules.slotId(t.toLocalDate(), t.getHour() * 60 + t.getMinute()),
                CHICAGO, hold);
    }

    @Test
    void theNextShowerSkipsASlotARestartHolds() {
        Candy c = TWENTY;
        assertEquals(LocalDateTime.of(2026, 10, 20, 15, 50),
                HcmCommand.nextShower(LocalDateTime.of(2026, 10, 20, 15, 31), c, shippedHold()),
                ":10/:30/:50 are never held");
        assertEquals(LocalDateTime.of(2026, 10, 31, 16, 0),
                HcmCommand.nextShower(LocalDateTime.of(2026, 10, 31, 15, 55), c, null), "nothing held");
        assertEquals(LocalDateTime.of(2026, 10, 31, 16, 10),
                HcmCommand.nextShower(LocalDateTime.of(2026, 10, 31, 15, 55), c, shippedHold()),
                "the last day's 16:00 is skipped, 16:10 fires");
        assertNull(HcmCommand.nextShower(LocalDateTime.of(2026, 10, 31, 23, 50), c, shippedHold()),
                "the 23:50 on the last day was the last");
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 10),
                HcmCommand.nextShower(LocalDateTime.of(2026, 9, 25, 12, 0), c, shippedHold()), "before the window");
    }

    /** 0.38.1's gentler shower: an hour apart at :10, half an hour on the last day, and no restart ever holds one. */
    @Test
    void theGentlerShowerComesHourlyAndNoShippedRestartHoldsIt() {
        Candy c = Candy.DEFAULTS;
        assertEquals(LocalDateTime.of(2026, 10, 25, 16, 10),
                HcmCommand.nextShower(LocalDateTime.of(2026, 10, 25, 15, 11), c, shippedHold()), "an hour apart, at :10");
        assertEquals(LocalDateTime.of(2026, 10, 25, 4, 10),
                HcmCommand.nextShower(LocalDateTime.of(2026, 10, 25, 3, 50), c, shippedHold()),
                "the 04:00 restart holds nothing");
        assertEquals(LocalDateTime.of(2026, 10, 31, 15, 40),
                HcmCommand.nextShower(LocalDateTime.of(2026, 10, 31, 15, 11), c, shippedHold()), "the last day: :10, :40");
        assertEquals(LocalDateTime.of(2026, 10, 31, 16, 10),
                HcmCommand.nextShower(LocalDateTime.of(2026, 10, 31, 15, 41), c, shippedHold()),
                "nor the 16:00 one on the last day");
        assertEquals(LocalDateTime.of(2026, 10, 31, 0, 10),
                HcmCommand.nextShower(LocalDateTime.of(2026, 10, 30, 23, 11), c, shippedHold()), "into the last day");
        assertNull(HcmCommand.nextShower(LocalDateTime.of(2026, 10, 31, 23, 41), c, shippedHold()),
                "the 23:40 on the last day was the last");
    }

    // ---- what an admin is told --------------------------------------------------------------

    @Test
    void theStatusNamesARestartSkipAndTheShowerAfterIt() {
        Candy c = TWENTY;
        LocalDateTime now = LocalDateTime.of(2026, 10, 31, 15, 55);
        assertEquals("Next shower at 4:00 PM (skipped: a restart is due) then at 4:10 PM",
                Text.plain("&7Next shower " + HcmCommand.candyNextText(HcmCommand.CandyState.OPEN, now, c,
                        shippedHold())));
        assertEquals("at 4:10 PM",
                Text.plain(HcmCommand.candyNextText(HcmCommand.CandyState.OPEN, now.plusMinutes(10), c, shippedHold())));
        assertEquals("- none", Text.plain(HcmCommand.candyNextText(HcmCommand.CandyState.OVER, now, c, null)));
        assertEquals("- none", Text.plain(HcmCommand.candyNextText(HcmCommand.CandyState.OFF, now, c, null)));
        assertEquals("on 1 Oct at 12:10 AM", Text.plain(HcmCommand.candyNextText(HcmCommand.CandyState.NOT_YET,
                LocalDateTime.of(2026, 9, 25, 12, 0), c, null)));
    }

    @Test
    void theStatusShowsTheWindowInItsZone() {
        assertEquals("Window: 1 Oct - 31 Oct 2026 (America/Chicago). Every 20 min from 00:10, every 10 min on 31 Oct "
                + "from 00:00.", Text.plain(HcmCommand.candyWindowText(TWENTY, CHICAGO)));
        assertEquals("Window: 1 Oct - 31 Oct 2026 (America/Chicago). Every 60 min from 00:10, every 30 min on 31 Oct "
                + "from 00:10.", Text.plain(HcmCommand.candyWindowText(Candy.DEFAULTS, CHICAGO)),
                "0.38.1's gentler shower");
        Candy plain = new Candy(true, LocalDate.of(2026, 12, 20), LocalDate.of(2027, 1, 6), 30, 5, 0, 6, 4, 5, 10, 31,
                false);
        assertEquals("Window: 20 Dec 2026 - 6 Jan 2027 (UTC). Every 30 min from 00:05.",
                Text.plain(HcmCommand.candyWindowText(plain, ZoneOffset.UTC)),
                "no last-day line when it is as every day; the UTC fallback reads as UTC, not Z");
    }

    @Test
    void eachPlayersLineHasTheCountTheCandyOutIdleMinutesAndTheBag() {
        assertEquals("Ava - found 17/31, 6 out, idle 1 min, bag: not yet",
                Text.plain(HcmCommand.candyPlayerLine("Ava", 17, 31, false, 6, 1)), "§6 Step 2 #5: idle 1 min");
        assertEquals("Ava - found 31/31, 0 out, idle 0 min, bag: owed",
                Text.plain(HcmCommand.candyPlayerLine("Ava", 31, 31, false, 0, 0)));
        assertEquals("Ava - found 35/31, 0 out, idle 7 min, bag: given",
                Text.plain(HcmCommand.candyPlayerLine("Ava", 35, 31, true, 0, 7)));
        assertEquals("Ava - found ?/31, 2 out, idle 0 min, bag: not yet",
                Text.plain(HcmCommand.candyPlayerLine("Ava", -1, 31, false, 2, 0)), "a count that couldn't be read");
    }

    @Test
    void theStatusSaysWhenThePumpkinCapLeavesTheShopWithTheZone() {
        Prize cap = null;
        for (Prize p : Collectors.ROWS) {
            if (p.id().equals("cap_pumpkin_2026")) {
                cap = p;
            }
        }
        assertEquals("Pumpkin Cap on sale until 23:59 on 31 Oct (America/Chicago)",
                HcmCommand.collectorSaleLine(cap.display(), cap.collector(), true, CHICAGO), "red-team m8a");
        assertEquals("Pumpkin Cap on sale until 23:59 on 31 Oct (UTC)",
                HcmCommand.collectorSaleLine(cap.display(), cap.collector(), true, ZoneOffset.UTC),
                "a UTC fallback shows");
        assertEquals("Pumpkin Cap is not on sale (arcade.collectors.on_sale: false)",
                HcmCommand.collectorSaleLine(cap.display(), cap.collector(), false, CHICAGO));
    }

    @Test
    void nowSaysShoweredOrTheReason() {
        assertEquals("Ava - showered", Text.plain(HcmCommand.candyNowLine("Ava", null)));
        assertEquals("Sam - idle 6 min", Text.plain(HcmCommand.candyNowLine("Sam", "idle 6 min")));
    }

    /** {@code /hcm candy add}'s goal check, told to the admin from what the shower's service did. */
    @Test
    void addSaysWhatTheGoalCheckDid() {
        assertNull(HcmCommand.candyAddBagLine("Ava", null), "below the goal: nothing to say");
        assertNull(HcmCommand.candyAddBagLine("Ava", CandyService.BagResult.HAS_IT), "she had it already");
        assertEquals("Ava got their Trick-or-Treat Bag.",
                Text.plain(HcmCommand.candyAddBagLine("Ava", CandyService.BagResult.GIVEN)));
        assertEquals("Their Trick-or-Treat Bag is waiting: Ava's bag is full.",
                Text.plain(HcmCommand.candyAddBagLine("Ava", CandyService.BagResult.FULL)));
        assertEquals("Their Trick-or-Treat Bag is waiting: Ava is in a game or another world.",
                Text.plain(HcmCommand.candyAddBagLine("Ava", CandyService.BagResult.WAITING)));
    }

    // ---- the help, the tab list and plugin.yml ----------------------------------------------

    @Test
    void theHelpShowsAPlayerTheirLineAndAnAdminTheRest() {
        List<String> player = run(PLAYER);
        assertTrue(player.contains("/hcm candy - your candy apples and the next shower"), player.toString());
        assertFalse(String.join("\n", player).contains("candy now"), "add and the rest are not in the player help");
        List<String> admin = run(ADMIN);
        assertTrue(admin.contains("/hcm candy now [player]|status|clear|bag <player>|add <player> <n> - the Candy Apple "
                + "shower"), admin.toString());
        assertTrue(admin.contains("/hcm candy - your candy apples and the next shower"));
    }

    @Test
    void tabOffersCandyToEveryoneAndItsWordsToAdmins() {
        assertTrue(tab(PLAYER, "ca").contains("candy"), "the first word, for a player");
        assertTrue(tab(ADMIN, "ca").contains("candy"), "and for an admin");
        assertEquals(List.of("now", "status", "clear", "bag", "add"), tab(ADMIN, "candy", ""));
        assertEquals(List.of("status"), tab(ADMIN, "candy", "S"));
        assertEquals(List.of(), tab(PLAYER, "candy", ""), "a player has no words to complete");
        String cmd;
        try {
            cmd = Files.readString(Path.of("src", "main", "java", "com", "dierks", "homecraft", "command",
                    "HcmCommand.java"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertTrue(cmd.contains("List.of(\"now\", \"bag\", \"add\").contains(args[1].toLowerCase(Locale.ROOT))"),
                "online names after now, bag and add");
    }

    @Test
    void pluginYmlHasBothUsageLines() throws IOException {
        String yml = Files.readString(Path.of("src", "main", "resources", "plugin.yml"), StandardCharsets.UTF_8);
        assertTrue(yml.contains("\n      /hcm candy             - your candy apples, your Trick-or-Treat Bag and the "
                + "next shower\n"), "the player's line");
        assertTrue(yml.contains("\n      /hcm candy now [player]|status|clear|bag <player>|add <player> <n>  - admin: "
                + "the Candy Apple shower\n"), "the admin line (§3.2 row 11)");
    }
}
