package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.DailyStars;

import java.util.ArrayList;
import java.util.List;

/**
 * The one spelling of every Fresh Courses key in {@code hcm_meta} (GEN-SPEC §5.5), all under
 * {@code gen.<slot>.}:
 * <ul>
 *   <li>{@code enabled} — the admin's on/off ({@code true}/{@code false}), over config;</li>
 *   <li>{@code tier} — a tier or golf mix, over config, from the next build;</li>
 *   <li>{@code pin} — {@code seed:algo:until} ({@link GenScheduler.Pin});</li>
 *   <li>{@code reroll.<edition>} — how many times an admin rerolled that edition ({@code 7:38});</li>
 *   <li>{@code claim} — {@code world,x,y,z,sx,sy,sz}: the region it may build in ({@link Regions#claim});</li>
 *   <li>{@code mix} — {@code plan:mix}: the tier or mix the live layout was made with, written with the
 *       flip, so the boot check derives the same plan even after an admin changed the tier.</li>
 * </ul>
 * And one key for every slot: {@code gen.cadence} — {@code <cadence>|<rebuild day>|<epoch ms>}, the
 * schedule and when the engine first saw it, so a cadence change keeps the current courses until
 * the new schedule's first start even across a restart ({@link GenScheduler#target}); and
 * {@code gen.goals.<week>} — {@code <stars>:<tokens>,...}, a week's Star Chart goals as they were
 * fixed the first time they were asked for that week, so every goal of a week is paid against the
 * same list whatever is switched on or off later ({@link GenService#goals}).
 */
public final class GenAdminKeys {

    private GenAdminKeys() {
    }

    public static String enabled(String slot) {
        return "gen." + slot + ".enabled";
    }

    public static String tier(String slot) {
        return "gen." + slot + ".tier";
    }

    public static String pin(String slot) {
        return "gen." + slot + ".pin";
    }

    /** The reroll count of an edition ({@code edition} without a reroll: {@code 7:38}). */
    public static String reroll(String slot, String edition) {
        return "gen." + slot + ".reroll." + edition;
    }

    /** The schedule and when it was first seen ({@code <cadence>|<rebuild day>|<epoch ms>}). */
    public static String schedule() {
        return "gen.cadence";
    }

    public static String claim(String slot) {
        return "gen." + slot + ".claim";
    }

    public static String mix(String slot) {
        return "gen." + slot + ".mix";
    }

    /** Every week's fixed goals start with this. */
    public static final String GOALS = "gen.goals.";

    /** The Star Chart goals of the week starting on local epoch day {@code week}. */
    public static String goals(long week) {
        return GOALS + week;
    }

    /** The week a {@link #goals(long)} key is for, or {@code null} when it isn't one. */
    public static Long goalsWeek(String key) {
        if (key == null || !key.startsWith(GOALS)) {
            return null;
        }
        try {
            return Long.parseLong(key.substring(GOALS.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Goals as stored: {@code 6:1,9:2} (stars:tokens, smallest first); {@code ""} for a week with none. */
    public static String goalsText(List<DailyStars.Goal> goals) {
        StringBuilder sb = new StringBuilder();
        for (DailyStars.Goal g : goals == null ? List.<DailyStars.Goal>of() : goals) {
            sb.append(sb.isEmpty() ? "" : ",").append(g.stars()).append(':').append(g.tokens());
        }
        return sb.toString();
    }

    /** Stored goals read back, or {@code null} when unset or unreadable (then they are worked out again). */
    public static List<DailyStars.Goal> goalsOf(String v) {
        if (v == null) {
            return null;
        }
        if (v.isBlank()) {
            return List.of();
        }
        List<DailyStars.Goal> out = new ArrayList<>();
        for (String part : v.split(",")) {
            String[] p = part.trim().split(":");
            if (p.length != 2) {
                return null;
            }
            try {
                int stars = Integer.parseInt(p[0].trim());
                int tokens = Integer.parseInt(p[1].trim());
                if (stars <= 0 || tokens < 0) {
                    return null;
                }
                out.add(new DailyStars.Goal(stars, tokens));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return List.copyOf(out);
    }

    /** A stored switch, or {@code null} when unset or unreadable. */
    public static Boolean bool(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.equalsIgnoreCase("true") ? Boolean.TRUE : t.equalsIgnoreCase("false") ? Boolean.FALSE : null;
    }

    /** A stored whole number, or 0 when unset or unreadable. */
    public static int whole(String v) {
        if (v == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
