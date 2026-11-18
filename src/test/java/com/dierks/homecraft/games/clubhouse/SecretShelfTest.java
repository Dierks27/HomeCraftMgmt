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
}
