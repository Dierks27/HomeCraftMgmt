package com.dierks.homecraft.games.chance.mobraces;

/**
 * The six racers of Mob Races, in lane order from the inside of the track out. Each is a real mob, shrunk, with a
 * number and a colour of its own that every screen, the track and the board use for it.
 *
 * <p>Small mobs and big ones take turns across the lanes, so two wide ones never run shoulder to shoulder. Pure: the
 * mob and the colour are names here ({@code EntityType} and dye names), turned into the real things where they are
 * drawn.
 */
public enum Racer {

    CHICKEN("Chicken", 'c', "RED", 0xD8, 0x30, 0x30, 0.35),
    PIG("Pig", 'd', "PINK", 0xF0, 0x8C, 0xB4, 0.25),
    RABBIT("Rabbit", 'b', "LIGHT_BLUE", 0x4C, 0xB4, 0xE8, 0.35),
    COW("Cow", 'a', "LIME", 0x64, 0xC4, 0x3C, 0.25),
    FOX("Fox", '6', "ORANGE", 0xF0, 0x80, 0x20, 0.32),
    SHEEP("Sheep", 'e', "YELLOW", 0xF0, 0xD0, 0x28, 0.25);

    /** How many race. */
    public static final int COUNT = 6;

    private final String mobName;
    private final char code;
    private final String dye;
    private final int red;
    private final int green;
    private final int blue;
    private final double scale;

    Racer(String mobName, char code, String dye, int red, int green, int blue, double scale) {
        this.mobName = mobName;
        this.code = code;
        this.dye = dye;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.scale = scale;
    }

    /** The racer in lane {@code lane} (0 is the inside lane). */
    public static Racer of(int lane) {
        return values()[lane];
    }

    /** Its lane, 0 to 5, the inside lane first. */
    public int lane() {
        return ordinal();
    }

    /** Its number, 1 to 6. */
    public int number() {
        return ordinal() + 1;
    }

    /** "Chicken". */
    public String mobName() {
        return mobName;
    }

    /** The mob it is: an {@code EntityType} name ("CHICKEN"). */
    public String mob() {
        return name();
    }

    /** Its colour as a text colour code's character ('c' for red). */
    public char code() {
        return code;
    }

    /** Its colour as a dye's name ("RED": red concrete, a red banner). */
    public String dye() {
        return dye;
    }

    /** Its colour as red, green and blue (0-255 each), for its flag. */
    public int[] rgb() {
        return new int[]{red, green, blue};
    }

    /** How big the mob is drawn: a fraction of its real size. */
    public double scale() {
        return scale;
    }

    /** "1 Chicken", in its colour: how it is named everywhere ({@code &c1 Chicken}). */
    public String label() {
        return "&" + code + number() + " " + mobName;
    }
}
