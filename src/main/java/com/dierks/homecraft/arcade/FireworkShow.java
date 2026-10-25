package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeType;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * A Firework Show's schedule (0.38.1, the Grand Firework Show; the owner's request of 25 Oct: "a bigger firework
 * show (I think she means longer)"): when each burst goes off, how many fireworks it has and where they go. Pure, so
 * a test reads a show of any length with no server; {@link FireworkShows} plays it.
 *
 * <p><b>A short show</b> ({@value #SHORT_SECONDS} seconds, the Firework Show's and the default) is exactly what it has
 * always been: 6 bursts, 10 ticks apart, of 2 fireworks each within 3 blocks. <b>A longer one</b> (a firework row's
 * {@code seconds}, up to {@value #MAX_SECONDS}) bursts every 10 ticks for its whole length, a little fuller: 3 a
 * burst, a few blocks further out, alternately low and at the short show's height, so more of it bursts where a
 * player looking ahead can see it. A second after its last burst comes the finale: {@value #FINALE_FIREWORKS} at
 * once, evenly round the player, higher up.
 */
public final class FireworkShow {

    /** The shortest show, and the length of a row that doesn't say: the Firework Show's three seconds. */
    public static final int SHORT_SECONDS = 3;
    /** The longest a row may ask for. */
    public static final int MAX_SECONDS = 30;
    /** A show this long or longer is a long one: one at a time a player ({@link FireworkShows}). */
    public static final int LONG_SECONDS = 6;
    /** A burst every this many ticks, whatever the length. */
    static final long BURST_TICKS = 10;
    /** Fireworks a burst: a short show's, and a longer one's. */
    static final int SHORT_PER_BURST = 2;
    static final int LONG_PER_BURST = 3;
    /** The finale: this many fireworks at once, this many ticks after the last burst. */
    static final int FINALE_FIREWORKS = 9;
    static final long FINALE_AFTER_TICKS = 20;

    /** How a burst's fireworks are placed ({@link #spot}). */
    public enum Kind {
        /** within 3 blocks either way, at the feet, at the rocket's usual height: the short show, as always */
        SHORT,
        /** 2.5 to 5.5 blocks out, alternately low (power 0) and at the short show's height (power 1) */
        LONG,
        /** 3 to 5 blocks out, evenly round, a block up and higher up (power 2) */
        FINALE
    }

    /** One burst: its tick from the show's start, how many fireworks, and how they are placed. */
    public record Burst(long tick, int fireworks, Kind kind) {
    }

    /** Where one firework goes, from the player's feet, and its flight power ({@code Fireworks.spawn}). */
    public record Spot(double dx, double dy, double dz, int power) {
    }

    private FireworkShow() {
    }

    /** A length as a show plays it: {@value #SHORT_SECONDS} to {@value #MAX_SECONDS} seconds. */
    public static int seconds(int seconds) {
        return Math.max(SHORT_SECONDS, Math.min(MAX_SECONDS, seconds));
    }

    /**
     * The length of the show a used item plays, from its row at use time ({@code TokenPrizes.prizeId} → the row):
     * the row's {@code seconds}, or {@value #SHORT_SECONDS} when the row is gone or is no longer a firework row.
     */
    public static int seconds(Prize row) {
        return row == null || row.type() != PrizeType.FIREWORK ? SHORT_SECONDS : seconds(row.extra().seconds());
    }

    /** Whether a show of {@code seconds} is a long one ({@value #LONG_SECONDS} seconds or more). */
    public static boolean isLong(int seconds) {
        return seconds(seconds) >= LONG_SECONDS;
    }

    /**
     * A show's bursts, in tick order: one every {@value #BURST_TICKS} ticks from tick 0 for its whole length (two a
     * second), 2 fireworks each in a short show and 3 in a longer one, which then ends {@value #FINALE_AFTER_TICKS}
     * ticks after its last burst with the {@value #FINALE_FIREWORKS}-firework finale.
     */
    public static List<Burst> schedule(int seconds) {
        int s = seconds(seconds);
        boolean isShort = s == SHORT_SECONDS;
        int bursts = (int) (s * 20L / BURST_TICKS);
        List<Burst> out = new ArrayList<>(bursts + 1);
        for (int i = 0; i < bursts; i++) {
            out.add(isShort ? new Burst(i * BURST_TICKS, SHORT_PER_BURST, Kind.SHORT)
                    : new Burst(i * BURST_TICKS, LONG_PER_BURST, Kind.LONG));
        }
        if (!isShort) {
            out.add(new Burst((bursts - 1) * BURST_TICKS + FINALE_AFTER_TICKS, FINALE_FIREWORKS, Kind.FINALE));
        }
        return List.copyOf(out);
    }

    /** The tick of a show's last burst (a longer show's finale): when it is over. */
    public static long lastTick(int seconds) {
        List<Burst> bursts = schedule(seconds);
        return bursts.get(bursts.size() - 1).tick();
    }

    /**
     * Where firework {@code index} of a burst of {@code count} goes. A short show's are today's: anywhere within 3
     * blocks either way, half a block up, power 1 ({@code Fireworks.spawn}'s). A longer show's go 2.5 to 5.5 blocks
     * out at any angle, the even ones low (power 0, bursting about 5 blocks up) and the odd ones at the short show's
     * height. The finale's go evenly round, each in its own slice of the circle, 3 to 5 blocks out, a block up, at
     * power 2 (about 28 blocks up).
     */
    public static Spot spot(Kind kind, int index, int count, RandomGenerator r) {
        return switch (kind) {
            case SHORT -> {
                double dx = r.nextDouble(-3, 3);
                yield new Spot(dx, 0.5, r.nextDouble(-3, 3), 1);
            }
            case LONG -> {
                double angle = r.nextDouble(Math.PI * 2);
                double dist = 2.5 + r.nextDouble(3.0);
                yield new Spot(Math.cos(angle) * dist, 0.5, Math.sin(angle) * dist, index % 2 == 0 ? 0 : 1);
            }
            case FINALE -> {
                double angle = r.nextDouble(Math.PI * 2 / Math.max(1, count)) + index * Math.PI * 2 / Math.max(1, count);
                double dist = 3.0 + r.nextDouble(2.0);
                yield new Spot(Math.cos(angle) * dist, 1.0, Math.sin(angle) * dist, 2);
            }
        };
    }
}
