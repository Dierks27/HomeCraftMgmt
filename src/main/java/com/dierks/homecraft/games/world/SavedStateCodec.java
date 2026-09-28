package com.dierks.homecraft.games.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Everything about a saved player state that needs no server (spec §7.3): the potion-effect text,
 * the return point, and the ORDER in which a snapshot is put back on a player or a player is
 * cleared for a game.
 *
 * <p>The order is the part that matters. Restoring sets the game mode first (changing it resets
 * flight, and a game-mode profile in Multiverse-Inventories would otherwise swap items in under
 * us), then the effects (Health Boost raises max health), then health clamped to the max health as
 * it now stands, absorption, food, XP, speeds, flight, fire and air, and the inventory last.
 * Clearing for a game sets ADVENTURE before touching the inventory for the same reason. The Bukkit
 * adapter only implements {@link Body} over a {@code Player}; the order lives here, where a test
 * can hold it still.
 *
 * <p>The item blobs ({@code items}, {@code carry}) are {@code ItemStack.serializeItemsAsBytes} and
 * need a server; they are the adapter's.
 */
public final class SavedStateCodec {

    /** An effect that never runs out ({@code PotionEffect.INFINITE_DURATION}). */
    public static final int INFINITE = -1;

    /** Vanilla's full food bar, and the saturation a fresh player starts with. */
    static final int FULL_FOOD = 20;
    static final float FRESH_SATURATION = 5f;
    /** Vanilla's default walk and fly speeds, for a saved speed that can't be read. */
    static final float WALK = 0.2f;
    static final float FLY = 0.1f;

    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private SavedStateCodec() {
    }

    /**
     * One potion effect as saved: {@code key|amplifier|duration|ambient|particles|icon}.
     *
     * @param key      the effect's namespaced key ({@code minecraft:speed})
     * @param duration ticks left, or {@link #INFINITE}
     */
    public record Effect(String key, int amplifier, int duration, boolean ambient, boolean particles, boolean icon) {
    }

    // ---- effects --------------------------------------------------------------------------------

    /** The effects as text, one per line; {@code ""} for none. An effect that can't be written is left out. */
    public static String encodeEffects(List<Effect> effects) {
        if (effects == null || effects.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Effect e : effects) {
            if (e == null || e.key() == null || !KEY.matcher(e.key()).matches()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(e.key()).append('|').append(e.amplifier()).append('|').append(e.duration()).append('|')
                    .append(e.ambient()).append('|').append(e.particles()).append('|').append(e.icon());
        }
        return out.toString();
    }

    /**
     * The effects back from text. A line that can't be read (wrong shape, a bad key, a number
     * that isn't one) is skipped, so one bad line never costs the player the rest.
     */
    public static List<Effect> decodeEffects(String text) {
        List<Effect> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String raw : text.split("\r?\n")) {
            Effect e = line(raw.trim());
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    private static Effect line(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length != 6) {
            return null;
        }
        String key = parts[0].toLowerCase(Locale.ROOT);
        if (!KEY.matcher(key).matches()) {
            return null;
        }
        try {
            int amplifier = Integer.parseInt(parts[1]);
            int duration = Integer.parseInt(parts[2]);
            if (amplifier < 0 || amplifier > 255 || (duration < 1 && duration != INFINITE)) {
                return null;
            }
            Boolean ambient = bool(parts[3]);
            Boolean particles = bool(parts[4]);
            Boolean icon = bool(parts[5]);
            if (ambient == null || particles == null || icon == null) {
                return null;
            }
            return new Effect(key, amplifier, duration, ambient, particles, icon);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean bool(String s) {
        return "true".equals(s) ? Boolean.TRUE : "false".equals(s) ? Boolean.FALSE : null;
    }

    // ---- the return point ------------------------------------------------------------------------

    /** Where the player came from (the row's {@code world, x, y, z, yaw, pitch}), or {@code null}. */
    static Place from(SavedState s) {
        if (s == null || s.world() == null || s.world().isBlank()) {
            return null;
        }
        return new Place(s.world(), s.x(), s.y(), s.z(), s.yaw(), s.pitch());
    }

    // ---- applying and clearing -------------------------------------------------------------------

    /** What restoring or clearing a player needs. The Bukkit adapter implements it over a Player. */
    public interface Body {

        /** Change the game mode; the change is flagged as the sessions' own, so the guard lets it through. */
        void gameMode(String mode);

        void clearEffects();

        /** Add one effect; an effect whose key the server doesn't know is skipped. */
        void addEffect(Effect effect);

        /** The MAX_HEALTH attribute's value as it stands now. */
        double maxHealth();

        void health(double health);

        /** The MAX_ABSORPTION attribute's value as it stands now. */
        double maxAbsorption();

        void absorption(double amount);

        void food(int level, float saturation, float exhaustion);

        void xp(int level, float progress, int total);

        void speeds(float walk, float fly);

        void flight(boolean allowFlight, boolean flying);

        void fire(int ticks);

        void air(int ticks);

        /** Put the snapshot's inventory back, overwriting every slot (storage, armour, off-hand). */
        void contents();

        /** Empty every slot (storage, armour, off-hand) and the cursor. */
        void clearContents();
    }

    /**
     * Put {@code s} back on {@code body}, overwriting: game mode, effects, health (clamped to max
     * health once the effects are back), absorption, food, XP, speeds, flight, fire, air, and the
     * inventory last.
     */
    public static void apply(SavedState s, List<Effect> effects, Body body) {
        body.gameMode(s.gameMode() == null || s.gameMode().isBlank() ? "SURVIVAL" : s.gameMode());
        body.clearEffects();
        for (Effect e : effects) {
            body.addEffect(e);
        }
        double max = body.maxHealth();
        double health = s.health();
        body.health(!Double.isFinite(health) || health <= 0 ? max : Math.min(health, max));
        double absorption = s.absorption();
        body.absorption(!Double.isFinite(absorption) || absorption < 0 ? 0 : Math.min(absorption, body.maxAbsorption()));
        int food = Math.max(0, Math.min(FULL_FOOD, s.food()));
        body.food(food, clamp(s.saturation(), 0f, food, FRESH_SATURATION), clamp(s.exhaustion(), 0f, 40f, 0f));
        body.xp(Math.max(0, s.xpLevel()), clamp(s.xpProgress(), 0f, 1f, 0f), Math.max(0, s.xpTotal()));
        body.speeds(clamp(s.walkSpeed(), -1f, 1f, WALK), clamp(s.flySpeed(), -1f, 1f, FLY));
        body.flight(s.allowFlight(), s.allowFlight() && s.flying());
        body.fire(s.fireTicks());
        body.air(s.air());
        body.contents();
    }

    /**
     * Ready a player for a world game: ADVENTURE first (our own change, before anything else, so
     * no game-mode profile can swap items in), then an empty inventory, no effects, no fire, no
     * flight, full health and food, and no XP. Speeds, attributes and air are left alone — a world
     * session never changes them.
     */
    public static void clearForGame(Body body) {
        body.gameMode("ADVENTURE");
        body.clearContents();
        body.clearEffects();
        body.fire(0);
        body.flight(false, false);
        body.health(body.maxHealth());
        body.food(FULL_FOOD, FRESH_SATURATION, 0f);
        body.xp(0, 0f, 0);
    }

    private static float clamp(float v, float lo, float hi, float fallback) {
        if (!Float.isFinite(v)) {
            return fallback;
        }
        return Math.max(lo, Math.min(hi, v));
    }
}
