package com.dierks.homecraft;

import com.dierks.homecraft.games.clubhouse.ClubBoardText;
import com.dierks.homecraft.gui.games.daily.DailyText;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the README and the games guide say of Sky Rings v3 and the Long Drop, against what the game does: a rainbow
 * ring is no climb (a glide or a level leg, and a level leg can take a rocket), about half the Long Drop's plates have
 * false holes, Now playing counts rings on Sky Rings, the Dropper's tile shows its star line as the tile writes it,
 * and the Dropper's first clear is paid once ever per course, so an account that cleared it before is not paid again.
 */
class SkyLoopLongDropDocsTest {

    private static String flat(String file) throws Exception {
        return Files.readString(Path.of(file), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }

    /** Review copy-docs F2: a level leg's ring is rainbow too, and it is "fast or rocket". */
    @Test
    void aRainbowRingIsAGlideOrALevelLeg() throws Exception {
        String readme = flat("README.md");
        String guide = flat("docs/games-guide.md");
        assertFalse(readme.contains("a rainbow ring is a glide)"), "README");
        assertTrue(readme.contains("a rainbow ring is a glide or a level leg, fast or one rocket"), "README");
        assertFalse(guide.contains("A rainbow ring is a glide and never needs one"), "guide");
        assertTrue(guide.contains("Rainbow rings are glides or level legs"), "guide");
        assertTrue(guide.contains("A level leg (\"level: fast or rocket\") is free if you arrive fast; slow, it takes"
                + " one."), "guide");
    }

    /** Reviews dropper LD-1 and copy-docs F3: about 3.5 of 8 plates draw false holes, some only one. */
    @Test
    void aboutHalfTheLongDropsPlatesHaveFalseHoles() throws Exception {
        String readme = flat("README.md");
        String guide = flat("docs/games-guide.md");
        assertFalse(readme.contains("most of them with two false holes"), "README");
        assertTrue(readme.contains("about half of them with one or two false holes beside the real one"), "README");
        assertFalse(guide.contains("most with false holes"), "guide");
        assertTrue(guide.contains("about half with false holes beside the real one"), "guide");
    }

    /** Review copy-docs F8: Now playing says "ring 3" on an elytra course, not "checkpoint 3". */
    @Test
    void nowPlayingCountsRingsOnSkyRings() throws Exception {
        String example = "\"Sam - Sky Rings - " + ClubBoardText.ring(3) + " - 0:41\"";
        for (String file : new String[]{"README.md", "docs/games-guide.md"}) {
            String text = flat(file);
            assertFalse(text.contains("Sky Rings - checkpoint"), file);
            assertTrue(text.contains(example), file);
        }
    }

    /**
     * Reviews copy-docs F6 and dropper LD-3: the Dropper tile's star line is quoted as the tile writes it, and the
     * first clear's 25 is promised only to an account that never cleared the Dropper (once ever per course).
     */
    @Test
    void theDropperStepQuotesTheTileAndTheOnceEverFirstClear() throws Exception {
        String readme = flat("README.md");
        String tile = DailyText.starTimes(24_000, 35_000).replace("&7", "");
        assertFalse(readme.contains("3 stars 24 s, 2 stars 35 s"), "README");
        assertTrue(readme.contains("The Dropper tile reads Hard, \"" + tile + "\""), tile);
        assertFalse(readme.contains("the first clear pays 25 once."), "README");
        assertTrue(readme.contains("the first clear pays 25 once, to an account that has never cleared the Dropper"
                + " (one paid before 0.38.5 is not paid again)"), "README");
    }
}
