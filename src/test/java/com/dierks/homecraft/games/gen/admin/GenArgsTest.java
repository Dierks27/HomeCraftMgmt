package com.dierks.homecraft.games.gen.admin;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The words of {@code history}, {@code recall} and {@code keep} (GEN-SPEC-KEEP §2-§4, §8): an
 * edition named by its code, number, key, {@code last}, {@code current}, a date or a seed; a code
 * anywhere an edition is accepted (the Classics slot inferred from it); durations; names in quotes;
 * the fresh-board flag; pages.
 */
class GenArgsTest {

    private static List<String> words(String line) {
        return line.isBlank() ? List.of() : List.of(line.split(" "));
    }

    @Test
    void anEditionIsNamedManyWays() {
        assertEquals(GenArgs.How.CODE, GenArgs.which("hard-40").how(), "a code, any case");
        assertEquals("HARD-40", GenArgs.which("hard-40").text(), "written back in capitals");
        assertEquals("fresh_parkour_hard", GenArgs.which("HARD-40").slot(), "a code says its slot");
        assertEquals(GenArgs.How.KEY, GenArgs.which("7:40").how(), "an edition key");
        assertEquals(GenArgs.How.KEY, GenArgs.which("7:40r1").how(), "with a reroll");
        assertEquals(40, GenArgs.which("40").n(), "a number is the code's number");
        assertEquals(GenArgs.How.LAST, GenArgs.which("LAST").how(), "last");
        assertEquals(GenArgs.How.CURRENT, GenArgs.which("current").how(), "current");
        assertEquals(LocalDate.of(2026, 10, 5), GenArgs.which("2026-10-05").date(), "a date alone");
        GenArgs.Which seed = GenArgs.which("seed:3F2A9C01B7DE");
        assertEquals(GenArgs.How.SEED, seed.how(), "a seed, as the website shows it");
        assertEquals("3f2a9c01b7de", seed.text(), "in lower case");
        assertTrue(seed.remade(), "a seed means made again from it");
        assertNull(GenArgs.which("seed:3f2a"), "too few digits to find one course");
        assertNull(GenArgs.which("seed:xyz12345"), "not hex");
        assertNull(GenArgs.which("dragon_run"), "an id is not an edition");
        assertNull(GenArgs.which("2026-13-45"), "nor a date that isn't one");
        assertNull(GenArgs.which("0"), "nor edition 0");
    }

    @Test
    void recallTakesACodeAloneOrAClassicsSlotACourseAndWhichOne() {
        GenArgs.Recall a = GenArgs.recall(words("HARD-40"));
        assertNull(a.error(), "a code alone: " + a.error());
        assertNull(a.classic(), "the Classics slot follows the course's kind");
        assertNull(a.slot(), "the code says the course");
        assertEquals("HARD-40", a.which().text(), "its code");
        assertEquals(GenArgs.DAYS_DEFAULT, a.days(), "for classics.days");
        GenArgs.Recall b = GenArgs.recall(words("parkour fresh_parkour_hard last 3"));
        assertNull(b.error(), b.error());
        assertEquals("fresh_classic_parkour", b.classic(), "the kind names the Classics slot");
        assertEquals("fresh_parkour_hard", b.slot(), "the course");
        assertEquals(GenArgs.How.LAST, b.which().how(), "the one before the current");
        assertEquals(3, b.days(), "for 3 days");
        GenArgs.Recall c = GenArgs.recall(words("fresh_classic_golf fresh_tiny_golf date 2026-10-05 forever"));
        assertNull(c.error(), c.error());
        assertEquals(LocalDate.of(2026, 10, 5), c.which().date(), "the one up that day");
        assertEquals(GenArgs.FOREVER, c.days(), "until replaced or unrecalled");
        GenArgs.Recall d = GenArgs.recall(words("rings RINGS-7 14"));
        assertNull(d.error(), "a kind and a code: " + d.error());
        assertEquals("fresh_classic_rings", d.classic(), "rings");
        GenArgs.Recall e = GenArgs.recall(words("parkour fresh_parkour_hard seed:3f2a9c01b7de"));
        assertTrue(e.which().remade(), "seed: re-makes it");
        for (String bad : new String[]{"", "parkour", "boat fresh_boat last", "parkour nowhere last",
                "parkour fresh_parkour_hard", "parkour fresh_parkour_hard yesterday", "HARD-40 400", "HARD-40 7 extra",
                "parkour fresh_parkour_hard current"}) {
            assertNotNull(GenArgs.recall(words(bad)).error(), "'" + bad + "' is refused with a reason");
        }
    }

    @Test
    void keepTakesACodeOrACourseAndWhichOneAnIdANameAndTheFreshBoardFlag() {
        GenArgs.Keep a = GenArgs.keep(words("HARD-40 dragon_run \"Dragon Run\""));
        assertNull(a.error(), a.error());
        assertEquals("fresh_parkour_hard", a.slot(), "the code says the course");
        assertEquals("HARD-40", a.which().text(), "and which");
        assertEquals("dragon_run", a.id(), "the new id");
        assertEquals("Dragon Run", a.name(), "the name, quotes off");
        assertFalse(a.freshBoard(), "records are copied by default");
        GenArgs.Keep b = GenArgs.keep(words("fresh_rings sky_loop --fresh-board"));
        assertNull(b.error(), b.error());
        assertEquals(GenArgs.How.CURRENT, b.which().how(), "no edition: the current one");
        assertEquals("sky_loop", b.id(), "the id");
        assertNull(b.name(), "no name: the id's own");
        assertTrue(b.freshBoard(), "an empty board");
        GenArgs.Keep c = GenArgs.keep(words("fresh_parkour last cliff_hop Cliff Hop"));
        assertEquals(GenArgs.How.LAST, c.which().how(), "an edition before the id");
        assertEquals("Cliff Hop", c.name(), "a name in words");
        GenArgs.Keep d = GenArgs.keep(words("fresh_parkour 12 Cliff_Hop"));
        assertEquals(12, d.which().n(), "a number");
        assertEquals("cliff_hop", d.id(), "ids are lower case");
        assertNotNull(GenArgs.keep(words("")).error(), "nothing");
        assertNotNull(GenArgs.keep(words("HARD-40")).error(), "no id");
        assertNotNull(GenArgs.keep(words("river_run x")).error(), "not a course or a code");
    }

    @Test
    void keepWithAnEditionButNoIdIsRefusedNeverKeepingTheCurrentOneUnderThatWord() {
        for (String which : List.of("last", "current", "live", "12", "date", "2026-10-05", "7:40")) {
            GenArgs.Keep k = GenArgs.keep(words("fresh_parkour_hard " + which));
            assertNotNull(k.error(), "'keep fresh_parkour_hard " + which + "' names an edition and no id: refused,"
                    + " not the current course kept as '" + which + "'");
            assertTrue(k.error().contains("Give the new course an id too"), "and it says what is missing: "
                    + k.error());
        }
        GenArgs.Keep id = GenArgs.keep(words("fresh_parkour_hard dragon_run"));
        assertNull(id.error(), "an id alone still keeps the current course: " + id.error());
        assertEquals(GenArgs.How.CURRENT, id.which().how(), "the current one");
        assertEquals("dragon_run", id.id(), "as that id");
        GenArgs.Keep both = GenArgs.keep(words("fresh_parkour_hard last dragon_run"));
        assertEquals(GenArgs.How.LAST, both.which().how(), "an edition and an id: that edition");
        assertEquals("dragon_run", both.id(), "as that id");
    }

    @Test
    void historyTakesACourseOrAllAPageOrOneCourse() {
        GenArgs.History a = GenArgs.history(words("fresh_parkour_hard 2"));
        assertEquals("fresh_parkour_hard", a.slot(), "one course");
        assertEquals(2, a.page(), "page 2");
        assertNull(a.detail(), "the list");
        GenArgs.History b = GenArgs.history(words("all"));
        assertNull(b.slot(), "every course");
        assertEquals(1, b.page(), "page 1");
        GenArgs.History c = GenArgs.history(words("HARD-40"));
        assertEquals("HARD-40", c.detail().text(), "a code alone is one course in full");
        assertEquals("fresh_parkour_hard", c.slot(), "of its slot");
        GenArgs.History d = GenArgs.history(words("fresh_parkour_hard last"));
        assertEquals(GenArgs.How.LAST, d.detail().how(), "one course by last");
        GenArgs.History e = GenArgs.history(words("fresh_parkour_hard date 2026-10-05"));
        assertEquals(LocalDate.of(2026, 10, 5), e.detail().date(), "one course by date");
        GenArgs.History f = GenArgs.history(words("fresh_parkour_hard HARD-40"));
        assertEquals("HARD-40", f.detail().text(), "one course by code");
        assertNull(GenArgs.history(words("")).error(), "no words: everything, page 1");
        assertNotNull(GenArgs.history(words("nowhere")).error(), "an unknown course");
        assertNotNull(GenArgs.history(words("all zero")).error(), "a page that isn't a number");
    }
}
