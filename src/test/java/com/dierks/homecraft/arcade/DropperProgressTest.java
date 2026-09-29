package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.QuestType;
import com.dierks.homecraft.games.GameProgress;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Dropper's clean drop (EVENTS-DROPPER-SPEC §B.1.8, the {@code game_dropper_clean} achievement):
 * a counted Dropper run with no bonks is one {@value GamesProgress#DROPPER_CLEAN}, only where the
 * games count, never for nobody; its finish is a course finish through the trials path as for any
 * trial; and a listener that doesn't hear clean drops hears nothing (the default is a no-op).
 */
class DropperProgressTest {

    private final List<String> events = new ArrayList<>();
    private boolean here = true;

    private final GamesProgress progress = new GamesProgress(new GamesProgress.Sink() {
        @Override
        public boolean countsHere(Player player) {
            return here;
        }

        @Override
        public boolean cabinet(String gameId) {
            return false;
        }

        @Override
        public void quest(Player player, QuestType type, long amount) {
            events.add("quest " + type + " " + amount);
        }

        @Override
        public void count(Player player, String counter, long by) {
            events.add("count " + counter + " " + by);
        }

        @Override
        public boolean firstTime(Player player, String marker) {
            return true;
        }
    });

    private static Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> 7;
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Alex";
                    default -> null;
                });
    }

    @Test
    void aCleanDropIsOneCountOnlyWhereTheGamesCount() {
        Player alex = player();
        progress.dropperClean(alex, "fresh_dropper");
        assertEquals(List.of("count dropper_clean 1"), events, "one clean drop");
        events.clear();
        here = false;
        progress.dropperClean(alex, "fresh_dropper");
        assertEquals(List.of(), events, "creative, or a world without games: nothing");
        here = true;
        progress.dropperClean(null, "fresh_dropper");
        assertEquals(List.of(), events, "nobody is nothing");
        assertTrue(GamesProgress.COUNTERS.contains(GamesProgress.DROPPER_CLEAN), "a counter the achievements read");
        assertEquals("dropper_clean", GamesProgress.DROPPER_CLEAN, "the name config.yml's row reads");
    }

    @Test
    void aListenerThatDoesNotHearCleanDropsHearsNothing() {
        GameProgress quiet = new GameProgress() {
        };
        quiet.dropperClean(player(), "fresh_dropper");
        assertEquals(List.of(), events, "the default is a no-op");
    }
}
