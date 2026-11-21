package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.GameKind;
import com.dierks.homecraft.storage.GamesDao;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.43.0, the secret trophy room: only a player with the permission goes in (anyone else gets nothing at all); each
 * visitor's trophies are their own stored bests, medals and firsts (no new scoring); a trophy bursts once, the first
 * time they come in after earning it.
 */
class SecretShelfTest {

    private static final Map<String, GameKind> KINDS = Map.of("whack_a_zombie", GameKind.CABINET, "snake",
            GameKind.CABINET, "trials", GameKind.TRIAL, "golf", GameKind.GOLF, "ore_slots", GameKind.CHANCE);

    private static SecretShelf.Games games() {
        return new SecretShelf.Games() {
            @Override
            public GameKind kind(String game) {
                return KINDS.get(game);
            }

            @Override
            public String name(String game) {
                return game.equals("whack_a_zombie") ? "Whack-a-Zombie" : game;
            }

            @Override
            public int medal(String game, String board, long score) {
                return game.equals("whack_a_zombie") ? (score >= 35 ? 3 : score >= 25 ? 2 : score >= 15 ? 1 : 0) : 0;
            }
        };
    }

    @Test
    void onlyAPlayerWithThePermissionGoesIn() {
        assertTrue(SecretShelf.mayEnter(true));
        assertFalse(SecretShelf.mayEnter(false), "no access: no teleport (and the tap says nothing)");
    }

    @Test
    void theTrophiesAreTheVisitorsOwnBestsMedalsAndFirsts() {
        List<GamesDao.Mine> rows = List.of(
                new GamesDao.Mine("whack_a_zombie", "classic", 27, 9),
                new GamesDao.Mine("whack_a_zombie", "daily:20500", 18, 1),
                new GamesDao.Mine("snake", "classic", 4, 2),
                new GamesDao.Mine("trials", "course:sky_rings", 62_400, 3),
                new GamesDao.Mine("trials", "week:sky_rings:7", 62_400, 3),
                new GamesDao.Mine("golf", "golf:windmill", 21, 1),
                new GamesDao.Mine("ore_slots", "x", 5, 40));
        List<SecretShelf.Trophy> t = SecretShelf.trophies(rows, games());
        List<String> keys = t.stream().map(SecretShelf.Trophy::key).toList();
        assertEquals(List.of("first:cabinet", "first:race", "first:golf", "played:5", "played:10",
                "best:whack_a_zombie:classic", "best:snake:classic", "best:trials:course:sky_rings",
                "best:golf:golf:windmill"), keys, "firsts, games played (16: no weekly copies, no game of chance),"
                + " then each best; never a game of chance's best, and never today's board");
        SecretShelf.Trophy whack = t.get(keys.indexOf("best:whack_a_zombie:classic"));
        assertEquals(2, whack.metal(), "27 points reached silver (25): a silver cup");
        assertTrue(whack.label().contains("Whack-a-Zombie") && whack.label().contains("27"), whack.label());
        assertEquals(3, t.get(keys.indexOf("best:snake:classic")).metal(), "no medal: bronze");
        assertTrue(t.get(keys.indexOf("best:trials:course:sky_rings")).label().contains("1:02.4"), "a course's time");
        assertTrue(t.get(keys.indexOf("best:golf:golf:windmill")).label().contains("21 strokes"));
        assertEquals(List.of(), SecretShelf.trophies(List.of(), games()), "nothing played: an empty room");
    }

    @Test
    void aNewTrophyBurstsOnceThenItIsSeen() {
        List<SecretShelf.Trophy> t = SecretShelf.trophies(List.of(new GamesDao.Mine("snake", "classic", 4, 6)),
                games());
        assertEquals(t, SecretShelf.fresh(t, Set.of()), "the first time in: all new");
        String seen = SecretShelf.seenText(t);
        assertEquals(List.of(), SecretShelf.fresh(t, SecretShelf.seen(seen)), "after that: nothing new");
        List<SecretShelf.Trophy> more = SecretShelf.trophies(List.of(new GamesDao.Mine("snake", "classic", 9, 11),
                new GamesDao.Mine("golf", "golf:windmill", 21, 1)), games());
        assertEquals(List.of("first:golf", "played:10", "best:golf:golf:windmill"),
                SecretShelf.fresh(more, SecretShelf.seen(seen)).stream().map(SecretShelf.Trophy::key).toList(),
                "only what was earned since bursts");
    }

    /**
     * The review's find, and the owner's screenshot (a ledge of cake-topped silver cups): the Clubhouse's own points
     * tallies sit in the same table as the bests and each became a "best" trophy, a new one every day.
     */
    @Test
    void pointsTalliesAreNoTrophies() {
        assertTrue(SecretShelf.scoreBoard(GameKind.CABINET, "classic"));
        assertTrue(SecretShelf.scoreBoard(GameKind.CABINET, "daily:20789"), "a play (never a best: see trophies)");
        assertTrue(SecretShelf.scoreBoard(GameKind.TRIAL, "course:sky"));
        assertTrue(SecretShelf.scoreBoard(GameKind.GOLF, "golf:windmill"));
        for (String tally : List.of("club:2970", "clubday:20789", "clubwins:2970", "ffsolo:2970", "gstars:x",
                "rnseason:2026", "week:course:sky")) {
            assertFalse(SecretShelf.scoreBoard(GameKind.TRIAL, tally), tally);
        }
        assertFalse(SecretShelf.scoreBoard(GameKind.CHANCE, "classic"));
        List<SecretShelf.Trophy> t = SecretShelf.trophies(List.of(
                new GamesDao.Mine("clubhouse", "club:2970", 7, 40), new GamesDao.Mine("clubhouse", "clubday:20789", 3, 90),
                new GamesDao.Mine("snake", "classic", 12, 2)), SecretShelf.games(g -> g.equals("snake")
                ? GameKind.CABINET : GameKind.TRIAL, g -> g));
        assertEquals(List.of("first:cabinet", "best:snake:classic"), t.stream().map(SecretShelf.Trophy::key).toList(),
                "the Snake best alone: no Clubhouse cup, and its 130 tally rows aren't 100 games played");
    }

    /**
     * The Floor Is Lava's boards are bests, one per mode: Lilah's first finished round of Little is a new trophy in
     * her secret room, and a later round with the same count is the same trophy, not another.
     */
    @Test
    void theFloorIsLavasBestsAreTrophies() {
        for (com.dierks.homecraft.games.lava.rules.LavaMode m : com.dierks.homecraft.games.lava.rules.LavaMode
                .values()) {
            assertTrue(SecretShelf.scoreBoard(GameKind.TRIAL, m.board()), m.board());
        }
        assertEquals(GameKind.TRIAL, com.dierks.homecraft.games.lava.FloorIsLava.SPEC.kind(), "the kind it is read by");
        SecretShelf.Games lava = SecretShelf.games(g -> g.equals("floor_is_lava") ? GameKind.TRIAL : null,
                g -> "The Floor Is Lava");
        List<SecretShelf.Trophy> t = SecretShelf.trophies(List.of(
                new GamesDao.Mine("floor_is_lava", "lava:little", 10, 1)), lava);
        assertEquals(List.of("best:floor_is_lava:lava:little"), t.stream().map(SecretShelf.Trophy::key).toList(),
                "one trophy: her best at Little");
        assertTrue(t.get(0).label().contains("10 stars (Little)"), t.get(0).label());
        assertEquals(t, SecretShelf.fresh(t, Set.of()), "and it bursts the first time she comes in");
        List<SecretShelf.Trophy> all = SecretShelf.trophies(List.of(
                new GamesDao.Mine("floor_is_lava", "lava:little", 10, 2),
                new GamesDao.Mine("floor_is_lava", "lava:group", 1, 1),
                new GamesDao.Mine("floor_is_lava", "lava:challenge", 4, 1)), lava);
        assertEquals(List.of("best:floor_is_lava:lava:little", "best:floor_is_lava:lava:group",
                "best:floor_is_lava:lava:challenge"), all.stream().map(SecretShelf.Trophy::key).toList());
        assertTrue(all.get(1).label().contains("1 star (Group)"), all.get(1).label());
        assertTrue(all.get(2).label().contains("4 rounds (Challenge)"), all.get(2).label());
    }

    /** Only what was drawn is marked seen (a trophy with no place on the shelf keeps its burst), added to the old list. */
    @Test
    void onlyDrawnTrophiesAreMarkedSeen() {
        SecretShelf.Trophy a = new SecretShelf.Trophy("a", 1, null, "", false);
        SecretShelf.Trophy b = new SecretShelf.Trophy("b", 1, null, "", false);
        assertEquals("old,a", SecretShelf.seenText(java.util.Set.of("old"), List.of(a)));
        assertEquals(java.util.Set.of("old", "a"), SecretShelf.seen(SecretShelf.seenText(java.util.Set.of("old"),
                List.of(a))));
        assertEquals(List.of(b), SecretShelf.fresh(List.of(a, b), SecretShelf.seen("old,a")), "b still bursts");
    }
}
