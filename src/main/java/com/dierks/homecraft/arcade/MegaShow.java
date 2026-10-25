package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Mega Firework Show (0.38.1; the owner's request: "one more level at like 75 that is even bigger: 3 droppers that
 * I have to set up, a full blown show with 3 firing locations", then "maybe we do less at a time and spread out the
 * show to like a minute long; it would be for those special events"). Pure, so a test reads the whole show, where it
 * fires from and every refusal with no server; {@link MegaShowService} plays it.
 *
 * <p><b>The show</b> is one minute (a row's {@code seconds}, {@value #MIN_SECONDS}-{@value #MAX_SECONDS}) of 81
 * rockets from up to three Firework Launchers, about 1.35 a second and never more than three in a tick before the
 * finale: an opening walk of single sparks left to right, slow chases across and back, pairs, a few volleys from all
 * three, a crescendo whose gaps shrink, a breath, and a triple finale that steps up in height and ends in gold from
 * every launcher. Each launcher has its own colours (left warm, middle green and blue, right pink and purple). A longer
 * or shorter row stretches the body and keeps the finale's five seconds as they are. With one or two launchers the
 * same 81 rockets go up from those ({@link #forLaunchers}).
 *
 * <p><b>Where it fires from</b>: the nearest launchers (anyone's) within {@code arcade.launcher.range} of whoever uses
 * it, in their world ({@link #nearest}), ordered left to right as they face them ({@link #leftToRight}).
 */
public final class MegaShow {

    /** The Firework Launcher's prize id: the Prize Counter row it is sold as, and the tag on every launcher item. */
    public static final String LAUNCHER_PRIZE_ID = "firework_launcher";
    /** A Mega show row's length: 60 seconds unless it says, 45 to 90. */
    public static final int MIN_SECONDS = 45;
    public static final int MAX_SECONDS = 90;
    public static final int DEFAULT_SECONDS = 60;
    /** The most launchers one show fires from. */
    public static final int MAX_LAUNCHERS = 3;
    /** The most Mega shows the whole server plays at once: "The sky is full of shows!" after that. */
    public static final int MAX_RUNNING = 4;
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
        /** the volleys from all three: big balls */
        VOLLEY,
        /** the crescendo: bursts with a trail */
        RISE,
        /** the finale: big flickering balls with a trail */
        FINALE,
        /** the last volley of the finale: gold, from every launcher */
        GOLD
    }

    /**
     * One rocket: its tick from the show's start, the lane it belongs to (0 left, 1 middle, 2 right, as the user saw
     * them at the start), the launcher it goes up from (the lane, with three), its style and height, and its number in
     * its lane (which of the lane's colours it takes).
     */
    public record Shot(int tick, int lane, int launcher, Style style, Height height, int nth) {
    }

    /** A launcher's place: its world and block. */
    public record Spot(String world, int x, int y, int z) {
    }

    /** A rocket's look, as {@code Fireworks.spawn} takes it. */
    public record Look(List<Color> colours, Color fade, FireworkEffect.Type type, boolean flicker, boolean trail) {
    }

    /** Each lane's colours: left warm, middle green and blue, right pink and purple. No colour is in two lanes. */
    static final List<List<Color>> LANE_COLOURS = List.of(
            List.of(Color.RED, Color.ORANGE, Color.YELLOW),
            List.of(Color.LIME, Color.AQUA, Color.TEAL),
            List.of(Color.FUCHSIA, Color.PURPLE, Color.BLUE));
    /** The last volley's gold, from every launcher. */
    static final List<Color> GOLD_COLOURS = List.of(Color.YELLOW, Color.ORANGE);

    private static final int L = 0;
    private static final int M = 1;
    private static final int R = 2;

    /** One rocket of the template: its beat, lane, style and height. */
    private record Beat(int beat, int lane, Style style, Height height) {
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

    // ---- the show -------------------------------------------------------------------------------------

    /** The 60-second show, in beats (half a second each), lane by lane as the user sees them. */
    static List<Beat> template() {
        List<Beat> t = new ArrayList<>();
        // 1 the opening walk (0-7.5 s): single sparks left to right, twice, then one high in the middle
        for (int b : new int[]{0, 8}) {
            t.add(new Beat(b, L, Style.SPARK, Height.LOW));
            t.add(new Beat(b + 2, M, Style.SPARK, Height.LOW));
            t.add(new Beat(b + 4, R, Style.SPARK, Height.LOW));
        }
        t.add(new Beat(14, M, Style.SPARK, Height.HIGH));
        // 2 the slow chases (8-21 s): across and back, twice, each ending a little higher
        chase(t, 16, L, R);
        chase(t, 23, R, L);
        chase(t, 30, L, R);
        chase(t, 37, R, L);
        // 3 the pairs (22-31.5 s): two at once, every second and a half, then the outside two high
        int[][] pairs = {{L, R}, {L, M}, {M, R}, {L, R}, {L, M}, {M, R}};
        for (int i = 0; i < pairs.length; i++) {
            for (int lane : pairs[i]) {
                t.add(new Beat(44 + i * 3, lane, Style.PAIR, Height.MID));
            }
        }
        t.add(new Beat(62, L, Style.PAIR, Height.HIGH));
        t.add(new Beat(62, R, Style.PAIR, Height.HIGH));
        // 4 the volleys (33-41 s): all three at once, every two and a half seconds, the last one high
        for (int b : new int[]{66, 71, 76}) {
            all(t, b, Style.VOLLEY, Height.MID);
        }
        all(t, 81, Style.VOLLEY, Height.HIGH);
        // 5 the crescendo (42-53.5 s): the gaps shrink and it climbs, ending with all three high
        int[][] rise = {{84, L}, {87, R}, {90, M}, {92, L}, {94, R}, {96, M}};
        for (int[] r : rise) {
            t.add(new Beat(r[0], r[1], Style.RISE, Height.LOW));
        }
        int[][] higher = {{98, L}, {98, R}, {100, M}, {101, L}, {102, R}, {103, M}};
        for (int[] r : higher) {
            t.add(new Beat(r[0], r[1], Style.RISE, Height.MID));
        }
        t.add(new Beat(104, L, Style.RISE, Height.HIGH));
        t.add(new Beat(104, R, Style.RISE, Height.HIGH));
        t.add(new Beat(105, M, Style.RISE, Height.HIGH));
        all(t, 106, Style.RISE, Height.HIGH);
        // (a breath: 53.5-55 s)
        // 6 the triple finale (55-58 s): all three low, middle, high; then low and high together; then the gold
        all(t, 110, Style.FINALE, Height.LOW);
        all(t, 111, Style.FINALE, Height.MID);
        all(t, 112, Style.FINALE, Height.HIGH);
        all(t, 114, Style.FINALE, Height.LOW);
        all(t, 114, Style.FINALE, Height.HIGH);
        all(t, 116, Style.GOLD, Height.HIGH);
        return t;
    }

    /** A chase from lane {@code from} to {@code to} through the middle, a second apart, the last one at MID. */
    private static void chase(List<Beat> t, int beat, int from, int to) {
        t.add(new Beat(beat, from, Style.CHASE, Height.LOW));
        t.add(new Beat(beat + 2, M, Style.CHASE, Height.LOW));
        t.add(new Beat(beat + 4, to, Style.CHASE, Height.MID));
    }

    /** All three lanes at once, left to right. */
    private static void all(List<Beat> t, int beat, Style style, Height height) {
        for (int lane = L; lane <= R; lane++) {
            t.add(new Beat(beat, lane, style, height));
        }
    }

    /**
     * The show of {@code seconds} (brought into {@value #MIN_SECONDS}-{@value #MAX_SECONDS}), in tick order, with
     * three launchers (each shot's launcher is its lane). The finale's five seconds end the show exactly as they are;
     * the body before them stretches or shrinks to fill the rest (a minute plays the template's beats as they are).
     * The same every time.
     */
    public static List<Shot> plan(int seconds) {
        int finale = seconds(seconds) * 20 - FINALE_TICKS;
        double k = finale / (double) (FINALE_BEAT * BEAT);
        int[] nth = new int[MAX_LAUNCHERS];
        List<Shot> out = new ArrayList<>();
        for (Beat b : template()) {
            int tick = b.beat() < FINALE_BEAT ? (int) Math.round(b.beat() * BEAT * k)
                    : finale + (b.beat() - FINALE_BEAT) * BEAT;
            out.add(new Shot(tick, b.lane(), b.lane(), b.style(), b.height(), nth[b.lane()]++));
        }
        out.sort(Comparator.comparingInt(Shot::tick)); // stable: a beat's shots keep their order
        return List.copyOf(out);
    }

    /**
     * {@code plan} folded onto {@code launchers} (1-3) launchers: with three, as it is; with two, the left lane on the
     * first, the right on the second and the middle's shots alternately on each; with one, everything on it. Same
     * rockets, same ticks, same colours (each keeps its lane's): "it fires from those". A rocket that would go up from
     * a launcher in the same tick and at the same height as one before it takes the nearest height still free there
     * ({@link #free}), so rockets stacked on one launcher burst apart.
     */
    public static List<Shot> forLaunchers(List<Shot> plan, int launchers) {
        int n = Math.max(1, Math.min(MAX_LAUNCHERS, launchers));
        if (n == MAX_LAUNCHERS) {
            return plan;
        }
        List<Shot> out = new ArrayList<>(plan.size());
        Map<Long, Set<Height>> used = new HashMap<>();
        int middle = 0;
        for (Shot s : plan) {
            int to = n == 1 ? 0 : s.lane() == L ? 0 : s.lane() == R ? 1 : middle++ % 2;
            Set<Height> here = used.computeIfAbsent(s.tick() * 4L + to, k -> EnumSet.noneOf(Height.class));
            Height h = free(s.height(), here);
            here.add(h);
            out.add(new Shot(s.tick(), s.lane(), to, s.style(), h, s.nth()));
        }
        return List.copyOf(out);
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
     * A rocket's look: two of its lane's colours ({@code nth} and the one after, so each rocket of a lane differs from
     * the last), fading to white; the gold volley is yellow and orange from every launcher. Small balls with a trail
     * for the walk and the chases, stars for the pairs, big balls for the volleys, bursts with a trail for the
     * crescendo, and big flickering balls with a trail for the finale. Never a creeper face.
     */
    public static Look look(Shot s) {
        List<Color> set = LANE_COLOURS.get(s.lane());
        List<Color> two = s.style() == Style.GOLD ? GOLD_COLOURS
                : List.of(set.get(s.nth() % set.size()), set.get((s.nth() + 1) % set.size()));
        return switch (s.style()) {
            case SPARK, CHASE -> new Look(two, Color.WHITE, FireworkEffect.Type.BALL, false, true);
            case PAIR -> new Look(two, Color.WHITE, FireworkEffect.Type.STAR, false, false);
            case VOLLEY -> new Look(two, Color.WHITE, FireworkEffect.Type.BALL_LARGE, false, false);
            case RISE -> new Look(two, Color.WHITE, FireworkEffect.Type.BURST, false, true);
            case FINALE, GOLD -> new Look(two, Color.WHITE, FireworkEffect.Type.BALL_LARGE, true, true);
        };
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
     * Why a Mega show can't start, in the order it is asked, or null when it can. Every refusal comes before anything
     * is claimed or used up, so the show stays in the hand: in a game or a Games world; the user's long show (a Grand
     * Firework Show, or another Mega show) still on, as 0.38.1's one-long-show guard says; no launcher near enough
     * ({@code found} none); one of those launchers in a show; or {@value #MAX_RUNNING} shows on already.
     */
    public static String refusal(boolean playsHere, boolean longShowOn, int found, boolean busy, int running) {
        if (!playsHere) {
            return PrizeItemListener.SHOW_NOT_HERE;
        }
        if (longShowOn) {
            return PrizeItemListener.SHOW_ON;
        }
        if (found <= 0) {
            return NO_LAUNCHER;
        }
        if (busy) {
            return BUSY;
        }
        if (running >= MAX_RUNNING) {
            return SKY_FULL;
        }
        return null;
    }

    /** What the user hears when it starts, from {@code launchers} launchers. */
    public static String startLine(int launchers) {
        return "&d✦ Mega Firework Show! &7Look at the launcher" + (launchers == 1 ? "" : "s") + "!";
    }

    /** The server-wide line (at most one of a player's a minute, {@code PrizeService.announce}). */
    public static String announceLine(String player) {
        return "&d✦ " + player + " started a Mega Firework Show!";
    }
}
