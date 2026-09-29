package com.dierks.homecraft.games.gen;

import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.gen.api.Slots;
import com.dierks.homecraft.games.gen.engine.GenService;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * "New courses this week!" (EXTRAS E2): when a new set of Fresh Courses is up, every player reads
 * ONE chat line about it, once per set: "&amp;aNew courses this week! &amp;7Easy, Parkour, Hard, Sky
 * Rings and Golf - &amp;e/hcm play".
 *
 * <p><b>Which set.</b> A set is the newest edition among the slots whose live layout is vouched for
 * and current ({@link GenService#report}), without its reroll, so an admin's reroll of one course
 * isn't a new set. The line names the courses of that edition in slot order, and its words follow
 * the cadence ("today", "this week", "new every 3 days").
 *
 * <p><b>When.</b> Builds run one course at a time, so the line waits until every switched-on course
 * is up in the new set, or {@value #SETTLE_MS} ms after the first one went up (a course whose build
 * failed is tried again later; the others shouldn't wait for it). Then anyone online hears it, and
 * anyone who logs in later hears it {@value #JOIN_WAIT_MS} ms after joining (so it isn't lost among
 * the join messages), if they may play, are in a world the games are played in, and aren't in a world
 * game. Someone on a screen hears it when they close it, or after {@value #SCREEN_WAIT_MS} ms.
 *
 * <p><b>Once.</b> The set a player was told about is kept in {@code game_prefs}
 * ({@value #PREF_TOLD}), so a restart doesn't repeat it. {@code /hcm play news off} ({@value #PREF_NEWS}
 * = off) turns it off for that player, and {@code games.fresh.announce: false} for everyone.
 *
 * <p>It is driven by Fresh Courses' one-second timer, so it runs only while Fresh Courses does, and
 * reads the engine only through its public read-side ({@link GenService#report}).
 */
public final class NewCoursesNudge {

    /** The {@code game_prefs} key holding the set a player was last told about. */
    public static final String PREF_TOLD = "news.fresh";
    /** The {@code game_prefs} key for the player's choice: {@code off} = no line. */
    public static final String PREF_NEWS = "news";
    /** A player who just joined hears it after this long. */
    static final long JOIN_WAIT_MS = 5_000L;
    /** A player on a screen hears it when they close it, or after this long. */
    static final long SCREEN_WAIT_MS = 60_000L;
    /** The line goes out this long after a set's first course, even if another is still being built. */
    static final long SETTLE_MS = 15 * 60_000L;

    /**
     * One Fresh Courses slot as the nudge reads it.
     *
     * @param on      switched on and nothing in the way
     * @param live    its live layout, or {@code null}
     * @param current the live layout is vouched for and is the current edition's
     */
    public record Slot(String id, boolean on, GenTag live, boolean current) {
    }

    /**
     * One player online.
     *
     * @param canPlay      may use {@code /hcm play}
     * @param allowedWorld in a world the games are played in
     * @param inSession    in a world game (a course, golf)
     * @param midScreen    a screen is open
     */
    public record Viewer(UUID id, boolean canPlay, boolean allowedWorld, boolean inSession, boolean midScreen) {
    }

    /**
     * A set of courses to tell players about.
     *
     * @param key      the edition without its reroll ({@code 7:38})
     * @param cadence  its length in days
     * @param names    the courses' short names, in slot order
     * @param complete every switched-on course is up in it
     */
    public record NewSet(String key, int cadence, List<String> names, boolean complete) {
    }

    /** Everything the nudge needs from the server (a test's fake, or {@link #live}). */
    public interface Host {

        long now();

        /** {@code games.fresh.announce}. */
        boolean announce();

        /** The regular slots (not the Classics ones), in slot order. */
        List<Slot> slots();

        List<Viewer> online();

        /** A player's pref, or {@code null} when unset. */
        String pref(UUID player, String key) throws SQLException;

        void setPref(UUID player, String key, String value) throws SQLException;

        /** A chat line to a player ({@code &} codes). */
        void tell(UUID player, String line);

        /** Something went wrong reading or writing a pref (logged; never thrown). */
        void failed(String what, Exception e);
    }

    private final Host host;
    /** The set being told about, and when it was first seen. */
    private String setKey;
    private long setSince;
    /** When each online player was first seen online. */
    private final Map<UUID, Long> seen = new HashMap<>();
    /** When a player was first found on a screen with the line waiting for them. */
    private final Map<UUID, Long> screenSince = new HashMap<>();
    /** The set each online player has been told about ({@code ""}: none), or has chosen not to hear. */
    private final Map<UUID, String> told = new HashMap<>();
    /** The last tick failed (logged once until one works again). */
    private boolean failing;

    public NewCoursesNudge(Host host) {
        this.host = host;
    }

    /**
     * {@link #tick}, guarded: the line is a nicety, and a failure in it must never switch Fresh
     * Courses off (the game's guard would). The first failure is logged; the next tick tries again.
     */
    public void tickSafely() {
        try {
            tick();
            failing = false;
        } catch (RuntimeException e) {
            if (!failing) {
                failing = true;
                host.failed("tell players about the new courses", e);
            }
        }
    }

    /** Once a second: follow the sets, and tell whoever is due. Never throws a pref failure. */
    public void tick() {
        long now = host.now();
        List<Viewer> online = host.online();
        Set<UUID> here = new HashSet<>();
        for (Viewer v : online) {
            here.add(v.id());
            seen.putIfAbsent(v.id(), now);
        }
        seen.keySet().retainAll(here);
        screenSince.keySet().retainAll(here);
        told.keySet().retainAll(here);
        if (!host.announce()) {
            return;
        }
        NewSet set = newest(host.slots());
        if (set == null) {
            return;
        }
        if (!set.key().equals(setKey)) {
            setKey = set.key();
            setSince = now;
        }
        if (!set.complete() && now - setSince < SETTLE_MS) {
            return; // the other courses are still being built
        }
        String line = line(set.cadence(), set.names());
        for (Viewer v : online) {
            if (now - seen.getOrDefault(v.id(), now) < JOIN_WAIT_MS) {
                continue;
            }
            if (!v.canPlay() || !v.allowedWorld() || v.inSession()) {
                screenSince.remove(v.id());
                continue;
            }
            if (setKey.equals(toldAbout(v.id()))) {
                continue;
            }
            if (v.midScreen()) {
                long since = screenSince.computeIfAbsent(v.id(), k -> now);
                if (now - since < SCREEN_WAIT_MS) {
                    continue;
                }
            }
            screenSince.remove(v.id());
            told.put(v.id(), setKey);
            if (newsOff(v.id())) {
                continue; // remembered for this set only: turned back on, the next set is told
            }
            host.tell(v.id(), line);
            try {
                host.setPref(v.id(), PREF_TOLD, setKey);
            } catch (SQLException | RuntimeException e) {
                host.failed("keep that a player was told about the new courses", e);
            }
        }
    }

    /** The set the player was last told about ({@code ""}: none), read once while they are online. */
    private String toldAbout(UUID player) {
        String t = told.get(player);
        if (t != null) {
            return t;
        }
        String stored;
        try {
            stored = host.pref(player, PREF_TOLD);
        } catch (SQLException | RuntimeException e) {
            host.failed("read which new courses a player was told about", e);
            stored = setKey; // unknown: don't risk telling them twice
        }
        String v = stored == null ? "" : stored;
        told.put(player, v);
        return v;
    }

    private boolean newsOff(UUID player) {
        try {
            return "off".equalsIgnoreCase(host.pref(player, PREF_NEWS));
        } catch (SQLException | RuntimeException e) {
            host.failed("read a player's news setting", e);
            return true;
        }
    }

    // ---- pure (tested) ------------------------------------------------------------------------------

    /**
     * The newest set among the slots, or {@code null} when no course is up: the edition (without the
     * reroll) of the newest current layout, the courses up in it, and whether every switched-on course is.
     */
    public static NewSet newest(List<Slot> slots) {
        GenTag newest = null;
        for (Slot s : slots) {
            if (s.current() && s.live() != null && !s.live().recalled()
                    && (newest == null || s.live().day() > newest.day())) {
                newest = s.live();
            }
        }
        if (newest == null) {
            return null;
        }
        String key = newest.edition();
        List<String> names = new ArrayList<>();
        boolean complete = true;
        for (Slot s : slots) {
            boolean in = s.current() && s.live() != null && key.equals(s.live().edition());
            if (in) {
                String name = shortName(s.id());
                if (!names.contains(name)) {
                    names.add(name);
                }
            } else if (s.on()) {
                complete = false;
            }
        }
        return new NewSet(key, newest.cadence(), List.copyOf(names), complete);
    }

    /** A course's name in the line: "Easy", "Parkour", "Hard", "Sky Rings", "Golf" (both golf courses), "Ice Boat". */
    public static String shortName(String slotId) {
        String id = slotId == null ? "" : slotId;
        if (id.equals(Slots.DAILY_PARKOUR_EASY.id())) {
            return "Easy";
        }
        if (id.equals(Slots.DAILY_PARKOUR_MEDIUM.id())) {
            return "Parkour";
        }
        if (id.equals(Slots.DAILY_PARKOUR_HARD.id())) {
            return "Hard";
        }
        if (id.equals(Slots.DAILY_GOLF.id()) || id.equals(Slots.TINY_GOLF.id())) {
            return "Golf";
        }
        Slots.Def d = Slots.of(id);
        return d == null ? id : d.name();
    }

    /**
     * The line: "&amp;aNew courses this week! &amp;7Easy, Parkour, Hard, Sky Rings and Golf - &amp;e/hcm
     * play", "New courses today!" for a daily cadence, "New courses are up! (new every 3 days)" for
     * any other.
     */
    public static String line(int cadence, List<String> names) {
        String head = switch (cadence) {
            case 1 -> "&aNew courses today! &7";
            case 7 -> "&aNew courses this week! &7";
            default -> "&aNew courses are up! &7(new every " + cadence + " days) ";
        };
        return head + list(names) + " - &e/hcm play";
    }

    /** "A", "A and B", "A, B and C". */
    static String list(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "Fresh Courses";
        }
        if (names.size() == 1) {
            return names.get(0);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    // ---- the live server ---------------------------------------------------------------------------

    /** The nudge on the running server: the engine as {@code engine} gives it, and {@code settings}. */
    public static NewCoursesNudge live(GamesService games, Supplier<GenService> engine,
                                       Supplier<DailySettings> settings) {
        return new NewCoursesNudge(new Host() {
            @Override
            public long now() {
                return games.clock().nowMillis();
            }

            @Override
            public boolean announce() {
                DailySettings st = settings.get();
                return st != null && st.announce();
            }

            @Override
            public List<Slot> slots() {
                GenService e = engine.get();
                List<Slot> out = new ArrayList<>();
                if (e == null) {
                    return out;
                }
                for (GenService.SlotReport r : e.report()) {
                    if (!r.classic()) {
                        out.add(new Slot(r.id(), r.on(), r.live(), r.current()));
                    }
                }
                return out;
            }

            @Override
            public List<Viewer> online() {
                List<Viewer> out = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    InventoryType top = p.getOpenInventory().getType();
                    out.add(new Viewer(p.getUniqueId(), p.hasPermission("hcm.games.play"),
                            games.gate().worldAllowed(p.getWorld()), games.sessions().session(p) != null,
                            top != InventoryType.CRAFTING && top != InventoryType.CREATIVE));
                }
                return out;
            }

            @Override
            public String pref(UUID player, String key) throws SQLException {
                return games.dao().pref(player, key);
            }

            @Override
            public void setPref(UUID player, String key, String value) throws SQLException {
                games.dao().setPref(player, key, value);
            }

            @Override
            public void tell(UUID player, String line) {
                Player p = Bukkit.getPlayer(player);
                if (p != null) {
                    p.sendMessage(Text.of(line));
                }
            }

            @Override
            public void failed(String what, Exception e) {
                games.plugin().getLogger().log(Level.WARNING, "Fresh Courses: could not " + what, e);
            }
        });
    }
}
