package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.looks.LookRules;
import com.dierks.homecraft.arcade.looks.LookRules.Window;
import com.dierks.homecraft.config.PluginConfig;
import com.dierks.homecraft.config.PluginConfig.LimitPer;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2's 0.38 shelf through S3's wardrobe (SHOP-V2-SPEC §2.4, §3.1, §3.5, §3.13, §7.4): every hat, cap and trail the
 * bundled config ships is a rental, sold at its price the first time and at the same price again (D6), can be a
 * present, shows without a picture when it names a material (P3) and never comes out of a crate when it is a hat
 * (D11); the golf balls are kept for good, can be presents and are never rented. And the paths, read from the source
 * (a PrizeService can't be built without a server): a rented row's buy and present go to the wardrobe before anything
 * else, and the plain delivery refuses a rented hat, so no hat kept for good is made past it ("S2 and S3 ship
 * together", §7.4).
 */
class RentalWiringTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 21);
    private static final PrizeService.Shelf SHELF = new PrizeService.Shelf(true, TODAY, Set.of(), true);

    /**
     * The bundled rows, read by the loader's own {@code readPrize} with name rules for the two material facts that
     * need a running server (as {@code PrizeExtraParseTest} does): everything is an item, a block is named like the
     * balls' blocks.
     */
    private static Map<String, Prize> bundled() throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        try (var r = new InputStreamReader(RentalWiringTest.class.getResourceAsStream("/config.yml"),
                StandardCharsets.UTF_8)) {
            c.load(r);
        }
        Method read = PluginConfig.class.getDeclaredMethod("readPrize", Map.class, int.class, int.class,
                Consumer.class, Predicate.class, Predicate.class);
        read.setAccessible(true);
        Predicate<Material> item = m -> m != null;
        Predicate<Material> block = m -> m.name().endsWith("_CONCRETE") || m.name().endsWith("_BLOCK");
        List<String> warnings = new ArrayList<>();
        Consumer<String> warn = warnings::add;
        Map<String, Prize> out = new LinkedHashMap<>();
        for (Map<?, ?> row : c.getMapList("arcade.prizes")) {
            Prize p = (Prize) read.invoke(null, row, 150, 1, warn, item, block);
            assertNotNull(p, String.valueOf(row.get("id")));
            out.put(p.id(), p);
        }
        assertEquals(List.of(), warnings);
        return out;
    }

    private static final List<String> RENTED_HATS = List.of("hat_creeper", "hat_dragon", "cap_pink", "cap_purple",
            "cap_lime", "cap_sky", "hat_bunny", "hat_panda", "hat_fox", "hat_penguin", "hat_frog", "hat_cake");
    private static final List<String> RENTED_TRAILS = List.of("trail_hearts", "trail_notes", "trail_sparkles",
            "trail_snow", "trail_stars", "trail_clover", "trail_glow", "trail_magic");

    @Test
    void theShippedHatsCapsAndTrailsAreRentals() throws Exception {
        Map<String, Prize> rows = bundled();
        List<String> rented = new ArrayList<>();
        for (Prize p : rows.values()) {
            if (p.rental()) {
                rented.add(p.id());
            }
        }
        List<String> expected = new ArrayList<>(RENTED_TRAILS);
        expected.addAll(RENTED_HATS);
        assertEquals(Set.copyOf(expected), Set.copyOf(rented), "every config hat and trail is rented, nothing else");
        assertEquals(Window.NEW, LookRules.window(null, TODAY.toEpochDay() * LookRules.DAY),
                "never had it: a first buy");
        for (String id : rented) {
            Prize p = rows.get(id);
            assertEquals(p.type() == PrizeType.HAT ? 90 : 30, p.days(), id + ": its days");
            assertEquals(p.costTokens(), LookRules.price(p, Window.NEW), id + ": a first buy at its price");
            assertEquals(p.costTokens(), LookRules.price(p, Window.OPEN), id + ": and again at the same (D6)");
            assertTrue(PrizeService.giftable(p), id + ": bound to its owner, so a present is the way to give one");
        }
    }

    @Test
    void aShippedMaterialHatShowsWithNoPictureAndNoHatComesFromACrate() throws Exception {
        Map<String, Prize> rows = bundled();
        for (String id : RENTED_HATS) {
            Prize p = rows.get(id);
            assertEquals(PrizeTab.LOOKS, p.tab(), id);
            assertFalse(PrizeService.crateable(p), id + ": a hat is bought for its days, never won (D11)");
            assertFalse(PrizeService.grantable(p, SHELF), id);
            if (p.extra().hatMaterial() != null) {
                assertTrue(PrizeService.shelved(p, SHELF), id + ": the game's own head or the cap needs no picture");
            } else {
                assertFalse(PrizeService.shelved(p, SHELF), id + ": an animal hat with no texture stays hidden (D10)");
            }
        }
        for (String id : RENTED_TRAILS) {
            Prize p = rows.get(id);
            assertTrue(PrizeService.shelved(p, SHELF), id);
            assertTrue(PrizeService.grantable(p, SHELF), id + ": a crate's trail is a free day of a rented one");
        }
    }

    @Test
    void theGolfBallsAreKeptForGoodAndCanBePresentsButAreNeverRented() throws Exception {
        int balls = 0;
        for (Prize p : bundled().values()) {
            if (p.type() != PrizeType.BALL) {
                if (p.type() == PrizeType.TROPHY || p.type() == PrizeType.PARTY || p.type() == PrizeType.BOOST) {
                    assertFalse(PrizeService.giftable(p), p.id() + ": never a present");
                }
                continue;
            }
            balls++;
            assertFalse(p.rental(), p.id());
            assertEquals(LimitPer.LIFETIME, p.limit().per(), p.id() + ": kept for good, one each");
            assertTrue(PrizeService.giftable(p), p.id());
            assertFalse(PrizeService.crateable(p), p.id());
        }
        assertEquals(10, balls);
    }

    /** A rented row's buy and present go to the wardrobe first; the plain delivery refuses a rented hat. */
    @Test
    void rentedBuysAndPresentsGoToTheWardrobe() throws IOException {
        String src = source("arcade", "PrizeService.java");
        String buy = body(src,
                "public Outcome buy(Player player, Prize p, DyeColor color, int shownCost) {");
        assertTrue(buy.startsWith(" if (p.rental() && plugin.looks() != null) { return plugin.looks().purchase(player,"
                + " player.getUniqueId(), p, shownCost); }"), "buy(): a rental is the wardrobe's, before every check");
        String gift = body(src,
                "public Outcome gift(Player giver, UUID receiverId, Prize p, int shownCost) {");
        assertTrue(gift.contains("if (p.rental() && plugin.looks() != null) { return plugin.looks().purchase(giver,"
                + " receiverId, p, shownCost); }"), "gift(): a rented present is the wardrobe's (the giver pays)");
        assertTrue(gift.indexOf("plugin.looks().purchase") < gift.indexOf("giftBall(giver, receiverId, p)"));
        String deliver = body(src,
                "private Outcome deliver(Player player, Prize p, DyeColor color, boolean charge, int daysOverride,"
                        + " int shown) {");
        assertTrue(deliver.contains("if (p.type() == PrizeType.HAT && (p.rental() || p.keeper())) {"),
                "deliver(): no loose rented hat, nor a loose Keeper (S4)");
        assertTrue(deliver.indexOf("p.rental()") < deliver.indexOf("PrizeItems.build(plugin, p)"));
        String price = body(src, "public int price(UUID player, Prize p) {");
        assertTrue(price.startsWith(" if (p.rental() && plugin.looks() != null) { return plugin.looks().price(player,"
                + " p); }"), "price(): the buy-again window's price");

        String menu = source("gui/arcade", "PrizeCounterMenu.java");
        assertTrue(body(menu, "private ItemStack tile(Prize p, int tokens) {")
                .startsWith(" if (p.rental() && plugin.looks() != null) { return rentalTile(p, tokens); }"));
        assertTrue(body(menu, "private void click(Prize p) {")
                .startsWith(" if (p.rental() && plugin.looks() != null) { clickLook(p); return; }"));
    }

    private static String source(String dir, String file) throws IOException {
        return Files.readString(Path.of("src/main/java/com/dierks/homecraft", dir, file), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");
    }

    /** The text of a method from its head to its closing brace, whitespace already one space (as CollectorsTest). */
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
        throw new AssertionError("fixture: no end for " + head);
    }
}
