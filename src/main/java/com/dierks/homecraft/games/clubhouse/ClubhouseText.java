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
     * A race or a golf round that would end in the Clubhouse ends in the restart hold's last minute or
     * so, too late to get the hold's minute there: home instead ({@link ClubDoor#closingForRestart}),
     * and why.
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
    /** 0.38.3: the same item while the player's own solo run or round here is newer than the last race's. */
    public static final String KIT_RESULTS_MINE = "&eResults &7- your last run";
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

    // ---- 0.38.3: through the Clubhouse (courses and mini golf) ----------------------------------

    /** "Off to Sky Rings!" (the title on arriving for a game). */
    public static String offTo(String name) {
        return "&6Off to " + name + "!";
    }

    /** The title's small line. */
    public static final String OFF_TO_SMALL = "&7Tap Stay here to hang out";

    /** The chat line on arriving for a game. */
    public static String offToLine(String name) {
        return "&6Off to " + name + "! &7You go in a few seconds - or tap Go now. Stay here keeps you in.";
    }

    /** The action bar: "Sky Rings in 3". */
    public static String countdown(String name, int seconds) {
        return "&e" + name + " &7in &f" + seconds;
    }

    /** Kit slot 3: "Go now: Sky Rings". */
    public static String goNow(String name) {
        return "&aGo now: " + name;
    }

    public static final String KIT_STAY = "&eStay here - hang out";
    public static final String KIT_GAMES = "&bGames &7- pick a game";
    public static final String STAYING = "&7You're staying in the Clubhouse. Tap Games when you want to play.";
    public static final String NEXT_CLOSED = "&7That game closed meanwhile - you're still in the Clubhouse.";

    /** "Back in the Clubhouse! Sky Rings 1:23.4" (a solo run or a round alone, back here). */
    public static String backSolo(String name, String score) {
        return "&dBack in the Clubhouse! &f" + name + " " + score;
    }

    public static final String LEFT_RUN = "&7You left the course - back in the Clubhouse. Leave game again takes you"
            + " home.";
    public static final String LEFT_ROUND = "&7You left the round - back in the Clubhouse. Leave game again takes"
            + " you home.";
    /** A hand-out from here whose move to the start failed: they are handed back. */
    public static final String NO_START = "Couldn't get you to the start - you're still in the Clubhouse.";

    /**
     * The lore under a run's or a round's "Leave game" (the name stays, Bedrock reads it): where its second
     * click goes. One handed out from the Clubhouse goes back there, with the player's things still kept
     * (R383-J2); any other ends the session, home with their things.
     */
    public static String[] leaveLore(boolean fromClub) {
        return fromClub ? new String[] {"&7Click twice: back to", "&7the Clubhouse."}
                : new String[] {"&7Click twice to leave.", "&7Your things come back."};
    }

    // ---- 0.38.4: the record, the challenge ----------------------------------------------------

    /** The title everyone in the room sees when a solo run or round that set the course record lands here. */
    public static final String NEW_RECORD = "&6NEW RECORD!";

    /** Its small line: the record line, in grey ("Sam set a new record on Sky Rings: 1:02.4"). */
    public static String newRecordSmall(String recordLine) {
        return "&7" + com.dierks.homecraft.util.Text.plain(recordLine);
    }

    /** The chat line: "Sam set a new record on Sky Rings: 1:02.4". */
    public static String newRecordLine(String name, String course, String time) {
        return "&6" + name + " set a new record on " + course + ": &f" + time;
    }

    /** Kit slot 1, while not in a party: a challenge is a party race of two with anyone online. */
    public static final String KIT_CHALLENGE = "&6Challenge &7- race anyone online";
}
