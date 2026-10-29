package com.dierks.homecraft.games.trial;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.clubhouse.ClubBoard;
import com.dierks.homecraft.games.clubhouse.ClubDoor;
import com.dierks.homecraft.games.clubhouse.ClubVisits;
import com.dierks.homecraft.games.clubhouse.ClubhouseText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A solo run's side of the Clubhouse (0.38.3, the owner: "go to the clubhouse when click into a game"): the
 * hand-out from the Clubhouse to a course's start (refused before anything is taken when it can't be made,
 * handed back when the start can't be reached), the way back after a solo finish with the player's OWN
 * results (never the shared board or Results), and Leave game partway back to the Clubhouse; with every
 * fallback (no Clubhouse, {@code solo_after} off, a test run, a rider, another world, closing for the
 * restart) home exactly as before, with nothing touched.
 */
class ClubSoloTest {

    private static final UUID SAM = UUID.randomUUID();
    private static final UUID AVA = UUID.randomUUID();

    /** A Clubhouse that records what the races and runs ask of it. */
    private static final class Door implements ClubDoor {
        final Map<UUID, ClubVisits.Kind> in = new LinkedHashMap<>();
        final Map<UUID, String> lines = new LinkedHashMap<>();
        final List<UUID> handedOut = new ArrayList<>();
        final List<UUID> handedBack = new ArrayList<>();
        final List<ClubVisits.Kind> backKinds = new ArrayList<>();
        final Map<UUID, Consumer<Player>> solo = new LinkedHashMap<>();
        int shared;
        boolean soloAfter = true;
        boolean closing;
        boolean handOutOk = true;
        boolean takeInOk = true;

        @Override
        public String world() {
            return "games";
        }

        @Override
        public boolean seatable(UUID player) {
            return in.containsKey(player);
        }

        @Override
        public boolean spectator(UUID player) {
            return false;
        }

        @Override
        public boolean handOut(Player p, Game to, String ref) {
            if (!handOutOk || in.remove(p.getUniqueId()) == null) {
                return false;
            }
            handedOut.add(p.getUniqueId());
            return true;
        }

        @Override
        public void handBack(Player p, ClubVisits.Kind kind) {
            handedBack.add(p.getUniqueId());
            backKinds.add(kind);
            in.put(p.getUniqueId(), kind);
        }

        @Override
        public boolean takeIn(Player p, ClubVisits.Kind kind, String line) {
            if (!takeInOk) {
                return false;
            }
            in.put(p.getUniqueId(), kind);
            lines.put(p.getUniqueId(), line);
            return true;
        }

        @Override
        public boolean closingForRestart() {
            return closing;
        }

        @Override
        public boolean partyAfter() {
            return true;
        }

        @Override
        public boolean nightAfter() {
            return true;
        }

        @Override
        public boolean golfAfter() {
            return true;
        }

        @Override
        public boolean soloFirst() {
            return true;
        }

        @Override
        public boolean soloAfter() {
            return soloAfter;
        }

        @Override
        public void soloResult(Player p, Consumer<Player> opener) {
            solo.put(p.getUniqueId(), opener);
        }

        @Override
        public void result(ClubBoard.Sheet sheet, Consumer<Player> opener) {
            shared++;
        }

        @Override
        public void podium(List<UUID> topThree) {
        }
    }

    /** A player who remembers what they read (colour codes stripped). */
    private static Player player(UUID id, List<String> heard) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getUniqueId" -> id;
                    case "isOnline" -> true;
                    case "sendMessage" -> {
                        if (args != null && args.length == 1 && args[0] instanceof Component c) {
                            heard.add(PlainTextComponentSerializer.plainText().serialize(c));
                        }
                        yield null;
                    }
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    // ---- the hand-out from the Clubhouse ------------------------------------------------------------

    @Test
    void aPlayerInTheClubhouseIsHandedOutToTheStartOnce() {
        Door door = new Door();
        door.in.put(SAM, ClubVisits.Kind.VISIT);
        List<UUID> readied = new ArrayList<>();
        Refusal r = ClubSolo.seat(door, player(SAM, new ArrayList<>()), null, "sky_rings", "games", () -> null,
                p -> readied.add(p.getUniqueId()));
        assertNull(r, "on their way to the start");
        assertEquals(List.of(SAM), door.handedOut, "their Clubhouse session handed to Time Trials, once");
        assertEquals(List.of(SAM), readied, "then the run's own teleport to the start, its kit and 3-2-1");
        assertTrue(door.handedBack.isEmpty(), "never handed back");
    }

    @Test
    void aHandOutThatCantBeMadeTakesNothing() {
        Door door = new Door();
        door.in.put(SAM, ClubVisits.Kind.VISIT);
        List<UUID> readied = new ArrayList<>();
        Player sam = player(SAM, new ArrayList<>());
        assertEquals(ClubhouseText.OTHER_WORLD, ClubSolo.seat(door, sam, null, "far", "other_world", () -> null,
                p -> readied.add(p.getUniqueId())).message(), "a course in another world: said so");
        Refusal hold = Refusal.restart("4:00 PM");
        assertEquals(hold, ClubSolo.seat(door, sam, null, "sky_rings", "games", () -> hold,
                p -> readied.add(p.getUniqueId())), "the restart hold (or the game closed): its own line");
        door.handOutOk = false;
        assertEquals("Couldn't take you from the Clubhouse right now.", ClubSolo.seat(door, sam, null, "sky_rings",
                "games", () -> null, p -> readied.add(p.getUniqueId())).message(), "the hand-out failed");
        assertTrue(door.handedOut.isEmpty(), "nothing handed out");
        assertTrue(readied.isEmpty(), "no run started");
        assertTrue(door.in.containsKey(SAM), "still in the Clubhouse, with their things");
    }

    @Test
    void aStartThatCantBeReachedHandsThemBack() {
        Door door = new Door();
        door.in.put(SAM, ClubVisits.Kind.VISIT);
        Refusal r = ClubSolo.seat(door, player(SAM, new ArrayList<>()), null, "sky_rings", "games", () -> null,
                p -> false);
        assertEquals(ClubhouseText.NO_START, r.message(), "said why");
        assertEquals(List.of(SAM), door.handedBack, "handed straight back to the Clubhouse");
        assertEquals(List.of(ClubVisits.Kind.VISIT), door.backKinds, "as a visitor");
        assertTrue(door.in.containsKey(SAM), "in the Clubhouse again");
    }

    // ---- back after a solo finish -------------------------------------------------------------------

    @Test
    void aSoloFinishComesBackWithTheirOwnResultsOnly() {
        Door door = new Door();
        List<String> heard = new ArrayList<>();
        Player sam = player(SAM, heard);
        int[] forgot = {0};
        Consumer<Player> results = p -> { };
        String line = ClubhouseText.backSolo("Sky Rings", "1:23.4");
        assertTrue(ClubSolo.back(door, sam, "games", false, false, () -> forgot[0]++, line, results),
                "back to the Clubhouse");
        assertEquals(1, forgot[0], "the run ended as the session's end would have ended it (once)");
        assertEquals(ClubVisits.Kind.VISIT, door.in.get(SAM), "in as a visitor");
        assertEquals(line, door.lines.get(SAM), "reading what they did");
        assertEquals("&dBack in the Clubhouse! &fSky Rings 1:23.4", line, "Back in the Clubhouse! Sky Rings 1:23.4");
        assertEquals(results, door.solo.get(SAM), "their own results screen, for them");
        assertFalse(door.solo.containsKey(AVA), "nobody else's");
        assertEquals(0, door.shared, "the board and the shared Results (a party race's) are never touched");
    }

    @Test
    void everyFallbackGoesHomeAsBeforeWithNothingTouched() {
        int[] forgot = {0};
        Door door = new Door();
        Player sam = player(SAM, new ArrayList<>());
        String line = ClubhouseText.backSolo("Sky Rings", "1:23.4");
        assertFalse(ClubSolo.back(null, sam, "games", false, false, () -> forgot[0]++, line, p -> { }),
                "no Clubhouse");
        assertFalse(ClubSolo.back(door, sam, "games", true, false, () -> forgot[0]++, line, p -> { }), "a test run");
        assertFalse(ClubSolo.back(door, sam, "games", false, true, () -> forgot[0]++, line, p -> { }),
                "with a rider (they go home together)");
        assertFalse(ClubSolo.back(door, sam, "other", false, false, () -> forgot[0]++, line, p -> { }),
                "another world");
        door.soloAfter = false;
        assertFalse(ClubSolo.back(door, sam, "games", false, false, () -> forgot[0]++, line, p -> { }),
                "solo_after off");
        assertEquals(0, forgot[0], "nothing forgotten: the session's end does it, at home");
        assertTrue(door.in.isEmpty() && door.solo.isEmpty(), "nobody taken in, no results kept");
    }

    @Test
    void closingForTheRestartGoesHomeSayingWhy() {
        Door door = new Door();
        door.closing = true;
        List<String> heard = new ArrayList<>();
        int[] forgot = {0};
        assertFalse(ClubSolo.back(door, player(SAM, heard), "games", false, false, () -> forgot[0]++, "x", p -> { }),
                "home");
        assertEquals(0, forgot[0], "nothing touched");
        assertEquals(1, heard.size(), heard.toString());
        assertTrue(heard.getFirst().contains("closed for the restart"), heard.toString());
    }

    @Test
    void aClubhouseThatCantTakeThemSendsThemHome() {
        Door door = new Door();
        door.takeInOk = false;
        int[] forgot = {0};
        assertFalse(ClubSolo.back(door, player(SAM, new ArrayList<>()), "games", false, false, () -> forgot[0]++,
                "x", p -> { }), "the caller sends them home");
        assertTrue(door.solo.isEmpty(), "no results kept for a player who isn't there");
    }

    // ---- Leave game partway ---------------------------------------------------------------------------

    @Test
    void leaveGamePartwayGoesBackOnlyFromARunStartedThere() {
        Door door = new Door();
        int[] forgot = {0};
        Player sam = player(SAM, new ArrayList<>());
        assertFalse(ClubSolo.leftToClub(door, sam, "games", false, false, false, () -> forgot[0]++,
                ClubhouseText.LEFT_RUN), "a run started from home: Leave game takes them home");
        assertEquals(0, forgot[0], "untouched");
        assertTrue(ClubSolo.leftToClub(door, sam, "games", true, false, false, () -> forgot[0]++,
                ClubhouseText.LEFT_RUN), "started from the Clubhouse: back there");
        assertEquals(1, forgot[0], "the run forgotten (it records nothing, as any Leave game)");
        assertEquals(ClubhouseText.LEFT_RUN, door.lines.get(SAM), "reading where they are");
        assertTrue(ClubhouseText.LEFT_RUN.contains("Leave game again takes you home"), "and how to go home");
        door.closing = true;
        assertFalse(ClubSolo.leftToClub(door, player(AVA, new ArrayList<>()), "games", true, false, false,
                () -> forgot[0]++, ClubhouseText.LEFT_RUN), "closing for the restart: home");
    }

    // ---- the 3-2-1 after a hand-out (R383-S1) ------------------------------------------------------------

    @Test
    void theCountdownWaitsForTheHandOutsMoveToLandAndSendsAgainWhenItNeverComes() {
        TrialRun run = new TrialRun(SAM, LapsTest.straight(), false, TimeTrials.COUNTDOWN_TICKS + 1);
        run.fromClub = true;
        long made = 1000;
        run.lastReset = made; // readyFromClub's: the hold is armed before the move
        run.expect = new Location(null, 1, 2, 3); // move(): the run's own teleport, on its way
        run.suspended = true;
        run.countdown = 40; // as if ticks had been counted off in the Clubhouse
        assertEquals(TimeTrials.Hold.WAIT, TimeTrials.hold(run, made + 1), "still in flight: nothing counts");
        assertEquals(TimeTrials.COUNTDOWN_TICKS + 1, run.countdown, "the full 3-2-1 begins at the line");
        assertEquals(TimeTrials.Hold.WAIT, TimeTrials.hold(run, made + TimeTrials.WAIT_TICKS - 1), "not yet");
        assertEquals(TimeTrials.Hold.AGAIN, TimeTrials.hold(run, made + TimeTrials.WAIT_TICKS),
                "our teleport never came: sent to the start again, as running() does");
        run.expect = null; // the teleport listener: the landing matched
        run.suspended = false;
        assertEquals(TimeTrials.Hold.NONE, TimeTrials.hold(run, made + 5), "landed: the 3-2-1 counts");
        assertEquals(TimeTrials.COUNTDOWN_TICKS + 1, run.countdown, "from its full length");
        run.suspended = true; // moved by someone else (no expect): never held, as before
        assertEquals(TimeTrials.Hold.NONE, TimeTrials.hold(run, made + 5), "only the run's own move holds it");
    }
}
