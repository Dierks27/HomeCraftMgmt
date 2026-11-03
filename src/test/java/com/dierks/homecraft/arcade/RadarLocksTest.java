package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.hunt.HuntMath;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a Mini Radar does once it is on ({@link RadarLocks}), checked once a second as {@link RadarService} does: a
 * reading at once and then every {@code interval_seconds}, "out of charge" once when {@code lasts_seconds} are up,
 * "the Mini is gone" once when it is caught or gets away, and one radar a player per wild Mini.
 */
class RadarLocksTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final long T0 = 1_000_000;

    private final Set<String> live = new HashSet<>(Set.of("hunt-1", "hunt-2"));

    /** The steps for one player over {@code seconds} once-a-second checks from {@code T0 + 1 s}, by second. */
    private List<String> run(RadarLocks locks, UUID player, int seconds, PluginConfig.Radar settings) {
        List<String> out = new ArrayList<>();
        for (int s = 1; s <= seconds; s++) {
            for (RadarLocks.Due d : locks.check(T0 + s * 1000L, live::contains, settings)) {
                if (d.player().equals(player)) {
                    out.add(s + ":" + d.step());
                }
            }
        }
        return out;
    }

    @Test
    void theShippedRadarReadsEverySixSecondsForAMinuteThenIsSpentOnce() {
        RadarLocks locks = new RadarLocks();
        PluginConfig.Radar shipped = PluginConfig.Radar.DEFAULTS;
        assertEquals(RadarLocks.Lock.ON, locks.lock(A, "hunt-1", T0, shipped));
        assertTrue(locks.active(A, T0, live::contains, shipped));
        List<String> steps = run(locks, A, 90, shipped);
        assertEquals(List.of("6:READ", "12:READ", "18:READ", "24:READ", "30:READ", "36:READ", "42:READ", "48:READ",
                "54:READ", "60:SPENT"), steps, "nine more readings after the one at lock-on, then out of charge once");
        assertFalse(locks.active(A, T0 + 90_000, live::contains, shipped));
    }

    @Test
    void theOldRadarReadsEveryTwoSecondsUntilTheMiniIsGone() {
        RadarLocks locks = new RadarLocks();
        PluginConfig.Radar old = new PluginConfig.Radar(2, 0, new HuntMath.Bands(12, 32, 64), false);
        locks.lock(A, "hunt-1", T0, old);
        List<String> steps = run(locks, A, 10, old);
        assertEquals(List.of("2:READ", "4:READ", "6:READ", "8:READ", "10:READ"), steps);
        assertTrue(locks.active(A, T0 + 600_000, live::contains, old), "0 = no end of its own");
        live.remove("hunt-1");
        List<RadarLocks.Due> due = locks.check(T0 + 11_000, live::contains, old);
        assertEquals(List.of(new RadarLocks.Due(A, "hunt-1", RadarLocks.Step.GONE)), due, "gone, said once");
        assertEquals(List.of(), locks.check(T0 + 12_000, live::contains, old));
        assertFalse(locks.active(A, T0 + 12_000, live::contains, old));
    }

    @Test
    void aSecondRadarOnTheSameMiniIsRefusedAndNothingChanges() {
        RadarLocks locks = new RadarLocks();
        PluginConfig.Radar shipped = PluginConfig.Radar.DEFAULTS;
        locks.lock(A, "hunt-1", T0, shipped);
        run(locks, A, 61, shipped); // spent
        assertEquals(RadarLocks.Lock.USED, locks.lock(A, "hunt-1", T0 + 62_000, shipped), "once per Mini");
        assertFalse(locks.active(A, T0 + 62_000, live::contains, shipped), "a refused radar doesn't switch on");
        assertEquals(RadarLocks.Lock.ON, locks.lock(A, "hunt-2", T0 + 62_000, shipped), "another Mini is fine");
        assertEquals(RadarLocks.Lock.ON, locks.lock(B, "hunt-1", T0 + 62_000, shipped), "and so is another player");
    }

    @Test
    void withoutOnePerHuntASecondRadarStartsAFreshCharge() {
        RadarLocks locks = new RadarLocks();
        PluginConfig.Radar many = new PluginConfig.Radar(6, 60, HuntMath.Bands.SHIPPED, false);
        locks.lock(A, "hunt-1", T0, many);
        run(locks, A, 61, many);
        assertEquals(RadarLocks.Lock.ON, locks.lock(A, "hunt-1", T0 + 62_000, many));
        assertTrue(locks.active(A, T0 + 62_000, live::contains, many));
    }

    @Test
    void aMiniThatEndsIsForgottenSoTheNextOneWithTheSameKeyIsFresh() {
        RadarLocks locks = new RadarLocks();
        PluginConfig.Radar shipped = PluginConfig.Radar.DEFAULTS;
        locks.lock(A, "hunt-1", T0, shipped);
        assertTrue(locks.usedOn(A, "hunt-1"));
        live.remove("hunt-1");
        locks.check(T0 + 1_000, live::contains, shipped);
        assertFalse(locks.usedOn(A, "hunt-1"), "forgotten when that hunt ends");
    }

    @Test
    void noWildMiniLocksNothing() {
        RadarLocks locks = new RadarLocks();
        assertEquals(RadarLocks.Lock.NO_MINI, locks.lock(A, null, T0, PluginConfig.Radar.DEFAULTS));
        assertFalse(locks.active(A, T0, live::contains, PluginConfig.Radar.DEFAULTS));
        assertEquals(List.of(), locks.check(T0 + 6_000, live::contains, PluginConfig.Radar.DEFAULTS));
    }

    @Test
    void aReloadChangesARadarThatIsAlreadyOn() {
        RadarLocks locks = new RadarLocks();
        locks.lock(A, "hunt-1", T0, PluginConfig.Radar.DEFAULTS);
        PluginConfig.Radar quicker = new PluginConfig.Radar(3, 20, HuntMath.Bands.SHIPPED, true);
        assertEquals(List.of("3:READ", "6:READ", "9:READ", "12:READ", "15:READ", "18:READ", "20:SPENT"),
                run(locks, A, 30, quicker), "the new cadence and the new length, from when it locked on");
    }

    @Test
    void aRestartRemembersNothingTheMiniOutlivesTheMemory() {
        PluginConfig.Radar shipped = PluginConfig.Radar.DEFAULTS;
        RadarLocks before = new RadarLocks();
        assertEquals(RadarLocks.Lock.ON, before.lock(A, "hunt-1", T0, shipped));
        assertEquals(RadarLocks.Lock.USED, before.lock(A, "hunt-1", T0 + 1000, shipped));

        RadarLocks after = new RadarLocks();
        assertFalse(after.active(A, T0 + 2000, live::contains, shipped), "the running radar stops");
        assertFalse(after.usedOn(A, "hunt-1"));
        assertEquals(RadarLocks.Lock.ON, after.lock(A, "hunt-1", T0 + 2000, shipped),
                "memory only (the documented limit): one more radar on a Mini that was out over a restart");
    }
}
