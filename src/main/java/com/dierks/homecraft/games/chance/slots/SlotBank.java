package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.config.GamesConfig;
import com.dierks.homecraft.games.RtpLimits;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.StakeOdds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One solved {@link SlotsEngine} for each stake, for the two machines whose stakes can't share one: Deep Mine (a
 * stake's cap is a third of what that stake's whole spin may pay) and Mob Slots (a stake's target depends on how
 * often that stake's reels land the Creepers). Each engine here takes exactly one stake.
 *
 * @param engines the engine for each stake that landed inside the band, smallest stake first
 * @param dropped the stakes that could not, and why
 * @param first   the engine {@link SlotTable#engine()} answers with: the smallest offered stake's, or with none
 *                offered an engine that takes no stake (so the machine reads as closed)
 */
public record SlotBank(Map<Integer, SlotsEngine> engines, List<Dropped> dropped, SlotsEngine first) {

    /**
     * A stake that is not offered: nothing it can reach is inside the band.
     *
     * @param stake   the stake as configured
     * @param nearest the nearest returns it could reach, for the WARN ("84.2% or 96.1%", or "nothing")
     * @param cap     the most one line (Deep Mine) or one spin (Mob Slots) could pay at that stake
     */
    public record Dropped(int stake, String nearest, int cap) {
    }

    public SlotBank {
        engines = Collections.unmodifiableMap(new LinkedHashMap<>(engines));
        dropped = List.copyOf(dropped);
    }

    /** Collects each stake's engine as it is solved. */
    static final class Builder {

        private final Map<Integer, SlotsEngine> engines = new LinkedHashMap<>();
        private final List<Dropped> dropped = new ArrayList<>();
        private SlotsEngine closed;

        /** A stake solved by {@code engine}: offered when the engine takes it, else dropped. */
        void add(int stake, SlotsEngine engine) {
            if (engine.odds(stake) != null) {
                engines.put(stake, engine);
                return;
            }
            drop(stake, engine.dropped().isEmpty() ? "nothing" : engine.dropped().get(0).nearest(), engine.cap(),
                    engine);
        }

        /** A stake that is not offered ({@code tried}: an engine that takes no stake, for a closed machine). */
        void drop(int stake, String nearest, int cap, SlotsEngine tried) {
            dropped.add(new Dropped(stake, nearest, cap));
            if (closed == null) {
                closed = tried;
            }
        }

        /** The bank; {@code none} is the engine a machine with no stake at all answers with. */
        SlotBank build(SlotsEngine none) {
            SlotsEngine first = !engines.isEmpty() ? engines.values().iterator().next()
                    : closed != null ? closed : none;
            return new SlotBank(engines, dropped, first);
        }
    }

    /** The stakes offered, smallest first. */
    public List<Integer> stakes() {
        return List.copyOf(engines.keySet());
    }

    /** Whether any stake is offered. */
    public boolean playable() {
        return !engines.isEmpty();
    }

    /** The engine for this stake, or {@code null} when it is not offered. */
    public SlotsEngine engine(int stake) {
        return engines.get(stake);
    }

    /** One stake's solved odds, or {@code null} when it is not offered. */
    public StakeOdds odds(int stake) {
        SlotsEngine e = engines.get(stake);
        return e == null ? null : e.odds(stake);
    }

    /**
     * The solve's WARN lines, worded as every slot machine's are: a dropped stake, or no stake left (the machine
     * closes).
     *
     * @param name the machine's name ("Deep Mine doesn't take 5 tokens")
     * @param cap  what the cap in the WARN is: "a line's most" or "a spin's most"
     */
    void report(GamesConfig.Node n, String name, String cap, Map<String, Integer> reels, Map<String, Integer> pays) {
        String band = "85-95 of every 100 tokens";
        if (!playable()) {
            n.invalid(n.path() + " can't give back " + band + " at any stake (" + why(reels, pays) + ")");
            return;
        }
        for (Dropped d : dropped) {
            n.warn(n.key("stakes") + " " + d.stake() + " can't give back " + band + " (nearest " + d.nearest()
                    + " with " + cap + " " + d.cap() + ") - " + name + " doesn't take " + d.stake() + " tokens");
        }
    }

    private String why(Map<String, Integer> reels, Map<String, Integer> pays) {
        if (reels.values().stream().allMatch(w -> w == 0)) {
            return "every reel weight is 0";
        }
        if (pays.values().stream().allMatch(p -> p == 0)) {
            return "no line pays anything";
        }
        StringBuilder sb = new StringBuilder();
        for (Dropped d : dropped) {
            sb.append(sb.length() == 0 ? "" : ", ").append("stake ").append(d.stake()).append(": nearest ")
                    .append(d.nearest());
        }
        return sb.toString();
    }

    /** "84.2%" for a return that is outside the band, for a WARN. */
    static String percent(Frac value) {
        return RtpLimits.tenthPercent(value.value()) + "%";
    }
}
