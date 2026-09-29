package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.arena.rules.RoundEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Falling Floors' own words (EVENTS-DROPPER-SPEC §B.3.1, §B.4): the tile's NAME carries how many are
 * playing and that you can join; the kit's names say what each item does; the week's shapes read
 * well; and no line has an emoji, a glyph above U+FFFF, a casino or pressure word, or anything about
 * TNT or explosions.
 */
class FloorsTextTest {

    @Test
    void theTilesNameCarriesTheKeyFact() {
        assertEquals("&eFalling Floors &7- 2 playing · join!", FloorsText.tile(2, true), "the spec's tile");
        assertEquals("&eFalling Floors &7- play solo or together", FloorsText.tile(0, true), "nobody in yet");
        assertEquals("&7Falling Floors &7- opening soon", FloorsText.tile(3, false), "not open");
    }

    @Test
    void theKitsNamesSayWhatEachItemDoes() {
        assertTrue(FloorsText.KIT_READY.startsWith("&aReady"), "Ready is green and says Ready");
        assertTrue(FloorsText.KIT_READY_ON.contains("you're ready"), "pressed, it says so");
        assertTrue(FloorsText.KIT_SOLO.startsWith("&ePlay solo"), "Play solo");
        assertEquals("&cLeave game", FloorsText.KIT_LEAVE, "the way out, as in every world game");
    }

    /** F review #9: the Ready item says how many start a round, from min_players, not a fixed "two". */
    @Test
    void theReadyItemSaysHowManyStartARound() {
        assertEquals(List.of("&7A round starts when 2 are ready,", "&7or 20 seconds after a friend comes."),
                FloorsText.readyLore(2), "the shipped min_players");
        assertTrue(FloorsText.readyLore(3).get(0).contains("when 3 are ready"), "min_players 3 says 3");
    }

    /** F review #9: an edit in the box names this arena and its own command, not Fresh Courses'. */
    @Test
    void anEditInTheBoxNamesTheArenasOwnCommand() {
        assertTrue(FloorsText.GUARDED.contains("Falling Floors") && FloorsText.GUARDED.contains("/hcm games floors"),
                FloorsText.GUARDED);
        assertFalse(FloorsText.GUARDED.contains("Fresh Courses") || FloorsText.GUARDED.contains("games gen"),
                "not Fresh Courses' words: " + FloorsText.GUARDED);
    }

    @Test
    void theLinesAroundARound() {
        assertEquals("&7This week's floors: &fRing with an island, disc and plus&7.",
                FloorsText.thisWeek(ArenaPlanner.shapesText(List.of("ring", "disc", "plus"))), "the week's shapes");
        assertNull(FloorsText.thisWeek(""), "none: nothing to say");
        assertTrue(FloorsText.lobbyHint(true).contains("Play solo"), "solo offered");
        assertFalse(FloorsText.lobbyHint(false).contains("solo"), "solo not offered");
        assertTrue(FloorsText.countdownStopped(RoundEvent.Why.HOLD, "4:00 PM").contains("4:00 PM"), "the hold's time");
        assertNull(FloorsText.countdownStopped(RoundEvent.Why.CLOSED, null), "closing says its own line");
        assertEquals("&eRound 3: 4 players - last one standing wins!", FloorsText.roundStarting(3, 4, false));
        assertEquals("&e1:05 &7- 3 still in", FloorsText.playing(1300, 3, false), "the action bar");
    }

    @Test
    void noLineHasAnEmojiACasinoWordOrAnExplosion() throws Exception {
        List<String> lines = new ArrayList<>();
        for (Field f : FloorsText.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                lines.add((String) f.get(null));
            }
        }
        lines.add(FloorsText.tile(5, true));
        lines.add(FloorsText.tile(0, false));
        lines.add(FloorsText.lobbyHint(true));
        lines.add(FloorsText.playing(20, 1, true));
        lines.add(FloorsText.watching(4));
        lines.add(FloorsText.roundStarting(1, 1, true));
        for (RoundEvent.Why why : RoundEvent.Why.values()) {
            String s = FloorsText.countdownStopped(why, "4:00 PM");
            if (s != null) {
                lines.add(s);
            }
        }
        lines.add(LiveArenaHost.MOVED);
        lines.add(FloorsRewards.bestLine(42_000, -1L));
        lines.add(FloorsRewards.bestLine(65_000, 42_000L));
        lines.addAll(ArenaAdmin.HELP);
        lines.addAll(FloorsText.readyLore(3));
        lines.add(ArenaAdmin.TP_NOT_BUILT);
        for (String line : lines) {
            assertTrue(line.codePoints().allMatch(c -> c <= 0xFFFF), "nothing above U+FFFF: " + line);
            String lower = line.toLowerCase(Locale.ROOT);
            for (String banned : List.of("bet ", "wager", "gambl", "casino", "lucky", "almost", "so close", "sink",
                    "tnt", "explo", "boom")) {
                assertFalse(lower.contains(banned), "\"" + banned + "\" is never said: " + line);
            }
        }
    }
}
