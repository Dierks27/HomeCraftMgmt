package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.chance.slots.Line;
import com.dierks.homecraft.games.chance.slots.OreSlots;
import com.dierks.homecraft.games.chance.slots.OreSlotsSettings;
import com.dierks.homecraft.games.chance.slots.ReelShow;
import com.dierks.homecraft.games.chance.slots.SlotsCopy;
import com.dierks.homecraft.games.chance.slots.SlotsEngine;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.LineOdds;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.Spin;
import com.dierks.homecraft.games.chance.slots.SlotsEngine.StakeOdds;
import com.dierks.homecraft.games.chance.slots.Symbol;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Bedrock;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Ore Slots screen (spec §5.3): the paytable, the line, the stakes and Spin on one screen, so
 * the rules and the odds are in front of the player before the first spin.
 *
 * <pre>
 *  row 0   How to play | the paytable, top line first (7 lines, "×N · 1 in N")
 *  row 1               result (13)
 *  row 2            ▶  reel  reel  reel  ◀        (21 22 23: the one line, nothing above or below)
 *  row 3                  Spin (31)
 *  row 4          stake buttons, centred on 40 (a glint on the one picked)
 *  row 5   46 gives back about N · 47 plays left · 48 today's tokens · 49 Back · 50 balance
 * </pre>
 *
 * <p>The spin is decided and paid before the reels move ({@code ChanceRounds.play}); this screen
 * is the show. The reels stop on fixed ticks with cosmetic frames that never show a paying line
 * early ({@link ReelShow}), closing the screen just prints the result, and the result is told
 * once: a win in green with {@code Sounds.won} (and a private title for a line of ×20 or more),
 * "Your N back" or "No win this time." with {@code Sounds.miss}. The buttons stay where they are
 * after any result; nothing asks the player to spin again. Every number shown comes from the
 * engine snapshot this screen was opened with.
 */
public final class OreSlotsMenu extends GameMenu {

    private static final int RULES = 0;
    /** The paytable: one slot per {@link Line}, in its order. */
    private static final int PAYTABLE = 1;
    private static final int RESULT = 13;
    private static final int[] REELS = {21, 22, 23};
    private static final int POINT_RIGHT = 20;
    private static final int POINT_LEFT = 24;
    private static final int SPIN = 31;
    private static final int STAKES_CENTRE = 40;
    private static final int GIVE_BACK = 46;
    private static final int PLAYS = 47;
    private static final int TODAY = 48;
    private static final int BALANCE = 50;

    private final OreSlots slots;
    private final OreSlotsSettings settings;
    private final SlotsEngine engine;
    private final boolean bedrock;
    private int stake;
    /** What the line shows now ({@code null} = a blank reel). */
    private List<Symbol> shown = blank();
    /** The spin on screen: the one spinning, or the last one. */
    private Spin spin;
    private boolean spinning;
    private boolean told = true;
    private int tick;
    private int stopped;
    private SplittableRandom cosmetic;
    private BukkitTask task;

    public OreSlotsMenu(HomeCraftManagement plugin, OreSlots game, Player viewer, Runnable back,
                        OreSlotsSettings settings) {
        super(plugin, game, viewer, back);
        this.slots = game;
        this.settings = settings;
        this.engine = settings.engine();
        this.bedrock = Bedrock.is(viewer);
        this.stake = engine.stakes().isEmpty() ? 0 : engine.stakes().get(0);
        init(54, Text.of("&6Ore Slots"));
    }

    @Override
    protected void build() {
        fill();
        set(RULES, rulesTile(game.rules()), null);
        StakeOdds odds = engine.odds(stake);
        if (odds != null) {
            for (Line line : Line.values()) {
                LineOdds l = odds.line(line);
                if (l != null) {
                    set(PAYTABLE + line.ordinal(), payTile(l), null);
                }
            }
            set(GIVE_BACK, Menus.glint(Menus.icon(Material.KNOWLEDGE_BOOK, SlotsCopy.giveBack(odds),
                    "&7For " + SlotsCopy.tokens(stake) + " in, worked out", "&7exactly from the paytable.",
                    SlotsCopy.hitLine(odds)), false), null);
        }
        set(RESULT, resultTile(), null);
        set(POINT_RIGHT, Menus.icon(Material.YELLOW_STAINED_GLASS_PANE, "&e▶"), null);
        set(POINT_LEFT, Menus.icon(Material.YELLOW_STAINED_GLASS_PANE, "&e◀"), null);
        for (int i = 0; i < REELS.length; i++) {
            set(REELS[i], reelTile(shown.get(i)), null);
        }
        int left = slots.playsLeft(viewer.getUniqueId(), settings);
        set(SPIN, spinTile(left), e -> spin());
        List<Integer> stakes = engine.stakes();
        int first = STAKES_CENTRE - (stakes.size() - 1) / 2;
        for (int i = 0; i < stakes.size(); i++) {
            int s = stakes.get(i);
            set(first + i, stakeTile(s), e -> pick(s));
        }
        set(PLAYS, Menus.icon(Material.CLOCK, "&ePlays left today: &f" + (left < 0 ? "?" : left),
                "&7" + settings.dailyLimit() + " spins a day.", "&7They come back at midnight."), null);
        set(TODAY, Menus.icon(Material.HOPPER, slots.todayLine(viewer.getUniqueId()),
                "&7Tokens put into games of chance today.", "&7You can set a lower limit in Take a break."), null);
        set(BALANCE, balanceTile(), null);
        exitTile();
    }

    // ---- tiles ---------------------------------------------------------------------------------

    private ItemStack payTile(LineOdds l) {
        ItemStack icon = Menus.icon(lineMaterial(l.line()), SlotsCopy.lineName(l),
                SlotsCopy.lineLore(l).toArray(new String[0]));
        if (l.line() == Line.TWO_THE_SAME) {
            Menus.count(icon, 2);
        }
        return Menus.glint(icon, false);
    }

    private ItemStack reelTile(Symbol s) {
        if (s == null) {
            return Menus.icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7…");
        }
        return Menus.glint(Menus.icon(material(s), "&f" + s.label()), false);
    }

    private ItemStack resultTile() {
        if (spinning) {
            return Menus.icon(Material.PAPER, "&7Spinning…");
        }
        if (spin == null) {
            return Menus.icon(Material.PAPER, "&7Pick your tokens, then Spin.", "&7Only the line between the arrows counts.");
        }
        return Menus.glint(Menus.icon(Material.PAPER, SlotsCopy.result(spin)), spin.win());
    }

    private ItemStack spinTile(int left) {
        if (spinning) {
            return Menus.icon(Material.GRAY_CONCRETE, "&7Spinning…");
        }
        if (left == 0) {
            return Menus.icon(Material.GRAY_CONCRETE, "&7No plays left today",
                    "&7Ore Slots gives " + settings.dailyLimit() + " spins a day.", "&7They come back at midnight.");
        }
        int balance = plugin.tokens() == null ? 0 : plugin.tokens().balance(viewer.getUniqueId());
        if (balance < stake) {
            return Menus.icon(Material.GRAY_CONCRETE, "&cNeed " + (stake - balance) + " more tokens",
                    "&7A spin is " + SlotsCopy.tokens(stake) + " in.");
        }
        return Menus.icon(Material.LIME_CONCRETE, "&a&lSpin &7- &6" + SlotsCopy.tokens(stake) + " in",
                "&7The spin is decided the moment you click.", "&7Closing the screen just shows the result.");
    }

    private ItemStack stakeTile(int s) {
        boolean chosen = s == stake;
        StakeOdds odds = engine.odds(s);
        String line = odds == null ? "" : SlotsCopy.giveBack(odds).replace("&e", "&7");
        ItemStack icon = Menus.icon(Material.GOLD_NUGGET,
                (chosen ? "&a&l" : "&e") + SlotsCopy.tokens(s) + " in",
                chosen ? "&7Picked." : "&eClick to pick", line);
        return Menus.glint(Menus.count(icon, s), chosen);
    }

    private static Material material(Symbol s) {
        return switch (s) {
            case COAL -> Material.COAL;
            case COPPER -> Material.COPPER_INGOT;
            case IRON -> Material.IRON_INGOT;
            case GOLD -> Material.GOLD_INGOT;
            case DIAMOND -> Material.DIAMOND;
            case WILD -> Material.NETHER_STAR;
            case STONE -> Material.COBBLESTONE;
        };
    }

    private static Material lineMaterial(Line line) {
        return line.symbol() == null ? Material.IRON_NUGGET : material(line.symbol());
    }

    // ---- clicks --------------------------------------------------------------------------------

    private void pick(int s) {
        if (spinning || s == stake) {
            return;
        }
        stake = s;
        refresh();
    }

    /** Play one spin (gate, seed, outcome, tokens in and back: all done here), then show it. */
    private void spin() {
        if (spinning) {
            return;
        }
        GamesService games = plugin.games();
        if (games == null) {
            viewer.sendMessage(Text.of("&c" + Refusal.CLOSED.message()));
            Sounds.refused(viewer);
            return;
        }
        Spin result = games.rounds().play(viewer, game, stake, engine);
        if (result == null) {
            refresh(); // refused, and already told why
            return;
        }
        spin = result;
        spinning = true;
        told = false;
        tick = 0;
        stopped = 0;
        cosmetic = new SplittableRandom(ThreadLocalRandom.current().nextLong());
        shown = ReelShow.frame(engine, stake, result.reels(), 0, cosmetic);
        getInventory().setItem(RESULT, resultTile());
        getInventory().setItem(SPIN, spinTile(1));
        paintReels();
        task = ticker(ReelShow.period(bedrock), this::step);
    }

    /** One frame: stop the reels whose tick has come, spin the others. */
    private void step() {
        if (!spinning) {
            return;
        }
        tick += ReelShow.period(bedrock);
        int now = ReelShow.stopped(bedrock, tick);
        shown = ReelShow.frame(engine, spin.stake(), spin.reels(), now, cosmetic);
        paintReels();
        if (now > stopped) {
            stopped = now;
            viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.8f + 0.2f * now);
        }
        if (now >= REELS.length) {
            land();
        }
    }

    private void land() {
        spinning = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        refresh();
        tell();
    }

    private void paintReels() {
        for (int i = 0; i < REELS.length; i++) {
            getInventory().setItem(REELS[i], reelTile(shown.get(i)));
        }
    }

    /** The result, once: when the reels stop, or when the screen closes first. */
    private void tell() {
        if (told || spin == null) {
            return;
        }
        told = true;
        viewer.sendMessage(Text.of(SlotsCopy.result(spin)));
        if (!spin.win()) {
            Sounds.miss(viewer);
            return;
        }
        Sounds.won(viewer);
        if (spin.big()) {
            String[] t = SlotsCopy.title(spin);
            try {
                viewer.showTitle(Title.title(Text.of(t[0]), Text.of(t[1]),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(500))));
            } catch (RuntimeException ignored) {
                // cosmetic: the tokens are already paid and the chat line said so
            }
        }
    }

    @Override
    protected void onClose(Player player) {
        super.onClose(player);
        task = null;
        if (spinning) {
            spinning = false;
            shown = spin.reels();
        }
        tell();
    }

    private static List<Symbol> blank() {
        List<Symbol> out = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            out.add(null);
        }
        return out;
    }
}
