package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.MegaShow.Pick;
import com.dierks.homecraft.arcade.MegaShow.Spot;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A show from a Firework Remote set (0.38.2; the owner: "when I buy the 5 launcher show or the 4 launcher show or the 3
 * launcher show I can then simply tag/click on the launchers I want to use"): the first ones that can fire, in link
 * order; the skipped ones counted (and only up to the show's size) and named in one line; none that can is refused
 * with its reasons; the questions asked in a safe order (no chunk is ever loaded for one); the lines' articles ("an
 * Ultimate"), and 0.38.1's lines word for word with no theme.
 */
class MegaShowLinkedTest {

    private static Spot at(int x) {
        return new Spot("world", x, 64, 0);
    }

    private static Spot elsewhere(int x) {
        return new Spot("world_nether", x, 64, 0);
    }

    @Test
    void theFirstNUsableInLinkOrder() {
        List<Spot> linked = List.of(at(5), at(1), at(9), at(3), at(7));
        Pick three = MegaShow.pickLinked(linked, "world", s -> true, s -> true, s -> true, 3);
        assertEquals(List.of(at(5), at(1), at(9)), three.spots(), "#1, #2 and #3 as they were tapped, not by place");
        assertEquals(0, three.skipped());
        Pick all = MegaShow.pickLinked(linked, "world", s -> true, s -> true, s -> true, 10);
        assertEquals(linked, all.spots(), "an Ultimate with five linked fires from the five (it folds onto them)");
        assertEquals(List.of(), MegaShow.pickLinked(List.of(), "world", s -> true, s -> true, s -> true, 3).spots());
    }

    @Test
    void theSkippedOnesAreCountedOnlyUpToTheShowsSize() {
        Set<Spot> recorded = Set.of(at(1), at(2), at(4), at(5), at(6), elsewhere(3));
        Set<Spot> loaded = Set.of(at(1), at(4), at(5), at(6));
        Set<Spot> standing = Set.of(at(1), at(5), at(6));
        List<Spot> linked = List.of(elsewhere(3), at(1), at(2), at(9), at(4), at(5), at(6), at(7));
        Pick pick = MegaShow.pickLinked(linked, "world", recorded::contains, loaded::contains, standing::contains, 3);
        assertEquals(List.of(at(1), at(5), at(6)), pick.spots(), "the first three that can fire");
        assertEquals(1, pick.otherWorld(), "#1 is in the Nether");
        assertEquals(1, pick.unloaded(), "#3 isn't loaded");
        assertEquals(List.of(at(9), at(4)), pick.gone(), "#4 has no record, #5's block is air: both gone");
        assertEquals(4, pick.skipped());
        Pick one = MegaShow.pickLinked(linked, "world", recorded::contains, loaded::contains, standing::contains, 1);
        assertEquals(List.of(at(1)), one.spots());
        assertEquals(1, one.skipped(), "it stopped at one taken: the ones after it aren't counted");
    }

    @Test
    void theQuestionsAreAskedWorldRecordTickingPresentInThatOrder() {
        List<String> asked = new ArrayList<>();
        Spot s = at(1);
        MegaShow.pickLinked(List.of(s), "world", x -> asked.add("recorded"), x -> asked.add("ticking"),
                x -> asked.add("present"), 3);
        assertEquals(List.of("recorded", "ticking", "present"), asked, "the block only once its chunk is ticking");
        asked.clear();
        MegaShow.pickLinked(List.of(elsewhere(1)), "world", x -> asked.add("recorded"), x -> asked.add("ticking"),
                x -> asked.add("present"), 3);
        assertEquals(List.of(), asked, "another world: nothing asked of it");
        asked.clear();
        MegaShow.pickLinked(List.of(s), "world", x -> !asked.add("recorded"), x -> asked.add("ticking"),
                x -> asked.add("present"), 3);
        assertEquals(List.of("recorded"), asked, "no record: its chunk is never asked");
        asked.clear();
        MegaShow.pickLinked(List.of(s), "world", x -> asked.add("recorded"), x -> !asked.add("ticking"),
                x -> asked.add("present"), 3);
        assertEquals(List.of("recorded", "ticking"), asked, "not ticking: its block is never asked");
    }

    @Test
    void theLinesSayWhatWasSkippedAndWhy() {
        assertNull(MegaShow.skippedLine(Pick.of(List.of(at(1)))), "nothing skipped: no line");
        assertNull(MegaShow.skippedLine(null));
        assertEquals("&7Skipped 1 of your linked launchers: 1 not loaded (nobody is near it).",
                MegaShow.skippedLine(new Pick(List.of(at(1)), List.of(), 0, 1)));
        assertEquals("&7Skipped 4 of your linked launchers: 1 in another world, 1 gone and 2 not loaded (nobody is near"
                + " them).", MegaShow.skippedLine(new Pick(List.of(at(1)), List.of(at(2)), 1, 2)));
        assertEquals("&7Skipped 2 of your linked launchers: 2 gone.",
                MegaShow.skippedLine(new Pick(List.of(at(1)), List.of(at(2), at(3)), 0, 0)));
        assertEquals("None of your linked launchers can fire right now (2 not loaded (nobody is near them)). Go near"
                + " them, or change them with your Firework Remote!",
                MegaShow.noneLinkedLine(new Pick(List.of(), List.of(), 0, 2)));
        assertEquals("None of your linked launchers can fire right now (1 in another world). Go near them, or change"
                + " them with your Firework Remote!", MegaShow.noneLinkedLine(new Pick(List.of(), List.of(), 1, 0)));
        assertEquals("None of your linked launchers can fire right now. Go near them, or change them with your"
                + " Firework Remote!", MegaShow.noneLinkedLine(Pick.NONE));
    }

    @Test
    void theRefusalSaysTheLinkedLineWhenNoneCanFire() {
        String none = MegaShow.noneLinkedLine(new Pick(List.of(), List.of(), 0, 3));
        assertEquals(none, MegaShow.refusal(true, false, 0, none, false, 0), "none of the set can fire: why, and kept");
        assertEquals(MegaShow.NO_LAUNCHER, MegaShow.refusal(true, false, 0, false, 0), "no set: 0.38.1's line");
        assertEquals(PrizeItemListener.SHOW_NOT_HERE, MegaShow.refusal(false, false, 0, none, false, 0),
                "the games first, as ever");
        assertEquals(PrizeItemListener.SHOW_ON, MegaShow.refusal(true, true, 0, none, false, 0),
                "then the one long show");
        assertEquals(MegaShow.BUSY, MegaShow.refusal(true, false, 3, none, true, 0),
                "a linked launcher in a show refuses the whole show");
        assertEquals(MegaShow.SKY_FULL, MegaShow.refusal(true, false, 3, none, false, 4), "and the four-show cap");
        assertNull(MegaShow.refusal(true, false, 1, none, false, 3), "one that can fire is enough");
    }

    @Test
    void theStartAndServerLinesNameTheTierAndTheHoliday() {
        HolidayShows.Theme halloween = new HolidayShows.Theme("halloween", "Halloween", List.of(Color.ORANGE),
                List.of(Color.WHITE), FireworkEffect.Type.CREEPER);
        HolidayShows.Theme july = new HolidayShows.Theme("independence_day", "Independence Day", List.of(Color.RED),
                List.of(Color.WHITE), FireworkEffect.Type.BURST);
        assertEquals("&d✦ Someone started a Mega Firework Show!", MegaShow.announceLine("Someone"),
                "0.38.1's line word for word");
        assertEquals("&d✦ Someone started a Mega Firework Show!",
                MegaShow.announceLine("Someone", "Mega Firework Show", null), "and with no theme");
        assertEquals("&d✦ Someone started a Halloween Mega Firework Show!",
                MegaShow.announceLine("Someone", "Mega Firework Show", halloween), "the owner's example");
        assertEquals("&d✦ Someone started an Ultra Firework Show!",
                MegaShow.announceLine("Someone", "Ultra Firework Show", null));
        assertEquals("&d✦ Someone started an Ultimate Firework Show!",
                MegaShow.announceLine("Someone", "Ultimate Firework Show", null));
        assertEquals("&d✦ Someone started a Super Firework Show!",
                MegaShow.announceLine("Someone", "Super Firework Show", null));
        assertEquals("&d✦ Someone started an Independence Day Ultimate Firework Show!",
                MegaShow.announceLine("Someone", "Ultimate Firework Show", july));
        assertEquals("&d✦ Someone started a Halloween Ultimate Firework Show!",
                MegaShow.announceLine("Someone", "Ultimate Firework Show", halloween), "the theme's word decides");
        assertEquals("an", MegaShow.article("Ultra"));
        assertEquals("an", MegaShow.article("independence Day"));
        assertEquals("a", MegaShow.article("Halloween"));
        assertEquals("a", MegaShow.article(""));
        assertEquals("&d✦ Mega Firework Show! &7Look at the launchers!", MegaShow.startLine(3), "0.38.1's");
        assertEquals("&d✦ Halloween Ultra Firework Show! &7Look at the launchers!",
                MegaShow.startLine("Ultra Firework Show", halloween, 5));
        assertEquals("&d✦ Super Firework Show! &7Look at the launcher!",
                MegaShow.startLine("Super Firework Show", null, 1));
    }
}
