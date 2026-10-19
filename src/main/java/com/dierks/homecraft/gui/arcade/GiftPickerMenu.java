package com.dierks.homecraft.gui.arcade;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.arcade.ArcadeService.Outcome;
import com.dierks.homecraft.arcade.PrizeItems;
import com.dierks.homecraft.arcade.PrizeService;
import com.dierks.homecraft.arcade.PrizeService.GiftFacts;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.gui.ConfirmMenu;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
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
 * "Give a present" (HALLOWEEN-SPEC D8): buy a collector for someone who's online. Reached only from
 * its own tile on the Cosmetics tab, never from a collector's tile (that one buys it for yourself).
 * Every screen takes plain taps only, because Bedrock touch can't shift- or right-click reliably:
 * <ol>
 *   <li>"Which present?" (27 slots): one tile per giftable collector on sale, Back on 22. A giver
 *       who can't afford one is told how many more tokens at once.</li>
 *   <li>"Who is it for?" (54 slots): one tile per other online player the giver can see, by name.
 *       Why someone can't get it is in the NAME ("has it", "bag full", "not here"), because Bedrock
 *       shows lore only on a long press; only the green ones can be tapped. 36 a page, the arrows
 *       on 45/53, Back on 49. There is no "For me" tile.</li>
 *   <li>The confirm, with the RECEIVER in the middle (a buy's middle tile is the item), so a present
 *       never looks like a purchase. Cancel goes back to the people.</li>
 * </ol>
 *
 * <p>The confirm hands the receiver's UUID to {@link PrizeService#gift}, which looks them up again and
 * re-checks everything before any token moves. On success it closes this menu and gives both players
 * their moment itself, so nothing here reopens a menu or plays a sound after a present. A menu never
 * shows a tagged item: a cap is drawn from its untagged look, a trail from its row's icon.
 */
public final class GiftPickerMenu extends Menu {

    private static final int GRID_START = 9;
    private static final int GRID_SIZE = 36;

    private final Player giver;
    private final Runnable back;
    /** The present picked on the first screen; null while picking it. */
    private final Prize present;
    private final int page;

    /** The first screen: which present. {@code back} returns to the counter. */
    public GiftPickerMenu(HomeCraftManagement plugin, Player giver, Runnable back) {
        this(plugin, giver, back, null, 0);
    }

    private GiftPickerMenu(HomeCraftManagement plugin, Player giver, Runnable back, Prize present, int page) {
        super(plugin);
        this.giver = giver;
        this.back = back;
        this.present = present;
        this.page = Math.max(0, page);
        init(present == null ? 27 : 54, Text.of(present == null ? "&5Which present?" : "&5Who is it for?"));
    }

    @Override
    protected void build() {
        if (present == null) {
            buildPresents();
        } else {
            buildPeople();
        }
    }

    // ---- the counter's entry -----------------------------------------------------------

    /** Whether the counter shows "Give a present": gifting is on and a giftable collector is on sale. */
    public static boolean entryShown(HomeCraftManagement plugin) {
        return !presents(plugin).isEmpty();
    }

    /** The presents that can be given right now, in counter order. */
    private static List<Prize> presents(HomeCraftManagement plugin) {
        List<Prize> visible = new ArrayList<>();
        for (Prize p : plugin.config().arcade().prizes()) {
            if (p.collector() != null && plugin.prizes().visible(p)) {
                visible.add(p);
            }
        }
        return presents(plugin.config().arcade().collectorsGifting(), visible);
    }

    /**
     * The giftable collectors among {@code visible} (rows already on sale and in season), and none
     * at all while {@code arcade.collectors.gifting} is off. Pure, so the entry's rule is tested.
     */
    static List<Prize> presents(boolean giftingOn, List<Prize> visible) {
        List<Prize> out = new ArrayList<>();
        if (!giftingOn) {
            return out;
        }
        for (Prize p : visible) {
            if (p.collector() != null && p.collector().giftable()) {
                out.add(p);
            }
        }
        return out;
    }

    /** The present with this id as it is now (a reload or the season may have taken it), or null. */
    private Prize current(String id) {
        for (Prize p : presents(plugin)) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    // ---- 1. which present ----------------------------------------------------------------

    private void buildPresents() {
        for (int i = 0; i < 27; i++) {
            set(i, Menus.FILLER, null);
        }
        set(4, Menus.icon(Material.CAKE, "&dGive a present",
                "&7Pick a present, then", "&7pick who it's for."), null);
        List<Prize> rows = presents(plugin);
        if (rows.isEmpty()) {
            set(13, Menus.icon(Material.BARRIER, "&7No presents right now", "&8Check back soon!"), null);
        }
        int balance = plugin.tokens().balance(giver.getUniqueId());
        int[] slots = rowSlots(rows.size());
        for (int i = 0; i < slots.length; i++) {
            Prize p = rows.get(i);
            set(slots[i], presentTile(p, balance), e -> pickPresent(p.id()));
        }
        set(22, Menus.icon(Material.BARRIER, "&cBack"), e -> goBack());
    }

    /**
     * Where {@code n} present tiles go on the middle row (slots 9-17): centred, a gap between them
     * while five or fewer fit that way, side by side after that, and never more than the row holds.
     */
    static int[] rowSlots(int n) {
        int count = Math.max(0, Math.min(n, 9));
        if (count == 0) {
            return new int[0];
        }
        int step = count <= 5 ? 2 : 1;
        int width = (count - 1) * step + 1;
        int start = GRID_START + (9 - width) / 2;
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = start + i * step;
        }
        return out;
    }

    /** A present's tile: its name and price, the collector lines, then whether the giver can afford it. */
    private ItemStack presentTile(Prize p, int balance) {
        int cost = plugin.prizes().price(giver.getUniqueId(), p);
        List<String> lore = new ArrayList<>();
        for (String line : p.description()) {
            lore.add("&7" + line);
        }
        lore.addAll(PrizeCounterMenu.collectorLines(p.collector()));
        lore.add(balance >= cost ? "&eClick to pick who it's for" : "&cNeed " + (cost - balance) + " more tokens");
        return look(p, p.display() + " &7- &6" + cost + " tokens", lore);
    }

    /** A present's picture, never a tagged item: a cap's untagged look, or a trail's row icon. */
    private static ItemStack look(Prize p, String name, List<String> lore) {
        if (p.collector() != null && p.collector().look() != null) {
            return PrizeItems.capLook(p.collector().look(), name, lore);
        }
        return Menus.icon(p.icon().material(), name, lore.toArray(new String[0]));
    }

    private void pickPresent(String id) {
        Prize p = current(id);
        if (p == null) {
            refuse("That isn't for sale right now.");
            refresh();
            return;
        }
        int cost = plugin.prizes().price(giver.getUniqueId(), p);
        int have = plugin.tokens().balance(giver.getUniqueId());
        if (have < cost) {
            refuse("You need " + (cost - have) + " more tokens."); // as a buy says it
            return;
        }
        new GiftPickerMenu(plugin, giver, back, p, 0).open(giver);
    }

    private void goBack() {
        if (back != null) {
            back.run();
        } else {
            giver.closeInventory();
        }
    }

    // ---- 2. who it's for -----------------------------------------------------------------

    private void buildPeople() {
        for (int i = 0; i < 9; i++) {
            set(i, Menus.FILLER, null);
        }
        for (int i = 45; i < 54; i++) {
            set(i, Menus.FILLER, null);
        }
        String item = Text.plain(present.display());
        set(4, look(present, "&dWho is it for?", List.of("&7Pick who gets the " + item + ".",
                "&7Presents go to someone who's online.")), null);

        List<Player> people = people();
        int p = clampPage(page, people.size());
        if (people.isEmpty()) {
            set(22, Menus.icon(Material.PAPER, "&7No one else is online", "&8Try again when they are."), null);
        }
        boolean bedrock = Bedrock.is(giver);
        boolean maySpend = plugin.sandbox().allowed(giver.getWorld());
        int from = p * GRID_SIZE;
        for (int i = 0; i < GRID_SIZE && from + i < people.size(); i++) {
            Player r = people.get(from + i);
            String note = note(plugin.prizes().giftFacts(giver, r.getUniqueId(), present, maySpend));
            String name = tileName(r.getName(), note);
            String[] lore = note == null ? new String[] {"&7Gets the " + item, "&eClick to choose"} : new String[0];
            ItemStack icon = bedrock ? Menus.icon(Material.PAPER, name, lore) : ScoresMenu.playerHead(r, name, lore);
            UUID id = r.getUniqueId();
            set(GRID_START + i, icon, note == null ? e -> pickPerson(id) : null);
        }
        if (p > 0) {
            set(45, Menus.icon(Material.ARROW, "&fPrevious page"),
                    e -> new GiftPickerMenu(plugin, giver, back, present, p - 1).open(giver));
        }
        if (p < pages(people.size()) - 1) {
            set(53, Menus.icon(Material.ARROW, "&fNext page"),
                    e -> new GiftPickerMenu(plugin, giver, back, present, p + 1).open(giver));
        }
        // Slot 49 is the way out and never an arrow (NavAnchorTest).
        set(49, Menus.icon(Material.BARRIER, "&cBack"), e -> new GiftPickerMenu(plugin, giver, back).open(giver));
    }

    /** Everyone the picker lists, by name: online, not the giver, and seen by the giver (a vanished admin isn't). */
    private List<Player> people() {
        List<Player> out = new ArrayList<>();
        for (Player r : Bukkit.getOnlinePlayers()) {
            if (listable(r.getUniqueId().equals(giver.getUniqueId()), giver.canSee(r))) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Whether the picker lists a player at all: never the giver, and never anyone the giver can't see. */
    static boolean listable(boolean self, boolean giverCanSee) {
        return !self && giverCanSee;
    }

    /**
     * Why a listed player can't get this present, for their tile's name, or null when they can: in
     * {@link PrizeService#giftRefusal}'s order for the receiver's own checks (where they are, whether
     * they have it, room on their head or in their bag). The giver's side (tokens, the season) is
     * the confirm's to refuse, and the same for everyone.
     */
    static String note(GiftFacts f) {
        if (!f.receiverEconomyWorld() || f.receiverGamesWorld() || f.receiverInGame()) {
            return "not here"; // another world, a Games world, or in a game
        }
        if (f.receiverHasIt()) {
            return "has it";
        }
        if (f.cap() && !f.headEmpty() && !f.bagHasRoom()) {
            return "bag full";
        }
        return null;
    }

    /** "&amp;a<name>" for someone who can get it, else "&amp;7<name> &amp;8- <why>". */
    static String tileName(String name, String note) {
        return note == null ? "&a" + name : "&7" + name + " &8- " + note;
    }

    /** Pages of {@value #GRID_SIZE} people; always at least one. */
    static int pages(int count) {
        return Math.max(1, (count + GRID_SIZE - 1) / GRID_SIZE);
    }

    /** {@code page} kept inside the pages there are (people leave while the picker is open). */
    static int clampPage(int page, int count) {
        return Math.max(0, Math.min(page, pages(count) - 1));
    }

    // ---- 3. the confirm ------------------------------------------------------------------

    private void pickPerson(UUID id) {
        Prize p = current(present.id());
        if (p == null) {
            refuse("That isn't for sale right now.");
            new GiftPickerMenu(plugin, giver, back).open(giver);
            return;
        }
        // Looked up again by UUID: the tile is a snapshot, and they may have left or moved since.
        GiftFacts f = plugin.prizes().giftFacts(giver, id, p, plugin.sandbox().allowed(giver.getWorld()));
        String refused = PrizeService.giftRefusal(f);
        Player r = Bukkit.getPlayer(id);
        if (refused != null || r == null) {
            refuse(refused != null ? refused : "That player isn't online.");
            refresh();
            return;
        }
        new ConfirmMenu(plugin, "&5Give " + f.item() + " to " + r.getName() + "?", receiverTile(r, f.item()),
                List.of("&7Costs &6" + f.cost() + " tokens&7.", "&7You have &6" + f.balance() + "&7."),
                "&7Click to give it.", () -> give(p, id), this::reopenPeople).open(giver);
    }

    /** The confirm's middle tile: the person, not the item (paper on Bedrock, their head on Java). */
    private ItemStack receiverTile(Player r, String item) {
        String name = "&a" + r.getName();
        String line = "&7Gets the " + item;
        return Bedrock.is(giver) ? Menus.icon(Material.PAPER, name, line) : ScoresMenu.playerHead(r, name, line);
    }

    /**
     * Confirm: the present itself. A success has already closed this menu and given both players
     * their moment ({@link PrizeService#gift}), so only a refusal is shown here, and the picker
     * comes back so the giver can try someone else.
     */
    private void give(Prize p, UUID receiver) {
        Outcome out = plugin.prizes().gift(giver, receiver, p);
        if (out.ok()) {
            return;
        }
        refuse(out.error());
        if (current(p.id()) == null) {
            new GiftPickerMenu(plugin, giver, back).open(giver);
        } else {
            reopenPeople();
        }
    }

    private void reopenPeople() {
        new GiftPickerMenu(plugin, giver, back, present, page).open(giver);
    }

    private void refuse(String message) {
        giver.sendMessage(Text.of("&c" + message));
        Sounds.refused(giver);
    }
}
