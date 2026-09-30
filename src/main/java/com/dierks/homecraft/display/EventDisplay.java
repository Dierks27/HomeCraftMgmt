package com.dierks.homecraft.display;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.event.EventBoard;
import com.dierks.homecraft.games.event.EventCopy;
import com.dierks.homecraft.games.event.RaceNight;

import java.time.ZoneOffset;
import java.util.List;

/**
 * Race Night's hub display, {@code @event} (EVENTS-DROPPER-SPEC §A.6): a sign, hologram or TV an
 * admin binds to {@value #TARGET} shows the next night, the join window, the race on now with the
 * standings, or the last results for 30 minutes (the words are {@link EventBoard}'s). It is drawn on
 * the display timer and again within a second of the night changing, never per tick. The
 * displays' one reach into Race Night, and every read runs in its guard: a display can never break
 * the game, and shows "No race set" when the game is closed or can't be read.
 *
 * <p>Also Race Night's leaderboards for {@code @board}: {@code @board:race_night} is this month's
 * season table ("Race Night - October"), {@code @board:race_night:last} the last night's result.
 */
final class EventDisplay {

    /** The display target. */
    static final String TARGET = EventBoard.TARGET;
    /** The {@code @board} sub-board for the last night. */
    static final String LAST = "last";

    private EventDisplay() {
    }

    /** Whether a display's item is Race Night's {@code @event} (any case). */
    static boolean is(String itemId) {
        return itemId != null && itemId.trim().equalsIgnoreCase(TARGET);
    }

    /** Race Night, whatever its state, or {@code null} when the games aren't running. */
    private static RaceNight game(HomeCraftManagement plugin) {
        GamesService games = plugin == null ? null : plugin.games();
        Game g = games == null ? null : games.game(RaceNight.SPEC.id());
        return g instanceof RaceNight r ? r : null;
    }

    /** Why {@code @event} can't be bound now, or {@code null} (it can be bound while Race Night is off). */
    static String problem(HomeCraftManagement plugin) {
        return game(plugin) == null ? "The games aren't running, so there is no Race Night to show." : null;
    }

    /** The night as the display sees it now ("No race set" while closed). */
    private static EventBoard.View view(HomeCraftManagement plugin) {
        RaceNight r = game(plugin);
        GamesService games = plugin == null ? null : plugin.games();
        EventBoard.View nothing = EventBoard.View.nothing(System.currentTimeMillis(),
                games == null ? ZoneOffset.UTC : games.clock().zone());
        if (r == null || !games.enabled(r)) {
            return nothing;
        }
        return games.guard(r, r::board, nothing);
    }

    /** A sign's four lines, coloured like the leaderboards'. */
    static String[] sign(HomeCraftManagement plugin) {
        List<String> l = EventBoard.sign(view(plugin));
        return new String[]{"&1&l" + l.get(0), "&0" + l.get(1), "&0" + l.get(2), "&0" + l.get(3)};
    }

    /** A hologram's or TV's text. */
    static String screen(HomeCraftManagement plugin) {
        return String.join("\n", EventBoard.screen(view(plugin)));
    }

    /** Changes whenever the night does (the displays redraw within a second of a change). */
    static long version(HomeCraftManagement plugin) {
        RaceNight r = game(plugin);
        if (r == null) {
            return -1;
        }
        GamesService games = plugin.games();
        return games.enabled(r) ? games.guard(r, r::version, -1L) : -2;
    }

    /**
     * Race Night's leaderboards for {@code @board:race_night[:last]}: the season table, or the last
     * night; {@code null} when {@code id} isn't Race Night's (the other lookups go on).
     */
    static BoardDisplay.Result board(GamesService games, BoardDisplay.Target t) {
        if (games == null || t == null || !(RaceNight.SPEC.id().equals(t.id()) || "race".equals(t.id()))) {
            return null;
        }
        Game g = games.game(RaceNight.SPEC.id());
        if (!(g instanceof RaceNight r)) {
            return null;
        }
        if (t.board() == null) {
            String season = games.guard(r, r::seasonBoard, null);
            if (season == null) {
                return BoardDisplay.Result.fail("Race Night's season is off (games.race_night.season): use "
                        + "@board:race_night:last for the last night.");
            }
            return BoardDisplay.Result.ok(new BoardDisplay.Resolved(r.id(), season, false, "points",
                    EventCopy.boardLabel(season).replace(" · ", " - "), r.id()));
        }
        if (LAST.equals(t.board())) {
            String last = games.guard(r, r::lastBoard, null);
            return BoardDisplay.Result.ok(new BoardDisplay.Resolved(r.id(), last, false, "points",
                    last == null ? "Race Night - last night" : EventCopy.boardLabel(last).replace(" · ", " - "), r.id()));
        }
        return BoardDisplay.Result.fail("Race Night shows @board:race_night (the season) or @board:race_night:last.");
    }
}
