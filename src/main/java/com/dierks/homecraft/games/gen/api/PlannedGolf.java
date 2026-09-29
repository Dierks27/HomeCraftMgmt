package com.dierks.homecraft.games.gen.api;

import com.dierks.homecraft.games.golf.GolfCourse;

import java.util.ArrayList;
import java.util.List;

/**
 * A planned golf course: the holes Mini Golf runs unchanged, and per hole what the planner proved
 * (GEN-SPEC §4.3).
 *
 * @param course   the holes (tee, sunken cup, par, bounds); its id is the slot
 * @param attempts each hole's winning attempt number ({@code rederive} rebuilds from these)
 * @param witness  each hole's expert line: it holes out in exactly {@code expert} strokes
 * @param expert   each hole's expert strokes E (par is E + 1)
 * @param kid      each hole's worst case K for the sloppy-player policy (at most par + 1)
 */
public record PlannedGolf(GolfCourse course, List<Integer> attempts, List<List<Putt>> witness,
                          List<Integer> expert, List<Integer> kid) implements PlannedCourse {

    public PlannedGolf {
        if (course == null) {
            throw new IllegalArgumentException("a planned golf course needs its holes");
        }
        attempts = List.copyOf(attempts == null ? List.of() : attempts);
        List<List<Putt>> lines = new ArrayList<>();
        if (witness != null) {
            for (List<Putt> line : witness) {
                lines.add(List.copyOf(line == null ? List.of() : line));
            }
        }
        witness = List.copyOf(lines);
        expert = List.copyOf(expert == null ? List.of() : expert);
        kid = List.copyOf(kid == null ? List.of() : kid);
    }
}
