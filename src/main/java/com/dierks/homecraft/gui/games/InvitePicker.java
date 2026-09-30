package com.dierks.homecraft.gui.games;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Breaks;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.storage.GamesDao.BreakRow;
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
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Pick another player for a two-player game: Coin Flip, or Connect Four / Tic-Tac-Toe against a
 * friend (spec §3.1.8, §5.7). One head per player who can be asked right now, 36 a page.
 *
 * <p>Only players who can actually take the invite appear, and the picker never says why anyone
 * is missing — a parent may have switched something off, and that is between them. Besides the
 * game's own test ({@code eligible}: range, stake, cooldown), the picker itself always leaves out
 * the viewer, anyone without {@code hcm.games.play}, anyone who doesn't take this game's invites
 * or already has one waiting, and — for a game of chance — anyone without
 * {@code hcm.games.chance} or on a break (or whose break can't be read). Clicking a head hands the
 * player to the game, which re-checks everything before any token moves.
 */
public final class InvitePicker extends GameMenu {

    private static final int GRID = 9;

    private final Predicate<Player> eligible;
    private final Consumer<Player> chosen;
    private final int page;

    public InvitePicker(HomeCraftManagement plugin, Game game, Player viewer, Predicate<Player> eligible,
                        Consumer<Player> chosen, int page, Runnable back) {
        super(plugin, game, viewer, back);
        this.eligible = eligible;
        this.chosen = chosen;
        this.page = Math.max(0, page);
        init(54, Text.of("&bPick a player"));
    }

    /**
     * Whether the picker may list someone, given what it knows about them (pure, tested). The
     * game's own {@code eligible} test is asked separately, and only when this says yes.
     */
    static boolean listable(boolean self, boolean canPlay, boolean chanceGame, boolean canChance,
                            boolean breakReadable, boolean paused, boolean takesInvites, boolean hasPending) {
        if (self || !canPlay || !takesInvites || hasPending) {
            return false;
        }
        return !chanceGame || (canChance && breakReadable && !paused);
    }

    @Override
    protected void build() {
        fill();
        exitTile();
        set(4, Menus.icon(Material.WRITABLE_BOOK, "&ePick a player",
                "&7Ask someone to play " + game.name() + ".", "&7They say yes or no."), null);
        List<Player> players = candidates();
        int p = GamesMenu.clampPage(page, players.size());
        List<Player> onPage = GamesMenu.slice(players, p);
        if (onPage.isEmpty()) {
            set(22, Menus.icon(Material.PAPER, "&7No one to ask right now"), null);
        }
        boolean bedrock = Bedrock.is(viewer);
        for (int i = 0; i < onPage.size(); i++) {
            Player target = onPage.get(i);
            String name = "&a" + target.getName();
            ItemStack icon = bedrock ? Menus.icon(Material.PAPER, name, "&eClick to ask")
                    : ScoresMenu.playerHead(target, name, "&eClick to ask");
            UUID id = target.getUniqueId();
            set(GRID + i, icon, e -> pick(id));
        }
        if (p > 0) {
            set(45, Menus.icon(Material.ARROW, "&fPrevious page"),
                    e -> new InvitePicker(plugin, game, viewer, eligible, chosen, p - 1, back).open(viewer));
        }
        if (p < GamesMenu.pages(players.size()) - 1) {
            set(53, Menus.icon(Material.ARROW, "&fNext page"),
                    e -> new InvitePicker(plugin, game, viewer, eligible, chosen, p + 1, back).open(viewer));
        }
    }

    /** Everyone who may be asked, by name. */
    private List<Player> candidates() {
        GamesService games = plugin.games();
        List<Player> out = new ArrayList<>();
        if (games == null) {
            return out;
        }
        boolean chance = game.kind() == GameKind.CHANCE;
        long now = plugin.clock().nowMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (fits(games, p, chance, now)) {
                out.add(p);
            }
        }
        out.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private boolean fits(GamesService games, Player p, boolean chance, long now) {
        UUID id = p.getUniqueId();
        boolean self = id.equals(viewer.getUniqueId());
        if (self) {
            return false;
        }
        boolean readable = true;
        boolean paused = false;
        if (chance) {
            Breaks breaks = plugin.breaks();
            BreakRow row = null;
            try {
                row = breaks == null ? null : breaks.row(id);
            } catch (RuntimeException e) {
                // unreadable: left out, like any break that can't be read (fails closed)
            }
            readable = row != null;
            paused = row != null && Breaks.paused(row, now);
        }
        boolean takes = games.guard(game, () -> games.invites().accepts(id, game.id()), false);
        boolean pending = games.guard(game, () -> games.invites().pending(id) != null, true);
        if (!listable(false, p.hasPermission("hcm.games.play"), chance, p.hasPermission("hcm.games.chance"),
                readable, paused, takes, pending)) {
            return false;
        }
        return eligible == null || games.guard(game, () -> eligible.test(p), false);
    }

    /** Hand the player to the game — if they are still there to ask. */
    private void pick(UUID id) {
        Player target = Bukkit.getPlayer(id);
        GamesService games = plugin.games();
        if (target == null || !target.isOnline() || games == null
                || !fits(games, target, game.kind() == GameKind.CHANCE, plugin.clock().nowMillis())) {
            viewer.sendMessage(Text.of("&cThey can't be asked right now."));
            Sounds.refused(viewer);
            refresh();
            return;
        }
        chosen.accept(target);
    }
}
