package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.trial.PartyRace;
import com.dierks.homecraft.gui.games.daily.DailyLookup;
import com.dierks.homecraft.util.GameClock;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The family week (0.38.4; the owner: a family scoreboard of the week with a podium ceremony at the week's end):
 * the live side of {@link FamilyWeekRules}.
 *
 * <p><b>The points</b> go on the week's board through one hook, {@link #scored}, from the four once-per-event
 * places (a counted run's progress, a recorded round's progress, a party race's finish, a cheer that was heard),
 * under the daily cap in one transaction ({@code GamesDao.addPointsCapped}). The Fame board's "Family week" page
 * and My stats read them from the boards' cache.
 *
 * <p><b>The ceremony</b>: once a second while the room is open, {@link FamilyWeekRules#turn} says whether the
 * week turned and last week's top three (two of them at least) are in: then the marker
 * ({@link ClubhouseRoom#WEEK_DONE_KEY}) is written FIRST, the three go on the podium ("Photo time!" with "Last
 * week's top three", the firework over 1st: the Clubhouse's own podium), and the room reads "Last week: 1. ...".
 * The marker is kept across restarts, so it runs once a week; a marker that can't be read means no ceremony this
 * run (one WARN), and one that can't be written may let it run again after a restart (the risks say so).
 *
 * <p>The week is the wall boards' week ({@code arcade.quests.week_starts}, local midnight), never the Cup's.
 */
public final class FamilyWeek {

    private final Clubhouse club;
    private String doneKey;
    private boolean read;
    private boolean readFailed;
    private boolean showing;
    private long warnedAt = Long.MIN_VALUE / 2;

    FamilyWeek(Clubhouse club) {
        this.club = club;
    }

    // ---- the hook ----------------------------------------------------------------------------------

    /**
     * {@code points} for {@code player} (nothing while the Clubhouse isn't running, inside its guard): the one
     * hook the games call, from their once-per-event places.
     */
    public static void scored(GamesService games, UUID player, int points) {
        Clubhouse c = Clubhouse.running(games);
        if (c == null || c.familyWeek() == null || player == null || points <= 0) {
            return;
        }
        games.guard(c, () -> c.familyWeek().scored(player, points));
    }

    /** A party race finished: its winner (of two or more) gets the win's points and a win. */
    public static void partyWon(GamesService games, List<PartyRace.Line> lines, int racers) {
        UUID winner = FamilyWeekRules.partyWinner(lines, racers);
        Clubhouse c = winner == null ? null : Clubhouse.running(games);
        if (c == null || c.familyWeek() == null) {
            return;
        }
        games.guard(c, () -> {
            c.familyWeek().scored(winner, FamilyWeekRules.PARTY_WIN);
            c.familyWeek().won(winner);
        });
    }

    /** {@code points} on this week's board for {@code player}, under today's cap; the boards read again soon. */
    void scored(UUID player, int points) {
        if (player == null || points <= 0) {
            return;
        }
        GameClock clock = club.games().clock();
        long week = weekKey();
        try {
            club.games().dao().addPointsCapped(player, club.id(), FamilyWeekRules.boardOf(week),
                    FamilyWeekRules.dayBoard(clock.dayKey()), points, FamilyWeekRules.DAY_CAP, club.now());
        } catch (SQLException e) {
            warn("Clubhouse family week: could not add points: " + e);
            return;
        }
        if (club.boards() != null) {
            club.boards().soon();
        }
    }

    /** A party race win for {@code player} (a count, never capped). */
    void won(UUID player) {
        try {
            club.games().dao().addPoints(player, club.id(), FamilyWeekRules.winsBoard(weekKey()), 1, club.now());
        } catch (SQLException e) {
            warn("Clubhouse family week: could not count a win: " + e);
        }
    }

    /** This week's key: the wall boards' week. */
    long weekKey() {
        return club.games().clock().weekKey(DailyLookup.weekStart(club.plugin()));
    }

    // ---- the ceremony ------------------------------------------------------------------------------

    /** Once a second while the room is open. */
    void second(long now) {
        if (showing && !club.photoPending()) {
            showing = false;
        }
        ClubhouseRoom room = club.room();
        if (room == null || !room.open()) {
            return;
        }
        if (!read) {
            read = true;
            try {
                doneKey = room.meta(ClubhouseRoom.WEEK_DONE_KEY);
            } catch (Exception e) {
                readFailed = true;
                club.log().log(Level.WARNING, "Clubhouse family week: could not read its week marker; no ceremony"
                        + " this run", e);
            }
        }
        if (readFailed) {
            return;
        }
        long week = weekKey();
        if (doneKey != null && doneKey.equals(Long.toString(week))) {
            return; // this week is done: the usual case, nothing read
        }
        FameBoard.Fame fame = club.boards() == null ? null : club.boards().fame();
        if (fame == null || fame.weekKey() != week) {
            return; // the cache isn't this week's yet (it is read while anyone is in)
        }
        List<FameBoard.FamilyRow> top = fame.lastWeek();
        int here = 0;
        for (FameBoard.FamilyRow r : top) {
            if (club.visits().in(r.id())) {
                here++;
            }
        }
        long weekStart = club.games().clock().startOfDay(week);
        switch (FamilyWeekRules.turn(week, doneKey, now, weekStart, top.size(), here, club.closingForRestart(),
                club.photoPending())) {
            case DONE_NOW, EXPIRED -> writeDone(week);
            case CEREMONY -> {
                if (!writeDone(week)) {
                    return; // not marked: not run (it would repeat)
                }
                List<UUID> ids = new ArrayList<>();
                List<String> names = new ArrayList<>();
                for (FameBoard.FamilyRow r : top) {
                    ids.add(r.id());
                    names.add(r.name());
                }
                showing = true;
                club.podium(ids);
                String line = FamilyWeekRules.lastLine(names);
                for (ClubVisits.Visit v : club.visits().all()) {
                    Player p = online(v.id());
                    if (p != null) {
                        p.sendMessage(Text.of(line));
                    }
                }
            }
            case NONE -> {
                // waiting for last week's top three
            }
        }
    }

    private boolean writeDone(long week) {
        String key = Long.toString(week);
        try {
            club.room().meta(ClubhouseRoom.WEEK_DONE_KEY, key);
        } catch (Exception e) {
            warn("Clubhouse family week: could not save its week marker: " + e);
            return false;
        }
        doneKey = key;
        return true;
    }

    /** Whether the Photo up now is the family week's (its small line says so). */
    boolean showing() {
        return showing;
    }

    private void warn(String line) {
        long now = club.now();
        if (now - warnedAt >= ClubReads.WARN_EVERY_MS) {
            warnedAt = now;
            club.log().warning(line);
        }
    }

    private static Player online(UUID id) {
        try {
            return id == null ? null : Bukkit.getPlayer(id);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
