package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.Crate;
import com.dierks.homecraft.config.PluginConfig.CrateReward;
import com.dierks.homecraft.config.PluginConfig.PaidTier;
import com.dierks.homecraft.config.PluginConfig.RewardType;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.mini.Rarity;
import com.dierks.homecraft.storage.TokenDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Arcade engine (Phase 8, §3.9): earn tokens by playing (login streaks +
 * playtime), spend them on weighted loot crates (cap-aware Mini prizes minted
 * through the standard pipeline), buy better odds with a Vault fee, exchange
 * tokens for a guaranteed Rare+ via the pity path, and scratch lotto tickets.
 *
 * <p>All currency is in-game (tokens + Vault money) — never real money. Every Mini
 * prize goes through {@link MiniService#mintWild} so anti-dupe, caps, and
 * circulation are preserved.
 */
public final class ArcadeService {

    /** The result of opening a crate / pity / lotto — carries a display icon for the reveal GUI. */
    public record Outcome(boolean ok, String error, ItemStack icon, String label) {
        static Outcome fail(String e) {
            return new Outcome(false, e, null, null);
        }
        static Outcome won(ItemStack icon, String label) {
            return new Outcome(true, null, icon, label);
        }
    }

    private static final long MS_PER_DAY = 86_400_000L;

    private final HomeCraftManagement plugin;
    private final TokenDao dao;
    private BukkitTask playtimeTask;

    public ArcadeService(HomeCraftManagement plugin, TokenDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    public void start() {
        stop();
        if (!plugin.config().arcade().enabled()) {
            return;
        }
        validateCrates();
        // Accrue playtime tokens for online players every 5 minutes.
        playtimeTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                grantPlaytime(p, false);
            }
        }, 20L * 300L, 20L * 300L);
    }

    public void stop() {
        if (playtimeTask != null) {
            playtimeTask.cancel();
            playtimeTask = null;
        }
    }

    /** Warn (once, on load) about crate rewards that reference a Mini not in the catalog. */
    private void validateCrates() {
        for (Crate crate : plugin.config().arcade().crates().values()) {
            for (CrateReward r : crate.rewards()) {
                if (r.type() == RewardType.MINI && plugin.miniService().def(r.miniId()) == null) {
                    plugin.getLogger().warning("Arcade crate '" + crate.id() + "' references unknown Mini '"
                            + r.miniId() + "' — that reward is skipped; the crate still works.");
                }
            }
        }
    }

    // ---- token balance --------------------------------------------------------

    public int balance(UUID player) {
        try {
            return dao.get(player).tokens();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read tokens: " + e.getMessage());
            return 0;
        }
    }

    public int streak(UUID player) {
        try {
            return dao.get(player).streak();
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Minutes of play until the next playtime token, or -1 if playtime rewards are off. */
    public int minutesToNextPlaytimeToken(Player player) {
        PluginConfig.Arcade arc = plugin.config().arcade();
        if (!arc.playtimeEnabled() || arc.playtimeMinutesPerToken() <= 0) {
            return -1;
        }
        long minutes = player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L / 60L;
        int per = arc.playtimeMinutesPerToken();
        int into = (int) (minutes % per);
        return per - into;
    }

    private boolean spend(UUID player, int tokens) {
        try {
            TokenDao.TokenState s = dao.get(player);
            if (s.tokens() < tokens) {
                return false;
            }
            dao.save(new TokenDao.TokenState(player, s.tokens() - tokens, s.streak(),
                    s.lastStreakDay(), s.playtimeTokens()));
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to spend tokens: " + e.getMessage());
            return false;
        }
    }

    private void grant(UUID player, int tokens) {
        try {
            TokenDao.TokenState s = dao.get(player);
            dao.save(new TokenDao.TokenState(player, s.tokens() + tokens, s.streak(),
                    s.lastStreakDay(), s.playtimeTokens()));
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to grant tokens: " + e.getMessage());
        }
    }

    /** "+N token" feedback: a chat line and a bright pickup sound. */
    private void feedback(Player player, int tokens, String reason) {
        if (tokens <= 0) {
            return;
        }
        player.sendMessage(Text.of("&e✦ &a+" + tokens + " token" + (tokens == 1 ? "" : "s")
                + " &7(" + reason + ")&7. Balance: &6" + balance(player.getUniqueId())));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
    }

    /** Grant tokens to an online player with "+N token" feedback (the general earn path). */
    public void award(Player player, int tokens, String reason) {
        if (tokens <= 0) {
            return;
        }
        grant(player.getUniqueId(), tokens);
        feedback(player, tokens, reason);
    }

    // ---- admin grants ---------------------------------------------------------

    /** Admin: add tokens to a player (may be offline). Returns the new balance. */
    public int adminAdd(UUID player, int tokens) {
        grant(player, tokens);
        Player online = plugin.getServer().getPlayer(player);
        if (online != null && tokens > 0) {
            feedback(online, tokens, "admin grant");
        }
        return balance(player);
    }

    /** Admin: set a player's balance to an exact value. Returns the new balance. */
    public int adminSet(UUID player, int tokens) {
        try {
            TokenDao.TokenState s = dao.get(player);
            dao.save(new TokenDao.TokenState(player, Math.max(0, tokens), s.streak(),
                    s.lastStreakDay(), s.playtimeTokens()));
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to set tokens: " + e.getMessage());
        }
        return balance(player);
    }

    /** Admin: take tokens from a player (floored at 0). Returns the new balance. */
    public int adminTake(UUID player, int tokens) {
        try {
            TokenDao.TokenState s = dao.get(player);
            dao.save(new TokenDao.TokenState(player, Math.max(0, s.tokens() - Math.max(0, tokens)),
                    s.streak(), s.lastStreakDay(), s.playtimeTokens()));
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to take tokens: " + e.getMessage());
        }
        return balance(player);
    }

    // ---- earning: login streak + playtime -------------------------------------

    /** On join: award today's streak token (once per real day) and catch up playtime tokens. */
    public void onJoin(Player player) {
        PluginConfig.Arcade arc = plugin.config().arcade();
        if (!arc.enabled()) {
            return;
        }
        if (arc.streakEnabled()) {
            try {
                TokenDao.TokenState s = dao.get(player.getUniqueId());
                long today = System.currentTimeMillis() / MS_PER_DAY;
                if (today != s.lastStreakDay()) {
                    int streak = (today == s.lastStreakDay() + 1) ? s.streak() + 1 : 1;
                    int reward = arc.streakReward(streak);
                    dao.save(new TokenDao.TokenState(player.getUniqueId(), s.tokens() + reward,
                            streak, today, s.playtimeTokens()));
                    feedback(player, reward, "day " + streak + " login streak");
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed streak grant: " + e.getMessage());
            }
        }
        grantPlaytime(player, true);
    }

    /** Grant any whole playtime-milestone tokens the player has newly earned. */
    private void grantPlaytime(Player player, boolean announce) {
        PluginConfig.Arcade arc = plugin.config().arcade();
        if (!arc.enabled() || !arc.playtimeEnabled() || arc.playtimeMinutesPerToken() <= 0) {
            return;
        }
        try {
            TokenDao.TokenState s = dao.get(player.getUniqueId());
            long ticks = player.getStatistic(Statistic.PLAY_ONE_MINUTE); // stat is in ticks
            long minutes = ticks / 20L / 60L;
            int earned = (int) (minutes / arc.playtimeMinutesPerToken());
            if (earned > s.playtimeTokens()) {
                int diff = earned - s.playtimeTokens();
                dao.save(new TokenDao.TokenState(player.getUniqueId(), s.tokens() + diff, s.streak(),
                        s.lastStreakDay(), earned));
                feedback(player, diff, "time played");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed playtime grant: " + e.getMessage());
        }
    }

    // ---- crates ---------------------------------------------------------------

    public Crate crate(String id) {
        return plugin.config().arcade().crates().get(id);
    }

    /**
     * Open a crate. {@code tier} (optional) is a paid-odds fee guaranteeing a Rare+
     * (its floor) Mini. Tokens (and the fee, if any) are only charged once a reward
     * is guaranteed available, so a player is never charged for nothing.
     */
    public Outcome openCrate(Player player, String crateId, PaidTier tier) {
        PluginConfig.Arcade arc = plugin.config().arcade();
        Crate crate = arc.crates().get(crateId);
        if (crate == null) {
            return Outcome.fail("No such crate.");
        }
        if (crate.rewards().isEmpty()) {
            return Outcome.fail("This crate has no rewards configured.");
        }
        UUID id = player.getUniqueId();
        if (balance(id) < crate.costTokens()) {
            return Outcome.fail("You need " + crate.costTokens() + " tokens (you have " + balance(id) + ").");
        }
        if (tier != null && !plugin.economy().has(player, tier.costMoney())) {
            return Outcome.fail("You can't afford the " + plugin.economy().format(tier.costMoney()) + " odds fee.");
        }

        List<CrateReward> pool = eligiblePool(crate, tier);
        if (pool.isEmpty()) {
            return Outcome.fail(tier != null
                    ? "No " + tier.floor() + "+ Mini is available right now — fee not charged."
                    : "Nothing is available in this crate right now.");
        }

        // Commit the costs now that a reward is guaranteed.
        if (!spend(id, crate.costTokens())) {
            return Outcome.fail("You need " + crate.costTokens() + " tokens.");
        }
        if (tier != null) {
            plugin.economy().withdraw(player, tier.costMoney()); // burned money sink
        }

        return grantFromPool(player, pool);
    }

    /** Weighted-pick and grant a reward from an already-eligible pool. */
    private Outcome grantFromPool(Player player, List<CrateReward> pool) {
        List<CrateReward> working = new ArrayList<>(pool);
        while (!working.isEmpty()) {
            CrateReward r = weightedPick(working);
            switch (r.type()) {
                case MONEY -> {
                    plugin.economy().deposit(player, r.amount());
                    return Outcome.won(icon(Material.GOLD_INGOT, "&6" + plugin.economy().format(r.amount())),
                            "&6" + plugin.economy().format(r.amount()) + " &7cash");
                }
                case ITEM -> {
                    ItemStack give = new ItemStack(r.material(), r.itemAmount());
                    giveOrDrop(player, give);
                    return Outcome.won(give.clone(), "&f" + r.itemAmount() + "x " + niceName(r.material()));
                }
                case MINI -> {
                    MiniService.MintResult mr = plugin.miniService().mintWild(player, r.miniId());
                    if (mr.ok()) {
                        MiniDef def = plugin.miniService().def(r.miniId());
                        ItemStack ic = def != null ? plugin.miniService().icon(def) : icon(Material.PLAYER_HEAD, "&dMini");
                        return Outcome.won(ic, (def != null ? "&d" + def.name() : "&da Mini")
                                + " &7#" + mr.mintNumber());
                    }
                    working.remove(r); // minted out between check and mint — drop and re-roll
                }
            }
        }
        // Everything left was a minted-out Mini; hand back a small consolation of nothing.
        return Outcome.won(icon(Material.GRAY_DYE, "&7Better luck next time"), "&7no prize");
    }

    /** Rewards that can actually pay out now: mintable Minis (and, unpaid, money/item too). */
    private List<CrateReward> eligiblePool(Crate crate, PaidTier tier) {
        List<CrateReward> out = new ArrayList<>();
        for (CrateReward r : crate.rewards()) {
            if (r.type() == RewardType.MINI) {
                MiniDef def = plugin.miniService().def(r.miniId());
                if (def == null || mintedOut(def)) {
                    continue;
                }
                if (tier != null && def.rarity().ordinal() < tier.floor().ordinal()) {
                    continue; // paid tier: only Minis at/above the floor
                }
                out.add(r);
            } else if (tier == null) {
                out.add(r); // money/item only count when not paying for guaranteed rarity
            }
        }
        return out;
    }

    private boolean mintedOut(MiniDef def) {
        return !def.uncapped() && plugin.miniService().counts(def.id()).minted() >= def.cap();
    }

    // ---- pity exchange --------------------------------------------------------

    /** Spend the configured tokens for a guaranteed Rare+ (config floor) Mini. */
    public Outcome pity(Player player) {
        PluginConfig.Arcade arc = plugin.config().arcade();
        int cost = arc.pityTokens();
        if (cost <= 0) {
            return Outcome.fail("The pity exchange is disabled.");
        }
        UUID id = player.getUniqueId();
        if (balance(id) < cost) {
            return Outcome.fail("You need " + cost + " tokens (you have " + balance(id) + ").");
        }
        List<MiniDef> pool = new ArrayList<>();
        for (MiniDef def : plugin.miniService().catalog()) {
            if (def.rarity().ordinal() >= arc.pityRarity().ordinal() && !mintedOut(def)) {
                pool.add(def);
            }
        }
        if (pool.isEmpty()) {
            return Outcome.fail("No " + arc.pityRarity() + "+ Mini is available right now.");
        }
        if (!spend(id, cost)) {
            return Outcome.fail("You need " + cost + " tokens.");
        }
        MiniDef chosen = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        MiniService.MintResult mr = plugin.miniService().mintWild(player, chosen.id());
        if (!mr.ok()) {
            grant(id, cost); // refund on the rare race where it just minted out
            return Outcome.fail(mr.error());
        }
        ItemStack ic = plugin.miniService().icon(chosen);
        return Outcome.won(ic, "&d" + chosen.name() + " &7#" + mr.mintNumber());
    }

    // ---- lotto / scratch ------------------------------------------------------

    public Outcome scratch(Player player) {
        PluginConfig.Lotto l = plugin.config().arcade().lotto();
        if (l.payouts().isEmpty()) {
            return Outcome.fail("The lotto has no payouts configured.");
        }
        if (!plugin.economy().has(player, l.ticketCost())) {
            return Outcome.fail("A ticket costs " + plugin.economy().format(l.ticketCost()) + ".");
        }
        plugin.economy().withdraw(player, l.ticketCost()); // money sink
        double total = 0;
        for (PluginConfig.LottoPayout p : l.payouts()) {
            total += p.weight();
        }
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        double amount = 0;
        for (PluginConfig.LottoPayout p : l.payouts()) {
            roll -= p.weight();
            if (roll <= 0) {
                amount = p.amount();
                break;
            }
        }
        if (amount > 0) {
            plugin.economy().deposit(player, amount);
            return Outcome.won(icon(Material.EMERALD, "&a" + plugin.economy().format(amount)),
                    "&aWON " + plugin.economy().format(amount) + "&7!");
        }
        return Outcome.won(icon(Material.GRAY_DYE, "&7No win"), "&7no win — try again");
    }

    // ---- helpers --------------------------------------------------------------

    private CrateReward weightedPick(List<CrateReward> pool) {
        double total = 0;
        for (CrateReward r : pool) {
            total += r.weight();
        }
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        for (CrateReward r : pool) {
            roll -= r.weight();
            if (roll <= 0) {
                return r;
            }
        }
        return pool.get(pool.size() - 1);
    }

    private void giveOrDrop(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
    }

    private ItemStack icon(Material material, String name) {
        ItemStack it = new ItemStack(material);
        var meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.of(name));
            it.setItemMeta(meta);
        }
        return it;
    }

    private String niceName(Material material) {
        String n = material.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
