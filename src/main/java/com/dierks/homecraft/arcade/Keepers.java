package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.TrailService.Puff;
import com.dierks.homecraft.arcade.TrailService.Step;
import com.dierks.homecraft.arcade.TrailService.TrailPattern;
import com.dierks.homecraft.config.PluginConfig.Icon;
import com.dierks.homecraft.config.PluginConfig.Keeper;
import com.dierks.homecraft.config.PluginConfig.KeeperLook;
import com.dierks.homecraft.config.PluginConfig.KeeperSwitches;
import com.dierks.homecraft.config.PluginConfig.LimitPer;
import com.dierks.homecraft.config.PluginConfig.OnlyIf;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeExtra;
import com.dierks.homecraft.config.PluginConfig.PrizeLimit;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import org.bukkit.Material;
import org.bukkit.Particle;

import java.util.List;
import java.util.Locale;

/**
 * The Keepers (SHOP-V2-SPEC §5.1): looks and trails kept for good, one each, never given or traded, bound to
 * their owner, on the Prize Counter's Keepers tab. Append only.
 *
 * <p>Like the collectors ({@link Collectors}), and for the same reason, they live here in the jar rather than as
 * {@code arcade.prizes} rows: an owned Keeper must never lose its row, and its price and look can't be edited.
 * Every id starts {@code keeper_}, and no config row may use one ({@link #reserved}).
 *
 * <p>Each Keeper ships two forms (§5.2, §5.3): a worn one's trim (the Crown's glint too), a trail's steps. The owner
 * picks one per Keeper after looking at them on both screens ({@code arcade.keepers.looks.<id>: default | fallback},
 * read at enable, {@link #variant}); only a shipped form can be picked, so nothing else about a Keeper is
 * configurable. A worn Keeper also shows a <b>mark</b> (§5.3a): one puff of its own particle above the head every
 * two seconds while it is worn and walking, the one thing about it nobody can craft ({@code arcade.keepers.marks}).
 * Every particle a Keeper draws is reserved ({@link #RESERVED_PARTICLES}): a config trail using one loads with a WARN.
 */
public final class Keepers {

    private Keepers() {
    }

    /** What every Keeper id starts with, and no config row's may. */
    public static final String PREFIX = "keeper_";

    public static final String CROWN = "keeper_legend_crown";
    public static final String ROYAL = "keeper_trail_royal";
    public static final String AURORA = "keeper_trail_aurora";
    public static final String STARDUST = "keeper_trail_stardust";
    public static final String FROST = "keeper_trail_frost";
    public static final String WIZARD = "keeper_wizard_hat";
    public static final String KNIGHT = "keeper_knight_helm";

    /** Every Keeper id ever shipped. Never remove or rename one: an owned Keeper resolves only while its row exists. */
    public static final List<String> SHIPPED_IDS = List.of(CROWN, ROYAL, AURORA, STARDUST, FROST, WIZARD, KNIGHT);

    /** A Keeper is kept for good: 100 years, the collectors' figure, never a Long.MAX_VALUE that overflows. */
    static final int KEPT_DAYS = 36500;

    /** A worn Keeper's mark puffs at most once in this many milliseconds (a trail every 150: about 13 times sparser). */
    public static final long MARK_MS = 2_000;

    /** Which of a Keeper's two shipped forms the config picks ({@code arcade.keepers.looks.<id>}). */
    public enum Variant {
        DEFAULT("default"), FALLBACK("fallback");

        private final String key;

        Variant(String key) {
            this.key = key;
        }

        /** The word in config.yml and on a key ({@code look_variant}). */
        public String key() {
            return key;
        }

        /** {@code default} or {@code fallback} (any case, trimmed); null for anything else. */
        public static Variant parse(String raw) {
            if (raw == null) {
                return null;
            }
            String n = raw.trim().toLowerCase(Locale.ROOT);
            for (Variant v : values()) {
                if (v.key.equals(n)) {
                    return v;
                }
            }
            return null;
        }
    }

    // ---- the worn looks (§5.2) ------------------------------------------------------------------------

    /**
     * The Legend Crown: gold-dyed leather with a spire trim and a glint, and for Java the golden helmet's equipment
     * asset and model, so it draws as metal (a leather item, so piglins don't take it for gold). Bedrock draws the
     * gold-dyed leather with its trim and glint.
     */
    static final KeeperLook CROWN_LOOK = new KeeperLook(Material.LEATHER_HELMET, 0xE8B931, "spire", "redstone",
            "minecraft:gold", "minecraft:golden_helmet", true);
    /** The Crown's other form: a diamond spire trim, and no glint. */
    static final KeeperLook CROWN_FALLBACK = new KeeperLook(Material.LEATHER_HELMET, 0xE8B931, "spire", "diamond",
            "minecraft:gold", "minecraft:golden_helmet", false);
    /** The Wizard Hat: midnight-blue leather with golden flow swirls. */
    static final KeeperLook WIZARD_LOOK = new KeeperLook(Material.LEATHER_HELMET, 0x2A2F6E, "flow", "gold", null, null,
            false);
    static final KeeperLook WIZARD_FALLBACK = new KeeperLook(Material.LEATHER_HELMET, 0x2A2F6E, "silence", "amethyst",
            null, null, false);
    /** The Knight's Helm: an iron helmet with a gold sentry trim and no armour at all (EMPTY attributes). */
    static final KeeperLook KNIGHT_LOOK = new KeeperLook(Material.IRON_HELMET, -1, "sentry", "gold", null, null, false);
    static final KeeperLook KNIGHT_FALLBACK = new KeeperLook(Material.IRON_HELMET, -1, "ward", "gold", null, null,
            false);

    // ---- the trail patterns (§5.3) and the marks (§5.3a) -----------------------------------------------

    /** Just over the crown, out of the eye line (red team O17). */
    private static final Puff ROYAL_PUFF = new Puff(1.9, 1, 0.2, 0.05, 0.2);

    static final TrailPattern ROYAL_PATTERN = new TrailPattern(300, List.of(
            new Step(Particle.TOTEM_OF_UNDYING, ROYAL_PUFF)));
    /** Gold drips from the crown. */
    static final TrailPattern ROYAL_FALLBACK = new TrailPattern(300, List.of(
            new Step(Particle.FALLING_HONEY, ROYAL_PUFF)));
    static final TrailPattern AURORA_PATTERN = new TrailPattern(150, List.of(
            new Step(Particle.SCRAPE, new Puff(1.7, 1, 0.35, 0.15, 0.35)),
            new Step(Particle.REVERSE_PORTAL, new Puff(1.5, 1, 0.3, 0.1, 0.3))));
    static final TrailPattern AURORA_FALLBACK = new TrailPattern(150, List.of(
            new Step(Particle.SCRAPE, new Puff(1.7, 1, 0.35, 0.15, 0.35)),
            new Step(Particle.PORTAL, new Puff(1.5, 1, 0.3, 0.1, 0.3))));
    static final TrailPattern STARDUST_PATTERN = new TrailPattern(150, List.of(
            new Step(Particle.WAX_ON, new Puff(1.3, 1, 0.3, 0.2, 0.3)),
            new Step(Particle.WAX_ON, new Puff(0.8, 1, 0.3, 0.2, 0.3)),
            new Step(Particle.CRIT, new Puff(1.0, 1, 0.25, 0.25, 0.25))));
    /** CRIT is an emitter on Bedrock and may burst on her screen; nectar drifts down instead. */
    static final TrailPattern STARDUST_FALLBACK = new TrailPattern(150, List.of(
            new Step(Particle.WAX_ON, new Puff(1.3, 1, 0.3, 0.2, 0.3)),
            new Step(Particle.WAX_ON, new Puff(0.8, 1, 0.3, 0.2, 0.3)),
            new Step(Particle.FALLING_NECTAR, new Puff(1.0, 1, 0.25, 0.25, 0.25))));
    static final TrailPattern FROST_PATTERN = new TrailPattern(150, List.of(
            new Step(Particle.DOLPHIN, new Puff(0.15, 2, 0.2, 0.05, 0.2)),
            new Step(Particle.ITEM_SNOWBALL, new Puff(0.1, 1, 0.2, 0.02, 0.2))));
    static final TrailPattern FROST_FALLBACK = new TrailPattern(150, List.of(
            new Step(Particle.ITEM_SNOWBALL, new Puff(0.1, 1, 0.2, 0.02, 0.2))));

    /** The Wizard Hat's mark: a witch's sparkle just above the hat. */
    static final Step WIZARD_MARK = new Step(Particle.WITCH, new Puff(2.1, 1, 0.15, 0.02, 0.15));
    /** The Knight's Helm's mark: a tiny flame on its crest. */
    static final Step KNIGHT_MARK = new Step(Particle.SMALL_FLAME, new Puff(2.05, 1, 0.05, 0.01, 0.05));

    /**
     * Every particle a Keeper draws (twelve): the patterns' seven, the three fallbacks and the two marks. A config
     * trail row using one still loads, with a WARN ({@link #reservedWarning}); no shipped rental or collector uses one.
     */
    public static final List<Particle> RESERVED_PARTICLES = List.of(
            Particle.TOTEM_OF_UNDYING, Particle.SCRAPE, Particle.REVERSE_PORTAL, Particle.WAX_ON, Particle.CRIT,
            Particle.DOLPHIN, Particle.ITEM_SNOWBALL,
            Particle.FALLING_HONEY, Particle.PORTAL, Particle.FALLING_NECTAR,
            Particle.WITCH, Particle.SMALL_FLAME);

    // ---- the rows (§5.1) ------------------------------------------------------------------------------

    /** The Keepers' rows (tab KEEPERS), merged into the Prize Counter before the collectors. */
    public static final List<Prize> ROWS = List.of(
            hat(CROWN, "&6&lLegend Crown", List.of("The rarest look of all.", "Comes with its own Royal Trail."),
                    5000, Material.GOLDEN_HELMET, new Keeper(CROWN_LOOK, false, ROYAL, null)),
            trail(ROYAL, "&6Royal Trail", List.of("The Legend Crown's own trail."), 0, Material.GOLD_INGOT,
                    ROYAL_PATTERN, true),
            trail(AURORA, "&aAurora Trail", List.of("Green and purple lights float", "up around you."), 3000,
                    Material.AMETHYST_CLUSTER, AURORA_PATTERN, false),
            trail(STARDUST, "&eStardust Trail", List.of("Golden sparkles drift down", "behind you."), 3000,
                    Material.GOLD_NUGGET, STARDUST_PATTERN, false),
            trail(FROST, "&bFrost Trail", List.of("Icy sparkles and snow", "at your feet."), 3000,
                    Material.BLUE_ICE, FROST_PATTERN, false),
            hat(WIZARD, "&5Wizard Hat", List.of("A midnight hat with", "golden swirls.", "A little magic floats above it."),
                    2500, Material.LEATHER_HELMET, new Keeper(WIZARD_LOOK, false, null, Particle.WITCH)),
            hat(KNIGHT, "&7Knight's Helm", List.of("A shining knight's helmet.", "Just for looks: no armour.",
                            "A tiny flame on its crest."),
                    2500, Material.IRON_HELMET, new Keeper(KNIGHT_LOOK, false, null, Particle.SMALL_FLAME)));

    /** A worn Keeper: a HAT row on the Keepers tab, one each for good, its look in {@code keeper}. */
    private static Prize hat(String id, String display, List<String> desc, int price, Material icon, Keeper keeper) {
        return new Prize(id, PrizeTab.KEEPERS, display, desc, price, new Icon("", icon), PrizeType.HAT, true,
                new PrizeLimit(LimitPer.LIFETIME, 1), 1, null, null, null, null, 0, 0, null, KEPT_DAYS, "",
                List.of(), List.of(), null, null, List.of(), null,
                new PrizeExtra(null, 0, null, OnlyIf.ANY, 0, keeper));
    }

    /**
     * A Keeper trail: a TRAIL row on the Keepers tab, one each for good. Its {@code particle} is its pattern's first
     * step (what the shelf's "can this server show it" check reads); the pattern itself is {@link #pattern}'s. A
     * companion (the Royal Trail) comes with another Keeper and is never a tile.
     */
    private static Prize trail(String id, String display, List<String> desc, int price, Material icon,
                               TrailPattern pattern, boolean companion) {
        return new Prize(id, PrizeTab.KEEPERS, display, desc, price, new Icon("", icon), PrizeType.TRAIL, true,
                new PrizeLimit(LimitPer.LIFETIME, 1), 1, null, null, null, null, 0, 0,
                pattern.steps().get(0).particle().name(), KEPT_DAYS, "",
                List.of(), List.of(), null, null, List.of(), null,
                new PrizeExtra(null, 0, null, OnlyIf.ANY, 0, new Keeper(null, companion, null, null)));
    }

    // ---- lookups ------------------------------------------------------------------------------------

    /** One Keeper row by id (case-insensitive), or null. */
    public static Prize row(String id) {
        if (id == null) {
            return null;
        }
        for (Prize p : ROWS) {
            if (p.id().equalsIgnoreCase(id.trim())) {
                return p;
            }
        }
        return null;
    }

    /** The form the config picks for a Keeper ({@code arcade.keepers.looks.<id>}); DEFAULT when none is set. */
    public static Variant variant(KeeperSwitches switches, String id) {
        if (switches == null || id == null) {
            return Variant.DEFAULT;
        }
        Variant v = Variant.parse(switches.looks().get(id.trim().toLowerCase(Locale.ROOT)));
        return v == null ? Variant.DEFAULT : v;
    }

    /** A worn Keeper's look in one form, or null for a trail (or an id that isn't a worn Keeper). */
    public static KeeperLook look(String id, Variant v) {
        if (id == null) {
            return null;
        }
        boolean fallback = v == Variant.FALLBACK;
        return switch (id.trim().toLowerCase(Locale.ROOT)) {
            case CROWN -> fallback ? CROWN_FALLBACK : CROWN_LOOK;
            case WIZARD -> fallback ? WIZARD_FALLBACK : WIZARD_LOOK;
            case KNIGHT -> fallback ? KNIGHT_FALLBACK : KNIGHT_LOOK;
            default -> null;
        };
    }

    /** A Keeper trail's pattern in one form, or null for an id that isn't a Keeper trail. */
    public static TrailPattern pattern(String id, Variant v) {
        if (id == null) {
            return null;
        }
        boolean fallback = v == Variant.FALLBACK;
        return switch (id.trim().toLowerCase(Locale.ROOT)) {
            case ROYAL -> fallback ? ROYAL_FALLBACK : ROYAL_PATTERN;
            case AURORA -> fallback ? AURORA_FALLBACK : AURORA_PATTERN;
            case STARDUST -> fallback ? STARDUST_FALLBACK : STARDUST_PATTERN;
            case FROST -> fallback ? FROST_FALLBACK : FROST_PATTERN;
            default -> null;
        };
    }

    /**
     * A worn Keeper's mark (§5.3a): the Wizard Hat's witch sparkle, the Knight's Helm's flame, and for the Crown its
     * Royal Trail's own step in the form the Royal Trail is set to ({@code royal}). Null for a trail or any other id.
     * A mark has no fallback of its own.
     */
    public static Step mark(String id, Variant royal) {
        if (id == null) {
            return null;
        }
        return switch (id.trim().toLowerCase(Locale.ROOT)) {
            case CROWN -> pattern(ROYAL, royal).steps().get(0);
            case WIZARD -> WIZARD_MARK;
            case KNIGHT -> KNIGHT_MARK;
            default -> null;
        };
    }

    /** True for an id the catalogue ships (case-insensitive). */
    public static boolean isKeeperId(String id) {
        if (id == null) {
            return false;
        }
        for (String shipped : SHIPPED_IDS) {
            if (shipped.equalsIgnoreCase(id.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * True for an id no config row may use: any that starts {@code keeper_} (case-insensitive), shipped or not,
     * so a Keeper added later can never meet an owner's row of the same id. An item whose prize id is one is
     * bound to its owner ({@link PrizeService#bound}).
     */
    public static boolean reserved(String id) {
        return id != null && id.trim().toLowerCase(Locale.ROOT).startsWith(PREFIX);
    }

    /** The one warning for a config row {@link #reserved} skips. */
    public static String skipWarning(String id) {
        return "Arcade prize '" + id + "' has an id the plugin keeps for its Keepers (keeper_...) - your row is "
                + "skipped (Keepers are built in).";
    }

    /** True for a particle name (any case) one of the Keepers draws ({@link #RESERVED_PARTICLES}). */
    public static boolean reservedParticle(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String n = name.trim().toUpperCase(Locale.ROOT);
        for (Particle p : RESERVED_PARTICLES) {
            if (p.name().equals(n)) {
                return true;
            }
        }
        return false;
    }

    /** The one warning for a config trail row drawn with a Keeper's particle: it still loads. */
    public static String reservedWarning(String id, String particle) {
        return "Arcade prize '" + id + "' is a trail of " + particle + ", which is reserved for a Keeper - it loads, "
                + "but a Keeper's look should be its own. Pick another particle, such as HEART or END_ROD.";
    }
}
