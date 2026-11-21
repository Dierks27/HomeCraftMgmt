package com.dierks.homecraft.games.cpu;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * What an NPC wears (the owner: "Can I pick some of the Skins?"): one of Minecraft's built-in default skins, the skin of
 * a Minecraft Java account by its name, or a raw skin texture pasted from a skin site. Pure: parsed from what the owner
 * types and kept as one line of text ({@link #text}); putting it on a mannequin, and falling back to the NPC's own
 * built-in skin when it can't be had, is {@code ClubRegulars}'.
 *
 * <p>Nothing here looks anything up: a built-in skin is a texture every client already has, so it can never come out
 * blank; an account's skin is fetched by the server when it is used, and a pasted texture is checked here to be a skin
 * texture before it is ever kept.
 */
public sealed interface NpcSkin {

    /** Minecraft's built-in default skins. */
    List<String> BUILT_IN = List.of("steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri");

    /** The longest texture value kept (a real one is well under 1000 characters). */
    int MAX_TEXTURE = 4096;

    /** The line it is kept as: {@code default:noor:slim}, {@code player:Name}, {@code texture:<value>|<signature>}. */
    String text();

    /** What the admin reads: "Noor (slim)", "the skin of Name", "a pasted texture". */
    String describe();

    /**
     * One of the built-in skins.
     *
     * @param name one of {@link #BUILT_IN}
     * @param slim the slim (three-pixel arms) model, else the wide one
     */
    record BuiltIn(String name, boolean slim) implements NpcSkin {

        public BuiltIn {
            if (!BUILT_IN.contains(name)) {
                throw new IllegalArgumentException("not a built-in skin: " + name);
            }
        }

        /** The texture's path in the game's own resources: {@code entity/player/slim/noor}. */
        public String texturePath() {
            return "entity/player/" + (slim ? "slim" : "wide") + "/" + name;
        }

        @Override
        public String text() {
            return "default:" + name + ":" + (slim ? "slim" : "wide");
        }

        @Override
        public String describe() {
            return Character.toUpperCase(name.charAt(0)) + name.substring(1) + " (" + (slim ? "slim" : "wide") + ")";
        }
    }

    /** The skin of a Minecraft Java account, by its name. */
    record Account(String name) implements NpcSkin {

        public Account {
            if (!validName(name)) {
                throw new IllegalArgumentException("not a Minecraft name: " + name);
            }
        }

        @Override
        public String text() {
            return "player:" + name;
        }

        @Override
        public String describe() {
            return "the skin of " + name;
        }
    }

    /**
     * A skin texture pasted from a skin site: its {@code textures} value (base64 of the texture's JSON) and, if the
     * site gave one, its signature.
     */
    record Texture(String value, String signature) implements NpcSkin {

        public Texture {
            if (!validTexture(value)) {
                throw new IllegalArgumentException("not a skin texture");
            }
            signature = signature == null || signature.isBlank() ? null : signature.trim();
        }

        @Override
        public String text() {
            return "texture:" + value + (signature == null ? "" : "|" + signature);
        }

        @Override
        public String describe() {
            return "a pasted texture" + (signature == null ? "" : " (signed)");
        }
    }

    /** Whether {@code name} could be a Minecraft Java account's name: 3-16 letters, digits or underscores. */
    static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{3,16}");
    }

    /**
     * Whether {@code value} is a skin texture: base64 of JSON naming a {@code SKIN} on Minecraft's texture server. A
     * value that isn't would show a broken skin, so it is never kept.
     */
    static boolean validTexture(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXTURE) {
            return false;
        }
        try {
            String json = new String(Base64.getDecoder().decode(value.trim()), StandardCharsets.UTF_8);
            return json.contains("\"SKIN\"") && json.contains("textures.minecraft.net/texture/");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * What the owner typed (or what was kept): {@code default:<name>} (optionally {@code :slim} or {@code :wide}, or
     * {@code <name>_slim}), {@code player:<name>}, or {@code texture:<value>} with the signature after a space or a
     * {@code |}.
     *
     * @throws IllegalArgumentException with a line for the admin when it is none of those
     */
    static NpcSkin parse(String typed) {
        if (typed == null || typed.isBlank()) {
            throw new IllegalArgumentException("say which skin: default:<name>, player:<name> or texture:<value>");
        }
        String t = typed.trim();
        int colon = t.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("start with default:, player: or texture:");
        }
        String kind = t.substring(0, colon).toLowerCase(Locale.ROOT);
        String rest = t.substring(colon + 1).trim();
        switch (kind) {
            case "default" -> {
                String[] p = rest.toLowerCase(Locale.ROOT).split("[:\\s]+");
                String name = p[0];
                boolean slim = false;
                if (name.endsWith("_slim")) {
                    name = name.substring(0, name.length() - 5);
                    slim = true;
                } else if (name.endsWith("_wide")) {
                    name = name.substring(0, name.length() - 5);
                }
                if (p.length > 1) {
                    if (p[1].equals("slim")) {
                        slim = true;
                    } else if (!p[1].equals("wide")) {
                        throw new IllegalArgumentException("a built-in skin is wide or slim, not " + p[1]);
                    }
                }
                if (!BUILT_IN.contains(name)) {
                    throw new IllegalArgumentException("the built-in skins are " + String.join(", ", BUILT_IN));
                }
                return new BuiltIn(name, slim);
            }
            case "player" -> {
                if (!validName(rest)) {
                    throw new IllegalArgumentException("a Minecraft name is 3-16 letters, digits or _");
                }
                return new Account(rest);
            }
            case "texture" -> {
                String[] p = rest.split("[|\\s]+", 2);
                if (!validTexture(p[0])) {
                    throw new IllegalArgumentException("that isn't a skin texture value (copy the long \"Value\" from"
                            + " the skin site)");
                }
                return new Texture(p[0].trim(), p.length > 1 ? p[1] : null);
            }
            default -> throw new IllegalArgumentException("start with default:, player: or texture:");
        }
    }

    /** What was kept, or {@code fallback} when it can't be read (a value from an older or hand-edited store). */
    static NpcSkin read(String kept, NpcSkin fallback) {
        try {
            return kept == null ? fallback : parse(kept);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
