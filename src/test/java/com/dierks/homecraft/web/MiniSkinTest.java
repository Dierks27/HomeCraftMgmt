package com.dierks.homecraft.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Mini's texture becomes the website's skin URL, or nothing.
 *
 * <p>The site draws a Mini's face only from {@code https://textures.minecraft.net/texture/<hex>};
 * every shipped value says {@code http://}, so the upgrade is the difference between faces and
 * placeholders on the whole Minis page. Pinned: a real shipped value decodes to the right hash
 * over https; the ways a value gets mangled on its way into config.yml (line breaks, lost
 * padding, the URL-safe alphabet) still decode; and everything that is not a Minecraft skin —
 * garbage, other JSON, another host, a path that is not a hash — gives no skin at all rather
 * than something the site would refuse or, worse, fetch.
 */
class MiniSkinTest {

    /** "Piggy Mini", exactly as src/main/resources/config.yml ships it. */
    private static final String PIGGY = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTlhOTIxMmQzNmMwY2E3ZjA1MzczNzZmYWI1Yjg0ZWNiN2FmNjZjZjY3NTRlN2I4N2Q4YjFhMDhkOWQ1NDAyYiJ9fX0=";
    private static final String PIGGY_HASH = "a9a9212d36c0ca7f0537376fab5b84ecb7af66cf6754e7b87d8b1a08d9d5402b";
    private static final String PIGGY_URL = "https://textures.minecraft.net/texture/" + PIGGY_HASH;

    private static String b64(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String skinJson(String url) {
        return "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
    }

    private static void none(String texture, String why) {
        assertEquals(Optional.empty(), MiniSkin.url(texture), why);
    }

    // ---- values that work ---------------------------------------------------------------

    @Test
    void aShippedValueBecomesItsHttpsSkinUrl() {
        assertEquals(Optional.of(PIGGY_URL), MiniSkin.url(PIGGY));
    }

    @Test
    void anAlreadyHttpsValueIsKeptAsItIs() {
        String url = "https://textures.minecraft.net/texture/" + PIGGY_HASH;
        assertEquals(Optional.of(url), MiniSkin.url(b64(skinJson(url))));
    }

    @Test
    void anUpperCaseSchemeIsUpgradedToo() {
        String url = "HTTP://textures.minecraft.net/texture/ABCdef0123";
        assertEquals(Optional.of("https://textures.minecraft.net/texture/ABCdef0123"),
                MiniSkin.url(b64(skinJson(url))));
    }

    @Test
    void whitespacePastedIntoTheValueIsIgnored() {
        String wrapped = "  " + PIGGY.substring(0, 40) + "\n" + PIGGY.substring(40, 90) + "\r\n\t"
                + PIGGY.substring(90, 120) + " " + PIGGY.substring(120) + "\n";
        assertEquals(Optional.of(PIGGY_URL), MiniSkin.url(wrapped));
    }

    @Test
    void missingPaddingStillDecodes() {
        assertTrue(PIGGY.endsWith("="));
        assertEquals(Optional.of(PIGGY_URL), MiniSkin.url(PIGGY.replace("=", "")));

        String two = b64(skinJson(PIGGY_URL) + " "); // a length that needs "==" padding
        assertTrue(two.endsWith("=="), two);
        assertEquals(Optional.of(PIGGY_URL), MiniSkin.url(two.replace("=", "")));
    }

    @Test
    void theUrlSafeAlphabetDecodes() {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + PIGGY_HASH
                + "\"}},\"note\":\"???>>>\"}";
        String urlSafe = Base64.getUrlEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertTrue(urlSafe.contains("-") || urlSafe.contains("_"),
                "the sample must actually use the URL-safe characters: " + urlSafe);
        assertEquals(Optional.of(PIGGY_URL), MiniSkin.url(urlSafe));
        assertEquals(Optional.of(PIGGY_URL), MiniSkin.url(urlSafe.replace("=", "")), "and without padding");
    }

    @Test
    void aFullMojangProfileValueWithExtraFieldsWorks() {
        String json = "{\n  \"timestamp\" : 1700000000000,\n  \"profileId\" : \"0123456789abcdef0123456789abcdef\",\n"
                + "  \"profileName\" : \"Someone\",\n  \"signatureRequired\" : true,\n  \"textures\" : {\n"
                + "    \"SKIN\" : {\n      \"url\" : \"http://textures.minecraft.net/texture/" + PIGGY_HASH + "\",\n"
                + "      \"metadata\" : { \"model\" : \"slim\" }\n    },\n"
                + "    \"CAPE\" : { \"url\" : \"http://textures.minecraft.net/texture/cafe\" }\n  }\n}";
        assertEquals(Optional.of(PIGGY_URL), MiniSkin.url(b64(json)));
    }

    @Test
    void spacesAroundTheUrlAreTrimmed() {
        assertEquals(Optional.of(PIGGY_URL), MiniSkin.url(b64(skinJson("  " + PIGGY_URL + "\\n"))));
    }

    // ---- values that do not -------------------------------------------------------------

    @Test
    void nullAndBlankHaveNoSkin() {
        none(null, "null");
        none("", "empty");
        none("   ", "spaces");
        none("\n\t ", "only whitespace");
    }

    @Test
    void garbageThatIsNotBase64HasNoSkin() {
        none("not base64 at all!!", "punctuation outside both alphabets");
        none("a", "a single leftover character is not a Base64 unit");
        none("eyJ0ZXh0dXJlcyI6*&^%", "a good start spoiled");
        none("ab+c-d", "the two alphabets mixed");
    }

    @Test
    void base64OfSomethingThatIsNotJsonHasNoSkin() {
        none(b64("hello world"), "plain text");
        none(b64("{\"textures\":"), "truncated JSON");
        none(Base64.getEncoder().encodeToString(new byte[] {(byte) 0xff, (byte) 0xfe, 0, 1, 2}), "binary");
    }

    @Test
    void jsonOfTheWrongShapeHasNoSkin() {
        none(b64("null"), "JSON null");
        none(b64("\"" + PIGGY_URL + "\""), "a bare string");
        none(b64("[" + skinJson(PIGGY_URL) + "]"), "an array around the right object");
        none(b64("{\"foo\":1}"), "no textures");
        none(b64("{\"textures\":\"" + PIGGY_URL + "\"}"), "textures not an object");
        none(b64("{\"textures\":{\"CAPE\":{\"url\":\"" + PIGGY_URL + "\"}}}"), "no SKIN");
        none(b64("{\"textures\":{\"skin\":{\"url\":\"" + PIGGY_URL + "\"}}}"), "the key is SKIN, upper case");
        none(b64("{\"textures\":{\"SKIN\":[\"" + PIGGY_URL + "\"]}}"), "SKIN not an object");
        none(b64("{\"textures\":{\"SKIN\":{}}}"), "no url");
        none(b64("{\"textures\":{\"SKIN\":{\"url\":null}}}"), "url null");
        none(b64("{\"textures\":{\"SKIN\":{\"url\":42}}}"), "url a number");
        none(b64("{\"textures\":{\"SKIN\":{\"url\":{\"href\":\"" + PIGGY_URL + "\"}}}}"), "url an object");
    }

    @Test
    void aUrlOnAnotherHostHasNoSkin() {
        none(b64(skinJson("https://evil.example/texture/" + PIGGY_HASH)), "another host");
        none(b64(skinJson("https://textures.minecraft.net.evil.example/texture/" + PIGGY_HASH)), "a lookalike host");
        none(b64(skinJson("https://education.minecraft.net/texture/" + PIGGY_HASH)), "a sibling host");
        none(b64(skinJson("https://user@textures.minecraft.net/texture/" + PIGGY_HASH)), "credentials");
        none(b64(skinJson("https://textures.minecraft.net:8443/texture/" + PIGGY_HASH)), "a port");
        none(b64(skinJson("ftp://textures.minecraft.net/texture/" + PIGGY_HASH)), "another scheme");
        none(b64(skinJson("//textures.minecraft.net/texture/" + PIGGY_HASH)), "protocol-relative");
        none(b64(skinJson("textures.minecraft.net/texture/" + PIGGY_HASH)), "no scheme");
    }

    @Test
    void aPathThatIsNotAHexHashHasNoSkin() {
        String base = "http://textures.minecraft.net/texture/";
        none(b64(skinJson(base)), "no hash");
        none(b64(skinJson(base + "xyz123")), "not hex");
        none(b64(skinJson(base + PIGGY_HASH + "?size=64")), "a query string");
        none(b64(skinJson(base + PIGGY_HASH + "#face")), "a fragment");
        none(b64(skinJson(base + PIGGY_HASH + "/")), "a trailing slash");
        none(b64(skinJson(base + "../skins/" + PIGGY_HASH)), "a path climb");
        none(b64(skinJson(base + "abc def")), "a space inside");
        none(b64(skinJson("http://textures.minecraft.net/skin/" + PIGGY_HASH)), "another path");
        none(b64(skinJson(base + PIGGY_HASH + "\\n" + base + PIGGY_HASH)), "two URLs on two lines");
    }
}
