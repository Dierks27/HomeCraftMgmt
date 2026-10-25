package com.dierks.homecraft.arcade;

import com.dierks.homecraft.block.CustomBlockType;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The Mega Firework Show (0.38.1; the owner's request: "one more level at like 75 that is even bigger: 3 droppers that
 * I have to set up, a full blown show with 3 firing locations", then "maybe we do less at a time and spread out the
 * show to like a minute long; it would be for those special events"), and from 0.38.2 its bigger tiers ("Let's make it
 * so we can chain together up to 5 launchers!", "Or really as many launchers as you feel like you could do!"): the
 * Super (4 launchers), the Ultra (5) and the Ultimate (10, a minute and a half). Pure, so a test reads the whole show,
 * where it fires from and every refusal with no server; {@link MegaShowService} plays it.
 *
 * <p><b>The show</b> is one minute (a row's {@code seconds}, {@value #MIN_SECONDS}-{@value #MAX_SECONDS}) of 27
 * rockets a lane from up to {@value #MAX_LAUNCHERS} Firework Launchers, a lane a launcher: an opening walk of single
 * sparks left to right, slow chases across and back, pairs, a few volleys from every launcher, a crescendo whose gaps
 * shrink, a breath, and a triple finale that steps up in height and ends in gold from every launcher. With three lanes
 * it is 0.38.1's show to the tick (81 rockets, never more than three in a tick before the finale). With more, the walks
 * and chases sweep across every lane, the pairs and the crescendo fire whole zones (the left, the middle and the right
 * third), and the volleys and the finale are every lane at once ({@link #plan(int, int)}); a show of four or more lanes
 * longer than {@value #ENCORE_AFTER_SECONDS} seconds gets a 30-second encore (waves, mirrored pairs, ripples), so the
 * Ultimate's 90 seconds keep the minute's density. Never more than {@value #BODY_CAP} rockets in a tick before the
 * finale, nor {@value #FINALE_CAP} in it. Each lane has its own colours ({@link #laneColours}). A longer or shorter row
 * stretches the body and keeps the finale's five seconds as they are. With fewer launchers than lanes the same rockets
 * go up from those ({@link #forLaunchers(List, int, int)}). In a holiday window every rocket takes that holiday's look
 * ({@link HolidayShows}, {@link #look(Shot, int, HolidayShows.Theme)}).
 *
 * <p><b>Where it fires from</b>: the launchers its user linked with a Firework Remote, in their order
 * ({@link #pickLinked}); with none linked, the nearest launchers (anyone's) within {@code arcade.launcher.range} of
 * whoever uses it, in their world ({@link #nearest}), ordered left to right as they face them ({@link #leftToRight}).
 */
public final class MegaShow {

    /** The Firework Launcher's prize id: the Prize Counter row it is sold as, and the tag on every launcher item. */
    public static final String LAUNCHER_PRIZE_ID = "firework_launcher";
    /** A Mega show row's length: 60 seconds unless it says, 45 to 90. */
    public static final int MIN_SECONDS = 45;
    public static final int MAX_SECONDS = 90;
    public static final int DEFAULT_SECONDS = 60;
    /** The most launchers one show fires from (0.38.2: the Ultimate Firework Show's ten). */
    public static final int MAX_LAUNCHERS = 10;
    /** The fewest lanes a plan has: a row of one or two launchers plays the three-lane plan folded, as 0.38.1 did. */
    public static final int MIN_LANES = 3;
    /** The most Mega shows the whole server plays at once: "The sky is full of shows!" after that. */
    public static final int MAX_RUNNING = 4;
    /** The most rockets one show sends up in one tick before its finale, and in it. */
    public static final int BODY_CAP = 10;
    public static final int FINALE_CAP = 20;
    /** A plan of this many lanes or more, longer than {@link #ENCORE_AFTER_SECONDS}, gets the encore. */
    static final int ENCORE_LANES = 4;
    static final int ENCORE_AFTER_SECONDS = 75;
    /** The encore's length in the template's ticks: 30 seconds before the crescendo. */
    static final int ENCORE_TICKS = 600;
    /** A rocket that finds all three heights taken on its launcher moves this many ticks later, at most so often. */
    static final int SPREAD_TICKS = 2;
    static final int SPREAD_STEPS = 8;
    /** A beat: 0.38.1's burst spacing ({@link FireworkShow#BURST_TICKS}), half a second. */
    static final int BEAT = (int) FireworkShow.BURST_TICKS;
    /** The finale's first beat, and its length: the last five seconds, never stretched. */
    static final int FINALE_BEAT = 110;
    static final int FINALE_TICKS = 100;

    /** No launcher near enough: refused, and the show is kept. */
    public static final String NO_LAUNCHER = "Set up a Firework Launcher nearby first!";
    /** One of the launchers is in another Mega show: refused (never moved on to farther ones), and kept. */
    public static final String BUSY = "Those launchers are in a show right now. Try again when it ends!";
    /** {@value #MAX_RUNNING} Mega shows are on already: refused, and kept. */
    public static final String SKY_FULL = "The sky is full of shows! Try again in a minute.";
    /** The shows aren't running (the plugin is starting or stopping): refused, and kept. */
    public static final String CANT_START = "That show can't start right now.";

    /** How high a rocket bursts: the ticks it flies before it does (about 13, 20 and 28 blocks up). */
    public enum Height {
        LOW(24), MID(30), HIGH(36);

        private final int ticks;

        Height(int ticks) {
            this.ticks = ticks;
        }

        /** The ticks from launch to burst. */
        public int ticks() {
            return ticks;
        }
    }

    /** What a rocket looks like ({@link #look}): each part of the show has its own. */
    public enum Style {
        /** the opening walk: a small ball with a trail */
        SPARK,
        /** the chases: the same, running across */
        CHASE,
        /** the pairs: stars */
        PAIR,
        /** the volleys from every launcher: big balls */
        VOLLEY,
        /** the crescendo: bursts with a trail */
        RISE,
        /** the finale: big flickering balls with a trail */
        FINALE,
        /** the last volley of the finale: gold, from every launcher */
        GOLD,
        /** the encore's waves (0.38.2, four lanes or more over 75 seconds): stars with a trail */
        WAVE
    }

    /**
     * One rocket: its tick from the show's start, the lane it belongs to (0 the far left, as the user saw them at the
     * start, or #1 of a linked set), the launcher it goes up from (the lane, with as many launchers as lanes), its
     * style and height, and its number in its lane (which of the lane's colours it takes).
     */
    public record Shot(int tick, int lane, int launcher, Style style, Height height, int nth) {
    }

    /** A launcher's place: its world and block. */
    public record Spot(String world, int x, int y, int z) {
    }

    /** A rocket's look, as {@code Fireworks.spawn} takes it. */
    public record Look(List<Color> colours, Color fade, FireworkEffect.Type type, boolean flicker, boolean trail) {
    }

    /**
     * The launchers a linked set's show fires from ({@link #pickLinked}): the first ones it can use, in link order,
     * and the ones it had to skip on the way (gone, so unlinked; in another world; not loaded). The nearest path's
     * pick has none skipped.
     */
    public record Pick(List<Spot> spots, List<Spot> gone, int otherWorld, int unloaded) {
        /** Nothing picked, nothing skipped. */
        public static final Pick NONE = new Pick(List.of(), List.of(), 0, 0);

        public Pick {
            spots = List.copyOf(spots);
            gone = List.copyOf(gone);
        }

        /** {@code spots} with nothing skipped (the nearest path). */
        public static Pick of(List<Spot> spots) {
            return new Pick(spots, List.of(), 0, 0);
        }

        /** How many it skipped. */
        public int skipped() {
            return gone.size() + otherWorld + unloaded;
        }
    }

    /**
     * Ten colour sets, left to right, warm to cool, no colour in two of them: a lane's own ({@link #laneColours}). Sets
     * 0, 5 and 9 are 0.38.1's three lanes' (left warm, middle green and blue, right pink and purple).
     */
    static final List<List<Color>> COLOUR_SETS = List.of(
            List.of(Color.RED, Color.ORANGE, Color.YELLOW),
            List.of(Color.fromRGB(0xFF7F50), Color.fromRGB(0xFFD700), Color.fromRGB(0xFFFFE0)),
            List.of(Color.fromRGB(0x7FFF00), Color.fromRGB(0xADFF2F), Color.fromRGB(0xFFFF66)),
            List.of(Color.GREEN, Color.fromRGB(0x00FF7F), Color.fromRGB(0x98FB98)),
            List.of(Color.fromRGB(0x00FA9A), Color.fromRGB(0x40E0D0), Color.fromRGB(0xE0FFFF)),
            List.of(Color.LIME, Color.AQUA, Color.TEAL),
            List.of(Color.fromRGB(0x00BFFF), Color.fromRGB(0x87CEFA), Color.NAVY),
            List.of(Color.fromRGB(0x4169E1), Color.fromRGB(0x1E90FF), Color.fromRGB(0xB0E0E6)),
            List.of(Color.fromRGB(0x8A2BE2), Color.fromRGB(0xEE82EE), Color.fromRGB(0xFF69B4)),
            List.of(Color.FUCHSIA, Color.PURPLE, Color.BLUE));
    /** The three-lane show's colours (0.38.1's): left warm, middle green and blue, right pink and purple. */
    static final List<List<Color>> LANE_COLOURS = List.of(COLOUR_SETS.get(0), COLOUR_SETS.get(5), COLOUR_SETS.get(9));
    /** The last volley's gold, from every launcher. */
    static final List<Color> GOLD_COLOURS = List.of(Color.YELLOW, Color.ORANGE);

    /** One rocket of the template: its tick (in a minute's show), lane, style and height. */
    private record Cue(int tick, int lane, Style style, Height height) {
    }

    /**
     * A plan's lanes in thirds: the left zone, the middle and the right. With three lanes each lane is its zone
     * (0.38.1); with more the outside two are {@code round(lanes / 3)} wide, so the zones are symmetric and none is
     * empty.
     */
    record Zones(List<Integer> left, List<Integer> middle, List<Integer> right) {
        static Zones of(int lanes) {
            int side = (int) Math.round(lanes / 3.0);
            List<Integer> l = new ArrayList<>();
            List<Integer> m = new ArrayList<>();
            List<Integer> r = new ArrayList<>();
            for (int lane = 0; lane < lanes; lane++) {
                (lane < side ? l : lane >= lanes - side ? r : m).add(lane);
            }
            return new Zones(List.copyOf(l), List.copyOf(m), List.copyOf(r));
        }

        /** Zone 'L', 'M' or 'R'. */
        List<Integer> of(char zone) {
            return zone == 'L' ? left : zone == 'R' ? right : middle;
        }
    }

    private MegaShow() {
    }

    // ---- the row ----------------------------------------------------------------------------------------

    /** Whether a row is a Mega Firework Show: a firework row with {@code launchers}. */
    public static boolean isMega(Prize row) {
        return row != null && row.type() == PrizeType.FIREWORK && row.extra().launchers() > 0;
    }

    /** A length as a Mega show plays it: {@value #MIN_SECONDS} to {@value #MAX_SECONDS} seconds. */
    public static int seconds(int seconds) {
        return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds));
    }

    /** A Mega row's length (its {@code seconds}, read 45-90), or the default for anything else. */
    public static int seconds(Prize row) {
        return isMega(row) ? seconds(row.extra().seconds()) : DEFAULT_SECONDS;
    }

    /** The lanes of a row's plan: its {@code launchers}, at least {@value #MIN_LANES} (0.38.1's plan for 1-2). */
    public static int lanes(Prize row) {
        return lanes(row == null ? 0 : row.extra().launchers());
    }

    /** {@code launchers} as a plan's lanes: {@value #MIN_LANES} to {@value #MAX_LAUNCHERS}. */
    static int lanes(int launchers) {
        return Math.max(MIN_LANES, Math.min(MAX_LAUNCHERS, launchers));
    }

    // ---- the show -------------------------------------------------------------------------------------

    /**
     * The minute's show of {@code lanes} lanes (3-10) in ticks, lane by lane as the user sees them, with the encore
     * when asked. In the order a tick's cues go up: the table's (left to right within a zone, the left zone before the
     * middle before the right), which with three lanes is 0.38.1's order.
     */
    static List<Cue> template(int lanes, boolean encore) {
        Zones z = Zones.of(lanes);
        List<Integer> lr = new ArrayList<>();
        for (int lane = 0; lane < lanes; lane++) {
            lr.add(lane);
        }
        List<Integer> rl = List.copyOf(lr.reversed());
        List<Cue> t = new ArrayList<>();
        // 1 the opening walk (0-7.5 s): single sparks left to right, twice, then the middle high
        sweep(t, 0, lr, 40, Style.SPARK, Height.LOW, Height.LOW);
        sweep(t, 80, lr, 40, Style.SPARK, Height.LOW, Height.LOW);
        zone(t, 140, z.middle(), Style.SPARK, Height.HIGH);
        // 2 the slow chases (8-21 s): across and back, twice, each ending a little higher
        sweep(t, 160, lr, 40, Style.CHASE, Height.LOW, Height.MID);
        sweep(t, 230, rl, 40, Style.CHASE, Height.LOW, Height.MID);
        sweep(t, 300, lr, 40, Style.CHASE, Height.LOW, Height.MID);
        sweep(t, 370, rl, 40, Style.CHASE, Height.LOW, Height.MID);
        // 3 the pairs (22-31.5 s): two zones at once, every second and a half, then the outside two high
        pairs(t, 440, z, "LR LM MR LR LM MR");
        zone(t, 620, z.left(), Style.PAIR, Height.HIGH);
        zone(t, 620, z.right(), Style.PAIR, Height.HIGH);
        // 4 the volleys (33-41 s): every launcher at once, every two and a half seconds, the last one high
        for (int tick : new int[]{660, 710, 760}) {
            zone(t, tick, lr, Style.VOLLEY, Height.MID);
        }
        zone(t, 810, lr, Style.VOLLEY, Height.HIGH);
        int shift = 0;
        if (encore) {
            // the encore (42-71.5 s, four lanes or more over 75 s): waves across and back, low then mid just behind
            int e = 840;
            int[] waves = {e, e + 80, e + 160};
            for (int i = 0; i < waves.length; i++) {
                List<Integer> order = i % 2 == 0 ? lr : rl;
                sweep(t, waves[i], order, 40, Style.WAVE, Height.LOW, Height.LOW);
                sweep(t, waves[i] + 10, order, 40, Style.WAVE, Height.MID, Height.MID);
            }
            // mirrored pairs, then the outside two high; then two quick ripples across every lane
            pairs(t, e + 260, z, "LR MR LM LR MR LM");
            zone(t, e + 440, z.left(), Style.PAIR, Height.HIGH);
            zone(t, e + 440, z.right(), Style.PAIR, Height.HIGH);
            sweep(t, e + 490, lr, 10, Style.VOLLEY, Height.MID, Height.MID);
            sweep(t, e + 540, rl, 10, Style.VOLLEY, Height.HIGH, Height.HIGH);
            shift = ENCORE_TICKS;
        }
        // 5 the crescendo (42-53.5 s): the gaps shrink and it climbs, zone by zone, ending with every lane high
        Object[][] rise = {{840, 'L', Height.LOW}, {870, 'R', Height.LOW}, {900, 'M', Height.LOW},
                {920, 'L', Height.LOW}, {940, 'R', Height.LOW}, {960, 'M', Height.LOW},
                {980, 'L', Height.MID}, {980, 'R', Height.MID}, {1000, 'M', Height.MID},
                {1010, 'L', Height.MID}, {1020, 'R', Height.MID}, {1030, 'M', Height.MID},
                {1040, 'L', Height.HIGH}, {1040, 'R', Height.HIGH}, {1050, 'M', Height.HIGH}};
        for (Object[] r : rise) {
            zone(t, (int) r[0] + shift, z.of((char) r[1]), Style.RISE, (Height) r[2]);
        }
        zone(t, 1060 + shift, lr, Style.RISE, Height.HIGH);
        // (a breath: 53.5-55 s)
        // 6 the triple finale (55-58 s): every lane low, middle, high; then low and high together; then the gold
        int f = FINALE_BEAT * BEAT + shift;
        zone(t, f, lr, Style.FINALE, Height.LOW);
        zone(t, f + 10, lr, Style.FINALE, Height.MID);
        zone(t, f + 20, lr, Style.FINALE, Height.HIGH);
        zone(t, f + 40, lr, Style.FINALE, Height.LOW);
        zone(t, f + 40, lr, Style.FINALE, Height.HIGH);
        zone(t, f + 60, lr, Style.GOLD, Height.HIGH);
        return t;
    }

    /**
     * A sweep over every lane of {@code order} from {@code t0}, the last {@code across} ticks later: the j-th of n at
     * {@code t0 + round(j * across / (n - 1))}, at {@code height} but the last at {@code last}. With three lanes and 40
     * ticks it is 0.38.1's walk and chase, a second apart.
     */
    private static void sweep(List<Cue> t, int t0, List<Integer> order, int across, Style style, Height height,
                              Height last) {
        int n = order.size();
        for (int j = 0; j < n; j++) {
            int at = n == 1 ? t0 : t0 + (int) Math.round(j * across / (double) (n - 1));
            t.add(new Cue(at, order.get(j), style, j == n - 1 ? last : height));
        }
    }

    /** Every lane of a zone at once, left to right. */
    private static void zone(List<Cue> t, int tick, List<Integer> lanes, Style style, Height height) {
        for (int lane : lanes) {
            t.add(new Cue(tick, lane, style, height));
        }
    }

    /** Two zones at once ("LR LM ..." in order), every 30 ticks from {@code t0}, at MID. */
    private static void pairs(List<Cue> t, int t0, Zones z, String which) {
        String[] pairs = which.split(" ");
        for (int i = 0; i < pairs.length; i++) {
            for (char zone : pairs[i].toCharArray()) {
                zone(t, t0 + 30 * i, z.of(zone), Style.PAIR, Height.MID);
            }
        }
    }

    /**
     * 0.38.1's show of {@code seconds}: the three-lane plan ({@link #plan(int, int)} with {@value #MIN_LANES} lanes),
     * to the tick.
     */
    public static List<Shot> plan(int seconds) {
        return plan(MIN_LANES, seconds);
    }

    /**
     * The show of {@code lanes} lanes (brought into {@value #MIN_LANES}-{@value #MAX_LAUNCHERS}) and {@code seconds}
     * (brought into {@value #MIN_SECONDS}-{@value #MAX_SECONDS}), in tick order, with a launcher a lane (each shot's
     * launcher is its lane). The finale's five seconds end the show exactly as they are; the body before them
     * stretches or shrinks to fill the rest (a minute plays the template's ticks as they are). Four lanes or more over
     * {@value #ENCORE_AFTER_SECONDS} seconds get the encore instead of a slower body. Never more than
     * {@value #BODY_CAP} in a tick before the finale and {@value #FINALE_CAP} in it ({@link #capped}). The same every
     * time.
     */
    public static List<Shot> plan(int lanes, int seconds) {
        int n = lanes(lanes);
        int s = seconds(seconds);
        boolean encore = n >= ENCORE_LANES && s > ENCORE_AFTER_SECONDS;
        int templateFinale = FINALE_BEAT * BEAT + (encore ? ENCORE_TICKS : 0);
        int finale = s * 20 - FINALE_TICKS;
        double k = finale / (double) templateFinale;
        int[] nth = new int[n];
        List<Shot> out = new ArrayList<>();
        for (Cue c : template(n, encore)) {
            int tick = c.tick() < templateFinale ? (int) Math.round(c.tick() * k)
                    : finale + (c.tick() - templateFinale);
            out.add(new Shot(tick, c.lane(), c.lane(), c.style(), c.height(), nth[c.lane()]++));
        }
        out.sort(Comparator.comparingInt(Shot::tick)); // stable: a tick's shots keep their order
        return List.copyOf(capped(out, finale));
    }

    /**
     * {@code shots} (in tick order) with no tick holding more than {@value #BODY_CAP} before {@code finaleTick} or
     * {@value #FINALE_CAP} from it: a shot that finds its tick full moves to the next tick with room, in order. The
     * shipped tiers are at or under the caps by construction, so it moves none of theirs: it is the guarantee for any
     * lanes, length and fold.
     */
    static List<Shot> capped(List<Shot> shots, int finaleTick) {
        Map<Integer, Integer> count = new HashMap<>();
        List<Shot> out = new ArrayList<>(shots.size());
        boolean moved = false;
        for (Shot s : shots) {
            int tick = s.tick();
            while (count.getOrDefault(tick, 0) >= (tick < finaleTick ? BODY_CAP : FINALE_CAP)) {
                tick++;
            }
            count.merge(tick, 1, Integer::sum);
            if (tick != s.tick()) {
                moved = true;
                out.add(new Shot(tick, s.lane(), s.launcher(), s.style(), s.height(), s.nth()));
            } else {
                out.add(s);
            }
        }
        if (moved) {
            out.sort(Comparator.comparingInt(Shot::tick));
        }
        return out;
    }

    /** The first tick of a plan's finale (its first FINALE or GOLD shot), or past the end when it has none. */
    static int finaleTick(List<Shot> shots) {
        int at = Integer.MAX_VALUE;
        for (Shot s : shots) {
            if (s.style() == Style.FINALE || s.style() == Style.GOLD) {
                at = Math.min(at, s.tick());
            }
        }
        return at;
    }

    /**
     * {@code plan} folded onto {@code launchers} launchers, its lanes being its highest lane and one (0.38.1's three):
     * {@link #forLaunchers(List, int, int)}.
     */
    public static List<Shot> forLaunchers(List<Shot> plan, int launchers) {
        int lanes = 0;
        for (Shot s : plan) {
            lanes = Math.max(lanes, s.lane() + 1);
        }
        return forLaunchers(plan, Math.max(1, lanes), launchers);
    }

    /**
     * {@code plan} of {@code lanes} lanes folded onto {@code launchers} launchers (never more than its lanes): with as
     * many as it has lanes, as it is; with fewer, each lane goes to the launcher nearest its place across the row
     * (lane i of n sits at i / (n - 1)), and a lane exactly between two takes turns, shot by shot. Onto two, a
     * three-lane plan's left lane is on the first, its right on the second and its middle's shots alternately on each
     * (0.38.1's fold); onto one, everything is on it. Same rockets, same colours (each keeps its lane's): "it fires
     * from those". A rocket that would go up from a launcher in the same tick and at the same height as one before it
     * takes the nearest height still free there ({@link #free}), so rockets stacked on one launcher burst apart; in a
     * plan of four lanes or more one that finds all three heights taken moves {@value #SPREAD_TICKS} ticks later, at
     * most {@value #SPREAD_STEPS} times, until one is free (the three-lane fold is 0.38.1's, which never moves one).
     */
    public static List<Shot> forLaunchers(List<Shot> plan, int lanes, int launchers) {
        int n = Math.max(1, Math.min(lanes, launchers));
        if (n >= lanes) {
            return plan;
        }
        boolean spread = lanes > MIN_LANES;
        List<Shot> out = new ArrayList<>(plan.size());
        Map<Long, Set<Height>> used = new HashMap<>();
        int[] turns = new int[lanes];
        for (Shot s : plan) {
            int to = launcherOf(s.lane(), lanes, n, turns);
            int tick = s.tick();
            Set<Height> here = used.computeIfAbsent(tick * 16L + to, k -> EnumSet.noneOf(Height.class));
            for (int step = 0; spread && here.size() >= Height.values().length && step < SPREAD_STEPS; step++) {
                tick += SPREAD_TICKS;
                here = used.computeIfAbsent(tick * 16L + to, k -> EnumSet.noneOf(Height.class));
            }
            Height h = free(s.height(), here);
            here.add(h);
            out.add(new Shot(tick, s.lane(), to, s.style(), h, s.nth()));
        }
        out.sort(Comparator.comparingInt(Shot::tick)); // stable: only a spread shot moves
        return List.copyOf(capped(out, finaleTick(out)));
    }

    /**
     * The launcher (of {@code n}) lane {@code lane} of {@code lanes} goes up from: the nearest by place across the
     * row, a lane exactly between two taking turns ({@code turns}, a count a lane), the first one first.
     */
    static int launcherOf(int lane, int lanes, int n, int[] turns) {
        if (n <= 1 || lanes <= 1) {
            return 0;
        }
        int num = lane * (n - 1);
        int q = num / (lanes - 1);
        int r = num % (lanes - 1);
        if (r == 0 || 2 * r < lanes - 1) {
            return q;
        }
        if (2 * r > lanes - 1) {
            return q + 1;
        }
        return turns[lane]++ % 2 == 0 ? q : q + 1;
    }

    /**
     * The height a rocket takes on a launcher that sends {@code used} up in the same tick: its own, else the nearest
     * free one above it, else below; with all three taken, its own.
     */
    static Height free(Height wanted, Set<Height> used) {
        if (!used.contains(wanted)) {
            return wanted;
        }
        Height[] all = Height.values();
        for (int i = wanted.ordinal() + 1; i < all.length; i++) {
            if (!used.contains(all[i])) {
                return all[i];
            }
        }
        for (int i = wanted.ordinal() - 1; i >= 0; i--) {
            if (!used.contains(all[i])) {
                return all[i];
            }
        }
        return wanted;
    }

    /** When a show is over: its last rocket's burst (its tick and its flight). Its launchers are busy until then. */
    public static int endTick(List<Shot> shots) {
        int end = 0;
        for (Shot s : shots) {
            end = Math.max(end, s.tick() + s.height().ticks());
        }
        return end;
    }

    /**
     * Lane {@code lane}'s colours in a plan of {@code lanes}: the colour set at its place across the row, so ten lanes
     * have all ten and three have 0.38.1's three ({@link #LANE_COLOURS}). No two lanes of one plan share one.
     */
    static List<Color> laneColours(int lane, int lanes) {
        if (lanes <= 1) {
            return COLOUR_SETS.get(0);
        }
        int at = (int) Math.round(Math.max(0, Math.min(lanes - 1, lane)) * (COLOUR_SETS.size() - 1)
                / (double) (lanes - 1));
        return COLOUR_SETS.get(at);
    }

    /** 0.38.1's look of a three-lane show's rocket: {@link #look(Shot, int, HolidayShows.Theme)} with no theme. */
    public static Look look(Shot s) {
        return look(s, MIN_LANES, null);
    }

    /**
     * A rocket's look in a plan of {@code lanes}. With no theme: two of its lane's colours ({@code nth} and the one
     * after, so each rocket of a lane differs from the last), fading to white; the gold volley is yellow and orange
     * from every launcher. Small balls with a trail for the walk and the chases, stars for the pairs, stars with a
     * trail for the encore's waves, big balls for the volleys, bursts with a trail for the crescendo, and big
     * flickering balls with a trail for the finale. Never a creeper face. In a holiday window ({@code theme}): the
     * holiday's colours and fades, each lane starting on its own ({@link HolidayShows#two}), its shape on about one
     * rocket in four, every other one of the finale, and the gold (the theme's first two colours); flicker and trail as
     * the style's.
     */
    public static Look look(Shot s, int lanes, HolidayShows.Theme theme) {
        boolean flicker = s.style() == Style.FINALE || s.style() == Style.GOLD;
        boolean trail = switch (s.style()) {
            case SPARK, CHASE, RISE, WAVE, FINALE, GOLD -> true;
            case PAIR, VOLLEY -> false;
        };
        FireworkEffect.Type own = switch (s.style()) {
            case SPARK, CHASE -> FireworkEffect.Type.BALL;
            case PAIR, WAVE -> FireworkEffect.Type.STAR;
            case VOLLEY, FINALE, GOLD -> FireworkEffect.Type.BALL_LARGE;
            case RISE -> FireworkEffect.Type.BURST;
        };
        if (theme == null) {
            List<Color> set = laneColours(s.lane(), lanes);
            List<Color> two = s.style() == Style.GOLD ? GOLD_COLOURS
                    : List.of(set.get(s.nth() % set.size()), set.get((s.nth() + 1) % set.size()));
            return new Look(two, Color.WHITE, own, flicker, trail);
        }
        List<Color> colours = s.style() == Style.GOLD ? HolidayShows.firstTwo(theme)
                : HolidayShows.two(theme, s.lane(), s.nth());
        FireworkEffect.Type type = switch (s.style()) {
            case GOLD -> theme.shape();
            case FINALE -> Math.floorMod(s.lane() + s.nth(), 2) == 0 ? theme.shape() : FireworkEffect.Type.BALL_LARGE;
            default -> HolidayShows.signature(s.lane(), s.nth()) ? theme.shape() : own;
        };
        return new Look(colours, HolidayShows.fade(theme, s.lane(), s.nth()), type, flicker, trail);
    }

    // ---- where it fires from ----------------------------------------------------------------------------

    /**
     * The launchers a show fires from: of {@code all}, those in {@code world} whose block's middle is within
     * {@code range} blocks of the user at ({@code x}, {@code y}, {@code z}), nearest first (a tie by x, then y, then
     * z, so the same every time), at most {@code max}. Anyone's launchers count.
     */
    public static List<Spot> nearest(List<Spot> all, String world, double x, double y, double z, int range, int max) {
        double r2 = (double) range * range;
        return all.stream()
                .filter(s -> s.world().equals(world) && distance2(s, x, y, z) <= r2)
                .sorted(Comparator.comparingDouble((Spot s) -> distance2(s, x, y, z))
                        .thenComparingInt(Spot::x).thenComparingInt(Spot::y).thenComparingInt(Spot::z))
                .limit(Math.max(0, max))
                .toList();
    }

    private static double distance2(Spot s, double x, double y, double z) {
        double dx = s.x() + 0.5 - x;
        double dy = s.y() + 0.5 - y;
        double dz = s.z() + 0.5 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * {@code chosen} left to right as a user at ({@code x}, {@code z}) facing {@code yaw} (degrees, 0 = south, +Z)
     * sees them: by how far each block's middle lies along their right hand, {@code (-cos yaw, 0, -sin yaw)} (facing
     * south it points west), a tie by the nearer, then by x, y and z. The first is the left lane. Taken once, at the
     * start: the show keeps its lanes wherever the user goes.
     */
    public static List<Spot> leftToRight(List<Spot> chosen, double x, double z, float yaw) {
        double rad = Math.toRadians(yaw);
        double rx = -Math.cos(rad);
        double rz = -Math.sin(rad);
        return chosen.stream()
                .sorted(Comparator.comparingDouble((Spot s) -> (s.x() + 0.5 - x) * rx + (s.z() + 0.5 - z) * rz)
                        .thenComparingDouble(s -> Math.hypot(s.x() + 0.5 - x, s.z() + 0.5 - z))
                        .thenComparingInt(Spot::x).thenComparingInt(Spot::y).thenComparingInt(Spot::z))
                .toList();
    }

    /**
     * The launchers a linked set's show fires from (0.38.2, the Firework Remote): {@code linked} in its order (#1 is
     * the first lane), asking of each, in this order: in another world than {@code world} (skipped, counted); no
     * placed-block record ({@code recorded}: gone); its chunk not loaded or not entity-ticking ({@code ticking}: not
     * loaded, and never loaded for it); its block air ({@code present}, which forgets the record as it answers:
     * gone); else it is taken. It stops at {@code want} taken: the ones after that are neither used nor counted. The
     * gone ones are for the caller to unlink.
     */
    public static Pick pickLinked(List<Spot> linked, String world, Predicate<Spot> recorded, Predicate<Spot> ticking,
                                  Predicate<Spot> present, int want) {
        List<Spot> taken = new ArrayList<>();
        List<Spot> gone = new ArrayList<>();
        int otherWorld = 0;
        int unloaded = 0;
        for (Spot s : linked) {
            if (taken.size() >= want) {
                break;
            }
            if (!s.world().equals(world)) {
                otherWorld++;
            } else if (!recorded.test(s)) {
                gone.add(s);
            } else if (!ticking.test(s)) {
                unloaded++;
            } else if (!present.test(s)) {
                gone.add(s);
            } else {
                taken.add(s);
            }
        }
        return new Pick(taken, gone, otherWorld, unloaded);
    }

    // ---- using one ------------------------------------------------------------------------------------

    /**
     * Whether a tap uses the show: in the air or on a plain block, and on a Firework Launcher; never on another of
     * ours (a HomeCraft block, a join sign, a Mini, a placed Arcade Trophy), whose own tap that is, as for a long
     * show and a hat.
     */
    public static boolean usesOnTap(boolean onBlock, boolean ours, boolean launcher) {
        return !onBlock || !ours || launcher;
    }

    /**
     * Whether a tapped block is a Firework Launcher, for {@link #usesOnTap}: its tile's tag ({@code tagged}, asked
     * first: no database), or else its placed-block record ({@code recorded}, null when there is none), which is what
     * {@code CustomBlockListener} goes by when it leaves a Mega show's tap to the show (review MEGA-RT-1). One answer
     * in both places, so a launcher whose tag is missing is never a tap that nobody answers.
     */
    public static boolean onLauncher(boolean tagged, CustomBlockType recorded) {
        return tagged || recorded == CustomBlockType.FIREWORK_LAUNCHER;
    }

    /**
     * {@link #refusal(boolean, boolean, int, String, boolean, int)} with 0.38.1's no-launcher line (the nearest path).
     */
    public static String refusal(boolean playsHere, boolean longShowOn, int found, boolean busy, int running) {
        return refusal(playsHere, longShowOn, found, NO_LAUNCHER, busy, running);
    }

    /**
     * Why a Mega show can't start, in the order it is asked, or null when it can. Every refusal comes before anything
     * is claimed or used up, so the show stays in the hand: in a game or a Games world; the user's long show (a Grand
     * Firework Show, or another Mega show) still on, as 0.38.1's one-long-show guard says; no launcher it can use
     * ({@code found} none: {@code none} says why, {@link #NO_LAUNCHER} or {@link #noneLinkedLine}); one of those
     * launchers in a show; or {@value #MAX_RUNNING} shows on already.
     */
    public static String refusal(boolean playsHere, boolean longShowOn, int found, String none, boolean busy,
                                 int running) {
        if (!playsHere) {
            return PrizeItemListener.SHOW_NOT_HERE;
        }
        if (longShowOn) {
            return PrizeItemListener.SHOW_ON;
        }
        if (found <= 0) {
            return none;
        }
        if (busy) {
            return BUSY;
        }
        if (running >= MAX_RUNNING) {
            return SKY_FULL;
        }
        return null;
    }

    /** What the user hears when 0.38.1's Mega show starts, from {@code launchers} launchers. */
    public static String startLine(int launchers) {
        return startLine("Mega Firework Show", null, launchers);
    }

    /**
     * What the user hears when a show named {@code show} (its row's display, plain) starts from {@code launchers}
     * launchers, the holiday's name before it in a window ("✦ Halloween Ultra Firework Show!").
     */
    public static String startLine(String show, HolidayShows.Theme theme, int launchers) {
        return "&d✦ " + (theme == null ? "" : theme.name() + " ") + show + "! &7Look at the launcher"
                + (launchers == 1 ? "" : "s") + "!";
    }

    /** The server-wide line for 0.38.1's Mega show (at most one of a player's a minute, {@code PrizeService.announce}). */
    public static String announceLine(String player) {
        return announceLine(player, "Mega Firework Show", null);
    }

    /**
     * The server-wide line for {@code player}'s show named {@code show}, the holiday's name before it in a window:
     * "✦ Someone started a Halloween Mega Firework Show!", "... started an Ultimate Firework Show!".
     */
    public static String announceLine(String player, String show, HolidayShows.Theme theme) {
        String named = (theme == null ? "" : theme.name() + " ") + show;
        return "&d✦ " + player + " started " + article(named) + " " + named + "!";
    }

    /** "an" before a word that starts with a vowel ("an Ultra", "an Independence Day"), else "a". */
    static String article(String words) {
        String w = words == null ? "" : words.trim().toLowerCase(Locale.ROOT);
        return !w.isEmpty() && "aeiou".indexOf(w.charAt(0)) >= 0 ? "an" : "a";
    }

    /**
     * Said after the start line when a linked set's show had to skip some ({@code pick}), or null when it skipped
     * none: "Skipped 2 of your linked launchers: 1 gone and 1 not loaded (nobody is near it)."
     */
    public static String skippedLine(Pick pick) {
        if (pick == null || pick.skipped() == 0) {
            return null;
        }
        return "&7Skipped " + pick.skipped() + " of your linked launchers: " + reasons(pick) + ".";
    }

    /** A linked set none of whose launchers can fire now ({@code pick}): refused, and the show is kept. */
    public static String noneLinkedLine(Pick pick) {
        String why = pick == null || pick.skipped() == 0 ? "" : " (" + reasons(pick) + ")";
        return "None of your linked launchers can fire right now" + why
                + ". Go near them, or change them with your Firework Remote!";
    }

    /** "1 in another world, 1 gone and 2 not loaded (nobody is near them)". */
    private static String reasons(Pick pick) {
        List<String> parts = new ArrayList<>();
        if (pick.otherWorld() > 0) {
            parts.add(pick.otherWorld() + " in another world");
        }
        if (!pick.gone().isEmpty()) {
            parts.add(pick.gone().size() + " gone");
        }
        if (pick.unloaded() > 0) {
            parts.add(pick.unloaded() + " not loaded (nobody is near " + (pick.unloaded() == 1 ? "it" : "them") + ")");
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }
}
