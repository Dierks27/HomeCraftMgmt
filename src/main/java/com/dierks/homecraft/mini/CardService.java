package com.dierks.homecraft.mini;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.storage.CardDao;
import com.dierks.homecraft.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;

/**
 * The Card economy (Phase 9, Part A): issues sealed Cards for Mini types. Cards —
 * not Minis — are what Wild Drops, the Arcade, quests, and Card Packs now hand out;
 * a Card is taken to a Printer to print a graded Mini (the single mint source).
 *
 * <p>Issuance is cap-aware: a type may set a finite {@code cardCap} (rare types) or
 * be uncapped (commons). The per-type running tally lives in {@link CardDao}
 * ({@code card_counts}), independent of the Mini mint tally.
 */
public final class CardService {

    /** Outcome of a card issue: ok + the type given, or a reason it couldn't be. */
    public record IssueResult(boolean ok, String error, String miniId) {
        static IssueResult fail(String error) {
            return new IssueResult(false, error, null);
        }
    }

    private final HomeCraftManagement plugin;
    private final CardDao dao;

    public CardService(HomeCraftManagement plugin, CardDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    /** How many Cards of a type have been issued so far (for tooltips / admin). */
    public long issued(String id) {
        try {
            return dao.issued(id);
        } catch (SQLException e) {
            return 0;
        }
    }

    /**
     * Issue one sealed Card of {@code id} to the player, enforcing the card cap. A
     * capped-out type quietly refuses (Wild Drops / crates re-roll or skip) so the
     * finite promise holds. Uncapped (common) types never refuse.
     */
    public IssueResult issue(Player player, String id) {
        MiniDef def = plugin.miniService().def(id);
        if (def == null) {
            return IssueResult.fail("No such Mini '" + id + "'.");
        }
        CardSpec spec = plugin.miniService().cardSpec(def);
        try {
            if (!spec.uncappedCards()) {
                long already = dao.issued(id);
                if (already >= spec.cardCap()) {
                    return IssueResult.fail(def.name() + " cards are sold out.");
                }
            }
            giveCard(player, id);
            dao.addIssued(id, 1);
            return new IssueResult(true, null, id);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to issue card " + id + ": " + e.getMessage());
            return IssueResult.fail("Card issue failed — try again.");
        }
    }

    /** Admin: give a Card ignoring the cap (still tallies issuance). */
    public IssueResult giveAdmin(Player player, String id) {
        MiniDef def = plugin.miniService().def(id);
        if (def == null) {
            return IssueResult.fail("No such Mini '" + id + "'.");
        }
        giveCard(player, id);
        try {
            dao.addIssued(id, 1);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to tally admin card " + id + ": " + e.getMessage());
        }
        return new IssueResult(true, null, id);
    }

    private void giveCard(Player player, String id) {
        ItemStack card = plugin.miniService().cardFor(id);
        if (card == null) {
            return;
        }
        player.getInventory().addItem(card).values()
                .forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
    }

    /** Announce a wild Card drop (kept consistent across drop sources). */
    public void announceDrop(Player player, String id) {
        MiniDef def = plugin.miniService().def(id);
        String name = def != null ? def.name() : id;
        player.sendMessage(Text.of("&b❐ A wild &f" + name + " Card &bdropped! &7Print it at a Printer."));
    }
}
