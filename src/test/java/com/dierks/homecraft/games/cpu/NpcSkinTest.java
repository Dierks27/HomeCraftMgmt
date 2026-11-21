package com.dierks.homecraft.games.cpu;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The owner's skin choice for an NPC ("Can I pick some of the Skins?"): a built-in skin, a Minecraft account's, or a
 * pasted texture; anything else is refused with a line saying what to type, and a kept value that can't be read falls
 * back to the NPC's own built-in skin (never a blank one).
 */
class NpcSkinTest {

    private static final String TEXTURE = Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":"
            + "\"http://textures.minecraft.net/texture/abc123\"}}}").getBytes(StandardCharsets.UTF_8));

    @Test
    void aBuiltInSkinWideOrSlim() {
        assertEquals(new NpcSkin.BuiltIn("noor", false), NpcSkin.parse("default:noor"));
        assertEquals(new NpcSkin.BuiltIn("noor", true), NpcSkin.parse("default:noor:slim"));
        assertEquals(new NpcSkin.BuiltIn("noor", true), NpcSkin.parse("DEFAULT:Noor_slim"));
        assertEquals(new NpcSkin.BuiltIn("steve", false), NpcSkin.parse("default:steve wide"));
        assertEquals("entity/player/slim/zuri", new NpcSkin.BuiltIn("zuri", true).texturePath());
        assertEquals("entity/player/wide/alex", new NpcSkin.BuiltIn("alex", false).texturePath());
        assertEquals(9, NpcSkin.BUILT_IN.size(), "Steve, Alex, Ari, Efe, Kai, Makena, Noor, Sunny, Zuri");
        assertThrows(IllegalArgumentException.class, () -> NpcSkin.parse("default:herobrine"));
        assertThrows(IllegalArgumentException.class, () -> NpcSkin.parse("default:noor:tall"));
    }

    @Test
    void anAccountsSkinByItsName() {
        assertEquals(new NpcSkin.Account("Jeff_123"), NpcSkin.parse("player:Jeff_123"));
        assertThrows(IllegalArgumentException.class, () -> NpcSkin.parse("player:no"), "too short");
        assertThrows(IllegalArgumentException.class, () -> NpcSkin.parse("player:has space"));
        assertThrows(IllegalArgumentException.class, () -> NpcSkin.parse("player:waytoolongforaminecraftname"));
    }

    @Test
    void aPastedTextureMustBeASkinTexture() {
        NpcSkin t = NpcSkin.parse("texture:" + TEXTURE);
        assertInstanceOf(NpcSkin.Texture.class, t);
        assertNull(((NpcSkin.Texture) t).signature());
        NpcSkin signed = NpcSkin.parse("texture:" + TEXTURE + " SIGNATURE==");
        assertEquals("SIGNATURE==", ((NpcSkin.Texture) signed).signature());
        assertEquals(signed, NpcSkin.parse(signed.text()), "kept and read back the same");
        assertThrows(IllegalArgumentException.class, () -> NpcSkin.parse("texture:not-base64!"));
        String notASkin = Base64.getEncoder().encodeToString("{\"hello\":1}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> NpcSkin.parse("texture:" + notASkin),
                "base64, but not a skin on Minecraft's texture server: it would show a broken skin");
    }

    @Test
    void nonsenseIsRefusedAndAKeptValueThatCantBeReadFallsBackToItsOwnSkin() {
        for (String bad : new String[]{null, "", "noor", "skin:noor", "player:"}) {
            assertThrows(IllegalArgumentException.class, () -> NpcSkin.parse(bad), String.valueOf(bad));
        }
        NpcSkin.BuiltIn own = CpuRoster.byKey("wren").skin();
        assertSame(own, NpcSkin.read("default:herobrine", own), "an unreadable kept skin: the NPC's own");
        assertSame(own, NpcSkin.read(null, own), "nothing kept: its own");
        for (String text : new String[]{"default:kai:wide", "player:Someone", "texture:" + TEXTURE}) {
            assertEquals(text, NpcSkin.parse(text).text(), "round trip");
        }
    }
}
