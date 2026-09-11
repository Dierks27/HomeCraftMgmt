package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.Crate;
import com.dierks.homecraft.config.PluginConfig.CrateReward;
import com.dierks.homecraft.config.PluginConfig.PaidTier;
import com.dierks.homecraft.config.PluginConfig.RewardType;
import com.dierks.homecraft.mini.MiniDef;
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
 * playtime), spend them on weighted loot crates (Cards, packs, filament or more
 * tokens — never money or sellable items, so tokens can never become money), buy
 * better odds with a Vault fee, exchange tokens for a guaranteed Rare+ Card via the
 * pity path, and scratch lotto tickets.
 *
 * <p>All currency is in-game (tokens + Vault money) — never real money.
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
                if ((r.type() == RewardType.MINI || r.type() == RewardType.CARD) && !r.usesTag()
                        && plugin.miniService().def(r.miniId()) == null) {
                    plugin.getLogger().warning("Arcade crate '" + crate.id() + "' references unknown Mini '"
                            + r.miniId() + "' — that reward is skipped; the crate still works.");
                }
                if (r.type() == RewardType.PACK && plugin.packs() != null && plugin.packs().pack(r.packId()) == null) {
                    plugin.getLogger().warning("Arcade crate '" + crate.id() + "' references unknown pack '"
                            + r.packId() + "' — that reward is skipped; the crate still works.");
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
        if (!plugin.sandbox().allowed(player.getWorld())) {
            plugin.sandbox().log(player, "token earn (" + reason + ")");
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
        if (!plugin.sandbox().allowed(player.getWorld())) {
            plugin.sandbox().log(player, "login-streak / playtime tokens");
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
        if (plugin.achievements() != null) {
            plugin.achievements().checkBalance(player);
        }
    }

    /** Grant any whole playtime-milestone tokens the player has newly earned. */
    private void grantPlaytime(Player player, boolean announce) {
        PluginConfig.Arcade arc = plugin.config().arcade();
        if (!arc.enabled() || !arc.playtimeEnabled() || arc.playtimeMinutesPerToken() <= 0) {
            return;
        }
        if (!plugin.sandbox().allowed(player.getWorld())) {
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
        if (!plugin.sandbox().check(player, "crate open " + crateId)) {
            return Outcome.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
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

        Outcome outcome = grantFromPool(player, pool, tier);
        if (outcome.ok() && plugin.achievements() != null) {
            plugin.achievements().tryAward(player, "first_crate");
        }
        if (outcome.ok() && plugin.quests() != null) {
            plugin.quests().record(player, PluginConfig.QuestType.OPEN_CRATE, 1);
        }
        return outcome;
    }

    /** Weighted-pick and grant a reward from an already-eligible pool. */
    private Outcome grantFromPool(Player player, List<CrateReward> pool, PaidTier tier) {
        List<CrateReward> working = new ArrayList<>(pool);
        while (!working.isEmpty()) {
            CrateReward r = weightedPick(working);
            switch (r.type()) {
                case CARD, MINI -> {
                    // A crate hands out the Mini's CARD (printed into a graded Mini at a
                    // Printer) — from a fixed id or a rarity-weighted tag pool. Tokens never
                    // become a finished Mini directly, let alone money.
                    MiniDef def = r.usesTag()
                            ? plugin.miniService().pickByRarity(tagPool(r.tag(), tier))
                            : plugin.miniService().def(r.miniId());
                    if (def != null && !mintedOut(def)) {
                        var cr = plugin.cards().issue(player, def.id());
                        if (cr.ok()) {
                            ItemStack ic = plugin.miniService().cardFor(def.id());
                            return Outcome.won(ic != null ? ic : icon(Material.PAPER, "&bCard"),
                                    "&b" + def.name() + " Card");
                        }
                    }
                    working.remove(r); // capped out between check and issue — drop and re-roll
                }
                case PACK -> {
                    ItemStack pack = plugin.packs() != null ? plugin.packs().packItem(r.packId()) : null;
                    if (pack == null) {
                        working.remove(r);
                        continue;
                    }
                    giveOrDrop(player, pack);
                    var def = plugin.packs().pack(r.packId());
                    return Outcome.won(pack.clone(), "&d" + (def != null ? def.displayName() : r.packId()) + " &7pack");
                }
                case FILAMENT -> {
                    org.bukkit.DyeColor color = r.color() != null ? r.color()
                            : org.bukkit.DyeColor.values()[ThreadLocalRandom.current().nextInt(org.bukkit.DyeColor.values().length)];
                    ItemStack fil = plugin.miniService().filamentItems().filament(color, r.amount());
                    giveOrDrop(player, fil);
                    return Outcome.won(fil.clone(), "&f" + r.amount() + "x " + niceName(color) + " Filament");
                }
                case TOKENS -> {
                    award(player, r.amount(), "crate prize");
                    return Outcome.won(icon(Material.SUNFLOWER, "&e+" + r.amount() + " tokens"),
                            "&e" + r.amount() + " token" + (r.amount() == 1 ? "" : "s"));
                }
            }
        }
        // Everything left was a minted-out Mini; hand back a small consolation of nothing.
        // A grey dye on the reveal screen's grey pane background was an invisible outcome; the
        // barrier is blunt but honest, and the player can see that the pull resolved.
        return Outcome.won(icon(Material.BARRIER, "&7Better luck next time"), "&7no prize");
    }

    /** Rewards that can actually pay out now: issuable Cards (and, unpaid, packs/filament/tokens too). */
    private List<CrateReward> eligiblePool(Crate crate, PaidTier tier) {
        List<CrateReward> out = new ArrayList<>();
        for (CrateReward r : crate.rewards()) {
            if (r.type() == RewardType.MINI || r.type() == RewardType.CARD) {
                if (r.usesTag()) {
                    if (!tagPool(r.tag(), tier).isEmpty()) {
                        out.add(r);
                    }
                    continue;
                }
                MiniDef def = plugin.miniService().def(r.miniId());
                if (def == null || mintedOut(def)) {
                    continue;
                }
                if (tier != null && def.rarity().ordinal() < tier.floor().ordinal()) {
                    continue; // paid tier: only Minis at/above the floor
                }
                out.add(r);
            } else if (tier == null) {
                if (r.type() == RewardType.PACK && (plugin.packs() == null || plugin.packs().pack(r.packId()) == null)) {
                    continue;
                }
                out.add(r); // packs/filament/tokens only count when not paying for guaranteed rarity
            }
        }
        return out;
    }

    /** The mintable Minis carrying a tag, filtered by a paid tier's rarity floor. */
    private List<MiniDef> tagPool(String tag, PaidTier tier) {
        List<MiniDef> pool = plugin.miniService().poolFromTag(tag);
        if (tier != null) {
            pool.removeIf(d -> d.rarity().ordinal() < tier.floor().ordinal());
        }
        return pool;
    }

    private boolean mintedOut(MiniDef def) {
        return !def.uncapped() && plugin.miniService().counts(def.id()).minted() >= def.cap();
    }

    // ---- pity exchange --------------------------------------------------------

    /** Spend the configured tokens for a guaranteed Rare+ (config floor) Mini. */
    public Outcome pity(Player player) {
        if (!plugin.sandbox().check(player, "pity exchange")) {
            return Outcome.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
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
        // Phase 9: the pity exchange guarantees a Rare+ CARD (printed at a Printer).
        var cr = plugin.cards().issue(player, chosen.id());
        if (!cr.ok()) {
            grant(id, cost); // refund on the rare race where its cards just sold out
            return Outcome.fail(cr.error());
        }
        ItemStack ic = plugin.miniService().cardFor(chosen.id());
        if (ic == null) {
            ic = icon(Material.PAPER, "&bCard");
        }
        return Outcome.won(ic, "&b" + chosen.name() + " Card");
    }

    // ---- lotto / scratch ------------------------------------------------------

    public Outcome scratch(Player player) {
        if (!plugin.sandbox().check(player, "scratch ticket")) {
            return Outcome.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
        PluginConfig.Lotto l = plugin.config().arcade().lotto();
        if (l.payouts().isEmpty()) {
            return Outcome.fail("The lotto has no payouts configured.");
        }
        if (!plugin.economy().has(player, l.ticketCost())) {
            return Outcome.fail("A ticket costs " + plugin.economy().format(l.ticketCost()) + ".");
        }
        plugin.economy().withdraw(player, l.ticketCost()); // money sink
        if (plugin.quests() != null) {
            plugin.quests().record(player, PluginConfig.QuestType.SCRATCH, 1);
        }
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
        return Outcome.won(icon(Material.BARRIER, "&7No win"), "&7no win — try again");
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

    private String niceName(org.bukkit.DyeColor color) {
        String n = color.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private String niceName(Material material) {
        String n = material.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
