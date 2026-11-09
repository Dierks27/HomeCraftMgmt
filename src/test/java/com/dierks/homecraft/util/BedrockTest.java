package com.dierks.homecraft.util;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who counts as Bedrock (0.38.7): Floodgate's answer or a Floodgate-style UUID; a Floodgate API that isn't
 * ready is tried again rather than given up on until the restart; and what the start-up log and the check
 * say. Floodgate itself isn't on the test classpath: {@link FakeApi} stands in for its API class.
 */
class BedrockTest {

    /** Floodgate's API as far as {@link Bedrock} reaches it: getInstance and isFloodgatePlayer. */
    public static final class FakeApi {
        static FakeApi instance;
        static boolean broken;
        static final Set<UUID> bedrock = new HashSet<>();

        public static FakeApi getInstance() {
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

    @BeforeAll
    static void quiet() {
        Logger l = Logger.getAnonymousLogger();
        l.setUseParentHandlers(false);
        Bedrock.logTo(l);
    }

    @BeforeEach
    void reset() {
        FakeApi.instance = null;
        FakeApi.broken = false;
        FakeApi.bedrock.clear();
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
}
