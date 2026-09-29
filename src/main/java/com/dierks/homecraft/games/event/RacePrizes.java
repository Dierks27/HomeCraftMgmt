package com.dierks.homecraft.games.event;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Race Night's token prizes (EVENTS-DROPPER-SPEC §A.3, EVENTS-RECONCILED decision 1). Entry is free
 * and the server pays small fixed prizes, so nobody can lose tokens. Pure: no Bukkit, no database.
 *
 * <p><b>The rules</b> (all configurable, bounded here whatever config says):
 * <ul>
 *   <li>The night's 1st, 2nd and 3rd get {@code prizes} (5, 3, 2). Every other racer who
 *       <b>finished at least one race</b> gets {@code finisher_prize} (1).</li>
 *   <li><b>The podium rule:</b> place k pays its prize only when at least k + 1 racers started race
 *       1, so 2nd needs 3 racers and 3rd needs 4, and nobody wins a podium prize for coming last.
 *       Two racers get 5 and 1; three get 5, 3 and 1.</li>
 *   <li><b>Ties</b> (level on points and countback) share the place: each gets that place's prize.</li>
 *   <li>At most {@value NightRules#MAX_PRIZE_PER_NIGHT} tokens a player a night.</li>
 *   <li>Only on a <b>prize night</b>: one of the week's {@code prize_events_per_week} (3) slots,
 *       claimed at race 1's Go ({@link #slotFree}). A "just for fun" night, or one with no slot
 *       left, pays nothing: "Just for fun tonight - points only".</li>
 * </ul>
 * A racer with no points tonight gets nothing. Prizes are paid as
 * {@code RewardKind.EVENT_PRIZE}, outside the daily skill cap, with the ref {@code event:<id>}, so
 * each racer is paid at most once a night.
 */
public final class RacePrizes {

    /** What a "just for fun" night reads. */
    public static final String JUST_FOR_FUN = "Just for fun tonight - points only";

    private RacePrizes() {
    }

    /**
     * One racer's prize.
     *
     * @param tokens what they win (1 to {@value NightRules#MAX_PRIZE_PER_NIGHT})
     * @param detail the ledger line ("Race Night: 2nd place")
     */
    public record Prize(UUID player, int tokens, String detail) {
    }

    /**
     * Every racer's prize tonight, in standings order; racers who win nothing are left out.
     *
     * @param standings  the night's standings ({@link NightStandings#rank})
     * @param started    how many racers were seated at race 1's Go
     * @param finishers  who finished (counted) at least one race
     * @param prizeNight whether the night holds a prize slot (false: nothing is paid)
     */
    public static Map<UUID, Prize> plan(List<NightStandings.Ranked> standings, int started, Collection<UUID> finishers,
                                        List<Integer> prizes, int finisherPrize, boolean prizeNight) {
        Map<UUID, Prize> out = new LinkedHashMap<>();
        if (!prizeNight || standings == null) {
            return out;
        }
        Set<UUID> finished = finishers == null ? Set.of() : Set.copyOf(finishers);
        for (NightStandings.Ranked r : standings) {
            if (r.points() <= 0) {
                continue;
            }
            int place = r.place();
            int tokens = 0;
            String detail = null;
            if (podium(place, started) && prizes != null && place <= prizes.size() && prizes.get(place - 1) > 0) {
                tokens = prizes.get(place - 1);
                detail = "Race Night: " + NightStandings.ordinal(place) + " place";
            }
            if (tokens == 0 && finished.contains(r.player()) && finisherPrize > 0) {
                tokens = finisherPrize;
                detail = "Race Night: finished a race";
            }
            tokens = Math.min(NightRules.MAX_PRIZE_PER_NIGHT, tokens);
            if (tokens > 0) {
                out.put(r.player(), new Prize(r.player(), tokens, detail));
            }
        }
        return out;
    }

    /** Whether place {@code place} (1-3) may pay a podium prize with {@code started} racers: k needs k + 1. */
    public static boolean podium(int place, int started) {
        return place >= 1 && place <= 3 && started >= place + 1;
    }

    /** Whether a night may claim a prize slot: fewer than {@code perWeek} prize nights so far this week. */
    public static boolean slotFree(int prizedThisWeek, int perWeek) {
        return prizedThisWeek < Math.max(0, perWeek);
    }

    /** The prize line on the screen and at Go: "Prizes: 5, 3, 2 tokens" or {@link #JUST_FOR_FUN}. */
    public static String line(List<Integer> prizes, int finisherPrize, boolean prizeNight) {
        if (!prizeNight || prizes == null || prizes.stream().allMatch(p -> p <= 0)) {
            return JUST_FOR_FUN;
        }
        StringBuilder b = new StringBuilder("Prizes: ");
        for (int i = 0; i < prizes.size(); i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(Math.min(NightRules.MAX_PRIZE_PER_NIGHT, prizes.get(i)));
        }
        b.append(" tokens");
        if (finisherPrize > 0) {
            b.append(", ").append(finisherPrize).append(" for every other finisher");
        }
        return b.toString();
    }
}
