package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.hunt.HuntService;
import com.dierks.homecraft.mini.MiniDef;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The Arcade hub — one screen with everything on it, in zones that read as rows even on Bedrock:
 *
 * <pre>
 *  row 0  border ........ [4 Wallet]
 *  row 1  [9 Games]  10-12 crates · 14 Scratch Ticket (jackpot in its name) · 16 wild Mini status
 *  row 2  [18 Prize Counter]  19 Boosts · 20 Hunt Gear · 21 Cosmetics · 22 Perks · 23 Trophies ·
 *                             24 Minis · 25 Card Packs
 *  row 3  [27 You]  29 Quests · 31 Achievements · 33 How It Works
 *  row 4  border
 *  row 5  border · 49 Close
 * </pre>
 *
 * Every button says what it is and its key number in its NAME, because Bedrock shows lore only on
 * tap-and-hold. The wild-Mini tile counts down while the hub is open.
 */
public final class ArcadeMenu extends Menu {

    static final int WALLET = 4;
    static final int WILD = 16;
    private static final int[] CRATES = {10, 11, 12};
    private static final int SCRATCH = 14;
    private static final int[] TABS = {19, 20, 21, 22, 23, 24};
    private static final int PACKS = 25;
    private static final int QUESTS = 29;
    private static final int ACHIEVEMENTS = 31;
    private static final int GUIDE = 33;

    private static final ItemStack BORDER = Menus.icon(Material.PURPLE_STAINED_GLASS_PANE, " ");

    private final Player player;
    private BukkitTask ticker;

    public ArcadeMenu(HomeCraftManagement plugin, Player player) {
        super(plugin);
        this.player = player;
        init(54, Text.of("&5&lArcade"));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 54; i++) {
            boolean border = i < 9 || i >= 36;
            set(i, border ? BORDER : Menus.FILLER, null);
        }
        set(9, Menus.icon(Material.ORANGE_STAINED_GLASS_PANE, "&6&lGames", "&7Crates and the Scratch Ticket."), null);
        set(18, Menus.icon(Material.YELLOW_STAINED_GLASS_PANE, "&e&lPrize Counter",
                "&7Pick what you want. No luck needed."), null);
        set(27, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&a&lYou",
                "&7Your quests, achievements", "&7and how it all works."), null);

        PluginConfig.Arcade arc = plugin.config().arcade();
        wallet(arc);
        crates(arc);
        scratch(arc);
        set(WILD, wildTile(), null);
        prizeTabs();
        set(PACKS, ArcadeIcons.of(plugin, player, "packs", Material.PAPER, "&bCard Packs",
                "&7Open a pack, get a Card,", "&7print it into a Mini.", "&eClick to shop"),
                e -> new com.dierks.homecraft.gui.mini.PackShopMenu(plugin, player, this::reopen).open(player));
        you();
        set(49, Menus.icon(Material.BARRIER, "&cClose"), e -> e.getWhoClicked().closeInventory());
    }

    private void wallet(PluginConfig.Arcade arc) {
        int tokens = plugin.tokens().balance(player.getUniqueId());
        int streak = plugin.tokens().streak(player.getUniqueId());
        List<String> lore = new ArrayList<>();
        lore.add("&7Login streak: &f" + streak + " day" + (streak == 1 ? "" : "s")
                + " &7(tomorrow &a+" + arc.streakReward(streak + 1) + "&7)");
        int toNext = plugin.tokens().minutesToNextPlaytimeToken(player);
        if (toNext >= 0) {
            lore.add("&7Next playtime token in &f" + toNext + " min");
        }
        lore.add("&eClick for your Wallet");
        set(WALLET, Menus.glint(ArcadeIcons.of(plugin, player, "wallet", Material.SUNFLOWER,
                "&eWallet: &6" + tokens + " tokens", lore), true),
                e -> new WalletMenu(plugin, player, this::reopen).open(player));
    }

    private void crates(PluginConfig.Arcade arc) {
        List<String> ids = new ArrayList<>(arc.crates().keySet());
        for (int i = 0; i < CRATES.length && i < ids.size(); i++) {
            String id = ids.get(i);
            PluginConfig.Crate crate = arc.crates().get(id);
            set(CRATES[i], ArcadeIcons.of(plugin, player, "crate", Material.CHEST,
                    crate.display() + " &7- &6" + crate.costTokens() + " tokens",
                    "&7See what's inside and the chances,", "&7then open it!", "&eClick to look"),
                    e -> new CrateMenu(plugin, player, id, this::reopen).open(player));
        }
    }

    private void scratch(PluginConfig.Arcade arc) {
        int cost = arc.lotto().ticketTokens();
        set(SCRATCH, Menus.glint(ArcadeIcons.of(plugin, player, "scratch", Material.FILLED_MAP,
                "&aScratch Ticket &7- Jackpot &6" + plugin.arcade().pot(),
                "&7Costs &6" + cost + " tokens&7.",
                "&7Scratch three squares to see what you win.",
                "&7The jackpot grows with every ticket!",
                "&eClick to buy one"), true), e -> {
            var r = plugin.arcade().scratch(player);
            if (r.ok()) {
                new ScratchTicketMenu(plugin, player, r, this::reopen).open(player);
            } else {
                player.sendMessage(Text.of("&c" + r.error()));
                com.dierks.homecraft.util.Sounds.refused(player);
                refresh();
            }
        });
    }

    /** "No wild Minis right now", or who it's near and how long is left, with the hints so far. */
    private ItemStack wildTile() {
        HuntService hunt = plugin.hunt();
        HuntService.Hunt h = null;
        if (hunt != null) {
            for (HuntService.Hunt each : hunt.live()) {
                h = each;
                break;
            }
        }
        if (h == null) {
            return ArcadeIcons.of(plugin, player, "wild_none", Material.GRAY_DYE, "&7No wild Minis right now",
                    "&7When one appears, everyone", "&7hears about it in chat.");
        }
        MiniDef def = plugin.miniService().def(h.miniId());
        String rarity = def == null ? "&dwild" : plugin.miniService().rarityText(def.rarity());
        String near = null;
        if (h.target() != null) {
            near = Bukkit.getOfflinePlayer(h.target()).getName();
        }
        String left = clock(hunt.msLeft(h));
        List<String> lore = new ArrayList<>();
        for (String hint : hunt.hintsSoFar(h)) {
            lore.add(hint);
        }
        if (lore.isEmpty()) {
            lore.add("&7Hints come as time goes by.");
        }
        lore.add("&7A Mini Radar says if you're close.");
        String article = def == null ? "A" : Character.toUpperCase(def.rarity().article().charAt(0))
                + def.rarity().article().substring(1);
        return Menus.glint(ArcadeIcons.of(plugin, player, "wild", Material.SPYGLASS,
                "&d" + article + " " + rarity + " &dMini is loose" + (near != null ? " near " + near : "")
                        + "! &f" + left + " &7left", lore), true);
    }

    /** m:ss */
    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    private void prizeTabs() {
        PrizeTab[] tabs = PrizeTab.values();
        for (int i = 0; i < TABS.length && i < tabs.length; i++) {
            PrizeTab t = tabs[i];
            set(TABS[i], PrizeCounterMenu.tabIcon(plugin, player, t, false),
                    e -> new PrizeCounterMenu(plugin, player, this::reopen, t, 0).open(player));
        }
    }

    private void you() {
        var quests = plugin.config().quests();
        List<String> qLore = new ArrayList<>();
        String qName = "&dQuests";
        if (plugin.quests() != null && quests != null && quests.enabled()) {
            var daily = plugin.quests().assigned(player.getUniqueId(), PluginConfig.QuestPeriod.DAILY);
            int done = 0;
            for (var q : daily) {
                if (plugin.quests().done(player.getUniqueId(), q)) {
                    done++;
                }
            }
            qName = "&dQuests &7- &f" + done + " of " + daily.size() + " done today";
            qLore.add("&7Daily and weekly jobs that pay tokens.");
        } else {
            qLore.add("&7Quests are off right now.");
        }
        qLore.add("&eClick to see them");
        set(QUESTS, ArcadeIcons.of(plugin, player, "quests", Material.WRITABLE_BOOK, qName, qLore),
                e -> new QuestsMenu(plugin, player, this::reopen).open(player));

        int total = 0;
        int have = 0;
        Set<String> unlocked = plugin.achievements() == null ? Set.of()
                : plugin.achievements().unlocked(player.getUniqueId());
        for (var a : plugin.config().achievements().values()) {
            if (a.enabled()) {
                total++;
                if (unlocked.contains(a.id())) {
                    have++;
                }
            }
        }
        set(ACHIEVEMENTS, ArcadeIcons.of(plugin, player, "achievements", Material.TOTEM_OF_UNDYING,
                "&6Achievements &7- &f" + have + " / " + total,
                "&7Big moments that pay tokens once.", "&eClick to see them"),
                e -> new AchievementsMenu(plugin, player, this::reopen, 0).open(player));

        set(GUIDE, ArcadeIcons.of(plugin, player, "guide", Material.KNOWLEDGE_BOOK, "&bHow It Works",
                "&7Tokens, Minis, wild Minis", "&7and the Arcade games.", "&eClick to read"),
                e -> new GuideMenu(plugin, player, 0, this::reopen).open(player));
    }

    @Override
    public void open(Player viewer) {
        super.open(viewer);
        if (ticker == null) {
            // Only the wild tile changes by itself; repaint just that slot, and less often on
            // Bedrock, where every inventory update is a stutter.
            long every = Bedrock.is(player) ? 100L : 20L;
            ticker = plugin.getServer().getScheduler().runTaskTimer(plugin,
                    () -> getInventory().setItem(WILD, wildTile()), every, every);
        }
    }

    @Override
    protected void onClose(Player viewer) {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    private void reopen() {
        new ArcadeMenu(plugin, player).open(player);
    }
}
