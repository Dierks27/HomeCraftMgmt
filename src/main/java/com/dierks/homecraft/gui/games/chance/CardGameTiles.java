package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Breaks;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.chance.twentyone.TwentyOneHand;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.pack.CardFace;
import com.dierks.homecraft.pack.PackModels;
import com.dierks.homecraft.util.GameClock;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiles the card games share (Twenty-One, Higher or Lower, Video Poker, Texas Hold'em): a face-up
 * card, a face-down one, the day's position against the player's limits, and the stake buttons' look.
 *
 * <p>0.58.0, a face-up card says its short form first, in its suit's colour, then its name
 * ("&amp;fJ♠ &amp;7Jack of Spades", "&amp;c10♥ &amp;710 of Hearts"): the owner, "Can we make it so
 * Jacks show a J, Queens are Q and such on the cards?". Before, the name was the long one only and
 * a Jack's stack count said 10. A viewer with HCM's pack ({@link #art}) sees each card's own picture
 * ({@link com.dierks.homecraft.pack.CardArt}) and a face-down card its back; everyone else (no
 * CraftBridge, the pack declined or not loaded yet, Bedrock through Geyser) keeps the vanilla item,
 * never a missing model. Which item stands in without the pack stays each game's own: plain paper
 * (the closest thing to a card a vanilla client draws, its name in red or white), Hold'em's four
 * suit dyes, a map for a face-down card.
 *
 * <p><b>The stack count</b> is the only text a tile shows without a hover, so it reads at a glance
 * even on Bedrock where lore needs a long press. In Twenty-One it is what the card counts (a King
 * 10, an Ace 1, with "Counts as 10" in the lore), since that is what a player adds up. In the
 * other games it is {@link #rankCount}: the rank with Aces high (2-10, J 11, Q 12, K 13, A 14,
 * the order Higher or Lower and both pokers rank by) on a plain card, and no number at all on a
 * drawn one, whose picture already says J, Q or K.
 *
 * <p>Nothing here plays a sound or sends chat: these are painted in {@code build()}.
 */
final class CardGameTiles {

    private CardGameTiles() {
    }

    /**
     * Whether {@code viewer} is drawn the cards' pictures: HCM's models are in CraftBridge's pack,
     * they loaded it, and they are on Java ({@link PackModels#drawsFor(Player)}).
     */
    static boolean art(HomeCraftManagement plugin, Player viewer) {
        PackModels pack = plugin == null ? null : plugin.packModels();
        return pack != null && pack.drawsFor(viewer);
    }

    /** "&amp;fJ♠ &amp;7Jack of Spades": the short form in the suit's colour, then the name. */
    static String name(CardFace face) {
        return name(face.red() ? "&c" : "&f", face);
    }

    /** {@link #name(CardFace)} in a colour of the screen's own (Hold'em's four suit colours). */
    static String name(String colour, CardFace face) {
        return colour + face.shortName() + " &7" + face.longName();
    }

    /**
     * The stack count of a card in a game that ranks Aces high (Higher or Lower, Video Poker,
     * Hold'em): its rank, 2 to 14, on a plain card; 1 (no number drawn) on a card drawn as its
     * picture, which shows its rank itself.
     */
    static int rankCount(boolean art, CardFace face) {
        return art ? 1 : face.aceHighRank();
    }

    /** The model a face-up card is drawn as: the card's own for a viewer with the pack, else none. */
    static String model(boolean art, CardFace face) {
        return art ? face.modelId() : null;
    }

    /** The model a face-down card is drawn as: the card back for a viewer with the pack, else none. */
    static String backModel(boolean art) {
        return art ? CardFace.BACK_ID : null;
    }

    /** A face-up card: paper named for it ({@link #name(CardFace)}), {@code count} as the stack size. */
    static ItemStack card(boolean art, CardFace face, int count, String... lore) {
        return drawn(Menus.count(Menus.icon(Material.PAPER, name(face), lore), count), model(art, face));
    }

    /**
     * A Twenty-One card (both screens): the stack count is what it counts, an Ace 1 and a picture
     * card 10, and the lore says so ("Counts as 10", "Counts as 1 or 11").
     */
    static ItemStack twentyOne(boolean art, TwentyOneHand.Card c) {
        return card(art, new CardFace(c.rank(), c.suit()), c.value(),
                c.rank() == 1 ? "&7Counts as 1 or 11" : "&7Counts as " + c.value());
    }

    /** Twenty-One's face-down card: the hole card, and any card not yet shown. */
    static ItemStack faceDown(boolean art) {
        return faceDown(art, "&8Face-down card");
    }

    /**
     * A face-down card: a map without the pack, the card back with it. The same item whatever card
     * it hides, so nothing about the card is in what the client is sent.
     */
    static ItemStack faceDown(boolean art, String name, String... lore) {
        String model = backModel(art);
        return drawn(Menus.icon(model == null ? Material.MAP : Material.PAPER, name, lore), model);
    }

    /** {@code item} drawn as {@code model} ({@code hcm:card_…}), or left as it is for {@code null}. */
    static ItemStack drawn(ItemStack item, String model) {
        if (model == null) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setItemModel(NamespacedKey.fromString(model));
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * "Today: 35 of 100 tokens" (or "Today: 35 tokens" with no limit at all), with the plays left
     * of this game today underneath (spec §4.1).
     *
     * @param plays "hands" or "runs"
     */
    static ItemStack today(GamesService games, Game game, Player viewer, GameClock clock, int dailyLimit,
                           String plays) {
        Breaks breaks = games.breaks();
        int in = breaks == null ? 0 : breaks.tokensInToday(viewer.getUniqueId());
        int limit = breaks == null ? Breaks.NO_LIMIT : breaks.limit(viewer.getUniqueId());
        String name = limit == Breaks.NO_LIMIT ? "&eToday: &6" + in + " tokens"
                : "&eToday: &6" + in + " &eof &6" + limit + " tokens";
        List<String> lore = new ArrayList<>();
        lore.add("&7Tokens put into games of chance today.");
        try {
            int played = games.dao().playsToday(viewer.getUniqueId(), game.id(), clock.dayKey());
            lore.add("&7" + game.name() + " " + plays + " left today: &f" + Math.max(0, dailyLimit - played));
        } catch (SQLException | RuntimeException e) {
            // The count is a courtesy; the gate still enforces the limit when tokens go in.
        }
        lore.add("&7A limit of your own: Wallet → Take a break.");
        return Menus.icon(Material.CLOCK, name, lore.toArray(new String[0]));
    }

    /** A stake button: "5 tokens in", lit when chosen. */
    static ItemStack stake(int stake, boolean chosen, List<String> lore) {
        List<String> lines = new ArrayList<>(lore);
        lines.add(chosen ? "&aChosen" : "&eClick to choose");
        ItemStack icon = Menus.icon(Material.GOLD_NUGGET, (chosen ? "&6&l" : "&6") + stake + " tokens in",
                lines.toArray(new String[0]));
        return Menus.glint(Menus.count(icon, stake), chosen);
    }

    /** The slots stake buttons use, in order: row 5's three, then row 4 for any more. */
    static final int[] STAKE_SLOTS = {46, 47, 48, 36, 37, 38, 39, 40, 41, 42, 43, 44};

    /** A button that can't be used now: grey, with why in its name. */
    static ItemStack unlit(String name, String why) {
        return Menus.icon(Material.LIGHT_GRAY_CONCRETE, "&7" + name + (why == null ? "" : " &8- " + why));
    }
}
