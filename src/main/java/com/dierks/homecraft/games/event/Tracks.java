package com.dierks.homecraft.games.event;

import com.dierks.homecraft.games.gen.api.Box;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.Point;
import com.dierks.homecraft.games.trial.TimeTrials;
import com.dierks.homecraft.games.trial.TrialKind;
import com.dierks.homecraft.storage.EventDao;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * The tracks Race Night can race on, on the live server (EVENTS-DROPPER-SPEC §A.4.1-§A.4.3): an
 * open boat course of Time Trials in a Games world, with enough grid spots for {@code min_racers},
 * and a viewing stand for more than one race. The rules are {@link RaceTrack}'s (pure); this class
 * reads the courses, the admin's stored grids and stands ({@code hcm_meta race.grid.<course>},
 * {@code race.stand.<course>}, each kept with the layout it was set for) and the live blocks the
 * automatic grid needs (about 60 reads, main thread).
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

    /** Every open boat course that could hold a night now, sorted by id. */
    List<String> raceable(int minRacers, int maxRacers) {
        TimeTrials t = game.trials();
        List<String> out = new ArrayList<>();
        if (t == null) {
            return out;
        }
        for (Course c : t.openCourses()) {
            if (c.kind() == TrialKind.BOAT && find(c.id(), 1, minRacers, maxRacers).problem() == null) {
                out.add(c.id());
            }
        }
        out.sort(null);
        return out;
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
        List<Course.Spot> auto = RaceTrack.autoGrid(c, n, probe(w));
        if (why != null && auto.size() < n) {
            why.add("the automatic grid found " + auto.size() + " of " + n + " spots behind the start (walls, "
                    + "blocks or no room)");
        }
        return auto;
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
     * A course's viewing stand: built into a Fresh Boat layout of algo 2 or later, else an admin's
     * (dropped with a WARN once the layout changes); {@code null} for none.
     */
    Point stand(Course c) {
        if (c.generated()) {
            Box half = game.games().generated().half(c.gen());
            return RaceTrack.freshStand(c, half);
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

    /** The live blocks as the automatic grid reads them. */
    static RaceTrack.Probe probe(World w) {
        return new RaceTrack.Probe() {
            @Override
            public boolean seat(double x, double y, double z) {
                int bx = (int) Math.floor(x);
                int by = (int) Math.floor(y);
                int bz = (int) Math.floor(z);
                Block feet = w.getBlockAt(bx, by, bz);
                Block head = w.getBlockAt(bx, by + 1, bz);
                boolean floating = feet.getType() == Material.WATER;
                Block below = w.getBlockAt(bx, by - 1, bz);
                boolean floor = floating || below.getType() == Material.WATER || !below.isPassable();
                if (!floor || !head.isPassable() || (!floating && !feet.isPassable())) {
                    return false;
                }
                int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] d : around) {
                    Block side = w.getBlockAt((int) Math.floor(x + d[0]), by, (int) Math.floor(z + d[1]));
                    if (!side.isPassable() && side.getType() != Material.WATER) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean open(Point a, Point b) {
                double len = a.flatDistance(b);
                int steps = Math.max(1, (int) Math.ceil(len / 0.5));
                int y = (int) Math.floor(a.y());
                for (int i = 0; i <= steps; i++) {
                    double t = i / (double) steps;
                    Block at = w.getBlockAt((int) Math.floor(a.x() + (b.x() - a.x()) * t), y,
                            (int) Math.floor(a.z() + (b.z() - a.z()) * t));
                    if (!at.isPassable() && at.getType() != Material.WATER) {
                        return false;
                    }
                }
                return true;
            }
        };
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
