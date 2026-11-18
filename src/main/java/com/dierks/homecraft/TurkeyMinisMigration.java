package com.dierks.homecraft;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Config revision 29 (0.44.0): Thanksgiving's two Minis, and Catch the Turkey as a special event. The owner: "This
 * should be a different mini ... The food mini should be a cooked turkey ... The caught Turkey should be a Turkey
 * itself", "we only want the Turkey run to be a special event so not every single time someone logs in".
 * <ul>
 *   <li>{@code minis.series}: the <b>Cooked Turkey 2026</b> ({@value #COOKED_ID}), the Family Feast's Mini, goes in
 *       right after the Turkey Mini 2026 (in whichever series holds it; else in the Holiday Collection; else a new
 *       Holiday Collection), only while no Mini has its id.</li>
 *   <li>The <b>Turkey Mini 2026</b> (Catch the Turkey's from now on) gets the turkey's head, only while its texture
 *       is still the one revision 26 shipped: an owner's own texture stays.</li>
 *   <li>{@code arcade.events.turkey.welcome}: true becomes false (the owner asked for no turkey at every login).</li>
 * </ul>
 * The new {@code random_times} key is the backfill's. The rows are frozen literals; a second run changes nothing.
 */
final class TurkeyMinisMigration {

    /** The config revision this step brings: {@code HomeCraftManagement.CONFIG_REVISION} 29. */
    static final int REVISION = 29;

    static final String SERIES = ThanksgivingMigration.SERIES;
    static final String WELCOME = "arcade.events.turkey.welcome";
    static final String COOKED_ID = "cooked_turkey_2026";

    /** The turkey's head (the owner's), for the Turkey Mini 2026. */
    static final String TURKEY_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3"
            + "RleHR1cmUvYjRlM2IyNzZmZWI1NWI2MmU2MzNhZTZlNDE0ZTVlMjk3NTRjOWNkZGQ4YWU5MjVmYzM3ZWUyNTRjODRiNTQxIn19fQ==";
    /** The cooked turkey's head (the owner's), for the Feast's Mini. */
    static final String COOKED_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3"
            + "RleHR1cmUvYzJhM2NhODkyYzZmZTM0MWZlMWFlMDNhZjZlM2FlMDMzMDkwNGUyM2M0NTIzMzNjNTdiMDhmZWI2ZmZkZWNlNyJ9fX0=";

    /** The Cooked Turkey 2026, as 0.44.0 ships it: earned only at the Family Feast 2026. */
    static final Map<String, Object> COOKED_TURKEY = row("name", "Cooked Turkey 2026", "id", COOKED_ID,
            "category", "HOLIDAY", "rarity", "EPIC", "cap", 20, "event_only", true, "earn_from", "2026-11-22",
            "earn_to", "2026-11-29", "texture", COOKED_TEXTURE);

    /** What the steps say. */
    static final String RETEXTURED = "Config migration: Minis - the Turkey Mini 2026 (Catch the Turkey's) is a turkey"
            + " now.";
    static final String ADDED = "Config migration: Minis - added the Cooked Turkey 2026, the Family Feast's Mini"
            + " (earned only, 22-29 Nov 2026).";

    /** The Turkey Mini 2026 as 0.44.0 ships it: revision 26's row with the turkey's head. */
    static final Map<String, Object> TURKEY_MINI = retextured(ThanksgivingMigration.TURKEY_MINI);

    private TurkeyMinisMigration() {
    }

    /** Run revision 29's steps against {@code c} (defaults-free), appending what they did to {@code log}. */
    static void apply(FileConfiguration c, List<String> log) {
        minis(c, log);
        if (c.isBoolean(WELCOME) && c.getBoolean(WELCOME)) {
            c.set(WELCOME, false);
            log.add("Config migration: Catch the Turkey - no welcome turkey at each first join of the day any more"
                    + " (" + WELCOME + ": false); its six runs a day now come at random times (random_times).");
        }
    }

    private static void minis(FileConfiguration c, List<String> log) {
        if (!(c.get(SERIES, null) instanceof List<?> raw)) {
            return; // absent: the backfill writes the whole bundled list
        }
        List<Object> series = new ArrayList<>(raw);
        int turkeySeries = -1;
        int turkeyAt = -1;
        int holiday = -1;
        boolean cooked = false;
        for (int i = 0; i < series.size(); i++) {
            Map<String, Object> s = asRow(series.get(i));
            if (s == null) {
                continue;
            }
            if (holiday < 0 && s.get("name") != null
                    && ThanksgivingMigration.HOLIDAY_SERIES.equalsIgnoreCase(String.valueOf(s.get("name")).trim())) {
                holiday = i;
            }
            if (s.get("entries") instanceof List<?> entries) {
                for (int j = 0; j < entries.size(); j++) {
                    Map<String, Object> e = asRow(entries.get(j));
                    if (e == null) {
                        continue;
                    }
                    String id = ThanksgivingMigration.miniId(e);
                    if (COOKED_ID.equals(id)) {
                        cooked = true;
                    } else if (ThanksgivingMigration.TURKEY_MINI_ID.equals(id) && turkeySeries < 0) {
                        turkeySeries = i;
                        turkeyAt = j;
                    }
                }
            }
        }
        boolean changed = false;
        if (turkeySeries >= 0) {
            Map<String, Object> s = new LinkedHashMap<>(asRow(series.get(turkeySeries)));
            List<Object> entries = new ArrayList<>((List<?>) s.get("entries"));
            Map<String, Object> turkey = new LinkedHashMap<>(asRow(entries.get(turkeyAt)));
            Object texture = turkey.get("texture");
            if (texture != null && String.valueOf(texture).equals(ThanksgivingMigration.TURKEY_MINI.get("texture"))) {
                turkey.put("texture", TURKEY_TEXTURE);
                entries.set(turkeyAt, turkey);
                changed = true;
                log.add(RETEXTURED);
            }
            if (!cooked) {
                entries.add(turkeyAt + 1, copy(COOKED_TURKEY));
                changed = true;
                log.add(ADDED);
            }
            if (changed) {
                s.put("entries", entries);
                series.set(turkeySeries, s);
            }
        } else if (!cooked) {
            if (holiday >= 0) {
                Map<String, Object> s = new LinkedHashMap<>(asRow(series.get(holiday)));
                List<Object> entries = s.get("entries") instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
                entries.add(copy(COOKED_TURKEY));
                s.put("entries", entries);
                series.set(holiday, s);
            } else {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("name", ThanksgivingMigration.HOLIDAY_SERIES);
                s.put("rarity", "EPIC");
                s.put("entries", new ArrayList<>(List.of(copy(COOKED_TURKEY))));
                series.add(s);
            }
            changed = true;
            log.add(ADDED);
        }
        if (changed) {
            c.set(SERIES, series);
        }
    }

    /** {@code mini} with the turkey's head. */
    static Map<String, Object> retextured(Map<String, Object> mini) {
        Map<String, Object> m = new LinkedHashMap<>(mini);
        m.put("texture", TURKEY_TEXTURE);
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Object> copy(Map<String, Object> row) {
        return new LinkedHashMap<>(row);
    }

    private static Map<String, Object> asRow(Object o) {
        if (o instanceof ConfigurationSection s) {
            return new LinkedHashMap<>(s.getValues(false));
        }
        List<Map<String, Object>> rows = ArcadeConfigMigration.mapRows(Collections.singletonList(o));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }
}
