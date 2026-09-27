package com.dierks.homecraft.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The skin URL inside a Mini's head texture, for the {@code /api/minis} feed.
 *
 * <p>A texture is the minecraft-heads "Value": Base64 of
 * {@code {"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/<hash>"}}}}. The
 * website draws each Mini's face from that skin, but only accepts
 * {@code https://textures.minecraft.net/texture/<hex>} — so the URL is upgraded to https
 * (every shipped value says http) and anything else is dropped rather than passed on: a Mini
 * whose texture is blank, mangled, or points somewhere else simply has no {@code skin} and
 * the site draws its placeholder.
 *
 * <p>Tolerant of the ways a value gets pasted into config.yml: line breaks or spaces inside
 * it, the {@code =} padding lost, the URL-safe alphabet. Never throws.
 *
 * <p>No Bukkit — Gson only (Paper ships it) — so it is unit-tested without a server.
 */
public final class MiniSkin {

    /** The only skin URL shape the website accepts. */
    private static final Pattern SKIN_URL =
            Pattern.compile("https://textures\\.minecraft\\.net/texture/[0-9a-fA-F]+");

    private static final String HTTP = "http://";

    private MiniSkin() {
    }

    /**
     * The https skin URL in {@code texture}, or empty when it is blank, is not Base64, is not
     * a {@code textures.SKIN.url} JSON object, or names anything but
     * {@code https://textures.minecraft.net/texture/<hex>} once {@code http://} is upgraded.
     */
    public static Optional<String> url(String texture) {
        if (texture == null || texture.isBlank()) {
            return Optional.empty();
        }
        byte[] decoded = decode(withoutWhitespace(texture));
        if (decoded == null) {
            return Optional.empty();
        }
        String raw;
        try {
            raw = skinUrl(JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            // JsonParseException for text that is not JSON; the rest for a tree of the wrong shape.
            return Optional.empty();
        }
        if (raw == null) {
            return Optional.empty();
        }
        String url = raw.trim();
        if (url.regionMatches(true, 0, HTTP, 0, HTTP.length())) {
            url = "https://" + url.substring(HTTP.length());
        }
        return SKIN_URL.matcher(url).matches() ? Optional.of(url) : Optional.empty();
    }

    private static String withoutWhitespace(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Standard Base64 (padding optional), else the URL-safe alphabet, else null. */
    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException notStandard) {
            try {
                return Base64.getUrlDecoder().decode(base64);
            } catch (IllegalArgumentException notUrlSafe) {
                return null;
            }
        }
    }

    /** {@code root.textures.SKIN.url} as a string, or null at the first missing or mistyped step. */
    private static String skinUrl(JsonElement root) {
        JsonObject skin = child(child(root, "textures"), "SKIN");
        if (skin == null) {
            return null;
        }
        JsonElement url = skin.get("url");
        if (url == null || !url.isJsonPrimitive() || !url.getAsJsonPrimitive().isString()) {
            return null;
        }
        return url.getAsString();
    }

    private static JsonObject child(JsonElement parent, String key) {
        if (parent == null || !parent.isJsonObject()) {
            return null;
        }
        JsonElement value = parent.getAsJsonObject().get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }
}
