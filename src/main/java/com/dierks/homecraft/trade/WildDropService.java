package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.mini.Grade;
import com.dierks.homecraft.mini.Loot;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.mini.MiniService;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Rolls Wild-Drop loot: on a matching trigger it rolls each bound source's tiny
 * chance and, on a hit, picks a Mini from the source's pool (a weighted list, or a
 * tag pool weighted by rarity), rolls a grade + Shiny using the Printer's odds (or
 * the source's override), and mints the finished Mini through the standard mint
 * pipeline — so drops respect the cap, count toward circulation, and announce
 * themselves. Minting stays the single source of truth; a drop is just another
 * mint path.
 */
public final class WildDropService {

    private final HomeCraftManagement plugin;
    private volatile Set<Material> trackedBlocks; // cached; rebuilt on reload

    public WildDropService(HomeCraftManagement plugin) {
        this.plugin = plugin;
    }

    /** Drop the cached tracked-block set (call on config reload). */
    public void invalidate() {
        trackedBlocks = null;
    }

    /**
     * The named materials at least one BLOCK_BREAK source watches — the only blocks
     * we anti-farm-track and roll on (a {@code *} block source is intentionally not
     * rolled, to avoid an unbounded placed-block ledger).
     */
    public boolean isTrackedBlock(Material material) {
        Set<Material> set = trackedBlocks;
        if (set == null) {
            set = new HashSet<>();
            for (Loot.LootSource s : plugin.config().miniLoot().sourcesFor(Loot.Trigger.BLOCK_BREAK)) {
                if (s.match() == null || s.match().equals("*")) {
                    continue;
                }
                Material m = Material.matchMaterial(s.match().toUpperCase());
                if (m != null) {
                    set.add(m);
                }
            }
            trackedBlocks = set;
        }
        return set.contains(material);
    }

    /**
     * Roll all sources bound to {@code trigger} whose match accepts {@code key}, and
     * mint the winners to {@code player}. Returns the number of Minis dropped.
     */
    public int roll(Player player, Loot.Trigger trigger, String key) {
        if (!plugin.sandbox().allowed(player.getWorld())) {
            return 0; // silent: a chat line per broken block would be spam
        }
        Loot.MiniLoot loot = plugin.config().miniLoot();
        List<Loot.LootSource> sources = loot.sourcesFor(trigger);
        if (sources.isEmpty()) {
            return 0;
        }
        int dropped = 0;
        for (Loot.LootSource source : sources) {
            if (!source.matches(key) || source.chancePercent() <= 0) {
                continue;
            }
            double roll = ThreadLocalRandom.current().nextDouble() * 100.0; // percent space
            if (roll >= source.chancePercent()) {
                continue;
            }
            MiniDef def = pick(source);
            if (def == null) {
                continue; // empty pool, or everything in it is minted out — the finite promise holds
            }
            Grade grade = rollGrade(source, def);
            boolean shiny = rollShiny(source);
            MiniService.Minted m = plugin.miniService().mintFound(player, def.id(), grade, shiny);
            if (!m.ok()) {
                continue;
            }
            dropped++;
            player.sendMessage(Text.of("&b✦ You found a &f" + def.name() + " " + grade.symbol()
                    + (shiny ? " &f✦Shiny" : "") + " &b" + trigger.verb() + "! &7(Mint #" + m.mintNumber() + ")"));
            plugin.announce().found(player, def, m.item(), trigger.verb());
        }
        return dropped;
    }

    /** Pick a mintable Mini from a source's pool (list or tag); null if nothing is available. */
    public MiniDef pick(Loot.LootSource source) {
        MiniService minis = plugin.miniService();
        if (source.usesTag()) {
            return minis.pickByRarity(minis.poolFromTag(source.tag()));
        }
        Loot.LootList list = plugin.config().miniLoot().listById(source.listId());
        if (list == null || list.entries().isEmpty()) {
            return null;
        }
        // Weighted pick over the entries that can still mint.
        double total = 0;
        for (Loot.LootEntry e : list.entries()) {
            MiniDef d = minis.def(e.miniId());
            if (d != null && !minis.mintedOut(d)) {
                total += Math.max(0, e.weight());
            }
        }
        if (total <= 0) {
            return null;
        }
        double r = ThreadLocalRandom.current().nextDouble() * total;
        MiniDef last = null;
        for (Loot.LootEntry e : list.entries()) {
            MiniDef d = minis.def(e.miniId());
            if (d == null || minis.mintedOut(d)) {
                continue;
            }
            last = d;
            r -= Math.max(0, e.weight());
            if (r <= 0) {
                return d;
            }
        }
        return last;
    }

    /** The source's grade override if set, else the Mini's own Printer odds. */
    public Grade rollGrade(Loot.LootSource source, MiniDef def) {
        MiniService minis = plugin.miniService();
        if (source.gradeWeights() != null && !source.gradeWeights().isEmpty()) {
            return minis.rollGrade(source.gradeWeights());
        }
        return minis.rollGrade(minis.cardSpec(def));
    }

    /** The source's Shiny chance if set, else the global {@code minis.loot.shiny_percent}. */
    public boolean rollShiny(Loot.LootSource source) {
        double pct = source.shinyPercent() != null ? source.shinyPercent() : plugin.config().miniLoot().shinyPercent();
        return plugin.miniService().rollShiny(pct);
    }
}
