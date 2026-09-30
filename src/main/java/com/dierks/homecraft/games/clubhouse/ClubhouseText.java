package com.dierks.homecraft.games.clubhouse;

/**
 * Everything the Clubhouse says (CLUBHOUSE-SPEC §7): kid-safe, no emoji, nothing above U+FFFF, and
 * the key fact of every item in its NAME (Bedrock shows lore only on tap-and-hold). The copy scan
 * ({@code ClubhouseCopyTest}) reads every string here and in the Clubhouse's other files.
 */
public final class ClubhouseText {

    private ClubhouseText() {
    }

    public static final String NAME = "The Clubhouse";

    // ---- arriving and leaving -------------------------------------------------------------------

    public static final String WELCOME = "&6Welcome to the Clubhouse! &7Hang out, chat and cheer. Leave game takes"
            + " you home.";
    public static final String WAIT_PARTY = "&dYou're waiting in the Clubhouse. &7When the host starts the race, you"
            + " go straight to the grid.";
    public static final String WAIT_NIGHT = "&bYou're waiting in the Clubhouse. &7We'll take you to the track when"
            + " Race Night starts.";
    public static final String SPECTATING = "&7You're watching from the Clubhouse. &7You won't be put in a race.";
    public static final String BACK_PARTY = "&dBack in the Clubhouse! &7Look at the board for the results.";
    public static final String BACK_GOLF = "&dBack in the Clubhouse! &7The board shows how your group did.";
    public static final String NIGHT_OVER = "&6Race Night is over - great racing! &7Everyone to the Clubhouse!";
    public static final String CLOSED = "&cThe Clubhouse isn't open right now.";
    public static final String CLOSED_NOW = "&7The Clubhouse closed, so we sent you home. Your things are back.";
    public static final String LOST = "&7We couldn't get you to the Clubhouse, so we sent you home. Your things are"
            + " back.";
    public static final String OTHER_WORLD = "That track is in another world - use Leave game, and you'll be taken"
            + " there from home.";
    public static final String NO_GRID = "Couldn't get you to the grid - you're still in the Clubhouse.";
    public static final String ALREADY = "&7You're in the Clubhouse already.";
    public static final String NOT_SPECTATOR = "&7You're racing next time - you'll go straight to the grid.";

    // ---- timeouts and the restart ---------------------------------------------------------------

    public static final String IDLE_WARN = "&eYou've been in the Clubhouse a long time. &7You go home in 1 minute -"
            + " your things come back.";
    public static final String IDLE_HOME = "&7Time to head home from the Clubhouse. Your things are back - see you"
            + " soon!";

    /** "The server restarts at 4:00 AM - the Clubhouse closes in 1 minute." */
    public static String holdWarn(String at) {
        return "&eThe server restarts at " + (at == null ? "soon" : at) + " &7- the Clubhouse closes in 1 minute."
                + " Your things come back.";
    }

    public static final String HOLD_HOME = "&7The Clubhouse is closing for the restart. Your things are back.";

    /**
     * A race or a golf round that would end in the Clubhouse ends during the restart hold: home instead
     * ({@link ClubDoor#closingForRestart}), and why.
     */
    public static final String HOLD_NOT_TAKEN = "&7The Clubhouse is closed for the restart, so you're going home."
            + " Your things are back.";

    // ---- the podium -----------------------------------------------------------------------------

    public static final String PHOTO = "&6Photo time!";

    /** "Race Night's winners on the podium" small line. */
    public static final String PHOTO_SMALL = "&7Race Night's top three";

    // ---- the kit --------------------------------------------------------------------------------

    public static final String KIT_PARTY = "&dParty &7- open your party screen";
    public static final String KIT_RESULTS = "&eResults &7- the last race";
    public static final String KIT_LEAVE = "&cLeave game &7- click twice";
    public static final String KIT_WATCH = "&bWatch live &7- fly round the course";
    public static final String NO_RESULTS = "&7No results yet - they show here after a race.";

    // ---- the Watch and Go buttons -----------------------------------------------------------------

    public static final String GO_BUTTON = "&6Go to the Clubhouse &7- wait there";
    public static final String WATCH_BUTTON = "&bWatch &7- from the Clubhouse, not racing";
    public static final String WAIT_BUTTON = "&6Wait in the Clubhouse &7- we take you to the track";

    /** The clickable line after joining Race Night. */
    public static final String WAIT_OFFER = "&6[Wait in the Clubhouse] &7- or type /hcm play clubhouse";
    /** Its hover. */
    public static final String WAIT_HOVER = "Hang out with the other racers until it starts";

    /** What the host reads after a party race that came back to the Clubhouse. */
    public static final String RACE_AGAIN = "&dRace again? &7Tap Party, then Race again!";
}
