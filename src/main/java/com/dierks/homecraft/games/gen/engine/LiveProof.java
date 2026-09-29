package com.dierks.homecraft.games.gen.engine;

import com.dierks.homecraft.games.gen.api.Putt;
import com.dierks.homecraft.games.gen.dropper.DropMarks;
import com.dierks.homecraft.games.golf.BallPhysics;
import com.dierks.homecraft.games.golf.GolfCourse;
import com.dierks.homecraft.games.golf.GolfShot;
import com.dierks.homecraft.games.trial.Course;
import com.dierks.homecraft.games.trial.TrialKind;

import java.util.ArrayList;
import java.util.List;

/**
 * The last word on a built course, on the real blocks (GEN-SPEC §3.3 step 6, §3.5, §4.3).
 *
 * <p><b>Golf: the witness replay.</b> The planner proved every hole on a model of the blocks and
 * kept the expert's line. After the blocks are built (and at every boot), that same line is played
 * with {@link GolfShot} on the real blocks — {@code LiveBlocks}, their true collision shapes — and
 * must hole out in exactly as many strokes as it has putts. A miss fails the build and the old
 * course stays up: it means the model and the real blocks disagree (a Paper update changed a
 * block's shape), and nobody should play a hole whose par was proven on a different world.
 *
 * <p><b>The structural check.</b> A layout made by an older planner version can't be derived again
 * after an update. Instead of the full proof it gets a quick one: a solid block under every place
 * a player or a ball must stand. It opens with a WARN, and the new version builds from the next
 * day.
 */
public final class LiveProof {

    private LiveProof() {
    }

    /**
     * What is wrong when each hole's witness line is played on {@code blocks}: every line must hole
     * out with exactly its own number of putts and no penalty. Empty = every hole is proven.
     */
    public static List<String> replay(BallPhysics.Blocks blocks, List<GolfCourse.Hole> holes,
                                      List<List<Putt>> witness) {
        List<String> out = new ArrayList<>();
        if (witness == null || witness.size() != holes.size()) {
            out.add("the course has " + holes.size() + " holes but " + (witness == null ? 0 : witness.size())
                    + " witness lines");
            return out;
        }
        for (int i = 0; i < holes.size(); i++) {
            List<Putt> line = witness.get(i);
            if (line.isEmpty()) {
                out.add("hole " + (i + 1) + " has no witness line");
                continue;
            }
            GolfShot.Replay r;
            try {
                r = GolfShot.replay(blocks, holes.get(i), line);
            } catch (RuntimeException e) {
                out.add("hole " + (i + 1) + " can't be replayed (" + e + ")");
                continue;
            }
            if (!r.holed() || r.putts() != line.size() || r.strokes() != line.size()) {
                out.add("hole " + (i + 1) + "'s witness line " + (r.holed() ? "took " + r.strokes() + " strokes"
                        : "didn't hole out") + " on the real blocks, not " + line.size());
            }
        }
        return out;
    }

    /** Whether block (x, y, z) is solid, for {@link #structure}. */
    @FunctionalInterface
    public interface Solid {
        boolean at(int x, int y, int z);
    }

    /**
     * The quick check for a trial built by an older planner: a solid block under the start, and
     * under every checkpoint and the finish of a parkour course (rings and boat marks float or
     * span the track, so only the start is checked). Empty = fine.
     */
    public static List<String> structure(Course c, Solid solid) {
        return structure(c, solid, null);
    }

    /**
     * {@link #structure(Course, Solid)} with a way to see water (EVENTS-DROPPER-SPEC §B.1.9, the C1
     * hook): a dropper is proven by a solid block under every ledge and still water at every pool's
     * surface centre ({@link DropMarks#probes}). Without {@code water} (a caller that can't see it) a
     * dropper gets the start check only, as every other kind. For every other kind {@code water} is
     * ignored.
     *
     * @param water whether block (x, y, z) is water, or {@code null}
     */
    public static List<String> structure(Course c, Solid solid, Solid water) {
        List<String> out = new ArrayList<>();
        if (c == null || c.start() == null) {
            out.add("it has no start");
            return out;
        }
        if (!under(solid, c.start().x(), c.start().y(), c.start().z())) {
            out.add("nothing solid under the start");
        }
        if (c.kind() == TrialKind.PARKOUR) {
            int i = 0;
            for (Course.Mark m : c.targets()) {
                i++;
                if (!under(solid, m.x(), m.y(), m.z())) {
                    out.add("nothing solid under " + (i > c.checkpoints().size() ? "the finish" : "checkpoint " + i));
                }
            }
        }
        if (c.kind() == TrialKind.DROPPER && water != null) {
            int level = 0;
            for (DropMarks.Probe p : DropMarks.probes(c)) {
                if (p.water()) {
                    level++;
                    if (!water.at(p.x(), p.y(), p.z())) {
                        out.add("level " + level + "'s pool has no water at its centre");
                    }
                } else if (!solid.at(p.x(), p.y(), p.z())) {
                    out.add("nothing solid under level " + (level + 1) + "'s ledge");
                }
            }
        }
        return out;
    }

    /** The quick check for golf: a solid block under every tee, and a solid cup block. Empty = fine. */
    public static List<String> structure(GolfCourse g, Solid solid) {
        List<String> out = new ArrayList<>();
        int i = 0;
        for (GolfCourse.Hole h : g.holes()) {
            i++;
            if (h.tee() == null || !under(solid, h.tee().x(), h.tee().y(), h.tee().z())) {
                out.add("nothing solid under hole " + i + "'s tee");
            }
            if (h.cup() == null || !solid.at(h.cup().x(), h.cup().y(), h.cup().z())) {
                out.add("hole " + i + "'s cup block is missing");
            }
        }
        return out;
    }

    /** A solid block right under feet at (x, y, z). */
    private static boolean under(Solid solid, double x, double y, double z) {
        return solid.at((int) Math.floor(x), (int) Math.floor(y - 0.01), (int) Math.floor(z));
    }
}
