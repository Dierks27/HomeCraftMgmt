package com.dierks.homecraft.games.clubhouse;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A race or a golf group going on now, as the Clubhouse sees it (CLUBHOUSE-SPEC §10, §11): a read-only
 * copy, made once a second from the party races, Race Night or golf together, that never changes the
 * race. The board shows its rows; a watcher flies in its {@link #area} and sees its
 * {@link #positions} line; only its {@link #racers} can be spectated or cheered.
 *
 * @param key       what it is: {@code night:<id>}, {@code party:<lobby>}, {@code golf:<party>}
 * @param title     the board's title ("Party race: River Run")
 * @param world     its course's world (a watcher is moved only inside their own session's world)
 * @param area      where a watcher may fly
 * @param racers    everyone racing or playing in it
 * @param rows      the live positions for the board (at most eight)
 * @param positions the one-line positions a watcher reads ("1. Sam  2. Ava  3. Lee")
 * @param linked    its players come back to the Clubhouse (the board follows it before others)
 */
public record LiveRace(String key, String title, String world, WatchArea area, Set<UUID> racers, List<String> rows,
                       String positions, boolean linked) {

    public LiveRace {
        racers = Set.copyOf(racers == null ? Set.of() : racers);
        rows = List.copyOf(rows == null ? List.of() : rows);
        positions = positions == null ? "" : positions;
    }

    /** The board's live sheet (redrawn at most once a second). */
    public ClubBoard.Sheet sheet() {
        return new ClubBoard.Sheet(title + " &a(live)", rows.isEmpty() ? List.of("&7Getting ready...") : rows, true, key);
    }

    /** "&amp;e1. &amp;fSam &amp;e2. &amp;fAva ...": the first {@code n} names, for an action bar. */
    public static String positions(List<String> namesInOrder, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < namesInOrder.size() && i < n; i++) {
            b.append(i > 0 ? "  " : "").append("&e").append(i + 1).append(". &f").append(ClubBoard.safe(namesInOrder.get(i)));
        }
        return b.toString();
    }
}
