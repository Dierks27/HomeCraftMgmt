package com.dierks.homecraft.games.chance.mobraces;

/**
 * The six racers of Mob Races, in lane order from the inside of the track out. Each is a real mob, shrunk, with a
 * number and a colour of its own that every screen, the track and the board use for it.
 *
 * <p><b>Its ratings</b> (1 to 5 each, fixed, suited to the mob): Speed (how fast it runs), Stamina (how well it keeps
 * going to the end) and Start (how quickly it gets away). They are real inputs: with today's form they make each race's
 * chances ({@link RaceCard}), and the show leans on them a little (a quick starter leads early, a stayer finishes
 * strong). Rabbit and Fox are quick, Cow is slow but steady, Chicken gets away fastest.
 *
 * <p>Small mobs and big ones take turns across the lanes, so two wide ones never run shoulder to shoulder. Pure: the
 * mob and the colour are names here ({@code EntityType} and dye names), turned into the real things where they are
 * drawn.
 */
public enum Racer {

    CHICKEN("Chicken", 'c', "RED", 0xD8, 0x30, 0x30, 0.35, 3, 2, 5),
    PIG("Pig", 'd', "PINK", 0xF0, 0x8C, 0xB4, 0.25, 2, 4, 3),
    RABBIT("Rabbit", 'b', "LIGHT_BLUE", 0x4C, 0xB4, 0xE8, 0.35, 5, 1, 4),
    COW("Cow", 'a', "LIME", 0x64, 0xC4, 0x3C, 0.25, 1, 5, 2),
    FOX("Fox", '6', "ORANGE", 0xF0, 0x80, 0x20, 0.32, 4, 3, 3),
    SHEEP("Sheep", 'e', "YELLOW", 0xF0, 0xD0, 0x28, 0.25, 2, 3, 3);

    /** How many race. */
    public static final int COUNT = 6;

    private final String mobName;
    private final char code;
    private final String dye;
    private final int red;
    private final int green;
    private final int blue;
    private final double scale;
    private final int speed;
    private final int stamina;
    private final int start;

    Racer(String mobName, char code, String dye, int red, int green, int blue, double scale, int speed, int stamina,
          int start) {
        this.mobName = mobName;
        this.code = code;
        this.dye = dye;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.scale = scale;
        this.speed = speed;
        this.stamina = stamina;
        this.start = start;
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

    /** How fast it runs, 1 to 5. */
    public int speed() {
        return speed;
    }

    /** How well it keeps going to the end, 1 to 5. */
    public int stamina() {
        return stamina;
    }

    /** How quickly it gets away from the line, 1 to 5. */
    public int start() {
        return start;
    }
}
