package com.dierks.homecraft.command;

import com.dierks.homecraft.games.GamesBench;
import com.dierks.homecraft.games.PlayerAs;
import com.dierks.homecraft.games.chance.wheel.Wheel;
import com.dierks.homecraft.games.chance.wheel.WheelSettings;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TimeTrialsSettings;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of {@code /hcm play|leave|games} that need no server.
 *
 * <p>Pinned here: inside a world game only play, leave, games and help pass (any case); a score
 * reset is a dry run unless it ends in {@code confirm}, {@code all} means every board, and a
 * player may follow the board; admin numbers are whole and in range or refused; and Tab on
 * {@code /hcm play} never offers the games of chance to a player they aren't open to (no
 * {@code hcm.games.chance}, or on a Take a break pause), as the Games screen hides them (the final
 * gate's #3).
 */
class GamesCommandTest {

    @Test
    void onlyPlayLeaveGamesAndHelpWorkInsideAWorldGame() {
        for (String ok : new String[]{"play", "LEAVE", "games", "help"}) {
            assertTrue(GamesCommand.allowedInSession(ok), ok + " is how a player gets out or gets help");
        }
        for (String no : new String[]{"arcade", "market", "museum", "auction", "binder", "tokens", "trail", ""}) {
            assertFalse(GamesCommand.allowedInSession(no), no + " could hand items into the session or move them");
        }
        assertFalse(GamesCommand.allowedInSession(null), "nothing is not a command");
    }

    @Test
    void aScoreResetIsADryRunUnlessItEndsInConfirm() {
        GamesCommand.Reset dry = GamesCommand.Reset.parse(new String[]{"Snake"});
        assertEquals(new GamesCommand.Reset("snake", null, null, false), dry, "every board, everyone, a dry run");
        assertEquals(new GamesCommand.Reset("snake", null, null, true),
                GamesCommand.Reset.parse(new String[]{"snake", "confirm"}), "confirm clears");
        assertEquals(new GamesCommand.Reset("snake", "classic", null, false),
                GamesCommand.Reset.parse(new String[]{"snake", "classic"}), "one board");
        assertEquals(new GamesCommand.Reset("snake", null, "Alex", true),
                GamesCommand.Reset.parse(new String[]{"snake", "all", "Alex", "confirm"}),
                "all = every board, then a player");
        assertEquals(new GamesCommand.Reset("trials", "course:river_run", "Alex", false),
                GamesCommand.Reset.parse(new String[]{"trials", "course:river_run", "Alex"}),
                "a course board and a player, still a dry run");
        assertNull(GamesCommand.Reset.parse(new String[]{}), "a game is needed");
        assertNull(GamesCommand.Reset.parse(new String[]{"confirm"}), "confirm alone names no game");
    }

    @Test
    void newsAndCheckAreWordsOfTheirOwnSoNoGameCanTakeThem() {
        assertTrue(GamesCommand.PLAY_WORDS.contains("news"), "/hcm play news on|off is offered and never opens a game");
        assertTrue(GamesCommand.VERBS.contains("check"), "/hcm games check is offered to admins");
        assertEquals(GamesCommand.VERBS.size(), new java.util.HashSet<>(GamesCommand.VERBS).size(), "no verb twice");
        for (String word : GamesCommand.PLAY_WORDS) {
            assertTrue(com.dierks.homecraft.games.GameCatalog.taken(word), word + " can't become a course's id");
        }
    }

    @Test
    void adminNumbersAreWholeAndInRange() {
        assertEquals(7, GamesCommand.parse("7", 1, 365), "a pause of 7 days");
        assertEquals(-2, GamesCommand.parse("0", 1, 365), "below the range");
        assertEquals(-2, GamesCommand.parse("366", 1, 365), "above it");
        assertEquals(-2, GamesCommand.parse("7.5", 1, 365), "not whole");
        assertEquals(-2, GamesCommand.parse("seven", 1, 365), "not a number");
        assertEquals(0, GamesCommand.parse(" 0 ", 0, 10_000), "a limit of 0 means nothing a day");
    }

    private static List<String> tab(GamesBench bench, CommandSender sender) {
        List<String> out = new ArrayList<>();
        GamesCommand.playIds(out, bench.games(), "", sender);
        return out;
    }

    @Test
    void tabNeverOffersTheGamesOfChanceToAPlayerTheyArentOpenTo() throws Exception {
        GamesBench bench = new GamesBench(GamesBench.at(2026, 9, 29, 12, 0), List.of(Wheel.SPEC, TimeTrials.SPEC),
                "wheel", WheelSettings.defaults(), "trials", TimeTrialsSettings.defaults());
        try {
            Player ava = bench.player("Ava");
            assertTrue(tab(bench, ava).containsAll(List.of("wheel", "trials")), "Ava gets every open game: "
                    + tab(bench, ava));
            Player kim = PlayerAs.limited(bench.player("Kim"), Set.of("hcm.games.chance"), null);
            assertFalse(tab(bench, kim).contains("wheel"),
                    "a parent took games of chance away: Kim doesn't see them at all, not even on Tab (#3)");
            assertTrue(tab(bench, kim).contains("trials"), "the skill games are still offered");
            assertTrue(bench.games().breaks().pause(ava.getUniqueId(), 1), "Ava takes a break");
            assertFalse(tab(bench, ava).contains("wheel"), "on a pause, the games of chance aren't offered either");
            assertTrue(tab(bench, ava).contains("trials"), "skill games are open as usual");
            CommandSender console = (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{CommandSender.class}, (proxy, m, a) -> m.getReturnType() == boolean.class);
            assertTrue(tab(bench, console).contains("wheel"), "the console still gets them");
        } finally {
            bench.close();
        }
    }
}
