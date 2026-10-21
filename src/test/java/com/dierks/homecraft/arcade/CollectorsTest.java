package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.CapLook;
import com.dierks.homecraft.config.PluginConfig.Collector;
import com.dierks.homecraft.config.PluginConfig.Icon;
import com.dierks.homecraft.config.PluginConfig.LimitPer;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeLimit;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The collectors' catalogue as it ships (HALLOWEEN-SPEC §2.1, D2-D6; SHOP-V2-SPEC §5.7): ids, names and lore
 * stamped with the year and the season, the owner's prices, one each for good, an append-only list of shipped
 * ids, and Christmas 2026 (the Santa Cap and Twinkle).
 */
class CollectorsTest {

    private static Prize row(String id) {
        for (Prize p : Collectors.ROWS) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        throw new AssertionError("no collector " + id);
    }

    @Test
    void shippedIdsAreAppendOnly() {
        assertEquals(List.of("cap_pumpkin_2026", "trail_leaves_2026", "cap_santa_2026", "trail_twinkle_2026"),
                Collectors.SHIPPED_IDS, "a shipped collector id is never removed or renamed: owned trails would vanish");
        assertEquals(Collectors.SHIPPED_IDS, Collectors.ROWS.stream().map(Prize::id).toList(),
                "every shipped collector has its row, in the order shipped (the counter's order)");
        for (Prize p : Collectors.ROWS) {
            assertTrue(Collectors.SHIPPED_IDS.contains(p.id()), p.id() + " is in SHIPPED_IDS");
        }
    }

    @Test
    void everyRowIsStampedWithItsYear() {
        Set<String> ids = new HashSet<>();
        for (Prize p : Collectors.ROWS) {
            assertNotNull(p.collector(), p.id());
            String year = String.valueOf(p.collector().year());
            assertTrue(ids.add(p.id().toLowerCase(Locale.ROOT)), "ids are unique: " + p.id());
            assertTrue(p.id().endsWith("_" + year), "the id ends in its year: " + p.id());
            assertTrue(p.display().contains(year), "the name carries the year: " + p.display());
            assertEquals(year, String.valueOf(p.collector().from().getYear()), "the season is in its year");
        }
    }

    @Test
    void theCapLoreCarriesTheYearAndTheGiver() {
        Prize cap = row("cap_pumpkin_2026");
        assertEquals(List.of("&7A pumpkin cap for Halloween!", "&6Halloween 2026 collector", "&aYours for good!",
                "&7Wear it! Right-click to put it on.", "&8Arcade prize"), PrizeItems.collectorLore(cap, null));
        assertEquals(List.of("&7A pumpkin cap for Halloween!", "&6Halloween 2026 collector", "&aYours for good!",
                        "&7A gift from &fA Parent", "&7Wear it! Right-click to put it on.", "&8Arcade prize"),
                PrizeItems.collectorLore(cap, "A Parent"));
        assertEquals(PrizeItems.collectorLore(cap, null), PrizeItems.collectorLore(cap, " "), "a blank giver is none");
    }

    /** D7 (SHOULD): the serial number goes after the giver and before how to wear it; 0 is none. */
    @Test
    void theCapLoreCarriesItsSerialNumber() {
        Prize cap = row("cap_pumpkin_2026");
        assertEquals(List.of("&7A pumpkin cap for Halloween!", "&6Halloween 2026 collector", "&aYours for good!",
                        "&7A gift from &fA Parent", "&7No. 3", "&7Wear it! Right-click to put it on.", "&8Arcade prize"),
                PrizeItems.collectorLore(cap, "A Parent", 3));
        assertEquals(List.of("&7A pumpkin cap for Halloween!", "&6Halloween 2026 collector", "&aYours for good!",
                        "&7No. 1", "&7Wear it! Right-click to put it on.", "&8Arcade prize"),
                PrizeItems.collectorLore(cap, null, 1));
        assertEquals(PrizeItems.collectorLore(cap, null), PrizeItems.collectorLore(cap, null, 0),
                "no number when it couldn't be read");
        assertEquals("collector:cap_pumpkin_2026", PrizeService.serialKey("cap_pumpkin_2026"),
                "its own counter in arcade_state, apart from the trophy's");
    }

    @Test
    void thePumpkinCap() {
        Prize cap = row("cap_pumpkin_2026");
        assertEquals("&6Pumpkin Cap 2026", cap.display());
        assertEquals(250, cap.costTokens());
        assertEquals(PrizeTab.COSMETICS, cap.tab());
        assertEquals(PrizeType.HAT, cap.type());
        assertEquals(LimitPer.LIFETIME, cap.limit().per());
        assertEquals(1, cap.limit().count());
        assertTrue(cap.enabled());
        assertTrue(cap.collector().giftable());
        assertEquals(Material.LEATHER_HELMET, cap.icon().material());
        assertNotNull(cap.collector().look());
        assertEquals(0xF9801D, cap.collector().look().rgb());
        assertEquals("wild", cap.collector().look().trimPattern());
        assertEquals("emerald", cap.collector().look().trimMaterial());
        assertTrue(cap.costs().isEmpty(), "one price, never a ladder");
    }

    @Test
    void theAutumnLeavesTrail() {
        Prize trail = row("trail_leaves_2026");
        assertEquals("&6Autumn Leaves 2026", trail.display());
        assertEquals(400, trail.costTokens());
        assertEquals(PrizeTab.COSMETICS, trail.tab());
        assertEquals(PrizeType.TRAIL, trail.type());
        assertEquals(LimitPer.LIFETIME, trail.limit().per());
        assertEquals(1, trail.limit().count());
        assertTrue(trail.enabled());
        assertTrue(trail.collector().giftable());
        assertEquals("ORANGE_POPLAR_LEAVES", trail.particle());
        // the orange poplar leaves block where the API has it (26.3); the 26.2 API the plugin compiles against
        // has none, and there a plain leaves block stands in
        Material poplar = Material.matchMaterial("ORANGE_POPLAR_LEAVES");
        assertEquals(poplar != null ? poplar : Material.OAK_LEAVES, trail.icon().material());
        assertEquals(36500, trail.days(), "kept for good: 100 years, never a Long.MAX_VALUE that overflows");
        assertNull(trail.collector().look());
    }

    /** {@code p} with another particle: everything else, its id and season included, kept. */
    private static Prize withParticle(Prize p, String particle) {
        return new Prize(p.id(), p.tab(), p.display(), p.description(), p.costTokens(), p.icon(), p.type(), p.enabled(),
                p.limit(), p.amount(), p.color(), p.blockKey(), p.packId(), p.effect(), p.amplifier(), p.minutes(),
                particle, p.days(), p.texture(), p.commands(), p.requiresPlugins(), p.ownedIfPermission(),
                p.requiresPrize(), p.costs(), p.collector());
    }

    /** {@code p} as a plain row: no collector part, everything else kept. */
    private static Prize uncollected(Prize p) {
        return new Prize(p.id(), p.tab(), p.display(), p.description(), p.costTokens(), p.icon(), p.type(), p.enabled(),
                p.limit(), p.amount(), p.color(), p.blockKey(), p.packId(), p.effect(), p.amplifier(), p.minutes(),
                p.particle(), p.days(), p.texture(), p.commands(), p.requiresPlugins(), p.ownedIfPermission(),
                p.requiresPrize(), p.costs(), null);
    }

    /**
     * Final audit F7: a collector trail whose particle this server doesn't have (the Autumn Leaves trail's
     * ORANGE_POPLAR_LEAVES is new in the 26.3 API) is not sold or given. It is hidden, so a buy, a present and
     * "Give a present" refuse it, and the give-back says why. Where the server has it (26.3), nothing changes.
     */
    @Test
    void aTrailThisServerCantShowIsNotSoldOrGiven() {
        Prize leaves = row("trail_leaves_2026");
        Prize cap = row("cap_pumpkin_2026");
        Prize unknown = withParticle(leaves, "NO_SUCH_PARTICLE_2099");
        assertFalse(TrailService.particleUsable("NO_SUCH_PARTICLE_2099"), "not a particle on this server");
        assertFalse(TrailService.particleUsable(null), "no particle at all");
        assertTrue(TrailService.particleUsable(" flame "), "a particle needing no data, any case, trimmed");
        assertFalse(TrailService.particleUsable("DUST"), "one needing data (DustOptions) shows nothing either");
        Set<String> notHere = PrizeService.trailsNotHere(List.of(cap, unknown), TrailService::particleUsable);
        assertEquals(Set.of("trail_leaves_2026"), notHere, "the trail, never the cap");
        LocalDate halloween = LocalDate.of(2026, 10, 31);
        assertTrue(PrizeService.onSale(unknown, true, halloween), "fixture: in season and on sale");
        assertFalse(PrizeService.offered(unknown, true, halloween, notHere),
                "hidden: no tile, no buy, no present, so nothing is charged for a trail that never draws");
        assertTrue(PrizeService.offered(cap, true, halloween, notHere), "the cap is still sold and given");
        assertEquals("That trail can't show on this server yet.", PrizeService.restoreRefusal(unknown, notHere),
                "/hcm give prize refuses it and says why");
        assertNull(PrizeService.restoreRefusal(cap, notHere), "and gives the cap back");
        assertEquals("That isn't a collector.", PrizeService.restoreRefusal(uncollected(cap), notHere),
                "a plain row is never given back this way");
        assertEquals("That isn't a collector.", PrizeService.restoreRefusal(null, notHere), "nor no row at all");
        Set<String> all = PrizeService.trailsNotHere(Collectors.ROWS, name -> true);
        assertEquals(Set.of(), all, "a server with every particle (26.3) holds nothing back");
        assertTrue(PrizeService.offered(leaves, true, halloween, all), "there it is sold and given as before");
        assertFalse(PrizeService.offered(leaves, false, halloween, all), "and the season gate still applies");
        assertNull(PrizeService.restoreRefusal(leaves, all), "and given back");
        // the shipped trail on this API: 26.2's (the test classpath) has no ORANGE_POPLAR_LEAVES, 26.3's has it
        assertEquals(!TrailService.particleUsable("ORANGE_POPLAR_LEAVES"),
                PrizeService.trailsNotHere(Collectors.ROWS, TrailService::particleUsable).contains("trail_leaves_2026"),
                "the Autumn Leaves trail is held back exactly where its particle can't show");
    }

    /**
     * The call sites of {@link #aTrailThisServerCantShowIsNotSoldOrGiven}'s rules, read from the source (a
     * PrizeService can't be built without a server): {@code visible()} answers a collector with {@code offered}
     * and the trails this server can't show, and {@code restore()} refuses first with {@code restoreRefusal}.
     */
    @Test
    void theShopAndTheGiveBackUseThoseRules() throws IOException {
        String src = Files.readString(Path.of("src", "main", "java", "com", "dierks", "homecraft", "arcade",
                "PrizeService.java"), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        String visible = body(src, "public boolean visible(Prize p) {");
        assertTrue(visible.contains("if (p.collector() != null) { return offered(p, plugin.config().arcade()"
                + ".collectorsOnSale(), plugin.clock().today(), trailsNotHere); }"),
                "visible(): a collector is shown, sold and given only where offered() says, trailsNotHere and all");
        String restore = body(src, "public Outcome restore(Player target, Prize p, String fromName) {");
        assertTrue(restore.startsWith(" String refused = restoreRefusal(p, trailsNotHere); if (refused != null) "
                + "{ return Outcome.fail(refused); }"), "restore(): restoreRefusal first, and its answer returned");
    }

    /** The text of the method that {@code head} opens, up to its closing brace (whitespace already one space). */
    private static String body(String src, String head) {
        int from = src.indexOf(head);
        assertTrue(from >= 0, "fixture: " + head);
        int at = from + head.length();
        int depth = 1;
        for (int i = at; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return src.substring(at, i);
            }
        }
        throw new AssertionError("fixture: no end to " + head);
    }

    @Test
    void theSantaCap() {
        Prize cap = row("cap_santa_2026");
        assertEquals("&cSanta Cap 2026", cap.display());
        assertEquals(List.of("A cozy Santa cap for Christmas!"), cap.description());
        assertEquals(250, cap.costTokens());
        assertEquals(PrizeTab.COSMETICS, cap.tab(), "no Looks tab before 0.38");
        assertEquals(PrizeType.HAT, cap.type());
        assertEquals(LimitPer.LIFETIME, cap.limit().per());
        assertEquals(1, cap.limit().count());
        assertTrue(cap.enabled());
        assertTrue(cap.collector().giftable());
        assertEquals(2026, cap.collector().year());
        assertEquals(Material.LEATHER_HELMET, cap.icon().material());
        assertEquals("", cap.icon().texture());
        assertEquals(new CapLook(0xB02E26, "rib", "quartz"), cap.collector().look(),
                "the vanilla red dye with a white (quartz) rib band");
        assertEquals("Christmas", cap.collector().season());
        assertTrue(cap.costs().isEmpty(), "one price, never a ladder");
        assertEquals(0, cap.days());
        assertNull(cap.particle());
        assertFalse(PrizeService.crateable(cap), "never from a crate");
    }

    @Test
    void theTwinkleTrail() {
        Prize trail = row("trail_twinkle_2026");
        assertEquals("&bTwinkle 2026", trail.display());
        assertEquals(List.of("Twinkly lights follow you!"), trail.description());
        assertEquals(400, trail.costTokens());
        assertEquals(PrizeTab.COSMETICS, trail.tab());
        assertEquals(PrizeType.TRAIL, trail.type());
        assertEquals(LimitPer.LIFETIME, trail.limit().per());
        assertEquals(1, trail.limit().count());
        assertTrue(trail.enabled());
        assertTrue(trail.collector().giftable());
        assertEquals(2026, trail.collector().year(), "the 2026 trail, though its season ends in 2027");
        assertEquals("ELECTRIC_SPARK", trail.particle());
        assertEquals(Material.SEA_LANTERN, trail.icon().material());
        assertEquals(36500, trail.days(), "kept for good");
        assertNull(trail.collector().look());
        assertEquals("Christmas", trail.collector().season());
        assertFalse(PrizeService.crateable(trail), "never from a crate");
        assertEquals(TrailService.puff("ELECTRIC_SPARK"), TrailService.puff("HEART"), "sparks at the feet");
    }

    /** Twinkle runs into the new year: {@code inSeason} compares whole dates, so the year changes nothing. */
    @Test
    void twinkleIsOnSaleFromTheFirstOfDecemberToTheSixthOfJanuary() {
        var twinkle = row("trail_twinkle_2026").collector();
        assertFalse(twinkle.inSeason(LocalDate.of(2026, 11, 30)));
        assertTrue(twinkle.inSeason(LocalDate.of(2026, 12, 1)));
        assertTrue(twinkle.inSeason(LocalDate.of(2026, 12, 31)));
        assertTrue(twinkle.inSeason(LocalDate.of(2027, 1, 1)), "past New Year's Eve");
        assertTrue(twinkle.inSeason(LocalDate.of(2027, 1, 6)));
        assertFalse(twinkle.inSeason(LocalDate.of(2027, 1, 7)));
        assertFalse(twinkle.inSeason(LocalDate.of(2027, 12, 15)), "the 2026 trail never comes back");
        assertFalse(twinkle.inSeason(LocalDate.of(2026, 1, 3)), "nor sells the January before it");
    }

    @Test
    void theSantaCapIsOnSaleForTheWholeOfDecember() {
        var santa = row("cap_santa_2026").collector();
        assertFalse(santa.inSeason(LocalDate.of(2026, 11, 30)));
        assertTrue(santa.inSeason(LocalDate.of(2026, 12, 1)));
        assertTrue(santa.inSeason(LocalDate.of(2026, 12, 25)));
        assertTrue(santa.inSeason(LocalDate.of(2026, 12, 31)));
        assertFalse(santa.inSeason(LocalDate.of(2027, 1, 1)));
        assertFalse(santa.inSeason(LocalDate.of(2027, 12, 1)), "the 2026 cap never comes back");
    }

    @Test
    void whatIsOnSaleMonthByMonth() {
        LocalDate oct = LocalDate.of(2026, 10, 15);
        LocalDate nov = LocalDate.of(2026, 11, 15);
        LocalDate dec = LocalDate.of(2026, 12, 15);
        assertEquals(List.of("cap_pumpkin_2026", "trail_leaves_2026"), onSaleOn(oct));
        assertEquals(List.of("trail_leaves_2026"), onSaleOn(nov), "November: the leaves only");
        assertEquals(List.of("cap_santa_2026", "trail_twinkle_2026"), onSaleOn(dec));
        assertEquals(List.of("trail_twinkle_2026"), onSaleOn(LocalDate.of(2027, 1, 6)));
        assertEquals(List.of(), onSaleOn(LocalDate.of(2027, 1, 7)));
    }

    private static List<String> onSaleOn(LocalDate day) {
        return Collectors.ROWS.stream().filter(p -> PrizeService.onSale(p, true, day)).map(Prize::id).toList();
    }

    /** The season comes from the start month, and names the collector's line and its colour. */
    @Test
    void theSeasonNameAndColour() {
        assertEquals("Halloween", Collector.seasonOf(LocalDate.of(2026, 10, 1)));
        assertEquals("Christmas", Collector.seasonOf(LocalDate.of(2026, 12, 1)));
        assertEquals("Spring", Collector.seasonOf(LocalDate.of(2027, 4, 1)));
        assertEquals("Spring", Collector.seasonOf(LocalDate.of(2027, 5, 20)));
        assertEquals("Summer", Collector.seasonOf(LocalDate.of(2027, 7, 1)));
        assertEquals("Summer", Collector.seasonOf(LocalDate.of(2027, 8, 31)));
        for (int month : new int[] {1, 2, 3, 6, 9, 11}) {
            assertEquals("Holiday", Collector.seasonOf(LocalDate.of(2027, month, 1)), "month " + month);
        }
        assertEquals("Holiday", Collector.seasonOf(null));

        assertEquals("&6", Collectors.seasonColour("Halloween"));
        assertEquals("&c", Collectors.seasonColour("Christmas"));
        assertEquals("&d", Collectors.seasonColour("Spring"));
        assertEquals("&e", Collectors.seasonColour("Summer"));
        assertEquals("&6", Collectors.seasonColour("Holiday"), "any other season keeps the gold of 0.37.1");
        assertEquals("&6", Collectors.seasonColour(null));

        assertEquals(List.of("Halloween", "Halloween", "Christmas", "Christmas"),
                Collectors.ROWS.stream().map(p -> p.collector().season()).toList());
        assertEquals("&6Halloween 2026 collector", Collectors.collectorLine(row("cap_pumpkin_2026").collector()));
        assertEquals("&6Halloween 2026 collector", Collectors.collectorLine(row("trail_leaves_2026").collector()));
        assertEquals("&cChristmas 2026 collector", Collectors.collectorLine(row("cap_santa_2026").collector()));
        assertEquals("&cChristmas 2026 collector", Collectors.collectorLine(row("trail_twinkle_2026").collector()),
                "the collector's year, not the year its season ends in");
    }

    /** The five-argument constructor works the season out; the full one keeps a season it is given. */
    @Test
    void theSeasonIsWorkedOutUnlessGiven() {
        Collector c = new Collector(2027, LocalDate.of(2027, 4, 1), LocalDate.of(2027, 5, 31), true, null);
        assertEquals("Spring", c.season());
        assertEquals(c, new Collector(2027, LocalDate.of(2027, 4, 1), LocalDate.of(2027, 5, 31), true, null, "Spring"));
        assertEquals("Easter", new Collector(2027, LocalDate.of(2027, 4, 1), LocalDate.of(2027, 4, 30), true, null,
                "Easter").season());
        assertEquals("Christmas", new Collector(2027, LocalDate.of(2027, 12, 1), LocalDate.of(2027, 12, 31), true,
                null, " ").season(), "a blank season is worked out too");
        assertEquals("Christmas", new Collector(2027, LocalDate.of(2027, 12, 1), LocalDate.of(2027, 12, 31), true,
                null, null).season());
    }

    /** The Santa Cap's lore: the Christmas line in red, and otherwise the Pumpkin Cap's. */
    @Test
    void theSantaCapsLoreSaysChristmas() {
        assertEquals(List.of("&7A cozy Santa cap for Christmas!", "&cChristmas 2026 collector", "&aYours for good!",
                        "&7A gift from &fA Parent", "&7No. 2", "&7Wear it! Right-click to put it on.", "&8Arcade prize"),
                PrizeItems.collectorLore(row("cap_santa_2026"), "A Parent", 2));
    }

    /**
     * Twinkle's particle draws on every server this plugin runs on: ELECTRIC_SPARK is in the 26.2 API (this
     * test's classpath) and takes no data, so it is never held back as the Autumn Leaves trail is on 26.2.
     */
    @Test
    void electricSparkTakesNoData() {
        assertEquals(Void.class, org.bukkit.Particle.ELECTRIC_SPARK.getDataType());
        assertTrue(TrailService.particleUsable("ELECTRIC_SPARK"));
        assertNull(CollectorSelfTest.particleProblem(row("trail_twinkle_2026"), true,
                org.bukkit.Particle.ELECTRIC_SPARK.getDataType()), "the self-test passes it");
        Set<String> notHere = PrizeService.trailsNotHere(Collectors.ROWS, TrailService::particleUsable);
        assertFalse(notHere.contains("trail_twinkle_2026"));
        assertTrue(PrizeService.offered(row("trail_twinkle_2026"), true, LocalDate.of(2026, 12, 1), notHere));
        assertNull(PrizeService.restoreRefusal(row("trail_twinkle_2026"), notHere));
    }

    /**
     * The two 2026 Halloween rows as 0.37.1 wrote them, with the long {@code Prize} calls. The helpers that
     * now build them must give back exactly these, field by field.
     */
    private static final List<Prize> ROWS_0371 = List.of(
            new Prize("cap_pumpkin_2026", PrizeTab.COSMETICS, "&6Pumpkin Cap 2026",
                    List.of("A pumpkin cap for Halloween!"), 250, new Icon("", Material.LEATHER_HELMET),
                    PrizeType.HAT, true, new PrizeLimit(LimitPer.LIFETIME, 1),
                    1, null, null, null, null, 0, 0, null, 0, "",
                    List.of(), List.of(), null, null, List.of(),
                    new Collector(2026, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), true,
                            new CapLook(0xF9801D, "wild", "emerald"))),
            new Prize("trail_leaves_2026", PrizeTab.COSMETICS, "&6Autumn Leaves 2026",
                    List.of("Autumn leaves follow you!"), 400, new Icon("", Collectors.leaves()),
                    PrizeType.TRAIL, true, new PrizeLimit(LimitPer.LIFETIME, 1),
                    1, null, null, null, null, 0, 0, "ORANGE_POPLAR_LEAVES", 36500, "",
                    List.of(), List.of(), null, null, List.of(),
                    new Collector(2026, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 30), true, null)));

    @Test
    void theTwoHalloweenRowsAreTheirOldSelvesFieldByField() throws ReflectiveOperationException {
        for (Prize old : ROWS_0371) {
            Prize now = row(old.id());
            for (RecordComponent c : Prize.class.getRecordComponents()) {
                assertEquals(c.getAccessor().invoke(old), c.getAccessor().invoke(now), old.id() + "." + c.getName());
            }
            for (RecordComponent c : Collector.class.getRecordComponents()) {
                assertEquals(c.getAccessor().invoke(old.collector()), c.getAccessor().invoke(now.collector()),
                        old.id() + ".collector." + c.getName());
            }
            assertEquals(old, now);
            assertEquals("Halloween", now.collector().season());
        }
        assertEquals(ROWS_0371, Collectors.ROWS.subList(0, 2), "and they stay the first two, in that order");
    }

    @Test
    void collectorIdsAreCaseInsensitive() {
        assertTrue(Collectors.isCollectorId("cap_pumpkin_2026"));
        assertTrue(Collectors.isCollectorId("CAP_Pumpkin_2026"));
        assertTrue(Collectors.isCollectorId(" trail_leaves_2026 "));
        assertFalse(Collectors.isCollectorId("cap_pumpkin"));
        assertFalse(Collectors.isCollectorId("cap_pumpkin_2027"));
        assertTrue(Collectors.isCollectorId("Cap_Santa_2026"));
        assertTrue(Collectors.isCollectorId("trail_twinkle_2026"));
        assertFalse(Collectors.isCollectorId("cap_santa"));
        assertFalse(Collectors.isCollectorId(null));
    }

    @Test
    void aConfigRowMayNotTakeACollectorOrCandyId() {
        assertTrue(Collectors.reserved("cap_pumpkin_2026"));
        assertTrue(Collectors.reserved("Trail_Leaves_2026"));
        assertTrue(Collectors.reserved("cap_santa_2026"));
        assertTrue(Collectors.reserved("TRAIL_TWINKLE_2026"));
        assertTrue(Collectors.reserved("candy_x"));
        assertTrue(Collectors.reserved("CANDY_apple_2026"));
        assertTrue(Collectors.reserved("bag_trick_or_treat_x"));
        assertFalse(Collectors.reserved("candycane_cap"), "only the candy_ prefix, with its underscore");
        assertFalse(Collectors.reserved("trail_heart"));
        assertFalse(Collectors.reserved(null));
        assertEquals("Arcade prize 'candy_x' is one of the plugin's own collectors - your row is skipped "
                + "(collectors are built in; see arcade.collectors).", Collectors.skipWarning("candy_x"));
    }
}
