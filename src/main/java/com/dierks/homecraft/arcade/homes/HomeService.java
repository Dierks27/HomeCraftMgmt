package com.dierks.homecraft.arcade.homes;

import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The +1 Home perk: a player's Essentials home limit is their own (the base) plus the slots
 * they bought (the bonus), granted as a single {@code hcm_<total>} tier through LuckPerms.
 *
 * <p>The tiers are read from {@code plugins/Essentials/config.yml} ({@code sethome-multiple},
 * read-only, re-read on {@code /hcm reload}). The player's grant is recomputed on purchase, on
 * join, on reload, when their groups change while online, and with {@code /hcm homes refresh}.
 * Nothing is written when the player already holds exactly the right tier.
 */
public final class HomeService implements Listener {

    /** The Prize Counter row id, and the ids of the two rows it replaced. */
    public static final String PRIZE_ID = "home_slot";
    static final Map<String, String> LEGACY = Map.of("home_2", "homes2", "home_3", "homes3");

    private final HomeCraftManagement plugin;
    private Map<String, Integer> tiers = Map.of();
    private boolean essentialsConfig;
    private LuckPermsBridge luckPerms;

    public HomeService(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /** Re-read the Essentials tiers; hook LuckPerms the first time it is there. */
    public void reload() {
        File file = new File(plugin.getDataFolder().getParentFile(), "Essentials/config.yml");
        Map<String, Integer> read = new LinkedHashMap<>();
        essentialsConfig = false;
        if (file.isFile()) {
            YamlConfiguration y = new YamlConfiguration();
            try {
                y.load(file);
                essentialsConfig = true;
                ConfigurationSection s = y.getConfigurationSection("sethome-multiple");
                if (s != null) {
                    for (String key : s.getKeys(false)) {
                        if (s.isInt(key)) {
                            read.put(key, s.getInt(key));
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Could not read " + file + " for home tiers: " + e.getMessage());
            }
        }
        tiers = Collections.unmodifiableMap(read);
        if (luckPerms == null && Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            try {
                luckPerms = new LuckPermsBridge(plugin, this);
            } catch (Throwable t) {
                plugin.getLogger().warning("LuckPerms is installed but its API could not be reached — the +1 Home "
                        + "perk is off: " + t.getMessage());
            }
        }
    }

    /** The perk works only with both an Essentials config to read and LuckPerms to write to. */
    public boolean available() {
        return luckPerms != null && essentialsConfig;
    }

    public Map<String, Integer> tiers() {
        return tiers;
    }

    // ---- the numbers ---------------------------------------------------------------------

    /** The homes the player has without the perk. */
    public int base(Player player) {
        return HomeMath.base(tiers, player::hasPermission, legacyTiers(player.getUniqueId()));
    }

    /** Slots bought (including ones credited from the old Second/Third Home rows). */
    public int bonus(UUID player) {
        try {
            return plugin.prizes().dao().purchases(player, PRIZE_ID, "life");
        } catch (SQLException e) {
            return 0;
        }
    }

    public boolean unlimited(Player player) {
        return player.hasPermission(HomeMath.UNLIMITED);
    }

    /** Homes the player has now: base plus the slots bought. */
    public int total(Player player) {
        return base(player) + bonus(player.getUniqueId());
    }

    /** Whether the row is offered to this player at all. */
    public boolean offered(Player player) {
        return available() && HomeMath.offered(base(player), unlimited(player));
    }

    /**
     * Why one more slot can't be bought right now, or null if it can. A missing {@code hcm_<N>}
     * tier refuses the purchase (before any charge) and logs the exact line to add.
     */
    public String refuseReason(Player player) {
        if (!offered(player)) {
            return "That isn't for sale right now.";
        }
        int next = total(player) + 1;
        String line = HomeMath.missingTierLine(tiers, next);
        if (line != null) {
            plugin.getLogger().warning("+1 Home for " + player.getName() + " needs a tier for " + next + " homes. Add "
                    + "this line under sethome-multiple in plugins/Essentials/config.yml, run /essentials reload, then "
                    + "/hcm reload:\n" + line);
            return "Homes can't be added right now — nothing was charged. An admin has been told.";
        }
        return null;
    }

    /** The old rows' tier names this player got from them (so their base ignores those). */
    private Set<String> legacyTiers(UUID player) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, String> e : LEGACY.entrySet()) {
            try {
                if (plugin.prizes().dao().everBought(player, e.getKey())) {
                    out.add(e.getValue());
                }
            } catch (SQLException ignored) {
                // not credited, not ignored
            }
        }
        return out;
    }

    // ---- writing ---------------------------------------------------------------------------

    /**
     * Make the player's grant match base + bonus ({@code extraBonus} counts a slot being bought
     * right now, before it is recorded). No bonus means no {@code hcm_} tier. The old rows'
     * homes2/homes3 nodes are cleared from anyone who bought them.
     */
    public void refresh(Player player, int extraBonus) {
        if (!available() || player == null || !player.isOnline()) {
            return;
        }
        int bonus = bonus(player.getUniqueId()) + extraBonus;
        String desired = null;
        if (bonus > 0) {
            int total = base(player) + bonus;
            String line = HomeMath.missingTierLine(tiers, total);
            if (line != null) {
                plugin.getLogger().warning(player.getName() + " should have " + total + " homes, but sethome-multiple "
                        + "has no tier for it. Add this line to plugins/Essentials/config.yml, run /essentials "
                        + "reload, then /hcm homes refresh " + player.getName() + ":\n" + line);
                return;
            }
            desired = HomeMath.node(total);
        }
        Set<String> legacyNodes = new HashSet<>();
        for (String tier : legacyTiers(player.getUniqueId())) {
            legacyNodes.add(HomeMath.MULTIPLE + "." + tier);
        }
        luckPerms.apply(player, desired, legacyNodes);
    }

    public void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            refresh(p, 0);
        }
    }

    /** On join, once LuckPerms has loaded the player and set their permissions. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> refresh(p, 0), 40L);
    }
}
