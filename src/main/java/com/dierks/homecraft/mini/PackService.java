package com.dierks.homecraft.mini;

import com.dierks.homecraft.HomeCraftManagement;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Card Packs (Phase 10, Part E) — the booster-pack money sink. Buying a pack charges
 * money and issues N weighted-random <b>Cards</b> (cap-aware, via {@link CardService})
 * for a reveal GUI. Pack types are admin-authored and live in config; this service
 * reads them and drives purchase + weighted rolling.
 */
public final class PackService {

    /** Result of opening a pack: the Card ids awarded (for the reveal GUI), or a failure. */
    public record OpenResult(boolean ok, String error, List<String> cardIds) {
        static OpenResult fail(String error) {
            return new OpenResult(false, error, List.of());
        }
    }

    /** How many times a single slot re-rolls past a card-capped-out type before giving up. */
    private static final int REROLL_LIMIT = 8;

    private final HomeCraftManagement plugin;

    public PackService(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    public List<Pack.PackDef> packs() {
        return plugin.config().packs().all();
    }

    public Pack.PackDef pack(String id) {
        return plugin.config().packs().byId(id);
    }

    /**
     * Buy and open a pack: charge the price, then roll {@code cardCount} weighted Cards
     * and issue them (cap-aware). A rolled card that's capped out re-rolls a bounded
     * number of times so the buyer still gets cards for their money. If the whole pool
     * is sold out, the purchase is refunded.
     */
    public OpenResult open(Player player, String packId) {
        Pack.PackDef def = pack(packId);
        if (def == null) {
            return OpenResult.fail("That pack doesn't exist.");
        }
        if (!def.isValid()) {
            return OpenResult.fail("That pack has no cards configured yet.");
        }
        if (def.price() > 0) {
            if (!plugin.economy().isEnabled()) {
                return OpenResult.fail("The economy is offline (no Vault).");
            }
            if (!plugin.economy().has(player, def.price())) {
                return OpenResult.fail("You can't afford " + plugin.economy().format(def.price()) + ".");
            }
            if (!plugin.economy().withdraw(player, def.price())) {
                return OpenResult.fail("Payment failed.");
            }
        }

        List<String> awarded = new ArrayList<>();
        for (int slot = 0; slot < def.cardCount(); slot++) {
            String id = rollAndIssue(player, def.pool());
            if (id != null) {
                awarded.add(id);
            }
        }
        if (awarded.isEmpty()) {
            // Everything was capped out — refund so the buyer is never charged for nothing.
            if (def.price() > 0) {
                plugin.economy().deposit(player, def.price());
            }
            return OpenResult.fail("That pack's cards are all sold out — you were refunded.");
        }
        return new OpenResult(true, null, awarded);
    }

    /** Weighted-roll a card and issue it; re-roll past a capped-out type up to the limit. */
    private String rollAndIssue(Player player, List<Pack.PackEntry> pool) {
        for (int attempt = 0; attempt < REROLL_LIMIT; attempt++) {
            String id = pickWeighted(pool);
            if (id == null) {
                return null;
            }
            CardService.IssueResult r = plugin.cards().issue(player, id);
            if (r.ok()) {
                return id;
            }
            // capped out (or unknown) — try again with a fresh weighted pick
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
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("display", p.displayName());
            m.put("price", p.price());
            m.put("count", p.cardCount());
            List<Map<String, Object>> pool = new ArrayList<>();
            for (Pack.PackEntry e : p.pool()) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("card", e.miniId());
                em.put("weight", e.weight());
                pool.add(em);
            }
            m.put("pool", pool);
            out.add(m);
        }
        plugin.getConfig().set("packs", out);
        plugin.saveConfig();
        plugin.config().load();
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
