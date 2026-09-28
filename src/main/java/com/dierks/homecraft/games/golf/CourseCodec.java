package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.storage.GamesDao;
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
 */
public final class CourseCodec {

    /** The game every golf row belongs to. */
    public static final String GAME = "golf";
    /** The {@code kind} column of a golf row. */
    public static final String KIND = "golf";
    static final int FORMAT = 1;

    private CourseCodec() {
    }

    /** The holes as YAML text. */
    public static String write(List<GolfCourse.Hole> holes) {
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
        return yaml.saveToString();
    }

    /** The holes back from {@link #write}'s text; throws {@link IllegalArgumentException} if they can't be read. */
    public static List<GolfCourse.Hole> read(String data) {
        if (data == null || data.isBlank()) {
            return List.of();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(data);
        } catch (InvalidConfigurationException e) {
            throw new IllegalArgumentException("not YAML: " + e.getMessage(), e);
        }
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

    /** A course from its row; throws {@link IllegalArgumentException} if its holes can't be read. */
    public static GolfCourse fromRow(GamesDao.CourseRow row) {
        return new GolfCourse(row.id(), row.name(), row.world(), row.enabled(), row.rev(), read(row.data()));
    }

    /**
     * The row for a course.
     *
     * @param createdAt when it was made (kept as it was for an existing course)
     * @param now       this edit
     */
    public static GamesDao.CourseRow toRow(GolfCourse c, long createdAt, long now) {
        return new GamesDao.CourseRow(c.id(), GAME, KIND, c.name(), c.world(), c.enabled(), write(c.holes()), c.rev(),
                createdAt, now);
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
