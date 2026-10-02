package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.GenTagCodec;
import com.dierks.homecraft.storage.GamesDao;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A golf course as it is kept in {@code game_courses} (spec §3.5, §12): the row's own columns
 * hold the id, name, world, switch and {@code rev}; {@code data} holds the holes as YAML, so an
 * admin reading the database sees something they can understand:
 *
 * <pre>
 * format: 1
 * holes:
 * - par: 3
 *   tee: {x: 10.5, y: 64.0, z: -3.5, yaw: 90.0}
 *   cup: {x: 18, y: 63, z: -3}
 *   corner1: {x: 8, y: 63, z: -6}
 *   corner2: {x: 20, y: 66, z: 0}
 * </pre>
 *
 * A part not set yet is simply absent. Reading never guesses: data that can't be read is an
 * error ({@link IllegalArgumentException}), so a broken row is reported and left alone rather
 * than quietly replaced by an empty course on the next edit.
 *
 * <p>A course Fresh Courses made also has a {@code gen:} block after the holes ({@link GenTagCodec},
 * with each hole's attempt and witness line); a course without one is written exactly as it
 * always was. A course kept from an Adventure Golf layout says {@code adventure: true}
 * ({@link GolfCourse#adventure}: it keeps playing Adventure Golf's rules); every other course
 * leaves it out. A course kept from a plan also says which golf planner version made it,
 * {@code kept_algo: 4} ({@link GolfCourse#keptAlgo}: a kept Golf v4 course keeps its hole clock by
 * par); a row without it reads as 0, as every row kept before 0.37 does.
 */
public final class CourseCodec {

    /** The game every golf row belongs to. */
    public static final String GAME = "golf";
    /** The {@code kind} column of a golf row. */
    public static final String KIND = "golf";
    static final int FORMAT = 1;
    /** The key a course kept from an Adventure Golf layout carries ({@link GolfCourse#adventure}). */
    static final String ADVENTURE = "adventure";
    /** The key a course kept from a plan carries: the golf planner version that made it ({@link GolfCourse#keptAlgo}). */
    static final String KEPT_ALGO = "kept_algo";

    private CourseCodec() {
    }

    /** The holes as YAML text. */
    public static String write(List<GolfCourse.Hole> holes) {
        return write(holes, null);
    }

    /** The holes and, for a generated course, its {@code gen:} block, as YAML text. */
    public static String write(List<GolfCourse.Hole> holes, GenTag gen) {
        return write(holes, gen, false);
    }

    /**
     * The holes, a generated course's {@code gen:} block, and {@code adventure: true} for a course
     * kept from an Adventure Golf layout ({@link GolfCourse#adventure}), as YAML text.
     */
    public static String write(List<GolfCourse.Hole> holes, GenTag gen, boolean adventure) {
        return write(holes, gen, adventure, 0);
    }

    /**
     * {@link #write(List, GenTag, boolean)}, and for a course kept from a plan of golf planner version
     * {@code keptAlgo} (more than 0) {@code kept_algo: <version>} ({@link GolfCourse#keptAlgo}).
     */
    public static String write(List<GolfCourse.Hole> holes, GenTag gen, boolean adventure, int keptAlgo) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", FORMAT);
        List<Map<String, Object>> list = new ArrayList<>();
        for (GolfCourse.Hole h : holes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("par", h.par());
            if (h.tee() != null) {
                Map<String, Object> tee = new LinkedHashMap<>();
                tee.put("x", h.tee().x());
                tee.put("y", h.tee().y());
                tee.put("z", h.tee().z());
                tee.put("yaw", (double) h.tee().yaw());
                m.put("tee", tee);
            }
            put(m, "cup", h.cup());
            put(m, "corner1", h.corner1());
            put(m, "corner2", h.corner2());
            list.add(m);
        }
        yaml.set("holes", list);
        if (gen != null) {
            yaml.set(GenTagCodec.KEY, GenTagCodec.write(gen));
        }
        if (adventure) {
            yaml.set(ADVENTURE, true);
        }
        if (keptAlgo > 0) {
            yaml.set(KEPT_ALGO, keptAlgo);
        }
        return yaml.saveToString();
    }

    /** The holes back from {@link #write}'s text; throws {@link IllegalArgumentException} if they can't be read. */
    public static List<GolfCourse.Hole> read(String data) {
        YamlConfiguration yaml = load(data);
        return yaml == null ? List.of() : holes(yaml);
    }

    /**
     * The {@code gen:} block of {@link #write}'s text, or {@code null} for a hand-built course;
     * throws {@link IllegalArgumentException} if it is there but can't be read.
     */
    public static GenTag readGen(String data) {
        YamlConfiguration yaml = load(data);
        return yaml == null ? null : gen(yaml);
    }

    /** The text as YAML, or {@code null} for none; throws {@link IllegalArgumentException} if it isn't YAML. */
    private static YamlConfiguration load(String data) {
        if (data == null || data.isBlank()) {
            return null;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(data);
        } catch (InvalidConfigurationException e) {
            throw new IllegalArgumentException("not YAML: " + e.getMessage(), e);
        }
        return yaml;
    }

    private static GenTag gen(YamlConfiguration yaml) {
        Object raw = yaml.get(GenTagCodec.KEY);
        if (raw == null) {
            return null;
        }
        if (raw instanceof ConfigurationSection s) {
            return GenTagCodec.read(s.getValues(false));
        }
        return GenTagCodec.read(map(raw, GenTagCodec.KEY));
    }

    private static List<GolfCourse.Hole> holes(YamlConfiguration yaml) {
        int format = yaml.getInt("format", FORMAT);
        if (format != FORMAT) {
            throw new IllegalArgumentException("unknown format " + format);
        }
        Object raw = yaml.get("holes");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> items)) {
            throw new IllegalArgumentException("holes is not a list");
        }
        List<GolfCourse.Hole> out = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("hole " + (out.size() + 1) + " is not a map");
            }
            int par = (int) number(m.get("par"), "par");
            GolfCourse.Tee tee = null;
            if (m.get("tee") != null) {
                Map<?, ?> t = map(m.get("tee"), "tee");
                tee = new GolfCourse.Tee(number(t.get("x"), "tee x"), number(t.get("y"), "tee y"),
                        number(t.get("z"), "tee z"), (float) number(t.get("yaw"), "tee yaw"));
            }
            out.add(new GolfCourse.Hole(tee, spot(m.get("cup"), "cup"), par, spot(m.get("corner1"), "corner1"),
                    spot(m.get("corner2"), "corner2")));
        }
        return out;
    }

    /** A course from its row; throws {@link IllegalArgumentException} if its holes (or its gen block) can't be read. */
    public static GolfCourse fromRow(GamesDao.CourseRow row) {
        YamlConfiguration yaml = load(row.data());
        return new GolfCourse(row.id(), row.name(), row.world(), row.enabled(), row.rev(),
                yaml == null ? List.of() : holes(yaml), yaml == null ? null : gen(yaml),
                yaml != null && adventure(yaml), yaml == null ? 0 : keptAlgo(yaml));
    }

    /**
     * The golf planner version a kept course says it was planned at ({@code kept_algo}), 0 when it
     * doesn't say; throws {@link IllegalArgumentException} if it says something that isn't one.
     */
    private static int keptAlgo(YamlConfiguration yaml) {
        Object raw = yaml.get(KEPT_ALGO);
        if (raw == null) {
            return 0;
        }
        if (raw instanceof Integer i && i >= 0) {
            return i;
        }
        throw new IllegalArgumentException(KEPT_ALGO + " is not a golf planner version: " + raw);
    }

    /**
     * Whether the text says {@code adventure: true}; throws {@link IllegalArgumentException} if it
     * says something else.
     */
    private static boolean adventure(YamlConfiguration yaml) {
        Object raw = yaml.get(ADVENTURE);
        if (raw == null) {
            return false;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException(ADVENTURE + " is not true or false");
    }

    /**
     * The row for a course.
     *
     * @param createdAt when it was made (kept as it was for an existing course)
     * @param now       this edit
     */
    public static GamesDao.CourseRow toRow(GolfCourse c, long createdAt, long now) {
        return new GamesDao.CourseRow(c.id(), GAME, KIND, c.name(), c.world(), c.enabled(),
                write(c.holes(), c.gen(), c.adventure(), c.keptAlgo()), c.rev(), createdAt, now);
    }

    private static void put(Map<String, Object> m, String key, GolfCourse.Spot s) {
        if (s == null) {
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("x", s.x());
        out.put("y", s.y());
        out.put("z", s.z());
        m.put(key, out);
    }

    private static GolfCourse.Spot spot(Object raw, String what) {
        if (raw == null) {
            return null;
        }
        Map<?, ?> m = map(raw, what);
        return new GolfCourse.Spot(whole(m.get("x"), what + " x"), whole(m.get("y"), what + " y"),
                whole(m.get("z"), what + " z"));
    }

    private static Map<?, ?> map(Object raw, String what) {
        if (raw instanceof Map<?, ?> m) {
            return m;
        }
        throw new IllegalArgumentException(what + " is not a map");
    }

    private static double number(Object raw, String what) {
        if (raw instanceof Number n && Double.isFinite(n.doubleValue())) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException(what + " is not a number");
    }

    private static int whole(Object raw, String what) {
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short) {
            return ((Number) raw).intValue();
        }
        throw new IllegalArgumentException(what + " is not a whole number");
    }
}
