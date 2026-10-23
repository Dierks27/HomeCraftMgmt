package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.Icon;
import com.dierks.homecraft.config.PluginConfig.LimitPer;
import com.dierks.homecraft.config.PluginConfig.OnlyIf;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeExtra;
import com.dierks.homecraft.config.PluginConfig.PrizeLimit;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 0.38 shelf (SHOP-V2-SPEC §5.11, P2-lite, P3, P9; D11): a mob head or a cap shows with no texture while an
 * animal hat with none stays hidden; hunt gear is hidden with no wild hunt, and so out of the crate's pool; a
 * config trail this server can't show is hidden with one WARN; no hat ever comes out of a crate, so an owner's
 * crate still naming the hat row pays nothing from it; the month's period key and its words.
 */
class PrizeShelfTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 21);
    private static final PrizeService.Shelf HUNT = new PrizeService.Shelf(true, TODAY, Set.of(), true);
    private static final PrizeService.Shelf NO_HUNT = new PrizeService.Shelf(true, TODAY, Set.of(), false);

    private static Prize row(String id, PrizeType type, String particle, String texture, Material hatMaterial) {
        return new Prize(id, PrizeTab.LOOKS, "&f" + id, List.of(), 100, new Icon("", Material.SUGAR), type, true,
                null, 1, null, null, null, null, 0, 0, particle, 30, texture, List.of(), List.of(), null, null,
                List.of(), null, new PrizeExtra(hatMaterial, 0, null, OnlyIf.ANY, 0, null));
    }

    // ---- P3: mob heads and caps from config --------------------------------------------------------

    @Test
    void aMobHeadOrCapShowsWithNoTextureAndAnAnimalHatWithNoneIsHidden() {
        assertTrue(PrizeService.shelved(row("hat_creeper", PrizeType.HAT, null, "", Material.CREEPER_HEAD), HUNT),
                "the game's own head needs no picture");
        assertTrue(PrizeService.shelved(row("hat_dragon", PrizeType.HAT, null, "", Material.DRAGON_HEAD), HUNT));
        assertTrue(PrizeService.shelved(row("cap_pink", PrizeType.HAT, null, "", Material.LEATHER_HELMET), HUNT),
                "nor a cap");
        assertFalse(PrizeService.shelved(row("hat_bunny", PrizeType.HAT, null, "", null), HUNT),
                "an animal hat with no texture is a Steve head: hidden (D10)");
        assertTrue(PrizeService.shelved(row("hat_bunny", PrizeType.HAT, null, "eyJ0", null), HUNT),
                "with the owner's picture it shows");
    }

    @Test
    void aDisabledRowNeverShows() {
        Prize off = new Prize("off", PrizeTab.LOOKS, "&foff", List.of(), 100, new Icon("", Material.SUGAR),
                PrizeType.HAT, false, null, 1, null, null, null, null, 0, 0, null, 30, "", List.of(), List.of(), null,
                null, List.of(), null, new PrizeExtra(Material.CREEPER_HEAD, 0, null, OnlyIf.ANY, 0, null));
        assertFalse(PrizeService.shelved(off, HUNT));
    }

    // ---- P9: hunt gear without a wild hunt --------------------------------------------------------

    @Test
    void huntGearIsHiddenWithNoWildHuntAndOutOfTheCratesPool() {
        for (PrizeType t : List.of(PrizeType.RADAR, PrizeType.LURE)) {
            Prize gear = row("mini_" + t.name().toLowerCase(), t, null, "", null);
            assertTrue(PrizeService.shelved(gear, HUNT), t + " with a wild hunt");
            assertFalse(PrizeService.shelved(gear, NO_HUNT), t + " with no NATURAL_SPAWN source");
        }
        Prize radar = row("mini_radar", PrizeType.RADAR, null, "", null);
        assertTrue(PrizeService.grantable(radar, HUNT), "a crate gives the radar while there is a hunt");
        assertFalse(PrizeService.grantable(radar, NO_HUNT), "and not without one: the crate's pool drops it");
        assertTrue(PrizeService.shelved(row("speed", PrizeType.BOOST, null, "", null), NO_HUNT),
                "other rows don't care");
    }

    // ---- P2-lite: every trail row's particle -------------------------------------------------------

    @Test
    void aConfigTrailThisServerCantShowIsHiddenWithOneWarn() {
        Prize dust = row("trail_dust", PrizeType.TRAIL, "DUST", "", null); // DUST needs its colour: data
        Prize nope = row("trail_nope", PrizeType.TRAIL, "NOT_A_PARTICLE", "", null);
        Prize hearts = row("trail_hearts", PrizeType.TRAIL, "HEART", "", null);
        List<String> warns = new ArrayList<>();
        Set<String> notHere = PrizeService.trailsNotHere(List.of(dust, nope, hearts), TrailService::particleUsable,
                warns::add);
        assertEquals(Set.of("trail_dust", "trail_nope"), notHere);
        assertEquals(2, warns.size(), "one WARN each: " + warns);
        assertTrue(warns.get(0).contains("trail_dust") && warns.get(0).contains("hidden"), warns.get(0));
        PrizeService.Shelf s = new PrizeService.Shelf(true, TODAY, notHere, true);
        assertFalse(PrizeService.shelved(dust, s));
        assertFalse(PrizeService.grantable(dust, s), "nor from a crate");
        assertTrue(PrizeService.shelved(hearts, s));
    }

    @Test
    void everyShippedTrailCanShow() {
        for (String particle : List.of("HEART", "NOTE", "FIREWORK", "SNOWFLAKE", "END_ROD", "HAPPY_VILLAGER", "GLOW",
                "ENCHANT")) {
            assertTrue(TrailService.particleUsable(particle), particle + " is a data-free particle in paper-api 26.2");
        }
    }

    // ---- D11: hats are bought, never won ----------------------------------------------------------

    @Test
    void noHatIsCrateableSoAnOwnersCrateWithTheHatRowPaysNothingFromIt() {
        Prize textured = row("hat_bunny", PrizeType.HAT, null, "eyJ0", null);
        assertTrue(PrizeService.shelved(textured, HUNT), "the hat is on the shelf (the owner pasted its picture)");
        assertFalse(PrizeService.crateable(textured));
        assertFalse(PrizeService.grantable(textured, HUNT),
                "so the crate's hat reward has nothing it can give, and is not droppable");
        assertFalse(PrizeService.grantable(row("hat_creeper", PrizeType.HAT, null, "", Material.CREEPER_HEAD), HUNT));
    }

    /**
     * The crate's pools and its published value read {@code grantable} and nothing else (no code change there in
     * 0.38), so hiding hunt gear, a trail this server can't show and every hat takes them out of a crate and its
     * value by itself. Read from the source.
     */
    @Test
    void theCratesPoolAndValueFollowGrantable() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src", "main", "java", "com", "dierks",
                "homecraft", "arcade", "ArcadeService.java"), java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");
        int from = src.indexOf("private List<Prize> rewardPrizes(CrateReward r) {");
        int to = src.indexOf("private List<CrateReward> eligiblePool(Crate crate) {");
        assertTrue(from > 0 && to > from, "fixture: rewardPrizes");
        String pool = src.substring(from, to);
        assertEquals(2, pool.split("plugin\\.prizes\\(\\)\\.grantable\\(p\\)", -1).length - 1,
                "the any-trail pool and the named rows both ask grantable: " + pool);
        assertTrue(src.contains("case PRIZE, TRAIL -> !rewardPrizes(r).isEmpty();"), "droppable reads the pool");
        int value = src.indexOf("private double counterValue(CrateReward r) {");
        assertTrue(value > 0 && src.indexOf("List<Prize> ps = rewardPrizes(r);", value) > value,
                "and so does the published value");
    }

    // ---- MONTH --------------------------------------------------------------------------------------

    @Test
    void theMonthsPeriodKeyIsTheLocalCalendarMonthAndRollsOverIntoJanuary() {
        assertEquals("m2026-11", PrizeService.monthKey(LocalDate.of(2026, 11, 1)));
        assertEquals("m2026-11", PrizeService.monthKey(LocalDate.of(2026, 11, 30)));
        assertEquals("m2026-12", PrizeService.monthKey(LocalDate.of(2026, 12, 31)));
        assertEquals("m2027-01", PrizeService.monthKey(LocalDate.of(2027, 1, 1)), "December → January");
    }

    @Test
    void aMonthsLimitSaysThisMonth() {
        assertEquals("1 left this month", PrizeService.limitLine(1, LimitPer.MONTH));
        assertEquals("0 left this month", PrizeService.limitLine(0, LimitPer.MONTH));
        assertEquals("5 left today", PrizeService.limitLine(5, LimitPer.DAY));
        assertEquals("1 left this week", PrizeService.limitLine(1, LimitPer.WEEK));
        assertNull(PrizeService.limitLine(1, LimitPer.LIFETIME), "a lifetime limit is the tile's \"You have this!\"");
        assertEquals("this month", PrizeService.periodWords(LimitPer.MONTH));
        assertEquals("for good", PrizeService.periodWords(LimitPer.LIFETIME));
        assertEquals(new PrizeLimit(LimitPer.MONTH, 1), new PrizeLimit(LimitPer.MONTH, 1));
    }
}
