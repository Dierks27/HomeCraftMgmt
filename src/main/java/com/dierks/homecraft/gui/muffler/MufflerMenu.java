package com.dierks.homecraft.gui.muffler;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.ConfirmMenu;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.muffler.MuffleLevel;
import com.dierks.homecraft.muffler.Muffler;
import com.dierks.homecraft.muffler.MufflerPos;
import com.dierks.homecraft.muffler.SoundGroups;
import com.dierks.homecraft.muffler.SoundMufflerService;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Sound Muffler's screen. Opens by itself the moment one is placed, and on right-click after.
 *
 * <pre>
 *  row 1  [muffler] [status] [power] [range] [quieter] [show area] [heard] [find] [single]
 *  row 2  animals
 *  row 3  villages and monsters
 *  row 4  more monsters, other players, footsteps, fishing
 *  row 5  machines and blocks
 *  row 6  .  .  [what it can't hush]  .  [close]  .  [clear all]  .  .
 * </pre>
 *
 * Every group button steps Normal → Quieter → Silent (right-click steps back). Anyone may open a
 * muffler and see what it hushes; only its owner or an admin may change it. Each click re-reads
 * the muffler, so two people with the menu open never undo each other's change.
 */
public final class MufflerMenu extends Menu {

    private final Player player;
    private final MufflerPos pos;

    public MufflerMenu(HomeCraftManagement plugin, Player player, MufflerPos pos) {
        super(plugin);
        this.player = player;
        this.pos = pos;
        init(54, Text.of("&8Sound Muffler"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        SoundMufflerService svc = plugin.soundMufflers();
        Muffler m = svc == null ? null : svc.at(pos);
        if (m == null) {
            set(22, Menus.icon(Material.BARRIER, "&cThis muffler is gone", "&7It was broken or moved."), null);
            set(49, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
            return;
        }
        boolean edit = svc.canEdit(player, m);

        set(0, info(svc, m, edit), null);
        if (!svc.working()) {
            set(1, Menus.icon(Material.RED_CONCRETE, "&cNot hushing anything right now",
                    "&7" + svc.problem(), "&7Your choices are saved and start", "&7working as soon as that's fixed."), null);
        }
        set(2, m.enabled()
                ? Menus.icon(Material.LIME_DYE, "&aMuffler: ON", lore("&7Hushing what you picked below.",
                        edit ? "&8Click to switch it off." : null))
                : Menus.icon(Material.GRAY_DYE, "&7Muffler: OFF", lore("&7Everything plays normally.",
                        "&7Your choices are kept.", edit ? "&8Click to switch it on." : null)),
                e -> change(cur -> cur.withEnabled(!cur.enabled())));
        set(3, Menus.count(Menus.icon(Material.SPYGLASS, "&eRange: &f" + m.radius() + " &eblock" + (m.radius() == 1 ? "" : "s"),
                lore("&7Hushes sounds made within &f" + m.radius() + " &7block" + (m.radius() == 1 ? "" : "s"),
                "&7of the muffler, in every direction —",
                "&7a box &f" + m.boxSize() + "×" + m.boxSize() + "×" + m.boxSize() + "&7.",
                "&7Everyone hears them hushed,",
                "&7wherever they're standing.",
                "",
                edit ? "&8Left-click: bigger · Right-click: smaller" : null,
                "&8Most allowed: " + svc.maxRadius())), m.radius()),
                e -> change(cur -> cur.withRadius(Muffler.stepRadius(cur.radius(), svc.maxRadius(), !e.isRightClick()))));
        set(4, Menus.count(Menus.icon(Material.NOTE_BLOCK, "&eQuieter = &f" + m.quietPercent() + "% &evolume",
                lore("&7How loud a sound set to &eQuieter",
                "&7still plays.",
                "",
                edit ? "&8Left-click: quieter · Right-click: louder" : null,
                "&8Choices: 50% · 25% · 10%")), m.quietPercent()),
                e -> change(cur -> cur.withQuietPercent(Muffler.stepQuiet(cur.quietPercent(), !e.isRightClick()))));
        set(5, Menus.icon(Material.GLOWSTONE_DUST, "&eShow the area",
                "&7Draws the muffler's box in the air",
                "&7for 10 seconds."), e -> {
            Muffler cur = current();
            if (cur == null) {
                return;
            }
            e.getWhoClicked().closeInventory();
            if (!player.getWorld().getName().equals(pos.world())) {
                return;
            }
            svc.showArea(player, cur);
            player.sendMessage(Text.of("&7Showing the muffler's box (&f" + cur.boxSize() + "×" + cur.boxSize()
                    + "×" + cur.boxSize() + "&7) for 10 seconds."));
        });
        int heardCount = svc.heardBy(m).size();
        set(6, Menus.icon(Material.SCULK_SENSOR, "&bHeard nearby",
                "&7Sounds made in the box in the",
                "&7last 10 minutes, newest first.",
                "&7Click one to hush just that sound.",
                "",
                "&8" + heardCount + " sound" + (heardCount == 1 ? "" : "s") + " heard"),
                e -> new SoundListMenu(plugin, player, pos, SoundListMenu.Mode.HEARD, "").open(player));
        set(7, Menus.icon(Material.NAME_TAG, "&bFind a sound",
                "&7Type part of a sound's name —",
                "&7&fchicken&7, &fpiston&7, &fdoor&7, &fvillager trade&7 —",
                "&7and pick from every sound in the game."), e ->
                plugin.chatPrompts().prompt(player, "Which sound? Type part of its name (e.g. chicken, piston, door):",
                        input -> new SoundListMenu(plugin, player, pos, SoundListMenu.Mode.SEARCH, input).open(player)));
        int picks = m.sounds().size();
        set(8, Menus.icon(Material.BOOK, "&bSingle sounds &7(" + picks + ")",
                "&7The sounds picked one at a time.",
                "&7A single pick beats its group, so you",
                "&7can hush Villagers but keep their",
                "&7trading sound (&aAlways play&7)."),
                e -> new SoundListMenu(plugin, player, pos, SoundListMenu.Mode.PICKS, "").open(player));

        for (SoundGroups.SoundGroup g : SoundGroups.all()) {
            set(g.slot(), groupIcon(g, m, edit), e -> change(cur -> {
                MuffleLevel now = cur.groups().get(g.id());
                MuffleLevel next = e.isRightClick() ? MuffleLevel.previousForGroup(now) : MuffleLevel.nextForGroup(now);
                return cur.withGroup(g.id(), next);
            }));
        }

        set(47, Menus.icon(Material.WRITABLE_BOOK, "&eWhat can't a muffler hush?",
                "&7Some sounds are made by each player's",
                "&7own game, not the server — no plugin",
                "&7can reach them:",
                "&8 rain and thunder, music and jukeboxes,",
                "&8 furnaces and campfires crackling,",
                "&8 portals humming, lava popping,",
                "&8 minecarts rolling, bees buzzing in",
                "&8 flight, and your OWN footsteps,",
                "&8 clicks and pickups.",
                "&7Anything in &bHeard nearby &7can be hushed."), null);
        set(49, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
        if (edit && m.ruleCount() > 0) {
            set(51, Menus.icon(Material.MILK_BUCKET, "&cClear every choice",
                    "&7Back to hushing nothing.",
                    "&7Power, range and the Quieter",
                    "&7volume stay as they are."), e -> confirmClear());
        }
    }

    private ItemStack info(SoundMufflerService svc, Muffler m, boolean edit) {
        String owner = ownerName(m.owner());
        List<String> lore = new ArrayList<>();
        lore.add("&7Owner: &f" + owner);
        lore.add("&7Range: &f" + m.radius() + " &7(" + m.boxSize() + "×" + m.boxSize() + "×" + m.boxSize() + ")");
        lore.add("&7Choices: &f" + m.groups().size() + " group" + (m.groups().size() == 1 ? "" : "s")
                + "&7, &f" + m.sounds().size() + " single sound" + (m.sounds().size() == 1 ? "" : "s"));
        lore.add("");
        lore.add(!svc.working() ? "&cNot hushing anything — see the red tile."
                : m.enabled() ? "&aWorking." : "&7Switched off.");
        if (!edit) {
            lore.add("&8Only " + owner + " can change this muffler.");
        }
        Material block = plugin.config().soundMuffler().block().material();
        return Menus.icon(block.isItem() ? block : Material.WHITE_WOOL, "&fSound Muffler",
                lore.toArray(new String[0]));
    }

    private ItemStack groupIcon(SoundGroups.SoundGroup g, Muffler m, boolean edit) {
        MuffleLevel level = m.groups().get(g.id());
        List<String> lore = new ArrayList<>();
        for (String line : g.about()) {
            lore.add(line.startsWith("&") ? line : "&7" + line);
        }
        lore.add("");
        lore.add("&7Now: " + (level == null ? "&fNormal"
                : level.label() + (level == MuffleLevel.QUIETER ? " &7(" + m.quietPercent() + "% volume)" : "")));
        if (edit) {
            lore.add("&8Click: Normal → Quieter → Silent");
            lore.add("&8Right-click: back a step");
        }
        ItemStack icon = Menus.icon(SoundIcons.group(g), (level == null ? "&f" : level.colour()) + g.name(),
                lore.toArray(new String[0]));
        return Menus.glint(icon, level != null);
    }

    private void confirmClear() {
        Muffler m = current();
        if (m == null || !mayEdit(m)) {
            return;
        }
        new ConfirmMenu(plugin, "&8Clear this muffler?",
                Menus.icon(Material.MILK_BUCKET, "&cClear every choice"),
                List.of("&7Forgets &f" + m.ruleCount() + " &7choice" + (m.ruleCount() == 1 ? "" : "s") + "."),
                "&7Click to clear.",
                () -> {
                    Muffler cur = current();
                    if (cur != null && mayEdit(cur)) {
                        plugin.soundMufflers().update(cur.cleared());
                    }
                    new MufflerMenu(plugin, player, pos).open(player);
                },
                () -> new MufflerMenu(plugin, player, pos).open(player)).open(player);
    }

    /** Apply a change to the muffler as it is NOW (not as it was when the menu opened), then redraw. */
    private void change(java.util.function.UnaryOperator<Muffler> how) {
        Muffler cur = current();
        if (cur == null) {
            player.closeInventory();
            player.sendMessage(Text.of("&7That muffler is gone."));
            return;
        }
        if (!mayEdit(cur)) {
            return;
        }
        plugin.soundMufflers().update(how.apply(cur));
        refresh();
    }

    private boolean mayEdit(Muffler m) {
        if (plugin.soundMufflers().canEdit(player, m)) {
            return true;
        }
        Sounds.refused(player);
        player.sendMessage(Text.of("&cOnly " + ownerName(m.owner()) + " can change this muffler."));
        return false;
    }

    private Muffler current() {
        SoundMufflerService svc = plugin.soundMufflers();
        return svc == null ? null : svc.at(pos);
    }

    static String ownerName(UUID owner) {
        String name = Bukkit.getOfflinePlayer(owner).getName();
        return name == null ? "its owner" : name;
    }

    /** Lore lines with the {@code null}s (lines that don't apply to this viewer) left out. */
    private static String[] lore(String... lines) {
        List<String> out = new ArrayList<>(lines.length);
        for (String line : lines) {
            if (line != null) {
                out.add(line);
            }
        }
        return out.toArray(new String[0]);
    }
}
