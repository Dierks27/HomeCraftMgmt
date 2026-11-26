package com.dierks.homecraft.gui.games.chance;

import com.dierks.homecraft.pack.CardFace;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.58.0: a card on a card game's screen says its short form first ("&amp;fJ♠ &amp;7Jack of Spades"), counts the
 * way its game needs, and is drawn as its picture only for a viewer with HCM's pack: every other viewer keeps the
 * vanilla item.
 */
class CardTilesTest {

    private static final Path BASE = Path.of("src", "main", "java", "com", "dierks", "homecraft");

    /** The card screens: every card they show goes through {@link CardGameTiles}. */
    private static final List<String> SCREENS = List.of("TwentyOneMenu.java", "TwentyOneTableMenu.java",
            "HigherLowerMenu.java", "VideoPokerMenu.java", "HoldemMenu.java");

    @Test
    void aCardsNameStartsWithItsShortFormInItsSuitsColour() {
        assertEquals("&fJ♠ &7Jack of Spades", CardGameTiles.name(new CardFace(11, 0)));
        assertEquals("&cQ♥ &7Queen of Hearts", CardGameTiles.name(new CardFace(12, 1)));
        assertEquals("&c10♦ &710 of Diamonds", CardGameTiles.name(new CardFace(10, 2)));
        assertEquals("&fK♣ &7King of Clubs", CardGameTiles.name(new CardFace(13, 3)));
        assertEquals("&fA♠ &7Ace of Spades", CardGameTiles.name(new CardFace(1, 0)));
        assertEquals("&9J♦ &7Jack of Diamonds", CardGameTiles.name("&9", new CardFace(11, 2)), "Hold'em's colours");
        for (CardFace f : CardFace.deck()) {
            String name = CardGameTiles.name(f);
            assertTrue(name.startsWith((f.red() ? "&c" : "&f") + f.shortName() + " "), name);
        }
    }

    @Test
    void aPlainCardCountsItsRankWithAcesHighAndAPictureNone() {
        assertEquals(14, CardGameTiles.rankCount(false, new CardFace(1, 0)), "an Ace is high");
        assertEquals(11, CardGameTiles.rankCount(false, new CardFace(11, 1)));
        assertEquals(13, CardGameTiles.rankCount(false, new CardFace(13, 1)));
        assertEquals(2, CardGameTiles.rankCount(false, new CardFace(2, 3)));
        for (CardFace f : CardFace.deck()) {
            assertEquals(1, CardGameTiles.rankCount(true, f), f + ": its picture says its rank");
        }
    }

    @Test
    void withoutThePackACardIsNeverGivenAModel() {
        for (CardFace f : CardFace.deck()) {
            assertNull(CardGameTiles.model(false, f), f + ": the vanilla item");
            assertEquals(f.modelId(), CardGameTiles.model(true, f));
        }
        assertNull(CardGameTiles.backModel(false), "a face-down card too");
        assertEquals("hcm:card_back", CardGameTiles.backModel(true));
    }

    /**
     * The one way a card gets a model is {@link CardGameTiles#drawn}, from {@link CardGameTiles#model} or
     * {@link CardGameTiles#backModel} (null without the pack); each card screen asks {@link CardGameTiles#art} for its
     * own viewer and paints no face-down map of its own.
     */
    @Test
    void everyCardScreenAsksWhetherItsViewerHasThePack() throws IOException {
        Path dir = BASE.resolve("gui/games/chance");
        assertTrue(Files.isDirectory(dir), "expected to scan " + dir.toAbsolutePath());
        List<String> setters = new ArrayList<>();
        try (Stream<Path> files = Files.walk(BASE.resolve("gui/games"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (Files.readString(p).contains("setItemModel")) {
                    setters.add(p.getFileName().toString());
                }
            }
        }
        assertEquals(List.of("CardGameTiles.java"), setters, "no screen sets a model on its own");
        for (String screen : SCREENS) {
            String src = Files.readString(dir.resolve(screen));
            assertTrue(src.contains("CardGameTiles.art(plugin, viewer)"), screen + " asks for its own viewer");
            assertTrue(!src.contains("Material.MAP, \"&8Face-down card\""), screen + ": the face-down card is shared");
        }
    }
}
