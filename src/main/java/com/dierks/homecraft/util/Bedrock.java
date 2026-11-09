package com.dierks.homecraft.util;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Is this player on Bedrock (through Geyser + Floodgate)?
 *
 * <p>Bedrock clients differ in two ways the menus care about: a custom-textured player head in an
 * inventory shows as a plain head unless the proxy registers it, and rapid inventory updates
 * stutter. Screens use this to pick a plain-material icon and a shorter animation, and golf shows a
 * Bedrock player their ball on an armour stand (Geyser can't draw display entities).
 *
 * <p>Floodgate is a soft dependency, reached by reflection so the plugin builds and runs without
 * it. Two answers count as Bedrock: Floodgate's own, and (0.38.7) a Floodgate-style UUID, whose top
 * 64 bits are zero. A Java account never has one, so a Bedrock player still reads as Bedrock when
 * Floodgate here doesn't know them (the proxy's data never reached this server: its
 * {@code send-floodgate-data}, or a {@code key.pem} that doesn't match). That case is warned once in
 * the console, and so is an API that fails when asked.
 *
 * <p>An API that isn't ready yet, or fails to load, is tried again after {@value #RETRY_MS} ms
 * instead of being given up on for good (before 0.38.7 one miss made every player Java until the
 * restart, silently). The lookup itself logs what it found: found or missing once, at INFO; not
 * ready and failing each once, as a WARN, the first failure with its exception. The plugin makes
 * that first lookup at enable ({@link #state()}), so the start-up log says it once, and
 * {@code /hcm games check} asks {@link #state()} again.
 */
public final class Bedrock {

    /** Floodgate's API class. */
    static final String FLOODGATE = "org.geysermc.floodgate.api.FloodgateApi";
    /** A Floodgate API that isn't ready, or failed, is tried again after this long. */
    static final long RETRY_MS = 5_000;

    /** What this server knows of Floodgate. */
    public enum State {
        /** Floodgate is installed and answering. */
        FOUND,
        /** Floodgate isn't installed: only Floodgate-style UUIDs count as Bedrock. */
        NOT_INSTALLED,
        /** Floodgate is installed but its API isn't there yet: tried again. */
        NOT_READY,
        /** Floodgate is installed but its API can't be reached: tried again. */
        FAILING
    }

    private static volatile Logger log = Logger.getLogger("HomeCraftManagement");
    private static final Probe LIVE = new Probe(() -> Class.forName(FLOODGATE), System::currentTimeMillis);

    private Bedrock() {
    }

    /** Log to the plugin's own logger (called once at enable). */
    public static void logTo(Logger logger) {
        if (logger != null) {
            log = logger;
        }
    }

    public static boolean is(Player player) {
        return player != null && is(player.getUniqueId());
    }

    public static boolean is(UUID uuid) {
        return LIVE.is(uuid);
    }

    /** What this server knows of Floodgate now (looked up again when it's due). */
    public static State state() {
        return LIVE.state();
    }

    /** Whether a player with a Floodgate-style UUID was unknown to Floodgate here. */
    public static boolean unknownFloodgateIds() {
        return LIVE.unknownId();
    }

    /** {@link #line} for this server now (the lookup logs on its own: this only says it). */
    public static String statusLine() {
        return line(state(), unknownFloodgateIds());
    }

    /** What {@code state} means for Bedrock players, in one line; {@code unknownIds} adds the proxy's hint. */
    static String line(State state, boolean unknownIds) {
        String s = switch (state) {
            case FOUND -> "Bedrock: Floodgate found; Bedrock players get the Bedrock ball and icons";
            case NOT_INSTALLED -> "Bedrock: Floodgate isn't installed; only Floodgate-style UUIDs count as Bedrock";
            case NOT_READY -> "Bedrock: Floodgate is installed but its API isn't ready yet; Floodgate-style UUIDs"
                    + " still count as Bedrock";
            case FAILING -> "Bedrock: Floodgate is installed but its API can't be reached; Floodgate-style UUIDs"
                    + " still count as Bedrock";
        };
        return unknownIds ? s + " (a player with a Floodgate UUID was unknown to Floodgate here: check the proxy's"
                + " send-floodgate-data and key.pem)" : s;
    }

    /** {@code FloodgateApi#isFloodgateId}: a Floodgate-made Bedrock UUID has its top 64 bits zero. */
    static boolean floodgateId(UUID uuid) {
        return uuid != null && uuid.getMostSignificantBits() == 0L;
    }

    /**
     * The lookup itself, with the class and the clock passed in so the retry is tested without Floodgate.
     * Safe from any thread: the fields are volatile and the lookup runs under the probe's lock.
     */
    static final class Probe {

        /** Finds Floodgate's API class (throws when it isn't installed). */
        @FunctionalInterface
        interface Finder {
            Class<?> find() throws ClassNotFoundException;
        }

        private final Finder finder;
        private final LongSupplier clock;
        private volatile boolean resolved;
        private volatile long nextTry;
        private volatile State state = State.NOT_READY;
        private volatile Object api;
        private volatile Method isFloodgatePlayer;
        /** Warned once each: a not-ready API says nothing of why, so it must never use up the failing warning. */
        private final AtomicBoolean warnedNotReady = new AtomicBoolean();
        private final AtomicBoolean warnedFailing = new AtomicBoolean();
        private final AtomicBoolean warnedCall = new AtomicBoolean();
        private final AtomicBoolean warnedId = new AtomicBoolean();

        Probe(Finder finder, LongSupplier clock) {
            this.finder = finder;
            this.clock = clock;
        }

        boolean is(UUID uuid) {
            if (uuid == null) {
                return false;
            }
            resolve();
            Method m = isFloodgatePlayer;
            Object a = api;
            if (m != null && a != null) {
                try {
                    if (Boolean.TRUE.equals(m.invoke(a, uuid))) {
                        return true;
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    if (warnedCall.compareAndSet(false, true)) {
                        log.log(Level.WARNING, "Bedrock: Floodgate couldn't say who is on Bedrock; going by the"
                                + " UUID instead", e);
                    }
                }
            }
            if (floodgateId(uuid)) {
                if (state == State.FOUND && warnedId.compareAndSet(false, true)) {
                    log.warning("Bedrock: " + uuid + " has a Floodgate UUID but Floodgate here doesn't know them;"
                            + " counted as Bedrock. Check the proxy's send-floodgate-data and key.pem");
                }
                return true;
            }
            return false;
        }

        State state() {
            resolve();
            return state;
        }

        boolean unknownId() {
            return warnedId.get();
        }

        private void resolve() {
            if (resolved || clock.getAsLong() < nextTry) {
                return;
            }
            synchronized (this) {
                if (resolved || clock.getAsLong() < nextTry) {
                    return;
                }
                Class<?> cls;
                try {
                    cls = finder.find();
                } catch (ClassNotFoundException | LinkageError e) {
                    state = State.NOT_INSTALLED;
                    resolved = true;
                    log.info(line(State.NOT_INSTALLED, false));
                    return;
                }
                try {
                    Method m = cls.getMethod("isFloodgatePlayer", UUID.class);
                    Object a = cls.getMethod("getInstance").invoke(null);
                    if (a == null) {
                        retry(State.NOT_READY, null);
                        return;
                    }
                    isFloodgatePlayer = m;
                    api = a;
                    state = State.FOUND;
                    resolved = true;
                    log.info(line(State.FOUND, false));
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    retry(State.FAILING, e);
                }
            }
        }

        /** Not this time: tried again after {@link #RETRY_MS}; said once for each of the two reasons. */
        private void retry(State why, Throwable e) {
            state = why;
            nextTry = clock.getAsLong() + RETRY_MS;
            AtomicBoolean warned = why == State.FAILING ? warnedFailing : warnedNotReady;
            if (warned.compareAndSet(false, true)) {
                log.log(Level.WARNING, line(why, false) + "; trying again", e);
            }
        }
    }
}
