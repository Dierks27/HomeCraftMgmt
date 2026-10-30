package com.dierks.homecraft.gui.games.trial;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.gui.games.ScoresMenu;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * "Challenge someone" (0.38.4): everyone online the viewer can see, 36 a page, a green name for anyone who can be
 * challenged now and a grey one with why not in the NAME ("in a party", "in a game", "invites off", "busy":
 * {@code PartyRaces.challengeNote}), because Bedrock shows lore only on a long press - the Trade picker's shape,
 * not the invite picker's, which hides people silently. Paper tiles on Bedrock, heads on Java. A tap sends the
 * challenge ({@code PartyRaces.sendChallenge}); the arrows on 45/53, the way out on 49.
 */
public final class ChallengePickerMenu extends GameMenu {

    private static final int GRID_START = 9;
    private static final int GRID_SIZE = 36;

    private final TimeTrials trials;
    private final int page;

    public ChallengePickerMenu(HomeCraftManagement plugin, TimeTrials trials, Player viewer, Runnable back) {
        this(plugin, trials, viewer, back, 0);
    }

    private ChallengePickerMenu(HomeCraftManagement plugin, TimeTrials trials, Player viewer, Runnable back, int page) {
        super(plugin, trials, viewer, back);
        this.trials = trials;
        this.page = Math.max(0, page);
        init(54, Text.of("&6Challenge someone"));
    }

    /**
     * The lore under a Challenge tile (the course screen's and this screen's header): where the other player's
     * Yes takes them. Into the Clubhouse while it is running ({@code PartyRaces.yesToClubhouse}); with it off,
     * onto their party screen, as any party invite.
     */
    static String[] challengeLore(boolean clubOpen) {
        return clubOpen
                ? new String[]{"&7Pick a player who's free.", "&7They tap Yes and meet you", "&7in the Clubhouse."}
                : new String[]{"&7Pick a player who's free.", "&7They tap Yes and join", "&7your party screen."};
    }

    @Override
    protected void build() {
        fill();
        set(4, Menus.icon(Material.GOLDEN_BOOTS, "&6Challenge someone",
                challengeLore(com.dierks.homecraft.games.clubhouse.Clubhouse.offered(plugin.games()))), null);
        List<Player> people = people();
        int p = clampPage(page, people.size());
        int from = p * GRID_SIZE;
        List<Player> onPage = people.subList(Math.min(from, people.size()), Math.min(people.size(), from + GRID_SIZE));
        if (onPage.isEmpty()) {
            set(22, Menus.icon(Material.PAPER, "&7No one else is online", "&8Try again when they are."), null);
        }
        boolean bedrock = Bedrock.is(viewer);
        for (int i = 0; i < onPage.size(); i++) {
            Player r = onPage.get(i);
            String note = trials.party().challengeNote(viewer, r);
            String name = tileName(r.getName(), note);
            String[] lore = note == null ? new String[]{"&eTap to challenge"} : new String[0];
            ItemStack icon = bedrock ? Menus.icon(Material.PAPER, name, lore) : ScoresMenu.playerHead(r, name, lore);
            UUID id = r.getUniqueId();
            set(GRID_START + i, icon, note == null ? e -> pick(id) : null);
        }
        if (p > 0) {
            set(45, Menus.icon(Material.ARROW, "&fPrevious page"),
                    e -> new ChallengePickerMenu(plugin, trials, viewer, back, p - 1).open(viewer));
        }
        if (p < pages(people.size()) - 1) {
            set(53, Menus.icon(Material.ARROW, "&fNext page"),
                    e -> new ChallengePickerMenu(plugin, trials, viewer, back, p + 1).open(viewer));
        }
        exitTile();
    }

    /**
     * The tile is a snapshot: the player is looked up and checked again on the tap. The picker closes (outside
     * the Clubhouse the party screen takes its place); when nothing was sent after all (Invites refused it: the
     * host heard why) the picker comes back, its grey names saying why, so the host can pick someone else.
     */
    private void pick(UUID id) {
        Player target = Bukkit.getPlayer(id);
        if (target == null || !target.isOnline() || trials.party().challengeNote(viewer, target) != null) {
            viewer.sendMessage(Text.of("&cThey can't be challenged right now."));
            Sounds.refused(viewer);
            refresh();
            return;
        }
        viewer.closeInventory();
        if (!trials.party().sendChallenge(viewer, target, back)) {
            new ChallengePickerMenu(plugin, trials, viewer, back, page).open(viewer);
        }
    }

    /** Everyone the picker lists, by name: online, not the viewer, and seen by the viewer. */
    private List<Player> people() {
        List<Player> out = new ArrayList<>();
        for (Player r : Bukkit.getOnlinePlayers()) {
            if (!r.getUniqueId().equals(viewer.getUniqueId()) && viewer.canSee(r)) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** "&amp;a<name>" for someone who can be challenged, else "&amp;7<name> &amp;8- <why>" (the Trade picker's shape). */
    static String tileName(String name, String note) {
        return note == null ? "&a" + name : "&7" + name + " &8- " + note;
    }

    static int pages(int count) {
        return Math.max(1, (count + GRID_SIZE - 1) / GRID_SIZE);
    }

    static int clampPage(int page, int count) {
        return Math.max(0, Math.min(page, pages(count) - 1));
    }
}
