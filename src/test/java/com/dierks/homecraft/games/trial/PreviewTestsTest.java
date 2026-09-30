package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.cup.live.CupLink;
import com.dierks.homecraft.games.gen.api.DailyStars;
import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An admin's test run of a Fresh Courses preview ({@code /hcm games gen test}, WP-ADM): it is Time
 * Trials' own test run, so it records nothing, never a board, a reward, stars, a Cup time, a quest or
 * an achievement, even though its course carries the tag (and star times) its flip would give it;
 * and "Play again" plays the preview again, until the admin tests another course.
 */
class PreviewTestsTest {

    private static final UUID ADMIN = new UUID(7, 7);
    private static final long NEXT_MONDAY = 20731;

    /** A Fresh preview as the engine hands it over: the slot's id, in the spare half, with its flip's tag. */
    private static Course preview() {
        GenTag tag = new GenTag("fresh_parkour", "parkour", 1, NEXT_MONDAY, 0, 0x3f2aL, 'B', "hash", 30_000, 60_000,
                90_000, List.of(), List.of(), 0L, 7);
        return new Course("fresh_parkour", TrialKind.PARKOUR, "Parkour", Tier.MEDIUM, "games",
                new Course.Spot(4.5, 140, 4.5, 0f, 0f), List.of(new Course.Mark(20.5, 140, 4.5, 2)),
                new Course.Mark(40.5, 140, 4.5, 3), 136.0, null, true, false, 1, tag);
    }

    /** Every call a finish could make to be recorded or paid. */
    private static final class Ledger implements TrialFinish.Ledger {
        final List<String> calls = new ArrayList<>();

        @Override
        public ScoreResult submit(String board, long ms) {
            calls.add("submit " + board);
            return new ScoreResult(true, null, true, 1);
        }

        @Override
        public void announce(ScoreResult course, ScoreResult week, boolean firstFinish) {
            calls.add("announce");
        }

        @Override
        public boolean firstClearPaid() {
            return false;
        }

        @Override
        public int pay(RewardKind kind, String ref, int tokens, String detail) {
            calls.add("pay " + ref);
            return tokens;
        }

        @Override
        public int payWhole(RewardKind kind, String ref, int tokens, String detail) {
            calls.add("payWhole " + ref);
            return tokens;
        }

        @Override
        public GamesDao.StarsAdded addStars(String dayBoard, String weekBoard, int stars) {
            calls.add("stars " + stars);
            return null;
        }

        @Override
        public int payGoal(String ref, int tokens, String detail) {
            calls.add("goal " + ref);
            return tokens;
        }

        @Override
        public void finished(TrialFinish.Run run, TrialFinish.Summary summary) {
            calls.add("finished");
        }
    }

    @Test
    void aTestRunOnAPreviewRecordsAndPaysNothing() {
        Course c = preview();
        TrialRun run = new TrialRun(ADMIN, c, true, 0); // what startTest begins: a test run
        assertTrue(run.test, "a test run");
        FairPlay.Verdict verdict = FairPlay.judge(run.test, null, false, 45_000, 5, -1);
        assertEquals(FairPlay.Kind.TEST, verdict.kind(), "judged a test whatever the time");
        assertFalse(verdict.counts(), "which never counts");
        GenTag t = c.gen();
        TrialFinish.Daily daily = new TrialFinish.Daily(t, 20724, 3, List.of(new DailyStars.Goal(6, 1)));
        TrialFinish.Run finished = new TrialFinish.Run(c.id(), c.name(), 45_000, 20725, 20724, false, false, 10, 5, 2, 3,
                daily);
        Ledger ledger = new Ledger();
        assertSame(TrialFinish.Summary.NONE, TrialFinish.settle(verdict, finished, ledger), "it earns nothing");
        assertEquals(List.of(), ledger.calls, "no board, no first finish, no stars, no goal, no quest or achievement: "
                + ledger.calls);
        assertFalse(CupLink.counts(verdict, false), "and never a Cup time");
        assertTrue(GenBoards.day(t).contains("fresh_parkour"), "(its board would be its own set's, were it counted)");
    }

    @Test
    void playAgainPlaysThePreviewAgainUntilAnotherCourseIsTested() {
        PreviewTests previews = new PreviewTests();
        assertNull(previews.again(ADMIN), "nothing yet");
        int[] runs = {0};
        previews.started(ADMIN, () -> runs[0]++);
        previews.again(ADMIN).run();
        assertEquals(1, runs[0], "Play again asks for the preview's test again");
        previews.started(ADMIN, null);
        assertTrue(previews.again(ADMIN) != null, "a missing again is ignored");
        previews.forget(ADMIN); // the admin tests the live course (startTest)
        assertNull(previews.again(ADMIN), "then Play again is that course's");
        previews.started(null, () -> runs[0]++);
        assertNull(previews.again(null), "no player, nothing kept");
    }
}
