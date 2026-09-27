package com.dierks.homecraft.market.sim;

import java.time.MonthDay;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One calendar season from {@code market.sim.seasons.list} (spec §9.1). The windows and
 * weights are {@link SeasonCalendar}'s job; this is only the row.
 *
 * @param id       lower-case id; the SEASON row's tag is {@code id:year}
 * @param name     player-facing name ({@code Harvest Time})
 * @param from     first local day, inclusive
 * @param to       last local day, inclusive; {@code from > to} wraps the year
 * @param percent  item id (or {@code "*"}) to a WHOLE percent, as written in config
 *                 ({@code -4} = -4%). Order is kept for display. Null keys and null or NaN
 *                 values are dropped.
 * @param headline the season's own news line
 */
public record Season(String id, String name, MonthDay from, MonthDay to,
                     Map<String, Double> percent, String headline) {

    public Season {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        name = name == null ? id : name;
        headline = headline == null ? "" : headline;
        Map<String, Double> copy = new LinkedHashMap<>();
        if (percent != null) {
            for (Map.Entry<String, Double> e : percent.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isNaN()) {
                    copy.put(e.getKey(), e.getValue());
                }
            }
        }
        percent = Collections.unmodifiableMap(copy);
    }
}
