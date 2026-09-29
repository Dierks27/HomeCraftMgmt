package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceLink;

import java.util.Collection;
import java.util.UUID;

/**
 * Everything a {@link NightRunner} needs from the server, by player id: the clock, who is online
 * and free, Time Trials' race mode ({@code TimeTrials.race}, {@code regrid}, {@code park},
 * {@code endRace}, {@code reserve}/{@code release}), and the ways to tell players things. The live
 * implementation is {@code RaceNight}'s; the tests pass a fake, so a whole night runs start to
 * finish without a server. Nothing here may throw into the runner: the live side is inside the
 * games' guard.
 */
public interface NightPorts {

    /** Now, epoch ms (the plugin's clock). */
    long now();

    /** The server tick now ({@code Bukkit.getCurrentTick()}). */
    long tick();

    /** Whether the player is online. */
    boolean online(UUID player);

    /** Whether the player is online and not in any world game (so they can be taken to the track). */
    boolean free(UUID player);

    /**
     * Whether a scheduled restart is minutes away (the restart hold): the shared warm-up then ends at
     * once, so the racing isn't eaten by free laps.
     */
    default boolean restartHeld() {
        return false;
    }

    /** The player's name (for chat, results and the hub), or {@code null}. */
    String name(UUID player);

    // ---- race mode (Time Trials) ------------------------------------------------------------------

    /**
     * Seat a racer: {@code TimeTrials.race(...)}. {@code stand} is where finishers wait, or
     * {@code null} (finishers go home).
     *
     * @return why they couldn't be seated (a plain line), or {@code null} when they are at the track
     */
    String seat(UUID racer, Course base, Course raced, Course.Spot grid, Point stand, RaceLink link);

    /** The next race: a new boat on their spot, held to the link's next Go ({@code TimeTrials.regrid}). */
    void regrid(UUID racer, Course raced, Course.Spot grid);

    /** Done for this race: onto the stand, the run idle ({@code TimeTrials.park}). */
    void park(UUID racer);

    /** Home with their things, reading {@code line} ({@code TimeTrials.endRace}). */
    void home(UUID racer, EndReason why, String line);

    /** Hold the track: new solo runs on it are refused with {@code line}. @return false when someone else holds it */
    boolean reserve(String courseId, Object holder, String line);

    /** Let the track go. */
    void release(String courseId, Object holder);

    /** End every solo run still on the track (not the racers'), telling each rider {@code line}. */
    void endSoloRuns(String courseId, Collection<UUID> racers, String line);

    /** Warn solo riders on the track, without ending anything. */
    void warnSoloRuns(String courseId, Collection<UUID> racers, String line);

    // ---- telling --------------------------------------------------------------------------------

    /** A chat line ('&amp;' colours); queued for their next join when {@code queueIfOffline} and they are offline. */
    void tell(UUID player, String line, boolean queueIfOffline);

    /** A title. */
    void title(UUID player, String big, String small);

    /** The racer's bossbar ({@code progress} 0-1; {@code lastLap} turns it yellow); {@code null} line hides it. */
    void bar(UUID player, String line, float progress, boolean lastLap);

    /** A chat line to everyone watching from anywhere (the Watch button) and at the track. */
    void watchers(String line);

    /** A broadcast line to players who aren't racing (the {@link Announcer}'s rules apply). */
    void announce(Announcer.Line line, String text, Collection<UUID> racers);

    /** Something the hub, the feed or the screens show changed (at most one redraw a second follows). */
    void changed();

    /** The player's points on a board of {@code race_night} (the season), 0 for none. */
    long points(UUID player, String board);

    /** The night ended: tell the quests and achievements (raced; won). */
    void progress(UUID player, boolean won);

    /** A line for the server log (INFO, or WARNING when {@code warn}). */
    void log(String line, boolean warn);
}
