package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceGrid;
import com.dierks.homecraft.games.trial.RaceStand;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.games.trial.WorldSurface;
import com.dierks.homecraft.storage.EventDao;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * The tracks Race Night can race on, on the live server (EVENTS-DROPPER-SPEC §A.4.1-§A.4.3): an
 * open boat course of Time Trials in a Games world, with enough grid spots for {@code min_racers},
 * and a viewing stand for more than one race. The rules are {@link RaceTrack}'s (pure); this class
 * reads the courses, the admin's stored grids and stands ({@code hcm_meta race.grid.<course>},
 * {@code race.stand.<course>}, each kept with the layout it was set for) and the live blocks. The
 * automatic grid and a Fresh Boat's built-in stand are Time Trials' own ({@link RaceGrid},
 * {@link RaceStand}), the same ones party races use, so there is one grid and one stand.
 */
final class Tracks {

    /** Where an admin's grid of a course is kept. */
    static final String GRID = EventDao.META + "grid.";
    /** Where an admin's stand of a course is kept. */
    static final String STAND = EventDao.META + "stand.";

    /**
     * A track as a night would race it, or why it can't.
     *
     * @param track   the track (its grid, pole first, and its stand or {@code null}), or {@code null}
     * @param races   races it can hold (1 without a stand)
     * @param problem why it can't be raced, or {@code null}
     */
    record Found(NightRunner.Track track, int races, String problem) {

        static Found no(String why) {
            return new Found(null, 0, why);
        }
    }

    private final RaceNight game;

    Tracks(RaceNight game) {
        this.game = game;
    }

    /** The track {@code courseId} for a night of {@code races} races and up to {@code maxRacers}. */
    Found find(String courseId, int races, int minRacers, int maxRacers) {
        TimeTrials t = game.trials();
        if (t == null) {
            return Found.no("Time Trials is closed");
        }
        Course c = t.openCourse(courseId);
        if (c == null) {
            Course any = t.course(courseId);
            return Found.no(any == null ? "there's no course called " + courseId : any.name() + " isn't open right now");
        }
        if (c.kind() != TrialKind.BOAT) {
            return Found.no(c.name() + " isn't a boat course");
        }
        List<Course.Spot> grid = grid(c, maxRacers, null);
        String problem = RaceTrack.raceProblem(c, grid.size(), minRacers);
        if (problem != null) {
            return Found.no(problem);
        }
        Point stand = stand(c);
        return new Found(new NightRunner.Track(c, c.name(), grid, stand), RaceTrack.races(races, stand), null);
    }

    /**
     * The open boat courses with a start, sorted by id: what {@code course: auto} takes turns
     * among. Cheap (no blocks are read), so the schedule, the screens and the feed can ask often;
     * the grid is checked when a night is made ({@link #pick}).
     */
    List<String> candidates() {
        TimeTrials t = game.trials();
        List<String> out = new ArrayList<>();
        if (t == null) {
            return out;
        }
        for (Course c : t.openCourses()) {
            if (c.kind() == TrialKind.BOAT && c.start() != null) {
                out.add(c.id());
            }
        }
        out.sort(null);
        return out;
    }

    /**
     * The track for night {@code eventId} under {@code course: auto}: its turn among the candidates
     * ({@link RaceTrack#pick}, stable, never random), or the next one after it that can be raced when
     * that one can't (a grid too small, no start). {@link Found#no} when none can.
     */
    Found pick(String eventId, int races, int minRacers, int maxRacers) {
        List<String> ids = candidates();
        String first = RaceTrack.pick(ids, eventId);
        if (first == null) {
            return Found.no(RaceNight.NO_TRACK);
        }
        int at = ids.indexOf(first);
        Found last = null;
        for (int i = 0; i < ids.size(); i++) {
            Found f = find(ids.get((at + i) % ids.size()), races, minRacers, maxRacers);
            if (f.problem() == null) {
                return f;
            }
            last = last == null ? f : last;
        }
        return last;
    }

    /**
     * A course's grid, pole first, for up to {@code n}: an admin's (while the layout is the one it was
     * set for; a Fresh course always uses the automatic one), else the automatic grid on the live
     * blocks. {@code why}, when given, gets a line for each thing that was dropped or failed.
     */
    List<Course.Spot> grid(Course c, int n, List<String> why) {
        if (!c.generated()) {
            List<Course.Spot> stored = storedGrid(c);
            if (stored != null && !stored.isEmpty()) {
                return stored.size() > n ? stored.subList(0, n) : stored;
            }
        }
        World w = Bukkit.getWorld(c.world());
        if (w == null) {
            if (why != null) {
                why.add("the world " + c.world() + " isn't loaded");
            }
            return List.of();
        }
        RaceGrid.Grid auto = RaceGrid.forCourse(c, new WorldSurface(w), n); // Time Trials' grid, as party races'
        if (why != null && auto.size() < n) {
            why.add("the automatic grid found " + auto.size() + " of " + n + " spots behind the start (walls, "
                    + "blocks or no room)");
            why.addAll(auto.notes());
        }
        return auto.spots();
    }

    /** An admin's stored grid while the layout still matches; a stale one is dropped with a WARN. */
    List<Course.Spot> storedGrid(Course c) {
        String key = GRID + c.id();
        String stored = meta(key);
        if (stored == null) {
            return null;
        }
        if (RaceTrack.stale(stored, c.layoutHash())) {
            game.log(Level.WARNING, "Race Night: the grid of " + c.id() + " was set for another layout - dropped", null);
            setMeta(key, null);
            return null;
        }
        return RaceTrack.decodeGrid(stored, c.layoutHash());
    }

    /**
     * A course's viewing stand: built into a Fresh Boat layout of algo 2 or later ({@link RaceStand},
     * used only while it really stands in the world), else an admin's (dropped with a WARN once the
     * layout changes); {@code null} for none (finishers go home at the line).
     */
    Point stand(Course c) {
        if (c.generated()) {
            Box half = game.games().generated().half(c.gen());
            Point built = RaceTrack.freshStand(c, half);
            World w = built == null ? null : Bukkit.getWorld(c.world());
            return w != null && RaceStand.standable(new WorldSurface(w), built) ? built : null;
        }
        String key = STAND + c.id();
        String stored = meta(key);
        if (stored == null) {
            return null;
        }
        if (RaceTrack.stale(stored, c.layoutHash())) {
            game.log(Level.WARNING, "Race Night: the stand of " + c.id() + " was set for another layout - dropped", null);
            setMeta(key, null);
            return null;
        }
        return RaceTrack.decodeStand(stored, c.layoutHash());
    }

    /** Why a stand can't go at {@code p} on course {@code c} (live blocks and the racing line), or {@code null}. */
    String standProblem(Course c, String world, Point p) {
        if (world == null || !world.equalsIgnoreCase(c.world())) {
            return "stand in the course's own world (" + c.world() + ")";
        }
        World w = Bukkit.getWorld(c.world());
        if (w == null) {
            return "the world " + c.world() + " isn't loaded";
        }
        int x = (int) Math.floor(p.x());
        int y = (int) Math.floor(p.y());
        int z = (int) Math.floor(p.z());
        if (w.getBlockAt(x, y - 1, z).isPassable() || !w.getBlockAt(x, y, z).isPassable()
                || !w.getBlockAt(x, y + 1, z).isPassable()) {
            return "a stand needs a solid block under it and 2 blocks of air above";
        }
        return RaceTrack.standProblem(c, grid(c, RaceTrack.MAX_GRID, null), p);
    }

    private String meta(String key) {
        try {
            return game.dao().meta(key);
        } catch (SQLException e) {
            game.log(Level.WARNING, "Race Night: could not read " + key, e);
            return null;
        }
    }

    private void setMeta(String key, String value) {
        try {
            game.dao().setMeta(key, value);
        } catch (SQLException e) {
            game.log(Level.WARNING, "Race Night: could not write " + key, e);
        }
    }
}
