package com.dierks.homecraft.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who counts as Bedrock (0.38.7): Floodgate's answer or a Floodgate-style UUID; a Floodgate API that isn't
 * ready is tried again rather than given up on until the restart; and what the start-up log and the check
 * say, each said once in the log. Floodgate itself isn't on the test classpath: {@link FakeApi} stands in for its
 * API class.
 */
class BedrockTest {

    /** Floodgate's API as far as {@link Bedrock} reaches it: getInstance and isFloodgatePlayer. */
    public static final class FakeApi {
        static FakeApi instance;
        static boolean broken;
        /** Thrown by getInstance when set: Floodgate failing to load. */
        static RuntimeException failing;
        static final Set<UUID> bedrock = new HashSet<>();

        public static FakeApi getInstance() {
            if (failing != null) {
                throw failing;
            }
            return instance;
        }

        public boolean isFloodgatePlayer(UUID id) {
            if (broken) {
                throw new IllegalStateException("not connected");
            }
            return bedrock.contains(id);
        }
    }

    private static final UUID FLOODGATE_ID = new UUID(0L, 0x0009_0000_1234_5678L);
    private static final UUID JAVA_ID = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

    private final long[] now = {1_000};
    /** What {@link Bedrock} logged in this test. */
    private final List<LogRecord> logged = new ArrayList<>();

    @BeforeEach
    void reset() {
        FakeApi.instance = null;
        FakeApi.broken = false;
        FakeApi.failing = null;
        FakeApi.bedrock.clear();
        Logger l = Logger.getAnonymousLogger();
        l.setUseParentHandlers(false);
        l.addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                logged.add(r);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        Bedrock.logTo(l);
    }

    private Bedrock.Probe probe() {
        return new Bedrock.Probe(() -> FakeApi.class, () -> now[0]);
    }

    @Test
    void aFloodgateIdHasItsTopBitsZeroAndAJavaAccountNeverDoes() {
        assertTrue(Bedrock.floodgateId(FLOODGATE_ID));
        assertFalse(Bedrock.floodgateId(JAVA_ID));
        assertFalse(Bedrock.floodgateId(UUID.randomUUID()), "a random (v4) UUID always has its version bits set");
        assertFalse(Bedrock.floodgateId(null));
    }

    @Test
    void withoutFloodgateOnlyAFloodgateStyleIdCountsAsBedrock() {
        assertTrue(Bedrock.is(FLOODGATE_ID), "Floodgate isn't on the test classpath: the UUID decides");
        assertFalse(Bedrock.is(JAVA_ID));
        assertFalse(Bedrock.is((UUID) null));
        assertEquals(Bedrock.State.NOT_INSTALLED, Bedrock.state());
        assertTrue(Bedrock.statusLine().contains("isn't installed"), Bedrock.statusLine());

        Bedrock.Probe missing = new Bedrock.Probe(() -> {
            throw new ClassNotFoundException(Bedrock.FLOODGATE);
        }, () -> now[0]);
        assertEquals(Bedrock.State.NOT_INSTALLED, missing.state());
        assertFalse(missing.unknownId(), "nothing to warn about: no Floodgate here is a choice");
    }

    @Test
    void anApiThatIsNotReadyIsTriedAgainInsteadOfGivenUpOn() {
        Bedrock.Probe p = probe();
        UUID bedrockPlayer = UUID.fromString("0f6fc3c4-6d3e-4a11-9a42-4f3f0b1c2d3e");
        FakeApi.bedrock.add(bedrockPlayer);
        assertFalse(p.is(bedrockPlayer), "Floodgate's API not there yet: a Java-shaped UUID reads as Java for now");
        assertEquals(Bedrock.State.NOT_READY, p.state());

        FakeApi.instance = new FakeApi();
        now[0] += Bedrock.RETRY_MS - 1;
        assertFalse(p.is(bedrockPlayer), "not due again yet");
        now[0] += 1;
        assertTrue(p.is(bedrockPlayer), "tried again: Floodgate answers");
        assertEquals(Bedrock.State.FOUND, p.state());
        assertFalse(p.is(JAVA_ID));
    }

    @Test
    void aFloodgateIdThatFloodgateDoesNotKnowStillCountsAndIsWarnedOnce() {
        FakeApi.instance = new FakeApi();
        Bedrock.Probe p = probe();
        assertTrue(p.is(FLOODGATE_ID), "the proxy's data never came: the UUID still says Bedrock");
        assertTrue(p.unknownId(), "and the check hears of it");
        assertTrue(Bedrock.line(p.state(), p.unknownId()).contains("send-floodgate-data"));
        assertFalse(p.is(JAVA_ID));
    }

    @Test
    void anApiThatFailsWhenAskedFallsBackToTheUuid() {
        FakeApi.instance = new FakeApi();
        FakeApi.broken = true;
        Bedrock.Probe p = probe();
        assertTrue(p.is(FLOODGATE_ID));
        assertFalse(p.is(JAVA_ID));
        assertEquals(Bedrock.State.FOUND, p.state());
    }

    @Test
    void aClassWithoutTheApisMethodsIsFailingAndTriedAgain() {
        Bedrock.Probe p = new Bedrock.Probe(() -> String.class, () -> now[0]);
        assertTrue(p.is(FLOODGATE_ID), "can't be reached: the UUID decides");
        assertEquals(Bedrock.State.FAILING, p.state());
        assertTrue(Bedrock.line(Bedrock.State.FAILING, false).contains("still count as Bedrock"));
    }

    @Test
    void theFirstLookupSaysWhatItFoundOnceAndTheStatusLineAddsNothing() {
        FakeApi.instance = new FakeApi();
        Bedrock.Probe p = probe();
        assertEquals(Bedrock.State.FOUND, p.state(), "the lookup the plugin makes at enable");
        assertEquals(1, logged.size(), "said once: " + logged.size());
        assertEquals(Level.INFO, logged.get(0).getLevel());
        assertEquals(Bedrock.line(Bedrock.State.FOUND, false), logged.get(0).getMessage());
        p.state();
        p.is(JAVA_ID);
        assertEquals(1, logged.size(), "found is said once, not at every look");

        logged.clear();
        Bedrock.Probe missing = new Bedrock.Probe(() -> {
            throw new ClassNotFoundException(Bedrock.FLOODGATE);
        }, () -> now[0]);
        missing.state();
        missing.state();
        assertEquals(1, logged.size(), "missing is said once too");
    }

    @Test
    void aFailureAfterANotReadyApiIsStillWarnedWithItsCause() {
        Bedrock.Probe p = probe();
        assertEquals(Bedrock.State.NOT_READY, p.state());
        assertEquals(1, logged.size());
        assertEquals(Level.WARNING, logged.get(0).getLevel());

        now[0] += Bedrock.RETRY_MS;
        FakeApi.failing = new IllegalStateException("Floodgate failed to load");
        assertEquals(Bedrock.State.FAILING, p.state());
        assertEquals(2, logged.size(), "a not-ready warning must not use up the failing one");
        LogRecord failed = logged.get(1);
        assertEquals(Level.WARNING, failed.getLevel());
        assertTrue(failed.getMessage().contains("can't be reached"), failed.getMessage());
        assertInstanceOf(InvocationTargetException.class, failed.getThrown(), "the cause is in the console");
        assertEquals(FakeApi.failing, failed.getThrown().getCause());

        now[0] += Bedrock.RETRY_MS;
        p.state();
        now[0] += Bedrock.RETRY_MS;
        FakeApi.failing = null;
        p.state();
        assertEquals(Bedrock.State.NOT_READY, p.state());
        assertEquals(2, logged.size(), "each reason once: trying again says nothing more");
    }
}
