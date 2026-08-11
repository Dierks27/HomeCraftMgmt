package com.dierks.homecraft.trade;

import com.dierks.homecraft.HomeCraftManagement;
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
 * chance and, on a hit, mints a weighted-random Mini through the standard mint
 * pipeline — so drops respect the cap (stop when minted out) and count toward
 * circulation. Minting stays the single source of truth; a drop is just another
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
            Loot.LootList list = loot.listById(source.listId());
            if (list == null || list.entries().isEmpty()) {
                continue;
            }
            String miniId = pickWeighted(list);
            if (miniId == null) {
                continue;
            }
            MiniService.MintResult r = plugin.miniService().mintWild(player, miniId);
            if (r.ok()) {
                dropped++;
                MiniDef def = plugin.miniService().def(miniId);
                String name = def != null ? def.name() : miniId;
                player.sendMessage(Text.of("&d✦ A wild &f" + name + " &dMini dropped! &7(Mint #"
                        + r.mintNumber() + ")"));
            }
            // If minted out, silently skip — the finite promise holds.
        }
        return dropped;
    }

    private String pickWeighted(Loot.LootList list) {
        double total = 0;
        for (Loot.LootEntry e : list.entries()) {
            total += Math.max(0, e.weight());
        }
        if (total <= 0) {
            return null;
        }
        double r = ThreadLocalRandom.current().nextDouble() * total;
        for (Loot.LootEntry e : list.entries()) {
            r -= Math.max(0, e.weight());
            if (r <= 0) {
                return e.miniId();
            }
        }
        return list.entries().get(list.entries().size() - 1).miniId();
    }
}
