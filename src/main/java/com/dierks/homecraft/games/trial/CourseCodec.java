package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.GenTagCodec;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A course as the YAML text kept in {@code game_courses.data} (spec §11: "YAML data, incl. rev").
 *
 * <p>YAML because an admin reading the database (or a backup) can see a course at a glance —
 * the start, each checkpoint with its radius, the finish — and could even fix one by hand. So the
 * reader is forgiving and never throws: a missing or garbled point is left out and reported, an
 * unknown tier reads as easy, a name is cleaned as if it had just been typed, and a course marked
 * enabled without a start and a finish reads as closed. Only text that isn't YAML at all, or a
 * course of no known kind, can't be read.
 *
 * <p>Coordinates are kept to the thousandth of a block, facing to a tenth of a degree, radii to a
 * hundredth: plenty for a course, and the text stays readable.
 *
 * <p>A course Fresh Courses made also has a {@code gen:} block ({@link GenTagCodec}); a course
 * without one is written exactly as it always was. A {@code gen:} block that can't be read is the
 * one thing not read forgivingly into something else: the course comes back closed and without
 * its tag, and says why, so nobody plays a layout nothing vouches for.
 */
public final class CourseCodec {

    private CourseCodec() {
    }

    /**
     * What reading a course gave.
     *
     * @param course   the course, or {@code null} when it couldn't be read at all
     * @param problems what was wrong with it, in admin words (empty = nothing)
     */
    public record Decoded(Course course, List<String> problems) {

        public Decoded {
            problems = List.copyOf(problems);
        }
    }

    /** The course as YAML text. */
    public static String encode(Course c) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("name", c.name());
        y.set("kind", c.kind().id());
        y.set("tier", c.tier().id());
        y.set("world", c.world());
        y.set("enabled", c.enabled());
        y.set("pinned", c.pinned());
        y.set("rev", c.rev());
        if (c.start() != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("x", round(c.start().x(), 1000));
            s.put("y", round(c.start().y(), 1000));
            s.put("z", round(c.start().z(), 1000));
            s.put("yaw", round(c.start().yaw(), 10));
            s.put("pitch", round(c.start().pitch(), 10));
            y.set("start", s);
        }
        List<Map<String, Object>> cps = new ArrayList<>();
        for (Course.Mark m : c.checkpoints()) {
            cps.add(mark(m));
        }
        y.set("checkpoints", cps);
        if (c.finish() != null) {
            y.set("finish", mark(c.finish()));
        }
        if (c.fallY() != null) {
            y.set("fall_y", round(c.fallY(), 1000));
        }
        if (c.minSeconds() != null) {
            y.set("min_seconds", c.minSeconds());
        }
        if (c.gen() != null) {
            y.set(GenTagCodec.KEY, GenTagCodec.write(c.gen()));
        }
        return y.saveToString();
    }

    /** Read course {@code id} from its YAML text; never throws. */
    public static Decoded decode(String id, String text) {
        List<String> problems = new ArrayList<>();
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(text == null ? "" : text);
        } catch (InvalidConfigurationException | RuntimeException e) {
            return new Decoded(null, List.of("its data isn't readable"));
        }
        TrialKind kind = TrialKind.of(y.getString("kind"));
        if (kind == null) {
            return new Decoded(null, List.of("its kind '" + y.getString("kind", "") + "' isn't "
                    + String.join(", ", TrialKind.ids())));
        }
        Tier tier = Tier.of(y.getString("tier", Tier.EASY.id()));
        if (tier == null) {
            problems.add("its tier '" + y.getString("tier") + "' isn't one of " + String.join(", ", Tier.ids())
                    + " - read as easy");
            tier = Tier.EASY;
        }
        String name = TrialText.cleanName(y.getString("name"));
        if (name == null) {
            name = TrialText.defaultName(id);
        }
        Course.Spot start = null;
        if (y.isSet("start")) {
            start = spot(y.get("start"));
            if (start == null) {
                problems.add("its start can't be read - set it again");
            }
        }
        List<Course.Mark> checkpoints = new ArrayList<>();
        List<?> raw = y.getList("checkpoints", List.of());
        for (int i = 0; i < raw.size(); i++) {
            Course.Mark m = mark(raw.get(i));
            if (m == null) {
                problems.add("checkpoint " + (i + 1) + " can't be read - left out");
            } else {
                checkpoints.add(m);
            }
        }
        Course.Mark finish = null;
        if (y.isSet("finish")) {
            finish = mark(y.get("finish"));
            if (finish == null) {
                problems.add("its finish can't be read - set it again");
            }
        }
        Double fallY = null;
        if (y.isSet("fall_y")) {
            fallY = number(y.get("fall_y"));
            if (fallY == null) {
                problems.add("its fall_y isn't a number - left out");
            }
        }
        Integer minSeconds = null;
        if (y.isSet("min_seconds")) {
            Double v = number(y.get("min_seconds"));
            if (v == null) {
                problems.add("its min_seconds isn't a number - left out");
            } else {
                minSeconds = (int) Math.max(0, Math.min(3600, Math.round(v)));
            }
        }
        boolean enabled = y.getBoolean("enabled", false);
        GenTag gen = null;
        if (y.isSet(GenTagCodec.KEY)) {
            try {
                Map<?, ?> block = map(y.get(GenTagCodec.KEY));
                if (block == null) {
                    throw new IllegalArgumentException("gen is not a map");
                }
                gen = GenTagCodec.read(block);
            } catch (IllegalArgumentException e) {
                problems.add("its gen: block can't be read (" + e.getMessage() + ") - closed");
                enabled = false;
            }
        }
        Course c = new Course(id, kind, name, tier, y.getString("world", ""), start, checkpoints, finish, fallY,
                minSeconds, enabled, y.getBoolean("pinned", false), y.getInt("rev", 1), gen);
        if (enabled && !c.ready()) {
            problems.add("it was open without a start and a finish - closed");
            c = c.withEnabled(false);
        }
        return new Decoded(c, problems);
    }

    private static Map<String, Object> mark(Course.Mark m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("x", round(m.x(), 1000));
        out.put("y", round(m.y(), 1000));
        out.put("z", round(m.z(), 1000));
        out.put("radius", round(m.radius(), 100));
        return out;
    }

    private static Course.Spot spot(Object raw) {
        Map<?, ?> m = map(raw);
        if (m == null) {
            return null;
        }
        Double x = number(m.get("x"));
        Double y = number(m.get("y"));
        Double z = number(m.get("z"));
        if (x == null || y == null || z == null) {
            return null;
        }
        Double yaw = number(m.get("yaw"));
        Double pitch = number(m.get("pitch"));
        return new Course.Spot(x, y, z, yaw == null ? 0f : yaw.floatValue(), pitch == null ? 0f : pitch.floatValue());
    }

    private static Course.Mark mark(Object raw) {
        Map<?, ?> m = map(raw);
        if (m == null) {
            return null;
        }
        Double x = number(m.get("x"));
        Double y = number(m.get("y"));
        Double z = number(m.get("z"));
        Double r = number(m.get("radius"));
        if (x == null || y == null || z == null || r == null) {
            return null;
        }
        return new Course.Mark(x, y, z, Course.radius(r));
    }

    /** A map from a YAML value: a section or a plain map (a list entry). */
    private static Map<?, ?> map(Object raw) {
        if (raw instanceof ConfigurationSection s) {
            return s.getValues(false);
        }
        return raw instanceof Map<?, ?> m ? m : null;
    }

    private static Double number(Object raw) {
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (raw instanceof String s) {
            try {
                double d = Double.parseDouble(s.trim());
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static double round(double v, double per) {
        return Math.round(v * per) / per;
    }
}
