package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.chance.coinflip.CoinFlip;
import com.dierks.homecraft.games.cpu.CpuPlayer;
import com.dierks.homecraft.games.cpu.CpuPlayers;
import com.dierks.homecraft.games.cpu.CpuRoster;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A pretend Coin Flip with an NPC player (27 slots): your side is gold, the NPC's blue, the coin turns and lands. It is
 * pretend, and the screen says so: no tokens move, nothing is written, it counts toward no limit. A real Coin Flip
 * against the computer would be a new game of chance with its own give-back, so there is none.
 *
 * <pre>
 *   .  .  .  .  R  .  .  .  .      4 what happened (and "Pretend: no tokens move")
 *   .  .  G  .  C  .  B  .  .      11 you · 13 the coin (tap to flip) · 15 the NPC
 *   .  .  .  .  X  .  .  .  .      22 back
 * </pre>
 */
public final class CoinFlipNpcMenu extends GameMenu {

    private static final int RESULT = 4;
    private static final int YOU = 11;
    private static final int COIN = 13;
    private static final int THEM = 15;
    /** Turns of the coin, a few ticks apart. */
    private static final int FRAMES = 10;

    private final CpuPlayer npc;
    private final String label;
    private int frame = -1;
    private boolean youWin;
    private boolean landed;
    private boolean ticking;

    public CoinFlipNpcMenu(HomeCraftManagement plugin, CoinFlip game, Player viewer, Runnable back, CpuPlayer npc) {
        super(plugin, game, viewer, back);
        this.npc = npc;
        CpuPlayers npcs = CpuPlayers.any(plugin.games());
        this.label = npcs == null ? npc.name() : npcs.label(npc);
        init(27, Text.of("&5Pretend Coin Flip"));
    }

    @Override
    protected void build() {
        fill();
        exitTile();
        set(YOU, Menus.icon(Material.GOLD_BLOCK, "&6You &7- gold"), null);
        set(THEM, Menus.icon(Material.ARMOR_STAND, label + " &7- blue"), null);
        if (frame < 0) {
            set(RESULT, Menus.icon(Material.PAPER, "&dPretend: no tokens move", "&7Just for fun with an NPC."), null);
            set(COIN, Menus.glint(Menus.icon(Material.SUNFLOWER, "&eFlip the coin!", "&7Gold: you. Blue: "
                    + label + "&7."), true), e -> flip());
            return;
        }
        boolean gold = landed ? youWin : frame % 2 == 0;
        set(COIN, Menus.icon(gold ? Material.GOLD_BLOCK : Material.LAPIS_BLOCK, landed ? "&eIt landed!" : "&7..."),
                landed ? e -> flip() : null);
        if (landed) {
            set(RESULT, Menus.glint(Menus.icon(youWin ? Material.NETHER_STAR : Material.PAPER,
                    youWin ? "&aGold! You won the pretend flip." : "&7Blue! " + label + " &7won the pretend flip.",
                    "&dPretend: no tokens moved.", "&eTap the coin to flip it"), youWin), null);
        } else {
            set(RESULT, Menus.icon(Material.PAPER, "&dPretend: no tokens move"), null);
        }
    }

    private void flip() {
        if (frame >= 0 && !landed) {
            return;
        }
        youWin = ThreadLocalRandom.current().nextBoolean();
        landed = false;
        frame = 0;
        refresh();
        if (ticking) {
            return; // the one ticker turns the new flip too
        }
        ticking = true;
        ticker(3, () -> {
            if (landed || frame < 0) {
                return;
            }
            frame++;
            viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f, 1.6f);
            if (frame >= FRAMES) {
                landed = true;
                viewer.playSound(viewer.getLocation(), youWin ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_WOOD_PLACE,
                        0.6f, 1.2f);
            }
            refresh();
        });
    }

    /** The NPC that flips with {@code viewer}: one picked at random. */
    public static CpuPlayer pick() {
        return CpuRoster.ALL.get(ThreadLocalRandom.current().nextInt(CpuRoster.ALL.size()));
    }
}
