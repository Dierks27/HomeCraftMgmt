package com.dierks.homecraft.games;

import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * The pot machines' pots (the owner: "can we make a slot machine ... where you can have a building pot",
 * and of Farm Slots "Lets make it the small-pot machine").
 *
 * <p>A pot machine (a slot machine with one set price, played for money in the Game Parlor) has one pot everyone
 * sees. What players read as "the pot" is two parts:
 * <ul>
 *   <li><b>the seed</b>: what the machine's three-Wilds line pays on its own. It is part of the machine's paytable
 *       and is paid through the round like any line, so it is never kept here;</li>
 *   <li><b>what the spins added</b>: a share of every spin's price. THAT is what this class keeps, in cents, one
 *       number per machine, in the database ({@code hcm_meta}, {@code gen.parlor.pot.<game>}) so a restart keeps
 *       the pot.</li>
 * </ul>
 * Three Wilds takes both: the seed through the round, and the added part on top ({@link #take}), after which the pot
 * reads as its seed again. The whole server is told (a moment later, once the reels have stopped), and the console.
 *
 * <p><b>Never twice.</b> Everything here runs on the server's main thread. {@link #take} writes the kept amount back
 * to zero FIRST and pays only what that write let go of; if the bank then refuses, the amount is kept as owed to the
 * player by the Parlor's own owed money ({@link Parlor#giveCents}), which pays it later, once. If the zero can't be
 * written (or the pot can't even be read), nothing is paid from the pot and it stays for the next three Wilds (the
 * player still has the line's own pay, through the round), and nothing is ever written over a pot that could not be
 * read.
 */
public final class Pots {

    /** Where each pot's added cents are kept: {@code gen.parlor.pot.<gameId>}. */
    static final String KEY = Parlor.KEY + "pot.";
    /**
     * Today's pots, everyone's: {@code day:0:cents taken on top of the rounds}. The rounds' own totals
     * ({@code /hcm games parlor}'s "given back") can't see that part, so it is counted here.
     */
    static final String KEY_TAKEN = Parlor.KEY + "pots_taken";
    /**
     * How long after the spin the server is told, in ticks: just past the slowest reels (a Bedrock player's stop at
     * 30), so the line never gives the result away to the player still watching them turn. The pot itself is taken
     * and paid at once.
     */
    static final long TELL_AFTER_TICKS = 32;

    private final GamesService games;
    private final Parlor parlor;
    private final Parlor.Store store;
    /** Each machine's added cents as last read or written (the store is read once per machine). */
    private final Map<String, Long> added = new HashMap<>();
    /** Today's cents taken on top of the rounds, when there is no store: {day, cents}. */
    private final long[] memoryTaken = new long[2];

    Pots(GamesService games, Parlor parlor, Parlor.Store store) {
        this.games = games;
        this.parlor = parlor;
        this.store = store;
    }

    /** What the spins have added to {@code gameId}'s pot, in cents (0 for a pot never fed). */
    public long added(String gameId) {
        return Math.max(0, read(gameId));
    }

    /**
     * {@link #added}, or -1 when the kept amount can't be read right now. Nothing is written over a pot that could
     * not be read: a spin's share is not added and the pot is not taken until it can be (it would be writing a
     * guess over the real amount).
     */
    private long read(String gameId) {
        Long known = added.get(gameId);
        if (known != null) {
            return known;
        }
        long read = 0;
        if (store != null) {
            try {
                String text = store.get(KEY + gameId);
                read = text == null ? 0 : Math.max(0, Long.parseLong(text.trim()));
            } catch (NumberFormatException e) {
                games.host().logger().warning("The kept amount of " + gameId + "'s pot is not a number - it starts"
                        + " again from nothing added");
            } catch (SQLException | RuntimeException e) {
                games.host().logger().log(Level.WARNING, "Could not read " + gameId + "'s pot - it reads as its"
                        + " starting amount until it can be", e);
                return -1; // not remembered: the next ask reads again
            }
        }
        added.put(gameId, read);
        return read;
    }

    /**
     * A spin was really played: add its share to the pot, but never past the Parlor's pot cap (what players read,
     * the seed and the added part together, stops at the cap; a spin's share that doesn't fit simply isn't added).
     *
     * @param cents     the spin's share
     * @param seedCents what the pot starts at (the three-Wilds line's own pay)
     * @return the cents added now
     */
    public long feed(String gameId, long cents, long seedCents) {
        if (cents <= 0) {
            return 0;
        }
        long now = read(gameId);
        if (now < 0) {
            return 0; // the pot can't be read: nothing is written over it
        }
        long more = cents;
        int cap = parlor.rules().potCap();
        if (cap > 0) {
            more = Math.max(0, Math.min(cents, cap * 100L - Math.max(0, seedCents) - now));
        }
        if (more <= 0) {
            return 0;
        }
        keep(gameId, now + more); // a write that fails is logged; the pot still grows until the next restart
        added.put(gameId, now + more);
        return more;
    }

    /**
     * Three Wilds: {@code player} takes {@code game}'s pot. The added part is written back to zero, then paid on top
     * of the round (or kept as owed to the player until the bank takes it); the server is told, and so is the
     * console.
     *
     * @param seedCents what the round itself just paid for the line (the pot's seed), for the lines told
     * @return the cents of the added part that are the player's now (paid, or kept for them); 0 when there were none
     */
    public long take(Player player, Game game, long seedCents) {
        String gameId = game.id();
        long cents = read(gameId);
        if (cents < 0) {
            games.host().logger().severe("[Parlor] " + game.name() + "'s pot could not be read, so what the spins"
                    + " added was NOT paid to " + player.getName() + " and stays in the pot");
            cents = 0;
        } else if (cents > 0 && !keep(gameId, 0)) {
            games.host().logger().severe("[Parlor] " + game.name() + "'s pot could not be set back, so the "
                    + Parlor.money(cents) + " the spins added was NOT paid to " + player.getName()
                    + " and stays in the pot");
            cents = 0;
        } else {
            added.put(gameId, 0L);
        }
        boolean paid = cents <= 0 || parlor.giveCents(player.getUniqueId(), cents, game.name() + " pot");
        tally(cents);
        long total = Math.max(0, seedCents) + cents;
        games.host().logger().info("[Parlor] " + player.getName() + " took " + game.name() + "'s pot: "
                + Parlor.money(total) + " (" + Parlor.money(Math.max(0, seedCents)) + " through the round, "
                + Parlor.money(cents) + " on top" + (paid ? "" : ", kept until the bank takes it") + ")");
        String line = "&6" + player.getName() + " &ejust took the pot on &6" + game.name() + "&e: &a"
                + Parlor.money(total) + "&e!";
        Runnable tell = () -> games.host().broadcast(line);
        if (games.host().later(TELL_AFTER_TICKS, tell) == null) {
            tell.run(); // nothing can be scheduled (the server is stopping): say it now
        }
        return cents;
    }

    /**
     * The cents taken out of the pots today on top of their rounds (the added parts), for the owner's status: what
     * "given back" misses.
     */
    public long takenToday() {
        long day = games.host().clock().dayKey();
        if (store == null) {
            return memoryTaken[0] == day ? memoryTaken[1] : 0;
        }
        try {
            return Parlor.dayTally(store.get(KEY_TAKEN), day)[1];
        } catch (SQLException | RuntimeException e) {
            return 0;
        }
    }

    private void tally(long cents) {
        if (cents <= 0) {
            return;
        }
        long day = games.host().clock().dayKey();
        long total = takenToday() + cents;
        if (store == null) {
            memoryTaken[0] = day;
            memoryTaken[1] = total;
            return;
        }
        try {
            store.set(KEY_TAKEN, day + ":0:" + total);
        } catch (SQLException | RuntimeException e) {
            games.host().logger().log(Level.WARNING, "Could not keep today's pot totals", e);
        }
    }

    /** Write {@code gameId}'s added cents; false (logged) when the database would not take it. */
    private boolean keep(String gameId, long cents) {
        if (store == null) {
            return true;
        }
        try {
            store.set(KEY + gameId, Long.toString(cents));
            return true;
        } catch (SQLException | RuntimeException e) {
            games.host().logger().log(Level.SEVERE, "Could not keep " + gameId + "'s pot (" + Parlor.money(cents)
                    + ")", e);
            return false;
        }
    }
}
