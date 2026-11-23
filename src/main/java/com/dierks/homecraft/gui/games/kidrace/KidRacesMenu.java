package com.dierks.homecraft.gui.games.kidrace;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.chance.mobraces.MobRaces.Phase;
import com.dierks.homecraft.games.chance.mobraces.RaceCard;
import com.dierks.homecraft.games.chance.mobraces.Racer;
import com.dierks.homecraft.games.kidrace.KidRaces;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.ClickHold;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The Arcade Room's Mob Races screen (54 slots), made for a four-year-old with a controller who can't read yet: the
 * six racers as big pictures, and one tap on a racer enters the race. Nothing on it needs reading: each racer is its
 * number (the stack's count), its colour and its mob.
 *
 * <pre>
 *  rows 0-1   racers 1, 2, 3: a block of 3 x 2 each, its mob's spawn egg in the middle of its colour
 *  rows 2-3   racers 4, 5, 6
 *  row 4      40 the clock: how many seconds until the race starts (the stack's count)
 *  row 5      a big green "go" picture once you're in (the race starts when everyone in it tapped it) · 49 All done
 * </pre>
 *
 * When the race starts the screen closes, so the race is watched on the track. When it is over (the track free
 * again) the kid screen opens for each entrant still in the room ({@link #ended}): the winner, one big green Play again
 * picture over everything under it, All done, and a short pause so a tapping finger doesn't start the next one.
 */
public final class KidRacesMenu extends GameMenu {

    /** Each racer's 3 x 2 block: its top-left slot, by lane. */
    private static final int[] BLOCK = {0, 3, 6, 18, 21, 24};
    private static final int CLOCK = 40;
    private static final int WINNER = 4;

    private final KidRaces races;
    private boolean watching;
    /** The race that just ended, for the kid screen; {@code null} for the racers' screen. */
    private Racer won;
    private boolean mineWon;

    public KidRacesMenu(HomeCraftManagement plugin, KidRaces game, Player viewer, Runnable back) {
        super(plugin, game, viewer, back);
        this.races = game;
        init(54, Text.of("&2Mob Races"));
    }

    /** This screen as the kid screen after a race {@code winner} won ({@code mine}: it was the viewer's pick). */
    public KidRacesMenu ended(Racer winner, boolean mine) {
        this.won = winner;
        this.mineWon = mine;
        return this;
    }

    @Override
    public void open(Player player) {
        super.open(player);
        if (won != null) {
            hold(KID_SETTLE_MS); // the result is seen before a stray tap starts the next race
            return;
        }
        if (showing()) {
            watching = true;
            races.watch(viewer.getUniqueId(), this::raceChanged);
        }
    }

    @Override
    protected void onClose(Player player) {
        super.onClose(player);
        if (watching) {
            watching = false;
            races.unwatch(viewer.getUniqueId());
        }
    }

    /** The race changed: paint it again, or, once it is on, close so the race is watched on the track. */
    private void raceChanged() {
        if (races.phase() == Phase.RUNNING || races.phase() == Phase.RESULT) {
            if (showing()) {
                closeNow(viewer);
            }
            return;
        }
        refresh();
    }

    // ---- what a picture says (pure) ---------------------------------------------------------------------------

    /** "★★★☆☆": a rating of 1 to 5 as gold stars and grey ones. */
    static String stars(int rating) {
        int r = Math.max(0, Math.min(5, rating));
        return "&e" + "★".repeat(r) + "&8" + "☆".repeat(5 - r);
    }

    /** A racer's lines (pure): stars only, for whoever can read them. */
    static List<String> racerLines(Racer r, RaceCard card) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Today  " + stars(KidRaces.stars(card, r)));
        lore.add("&7Speed  " + stars(r.speed()));
        lore.add("&7Stamina " + stars(r.stamina()));
        lore.add("&7Start   " + stars(r.start()));
        return lore;
    }

    /** The racer's picture: its own mob's spawn egg, or its colour. */
    static Material egg(Racer r) {
        Material m = Material.getMaterial(r.mob() + "_SPAWN_EGG");
        return m == null ? colour(r) : m;
    }

    /** The racer's colour, as a block every client draws. */
    static Material colour(Racer r) {
        Material m = Material.getMaterial(r.dye() + "_CONCRETE");
        return m == null ? Material.WHITE_CONCRETE : m;
    }

    // ---- painting -----------------------------------------------------------------------------------------

    @Override
    protected void build() {
        fill();
        if (won != null) {
            paintEnded();
            return;
        }
        KidRaces.Entry me = races.entry(viewer.getUniqueId());
        RaceCard card = races.card();
        for (Racer r : Racer.values()) {
            paintRacer(r, card, me);
        }
        paintClock();
        if (me != null) {
            paintGo(me);
        }
        kidExit();
    }

    private void paintRacer(Racer r, RaceCard card, KidRaces.Entry me) {
        boolean mine = me != null && me.pick() == r;
        String name = r.label() + (mine ? " &f- yours!" : "");
        String[] lore = racerLines(r, card).toArray(new String[0]);
        int top = BLOCK[r.lane()];
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 3; col++) {
                int slot = top + row * 9 + col;
                Material m = row == 0 && col == 1 ? egg(r) : colour(r);
                ItemStack icon = Menus.count(Menus.glint(Menus.icon(m, name, lore), mine), r.number());
                set(slot, icon, me != null ? null : e -> {
                    hold(ClickHold.ACTION_MS);
                    if (races.enter(viewer, r)) {
                        tap();
                    }
                    if (showing()) {
                        refresh();
                    }
                });
            }
        }
    }

    private void paintClock() {
        if (races.phase() != Phase.GATHERING) {
            set(CLOCK, Menus.icon(Material.SADDLE, "&aPick a racer!"), null);
            return;
        }
        int left = Math.max(1, races.secondsLeft());
        set(CLOCK, Menus.count(Menus.glint(Menus.icon(Material.CLOCK, "&eThe race starts in " + left + " s"), true),
                left), null);
    }

    /** Once in: the bottom row is one big green "go" picture (the start when everyone in it tapped it). */
    private void paintGo(KidRaces.Entry me) {
        ItemStack go = me.startNow()
                ? Menus.glint(Menus.icon(Material.LIME_STAINED_GLASS_PANE, "&aReady!"), true)
                : Menus.glint(Menus.icon(Material.LIME_CONCRETE, "&a&l▶ Go!"), true);
        for (int slot = 45; slot <= 53; slot++) {
            set(slot, go, me.startNow() ? null : e -> {
                hold(ClickHold.ACTION_MS);
                races.startNow(viewer);
            });
        }
    }

    /** The kid screen: the winner on top, Play again over everything under it, All done. */
    private void paintEnded() {
        ItemStack winner = Menus.count(Menus.glint(Menus.icon(egg(won), won.label() + (mineWon ? " &f- yours!" : "")),
                true), won.number());
        set(WINNER, winner, null);
        Material burst = mineWon ? Material.FIREWORK_ROCKET : colour(won);
        for (int slot : new int[]{WINNER - 2, WINNER - 1, WINNER + 1, WINNER + 2}) {
            set(slot, Menus.count(Menus.icon(burst, won.label()), won.number()), null);
        }
        bigAgain(slots(9, 53), () -> plugin.games().open(viewer, KidRaces.SPEC.id(), null));
        kidExit();
    }

    /** A happy little sound for a tap that entered. */
    private void tap() {
        try {
            viewer.playSound(viewer.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_CHIME, 0.9f, 1.4f);
        } catch (RuntimeException | LinkageError ignored) {
            // a sound never stops the entry
        }
    }
}
