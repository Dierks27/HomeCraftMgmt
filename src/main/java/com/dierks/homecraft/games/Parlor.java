package com.dierks.homecraft.games;

import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The Game Parlor's own rules (0.46.0): playing the games of chance for money, and keeping them to the Parlor.
 *
 * <p>The owner: "For the Casino I think we should be able to play with our money instead of tokens ... Being able to
 * choose between if you want to play for tokens or money would be ideal", "Daily Limit of 250 - I just dont want to
 * knock the balance of the server off ... Stakes can be in dollar amounts in 5's ... you click the 5, 10 or 25 to
 * simply add to the amount", "Only in the parlor - Not in the arcade. In fact the games can then come off the arcade
 * so kiddos dont even see them", and (the limits) "Can you make these commands instead?".
 *
 * <p><b>Money play.</b> A player who has {@link #PERMISSION_MONEY} (nobody has it until an admin gives it) and stands
 * in the Clubhouse's Game Parlor may put dollars into a game of chance there instead of tokens: whole dollars, built
 * from the chips, up to the biggest stake, and no more a day than the day's limit. The game is the same game: every
 * other step of the play gate still runs (the games-of-chance permission, Take a break, the cooldown, the game's
 * plays a day), each game solves what it pays for the dollars put in exactly as it does for tokens (so it gives back
 * 85 to 95 of every 100 either way), and nothing it pays is over the biggest payout.
 *
 * <p><b>Everything here is set by command</b> ({@code /hcm games parlor}), never in config.yml, and kept in the
 * database ({@code hcm_meta}, under {@code gen.parlor.}); a player's own day limit is a {@code game_prefs} row.
 *
 * <p><b>Only in the Parlor.</b> While {@link Rules#onlyHere} is on and the Clubhouse has its Parlor, the games of
 * chance are played nowhere else: the Games screen, the Arcade and {@code /hcm play} stop offering them.
 */
public final class Parlor {

    /** Playing for money in the Game Parlor. Nobody has it by default. */
    public static final String PERMISSION_MONEY = "hcm.games.money";
    /** No day limit. */
    public static final int NO_LIMIT = -1;
    /** The most chips the screen has room for. */
    public static final int MAX_CHIPS = 3;
    /** The biggest stake an admin can set. */
    public static final int STAKE_CEILING = 1000;
    /** The biggest payout an admin can set. */
    public static final int PAYOUT_CEILING = 100_000;

    static final String KEY = "gen.parlor.";
    static final String KEY_ONLY = KEY + "only";
    static final String KEY_MONEY = KEY + "money";
    static final String KEY_LIMIT = KEY + "limit";
    static final String KEY_STAKE = KEY + "max_stake";
    static final String KEY_PAYOUT = KEY + "max_payout";
    static final String KEY_CHIPS = KEY + "chips";
    /** A player's own day limit ({@code game_prefs}): dollars, or {@link #NO_LIMIT}. */
    static final String PREF_LIMIT = "parlor.limit";

    public static final Refusal MONEY_CLOSED = Refusal.of("Playing for money is closed right now.");
    public static final Refusal MONEY_NOT_YOURS = new Refusal(Refusal.Reason.NO_PERMISSION,
            "Playing for money isn't open to you.");
    public static final Refusal MONEY_NOT_HERE = Refusal.of("Playing for money is only in the Game Parlor.");
    /** A game of chance opened anywhere but the Parlor while they are kept to it. */
    public static final Refusal ONLY_HERE = new Refusal(Refusal.Reason.CLOSED,
            "That game is played in the Clubhouse's Game Parlor.");

    /**
     * The rules as set.
     *
     * @param onlyHere  the games of chance are played only in the Parlor (while the Clubhouse has one)
     * @param money     money play is on
     * @param limit     dollars a player may put in a day, or {@link #NO_LIMIT}
     * @param maxStake  the biggest stake, in dollars
     * @param maxPayout the most one play gives back, in dollars
     * @param chips     the chips a stake is built from, smallest first (1 to {@link #MAX_CHIPS})
     */
    public record Rules(boolean onlyHere, boolean money, int limit, int maxStake, int maxPayout, List<Integer> chips) {

        public Rules {
            chips = List.copyOf(chips);
        }

        /** As shipped: the owner's numbers. */
        public static Rules defaults() {
            return new Rules(true, true, 250, 50, 500, List.of(5, 10, 25));
        }

        /** The smallest stake: the smallest chip. */
        public int minStake() {
            return chips.get(0);
        }

        /** The most a play may give back: never under the stake itself. */
        public int capFor(int stake) {
            return Math.max(maxPayout, stake);
        }
    }

    /** Where the rules are kept ({@code hcm_meta}); {@code null} in a test that keeps them in memory. */
    public interface Store {

        String get(String key) throws SQLException;

        void set(String key, String value) throws SQLException;
    }

    private final GamesService games;
    private final Store store;
    private volatile Rules rules = Rules.defaults();

    Parlor(GamesService games, Store store) {
        this.games = games;
        this.store = store;
        load();
    }

    /** The rules now. */
    public Rules rules() {
        return rules;
    }

    /** Read the rules from the store (a key never set reads as shipped; nonsense reads as shipped, with a WARN). */
    void load() {
        if (store == null) {
            return;
        }
        Rules d = Rules.defaults();
        try {
            rules = new Rules(flag(store.get(KEY_ONLY), d.onlyHere()), flag(store.get(KEY_MONEY), d.money()),
                    whole(store.get(KEY_LIMIT), d.limit(), NO_LIMIT, Integer.MAX_VALUE),
                    whole(store.get(KEY_STAKE), d.maxStake(), 1, STAKE_CEILING),
                    whole(store.get(KEY_PAYOUT), d.maxPayout(), 1, PAYOUT_CEILING),
                    chips(store.get(KEY_CHIPS), d.chips()));
        } catch (SQLException | RuntimeException e) {
            games.host().logger().log(Level.WARNING, "Could not read the Game Parlor's rules - using the shipped ones",
                    e);
            rules = d;
        }
    }

    // ---- setting them (the admin command) ---------------------------------------------------------------

    public void onlyHere(boolean on) {
        save(KEY_ONLY, on ? "1" : "0", new Rules(on, rules.money(), rules.limit(), rules.maxStake(),
                rules.maxPayout(), rules.chips()));
    }

    public void money(boolean on) {
        save(KEY_MONEY, on ? "1" : "0", new Rules(rules.onlyHere(), on, rules.limit(), rules.maxStake(),
                rules.maxPayout(), rules.chips()));
    }

    /** The day's limit for everyone: dollars, or {@link #NO_LIMIT}. */
    public void limit(int dollars) {
        int v = dollars < 0 ? NO_LIMIT : dollars;
        save(KEY_LIMIT, Integer.toString(v), new Rules(rules.onlyHere(), rules.money(), v, rules.maxStake(),
                rules.maxPayout(), rules.chips()));
    }

    public void maxStake(int dollars) {
        int v = Math.max(1, Math.min(STAKE_CEILING, dollars));
        save(KEY_STAKE, Integer.toString(v), new Rules(rules.onlyHere(), rules.money(), rules.limit(), v,
                rules.maxPayout(), rules.chips()));
    }

    public void maxPayout(int dollars) {
        int v = Math.max(1, Math.min(PAYOUT_CEILING, dollars));
        save(KEY_PAYOUT, Integer.toString(v), new Rules(rules.onlyHere(), rules.money(), rules.limit(),
                rules.maxStake(), v, rules.chips()));
    }

    public void chips(List<Integer> chips) {
        List<Integer> v = chips(text(chips), rules.chips());
        save(KEY_CHIPS, text(v), new Rules(rules.onlyHere(), rules.money(), rules.limit(), rules.maxStake(),
                rules.maxPayout(), v));
    }

    /**
     * One player's own day limit: dollars, {@link #NO_LIMIT} for none, or {@code null} to follow everyone's again.
     *
     * @return false if it could not be kept
     */
    public boolean limit(UUID player, Integer dollars) {
        try {
            if (dollars == null) {
                games.dao().deletePref(player, PREF_LIMIT);
            } else {
                games.dao().setPref(player, PREF_LIMIT, Integer.toString(dollars < 0 ? NO_LIMIT : dollars));
            }
            return true;
        } catch (SQLException e) {
            games.host().logger().log(Level.SEVERE, "Could not keep a player's Game Parlor limit", e);
            return false;
        }
    }

    private void save(String key, String value, Rules next) {
        rules = next;
        if (store == null) {
            return;
        }
        try {
            store.set(key, value);
        } catch (SQLException | RuntimeException e) {
            games.host().logger().log(Level.SEVERE, "Could not keep the Game Parlor's " + key
                    + " - it holds until the next restart only", e);
        }
    }

    // ---- asking -----------------------------------------------------------------------------------------

    /** Whether the games of chance are kept to the Parlor right now (the rule is on, and there is a Parlor). */
    public boolean onlyHere() {
        return rules.onlyHere() && games.parlorOpen();
    }

    /**
     * Whether {@code game} is offered to {@code player} on the Games screen and by {@code /hcm play}'s Tab now: a
     * game of chance kept to the Parlor is offered only to someone standing in it. The console ({@code null}) is
     * offered everything.
     */
    public boolean listed(Game game, Player player) {
        if (game == null || !game.kind().chance() || player == null) {
            return true;
        }
        return games.inParlor(player.getUniqueId()) || !onlyHere();
    }

    /** What buying a Scratch Ticket anywhere else answers while it is kept to the Parlor (0.46.1). */
    public static final String TICKET_ONLY_HERE = "The Scratch Ticket is in the Clubhouse's Game Parlor.";

    /**
     * 0.46.1 (the owner: "Scratch ticket is lottery... I would put that in the parlor as well"): whether the Scratch
     * Ticket is offered to {@code player} where they are. It goes where the games of chance go: kept to the Parlor
     * with them, it is offered only to someone standing there. Crates and Card Packs are not: they stay in the Arcade.
     */
    public boolean ticketListed(Player player) {
        return player == null || games.inParlor(player.getUniqueId()) || !onlyHere();
    }

    /**
     * Whether the money switch is shown to {@code player} now: money play is on, the server has its bank, they have
     * the permission, and they stand in the Parlor.
     */
    public boolean offered(Player player) {
        return allowed(player) == null;
    }

    /** Why {@code player} can't play for money now, or {@code null} when they can (before any amount). */
    public Refusal allowed(Player player) {
        if (!rules.money() || !games.host().bank().on()) {
            return MONEY_CLOSED;
        }
        if (!player.hasPermission(PERMISSION_MONEY)) {
            return MONEY_NOT_YOURS;
        }
        return games.inParlor(player.getUniqueId()) ? null : MONEY_NOT_HERE;
    }

    /** The day's limit for this player: their own if an admin set one, else everyone's; {@link #NO_LIMIT} for none. */
    public int limit(UUID player) {
        try {
            String own = games.dao().pref(player, PREF_LIMIT);
            if (own != null) {
                return whole(own, rules.limit(), NO_LIMIT, Integer.MAX_VALUE);
            }
        } catch (SQLException e) {
            games.host().logger().log(Level.WARNING, "Could not read a player's Game Parlor limit", e);
        }
        return rules.limit();
    }

    /** Whether an admin set this player's own day limit. */
    public boolean ownLimit(UUID player) {
        try {
            return games.dao().pref(player, PREF_LIMIT) != null;
        } catch (SQLException e) {
            return false;
        }
    }

    /** Dollars the player put in today, or -1 when it can't be read (money play then fails closed). */
    public int inToday(UUID player) {
        try {
            return games.dao().moneyInToday(player, games.host().clock().dayKey());
        } catch (SQLException e) {
            games.host().logger().log(Level.SEVERE, "Could not read a player's Game Parlor money today", e);
            return -1;
        }
    }

    /** Dollars the player may still put in today ({@link Integer#MAX_VALUE} with no limit; 0 if it can't be read). */
    public int leftToday(UUID player) {
        int in = inToday(player);
        if (in < 0) {
            return 0;
        }
        return left(limit(player), in);
    }

    /**
     * The money steps of the play gate for {@code stake} dollars: may play for money at all, the stake is one the
     * chips can make and no bigger than the biggest, the day's limit, and the balance. {@code null} = go ahead.
     *
     * @param fresh a new play (the stake's shape is checked); false for more put in mid-round (a Double)
     */
    public Refusal check(Player player, int stake, boolean fresh) {
        Refusal r = allowed(player);
        if (r != null) {
            return r;
        }
        Rules now = rules;
        if (stake <= 0 || (fresh && !stakeFits(stake, now))) {
            return MONEY_CLOSED;
        }
        UUID id = player.getUniqueId();
        int in = inToday(id);
        if (in < 0) {
            return MONEY_CLOSED;
        }
        int limit = limit(id);
        if (over(limit, in, stake)) {
            return limitReached(limit);
        }
        double have = games.host().bank().balance(id);
        if (have < stake) {
            return needMore((int) Math.ceil(stake - have));
        }
        return null;
    }

    // ---- pure rules (tested) ----------------------------------------------------------------------------

    /** Whether putting {@code stake} more in would pass {@code limit} with {@code in} already in today. */
    static boolean over(int limit, int in, int stake) {
        return limit >= 0 && (long) in + Math.max(0, stake) > limit;
    }

    /** Dollars left under {@code limit} with {@code in} already in. */
    static int left(int limit, int in) {
        return limit < 0 ? Integer.MAX_VALUE : Math.max(0, limit - in);
    }

    /** Whether the chips can make {@code stake} and it is no bigger than the biggest stake. */
    static boolean stakeFits(int stake, Rules rules) {
        return stake >= rules.minStake() && stake <= rules.maxStake() && makeable(stake, rules.chips());
    }

    /** Whether some pile of {@code chips} adds up to exactly {@code amount}. */
    static boolean makeable(int amount, List<Integer> chips) {
        if (amount <= 0) {
            return false;
        }
        boolean[] can = new boolean[amount + 1];
        can[0] = true;
        for (int a = 1; a <= amount; a++) {
            for (int chip : chips) {
                if (chip <= a && can[a - chip]) {
                    can[a] = true;
                    break;
                }
            }
        }
        return can[amount];
    }

    /**
     * The stake after one more chip: {@code stake + chip}, unless that is over the biggest stake, more than is left
     * of the day's limit, or more than the balance; then the stake stays as it was.
     */
    public static int add(int stake, int chip, Rules rules, int leftToday, double balance) {
        long next = (long) stake + chip;
        return next > rules.maxStake() || next > leftToday || next > balance ? stake : (int) next;
    }

    /** Why {@link #add} would not take one more {@code chip}, for the chip's tile; {@code null} when it would. */
    public static String chipRefusal(int stake, int chip, Rules rules, int leftToday, double balance) {
        long next = (long) stake + chip;
        if (next > rules.maxStake()) {
            return "the most is $" + rules.maxStake();
        }
        if (next > leftToday) {
            return "over your limit today";
        }
        return next > balance ? "not enough money" : null;
    }

    /** "$35": whole dollars, as every Parlor screen writes them. */
    public static String dollars(int amount) {
        return "$" + amount;
    }

    static Refusal limitReached(int limit) {
        return new Refusal(Refusal.Reason.PERSONAL_LIMIT,
                "That's your money limit for today (" + dollars(limit) + "). It resets at midnight.");
    }

    static Refusal needMore(int missing) {
        return new Refusal(Refusal.Reason.BALANCE, "You need " + dollars(Math.max(1, missing)) + " more.");
    }

    static boolean flag(String text, boolean fallback) {
        if (text == null) {
            return fallback;
        }
        return switch (text.trim()) {
            case "1", "true", "on" -> true;
            case "0", "false", "off" -> false;
            default -> fallback;
        };
    }

    static int whole(String text, int fallback, int min, int max) {
        if (text == null) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(text.trim());
            return v < min || v > max ? fallback : v;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** "5,10,25" as chips: whole dollars from 1 to {@link #STAKE_CEILING}, distinct, smallest first, at most three. */
    static List<Integer> chips(String text, List<Integer> fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        TreeSet<Integer> out = new TreeSet<>();
        for (String part : text.split("[,\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                int v = Integer.parseInt(part);
                if (v < 1 || v > STAKE_CEILING) {
                    return fallback;
                }
                out.add(v);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        if (out.isEmpty() || out.size() > MAX_CHIPS) {
            return fallback;
        }
        return List.copyOf(out);
    }

    static String text(List<Integer> chips) {
        List<String> parts = new ArrayList<>();
        for (int c : chips) {
            parts.add(Integer.toString(c));
        }
        return String.join(",", parts);
    }
}
