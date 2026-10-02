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
 *   <li>{@code choose} — {@code seed:algo:until:from:cadence:mix:fallDepth}: an admin's pick for the
 *       next set only ({@code /hcm games gen choose}, a {@link GenScheduler.Choice}: a one-set pin that
 *       keeps its set's length and the settings it was tried at); gone once that set is over, or once
 *       the schedule or those settings move away from it;</li>
 *   <li>{@code dropped} — {@code seed:from:cadence:why}: why that pick was dropped by a config or schedule
 *       change, until its set is over or a new pick is made (round 2);</li>
 *   <li>{@code reroll.<edition>} — how many times an admin rerolled that edition ({@code 7:38});</li>
 *   <li>{@code claim} — {@code world,x,y,z,sx,sy,sz}: the region it may build in ({@link Regions#claim});</li>
 *   <li>{@code old} — the claims of regions it left behind, until they are emptied ({@link #old});
 *       {@code retired} — the last one emptied ({@link #retired});</li>
 *   <li>{@code mix} — {@code plan:mix}: the tier or mix the live layout was made with, written with the
 *       flip, so the boot check derives the same plan even after an admin changed the tier.</li>
 * </ul>
 * And one key for every slot: {@code gen.cadence} — {@code <cadence>|<rebuild day>|<epoch ms>}, the
 * schedule and when the engine first saw it, so a cadence change keeps the current courses until
 * the new schedule's first start even across a restart ({@link GenScheduler#target}); and
 * {@code gen.goals.<week>} — {@code <stars>:<tokens>,...}, a week's Star Chart goals as they were
 * fixed the first time they were asked for that week, so every goal of a week is paid against the
 * same list whatever is switched on or off later ({@link GenService#goals}).
 *
 * <p>The archive's keys (GEN-SPEC-KEEP): {@code gen.<classic slot>.recall} — what an admin recalled
 * into a Classics slot ({@link ClassicWant}); {@code gen.<slot>.codes} — the highest course-code
 * number ever handed out (kept by the archive's flip); {@code gen.keep.plot.<n>} — the course kept
 * in plot n and where it stands ({@link KeptPlot}); {@code gen.keep.pending} — a keep or a
 * clear-plot in flight ({@link KeepService}), so a stop halfway is finished or cleaned at the
 * next start. A free plot has no key: nothing guards it, so every keep scans it again.
 *
 * <p>And {@code gen.layout.guard}: whether this server kept 0.35's spots or took the new layout, as
 * decided once at the update ({@code LayoutGuard.STAMP_KEY}).
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

    /** An admin's pick for the next set ({@code /hcm games gen choose}): a one-set pin. */
    public static String choose(String slot) {
        return "gen." + slot + ".choose";
    }

    /**
     * Why a pick was dropped by a config or schedule change ({@code seed:from:cadence:why}), shown by
     * status and the admin tools until that set is over or a new pick is made (round 2, G2 #3).
     */
    public static String dropped(String slot) {
        return "gen." + slot + ".dropped";
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

    /**
     * A Dropper slot's old regions that may still hold its pools ({@link Regions#wetText}): the
     * claims it had before it moved, guarded until it is claimed there again (and so drained by a
     * clear). Unset when there are none.
     */
    public static String wet(String slot) {
        return "gen." + slot + ".wet";
    }

    /**
     * A slot's old regions ({@link Regions#oldText}): every claim it held before its region changed whose
     * halves may still hold its blocks, each with the world, origin, half size and gap it RECORDED. Guarded
     * until it is emptied (RETIRE: by itself when this version changed the slot's size, or by
     * {@code /hcm games gen tidy}), or claimed there again. It replaces 0.35's {@link #wet}, which is read
     * into it once. Unset when there are none. A claim RETIRE has emptied carries {@code |emptied@<epoch ms>}
     * ({@link Regions#oldEmptied}) and stays, guarded, until the world has been saved twice since or a later
     * start finds it still empty (F09).
     */
    public static String old(String slot) {
        return "gen." + slot + ".old";
    }

    /**
     * F12: what a slot's halves held when its world was last known to be saved, for the claim it names:
     * {@code claim=<claim>;A=<fact>;B=<fact>}, a fact being {@code empty} or {@code plan:<hash>}. A start reads
     * it to skip loading a spare half known to be empty, and to check a big live half by a sample first.
     */
    public static String onDisk(String slot) {
        return "gen." + slot + ".ondisk";
    }

    /**
     * The last time a slot's old region was emptied ({@code OldAreas.Retired#text}): when, which claim, the
     * blocks taken away and any left there that weren't Fresh Courses', for {@code /hcm games check}.
     */
    public static String retired(String slot) {
        return "gen." + slot + ".retired";
    }

    /** What is recalled into a Classics slot ({@link ClassicWant#text()}); unset when it is empty. */
    public static String recall(String classicSlot) {
        return "gen." + classicSlot + ".recall";
    }

    /** Every plot key starts with this. */
    public static final String PLOTS = "gen.keep.plot.";
    /** A keep or clear-plot in flight. */
    public static final String KEEP_PENDING = "gen.keep.pending";

    /** The course kept in plot {@code n} ({@link KeptPlot#text()}). */
    public static String plot(int n) {
        return PLOTS + n;
    }

    /** The plot number of a {@link #plot} key, or -1. */
    public static int plotOf(String key) {
        if (key == null || !key.startsWith(PLOTS)) {
            return -1;
        }
        try {
            return Integer.parseInt(key.substring(PLOTS.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
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
