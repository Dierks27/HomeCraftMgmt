package com.dierks.homecraft.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.Crate;
import com.dierks.homecraft.config.PluginConfig.CrateReward;
import com.dierks.homecraft.config.PluginConfig.PaidTier;
import com.dierks.homecraft.config.PluginConfig.RewardType;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Arcade's games (§3.9): weighted loot crates, the Prize Counter, the pity exchange and the
 * scratch ticket. Token balances, the streak, playtime and the ledger live in
 * {@link TokenService}; every token this class takes or pays goes through it, so each one leaves
 * a ledger line.
 *
 * <p>Crates pay Cards, packs, filament or more tokens — never money or sellable items, so tokens
 * can never become money. Every Card issued here passes {@link com.dierks.homecraft.mini.CardService#canIssue},
 * the one cap check every Card path shares.
 *
 * <p>All currency is in-game (tokens + Vault money) — never real money.
 */
public final class ArcadeService {

    /**
     * The result of opening a crate / pity / lotto — carries a display icon for the reveal GUI.
     *
     * @param win whether the pull actually paid something. A loss is still {@code ok} (the ticket
     *            was bought and resolved), but the reveal must not call it a win: it used to say
     *            "You won!" and play the fanfare over "no win".
     */
    public record Outcome(boolean ok, String error, ItemStack icon, String label, boolean win) {
        static Outcome fail(String e) {
            return new Outcome(false, e, null, null, false);
        }
        static Outcome won(ItemStack icon, String label) {
            return new Outcome(true, null, icon, label, true);
        }
        static Outcome lost(ItemStack icon, String label) {
            return new Outcome(true, null, icon, label, false);
        }
    }

    private final HomeCraftManagement plugin;

    public ArcadeService(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.config().arcade().enabled()) {
            return;
        }
        validateCrates();
    }

    public void stop() {
        // nothing scheduled here any more — the playtime/streak tick lives in TokenService
    }

    private TokenService tokens() {
        return plugin.tokens();
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
        int have = tokens().balance(id);
        if (have < crate.costTokens()) {
            return Outcome.fail("You need " + crate.costTokens() + " tokens (you have " + have + ").");
        }
        if (tier != null && !plugin.economy().has(player, tier.costMoney())) {
            return Outcome.fail("You can't afford the " + plugin.economy().format(tier.costMoney()) + " odds fee.");
        }

        List<CrateReward> pool = eligiblePool(crate, tier);
        if (pool.isEmpty()) {
            return Outcome.fail(tier != null
                    ? "No " + floorWords(tier.floor()) + " Card is left right now. Nothing was charged."
                    : "Nothing is available in this crate right now.");
        }

        // Commit the costs now that a reward is guaranteed.
        if (!tokens().spend(id, crate.costTokens(), TokenService.Source.CRATE, crate.display())) {
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
                    if (def != null && plugin.cards().canIssue(def)) {
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
                    return Outcome.won(pack.clone(), "&d" + (def != null ? def.displayName() : r.packId()));
                }
                case FILAMENT -> {
                    org.bukkit.DyeColor color = r.color() != null ? r.color()
                            : org.bukkit.DyeColor.values()[ThreadLocalRandom.current().nextInt(org.bukkit.DyeColor.values().length)];
                    ItemStack fil = plugin.miniService().filamentItems().filament(color, r.amount());
                    giveOrDrop(player, fil);
                    return Outcome.won(fil.clone(), "&f" + r.amount() + "x " + niceName(color) + " Filament");
                }
                case TOKENS -> {
                    tokens().award(player, r.amount(), TokenService.Source.CRATE, "Crate prize");
                    return Outcome.won(icon(Material.SUNFLOWER, "&e+" + r.amount() + " tokens"),
                            "&e" + r.amount() + " token" + (r.amount() == 1 ? "" : "s"));
                }
            }
        }
        // Everything left was a minted-out Mini; hand back a small consolation of nothing.
        // A grey dye on the reveal screen's grey pane background was an invisible outcome; the
        // barrier is blunt but honest, and the player can see that the pull resolved.
        return Outcome.lost(icon(Material.BARRIER, "&7Better luck next time"), "&7nothing this time");
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
                if (def == null || !plugin.cards().canIssue(def)) {
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

    /** The issuable Minis carrying a tag, filtered by a paid tier's rarity floor. */
    private List<MiniDef> tagPool(String tag, PaidTier tier) {
        List<MiniDef> pool = plugin.cards().issuable(plugin.miniService().poolFromTag(tag));
        if (tier != null) {
            pool.removeIf(d -> d.rarity().ordinal() < tier.floor().ordinal());
        }
        return pool;
    }

    /** "Rare-or-better" (or just "Legendary", which has nothing better), for plain-text messages. */
    private static String floorWords(com.dierks.homecraft.mini.Rarity floor) {
        return floor == com.dierks.homecraft.mini.Rarity.LEGENDARY
                ? floor.display() : floor.display() + "-or-better";
    }

    // ---- prize counter --------------------------------------------------------

    /**
     * Buy a Prize Counter row: a <b>known-outcome</b> token purchase (filament, a HomeCraft
     * block, a sealed pack). Unlike a crate this is a price rather than a pull, which is the
     * point — it gives tokens a floor value instead of only an expected one.
     *
     * <p>The item is built <i>before</i> anything is charged, so a prize that cannot be
     * produced right now costs nothing. Nothing here is sellable to the house, so §11 #9
     * holds: filament is craftable-but-unsellable, packs only ever become Cards, and a
     * HomeCraft block is a tool, not a market good.
     *
     * @param color the buyer's colour choice for a prize that lets them pick; ignored otherwise
     */
    public Outcome buyPrize(Player player, String prizeId, org.bukkit.DyeColor color) {
        if (!plugin.sandbox().check(player, "prize purchase " + prizeId)) {
            return Outcome.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
        PluginConfig.Prize prize = plugin.config().arcade().prize(prizeId);
        if (prize == null) {
            return Outcome.fail("No such prize.");
        }
        UUID id = player.getUniqueId();
        int have = tokens().balance(id);
        if (have < prize.costTokens()) {
            return Outcome.fail("You need " + prize.costTokens() + " tokens (you have " + have + ").");
        }

        ItemStack item;
        String label;
        switch (prize.type()) {
            case FILAMENT -> {
                org.bukkit.DyeColor dye = prize.color() != null ? prize.color() : color;
                if (dye == null) {
                    return Outcome.fail("Pick a filament colour first.");
                }
                item = plugin.miniService().filamentItems().filament(dye, prize.amount());
                label = "&f" + prize.amount() + "x " + niceName(dye) + " Filament";
            }
            case BLOCK -> {
                com.dierks.homecraft.block.CustomBlockType type;
                try {
                    type = com.dierks.homecraft.block.CustomBlockType.valueOf(prize.blockKey());
                } catch (IllegalArgumentException e) {
                    return Outcome.fail("That prize is misconfigured — tell an admin.");
                }
                item = plugin.items().of(type);
                if (item == null) {
                    return Outcome.fail("That prize is unavailable right now — nothing charged.");
                }
                label = prize.display();
            }
            case PACK -> {
                item = plugin.packs() != null ? plugin.packs().packItem(prize.packId()) : null;
                if (item == null) {
                    return Outcome.fail("That pack no longer exists — nothing charged.");
                }
                label = prize.display();
            }
            default -> {
                return Outcome.fail("That prize is misconfigured — tell an admin.");
            }
        }

        // Only now is anything taken: the prize is in hand and cannot fail to appear.
        if (!tokens().spend(id, prize.costTokens(), TokenService.Source.PRIZE, label)) {
            return Outcome.fail("You need " + prize.costTokens() + " tokens.");
        }
        giveOrDrop(player, item);
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f);
        return Outcome.won(item.clone(), label);
    }

    // ---- pity exchange --------------------------------------------------------

    /** Spend the configured tokens for a guaranteed Card at or above the configured rarity. */
    public Outcome pity(Player player) {
        if (!plugin.sandbox().check(player, "pity exchange")) {
            return Outcome.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
        PluginConfig.Arcade arc = plugin.config().arcade();
        int cost = arc.pityTokens();
        if (cost <= 0) {
            return Outcome.fail("This isn't available right now.");
        }
        UUID id = player.getUniqueId();
        int have = tokens().balance(id);
        if (have < cost) {
            return Outcome.fail("You need " + cost + " tokens (you have " + have + ").");
        }
        List<MiniDef> pool = new ArrayList<>();
        for (MiniDef def : plugin.miniService().catalog()) {
            if (def.rarity().ordinal() >= arc.pityRarity().ordinal() && plugin.cards().canIssue(def)) {
                pool.add(def);
            }
        }
        String none = "No " + floorWords(arc.pityRarity()) + " Card is left right now. Nothing was charged.";
        if (pool.isEmpty()) {
            return Outcome.fail(none);
        }
        // Weight the pick by rarity, exactly as crates and wild drops do. Picking
        // uniformly made every tier above the floor equally likely, so a Legendary came
        // up as often as a Rare — on a catalog whose only Rare+ entry is Legendary, a
        // fixed token price bought a guaranteed Legendary every time. The floor
        // guarantees Rare-or-better; it was never meant to flatten what sits above it.
        MiniDef chosen = plugin.miniService().pickByRarity(pool);
        if (chosen == null) {
            return Outcome.fail(none);
        }
        if (!tokens().spend(id, cost, TokenService.Source.PITY, chosen.name() + " Card")) {
            return Outcome.fail("You need " + cost + " tokens.");
        }
        // Phase 9: the pity exchange guarantees a Rare+ CARD (printed at a Printer).
        var cr = plugin.cards().issue(player, chosen.id());
        if (!cr.ok()) {
            // the rare race where its Cards just sold out
            tokens().grant(id, cost, TokenService.Source.REFUND, chosen.name() + " Card sold out");
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
                    "&a" + plugin.economy().format(amount));
        }
        return Outcome.lost(icon(Material.BARRIER, "&7No win this time"), "&7no win");
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
