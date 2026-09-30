package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.Invite;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.trial.Parties;
import com.dierks.homecraft.games.trial.PartyLobby;
import com.dierks.homecraft.gui.games.golf.GolfPartyMenu;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Golf together, around the round itself (EVENTS-OWNER-DECISIONS D4): the party, its invites and its
 * start. {@code /hcm play golf <course>} (or the course's own screen) has "Play with friends", which
 * makes a golf party of up to {@value GolfGroup#MAX} through C1's {@link Parties} (one party per
 * player across party races and golf) and opens its screen ({@link GolfPartyMenu}). Anyone in it
 * can invite a friend through the existing Invites system ([Accept] on Java, {@code /hcm play
 * accept} on Bedrock; its cooldown and invite switches stay); only the host can start; anyone can
 * leave, and a host who leaves passes the party on.
 *
 * <p>Starting takes everyone to hole 1 at once ({@link GolfRounds#startGroup}); from there the round
 * is {@link GolfGroup}'s turn flow. When the round is over the party opens again, so the same
 * friends can play again. Nothing here moves tokens: each player's round is a normal round for the
 * boards and rewards, and being in a party pays nothing extra.
 */
public final class GolfTogether {

    /** How long a golf invite waits for an answer. */
    static final int INVITE_SECONDS = 60;

    private final MiniGolf golf;

    GolfTogether(MiniGolf golf) {
        this.golf = golf;
    }

    private GamesService games() {
        return golf.games();
    }

    private Parties parties() {
        return games().parties();
    }

    /** The golf party the player is in, or {@code null}. */
    public PartyLobby party(UUID player) {
        PartyLobby l = parties().of(player);
        return l != null && l.kind() == PartyLobby.Kind.GOLF ? l : null;
    }

    /** A golf party by id, or {@code null} once it closed. */
    public PartyLobby lobby(long id) {
        PartyLobby l = parties().get(id);
        return l != null && l.kind() == PartyLobby.Kind.GOLF ? l : null;
    }

    /**
     * "Play with friends": the player's golf party on this course, made now if they have none, and its
     * screen. Refused (the player told) when the course is closed, the player can't play golf here, or
     * they are in another party.
     */
    public void openLobby(Player p, String courseId, Runnable back) {
        GolfCourse c = golf.playableCourse(courseId);
        if (c == null) {
            games().tell(p, Refusal.of("That course is closed right now."));
            return;
        }
        Refusal refusal = games().canOpen(p, golf);
        if (refusal != null) {
            games().tell(p, refusal);
            return;
        }
        UUID id = p.getUniqueId();
        PartyLobby l = parties().of(id);
        if (l != null && l.kind() != PartyLobby.Kind.GOLF) {
            games().tell(p, Refusal.of(PartyLobby.Why.IN_ANOTHER.message()));
            return;
        }
        if (l != null && !l.course().equals(c.id())) {
            GolfCourse other = golf.course(l.course());
            games().tell(p, Refusal.of("You're in a golf party on " + (other == null ? l.course() : other.name())
                    + " - leave it first."));
            return;
        }
        if (l == null) {
            l = parties().create(PartyLobby.Kind.GOLF, c.id(), id, GolfGroup.MAX);
            if (l == null) {
                games().tell(p, Refusal.of(PartyLobby.Why.IN_ANOTHER.message()));
                return;
            }
            p.sendMessage(Text.of("&dGolf together on " + c.name() + " &7- invite up to " + (GolfGroup.MAX - 1)
                    + " friends, then tap Start."));
        }
        new GolfPartyMenu(golf.plugin(), golf, p, l.id(), back).open(p);
    }

    /** "Invite a friend": the player picker, then the invite. */
    public void invite(Player from, long partyId, Runnable back) {
        PartyLobby l = lobby(partyId);
        if (l == null || !l.has(from.getUniqueId())) {
            games().tell(from, Refusal.of(PartyLobby.Why.CLOSED.message()));
            return;
        }
        if (l.full()) {
            games().tell(from, Refusal.of(PartyLobby.Why.FULL.message()));
            return;
        }
        games().screens().pickPlayer(from, golf, to -> parties().of(to.getUniqueId()) == null,
                to -> send(from, to, partyId), back);
    }

    /** Ask {@code to} to join the party. */
    void send(Player from, Player to, long partyId) {
        PartyLobby l = lobby(partyId);
        if (l == null || !l.has(from.getUniqueId()) || l.state() != PartyLobby.State.OPEN) {
            games().tell(from, Refusal.of(PartyLobby.Why.NOT_OPEN.message()));
            return;
        }
        GolfCourse c = golf.course(l.course());
        String course = c == null ? l.course() : c.name();
        Invite i = games().invites().send(from, to, golf, "Mini Golf together on " + course, INVITE_SECONDS,
                (invite, yes) -> answered(invite, yes, partyId));
        if (i == null) {
            from.sendMessage(Text.of("&7" + to.getName() + " can't be asked right now."));
            return;
        }
        from.sendMessage(Text.of("&aInvite sent to " + to.getName() + " &7- it lasts " + INVITE_SECONDS + " seconds."));
    }

    private void answered(Invite invite, boolean yes, long partyId) {
        Player from = Bukkit.getPlayer(invite.from());
        Player to = Bukkit.getPlayer(invite.to());
        if (!yes) {
            if (from != null && to != null) {
                from.sendMessage(Text.of("&7" + to.getName() + " didn't take your golf invite."));
            }
            return;
        }
        if (to == null) {
            return;
        }
        PartyLobby l = lobby(partyId);
        PartyLobby.Why no = l == null ? PartyLobby.Why.CLOSED : parties().join(partyId, to.getUniqueId());
        if (no != null) {
            games().tell(to, Refusal.of(no.message()));
            return;
        }
        tellParty(l, "&d" + to.getName() + " &7joined the golf party (" + l.size() + " of " + l.max() + ").");
        new GolfPartyMenu(golf.plugin(), golf, to, partyId, null).open(to);
    }

    /** "Ready" on the party screen. */
    public void ready(Player p, boolean on) {
        PartyLobby l = party(p.getUniqueId());
        if (l != null && l.ready(p.getUniqueId(), on) == null) {
            tellParty(l, "&7" + p.getName() + (on ? " is &aready&7." : " isn't ready yet."));
        }
    }

    /** "Leave the party". */
    public void leave(Player p) {
        PartyLobby l = party(p.getUniqueId());
        if (l == null) {
            return;
        }
        PartyLobby.Left left = parties().leave(p.getUniqueId());
        p.sendMessage(Text.of("&7You left the golf party."));
        if (!left.closed()) {
            tellParty(l, "&7" + p.getName() + " left the golf party.");
            if (left.newHost() != null) {
                Player host = Bukkit.getPlayer(left.newHost());
                if (host != null) {
                    host.sendMessage(Text.of("&dYou're the party's host now &7- you can start."));
                }
            }
        }
    }

    /**
     * The host's Start: everyone in the party, online and free, goes to hole 1 at once. Refused (the
     * host told why) unless everyone can play now: a friend still in another game, a restart due, or
     * fewer than 2 players.
     */
    public void start(Player host) {
        PartyLobby l = party(host.getUniqueId());
        if (l == null) {
            games().tell(host, Refusal.of(PartyLobby.Why.NOT_IN.message()));
            return;
        }
        PartyLobby.Why why = l.canStart(host.getUniqueId());
        if (why != null) {
            games().tell(host, Refusal.of(why.message()));
            return;
        }
        GolfCourse c = golf.playableCourse(l.course());
        if (c == null) {
            games().tell(host, Refusal.of("That course is closed right now."));
            return;
        }
        Refusal restart = games().restartRefusal();
        if (restart != null) {
            games().tell(host, restart);
            return;
        }
        List<Player> players = new ArrayList<>();
        for (UUID id : l.members()) {
            Player m = Bukkit.getPlayer(id);
            if (m == null) {
                continue; // gone: they're dropped from the party when they quit
            }
            String busy = startProblem(m, c);
            if (busy != null) {
                games().tell(host, Refusal.of(busy));
                return;
            }
            players.add(m);
        }
        if (players.size() < PartyLobby.MIN_PLAYERS) {
            games().tell(host, Refusal.of(PartyLobby.Why.TOO_FEW.message()));
            return;
        }
        if (l.start(host.getUniqueId()) != null) {
            games().tell(host, Refusal.of(PartyLobby.Why.NOT_OPEN.message()));
            return;
        }
        for (Player m : players) {
            m.closeInventory(); // the party screen: a world game starts only with nothing open
        }
        tellParty(l, "&dGolf together &7- everyone to hole 1 of " + c.name() + "!");
        if (!golf.rounds().startGroup(l.id(), c, players)) {
            l.finish(); // nobody could go: open again
        }
    }

    /** Why a party member can't start now, or {@code null}. */
    private String startProblem(Player m, GolfCourse c) {
        if (ClubGolf.waiting(games(), m.getUniqueId(), c.world())) {
            return null; // WP-CH: waiting in the Clubhouse after the last round: handed straight to hole 1
        }
        if (games().sessions().session(m) != null || !games().sessions().home(m)) {
            return m.getName() + " is still in another game.";
        }
        Refusal r = games().canOpen(m, golf);
        return r == null ? null : m.getName() + " can't play golf right now.";
    }

    /** The group's round is over: the party opens again ("play again" with the same friends). */
    void roundOver(long partyId) {
        PartyLobby l = lobby(partyId);
        if (l != null) {
            l.finish();
        }
    }

    /** A quit: the player leaves their golf party (a host's passes on). */
    void quit(UUID player) {
        PartyLobby l = party(player);
        if (l == null) {
            return;
        }
        PartyLobby.Left left = parties().leave(player);
        if (!left.closed()) {
            tellParty(l, "&7A player left the golf party.");
        }
    }

    /** Golf stops: every golf party closes. */
    void stop() {
        for (PartyLobby l : parties().all()) {
            if (l.kind() == PartyLobby.Kind.GOLF) {
                parties().close(l.id());
            }
        }
    }

    /** A line to everyone in the party who is online. */
    void tellParty(PartyLobby l, String line) {
        if (l == null) {
            return;
        }
        for (UUID id : l.members()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(Text.of(line));
                try {
                    Sounds.received(p);
                } catch (RuntimeException | LinkageError ignored) {
                    // a sound is decoration
                }
            }
        }
    }

    /** A player's name for the screens (the party's members may be offline for a moment). */
    public static String name(UUID player) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            return p.getName();
        }
        String n = Bukkit.getOfflinePlayer(player).getName();
        return n == null ? "a player" : n;
    }
}
