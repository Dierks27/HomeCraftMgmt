package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.chance.sportsbook.ScoreScreens;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 0.60.0: the owner's own score TVs ({@link ClubTvs}) kept and read back, and what a TV's text says. */
class ClubTvsTest {

    @Test
    void theOwnersTvsAreKeptAsTextAndReadBack() {
        List<ClubTvs.Tv> tvs = List.of(new ClubTvs.Tv("games", 6090.52, 171.5, 8590.5, 90f, 3),
                new ClubTvs.Tv("games", 6100.5, 172.5, 8550.98, 0f, 2));
        String kept = ClubTvs.text(tvs);
        assertEquals("games;6090.52;171.50;8590.50;90.00;3\ngames;6100.50;172.50;8550.98;0.00;2", kept);
        assertEquals(tvs, ClubTvs.parse(kept));
        assertEquals(List.of(), ClubTvs.parse(null));
        assertEquals(1, ClubTvs.parse("junk\n" + tvs.get(0).text() + "\nworld;1;2").size(), "junk is left out");
        assertEquals(ClubTvs.SIZE_MAX, ClubTvs.parse("games;1;2;3;0;99").get(0).size(), "a size is kept in range");
        assertNull(ClubTvs.Tv.of("games;x;2;3;0;2"));
        List<ClubTvs.Tv> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(new ClubTvs.Tv("games", i, 0, 0, 0f, 2));
        }
        assertEquals(ClubTvs.MAX, ClubTvs.parse(ClubTvs.text(many)).size(), "no more than " + ClubTvs.MAX);
    }

    @Test
    void theNearestTvIsFoundOnlyInItsWorldAndWithinReach() {
        List<ClubTvs.Tv> tvs = List.of(new ClubTvs.Tv("games", 0, 0, 0, 0f, 2),
                new ClubTvs.Tv("games", 10, 0, 0, 0f, 2), new ClubTvs.Tv("world", 1, 0, 0, 0f, 2));
        assertEquals(1, ClubTvs.nearest(tvs, "games", 7, 0, 0));
        assertEquals(0, ClubTvs.nearest(tvs, "GAMES", 1, 0, 0), "a world name in any case");
        assertEquals(-1, ClubTvs.nearest(tvs, "games", 40, 0, 0), "none within " + ClubTvs.NEAR);
        assertEquals(2, ClubTvs.nearest(tvs, "world", 1, 0, 0));
    }

    @Test
    void aScreenStandsJustOffTheFaceLookedAtFacingOut() {
        assertArrayEquals(new double[]{10.5, 64.5, 21.02, 0}, ClubTvs.onFace(10, 64, 20, 0, 1), 1e-9,
                "the south face: just south of it, facing south");
        assertArrayEquals(new double[]{-0.02, 64.5, 20.5, 90}, ClubTvs.onFace(0, 64, 20, -1, 0), 1e-9,
                "the west face: facing west");
        assertEquals(-90.0, ClubTvs.onFace(0, 64, 20, 1, 0)[3]);
        assertEquals(180.0, ClubTvs.onFace(0, 64, 20, 0, -1)[3]);
        List<String> list = ClubTvs.lines(List.of());
        assertTrue(list.get(1).contains("tv add"), list.toString());
    }

    @Test
    void aTvsTextIsTheGameBigWithTeamNamesAsTheyAre() {
        String plain = PlainTextComponentSerializer.plainText().serialize(SportsBar.component(List.of(
                new ScoreScreens.Line("NCAAF · Q2 3:00", ScoreScreens.Kind.HEAD),
                new ScoreScreens.Line("Texas A&M  14", ScoreScreens.Kind.LEAD),
                new ScoreScreens.Line("LSU  7", ScoreScreens.Kind.TEAM),
                new ScoreScreens.Line("1 of 2", ScoreScreens.Kind.NOTE))));
        assertEquals("NCAAF · Q2 3:00\nTexas A&M  14\nLSU  7\n1 of 2", plain, "never read as colour codes");
        assertEquals(1.0, SportsBar.textHigh(4, 1f), 1e-9);
        assertArrayEquals(new double[]{-0.02, 0}, SportsBar.out(90f), 1e-9, "a hair out of a west face");
    }
}
