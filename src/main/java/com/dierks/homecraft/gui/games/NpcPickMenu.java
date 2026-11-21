package com.dierks.homecraft.gui.games;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.cpu.CpuLevel;
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.CpuPlayers;
import com.dierks.homecraft.games.cpu.CpuRoster;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

/**
 * "Play an NPC" (27 slots): the NPC players to pick from, a picture each with its stars as the count, so a child can
 * pick by the number of stars alone. The stars are the level it will play at against this player in this game (one
 * star for everyone when they want easy).
 *
 * <pre>
 *   .  .  .  .  ?  .  .  .  .
 *   .  N  N  N  N  N  N  .  .      10-15 the NPCs
 *   .  .  .  .  X  .  .  .  .      22 back
 * </pre>
 */
public final class NpcPickMenu extends GameMenu {

    private final Consumer<CpuPlayer> chosen;

    public NpcPickMenu(HomeCraftManagement plugin, Game game, Player viewer, Runnable back,
                       Consumer<CpuPlayer> chosen) {
        super(plugin, game, viewer, back);
        this.chosen = chosen;
        init(27, Text.of("&bPlay an NPC"));
    }

    @Override
    protected void build() {
        fill();
        exitTile();
        CpuPlayers npcs = CpuPlayers.of(plugin.games());
        if (npcs == null) {
            return;
        }
        set(4, Menus.icon(Material.ARMOR_STAND, "&bPick an NPC to play &7" + CpuRoster.MARKER,
                "&7More stars: it plays better.", "&7Just for fun: no tokens, no scores."), null);
        List<CpuPlayer> all = CpuRoster.ALL;
        for (int i = 0; i < all.size() && i < 7; i++) {
            CpuPlayer c = all.get(i);
            CpuLevel plays = npcs.levelAgainst(c, viewer.getUniqueId(), game == null ? "" : game.id());
            set(10 + i, Menus.count(Menus.icon(Material.ARMOR_STAND, CpuRoster.label(npcs.name(c), plays),
                    "&eClick to play " + npcs.name(c)), plays.stars), e -> chosen.accept(c));
        }
    }
}
