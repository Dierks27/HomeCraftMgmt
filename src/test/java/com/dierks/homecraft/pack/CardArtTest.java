package com.dierks.homecraft.pack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.58.0, the playing cards (the owner: "Can we make it so Jacks show a J, Queens are Q and such on the cards? Any way
 * we can make a bit of art for the cards to show what the cards are a bit better than a paper?"): every card's short
 * name, its picture painted the same way every time at the right size, the 53 cards' files in HCM's pack, the owner's
 * drop-ins standing in for them, and who is drawn the pictures at all.
 */
class CardArtTest {

    private static final String[] RANKS = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
    private static final String[] SUITS = {"♠", "♥", "♦", "♣"};

    @Test
    void everyCardHasItsShortName() {
        List<CardFace> deck = CardFace.deck();
        assertEquals(52, deck.size());
        Set<String> seen = new HashSet<>();
        for (CardFace f : deck) {
            assertEquals(RANKS[f.rank() - 1] + SUITS[f.suit()], f.shortName(), f.longName());
            assertTrue(seen.add(f.shortName()), "once: " + f);
            assertEquals(f.suit() == 1 || f.suit() == 2, f.red(), f.longName() + ": Hearts and Diamonds are red");
        }
        assertEquals("A♠", new CardFace(1, 0).shortName());
        assertEquals("10♥", new CardFace(10, 1).shortName());
        assertEquals("J♦", new CardFace(11, 2).shortName());
        assertEquals("Q♣", new CardFace(12, 3).shortName());
        assertEquals("K♠", new CardFace(13, 0).shortName());
        assertEquals("Jack of Spades", new CardFace(11, 0).longName());
        assertEquals("Ace of Hearts", new CardFace(1, 1).longName());
        assertEquals("7 of Clubs", new CardFace(7, 3).longName());
    }

    @Test
    void aGameWithAcesHighNamesTheSameCards() {
        assertEquals(new CardFace(1, 2), CardFace.aceHigh(14, 2), "an Ace is an Ace");
        assertEquals(new CardFace(13, 0), CardFace.aceHigh(13, 0));
        assertEquals(new CardFace(2, 3), CardFace.aceHigh(2, 3));
        for (CardFace f : CardFace.deck()) {
            assertEquals(f, CardFace.aceHigh(f.aceHighRank(), f.suit()), f.longName());
            assertTrue(f.aceHighRank() >= 2 && f.aceHighRank() <= 14);
        }
        assertEquals(14, new CardFace(1, 0).aceHighRank());
        assertThrows(IllegalArgumentException.class, () -> new CardFace(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CardFace(14, 0), "an Ace high is aceHigh's to turn");
        assertThrows(IllegalArgumentException.class, () -> new CardFace(5, 4));
    }

    @Test
    void everyCardHasItsOwnModelName() {
        List<String> names = CardArt.names();
        assertEquals(53, names.size(), "52 faces and a back");
        assertEquals(53, new HashSet<>(names).size());
        assertEquals("hcm:card_ace_spades", new CardFace(1, 0).modelId());
        assertEquals("hcm:card_10_hearts", new CardFace(10, 1).modelId());
        assertEquals("hcm:card_queen_clubs", new CardFace(12, 3).modelId());
        assertEquals("hcm:card_back", CardFace.BACK_ID);
        for (String n : names) {
            assertTrue(n.matches("card_[a-z0-9_]+"), "a valid model name: " + n);
        }
    }

    // ---- the pictures --------------------------------------------------------------------------------------------

    @Test
    void thePicturesAre32SquarePngsPaintedTheSameEveryTime() throws IOException {
        Set<String> distinct = new HashSet<>();
        List<int[]> all = new ArrayList<>();
        for (CardFace f : CardFace.deck()) {
            all.add(CardArt.face(f));
        }
        all.add(CardArt.back());
        for (int i = 0; i < all.size(); i++) {
            int[] px = all.get(i);
            byte[] png = CardArt.png(px);
            assertArrayEquals(png, CardArt.png(i < 52 ? CardArt.face(CardFace.deck().get(i)) : CardArt.back()),
                    "the same bytes every time, so the pack only rebuilds when the art changes");
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            assertNotNull(img, "a PNG any reader can open");
            assertEquals(CardArt.SIZE, img.getWidth());
            assertEquals(CardArt.SIZE, img.getHeight());
            assertEquals(32, CardArt.SIZE);
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    assertEquals(px[y * 32 + x], img.getRGB(x, y), "pixel " + x + "," + y + " round trip");
                }
            }
            assertEquals(0, img.getRGB(0, 0) >>> 24, "clear around the card");
            assertEquals(0, img.getRGB(3, 1) >>> 24, "a rounded corner");
            assertEquals(255, img.getRGB(15, 0 + 2) >>> 24, "the card itself is solid");
            assertTrue(distinct.add(Arrays.toString(px)), "every card looks different: " + i);
        }
    }

    @Test
    void redSuitsAreRedAndBlackSuitsBlack() {
        for (CardFace f : CardFace.deck()) {
            int[] px = CardArt.face(f);
            int red = 0;
            int dark = 0;
            for (int p : px) {
                int r = p >>> 16 & 0xFF;
                int g = p >>> 8 & 0xFF;
                int b = p & 0xFF;
                if (p >>> 24 == 0xFF && r > 180 && g < 80 && b < 80) {
                    red++;
                }
                if (p >>> 24 == 0xFF && r < 40 && g < 40 && b < 50) {
                    dark++;
                }
            }
            if (f.red()) {
                assertTrue(red > 10, f.longName() + " has red ink");
            } else {
                assertEquals(0, red, f.longName() + " has no red ink");
                assertTrue(dark > 10, f.longName() + " has black ink");
            }
        }
    }

    @Test
    void theRankIsInTheTopLeftCorner() {
        // the corner's 5 x 5 below the rim: the rank's ink is there, and differs between ranks of a suit
        Set<String> corners = new HashSet<>();
        for (int rank = 1; rank <= 13; rank++) {
            int[] px = CardArt.face(new CardFace(rank, 1));
            StringBuilder corner = new StringBuilder();
            int ink = 0;
            for (int y = 3; y < 8; y++) {
                for (int x = 4; x < 9; x++) {
                    int p = px[y * 32 + x];
                    boolean on = (p >>> 16 & 0xFF) > 180 && (p >>> 8 & 0xFF) < 80;
                    corner.append(on ? '#' : '.');
                    ink += on ? 1 : 0;
                }
            }
            assertTrue(ink >= 5, "rank " + rank + " is drawn in the corner");
            assertTrue(corners.add(corner.toString()), "rank " + rank + "'s corner is its own");
        }
    }

    // ---- the pack's files ----------------------------------------------------------------------------------------

    @Test
    void thePackHasAnItemDefinitionAModelAndAPictureForEveryCard() {
        Map<String, byte[]> files = CardArt.files(Set.of(), Set.of());
        assertEquals(3 * 53, files.size());
        for (String name : CardArt.names()) {
            String id = name.substring("card_".length());
            JsonObject def = json(files.get("assets/hcm/items/" + name + ".json"));
            assertEquals("minecraft:model", def.getAsJsonObject("model").get("type").getAsString());
            assertEquals("hcm:item/placeholder/" + name, def.getAsJsonObject("model").get("model").getAsString());
            JsonObject model = json(files.get("assets/hcm/models/item/placeholder/" + name + ".json"));
            assertEquals("minecraft:item/generated", model.get("parent").getAsString(), "a flat item icon");
            assertEquals("hcm:item/cards/" + id, model.getAsJsonObject("textures").get("layer0").getAsString());
            byte[] png = files.get("assets/hcm/textures/item/cards/" + id + ".png");
            assertNotNull(png, name + "'s picture, where vanilla keeps its item pictures");
            assertEquals((byte) 0x89, png[0]);
            assertEquals('P', png[1]);
        }
        assertNotNull(files.get("assets/hcm/textures/item/cards/back.png"));
        assertNotNull(files.get("assets/hcm/textures/item/cards/ace_spades.png"));
        assertNotNull(files.get("assets/hcm/textures/item/cards/10_hearts.png"));
    }

    @Test
    void allOfHcmsFilesAreTheClubhousesAndTheCards() {
        Map<String, byte[]> files = PackModels.files(Set.of(), Set.of());
        assertEquals(2 * ClubModels.all().size() + 3 * 53, files.size());
        assertTrue(files.containsKey("assets/hcm/items/cabinet_whack.json"));
        assertTrue(files.containsKey("assets/hcm/items/card_king_hearts.json"));
        assertTrue(PackModels.modelNames().containsAll(CardArt.names()));
        assertTrue(PackModels.modelNames().contains("machine_cards"));
    }

    @Test
    void ourCardsPassTheSelfTest() {
        Map<String, byte[]> files = CardArt.files(Set.of(), Set.of());
        Map<String, String> json = new HashMap<>();
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            if (f.getKey().endsWith(".json")) {
                json.put(f.getKey(), new String(f.getValue(), StandardCharsets.UTF_8));
            }
        }
        for (String name : CardArt.names()) {
            assertNull(PackModels.problem(name, json, files.keySet()), name);
        }
        Map<String, byte[]> noPictures = new HashMap<>(files);
        noPictures.remove("assets/hcm/textures/item/cards/ace_spades.png");
        assertTrue(PackModels.problem("card_ace_spades", json, noPictures.keySet()).contains("isn't there"),
                "without its picture a card can't be drawn");
    }

    /** A flat icon (an owner's card) may use a vanilla item texture: it has no boxes to put it on. */
    @Test
    void anOwnersFlatCardMayUseAVanillaItemPicture() {
        Map<String, String> json = new HashMap<>();
        json.put("assets/hcm/items/card_ace_spades.json",
                "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"hcm:item/my_ace\"}}");
        json.put("assets/hcm/models/item/my_ace.json",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minecraft:item/paper\"}}");
        assertNull(PackModels.problem("card_ace_spades", json, Set.of()));
        json.put("assets/hcm/models/item/my_ace.json",
                "{\"textures\":{\"0\":\"minecraft:item/paper\"},\"elements\":[]}");
        assertTrue(PackModels.problem("card_ace_spades", json, Set.of()).contains("item texture"),
                "on boxes it is still the missing texture");
    }

    /** The owner's card replaces all three of ours; a picture alone replaces only our picture. */
    @Test
    void theOwnersCardsWinByLeavingOursOut() {
        List<String> dropIn = List.of(
                "assets/hcm/items/card_ace_spades.json",
                "assets/hcm/models/item/my_ace.json",
                "assets/hcm/textures/item/cards/ace_spades.png",
                "my-pack/assets/hcm/textures/item/cards/queen_hearts.png", // a zip with one extra folder
                "assets/hcm/textures/item/cards/joker.png",                // no such card
                "assets/hcm/textures/item/back.png");                      // not a card's place
        assertEquals(Set.of("card_ace_spades"), PackModels.providedBy(dropIn));
        assertEquals(Set.of("card_ace_spades", "card_queen_hearts"), CardArt.repaintedBy(dropIn));
        Map<String, byte[]> files = CardArt.files(Set.of("card_ace_spades"), Set.of("card_queen_hearts"));
        assertFalse(files.containsKey("assets/hcm/items/card_ace_spades.json"));
        assertFalse(files.containsKey("assets/hcm/models/item/placeholder/card_ace_spades.json"));
        assertFalse(files.containsKey("assets/hcm/textures/item/cards/ace_spades.png"), "one file at each path");
        assertTrue(files.containsKey("assets/hcm/items/card_queen_hearts.json"), "repainted: our model stays");
        assertTrue(files.containsKey("assets/hcm/models/item/placeholder/card_queen_hearts.json"));
        assertFalse(files.containsKey("assets/hcm/textures/item/cards/queen_hearts.png"), "and draws theirs");
        assertEquals(3 * 53 - 3 - 1, files.size());
    }

    @Test
    void theFolderIsWrittenOnlyWhereItChangedAndLeavesTheOwnersOut(@TempDir Path folder) throws IOException {
        Map<String, byte[]> files = PackModels.files(Set.of(), Set.of());
        PackModels.write(folder, files, Set.of(), Set.of());
        Path ace = folder.resolve("assets/hcm/textures/item/cards/ace_spades.png");
        assertArrayEquals(CardArt.png(CardArt.face(new CardFace(1, 0))), Files.readAllBytes(ace), "written as bytes");
        FileTime old = FileTime.fromMillis(1_000_000L);
        Files.setLastModifiedTime(ace, old);
        PackModels.write(folder, files, Set.of(), Set.of());
        assertEquals(old, Files.getLastModifiedTime(ace), "the same bytes: not written again");

        Set<String> owners = Set.of("card_ace_spades", "cabinet_whack");
        Set<String> repainted = Set.of("card_back");
        PackModels.write(folder, PackModels.files(owners, repainted), owners, repainted);
        assertFalse(Files.exists(ace));
        assertFalse(Files.exists(folder.resolve("assets/hcm/items/card_ace_spades.json")));
        assertFalse(Files.exists(folder.resolve("assets/hcm/models/item/placeholder/card_ace_spades.json")));
        assertFalse(Files.exists(folder.resolve("assets/hcm/items/cabinet_whack.json")), "the Clubhouse's rule too");
        assertFalse(Files.exists(folder.resolve("assets/hcm/textures/item/cards/back.png")));
        assertTrue(Files.exists(folder.resolve("assets/hcm/items/card_back.json")));
        assertTrue(Files.exists(folder.resolve("assets/hcm/textures/item/cards/king_clubs.png")));
    }

    // ---- who is drawn the pictures -----------------------------------------------------------------------------

    @Test
    void onlyAJavaPlayerWhoLoadedThePackIsDrawnThePictures() {
        assertTrue(PackModels.drawsFor(true, false, Status.SUCCESSFULLY_LOADED));
        assertFalse(PackModels.drawsFor(false, false, Status.SUCCESSFULLY_LOADED), "no CraftBridge: no models");
        assertFalse(PackModels.drawsFor(true, true, Status.SUCCESSFULLY_LOADED),
                "Bedrock through Geyser: never a Java pack, whatever its status says");
        for (Status s : Status.values()) {
            if (s != Status.SUCCESSFULLY_LOADED) {
                assertFalse(PackModels.drawsFor(true, false, s), s + ": the vanilla item");
            }
        }
        assertFalse(PackModels.drawsFor(true, false, null), "not answered yet");
    }

    private static JsonObject json(byte[] bytes) {
        assertNotNull(bytes);
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
