package com.dierks.homecraft.games.event;

import com.dierks.homecraft.storage.EventDao;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A night settled from its stored rows alone (EVENTS-DROPPER-SPEC §A.9): the server stopped (or
 * crashed) during a race or a break, and the next boot calls the night off. Its completed races
 * stand: the points are summed from {@code game_event_races}, the places are ranked by points and
 * countback exactly as a night that ran to the end, and prizes are owed only when the night had
 * claimed one of the week's prize slots ({@code prized}). Nothing here needs the racers online:
 * settling writes the places and prizes, and the pay loop pays whoever can be paid now, the rest at
 * their next join.
 */
public final class StoredNight {

    private StoredNight() {
    }

    /**
     * Each racer's night from the stored races: places, points and prizes, best first. Pure.
     *
     * @param races   every stored race row of the night
     * @param entries the night's entries, in join order (the tie order for display)
     * @param rules   the night's rules as stored with it
     * @param prized  whether it holds a prize slot
     */
    public static List<EventDao.Placed> placed(List<EventDao.RaceRow> races, List<EventDao.EntryRow> entries,
                                               NightRules rules, boolean prized) {
        Night n = night(races, entries);
        Map<UUID, RacePrizes.Prize> prizes = RacePrizes.plan(n.standings(), n.startedRace1().size(), n.finishers(),
                rules.prizes(), rules.finisherPrize(), prized && !races.isEmpty());
        List<EventDao.Placed> out = new ArrayList<>();
        for (NightStandings.Ranked s : n.standings()) {
            RacePrizes.Prize p = prizes.get(s.player());
            out.add(new EventDao.Placed(s.player(), s.place(), s.points(), p == null ? 0 : p.tokens()));
        }
        return out;
    }

    /**
     * Who raced a stored night, and whether they won it, for the achievements (fx2-C #6), in place
     * order: everyone with a race they started (not a DNS), as a night that ran to the end tells it
     * ({@code NightRunner} counts a racer who started a race); won by its 1st with points, a finish
     * and someone below ({@link RacePrizes#won}). Pure.
     */
    public static Map<UUID, Boolean> raced(List<EventDao.RaceRow> races, List<EventDao.EntryRow> entries) {
        Night n = night(races, entries);
        Map<UUID, Boolean> out = new LinkedHashMap<>();
        for (NightStandings.Ranked s : n.standings()) {
            if (n.raced().contains(s.player())) {
                out.put(s.player(), RacePrizes.won(s, n.standings(), n.finishers()));
            }
        }
        return out;
    }

    /** A stored night summed up: the standings, and who started race 1, finished a race, raced at all. */
    private record Night(List<NightStandings.Ranked> standings, Set<UUID> startedRace1, Set<UUID> finishers,
                         Set<UUID> raced) {
    }

    private static Night night(List<EventDao.RaceRow> races, List<EventDao.EntryRow> entries) {
        Map<UUID, Integer> points = new LinkedHashMap<>();
        Map<UUID, List<Integer>> places = new HashMap<>();
        for (EventDao.EntryRow e : entries) {
            points.put(e.player(), 0);
            places.put(e.player(), new ArrayList<>());
        }
        Set<UUID> startedRace1 = new HashSet<>();
        Set<UUID> finishers = new HashSet<>();
        Set<UUID> raced = new HashSet<>();
        for (EventDao.RaceRow r : races) {
            points.merge(r.player(), Math.max(0, r.points()), Integer::sum);
            places.computeIfAbsent(r.player(), k -> new ArrayList<>());
            boolean dns = NightStandings.Result.DNS.name().equals(r.result());
            if (!dns) {
                raced.add(r.player());
            }
            if (r.race() == 1 && !dns) {
                startedRace1.add(r.player());
            }
            if (r.place() != null && NightStandings.Result.FINISHED.name().equals(r.result())) {
                places.get(r.player()).add(r.place());
                finishers.add(r.player());
            }
        }
        Map<UUID, Integer> ranked = new LinkedHashMap<>();
        for (Map.Entry<UUID, Integer> e : points.entrySet()) {
            if (raced.contains(e.getKey()) || e.getValue() > 0) {
                ranked.put(e.getKey(), e.getValue());
            }
        }
        return new Night(NightStandings.rank(ranked, places), startedRace1, finishers, raced);
    }

    /**
     * Settle a stored night that was called off after at least one race: places and prizes in one
     * transaction, then the night is CALLED_OFF with {@code note}. The prizes are paid by the pay
     * loop afterwards (the caller's).
     *
     * @return the lines as stored, best first
     */
    public static List<EventDao.Placed> settle(EventDao dao, EventDao.EventRow row, NightRules rules, String note,
                                               long now) throws SQLException {
        List<EventDao.Placed> placed = placed(dao.races(row.id()), dao.entries(row.id()), rules, row.prized());
        dao.settle(row.id(), placed, EventCopy.nightBoard(row.id()), now);
        dao.setState(row.id(), EventDao.CALLED_OFF, note, now);
        return placed;
    }
}
