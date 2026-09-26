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
     * <b>The one cap check.</b> A Card may be issued only while its type has Cards left AND the
     * Mini it prints has not minted out.
     *
     * <p>Card packs used to check only the first half, so a pack could hand over a Card for a
     * Mini that could no longer be printed — a dead Card, paid for with real in-game money.
     * Every issuer (packs, crates, the pity exchange, anything added later) now asks this one
     * question, and {@link #issue} asks it again itself, so a path that forgets still cannot get
     * a dead Card out.
     */
    public boolean canIssue(MiniDef def) {
        if (def == null) {
            return false;
        }
        if (plugin.miniService().mintedOut(def)) {
            return false;
        }
        CardSpec spec = plugin.miniService().cardSpec(def);
        if (spec.uncappedCards()) {
            return true;
        }
        try {
            return dao.issued(def.id()) < spec.cardCap();
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not read the Card tally for " + def.id() + ": " + e.getMessage());
            return false; // unknown is not "available": refusing costs a re-roll, a dead Card costs a player
        }
    }

    /** The subset of {@code pool} whose Cards can be issued right now (a fresh, mutable list). */
    public java.util.List<MiniDef> issuable(java.util.Collection<MiniDef> pool) {
        java.util.List<MiniDef> out = new java.util.ArrayList<>();
        if (pool != null) {
            for (MiniDef def : pool) {
                if (canIssue(def)) {
                    out.add(def);
                }
            }
        }
        return out;
    }

    /**
     * Issue one sealed Card of {@code id} to the player, if {@link #canIssue} allows it. A type
     * that is sold out — or whose Mini is minted out — quietly refuses (packs and crates re-roll
     * or skip) so the finite promise holds.
     */
    public IssueResult issue(Player player, String id) {
        MiniDef def = plugin.miniService().def(id);
        if (def == null) {
            return IssueResult.fail("No such Mini '" + id + "'.");
        }
        if (!canIssue(def)) {
            return IssueResult.fail(plugin.miniService().mintedOut(def)
                    ? "Every " + def.name() + " has been made — its Cards are gone."
                    : def.name() + " Cards are sold out.");
        }
        try {
            giveCard(player, id);
            dao.addIssued(id, 1);
            return new IssueResult(true, null, id);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to issue card " + id + ": " + e.getMessage());
            return IssueResult.fail("Card issue failed — try again.");
        }
    }

    /**
     * Admin: give a Card ignoring the cap check (still tallies issuance). The one path allowed
     * past {@link #canIssue}, and it says so in the log when it actually goes past it — an
     * admin testing a print should be able to, but it should never be a surprise later.
     */
    public IssueResult giveAdmin(Player player, String id) {
        MiniDef def = plugin.miniService().def(id);
        if (def == null) {
            return IssueResult.fail("No such Mini '" + id + "'.");
        }
        if (!canIssue(def)) {
            plugin.getLogger().info("Admin Card give: " + id + " to " + player.getName()
                    + " bypassed the cap check (" + (plugin.miniService().mintedOut(def)
                    ? "the Mini is minted out" : "its Cards are sold out") + ").");
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
