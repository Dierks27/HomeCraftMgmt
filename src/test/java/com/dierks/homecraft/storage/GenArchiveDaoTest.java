package com.dierks.homecraft.storage;

import com.dierks.homecraft.games.gen.api.GenBoards;
import com.dierks.homecraft.games.gen.api.Slots;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The archive table (schema v35, GEN-SPEC-KEEP §1, §8) against a real SQLite: the migration creates
 * it; each archived edition gets the next code of its slot and ends the previous one; an edition
 * flipped again keeps its code; codes are unique and never reused, even after rows are pruned; the
 * history pages newest first; a date finds the edition up that day and a seed prefix its edition;
 * pruning spares kept and recalled editions; a board's plays and records are counted and copied.
 */
class GenArchiveDaoTest {

    private static final String HARD = "fresh_parkour_hard";
    private static final long DAY = 86_400_000L;

    private Connection conn;
    private Database db;
    private GenArchiveDao archive;
    private GamesDao games;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        db = Database.open(conn, Logger.getAnonymousLogger());
        archive = new GenArchiveDao(db);
        games = new GamesDao(db);
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    static GenArchiveDao.Row entry(String slot, String edition, long start, long seed) {
        return new GenArchiveDao.Row(slot, edition, null, 0, 20731, seed, "parkour/1", "parkour", "hard", "Hard Parkour",
                start, null, new byte[]{1, 2, 3}, 47_500, 68_400, start, null);
    }

    @Test
    void theMigrationMadeTheTableWithAUniqueCode() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM hcm_meta WHERE key = 'schema_version'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "a schema version is stored");
            assertTrue(Integer.parseInt(rs.getString(1)) >= 35, "v35 ran");
        }
        archive.archive(entry(HARD, "7:40", 1000, 1), 1000);
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO gen_editions(slot, edition, code, seq, day, seed,"
                + " algo, kind, name, starts_at, built_at) VALUES('fresh_parkour', '7:41', 'HARD-1', 1, 0, 0, 'x', 'x',"
                + " 'x', 0, 0)")) {
            assertThrows(SQLException.class, ps::executeUpdate, "the unique index refuses a second HARD-1");
        }
    }

    @Test
    void eachEditionGetsTheNextCodeAndEndsTheOneBefore() throws SQLException {
        GenArchiveDao.Row a = archive.archive(entry(HARD, "7:40", 1000, 11), 1000);
        assertEquals("HARD-1", a.code(), "the first hard parkour is HARD-1");
        GenArchiveDao.Row b = archive.archive(entry(HARD, "7:41", 2000, 22), 2000);
        assertEquals("HARD-2", b.code(), "the next is HARD-2");
        GenArchiveDao.Row e = archive.archive(entry("fresh_parkour_easy", "7:41", 2100, 33), 2100);
        assertEquals("EASY-1", e.code(), "each slot counts its own");
        assertEquals(2000L, archive.get(HARD, "7:40").endsAt(), "the flip that made 7:41 live ended 7:40");
        assertNull(archive.get(HARD, "7:41").endsAt(), "7:41 is live");
        assertNull(archive.get("fresh_parkour_easy", "7:41").endsAt(), "another slot's flip ends nothing of this one");
        assertArrayEquals(new byte[]{1, 2, 3}, archive.get(HARD, "7:41").plan(), "the plan is stored");
        GenArchiveDao.Row again = archive.archive(entry(HARD, "7:41", 2500, 22).withPlan(new byte[]{9}), 2500);
        assertEquals("HARD-2", again.code(), "an edition flipped again keeps its code");
        assertEquals(2000, archive.get(HARD, "7:41").startsAt(), "and its first start");
        assertArrayEquals(new byte[]{9}, archive.get(HARD, "7:41").plan(), "with the new plan");
        assertEquals(2, archive.count(HARD), "and no second row");
        assertEquals("HARD-2", archive.byCode("hard-2").code(), "found by code in any case");
        assertEquals(GenBoards.day(HARD, "7:41"), archive.byCode("HARD-2").board(), "its board is gfresh:<slot>:<ed>");
    }

    @Test
    void codesAreUniqueAndNeverReusedEvenAfterPruning() throws SQLException {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            seen.add(archive.archive(entry(HARD, "7:" + (40 + i), 1000L * (i + 1), i), 1000L * (i + 1)).code());
        }
        assertEquals(5, seen.size(), "five editions, five codes");
        assertEquals(4, archive.prune(10_000, Set.of()), "every ended edition goes (keep: a few days)");
        assertEquals(1, archive.count(HARD), "only the live one is left");
        archive.clearKept("nothing");
        GenArchiveDao.Row next = archive.archive(entry(HARD, "7:50", 20_000, 9), 20_000);
        assertEquals("HARD-6", next.code(), "the next code is 6, never an old number again");
        assertEquals(1, archive.prune(30_000, Set.of()), "HARD-5 ended at the last flip and goes too");
        GenArchiveDao.Row after = archive.archive(entry(HARD, "7:51", 40_000, 10), 40_000);
        assertEquals("HARD-7", after.code(), "still counting up with every stored row pruned");
    }

    @Test
    void pruningSparesKeptAndRecalledEditionsAndNeverALiveOne() throws SQLException {
        archive.archive(entry(HARD, "7:40", 1000, 1), 1000);
        archive.archive(entry(HARD, "7:41", 2000, 2), 2000);
        archive.archive(entry(HARD, "7:42", 3000, 3), 3000);
        archive.archive(entry(HARD, "7:43", 4000, 4), 4000);
        assertTrue(archive.setKept(HARD, "7:40", "dragon_run"), "7:40 was kept");
        int gone = archive.prune(Long.MAX_VALUE, Set.of(HARD + "|7:41"));
        assertEquals(1, gone, "only 7:42 goes: 7:40 is kept, 7:41 is recalled, 7:43 is live");
        assertNotNull(archive.get(HARD, "7:40"), "kept");
        assertNotNull(archive.get(HARD, "7:41"), "recalled");
        assertNotNull(archive.get(HARD, "7:43"), "live");
        assertEquals(Set.of(GenBoards.day(HARD, "7:40"), GenBoards.day(HARD, "7:41"), GenBoards.day(HARD, "7:43")),
                archive.boards(), "the boards pruning keeps are those of the archived editions");
        assertEquals(1, archive.clearKept("dragon_run"), "a cleared course is forgotten");
        assertNull(archive.get(HARD, "7:40").keptAs(), "it is no longer kept");
    }

    @Test
    void historyPagesNewestFirstAndFindsByDateAndSeed() throws SQLException {
        for (int i = 0; i < 20; i++) {
            archive.archive(entry(HARD, "7:" + (40 + i), DAY * (i + 1), 0x3f2a000000000000L + i), DAY * (i + 1));
        }
        archive.archive(entry("fresh_rings", "7:59", DAY * 20 + 5, 0x1234000000000000L), DAY * 20 + 5);
        List<GenArchiveDao.Row> first = archive.list(HARD, 0, 8);
        assertEquals(8, first.size(), "a page of 8");
        assertEquals("HARD-20", first.get(0).code(), "newest first");
        assertNull(first.get(0).plan(), "a list reads no plans");
        List<GenArchiveDao.Row> third = archive.list(HARD, 16, 8);
        assertEquals(List.of("HARD-4", "HARD-3", "HARD-2", "HARD-1"), third.stream().map(GenArchiveDao.Row::code)
                .toList(), "the last page holds the oldest");
        assertEquals(20, archive.count(HARD), "twenty in all");
        assertEquals(21, archive.count(null), "and one more across every slot");
        assertEquals("RINGS-1", archive.list(null, 0, 1).get(0).code(), "every slot's list, newest first");
        GenArchiveDao.Row onDay5 = archive.liveBetween(HARD, DAY * 5 + 10, DAY * 6 - 1);
        assertEquals("HARD-5", onDay5.code(), "the edition up during day 5");
        assertNull(archive.liveBetween(HARD, 0, DAY / 2), "none before the first");
        List<GenArchiveDao.Row> bySeed = archive.bySeed(HARD, "3f2a00000000000c");
        assertEquals(List.of("HARD-13"), bySeed.stream().map(GenArchiveDao.Row::code).toList(), "a full seed");
        assertEquals(20, archive.bySeed(HARD, "3f2a0000").size(), "a short prefix matches many");
        assertEquals(0, archive.bySeed(HARD, "1234").size(), "another slot's seed isn't this slot's");
    }

    @Test
    void aBoardIsCountedAndCopiedOntoAKeptCourse() throws SQLException {
        String board = GenBoards.day(HARD, "7:40");
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID p = UUID.randomUUID();
            players.add(p);
            games.submit(p, Slots.GAME_TRIALS, board, 60_000 + i * 1000L, true, 100 + i);
            games.submit(p, Slots.GAME_TRIALS, board, 70_000, true, 200 + i);
        }
        GenArchiveDao.BoardStats st = archive.stats(Slots.GAME_TRIALS, board);
        assertEquals(3, st.players(), "three players");
        assertEquals(6, st.plays(), "six counted runs");
        assertEquals(3, archive.copyBoard(Slots.GAME_TRIALS, board, "course:dragon_run"), "three records copied");
        assertEquals(60_000L, games.best(players.get(0), Slots.GAME_TRIALS, "course:dragon_run"),
                "each player's best comes along");
        assertEquals(games.top(Slots.GAME_TRIALS, board, true, 3).stream().map(GamesDao.ScoreRow::player).toList(),
                games.top(Slots.GAME_TRIALS, "course:dragon_run", true, 3).stream().map(GamesDao.ScoreRow::player)
                        .toList(), "in the same order, with when they were set");
        assertEquals(0, archive.copyBoard(Slots.GAME_TRIALS, board, "course:dragon_run"),
                "copying again adds nobody twice");
    }
}
