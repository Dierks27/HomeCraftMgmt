package com.dierks.homecraft.games;

/**
 * Why a player can't play (or put this many tokens in) right now, and what to tell them (spec
 * §4.1, R1.10, R3.9). {@code null} means "go ahead" wherever a {@code Refusal} is returned.
 *
 * <p>The {@link Reason} lets a screen show a refusal in place instead of in chat (an unlit Double
 * button reads "- over your limit today"), and keeps one refusal silent: the click cooldown,
 * where spam-clicking should simply do nothing — no chat line, no sound ({@link #SILENT}).
 *
 * <p>Messages are plain words, no colour codes; {@link GamesService#tell} colours them. They
 * never say why beyond what is written here: a parent may have switched something off, and that
 * is between them.
 *
 * @param reason  what kind of refusal it is
 * @param message the line the player reads (empty for {@link #SILENT})
 */
public record Refusal(Reason reason, String message) {

    /** The kinds of refusal, in the order the play gate checks them. */
    public enum Reason {
        /** In a world session and trying to use another game (gate step 0). */
        IN_SESSION,
        /** The games, or this game, are off or failed (step 1). */
        CLOSED,
        /** Missing {@code hcm.games.play}, or {@code hcm.games.chance} for a game of chance (step 2). */
        NO_PERMISSION,
        /** Not a world games may be played in (step 3). */
        WORLD,
        /** Taking a break from games of chance (step 4). */
        PAUSED,
        /** The click cooldown: silent (step 5). */
        COOLDOWN,
        /** The game's own daily limit (step 6). */
        DAILY_LIMIT,
        /** The personal (or server, or parent) daily token limit (step 7). */
        PERSONAL_LIMIT,
        /** Not enough tokens (step 8). */
        BALANCE,
        /** Anything else a game refuses for (its own message). */
        OTHER
    }

    public static final Refusal IN_SESSION = new Refusal(Reason.IN_SESSION, "Finish your game first (/hcm leave).");
    public static final Refusal CLOSED = new Refusal(Reason.CLOSED, "That game is closed right now.");
    /** What a player sees when a game failed and switched itself off. */
    public static final Refusal BROKEN = new Refusal(Reason.CLOSED, "That game is taking a break. Try another one!");
    /** Take a break could not be read: games of chance fail closed (R1.15). */
    public static final Refusal CHANCE_CLOSED = new Refusal(Reason.CLOSED, "Games of chance are closed right now.");
    public static final Refusal NO_GAMES = new Refusal(Reason.NO_PERMISSION, "Games aren't open to you.");
    public static final Refusal NO_CHANCE = new Refusal(Reason.NO_PERMISSION, "Games of chance aren't open to you.");
    public static final Refusal WORLD = new Refusal(Reason.WORLD, "Games can't be played in this world.");
    /** The click cooldown: nothing is said and nothing is played. */
    public static final Refusal SILENT = new Refusal(Reason.COOLDOWN, "");

    public Refusal {
        reason = reason == null ? Reason.OTHER : reason;
        message = message == null ? "" : message;
    }

    /** Taking a break until {@code until} (already formatted: "Thu 12 AM"). */
    public static Refusal paused(String until) {
        return new Refusal(Reason.PAUSED, "You're taking a break from games of chance until " + until + ".");
    }

    /** The game's own daily limit is used up. */
    public static Refusal dailyLimit(String gameName) {
        return new Refusal(Reason.DAILY_LIMIT,
                "That's all your plays of " + gameName + " for today. It opens again at midnight.");
    }

    /** Putting this many in would pass the day's token limit of {@code limit}. */
    public static Refusal personalLimit(int limit) {
        return new Refusal(Reason.PERSONAL_LIMIT,
                "That's your limit for today (" + limit + " tokens). It resets at midnight.");
    }

    /** {@code missing} more tokens are needed. */
    public static Refusal needMore(int missing) {
        return new Refusal(Reason.BALANCE, "You need " + missing + " more token" + (missing == 1 ? "" : "s") + ".");
    }

    /** A game's own refusal. */
    public static Refusal of(String message) {
        return new Refusal(Reason.OTHER, message);
    }

    /** Whether nothing should be said or played (the click cooldown). */
    public boolean silent() {
        return reason == Reason.COOLDOWN;
    }
}
