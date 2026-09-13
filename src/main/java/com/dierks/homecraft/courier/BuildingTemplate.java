package com.dierks.homecraft.courier;

import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One building the Courier can put at the far end of a delivery.
 *
 * <p>Two sources, one shape. A {@code VANILLA} template names a structure key Mojang already
 * ships and maintains per biome — correct-looking houses for nothing, with no schematic files
 * in the jar. A {@code CUSTOM} template names an {@code .nbt} file in the plugin's folder, for
 * builds commissioned later. Everything downstream — rotation, terrain check, door scan,
 * snapshot, restore — is identical for both, so adding a commissioned house is a config edit
 * and never a code change.
 *
 * <p>There is deliberately no footprint setting. Every template is loaded before it is placed
 * and {@code Structure.getSize()} reports its real dimensions, so a hand-written size could
 * only ever be a second answer to a question already answered correctly — and a wrong one
 * would size the snapshot too small and leave part of a building behind.
 */
public record BuildingTemplate(Source source, String key, int weight, List<BiomeGroup> biomes) {

    public enum Source { VANILLA, CUSTOM }

    /**
     * Biome families that share a village style.
     *
     * <p>Mojang ships five village sets and no more, so jungle, swamp, mangrove, mushroom and
     * every other biome fall to {@link #PLAINS}. That is not a gap to fill — a plains cottage
     * in a swamp reads as somebody's outpost, which is exactly what a delivery drop is.
     */
    public enum BiomeGroup {
        PLAINS, DESERT, SAVANNA, TAIGA, SNOWY;

        /**
         * The village family for a biome, matched on its name.
         *
         * <p>Matched by name rather than against {@code Biome} constants on purpose: the biome
         * registry gained and lost members across recent versions, and a hard reference to one
         * that no longer exists fails the class at load. A name this doesn't recognise is a
         * plains delivery, which is always a valid answer.
         */
        public static BiomeGroup of(String biomeName) {
            String n = String.valueOf(biomeName).toLowerCase(Locale.ROOT);
            if (n.contains("desert") || n.contains("badlands") || n.contains("mesa")) {
                return DESERT;
            }
            if (n.contains("savanna") || n.contains("plateau")) {
                return SAVANNA;
            }
            if (n.contains("snow") || n.contains("frozen") || n.contains("ice")
                    || n.contains("cold_ocean")) {
                return SNOWY;
            }
            if (n.contains("taiga") || n.contains("grove") || n.contains("windswept")
                    || n.contains("old_growth")) {
                return TAIGA;
            }
            return PLAINS;
        }

        /** The trade the recipient practises here. Flavour, and a hint at where you are. */
        public Villager.Profession profession() {
            return switch (this) {
                case DESERT -> Villager.Profession.LEATHERWORKER;
                case SAVANNA -> Villager.Profession.SHEPHERD;
                case TAIGA -> Villager.Profession.FLETCHER;
                case SNOWY -> Villager.Profession.LIBRARIAN;
                case PLAINS -> Villager.Profession.FARMER;
            };
        }

        /** The village style villagers here are born into, so the skin matches the house. */
        public Villager.Type villagerType() {
            return switch (this) {
                case DESERT -> Villager.Type.DESERT;
                case SAVANNA -> Villager.Type.SAVANNA;
                case TAIGA -> Villager.Type.TAIGA;
                case SNOWY -> Villager.Type.SNOW;
                case PLAINS -> Villager.Type.PLAINS;
            };
        }
    }

    /**
     * The shipped table: small vanilla village houses, per family.
     *
     * <p><b>Only the "small house" pieces</b> — the bigger village buildings are two storeys
     * and land badly on anything but flat ground, and a delivery wants somewhere to knock, not
     * a landmark. Keys are exactly what {@code /place structure minecraft:village/…} accepts.
     *
     * <p>Mojang renames these between versions, so nothing here is assumed to exist: every key
     * is resolved once at startup and the ones that fail are dropped with a warning
     * ({@link BuildingService#validateTemplates}). A family that loses all its keys falls back
     * to plains, and if plains itself resolves nothing the module says so and turns placement
     * off rather than failing a delivery at the far end.
     */
    public static List<BuildingTemplate> shipped() {
        List<BuildingTemplate> out = new ArrayList<>();
        addSet(out, BiomeGroup.PLAINS, "village/plains/houses/plains_small_house_", 1, 8);
        addSet(out, BiomeGroup.DESERT, "village/desert/houses/desert_small_house_", 1, 8);
        addSet(out, BiomeGroup.SAVANNA, "village/savanna/houses/savanna_small_house_", 1, 8);
        addSet(out, BiomeGroup.TAIGA, "village/taiga/houses/taiga_small_house_", 1, 4);
        addSet(out, BiomeGroup.SNOWY, "village/snowy/houses/snowy_small_house_", 1, 8);
        return out;
    }

    private static void addSet(List<BuildingTemplate> out, BiomeGroup group, String prefix,
                               int first, int last) {
        for (int i = first; i <= last; i++) {
            out.add(new BuildingTemplate(Source.VANILLA, prefix + i, 1, List.of(group)));
        }
    }

    public boolean fits(BiomeGroup group) {
        return biomes.isEmpty() || biomes.contains(group);
    }
}
