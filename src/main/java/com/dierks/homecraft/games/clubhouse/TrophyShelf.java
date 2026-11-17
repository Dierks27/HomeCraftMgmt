package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.trial.TrialText;
import com.dierks.homecraft.pack.ClubModels;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What stands in the Trophy Room (0.41.0), from the same feed as the trophy case ({@link TrophyWall}): the Fame
 * board's all-time records and last week's top three. No new scoring: every cup is a row the boards already show.
 *
 * <ul>
 *   <li>Pedestals 0-2, the podium: last week's family top three, a gold, a silver and a bronze cup (while the family
 *       week is on and last week had points).</li>
 *   <li>Pedestals 3 on, the record pedestals: each course's all-time record, then each golf course's, a gold cup each
 *       (a record is a 1st).</li>
 * </ul>
 * Each cup has its holder's head beside it (the way a four-year-old knows whose it is) and, for adults, a short label.
 * Pure: the snapshot in, the cups out.
 */
final class TrophyShelf {

    /** The podium's pedestals, first in the room's list ({@link ClubhousePlanner#pedestals}). */
    static final int PODIUM = 3;

    /**
     * One cup on one pedestal.
     *
     * @param slot   the pedestal (0-2 the podium, then the records)
     * @param place  1, 2 or 3: the cup's metal (gold, silver, bronze)
     * @param holder the holder's name as the boards show it
     * @param id     whose head stands beside it, or {@code null} when it isn't known (no head)
     * @param label  the adults' label: what, the score, who
     */
    record Cup(int slot, int place, String holder, UUID id, String label) {

        /** The cup's model. */
        ClubModels.Model model() {
            return ClubModels.trophy(place);
        }
    }

    private TrophyShelf() {
    }

    /** The cups for {@code f} on {@code pedestals} pedestals; none for no snapshot yet. */
    static List<Cup> cups(FameBoard.Fame f, int pedestals) {
        List<Cup> out = new ArrayList<>();
        if (f == null) {
            return out;
        }
        if (f.weekKey() >= 0) {
            List<FameBoard.FamilyRow> top = f.lastWeek();
            for (int n = 0; n < top.size() && n < PODIUM && n < pedestals; n++) {
                FameBoard.FamilyRow r = top.get(n);
                if (r.points() <= 0) {
                    continue;
                }
                String name = ClubBoardText.name(r.name());
                out.add(new Cup(n, n + 1, name, r.id(), "&6Last week #" + (n + 1) + "\n&f" + r.points() + " points\n&7"
                        + name));
            }
        }
        int slot = PODIUM;
        for (FameBoard.CourseTime t : f.records()) {
            if (slot >= pedestals) {
                return out;
            }
            String name = ClubBoardText.name(t.name());
            out.add(new Cup(slot++, 1, name, t.id(), "&6" + ClubBoardText.course(t.course()) + "\n&f"
                    + TrialText.time(t.ms()) + "\n&7" + name));
        }
        for (FameBoard.GolfRound r : f.golfRecords()) {
            if (slot >= pedestals) {
                return out;
            }
            String name = ClubBoardText.name(r.name());
            out.add(new Cup(slot++, 1, name, r.id(), "&6" + ClubBoardText.course(r.course()) + "\n&f" + r.strokes()
                    + " (" + ClubBoardText.vs(r.vsPar()) + ")\n&7" + name));
        }
        return out;
    }

    /** The pedestals whose cup is {@code who}'s: where the shimmer goes when they walk in. */
    static List<Integer> mine(List<Cup> cups, UUID who) {
        List<Integer> out = new ArrayList<>();
        if (who == null) {
            return out;
        }
        for (Cup c : cups) {
            if (who.equals(c.id())) {
                out.add(c.slot());
            }
        }
        return out;
    }

    /**
     * The pedestals that just got a new holder (a new record, a new week): what bursts with "a trophy!" when it is
     * drawn. Never on the first look ({@code before} empty: the room just opened), nor for a cup that went.
     */
    static List<Integer> won(List<Cup> before, List<Cup> now) {
        List<Integer> out = new ArrayList<>();
        if (before == null || before.isEmpty()) {
            return out;
        }
        for (Cup c : now) {
            Cup was = null;
            for (Cup b : before) {
                if (b.slot() == c.slot()) {
                    was = b;
                }
            }
            if (c.id() != null && (was == null || !c.id().equals(was.id()))) {
                out.add(c.slot());
            }
        }
        return out;
    }
}
