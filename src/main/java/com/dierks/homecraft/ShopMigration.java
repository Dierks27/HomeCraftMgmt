package com.dierks.homecraft;

import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.dierks.homecraft.HomeCraftManagement.WARN;

/**
 * Config revision 22: the token shop (SHOP-V2-SPEC §7.2-7.5). Trails last a month for the same 100, the six
 * animal hats move to the new Looks tab for 90 days, the Arcade Trophy's price rises with each one, 32 rows
 * join the counter (two boosts, Make it Day and Sunny Day, five trails, Party Time, the Creeper and Dragon
 * Hats, four caps, ten golf balls, the trophy ladder and the Trophy of the Month), and the Arcade Crate stops
 * holding hats (hats are bought, never won).
 *
 * <p><b>The rule</b> is every revision's ({@link EconomyMigration}): a row absent from the file is left to the
 * backfill (or revision 14, which writes the bundled list); a row still exactly as 0.35.0-0.37.2 shipped it
 * (the counter's rows were byte for byte the same in all of them) takes the new form; a row already at the new
 * form stays; anything else is the owner's and stays, with one {@link HomeCraftManagement#WARN} naming it and
 * the new row as a line to paste. Rows compare field by field ({@link ArcadeConfigMigration#sameRows}). One
 * exception: a hat the owner only gave a picture (its {@code texture} and/or {@code icon.texture}) moves too,
 * keeping their picture: pasting a texture switched our hat on, it wasn't a choice to keep hats for good.
 *
 * <p><b>New rows are appended whatever the owner did with the shipped ones</b> (red team D-m14): the counter
 * groups rows by tab, so where a row sits never matters, and a deleted or reordered shipped row (the disabled
 * name colours tidied away) is no reason to hold back new ones. A deleted shipped row is not put back. A new
 * id the owner already uses for a row of their own keeps their row, with a WARN and the shipped line. When
 * every new id is already there (a server older than revision 14 got them all from its bundled list), this
 * step says nothing.
 *
 * <p><b>The tables are data</b>, frozen as literals ({@link #CHANGED_22}, {@link #ADDED_22}, {@link #CRATE_OLD},
 * {@link #CRATE_NEW}) and never read from the bundled file, so a later release that edits a row can't change
 * what revision 22 did. {@code ShopMigrationTest} pins every old form to 0.35.0's, 0.36.0's and 0.37.2's
 * config.yml and every new one to this release's.
 *
 * <p>The comments it makes stale ({@code arcade.prizes}, {@code arcade.icons}, {@code arcade.collectors}) are
 * {@link ShippedComments}' revision-22 rows. Once the database is open, {@link #legacyHatsLine} reports any hat
 * sold before 0.38 (D34: they stay as they are, kept for good).
 */
final class ShopMigration {

    /** The config revision this step brings: {@code HomeCraftManagement.CONFIG_REVISION} 22. */
    static final int REVISION = 22;

    /** The list the rows live in. */
    static final String PRIZES = "arcade.prizes";
    /** The Arcade Crate's rewards, compared whole. */
    static final String CRATE = "arcade.crates.arcade_crate.rewards";

    /**
     * One row revision 22 changes in place.
     *
     * @param old what 0.35.0, 0.36.0 and 0.37.2 shipped (identical in all three)
     * @param now what 0.38 ships
     */
    record Change(Map<String, Object> old, Map<String, Object> now) {

        String id() {
            return idOf(now);
        }
    }

    /** The six animal hats, whose picture the owner may have pasted ({@code texture}, {@code icon.texture}). */
    static final List<String> HATS = List.of("hat_bunny", "hat_panda", "hat_fox", "hat_penguin", "hat_frog",
            "hat_cake");

    /** The 10 rows revision 22 changes in place, in file order (§7.3). */
    static final List<Change> CHANGED_22 = List.of(
            trail("trail_hearts", "&cHeart Trail", "Hearts follow you", "RED_DYE", "HEART"),
            trail("trail_notes", "&aMusic Trail", "Music notes follow you", "NOTE_BLOCK", "NOTE"),
            trail("trail_sparkles", "&eSparkle Trail", "Sparkles follow you", "AMETHYST_SHARD", "FIREWORK"),
            hat("hat_bunny", "&fBunny Hat"),
            hat("hat_panda", "&fPanda Hat"),
            hat("hat_fox", "&6Fox Hat"),
            hat("hat_penguin", "&fPenguin Hat"),
            hat("hat_frog", "&aFrog Hat"),
            hat("hat_cake", "&dCake Hat"),
            new Change(
                    row("id", "arcade_trophy", "tab", "TROPHIES", "display", "&6Arcade Trophy",
                            "description", List.of("A numbered trophy", "with your name on it."), "cost_tokens", 750,
                            "icon", icon("GOLD_BLOCK"), "type", "trophy", "texture", ""),
                    row("id", "arcade_trophy", "tab", "TROPHIES", "display", "&6Arcade Trophy",
                            "description", List.of("A numbered trophy", "with your name on it.",
                                    "Each one costs a bit more."), "costs", List.of(750, 1000, 1250, 1500),
                            "icon", icon("GOLD_BLOCK"), "type", "trophy", "texture", "",
                            "limit", limit("LIFETIME", 99))));

    /** The 32 rows revision 22 appends, in the order they ship at the end of {@code arcade.prizes} (§7.4). */
    static final List<Map<String, Object>> ADDED_22 = List.of(
            // little treats
            row("id", "jump_boost", "tab", "BOOSTS", "display", "&aBouncy Boots",
                    "description", List.of("Jump extra high", "for 10 minutes!"), "cost_tokens", 6,
                    "icon", icon("SLIME_BALL"), "type", "boost", "effect", "jump_boost", "amplifier", 1, "minutes", 10,
                    "limit", limit("DAY", 5)),
            row("id", "feather_fall", "tab", "BOOSTS", "display", "&fFloaty Feather",
                    "description", List.of("Float down gently", "for 10 minutes."), "cost_tokens", 6,
                    "icon", icon("FEATHER"), "type", "boost", "effect", "slow_falling", "amplifier", 0, "minutes", 10,
                    "limit", limit("DAY", 5)),
            row("id", "make_it_day", "tab", "BOOSTS", "display", "&eMake it Day!",
                    "description", List.of("Night turns into day", "in the world you're in."), "cost_tokens", 20,
                    "icon", icon("CLOCK"), "type", "command",
                    "commands", List.of("execute as %uuid% at @s run time set day"), "only_if", "night",
                    "limit", limit("DAY", 1)),
            row("id", "sunny_day", "tab", "BOOSTS", "display", "&bSunny Day",
                    "description", List.of("The rain stops for 10 minutes", "in the world you're in."),
                    "cost_tokens", 20, "icon", icon("SUNFLOWER"), "type", "command",
                    "commands", List.of("execute as %uuid% at @s run weather clear 12000"), "only_if", "rain",
                    "limit", limit("DAY", 1)),
            // trails (100 for a month) and Party Time
            newTrail("trail_snow", "&fSnow Trail", "Snowflakes follow you", "SNOWBALL", "SNOWFLAKE"),
            newTrail("trail_stars", "&eStar Trail", "Little stars follow you", "END_ROD", "END_ROD"),
            newTrail("trail_clover", "&aClover Trail", "Green sparkles follow you", "EMERALD", "HAPPY_VILLAGER"),
            newTrail("trail_glow", "&bGlow Trail", "Glowing dots follow you", "GLOW_INK_SAC", "GLOW"),
            newTrail("trail_magic", "&dMagic Trail", "Magic letters follow you", "ENCHANTED_BOOK", "ENCHANT"),
            row("id", "party_time", "tab", "COSMETICS", "display", "&dParty Time!",
                    "description", List.of("Fireworks around everyone", "who's playing right now!"),
                    "cost_tokens", 300, "icon", icon("CAKE"), "type", "party", "seconds", 10,
                    "limit", limit("DAY", 1)),
            // looks: hats and caps for 90 days; golf balls are yours for good
            row("id", "hat_creeper", "tab", "LOOKS", "display", "&aCreeper Hat",
                    "description", List.of("Wear it on your head!", "Yours for 90 days."), "cost_tokens", 120,
                    "icon", icon("CREEPER_HEAD"), "type", "hat", "material", "CREEPER_HEAD", "days", 90),
            row("id", "hat_dragon", "tab", "LOOKS", "display", "&5Dragon Hat",
                    "description", List.of("Wear it on your head!", "Yours for 90 days."), "cost_tokens", 150,
                    "icon", icon("DRAGON_HEAD"), "type", "hat", "material", "DRAGON_HEAD", "days", 90),
            cap("cap_pink", "&dPink Cap", "PINK"),
            cap("cap_purple", "&5Purple Cap", "PURPLE"),
            cap("cap_lime", "&aLime Cap", "LIME"),
            cap("cap_sky", "&bSky Cap", "LIGHT_BLUE"),
            ball("ball_pink", "&dPink Golf Ball", "pink", 40, "PINK_CONCRETE"),
            ball("ball_purple", "&5Purple Golf Ball", "purple", 40, "PURPLE_CONCRETE"),
            ball("ball_lime", "&aLime Golf Ball", "lime", 40, "LIME_CONCRETE"),
            ball("ball_sky", "&bSky Golf Ball", "sky-blue", 40, "LIGHT_BLUE_CONCRETE"),
            ball("ball_orange", "&6Orange Golf Ball", "an orange", 40, "ORANGE_CONCRETE"),
            ball("ball_red", "&cRed Golf Ball", "red", 40, "RED_CONCRETE"),
            ball("ball_yellow", "&eYellow Golf Ball", "yellow", 40, "YELLOW_CONCRETE"),
            ball("ball_black", "&8Black Golf Ball", "black", 40, "BLACK_CONCRETE"),
            ball("ball_gold", "&6Gold Golf Ball", "gold", 250, "GOLD_BLOCK"),
            ball("ball_diamond", "&bDiamond Golf Ball", "diamond", 600, "DIAMOND_BLOCK"),
            // the trophy ladder and the Trophy of the Month, yours for good
            trophy("trophy_bronze", "&6Bronze Trophy", List.of("A numbered trophy", "with your name on it."), 300,
                    "COPPER_BLOCK", null, "LIFETIME"),
            trophy("trophy_silver", "&fSilver Trophy", List.of("A numbered trophy", "with your name on it."), 1000,
                    "IRON_BLOCK", "trophy_bronze", "LIFETIME"),
            trophy("trophy_gold", "&eGold Trophy", List.of("A numbered trophy", "with your name on it."), 2000,
                    "GOLD_BLOCK", "trophy_silver", "LIFETIME"),
            trophy("trophy_diamond", "&bDiamond Trophy", List.of("A numbered trophy", "with your name on it."), 3500,
                    "DIAMOND_BLOCK", "trophy_gold", "LIFETIME"),
            trophy("trophy_legend", "&5Legend Trophy", List.of("The top of the ladder!"), 5000,
                    "NETHERITE_BLOCK", "trophy_diamond", "LIFETIME"),
            trophy("trophy_month", "&eTrophy of the Month",
                    List.of("A new trophy every month,", "named for the month."), 1000, "CLOCK", null, "MONTH"));

    /** The Arcade Crate's rewards as 0.35.0-0.37.2 shipped them. */
    static final List<Map<String, Object>> CRATE_OLD = List.of(
            row("type", "prize", "prize", List.of("speed_boost", "mining_boost", "night_vision", "lucky_fishing"),
                    "weight", 40),
            row("type", "prize", "prize", "firework_show", "weight", 15),
            row("type", "prize", "prize", "mini_radar", "weight", 12),
            row("type", "filament", "amount", 8, "weight", 10),
            row("type", "tokens", "amount", 8, "weight", 10),
            row("type", "trail", "days", 1, "weight", 8),
            row("type", "prize", "prize", List.of("hat_bunny", "hat_panda", "hat_fox", "hat_penguin", "hat_frog",
                    "hat_cake"), "weight", 3),
            row("type", "card", "tag", "*", "weight", 2));

    /** The Arcade Crate's rewards in 0.38: no hats (they are bought, never won), their weight to the fireworks. */
    static final List<Map<String, Object>> CRATE_NEW = List.of(
            row("type", "prize", "prize", List.of("speed_boost", "mining_boost", "night_vision", "lucky_fishing"),
                    "weight", 40),
            row("type", "prize", "prize", "firework_show", "weight", 18),
            row("type", "prize", "prize", "mini_radar", "weight", 12),
            row("type", "filament", "amount", 8, "weight", 10),
            row("type", "tokens", "amount", 8, "weight", 10),
            row("type", "trail", "days", 1, "weight", 8),
            row("type", "card", "tag", "*", "weight", 2));

    private ShopMigration() {
    }

    /** Run revision 22's three row steps against {@code c} (defaults-free), appending what they did to {@code log}. */
    static void apply(FileConfiguration c, List<String> log) {
        prizes(c, log);
        crate(c, log);
    }

    // ---- steps 1 and 2: the Prize Counter's rows -----------------------------------------------

    /**
     * The changed rows in place, then the new rows at the end; one INFO for the list, a WARN for each row of the
     * owner's that is kept, and an INFO for each hat that moves with the owner's picture.
     */
    private static void prizes(FileConfiguration c, List<String> log) {
        if (!(c.get(PRIZES, null) instanceof List<?> raw)) {
            return; // absent: the backfill writes the whole bundled list
        }
        Map<String, Change> changes = new HashMap<>();
        for (Change ch : CHANGED_22) {
            changes.put(ch.id(), ch);
        }
        List<Object> out = new ArrayList<>(raw);
        Set<String> present = new HashSet<>();
        Map<String, Map<String, Object>> rowsById = new HashMap<>();
        int updated = 0;
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> r = asRow(raw.get(i));
            if (r == null) {
                continue;
            }
            String id = idOf(r);
            present.add(id);
            rowsById.putIfAbsent(id, r);
            Change ch = changes.get(id);
            if (ch == null || same(r, ch.now())) {
                continue;
            }
            if (same(r, ch.old())) {
                out.set(i, copy(ch.now()));
                updated++;
            } else if (HATS.contains(id) && same(withoutPicture(r), ch.now())) {
                continue; // already moved, with the owner's picture (a second run)
            } else if (HATS.contains(id) && same(withoutPicture(r), ch.old())) {
                out.set(i, withPicture(ch.now(), r));
                updated++;
                lines.add("Config migration: moved your " + plainDisplay(r) + " to the Looks tab; it now lasts 90 days"
                        + " and can be bought again (your picture is kept).");
            } else {
                lines.add(WARN + "Config migration: kept your " + PRIZES + " → " + id + " row because you have changed"
                        + " it. 0.38's row is below; to take it, put it in place of yours:");
                lines.add(WARN + "  " + ArcadeConfigMigration.flowRow(ch.now()));
            }
        }
        List<String> added = new ArrayList<>();
        boolean all = true;
        for (Map<String, Object> r : ADDED_22) {
            all &= present.contains(idOf(r));
        }
        if (!all) {
            for (Map<String, Object> r : ADDED_22) {
                String id = idOf(r);
                Map<String, Object> theirs = rowsById.get(id);
                if (theirs == null) {
                    out.add(copy(r));
                    added.add(id);
                } else if (!same(theirs, r)) {
                    lines.add(WARN + "Config migration: kept your own " + PRIZES + " → " + id + " row: 0.38 ships a row"
                            + " with that id. To take 0.38's instead, put this line in place of yours:");
                    lines.add(WARN + "  " + ArcadeConfigMigration.flowRow(r));
                }
            }
        }
        if (updated > 0 || !added.isEmpty()) {
            c.set(PRIZES, out);
            List<String> parts = new ArrayList<>();
            if (!added.isEmpty()) {
                parts.add(added.size() + " new row" + (added.size() == 1 ? "" : "s") + (added.size() == ADDED_22.size()
                        ? " (Bouncy Boots, Floaty Feather, Make it Day, Sunny Day, five trails, Party Time, the Creeper"
                        + " and Dragon Hats, four caps, ten golf balls, the trophy ladder and the Trophy of the Month)"
                        : " (" + String.join(", ", added) + ")"));
            }
            if (updated > 0) {
                parts.add(updated + " row" + (updated == 1 ? "" : "s") + " updated (trails last a month, hats are on"
                        + " the Looks tab for 90 days, and the Arcade Trophy costs a bit more each time)");
            }
            log.add("Config migration: Prize Counter - " + String.join("; ", parts) + ". Rows you wrote yourself are"
                    + " unchanged.");
        }
        log.addAll(lines);
    }

    /** {@code r} with its picture taken out ({@code texture} and {@code icon.texture} blank), to compare. */
    private static Map<String, Object> withoutPicture(Map<String, Object> r) {
        Map<String, Object> out = copy(r);
        if (out.containsKey("texture")) {
            out.put("texture", "");
        }
        if (out.get("icon") instanceof Map<?, ?> icon) {
            Map<String, Object> i = copyMap(icon);
            if (i.containsKey("texture")) {
                i.put("texture", "");
            }
            out.put("icon", i);
        }
        return out;
    }

    /** 0.38's hat row with the owner's picture from {@code theirs}. */
    private static Map<String, Object> withPicture(Map<String, Object> now, Map<String, Object> theirs) {
        Map<String, Object> out = copy(now);
        out.put("texture", theirs.getOrDefault("texture", ""));
        if (theirs.get("icon") instanceof Map<?, ?> icon && out.get("icon") instanceof Map<?, ?> mine) {
            Map<String, Object> i = copyMap(mine);
            i.put("texture", icon.get("texture") == null ? "" : icon.get("texture"));
            out.put("icon", i);
        }
        return out;
    }

    // ---- step 3: the Arcade Crate --------------------------------------------------------------

    /** The crate's rewards as shipped take the new table; an edited crate is kept, with the hat line to delete. */
    private static void crate(FileConfiguration c, List<String> log) {
        if (!(c.get(CRATE, null) instanceof List<?> raw)) {
            return; // absent: revision 14 or the backfill writes the bundled crate
        }
        List<Map<String, Object>> rows = ArcadeConfigMigration.mapRows(raw);
        if (rows.size() == raw.size() && ArcadeConfigMigration.sameRows(rows, CRATE_NEW)) {
            return;
        }
        if (rows.size() == raw.size() && ArcadeConfigMigration.sameRows(rows, CRATE_OLD)) {
            List<Object> out = new ArrayList<>();
            for (Map<String, Object> r : CRATE_NEW) {
                out.add(copy(r));
            }
            c.set(CRATE, out);
            log.add("Config migration: the Arcade Crate no longer holds hats (hats are bought, never won); their"
                    + " share went to the Firework Show (weight 15 → 18).");
            return;
        }
        for (Map<String, Object> r : rows) {
            if (namesAHat(r)) {
                log.add(WARN + "Config migration: kept your " + CRATE + " because you have changed it. Hats are bought"
                        + " now, never won, so this line pays nothing any more: delete it from the crate.");
                log.add(WARN + "  " + ArcadeConfigMigration.flowRow(r));
            }
        }
    }

    /** A crate reward that hands out one of the animal hats. */
    private static boolean namesAHat(Map<String, Object> r) {
        Object prize = r.get("prize");
        List<?> ids = prize instanceof List<?> l ? l : prize == null ? List.of() : List.of(prize);
        for (Object id : ids) {
            if (id != null && HATS.contains(String.valueOf(id).trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    // ---- the hats sold before 0.38 (§3.15) -----------------------------------------------------

    /** The plain names a prize hat's ledger line carries ({@code token_ledger.detail}). */
    static final List<String> LEGACY_HAT_NAMES = List.of("Bunny Hat", "Panda Hat", "Fox Hat", "Penguin Hat",
            "Frog Hat", "Cake Hat");

    /** One player's hats of one kind bought before 0.38. */
    record LegacyHat(String player, String hat, int count) {
    }

    /**
     * The hats bought before 0.38, read-only, from the token ledger ({@code PRIZE} lines with a hat's plain
     * name): a hat sold before the wardrobe has no right in {@code cosmetics_owned} and stays a plain prize
     * hat for good (D34). A crate-won hat leaves no ledger line.
     */
    static List<LegacyHat> legacyHats(Connection c) throws SQLException {
        List<LegacyHat> out = new ArrayList<>();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("SELECT player, detail, COUNT(*) FROM token_ledger"
                    + " WHERE source='PRIZE' AND detail IN (?,?,?,?,?,?) GROUP BY 1, 2 ORDER BY 1, 2")) {
                for (int i = 0; i < LEGACY_HAT_NAMES.size(); i++) {
                    ps.setString(i + 1, LEGACY_HAT_NAMES.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new LegacyHat(rs.getString(1), rs.getString(2), rs.getInt(3)));
                    }
                }
            }
        }
        return out;
    }

    /**
     * The startup line (§3.15): {@code Hats bought before 0.38: none}, or each player's hats ("... (kept for
     * good): <player> Bunny Hat x1, ..."), so nobody has to run SQL on the live database to find out (red team
     * O18). {@code nameOf} turns a stored UUID into a name (null keeps the UUID).
     */
    static String legacyHatsLine(List<LegacyHat> hats, Function<UUID, String> nameOf) {
        if (hats == null || hats.isEmpty()) {
            return "Hats bought before 0.38: none";
        }
        List<String> parts = new ArrayList<>();
        for (LegacyHat h : hats) {
            String who = h.player();
            try {
                String name = nameOf == null ? null : nameOf.apply(UUID.fromString(h.player()));
                if (name != null && !name.isBlank()) {
                    who = name;
                }
            } catch (IllegalArgumentException | NullPointerException ignored) {
                // not a UUID: the stored text
            }
            parts.add(who + " " + h.hat() + " x" + h.count());
        }
        return "Hats bought before 0.38 (kept for good): " + String.join(", ", parts);
    }

    // ---- the frozen rows ------------------------------------------------------------------------

    private static Change trail(String id, String display, String what, String material, String particle) {
        return new Change(
                row("id", id, "tab", "COSMETICS", "display", display, "description", List.of(what, "for 7 days."),
                        "cost_tokens", 100, "icon", icon(material), "type", "trail", "particle", particle, "days", 7),
                row("id", id, "tab", "COSMETICS", "display", display, "description", List.of(what, "for a month."),
                        "cost_tokens", 100, "icon", icon(material), "type", "trail", "particle", particle, "days", 30));
    }

    private static Change hat(String id, String display) {
        return new Change(
                row("id", id, "tab", "COSMETICS", "display", display, "description", List.of("Wear it on your head!"),
                        "cost_tokens", 120, "icon", icon("LEATHER_HELMET"), "type", "hat", "texture", ""),
                row("id", id, "tab", "LOOKS", "display", display,
                        "description", List.of("Wear it on your head!", "Yours for 90 days."), "cost_tokens", 120,
                        "icon", icon("LEATHER_HELMET"), "type", "hat", "texture", "", "days", 90));
    }

    private static Map<String, Object> newTrail(String id, String display, String what, String material,
                                                String particle) {
        return row("id", id, "tab", "COSMETICS", "display", display, "description", List.of(what, "for a month."),
                "cost_tokens", 100, "icon", icon(material), "type", "trail", "particle", particle, "days", 30);
    }

    private static Map<String, Object> cap(String id, String display, String color) {
        return row("id", id, "tab", "LOOKS", "display", display,
                "description", List.of("A comfy cap to wear!", "Yours for 90 days."), "cost_tokens", 60,
                "icon", icon("LEATHER_HELMET"), "type", "hat", "material", "LEATHER_HELMET", "color", color,
                "days", 90);
    }

    private static Map<String, Object> ball(String id, String display, String colour, int cost, String look) {
        return row("id", id, "tab", "LOOKS", "display", display,
                "description", List.of("Play golf with " + (colour.startsWith("an ") ? colour : "a " + colour)
                        + " ball!"), "cost_tokens", cost, "icon", icon(look), "type", "ball", "look", look,
                "limit", limit("LIFETIME", 1));
    }

    private static Map<String, Object> trophy(String id, String display, List<String> description, int cost,
                                              String material, String requires, String per) {
        Map<String, Object> r = row("id", id, "tab", "TROPHIES", "display", display, "description", description,
                "cost_tokens", cost, "icon", icon(material), "type", "trophy", "texture", "");
        if (requires != null) {
            r.put("requires_prize", requires);
        }
        r.put("limit", limit(per, 1));
        return r;
    }

    private static Map<String, Object> icon(String material) {
        return row("texture", "", "material", material);
    }

    private static Map<String, Object> limit(String per, int count) {
        return row("per", per, "count", count);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Two rows with the same fields and values ({@link ArcadeConfigMigration#sameRows}). */
    static boolean same(Map<String, Object> a, Map<String, Object> b) {
        return ArcadeConfigMigration.sameRows(List.of(a), List.of(b));
    }

    private static Map<String, Object> asRow(Object o) {
        List<Map<String, Object>> rows = ArcadeConfigMigration.mapRows(java.util.Collections.singletonList(o));
        return rows.isEmpty() ? null : rows.get(0);
    }

    static String idOf(Map<String, Object> r) {
        Object id = r.get("id");
        return id == null ? "" : String.valueOf(id).trim().toLowerCase(Locale.ROOT);
    }

    private static String plainDisplay(Map<String, Object> r) {
        Object d = r.get("display");
        return d == null ? idOf(r) : String.valueOf(d).replaceAll("&[0-9a-fk-orA-FK-OR]", "");
    }

    /** A fresh, mutable deep copy, so the file never holds our constants. */
    static Map<String, Object> copy(Map<String, Object> r) {
        return copyMap(r);
    }

    private static Map<String, Object> copyMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), copyValue(e.getValue()));
        }
        return out;
    }

    private static Object copyValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            return copyMap(m);
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) {
                out.add(copyValue(o));
            }
            return out;
        }
        return v;
    }
}
