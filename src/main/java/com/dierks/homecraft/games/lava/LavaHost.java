package com.dierks.homecraft.games.lava;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.gen.engine.Person;
import com.dierks.homecraft.games.gen.engine.WorldPort;
import com.dierks.homecraft.games.lava.rules.LavaResult;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Everything {@link LavaService} needs from the running server, in one place (Falling Floors' {@code ArenaHost},
 * for the playroom), so the room's rules (the claim, the build and its check, the round, the lava put away on every
 * path) run for real in tests with a fake clock, a fake world and fake players. The live host is
 * {@link LiveLavaHost}; nothing here is asked off the main thread, and nothing throws on purpose but the claim's
 * reads and writes.
 */
public interface LavaHost {

    /** What a player holds, by where they are in a round. */
    enum Kit {
        /** Waiting for a Group or a Challenge to start: Start now, and Leave game. */
        GATHER,
        /** Playing: Leave game only. */
        PLAY,
        /** Out of a Challenge, watching from the gallery: Leave game only. */
        WATCH,
        /** The round is over: Play again, and Leave game. */
        OVER
    }

    /** A sound. Every one is soft: nothing loud, sudden or frightening. */
    enum Cue {
        /** A star collected: a chime, higher with every star. */
        STAR,
        /** Touched the lava: a sizzle. */
        SIZZLE,
        /** The warning begins: a low rumble. */
        RUMBLE,
        /** The warning's rising notes: climb! */
        RISE,
        /** The lava is here: a glug. */
        LAVA,
        /** The lava is gone: all clear. */
        CLEAR,
        /** Out of a Challenge. */
        OUT,
        /** The round is won: a fanfare. */
        WIN,
        /** A second of the gathering's countdown. */
        COUNT,
        /** The round starts. */
        START,
        /** 0.57.0: the furniture is about to move: a low, wobbly hum. */
        SHIFT,
        /** 0.57.0: the furniture moved: a soft whoosh. */
        MOVED,
        /** 0.57.0: a piece wobbles and creaks: get off it! */
        WOBBLE,
        /** 0.57.0: a piece sank into the lava: a soft splash. */
        SANK,
        /** 0.57.0: a cushion turned bouncy: boing. */
        BOING,
        /** 0.57.0: a shower of stars: twinkles. */
        SHOWER,
        /** 0.57.0, Group: the round's star goal reached: a happy chord. */
        GOAL,
        /** 0.57.0, Group: the round's star goal missed: a heart gone (a soft low note, never a buzzer). */
        MISSED
    }

    /** Something to see. */
    enum Fx {
        /** A puff of smoke where the lava was touched. */
        SMOKE,
        /** A sparkle where a star was collected. */
        SPARKLE,
        /** Embers rising from the floor: the lava is coming. */
        EMBERS,
        /** A burst of fireworks (sparks only: no bang). */
        FIREWORKS,
        /** 0.57.0: sparks over a piece that is about to move. */
        SHIFT,
        /** 0.57.0: a puff where a piece arrived. */
        LANDED,
        /** 0.57.0: crumbs falling from a piece that wobbles. */
        WOBBLE,
        /** 0.57.0: slime bubbles over a bouncy cushion. */
        SLIME,
        /** 0.57.0: a shower of sparkles from the ceiling. */
        SHOWER
    }

    /** Now (epoch ms): the plugin's clock. */
    long now();

    /** A monotonic clock for the build's time budget ({@code System.nanoTime()}). */
    long nanoTime();

    /**
     * 0.57.0: work out something pure (a room's next layout, tens of milliseconds) off the main thread; the tests do
     * it at once. The result is only ever read on the main thread.
     */
    default <T> java.util.concurrent.CompletableFuture<T> offThread(java.util.function.Supplier<T> work) {
        java.util.concurrent.CompletableFuture<T> f = new java.util.concurrent.CompletableFuture<>();
        try {
            f.complete(work.get());
        } catch (RuntimeException e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    Logger logger();

    /** The live {@code games.floor_is_lava} settings. */
    LavaSettings settings();

    /** The world the room is in ({@code games.fresh.world}, or the first Games world), or {@code ""}. */
    String worldName();

    /** The world by name, or {@code null} when it isn't loaded. */
    WorldPort world(String name);

    /** The room's claim ({@code gen.lava.claim}), or {@code null}. Throws when it can't be read. */
    String claim() throws Exception;

    /** Record the room's claim. Throws when it can't be written. */
    void claim(String value) throws Exception;

    /**
     * Why the box can't stand in {@code world}: too close to a Fresh Courses area, the kept courses, the Clubhouse,
     * the Falling Floors arena or a hand-built course, outside the world's height or border, or over the spawn or
     * safe spot. Empty when it can.
     */
    List<String> regionProblems(Box box, String world);

    /** Everyone online, with where their feet are and which world session they are in. */
    List<Person> people();

    boolean anyoneOnline();

    /** Paper's average tick time, in milliseconds. */
    double mspt();

    /** Where a player's feet are now ({x, y, z}), or {@code null} when they are offline. */
    double[] feet(UUID player);

    /** Whether the server says the player is in lava now (a second opinion beside the room's own geometry). */
    boolean inLava(UUID player);

    /**
     * The room session's own teleport (it becomes the session's safe point), with no fall and no fire carried over.
     *
     * @return whether it was made
     */
    boolean teleport(UUID player, String world, LavaSite.Spot spot);

    /**
     * 0.57.0: move a player with the furniture (they rode a piece, or one landed where they stood): as
     * {@link #teleport}, but they keep looking the way they were.
     */
    default boolean carry(UUID player, String world, LavaSite.Spot spot) {
        return teleport(player, world, spot);
    }

    /** Move someone who isn't in the room's session out of the box: the safe spot, or the spawn. */
    void toSafety(UUID player, String world);

    /** The player's time in the room is over: back to the Clubhouse if they came from it, else home with their things. */
    void endSession(UUID player);

    /**
     * Keep a player unharmed, every tick they are in the room: no fire on them, no fall building up, a full breath.
     * (Damage and hunger are the world session's to stop; this is what lava would otherwise leave on them.)
     */
    void calm(UUID player);

    void kit(UUID player, Kit kit);

    void tell(UUID player, String line);

    void actionBar(UUID player, String line);

    void title(UUID player, String big, String small, int stayTicks);

    /** Play a sound to a player. {@code step} tunes it: a star's number, a rising note's place in the scale. */
    void sound(UUID player, Cue cue, int step);

    /** Show something at a place in the room, to everyone there. */
    void fx(String world, Fx fx, double x, double y, double z);

    /** Show (or update) a player's bar. {@code red}: Challenge's. */
    void bar(UUID player, String title, float progress, boolean red);

    void hideBar(UUID player);

    /** Put star {@code id} out at a place: floating, glowing. */
    void showStar(String world, int id, double x, double y, double z);

    /** Take star {@code id} away. */
    void hideStar(int id);

    /** Take every star away. */
    void hideStars();

    /** A player's name for the result lines. */
    String name(UUID player);

    /** A player's best on a mode's board so far, or {@code null} for none (or when it can't be read). */
    Long best(UUID player, com.dierks.homecraft.games.lava.rules.LavaMode mode);

    /** A round was played out: its bests go on the boards ({@link LavaScores}). No tokens are ever paid. */
    void scored(LavaResult result);

    /** Open the end-of-round screen for a player: Play again, and All done. */
    void endScreen(UUID player, LavaResult result);
}
