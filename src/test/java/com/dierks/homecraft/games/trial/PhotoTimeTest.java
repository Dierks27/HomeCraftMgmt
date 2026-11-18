package com.dierks.homecraft.games.trial;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 0.42.0: every party race of two or more ends with "Photo time!" on the Trophy Room's podium: the racers who
 * finished, in their order, at most three; nothing for a race alone or when nobody finished.
 */
class PhotoTimeTest {

    private static PartyRace.Line line(int rank, UUID id, PartyRace.Result r) {
        return new PartyRace.Line(rank, id, "p" + rank, 60_000L + rank, r);
    }

    @Test
    void theTopThreeWhoFinishedInTheirOrder() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        UUID e = UUID.randomUUID();
        List<PartyRace.Line> lines = List.of(line(3, c, PartyRace.Result.FINISHED), line(1, a,
                PartyRace.Result.FINISHED), line(5, e, PartyRace.Result.STILL_RACING), line(2, b,
                PartyRace.Result.FINISHED), line(4, d, PartyRace.Result.FINISHED));
        assertEquals(List.of(a, b, c), ClubRaces.photoTop(lines));
        assertEquals(List.of(a), ClubRaces.photoTop(List.of(line(1, a, PartyRace.Result.FINISHED),
                line(2, b, PartyRace.Result.STILL_RACING))), "two raced, one finished: a photo of one");
        assertEquals(List.of(), ClubRaces.photoTop(List.of(line(1, a, PartyRace.Result.FINISHED))), "alone: none");
        assertEquals(List.of(), ClubRaces.photoTop(List.of(line(1, a, PartyRace.Result.STILL_RACING),
                line(2, b, PartyRace.Result.STILL_RACING))), "nobody finished: none");
        assertEquals(List.of(), ClubRaces.photoTop(null));
    }
}
