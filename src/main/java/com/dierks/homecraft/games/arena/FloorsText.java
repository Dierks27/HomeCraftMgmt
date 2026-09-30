package com.dierks.homecraft.games.arena;

import com.dierks.homecraft.games.arena.rules.ArenaText;
import com.dierks.homecraft.games.arena.rules.RoundEvent;

import java.util.List;

/**
 * The words Falling Floors says around a round (EVENTS-DROPPER-SPEC §B.3.3, §B.4): arriving, the
 * lobby, the kit, the bars, the tile. The round's own lines (out, results, countdown, lobby) are
 * {@link ArenaText}'s; these are the wiring's.
 *
 * <p>Kid copy: short and plain, '&amp;'-coloured, no emoji and nothing above U+FFFF, no pressure
 * words. The key facts are in the kit items' and the tile's NAMES, because Bedrock shows lore only
 * on tap-and-hold. Pure, so every line is tested without a server.
 */
public final class FloorsText {

    /** The first line on arriving. */
    public static final String WELCOME = "&eFalling Floors: &7every block you step on falls away. Keep moving!";
    /** Arriving (or pressing Ready) while the floors are being put back. */
    public static final String FIXING = "&7The floors are being fixed - one moment!";
    /** Arriving while a round is going. */
    public static final String WATCH = "&7A round is going - watch from here and play the next one!";
    /** The arena is moving or its rounds changing (an admin's reload): everyone goes home first. */
    public static final String MOVING = "&7Falling Floors is being changed - your things are back. Come back in a"
            + " minute!";
    /** Ready pressed on. */
    public static final String READY_ON = "&aReady! &7Waiting for the others.";
    /** Ready pressed off. */
    public static final String READY_OFF = "&7Not ready - press it again when you are.";
    /** Ready pressed during a round. */
    public static final String READY_LATER = "&7Wait for the next round, then press Ready.";

    /** The kit, key facts in the names. */
    public static final String KIT_READY = "&aReady &7- tap when you're set";
    public static final String KIT_READY_ON = "&aReady &7- you're ready!";
    public static final String KIT_SOLO = "&ePlay solo &7- how long can you last?";
    public static final String KIT_LEAVE = "&cLeave game";

    private FloorsText() {
    }

    /** "This week's floors: ring with an island, disc and plus." */
    public static String thisWeek(String shapes) {
        return shapes == null || shapes.isBlank() ? null : "&7This week's floors: &f" + shapes + "&7.";
    }

    /** What to do in the lobby: Ready, or Play solo when that is offered. */
    public static String lobbyHint(boolean soloOffered) {
        return soloOffered ? "&7Press &aReady &7when a friend is here, or &ePlay solo&7."
                : "&7Press &aReady &7when you're set.";
    }

    /**
     * Why the countdown stopped, for the gallery; {@code null} when the next line says it anyway
     * (the game closing).
     *
     * @param when the restart's time, for {@link RoundEvent.Why#HOLD}
     */
    public static String countdownStopped(RoundEvent.Why why, String when) {
        if (why == null) {
            return null;
        }
        return switch (why) {
            case TOO_FEW -> "&7The countdown stopped - waiting for more players.";
            case HOLD -> ArenaText.hold(when == null ? "soon" : when);
            case RESET -> "&7The floors are changing - one moment!";
            case CLOSED -> null;
        };
    }

    /** To the round's players as it starts. */
    public static String roundStarting(int round, int players, boolean solo) {
        return solo ? "&eSolo round: how long can you last? Keep moving!"
                : "&eRound " + round + ": " + players + " players - last one standing wins!";
    }

    /** To the gallery when a round starts without them. */
    public static final String ROUND_WITHOUT_YOU = "&7A round started - you play the next one!";

    /** To a round player the game couldn't move to their spawn: they watch this round instead. */
    public static final String NO_SPAWN = "&7We couldn't get you to the floors - watch this round and play the next"
            + " one!";

    /**
     * A solo milestone today's token limit can't pay whole: nothing is paid, and it waits for another
     * day (the milestones are once ever, so they are never short-paid).
     */
    public static final String MILESTONE_LIMIT = "&7You've reached today's token limit - last that long again"
            + " another day for its tokens.";

    /** Anyone changing a block of the arena's box, admins included: it puts itself back. */
    public static final String GUARDED = "&cThis is the Falling Floors arena - it puts itself back. Use &e/hcm games"
            + " floors&c.";

    /**
     * The Ready item's lore: how many ready players start a round ({@code min_players}), or the
     * 20 s after a second player arrives.
     */
    public static List<String> readyLore(int minPlayers) {
        return List.of("&7A round starts when " + minPlayers + " are ready,", "&7or 20 seconds after a friend comes.");
    }

    /** A round player's action bar: how long so far, and how many are still in. */
    public static String playing(long ticks, int stillIn, boolean solo) {
        return solo ? "&e" + ArenaText.clock(ticks) + " &7- keep moving!"
                : "&e" + ArenaText.clock(ticks) + " &7- " + stillIn + " still in";
    }

    /** A watcher's action bar during a round. */
    public static String watching(int stillIn) {
        return "&7" + stillIn + " still in - you play the next round!";
    }

    /** The 3-2-1 before Go: the big number. */
    public static String holdNumber(int seconds) {
        return "&e" + seconds;
    }

    /** Under the 3-2-1. */
    public static final String HOLD_SMALL = "&7Get ready!";

    /** The tile's NAME: the key fact is how many are playing, and that you can join. */
    public static String tile(int playing, boolean open) {
        if (!open) {
            return "&7" + ArenaText.NAME + " &7- opening soon";
        }
        return playing > 0 ? "&e" + ArenaText.NAME + " &7- " + playing + " playing · join!"
                : "&e" + ArenaText.NAME + " &7- play solo or together";
    }
}
