package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.RaceGrid;
import com.dierks.homecraft.games.trial.RaceStand;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TrackChunks;
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
 *
 * <p>Round 2, G2 #4: the live blocks are read through a {@link WorldSurface}, which never loads a chunk
 * on the main thread. The schedule, which makes a night when its heads-up is due, asks with
 * {@code mayLoad} false: a track whose chunks aren't loaded comes back {@link Found#loading} (the
 * chunks are asked for off the main thread) and the schedule asks again a second later, when they
 * are in. An admin's command and the boot's recovery of a night read as before.
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
    record Found(NightRunner.Track track, int races, String problem, boolean loading) {

        Found(NightRunner.Track track, int races, String problem) {
            this(track, races, problem, false);
        }

        static Found no(String why) {
            return new Found(null, 0, why);
        }

        /**
         * Round 2, G2 #4: the track's chunks aren't loaded; they are on their way, off the main thread.
         * Not a problem with the track: ask again in a moment.
         */
        static Found loading(String name) {
            return new Found(null, 0, name + "'s blocks are still loading - try again in a moment", true);
        }
    }

    private final RaceNight game;

    Tracks(RaceNight game) {
        this.game = game;
    }

    /**
     * The track {@code courseId} for a night of {@code races} races and up to {@code maxRacers}, read as
     * an admin's command or the boot reads it (chunks it needs are loaded, on the main thread).
     */
    Found find(String courseId, int races, int minRacers, int maxRacers) {
        return find(courseId, races, minRacers, maxRacers, true);
    }

    /**
     * The track {@code courseId}; with {@code mayLoad} false (the schedule), never a chunk loaded on
     * the main thread: {@link Found#loading} while its chunks come in (round 2, G2 #4).
     */
    Found find(String courseId, int races, int minRacers, int maxRacers, boolean mayLoad) {
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
        Live blocks = new Live(c.world(), mayLoad);
        List<Course.Spot> grid = grid(c, maxRacers, null, blocks);
        Point stand = stand(c, blocks);
        if (blocks.loading()) {
            // round 2, G2 #4: what was read isn't the track yet; its chunks come in off the main thread
            TrackChunks.whenLoaded(TrackChunks.server(blocks.world, game.games(), game), blocks.surface.missing(),
                    () -> {
                    });
            return Found.loading(c.name());
        }
        String problem = RaceTrack.raceProblem(c, grid.size(), minRacers);
        if (problem != null) {
            return Found.no(problem);
        }
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
        return pick(eventId, races, minRacers, maxRacers, true);
    }

    /**
     * {@link #pick(String, int, int, int)}; with {@code mayLoad} false (the schedule), a candidate whose
     * chunks are still loading is {@link Found#loading}, never skipped for the next (round 2, G2 #4).
     */
    Found pick(String eventId, int races, int minRacers, int maxRacers, boolean mayLoad) {
        List<String> ids = candidates();
        String first = RaceTrack.pick(ids, eventId);
        if (first == null) {
            return Found.no(RaceNight.NO_TRACK);
        }
        int at = ids.indexOf(first);
        Found last = null;
        for (int i = 0; i < ids.size(); i++) {
            Found f = find(ids.get((at + i) % ids.size()), races, minRacers, maxRacers, mayLoad);
            if (f.problem() == null || f.loading()) {
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
        return grid(c, n, why, new Live(c.world(), true)); // an admin's command
    }

    /**
     * The live blocks of a track's world, looked up the first time they are needed (a stored grid and
     * an admin's stand need none): loaded chunks only unless {@code mayLoad} (round 2, G2 #4).
     */
    private static final class Live {
        private final String name;
        private final boolean mayLoad;
        private boolean looked;
        World world;
        WorldSurface surface;

        Live(String name, boolean mayLoad) {
            this.name = name;
            this.mayLoad = mayLoad;
        }

        /** The blocks, or {@code null} when the world isn't loaded. */
        WorldSurface get() {
            if (!looked) {
                looked = true;
                world = Bukkit.getWorld(name);
                surface = world == null ? null : mayLoad ? WorldSurface.mayLoad(world) : new WorldSurface(world);
            }
            return surface;
        }

        /** Whether a read needed chunks that weren't loaded, so it isn't the track yet. */
        boolean loading() {
            return surface != null && !surface.complete();
        }
    }

    /** {@link #grid(Course, int, List)} read from {@code live}. */
    private List<Course.Spot> grid(Course c, int n, List<String> why, Live live) {
        if (!c.generated()) {
            List<Course.Spot> stored = storedGrid(c);
            if (stored != null && !stored.isEmpty()) {
                return stored.size() > n ? stored.subList(0, n) : stored;
            }
        }
        WorldSurface blocks = live.get();
        if (blocks == null) {
            if (why != null) {
                why.add("the world " + c.world() + " isn't loaded");
            }
            return List.of();
        }
        RaceGrid.Grid auto = RaceGrid.forCourse(c, blocks, n); // Time Trials' grid, as party races'
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
     * layout changes); {@code null} for none (finishers go home at the line). The built one is checked
     * on {@code live}.
     */
    private Point stand(Course c, Live live) {
        if (c.generated()) {
            Box half = game.games().generated().half(c.gen());
            Point built = RaceTrack.freshStand(c, half);
            return built != null && live.get() != null && RaceStand.standable(live.get(), built) ? built : null;
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
