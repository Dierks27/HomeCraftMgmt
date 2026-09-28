package com.dierks.homecraft.gui.games;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.gui.Menu;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * The base of every games screen (spec R3.4): a {@link Menu} that belongs to one {@link Game} and
 * so can't take the rest of the plugin down with it.
 *
 * <p>Every click handler set here runs inside the game's guard: if it throws, that one game is
 * switched off, the screen closes and the player reads "That game is taking a break. Try another
 * one!" — nothing reaches the menu listener. The first paint ({@link #open}) and the close
 * handling run inside it too; a screen that belongs to no game (the Games screen, Take a break)
 * logs what it throws instead. Real-time games tick through {@link #ticker}, which
 * stops by itself when the screen closes, is replaced by another, or the game fails, so a
 * forgotten task can never keep running for a screen nobody is looking at. It is also a
 * {@link GameScreen}, the only kind of screen a player in a world session may open.
 *
 * <p>A double click never reaches a handler as three clicks on a repainted screen: its
 * {@code DOUBLE_CLICK} is dropped, and a screen that just changed under the cursor calls
 * {@link #hold} so the rest of it is dropped too ({@link ClickHold}).
 *
 * <p>Subclasses call {@code init(size, title)} in their constructor and paint every slot in
 * {@code build()} (filler first, {@link #fill()}).
 */
public abstract class GameMenu extends Menu implements GameScreen {

    protected final Game game;
    protected final Player viewer;
    /** What "Back" does, or {@code null} for "Close". */
    protected final Runnable back;
    private final List<BukkitTask> tickers = new ArrayList<>();
    private final ClickHold clicks = new ClickHold(() -> System.nanoTime() / 1_000_000L);

    protected GameMenu(HomeCraftManagement plugin, Game game, Player viewer, Runnable back) {
        super(plugin);
        this.game = game;
        this.viewer = viewer;
        this.back = back;
    }

    /** The game this screen belongs to. */
    public Game game() {
        return game;
    }

    /** Who it was opened for. */
    public Player viewer() {
        return viewer;
    }

    /** Whether the viewer is looking at this screen now (false after an open something cancelled). */
    public boolean showing() {
        return isOpenFor(viewer);
    }

    /**
     * The game whose screen {@code player} has open, or {@code null} (no games screen, or a
     * shared one that belongs to no game).
     */
    public static Game gameOnScreen(Player player) {
        try {
            return player != null && player.getOpenInventory().getTopInventory().getHolder(false) instanceof GameMenu m
                    ? m.game() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * As {@link Menu#set}, with the handler run inside the game's guard. A double click's
     * {@code DOUBLE_CLICK}, and any click inside a {@link #hold}, never reaches it.
     */
    @Override
    protected void set(int slot, ItemStack item, Consumer<InventoryClickEvent> onClick) {
        super.set(slot, item, onClick == null ? null : e -> {
            if (!clicks.passes(e.getClick())) {
                return;
            }
            if (!guarded(() -> {
                onClick.accept(e);
                return true;
            })) {
                broken();
            }
        });
    }

    /**
     * Drop every click on this screen for {@code ms} (see {@link ClickHold}): call it when a click
     * turns this screen into another layout ({@link ClickHold#SETTLE_MS} after a deal or an armed
     * "click again"), or after each move of a game of chance ({@link ClickHold#ACTION_MS}).
     */
    protected void hold(long ms) {
        clicks.hold(ms);
    }

    /** The first paint and the open, inside the game's guard: a throw fails the game, not the caller. */
    @Override
    public void open(Player player) {
        if (!guarded(() -> {
            super.open(player);
            return true;
        })) {
            broken();
        }
    }

    /** The close handling (this screen's {@code onClose}), inside the game's guard. */
    @Override
    protected void handleClose(Player player) {
        guarded(() -> {
            super.handleClose(player);
            return true;
        });
    }

    /** Paint every slot with the filler pane (first thing in {@code build()}). */
    protected void fill() {
        for (int i = 0; i < getInventory().getSize(); i++) {
            set(i, Menus.FILLER, null);
        }
    }

    /**
     * The way out, in the bottom row's middle slot (49 on a 54-slot screen, 22 on a 27-slot one):
     * "&amp;cBack" when there is somewhere to go back to, else "&amp;cClose".
     */
    protected void exitTile() {
        set(getInventory().getSize() - 5, Menus.icon(Material.BARRIER, back != null ? "&cBack" : "&cClose"), e -> {
            if (back != null) {
                back.run();
            } else {
                e.getWhoClicked().closeInventory();
            }
        });
    }

    /** The token balance tile: "&amp;eYou have &amp;6N tokens". */
    protected ItemStack balanceTile() {
        int tokens = plugin.tokens() == null ? 0 : plugin.tokens().balance(viewer.getUniqueId());
        return Menus.icon(Material.SUNFLOWER, "&eYou have &6" + tokens + " token" + (tokens == 1 ? "" : "s"));
    }

    /** A "How to play" tile with the rules as lore. */
    protected ItemStack rulesTile(List<String> rules) {
        List<String> lore = new ArrayList<>(rules.size());
        for (String line : rules) {
            lore.add("&7" + line);
        }
        return Menus.icon(Material.BOOK, "&eHow to play", lore.toArray(new String[0]));
    }

    /**
     * Run {@code task} every {@code period} ticks while this screen is open for its viewer, inside
     * the game's guard. It stops by itself when the screen closes or is replaced, and if the task
     * throws (the screen then closes with the "taking a break" line).
     */
    protected BukkitTask ticker(long period, Runnable task) {
        BukkitTask[] self = new BukkitTask[1];
        long p = Math.max(1, period);
        self[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!isOpenFor(viewer)) {
                if (self[0] != null) {
                    self[0].cancel();
                }
                return;
            }
            if (!guarded(() -> {
                task.run();
                return true;
            })) {
                cancelTickers();
                broken();
            }
        }, p, p);
        tickers.add(self[0]);
        return self[0];
    }

    /** Closing the screen stops its tickers. Subclasses that override this call {@code super}. */
    @Override
    protected void onClose(Player player) {
        cancelTickers();
    }

    private void cancelTickers() {
        for (BukkitTask t : tickers) {
            t.cancel();
        }
        tickers.clear();
    }

    /**
     * True if {@code work} ran without throwing: through the game's guard when there is one (a
     * throw fails the game), else caught and logged (a screen that belongs to no game).
     */
    private boolean guarded(Supplier<Boolean> work) {
        GamesService games = plugin.games();
        if (games != null && game != null) {
            return games.guard(game, work, false);
        }
        try {
            return work.get();
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.SEVERE, (game == null ? "A games" : "A " + game.id()) + " screen failed", e);
            return false;
        }
    }

    /** The game failed under this screen: close it (its close handling guarded too) and say so. */
    private void broken() {
        closeNow(viewer);
        viewer.sendMessage(Text.of("&c" + Refusal.BROKEN.message()));
        Sounds.refused(viewer);
    }
}
