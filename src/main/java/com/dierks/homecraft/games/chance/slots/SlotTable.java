package com.dierks.homecraft.games.chance.slots;

import com.dierks.homecraft.games.chance.slots.SlotsEngine.StakeOdds;

import java.util.List;
import java.util.Map;

/**
 * A slot machine's settings, as the shared machine and its screen read them: whichever record they come
 * from ({@link OreSlotsSettings} for Ore Slots, {@link SlotSettings} for the themed machines), this is the table
 * the one {@link SlotsEngine} was solved from.
 */
public interface SlotTable {

    /** How the machine looks and what it calls things. */
    SlotTheme theme();

    /** The machine's own switch. */
    boolean enabled();

    /** What a spin may put in, smallest first (a pot machine: its one set price, in dollars). */
    List<Integer> stakes();

    /** Spins a player gets a day. */
    int dailyLimit();

    /** The target return, a percent: what the machine as a whole aims to give back. */
    double rtp();

    /** The reel weights as the engine reads them, by {@link Symbol#key()}. */
    Map<String, Integer> engineReels();

    /** The line multiples as the engine reads them, by {@link Line#key()}. */
    Map<String, Integer> enginePays();

    /** The odds and the draw, solved from exactly these values. */
    SlotsEngine engine();

    /**
     * The stakes a player is offered, smallest first: the ones the solve could fit inside the band. (Deep Mine: what
     * goes on each row.)
     */
    default List<Integer> offered() {
        return engine().stakes();
    }

    /**
     * The engine a spin of {@code stake} tokens is decided by. One engine holds every stake of most machines; Deep
     * Mine and Mob Slots solve each stake on its own ({@link SlotBank}), and theirs is {@code null} for a stake they
     * do not offer.
     */
    default SlotsEngine engine(int stake) {
        return engine();
    }

    /** Free spins three of the Wild's symbol on the line give (Mob Slots); 0 on every other machine. */
    default int freeSpins() {
        return 0;
    }

    /** A pot machine: money only, one set price, a pot every spin feeds and three Wilds takes. */
    default boolean pot() {
        return false;
    }

    /** The share of every spin's price that goes into the pot, a whole percent (0 for a machine with no pot). */
    default int feedPercent() {
        return 0;
    }

    /**
     * The target the LINES are solved for, a percent: {@link #rtp()} less what goes into the pot, so the lines and
     * the pot together give back the target.
     */
    default double linesRtp() {
        return rtp() - feedPercent();
    }

    /**
     * What a stake really gives back, a fraction: the lines' exact return plus the share that goes into the pot
     * (every cent of which is paid out again when the pot is taken). The number players read is THIS, floored.
     */
    default double givesBack(StakeOdds odds) {
        return odds.rtp() + feedPercent() / 100.0;
    }
}
