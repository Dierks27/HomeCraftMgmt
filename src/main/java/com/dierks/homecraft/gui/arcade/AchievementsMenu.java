package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig.AchievementDef;
import com.dierks.homecraft.config.PluginConfig.AchievementType;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Achievements, grouped. Each group starts a new row with a coloured label; an unlocked
 * achievement is bright and shimmering with a ✔ in its name, a locked one is gray with how far
 * along you are ("37 / 100"). Four rows a page, arrows on 45/53, Back on 49.
 */
public final class AchievementsMenu extends Menu {

    private static final int ROWS_PER_PAGE = 4;
    private static final Material[] LABELS = {Material.ORANGE_STAINED_GLASS_PANE, Material.LIGHT_BLUE_STAINED_GLASS_PANE,
            Material.MAGENTA_STAINED_GLASS_PANE, Material.LIME_STAINED_GLASS_PANE, Material.YELLOW_STAINED_GLASS_PANE,
            Material.CYAN_STAINED_GLASS_PANE, Material.PINK_STAINED_GLASS_PANE, Material.PURPLE_STAINED_GLASS_PANE};

    private final Player player;
    private final Runnable back;
    private final int page;

    public AchievementsMenu(HomeCraftManagement plugin, Player player, Runnable back, int page) {
        super(plugin);
        this.player = player;
        this.back = back;
        this.page = Math.max(0, page);
        init(54, Text.of("&6Achievements"));
    }

    /** One row of the screen: a group label followed by up to eight achievements. */
    private record Row(String group, int groupIndex, List<AchievementDef> defs) {
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        Map<String, List<AchievementDef>> groups = new LinkedHashMap<>();
        for (AchievementDef d : plugin.config().achievements().values()) {
            if (d.enabled()) {
                groups.computeIfAbsent(d.group(), g -> new ArrayList<>()).add(d);
            }
        }
        List<Row> rows = new ArrayList<>();
        int gi = 0;
        for (Map.Entry<String, List<AchievementDef>> g : groups.entrySet()) {
            List<AchievementDef> defs = g.getValue();
            for (int from = 0; from < defs.size(); from += 8) {
                rows.add(new Row(g.getKey(), gi, defs.subList(from, Math.min(defs.size(), from + 8))));
            }
            gi++;
        }

        Set<String> unlocked = plugin.achievements() == null ? Set.of()
                : plugin.achievements().unlocked(player.getUniqueId());
        int have = 0;
        int total = 0;
        int earned = 0;
        for (List<AchievementDef> defs : groups.values()) {
            for (AchievementDef d : defs) {
                total++;
                if (unlocked.contains(d.id())) {
                    have++;
                    earned += d.reward();
                }
            }
        }
        set(4, Menus.glint(ArcadeIcons.of(plugin, player, "achievements", Material.TOTEM_OF_UNDYING,
                "&6Achievements: &f" + have + " / " + total,
                "&7Tokens from achievements so far: &6" + earned,
                "&7Each one pays once."), true), null);

        int from = page * ROWS_PER_PAGE;
        for (int r = 0; r < ROWS_PER_PAGE && from + r < rows.size(); r++) {
            Row row = rows.get(from + r);
            int base = 9 + r * 9;
            set(base, Menus.icon(LABELS[row.groupIndex() % LABELS.length], "&f&l" + row.group()), null);
            for (int i = 0; i < row.defs().size(); i++) {
                AchievementDef d = row.defs().get(i);
                set(base + 1 + i, tile(d, unlocked.contains(d.id())), null);
            }
        }
        if (rows.isEmpty()) {
            set(22, Menus.icon(Material.PAPER, "&7No achievements yet"), null);
        }
        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&fPrevious page"),
                    e -> new AchievementsMenu(plugin, player, back, page - 1).open(player));
        }
        if (from + ROWS_PER_PAGE < rows.size()) {
            set(53, Menus.icon(Material.ARROW, "&fNext page"),
                    e -> new AchievementsMenu(plugin, player, back, page + 1).open(player));
        }
        set(49, Menus.icon(Material.BARRIER, back != null ? "&cBack" : "&cClose"), e -> {
            if (back != null) {
                back.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }

    private ItemStack tile(AchievementDef d, boolean done) {
        String reward = d.reward() > 0 ? " &8(+" + d.reward() + " tokens)" : "";
        if (done) {
            return Menus.glint(Menus.icon(material(d), "&a✔ " + d.display() + reward, "&aDone!"), true);
        }
        List<String> lore = new ArrayList<>();
        String progress = progressText(d);
        if (progress != null) {
            lore.add("&7" + progress);
        } else {
            lore.add("&7Not yet!");
        }
        return Menus.icon(Material.GRAY_DYE, "&7" + d.display() + reward, lore.toArray(new String[0]));
    }

    /** "37 / 100", "$450 / $1,000", or null for a one-off moment. */
    private String progressText(AchievementDef d) {
        if (plugin.achievements() == null) {
            return null;
        }
        long target = (long) d.target();
        return switch (d.type()) {
            case STAT, COUNTER, COLLECTION, STREAK -> Math.min(target, plugin.achievements().progress(player, d))
                    + " / " + target;
            case BALANCE -> plugin.economy().format(Math.min(target, plugin.achievements().progress(player, d)))
                    + " / " + plugin.economy().format(target);
            default -> null;
        };
    }

    private static Material material(AchievementDef d) {
        return switch (d.group().toLowerCase(java.util.Locale.ROOT)) {
            case "getting started" -> Material.OAK_SAPLING;
            case "mini hunter" -> Material.SPYGLASS;
            case "collector" -> Material.ITEM_FRAME;
            case "adventure" -> Material.FILLED_MAP;
            case "dedication" -> Material.CLOCK;
            case "work" -> Material.CHEST_MINECART;
            case "money" -> Material.GOLD_INGOT;
            case "arcade" -> Material.JUKEBOX;
            default -> d.type() == AchievementType.EVENT ? Material.FIREWORK_STAR : Material.NETHER_STAR;
        };
    }
}
