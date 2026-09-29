package com.dierks.homecraft.games.gen.engine;

/**
 * The one spelling of every Daily Courses key in {@code hcm_meta} (GEN-SPEC §5.5), all under
 * {@code gen.<slot>.}:
 * <ul>
 *   <li>{@code enabled} — the admin's on/off ({@code true}/{@code false}), over config;</li>
 *   <li>{@code tier} — a tier or golf mix, over config, from the next build;</li>
 *   <li>{@code pin} — {@code seed:algo:until} ({@link GenScheduler.Pin});</li>
 *   <li>{@code reroll.<day>} — how many times an admin rerolled that course day;</li>
 *   <li>{@code claim} — {@code world,x,y,z,sx,sy,sz}: the region it may build in ({@link Regions#claim});</li>
 *   <li>{@code mix} — {@code plan:mix}: the tier or mix the live layout was made with, written with the
 *       flip, so the boot check derives the same plan even after an admin changed the tier.</li>
 * </ul>
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

    public static String reroll(String slot, long day) {
        return "gen." + slot + ".reroll." + day;
    }

    public static String claim(String slot) {
        return "gen." + slot + ".claim";
    }

    public static String mix(String slot) {
        return "gen." + slot + ".mix";
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
