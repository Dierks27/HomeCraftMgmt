package com.dierks.homecraft.gui;

import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The department tab icons, and the two rules that keep the row readable.
 *
 * <p>These exist because the row shipped once as stained-glass panes and seven of the eight tabs
 * were invisible — light grey is the colour of the inventory slot behind it, and an item's name
 * only appears on hover, so an unreadable material is a tile with nothing on it. The icon is the
 * whole signal, which is why it is worth pinning.
 *
 * <p>Registry-free by construction: {@link org.bukkit.Material} enum access and identity only. On
 * Paper 26.2 the Material predicates ({@code isItem}, {@code isBlock}, {@code isAir}) resolve
 * through the registry and throw without a running server.
 */
class DepartmentIconsTest {

    /**
     * Every material the surrounding menu chrome already uses, in any menu that paints a
     * department tab row: StoreMenu and MarketMenu slots 45-53, MarketplaceMenu's nav and
     * empty-state, and the shared filler. A tab that wears one of these reads as that control —
     * and the two do very different things: a tab filters a list, the balance tile and the grid
     * below it are about money.
     */
    private static final Set<Material> MENU_CHROME = Set.of(
            Material.ARROW,                    // prev / next, all three menus
            Material.GOLD_INGOT,               // the balance tile
            Material.EMERALD,                  // Store -> Instant Market
            Material.CHEST,                    // Store -> Marketplace
            Material.PAPER,                    // Store -> Card Packs, Marketplace empty state
            Material.PLAYER_HEAD,              // Store -> Mini Museum
            Material.CHEST_MINECART,           // Store -> Mailbox & Orders
            Material.HOPPER,                   // the Sort toggle
            Material.BARRIER,                  // Back
            Material.GRAY_STAINED_GLASS_PANE); // Menus.FILLER

    /** "All" plus the departments config.yml ships, which is what the tab row actually paints. */
    private static List<String> shippedTabs() throws IOException, InvalidConfigurationException {
        try (InputStream in = DepartmentIconsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is missing from the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration bundled = new YamlConfiguration();
                bundled.load(reader);
                List<String> tabs = new ArrayList<>();
                tabs.add("All");
                tabs.addAll(bundled.getStringList("marketplace.departments"));
                assertTrue(tabs.size() > 1, "config.yml should ship a departments list");
                return tabs;
            }
        }
    }

    @Test
    void everyShippedDepartmentHasAnIconOfItsOwn() throws Exception {
        for (String tab : shippedTabs()) {
            assertNotEqualsFallback(tab);
        }
    }

    private void assertNotEqualsFallback(String tab) {
        Material icon = DepartmentIcons.of(tab);
        assertFalse(icon == DepartmentIcons.FALLBACK,
                "'" + tab + "' is shipped in marketplace.departments but falls back to the generic "
                        + "icon — add it to DepartmentIcons, or the tab row shows two identical tiles");
    }

    @Test
    void noTwoDepartmentsWearTheSameIcon() throws Exception {
        Set<Material> seen = new HashSet<>();
        for (String tab : shippedTabs()) {
            Material icon = DepartmentIcons.of(tab);
            assertTrue(seen.add(icon),
                    "two departments share " + icon + " — the tab row exists to tell them apart");
        }
    }

    @Test
    void noIconCollidesWithTheMenuChromeAroundIt() throws Exception {
        for (String tab : shippedTabs()) {
            Material icon = DepartmentIcons.of(tab);
            assertFalse(MENU_CHROME.contains(icon),
                    "the '" + tab + "' tab wears " + icon + ", which is already a button in the "
                            + "same menu — see MENU_CHROME for where");
        }
    }

    @Test
    void noIconIsAPaneOrOtherwiseInvisible() throws Exception {
        for (String tab : shippedTabs()) {
            String name = DepartmentIcons.of(tab).name();
            assertFalse(name.endsWith("_STAINED_GLASS_PANE"),
                    "'" + tab + "' is a pane (" + name + ") — a thin, semi-transparent sprite is "
                            + "exactly what made this row invisible");
            assertFalse(name.startsWith("LIGHT_GRAY_") || name.startsWith("WHITE_")
                            || name.startsWith("GRAY_"),
                    "'" + tab + "' is greyscale (" + name + ") and will sink into the slot behind it");
        }
    }

    /** The fallback is what an admin's own department gets, so it has to be visible too. */
    @Test
    void anUnknownDepartmentGetsTheFallback() {
        assertEquals(DepartmentIcons.FALLBACK, DepartmentIcons.of("Fireworks"));
        assertEquals(DepartmentIcons.FALLBACK, DepartmentIcons.of(""));
        assertEquals(DepartmentIcons.FALLBACK, DepartmentIcons.of("   "));
        assertEquals(DepartmentIcons.FALLBACK, DepartmentIcons.of(null));
        assertFalse(DepartmentIcons.FALLBACK.name().endsWith("_STAINED_GLASS_PANE"));
    }

    /** marketplace.departments is hand-typed, so casing and stray spaces must not lose the icon. */
    @Test
    void lookupIgnoresCaseAndSurroundingSpace() {
        Material food = DepartmentIcons.of("Food");
        assertFalse(food == DepartmentIcons.FALLBACK);
        assertEquals(food, DepartmentIcons.of("food"));
        assertEquals(food, DepartmentIcons.of("FOOD"));
        assertEquals(food, DepartmentIcons.of("  Food  "));
    }

    /**
     * The map must not drift ahead of the shipped list either: an entry for a department nobody
     * ships is dead weight that hides a rename.
     */
    @Test
    void theMapCoversExactlyTheShippedDepartments() throws Exception {
        Set<String> shipped = new HashSet<>();
        for (String tab : shippedTabs()) {
            shipped.add(tab.toLowerCase(Locale.ROOT));
        }
        assertEquals(shipped, new HashSet<>(DepartmentIcons.known()),
                "DepartmentIcons and marketplace.departments in config.yml have drifted apart");
    }
}
