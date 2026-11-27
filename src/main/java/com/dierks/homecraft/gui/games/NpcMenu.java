package com.dierks.homecraft.gui.games;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.cpu.CpuChoice;
import com.dierks.homecraft.games.cpu.CpuPlayers;
import com.dierks.homecraft.games.cpu.CpuRoster;
import com.dierks.homecraft.gui.MenuStyle;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * The NPC players setting (27 slots), pictures first: NPCs on or off, and how they play in your games: easy (one
 * star), matched (a compass: near your own record) or any (each its own level).
 *
 * <pre>
 *   .  .  .  .  ?  .  .  .  .      4 what NPC players are
 *   .  ON OFF  .  E  M  A  .  .    10 on · 11 off · 13 easy · 14 matched · 15 any
 *   .  .  .  .  X  .  .  .  L      22 back · 26 the lock (a parent's or admin's screen; on a player's own: "set for you")
 * </pre>
 *
 * <p>Two ways in: the player's own (the Games screen's tile, {@code /hcm play npc}), which changes only their own and
 * shows a lock picture and changes nothing while a parent or admin has locked it; and a parent's or admin's for another
 * player (the admin screen's Player game settings), which can set it and lock it.
 */
public final class NpcMenu extends GameMenu {

    private static final int INFO = 4;
    private static final int ON = 10;
    private static final int OFF = 11;
    private static final int[] LEVELS = {13, 14, 15};
    private static final int LOCK = 26;

    private final UUID target;
    private final String targetName;
    private final boolean admin;

    /** The player's own setting. */
    public NpcMenu(HomeCraftManagement plugin, Player viewer, Runnable back) {
        this(plugin, viewer, viewer.getUniqueId(), viewer.getName(), false, back);
    }

    /**
     * @param target     whose setting it is
     * @param targetName their name
     * @param admin      a parent's or admin's screen for {@code target}: it may set and lock it
     */
    public NpcMenu(HomeCraftManagement plugin, Player viewer, UUID target, String targetName, boolean admin,
                   Runnable back) {
        super(plugin, null, viewer, back);
        this.target = target;
        this.targetName = targetName;
        this.admin = admin;
        init(27, Text.of(admin ? MenuStyle.title("NPC players", targetName) : MenuStyle.title("NPC players")));
    }

    private CpuPlayers npcs() {
        return admin ? CpuPlayers.any(plugin.games()) : CpuPlayers.of(plugin.games());
    }

    @Override
    protected void build() {
        fill();
        exitTile();
        CpuPlayers npcs = npcs();
        if (npcs == null) {
            set(13, Menus.icon(Material.GRAY_DYE, "&7NPC players are off on this server"), null);
            return;
        }
        CpuChoice.Prefs prefs = npcs.prefs(target);
        CpuChoice now = prefs.effective();
        boolean locked = prefs.locked();
        boolean canChange = admin || !locked;
        set(INFO, Menus.icon(Material.ARMOR_STAND, "&bNPC players &7" + CpuRoster.MARKER,
                "&7Friendly computer players. Their name", "&7always has a gear " + CpuRoster.MARKER + " after it.",
                "&7They play pretend: no tokens, no scores."), null);
        set(ON, Menus.glint(Menus.icon(Material.LIME_CONCRETE, "&aNPCs on",
                "&7They can join your table and races,", "&7and hang out in the Clubhouse."), now.on()),
                canChange ? e -> choose(new CpuChoice(true, now.level())) : null);
        set(OFF, Menus.glint(Menus.icon(Material.RED_CONCRETE, "&cNPCs off",
                "&7You won't see them anywhere."), !now.on()),
                canChange ? e -> choose(new CpuChoice(false, now.level())) : null);
        CpuChoice.Pref[] prefsInOrder = {CpuChoice.Pref.EASY, CpuChoice.Pref.MATCHED, CpuChoice.Pref.ANY};
        for (int i = 0; i < prefsInOrder.length; i++) {
            CpuChoice.Pref p = prefsInOrder[i];
            set(LEVELS[i], Menus.glint(levelTile(p), now.level() == p),
                    canChange ? e -> choose(new CpuChoice(now.on(), p)) : null);
        }
        if (admin) {
            set(LOCK, Menus.glint(Menus.icon(locked ? Material.IRON_BARS : Material.TRIPWIRE_HOOK,
                    locked ? "&cLocked for " + targetName : "&7Not locked",
                    locked ? "&7" + targetName + " can't change it." : "&7" + targetName + " can change it.",
                    "&eClick to " + (locked ? "unlock" : "lock") + " it"), locked), e -> {
                boolean ok = locked ? npcs.setLock(target, null) : npcs.setLock(target, now);
                said(ok);
            });
        } else if (locked) {
            set(LOCK, Menus.icon(Material.IRON_BARS, "&7Set for you",
                    "&7A grown-up chose this for you."), null);
        }
    }

    /** The level pictures: one star for easy, a compass for matched, three stars for any. */
    static ItemStack levelTile(CpuChoice.Pref p) {
        return switch (p) {
            case EASY -> Menus.count(Menus.icon(Material.NETHER_STAR, "&aEasy &e★",
                    "&7Every NPC in your game plays easy."), 1);
            case MATCHED -> Menus.icon(Material.COMPASS, "&eMatched",
                    "&7NPCs play about as well as you", "&7do against them.");
            case ANY -> Menus.count(Menus.icon(Material.NETHER_STAR, "&6Any &e★ ★★ ★★★",
                    "&7Each NPC plays at its own level."), 3);
        };
    }

    private void choose(CpuChoice c) {
        CpuPlayers npcs = npcs();
        if (npcs == null) {
            return;
        }
        boolean ok;
        if (admin) {
            ok = npcs.prefs(target).locked() ? npcs.setLock(target, c) : npcs.setFor(target, c);
        } else {
            ok = npcs.setOwn(target, c);
        }
        said(ok);
    }

    private void said(boolean ok) {
        if (ok && admin) {
            // a parent's or admin's change is logged, as its command is
            CpuPlayers npcs = npcs();
            CpuChoice.Prefs p = npcs == null ? CpuChoice.Prefs.NONE : npcs.prefs(target);
            plugin.getLogger().info("[Games] " + viewer.getName() + " (admin screen): NPC players for " + targetName
                    + " " + p.effective().text() + (p.locked() ? " (locked)" : ""));
        }
        if (ok) {
            Sounds.received(viewer);
        } else {
            Sounds.refused(viewer);
        }
        refresh();
    }

    /** The Games screen's tile for it: on or off at a glance. */
    public static ItemStack stateTile(HomeCraftManagement plugin, Player viewer) {
        CpuPlayers npcs = CpuPlayers.of(plugin.games());
        if (npcs == null) {
            return Menus.icon(Material.ARMOR_STAND, "&7NPC players &8- off on this server");
        }
        CpuChoice c = npcs.choice(viewer.getUniqueId());
        return Menus.icon(Material.ARMOR_STAND, "&bNPC players &7" + CpuRoster.MARKER + " "
                + (c.on() ? "&aon" : "&coff"), "&7Computer players for your games.", "&eClick to choose");
    }
}
