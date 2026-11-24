package com.dierks.homecraft.games.kidbingo;

/**
 * The pictures Picture Bingo is played with (0.57.0): sixteen friendly animals, each its own spawn egg (a picture every
 * client, Java or Bedrock, draws) and its own sound, so a child who can't read knows each one by how it looks and how
 * it sounds. No monsters: it is for a four-year-old.
 *
 * <p>The sound is a sound key, played by name, so a sound a version renamed is simply not heard (it never stops a
 * round).
 */
public enum Picture {
    PIG("Pig", "PIG_SPAWN_EGG", "entity.pig.ambient"),
    COW("Cow", "COW_SPAWN_EGG", "entity.cow.ambient"),
    SHEEP("Sheep", "SHEEP_SPAWN_EGG", "entity.sheep.ambient"),
    CHICKEN("Chicken", "CHICKEN_SPAWN_EGG", "entity.chicken.ambient"),
    HORSE("Horse", "HORSE_SPAWN_EGG", "entity.horse.ambient"),
    WOLF("Wolf", "WOLF_SPAWN_EGG", "entity.wolf.ambient"),
    CAT("Cat", "CAT_SPAWN_EGG", "entity.cat.ambient"),
    FOX("Fox", "FOX_SPAWN_EGG", "entity.fox.ambient"),
    RABBIT("Rabbit", "RABBIT_SPAWN_EGG", "entity.rabbit.ambient"),
    FROG("Frog", "FROG_SPAWN_EGG", "entity.frog.ambient"),
    PANDA("Panda", "PANDA_SPAWN_EGG", "entity.panda.ambient"),
    BEE("Bee", "BEE_SPAWN_EGG", "entity.bee.loop"),
    AXOLOTL("Axolotl", "AXOLOTL_SPAWN_EGG", "entity.axolotl.idle_air"),
    PARROT("Parrot", "PARROT_SPAWN_EGG", "entity.parrot.ambient"),
    TURTLE("Turtle", "TURTLE_SPAWN_EGG", "entity.turtle.ambient_land"),
    LLAMA("Llama", "LLAMA_SPAWN_EGG", "entity.llama.ambient");

    /** How many pictures there are. */
    public static final int COUNT = values().length;

    private final String label;
    private final String egg;
    private final String sound;

    Picture(String label, String egg, String sound) {
        this.label = label;
        this.egg = egg;
        this.sound = sound;
    }

    /** Its name, for whoever reads it (a grown-up). */
    public String label() {
        return label;
    }

    /** Its spawn egg's material name. */
    public String egg() {
        return egg;
    }

    /** Its sound's key ({@code minecraft:} assumed). */
    public String sound() {
        return sound;
    }

    public static Picture of(int i) {
        return values()[Math.floorMod(i, COUNT)];
    }
}
