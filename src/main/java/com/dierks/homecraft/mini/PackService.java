package com.dierks.homecraft.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.TokenService;
import com.dierks.homecraft.mini.PackItems.Currency;
import com.dierks.homecraft.mini.PackItems.Paid;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Card Packs (§3.5): buy a sealed pack for dollars or tokens, open it for Cards.
 *
 * <p>A pack rolls a rarity by its odds — only rarities that still have a Card to give take part,
 * scaled back up to 100% — then a Mini of that rarity at random (only ones carrying the pack's
 * tag, if it has one). A pack with a hand-picked pool rolls that pool by weight instead. Every
 * Card goes through the one cap check ({@link CardService#canIssue}), so a pack never hands out a
 * Card for a Mini that is gone.
 *
 * <p>If nothing at all can come out, the pack is <b>sold out</b>: the shop will not sell it and a
 * sealed one stays sealed. A pack of several Cards that can only fill some of them hands back
 * the rest's share of what was paid, in the currency it was paid in.
 */
public final class PackService {

    /** Result of opening a pack: the Card ids awarded (for the reveal GUI), or a failure. */
    public record OpenResult(boolean ok, String error, List<String> cardIds, String refund) {
        static OpenResult fail(String error) {
            return new OpenResult(false, error, List.of(), null);
        }
    }

    /** Result of buying a pack (a sealed pack item is given), or a failure. */
    public record BuyResult(boolean ok, String error) {
        static BuyResult fail(String error) {
            return new BuyResult(false, error);
        }
    }

    /** One Mini a pack can give, and whether it is sold out right now. */
    public record Possible(MiniDef def, boolean soldOut) {
    }

    private final HomeCraftManagement plugin;
    private final PackItems packItems = new PackItems();

    public PackService(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    public PackItems packItems() {
        return packItems;
    }

    /** A sealed pack item for a pack id, given free (crates, /hcm give) — or null if unknown. */
    public ItemStack packItem(String packId) {
        return packItem(packId, null);
    }

    /** A sealed pack item carrying what it was bought with (null = free), or null if unknown. */
    public ItemStack packItem(String packId, Paid paid) {
        Pack.PackDef def = pack(packId);
        return def == null ? null : packItems.pack(def, paid);
    }

    public List<Pack.PackDef> packs() {
        return plugin.config().packs().all();
    }

    public Pack.PackDef pack(String id) {
        return plugin.config().packs().byId(id);
    }

    // ---- what a pack can give ------------------------------------------------------------

    /**
     * Every Mini this pack could ever give, each marked sold out or not, in catalog order. For
     * an odds pack that is every Mini of a rarity with odds above zero (and the tag, if set).
     */
    public List<Possible> possible(Pack.PackDef def) {
        List<Possible> out = new ArrayList<>();
        if (def == null) {
            return out;
        }
        if (def.usesPool()) {
            Set<String> seen = new HashSet<>();
            for (Pack.PackEntry e : def.pool()) {
                MiniDef m = plugin.miniService().def(e.miniId());
                if (m != null && e.weight() > 0 && seen.add(m.id())) {
                    out.add(new Possible(m, !plugin.cards().canIssue(m)));
                }
            }
            return out;
        }
        for (MiniDef m : candidates(def)) {
            if (def.odds(m.rarity()) > 0) {
                out.add(new Possible(m, !plugin.cards().canIssue(m)));
            }
        }
        return out;
    }

    /** Every Mini an odds pack draws from before the cap check: the tag's Minis, or all of them. */
    private List<MiniDef> candidates(Pack.PackDef def) {
        List<MiniDef> out = new ArrayList<>();
        for (MiniDef m : plugin.miniService().catalog()) {
            if (!def.hasTag() || m.hasTag(def.tag())) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * The odds this pack rolls with right now: only rarities with a Card left, scaled to 1.
     * Empty for a pool pack (it has weights per Mini instead) or a sold-out one.
     */
    public Map<Rarity, Double> liveOdds(Pack.PackDef def) {
        if (def == null || !def.usesOdds()) {
            return Map.of();
        }
        Set<Rarity> available = EnumSet.noneOf(Rarity.class);
        for (MiniDef m : plugin.cards().issuable(candidates(def))) {
            available.add(m.rarity());
        }
        return PackOdds.effective(def.rarityOdds(), available);
    }

    /** Nothing in this pack can be given right now. */
    public boolean soldOut(Pack.PackDef def) {
        if (def == null || !def.isValid()) {
            return true;
        }
        if (def.usesPool()) {
            for (Pack.PackEntry e : def.pool()) {
                if (e.weight() > 0 && plugin.cards().canIssue(plugin.miniService().def(e.miniId()))) {
                    return false;
                }
            }
            return true;
        }
        return liveOdds(def).isEmpty();
    }

    /** How many of the Minis this pack can still give that the player doesn't own yet. */
    public int missing(Player player, Pack.PackDef def) {
        Set<String> owned = plugin.miniService().ownedIds(player.getUniqueId());
        int n = 0;
        for (Possible p : possible(def)) {
            if (!p.soldOut() && !owned.contains(p.def().id())) {
                n++;
            }
        }
        return n;
    }

    // ---- buying -------------------------------------------------------------------------

    /**
     * Buy a sealed pack with dollars or tokens and give it to the player (opened later by
     * right-clicking it). Buying and opening stay separate so packs can be traded and given.
     * A sold-out pack is not sold.
     */
    public BuyResult buy(Player player, String packId, Currency currency) {
        if (!plugin.sandbox().check(player, "pack purchase " + packId)) {
            return BuyResult.fail(com.dierks.homecraft.integration.EconomySandbox.reason());
        }
        Pack.PackDef def = pack(packId);
        if (def == null) {
            return BuyResult.fail("That pack doesn't exist.");
        }
        if (!def.isValid()) {
            return BuyResult.fail("That pack isn't ready yet.");
        }
        if (soldOut(def)) {
            return BuyResult.fail("That pack is sold out.");
        }
        if (currency == Currency.TOKENS) {
            if (def.priceTokens() <= 0) {
                return BuyResult.fail("That pack isn't sold for tokens.");
            }
            if (plugin.tokens() == null) {
                return BuyResult.fail("The Arcade is offline.");
            }
            // Build first, then charge: nothing to undo if the charge is refused.
            ItemStack item = packItems.pack(def, new Paid(Currency.TOKENS, def.priceTokens()));
            if (!plugin.tokens().spend(player.getUniqueId(), def.priceTokens(), TokenService.Source.PACK,
                    com.dierks.homecraft.util.Text.plain(def.displayName()))) {
                int have = plugin.tokens().balance(player.getUniqueId());
                return BuyResult.fail("You need " + Math.max(1, def.priceTokens() - have) + " more tokens.");
            }
            give(player, item);
            return new BuyResult(true, null);
        }
        if (def.price() <= 0) {
            return BuyResult.fail("That pack isn't sold for dollars.");
        }
        if (!plugin.economy().isEnabled()) {
            return BuyResult.fail("The economy is offline (no Vault).");
        }
        if (!plugin.economy().has(player, def.price())) {
            return BuyResult.fail("You can't afford " + plugin.economy().format(def.price()) + ".");
        }
        ItemStack item = packItems.pack(def, new Paid(Currency.MONEY, def.price()));
        if (!plugin.economy().withdraw(player, def.price())) {
            return BuyResult.fail("Payment failed.");
        }
        give(player, item);
        return new BuyResult(true, null);
    }

    private static void give(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
    }

    // ---- opening ------------------------------------------------------------------------

    /**
     * Open a pack the player already owns: roll and issue its Cards. No charge here — paying
     * happened in {@link #buy}. Fails, leaving the pack sealed, only when nothing at all can
     * come out. A pack that fills only some of its Cards refunds the rest's share of
     * {@code paid} in the same currency.
     *
     * @param paid  what the pack item says it was bought with (null = given free)
     * @param cards how many Cards this particular pack holds, or 0 for the pack type's count (a
     *              pack sealed when packs held three still holds three)
     */
    public OpenResult open(Player player, String packId, Paid paid, int cards) {
        Pack.PackDef def = pack(packId);
        if (def == null) {
            return OpenResult.fail("That pack doesn't exist.");
        }
        if (!def.isValid()) {
            return OpenResult.fail("That pack isn't ready yet.");
        }
        if (soldOut(def)) {
            return OpenResult.fail("This pack is sold out right now. Keep it sealed — it opens again "
                    + "when there are Cards to give.");
        }
        int count = cards > 0 ? cards : def.cardCount();
        if (count > 1 && paid != null && !canRefund(paid.currency())) {
            // A short pack must be able to pay back its share; if that currency is offline, wait.
            return OpenResult.fail("This pack can't be opened right now — try again in a bit.");
        }
        List<String> awarded = new ArrayList<>();
        for (int slot = 0; slot < count; slot++) {
            String id = def.usesPool() ? rollPool(player, def.pool()) : rollOdds(player, def);
            if (id != null) {
                awarded.add(id);
            }
        }
        if (awarded.isEmpty()) {
            return OpenResult.fail("This pack is sold out right now. Keep it sealed.");
        }
        String refund = refund(player, count, paid, awarded.size());
        if (plugin.achievements() != null) {
            plugin.achievements().tryAward(player, "first_pack");
        }
        if (plugin.quests() != null) {
            plugin.quests().record(player,
                    com.dierks.homecraft.config.PluginConfig.QuestType.OPEN_PACK, 1);
        }
        return new OpenResult(true, null, awarded, refund);
    }

    private boolean canRefund(Currency currency) {
        return currency == Currency.TOKENS ? plugin.tokens() != null : plugin.economy().isEnabled();
    }

    /** Hand back the missing Cards' share; returns what was refunded, for the chat line. */
    private String refund(Player player, int count, Paid paid, int awarded) {
        if (paid == null || awarded >= count) {
            return null;
        }
        if (paid.currency() == Currency.TOKENS) {
            int back = PackOdds.refundTokens((int) Math.round(paid.amount()), count, awarded);
            if (back <= 0) {
                return null;
            }
            if (plugin.tokens() != null && plugin.tokens().grant(player.getUniqueId(), back,
                    TokenService.Source.REFUND, "Pack short " + (count - awarded) + " Card(s)") >= 0) {
                return back + " tokens";
            }
            return refundFailed(player, back + " tokens");
        }
        double back = PackOdds.refundMoney(paid.amount(), count, awarded);
        if (back <= 0) {
            return null;
        }
        if (plugin.economy().isEnabled() && plugin.economy().deposit(player, back)) {
            return plugin.economy().format(back);
        }
        return refundFailed(player, plugin.economy().format(back));
    }

    /** Never silently: the player is told, and the log says exactly what is owed to whom. */
    private String refundFailed(Player player, String owed) {
        plugin.getLogger().warning("Could not refund " + owed + " to " + player.getName() + " ("
                + player.getUniqueId() + ") for a pack that came up short. Pay it by hand.");
        player.sendMessage(com.dierks.homecraft.util.Text.of("&cThis pack came up short and we couldn't hand back "
                + owed + " just now. An admin has been told."));
        return null;
    }

    /** Roll a rarity by the pack's live odds, then any issuable Mini of it, and issue its Card. */
    private String rollOdds(Player player, Pack.PackDef def) {
        Map<Rarity, List<MiniDef>> byRarity = new EnumMap<>(Rarity.class);
        for (MiniDef m : plugin.cards().issuable(candidates(def))) {
            if (def.odds(m.rarity()) > 0) {
                byRarity.computeIfAbsent(m.rarity(), r -> new ArrayList<>()).add(m);
            }
        }
        while (!byRarity.isEmpty()) {
            Map<Rarity, Double> odds = PackOdds.effective(def.rarityOdds(), byRarity.keySet());
            Rarity rarity = PackOdds.pick(odds, ThreadLocalRandom.current().nextDouble());
            if (rarity == null) {
                return null;
            }
            List<MiniDef> pool = byRarity.get(rarity);
            MiniDef m = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            CardService.IssueResult r = plugin.cards().issue(player, m.id());
            if (r.ok()) {
                return m.id();
            }
            pool.remove(m); // lost a race to the last one — drop it
            if (pool.isEmpty()) {
                byRarity.remove(rarity);
            }
        }
        return null;
    }

    /**
     * Weighted-roll a Card among the pool entries that can actually be issued, and issue it.
     * Filters first rather than rolling and re-rolling, so a mostly sold-out pool still gives
     * the Card that is left.
     */
    private String rollPool(Player player, List<Pack.PackEntry> pool) {
        List<Pack.PackEntry> live = new ArrayList<>();
        for (Pack.PackEntry e : pool) {
            if (plugin.cards().canIssue(plugin.miniService().def(e.miniId()))) {
                live.add(e);
            }
        }
        while (!live.isEmpty()) {
            String id = pickWeighted(live);
            if (id == null) {
                return null;
            }
            CardService.IssueResult r = plugin.cards().issue(player, id);
            if (r.ok()) {
                return id;
            }
            live.removeIf(e -> e.miniId().equals(id));
        }
        return null;
    }

    // ---- admin authoring (persist to config, reload live) ---------------------

    /** Create or replace a pack by id, then persist + reload. */
    public void upsert(Pack.PackDef pack) {
        List<Pack.PackDef> list = new ArrayList<>();
        boolean replaced = false;
        for (Pack.PackDef p : packs()) {
            if (p.id().equalsIgnoreCase(pack.id())) {
                list.add(pack);
                replaced = true;
            } else {
                list.add(p);
            }
        }
        if (!replaced) {
            list.add(pack);
        }
        save(list);
    }

    /** Remove a pack by id, then persist + reload. */
    public void delete(String id) {
        List<Pack.PackDef> list = new ArrayList<>();
        for (Pack.PackDef p : packs()) {
            if (!p.id().equalsIgnoreCase(id)) {
                list.add(p);
            }
        }
        save(list);
    }

    /** Serialize the full pack list to config.yml ({@code packs:}) and reload live. */
    public void save(List<Pack.PackDef> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Pack.PackDef p : list) {
            out.add(toRow(p));
        }
        plugin.writeConfig("packs", out);
        plugin.config().load();
    }

    /** One pack as its config row. The pool is written only when the pack uses one. */
    public static Map<String, Object> toRow(Pack.PackDef p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("display", p.displayName());
        m.put("price", p.price());
        m.put("price_tokens", p.priceTokens());
        m.put("count", p.cardCount());
        if (p.hasTag()) {
            m.put("tag", p.tag());
        }
        Map<String, Object> odds = new LinkedHashMap<>();
        for (Rarity r : Rarity.values()) {
            odds.put(r.name(), whole(p.odds(r)));
        }
        m.put("rarity_odds", odds);
        if (p.usesPool()) {
            List<Map<String, Object>> pool = new ArrayList<>();
            for (Pack.PackEntry e : p.pool()) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("card", e.miniId());
                em.put("weight", whole(e.weight()));
                pool.add(em);
            }
            m.put("pool", pool);
        }
        return m;
    }

    /** 62.0 is written as 62, so the file stays readable. */
    private static Object whole(double v) {
        return v == Math.rint(v) ? (Object) (long) v : (Object) v;
    }

    private String pickWeighted(List<Pack.PackEntry> pool) {
        double total = 0;
        for (Pack.PackEntry e : pool) {
            total += Math.max(0, e.weight());
        }
        if (total <= 0) {
            return null;
        }
        double r = ThreadLocalRandom.current().nextDouble() * total;
        for (Pack.PackEntry e : pool) {
            r -= Math.max(0, e.weight());
            if (r <= 0) {
                return e.miniId();
            }
        }
        return pool.get(pool.size() - 1).miniId();
    }
}
