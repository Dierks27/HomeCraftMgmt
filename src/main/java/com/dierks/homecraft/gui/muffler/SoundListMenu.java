package com.dierks.homecraft.gui.muffler;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.muffler.MuffleLevel;
import com.dierks.homecraft.muffler.Muffler;
import com.dierks.homecraft.muffler.MufflerPos;
import com.dierks.homecraft.muffler.SoundGroups;
import com.dierks.homecraft.muffler.SoundMufflerService;
import com.dierks.homecraft.muffler.SoundNames;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A page of single sounds to pick from, in one of three lists:
 * <ul>
 *   <li><b>Heard nearby</b> — what the muffler actually heard in the last ten minutes. The quick
 *       way to find the noise: stand by the farm, open this, click the culprit.</li>
 *   <li><b>Find a sound</b> — every sound in the game whose key holds the words typed.</li>
 *   <li><b>Single sounds</b> — the ones already picked on this muffler.</li>
 * </ul>
 * A click steps the sound's own pick: not picked → Quieter → Silent → Always play → not picked.
 * A pick beats the sound's group, which is what "Always play" is for.
 */
final class SoundListMenu extends Menu {

    enum Mode { HEARD, SEARCH, PICKS }

    private static final int PAGE_SIZE = 45;

    private final Player player;
    private final MufflerPos pos;
    private final Mode mode;
    private final String query;
    private int page;
    /** The list as it was when the screen opened, so a click doesn't reshuffle the page under you. */
    private List<String> keys;
    private Map<String, Long> heardAt = Map.of();

    SoundListMenu(HomeCraftManagement plugin, Player player, MufflerPos pos, Mode mode, String query) {
        super(plugin);
        this.player = player;
        this.pos = pos;
        this.mode = mode;
        this.query = query == null ? "" : query.trim();
        init(54, Text.of(switch (mode) {
            case HEARD -> "&8Heard nearby";
            case SEARCH -> "&8Sounds: " + shorten(this.query);
            case PICKS -> "&8Single sounds";
        }));
    }

    private void load(SoundMufflerService svc, Muffler m) {
        switch (mode) {
            case HEARD -> {
                List<SoundMufflerService.Heard> heard = svc.heardBy(m);
                Map<String, Long> at = new HashMap<>();
                List<String> list = new ArrayList<>(heard.size());
                for (SoundMufflerService.Heard h : heard) {
                    list.add(h.key());
                    at.put(h.key(), h.at());
                }
                keys = list;
                heardAt = at;
            }
            case SEARCH -> keys = svc.search(query);
            case PICKS -> keys = m.pickedSounds();
        }
    }

    @Override
    protected void build() {
        for (int slot = 45; slot < 54; slot++) {
            set(slot, Menus.FILLER, null);
        }
        SoundMufflerService svc = plugin.soundMufflers();
        Muffler m = svc == null ? null : svc.at(pos);
        if (m == null) {
            set(22, Menus.icon(Material.BARRIER, "&cThis muffler is gone", "&7It was broken or moved."), null);
            set(49, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
            return;
        }
        if (keys == null) {
            load(svc, m);
        }
        boolean edit = svc.canEdit(player, m);
        int pages = Math.max(1, (int) Math.ceil(keys.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, pages - 1));

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= keys.size()) {
                set(i, null, null);
                continue;
            }
            String key = keys.get(idx);
            set(i, soundIcon(key, m, edit), e -> {
                Muffler cur = svc.at(pos);
                if (cur == null) {
                    player.closeInventory();
                    return;
                }
                if (!svc.canEdit(player, cur)) {
                    Sounds.refused(player);
                    player.sendMessage(Text.of("&cOnly " + MufflerMenu.ownerName(cur.owner())
                            + " can change this muffler."));
                    return;
                }
                MuffleLevel now = cur.sounds().get(key);
                MuffleLevel next = e.isRightClick() ? MuffleLevel.previousForSound(now) : MuffleLevel.nextForSound(now);
                Muffler changed = cur.withSound(key, next);
                if (changed == cur && next != null) {
                    Sounds.refused(player);
                    player.sendMessage(Text.of("&cThis muffler already has " + Muffler.MAX_PICKS
                            + " single sounds picked. Clear a few, or use a group."));
                    return;
                }
                svc.update(changed);
                refresh();
            });
        }

        if (keys.isEmpty()) {
            set(22, emptyIcon(svc), null);
        }

        if (page > 0) {
            set(45, Menus.icon(Material.ARROW, "&e« Previous"), e -> {
                page--;
                refresh();
            });
        }
        if (mode == Mode.HEARD) {
            set(47, Menus.icon(Material.CLOCK, "&eRefresh", "&7Look again for sounds heard", "&7since this opened."),
                    e -> new SoundListMenu(plugin, player, pos, Mode.HEARD, "").open(player));
        } else if (mode == Mode.SEARCH) {
            set(47, Menus.icon(Material.NAME_TAG, "&eNew search"), e ->
                    plugin.chatPrompts().prompt(player, "Which sound? Type part of its name (e.g. chicken, piston, door):",
                            input -> new SoundListMenu(plugin, player, pos, Mode.SEARCH, input).open(player)));
        }
        set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> new MufflerMenu(plugin, player, pos).open(player));
        set(51, Menus.icon(Material.PAPER, "&8" + keys.size() + " sound" + (keys.size() == 1 ? "" : "s")
                + (pages > 1 ? " · page " + (page + 1) + "/" + pages : "")), null);
        if (page + 1 < pages) {
            set(53, Menus.icon(Material.ARROW, "&eNext »"), e -> {
                page++;
                refresh();
            });
        }
    }

    private ItemStack soundIcon(String key, Muffler m, boolean edit) {
        MuffleLevel pick = m.sounds().get(key);
        MuffleLevel fromGroup = m.groupLevel(key);
        MuffleLevel effective = pick != null ? pick : fromGroup;
        List<String> lore = new ArrayList<>();
        lore.add("&8" + key);
        Long at = heardAt.get(key);
        if (at != null) {
            lore.add("&7Heard " + Menus.duration(System.currentTimeMillis() - at) + " ago");
        }
        List<SoundGroups.SoundGroup> groups = SoundGroups.of(key);
        if (!groups.isEmpty()) {
            List<String> names = new ArrayList<>(groups.size());
            for (SoundGroups.SoundGroup g : groups) {
                names.add(g.name());
            }
            lore.add("&7Group: &f" + String.join("&7, &f", names));
        }
        lore.add("");
        if (pick != null) {
            lore.add("&7Picked: " + describe(pick, m));
        } else if (fromGroup != null) {
            SoundGroups.SoundGroup g = m.decidingGroup(key);
            lore.add("&7From " + (g == null ? "its group" : g.name()) + ": " + describe(fromGroup, m));
        }
        lore.add("&7Now: " + (effective == null || effective == MuffleLevel.ALLOW ? "&fplays normally" : describe(effective, m)));
        if (edit) {
            lore.add("");
            lore.add("&8Click: Quieter → Silent → Always play → not picked");
            lore.add("&8Right-click: back a step");
        }
        String colour = effective == null ? "&f" : effective.colour();
        ItemStack icon = Menus.icon(SoundIcons.of(key), colour + SoundNames.friendly(key), lore.toArray(new String[0]));
        return Menus.glint(icon, pick != null);
    }

    private static String describe(MuffleLevel level, Muffler m) {
        return level.label() + (level == MuffleLevel.QUIETER ? " &7(" + m.quietPercent() + "% volume)" : "");
    }

    private ItemStack emptyIcon(SoundMufflerService svc) {
        return switch (mode) {
            case HEARD -> {
                List<String> lore = new ArrayList<>(List.of(
                        "&7Stay near the muffler while the noise",
                        "&7happens, then press &eRefresh&7.",
                        "",
                        "&8Sounds your own game makes (rain, music,",
                        "&8furnaces, portals, your own footsteps)",
                        "&8never show up — no muffler can reach them."));
                if (!svc.working()) {
                    lore.add("");
                    lore.add("&c" + svc.problem());
                }
                yield Menus.icon(Material.CLOCK, "&7Nothing heard yet", lore.toArray(new String[0]));
            }
            case SEARCH -> Menus.icon(Material.BARRIER, "&7No sound matches \"" + shorten(query) + "\"",
                    "&7Try one word, like &fchicken &7or &fdoor&7.");
            case PICKS -> Menus.icon(Material.BOOK, "&7No single sounds picked yet",
                    "&7Pick one from &bHeard nearby", "&7or &bFind a sound&7.");
        };
    }

    private static String shorten(String s) {
        String plain = s.replace('&', ' ');
        return plain.length() > 24 ? plain.substring(0, 23) + "…" : plain;
    }
}
