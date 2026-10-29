package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.gui.games.clubhouse.SongMenu;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * The jukebox corner (0.38.4; the owner: a jukebox corner with a request-a-song item): a real jukebox on a small
 * stage in the room ({@link ClubhousePlanner}; an owner-built room's is the one the admin stood on for
 * {@code /hcm games clubhouse jukebox}). A tap on it, by anyone, Java or Bedrock, opens the Songs screen
 * ({@link SongMenu}); so does Songs on the Games screen's Together tab while inside. A tap on a disc plays it for
 * the room ("Sam picked Pigstep"); a request while one plays goes next ("Next: ..."); Quiet stops it.
 *
 * <p><b>The disc is the room's.</b> The plugin puts a disc in the block and takes it out again: it is never an
 * item anyone gets ({@code eject} would drop one: never called; the block's inventory is cleared instead). The
 * tap is handled at LOWEST and the block denied, so vanilla never pops the disc out and nothing else (the Arcade
 * Machine listener looks up placed custom blocks by position: this isn't one) sees it. The music stops and the
 * disc comes out when the song and its queue run out, when the room closes or moves, when an admin switches it
 * off, rebuilds it or changes its spots ({@link Clubhouse#quietMusic}), and when the Clubhouse stops: a disc in
 * the block is {@code has_record=true}, which the plan's empty jukebox isn't, so a converge or a verify would
 * otherwise find it. The corner remembers where its disc went in ({@link Songs.Disc}: the block, never the room's
 * current spot or phase), so the clear reaches it whatever the room did since, and tries again each second while
 * its chunk isn't loaded. A disc left by a crash is taken out the same way when the room opens (the block's item,
 * not its {@code has_record}: a boot converge can hide one under {@code has_record=false}).
 *
 * <p>The sound is the client's block sound: Geyser plays it like any vanilla jukebox; the Sound Muffler has no
 * jukebox group, so the owner's zones can't hush it (the docs say so). The queue is pure ({@link Songs.Queue}).
 */
final class JukeboxCorner implements SongMenu.Corner {

    private final Clubhouse club;
    private final Songs.Queue queue = new Songs.Queue();
    private List<Songs.Song> songs;
    /** Whether a disc of ours is in a block now, and which. */
    private final Songs.Disc disc = new Songs.Disc();

    JukeboxCorner(Clubhouse club) {
        this.club = club;
    }

    /** The songs the server knows (read once; empty until the registry answers). */
    @Override
    public List<Songs.Song> songs() {
        if (songs == null || songs.isEmpty()) {
            try {
                songs = Songs.all();
            } catch (RuntimeException | LinkageError e) {
                songs = List.of();
            }
        }
        return songs;
    }

    /**
     * The room's jukebox block, or {@code null}: the room isn't open, has no jukebox spot, its chunk isn't loaded
     * (asked, never loaded), or the block there isn't a jukebox (an owner-built room without one).
     */
    Block block() {
        ClubhouseRoom room = club.room();
        int[] at = room == null || !room.open() ? null : room.jukeboxBlock();
        World w = at == null ? null : Bukkit.getWorld(room.world());
        if (w == null || !w.isChunkLoaded(at[0] >> 4, at[2] >> 4)) {
            return null;
        }
        Block b = w.getBlockAt(at[0], at[1], at[2]);
        return b.getType() == Material.JUKEBOX ? b : null;
    }

    /** Whether {@code b} is the room's jukebox. */
    private boolean ours(Block b) {
        ClubhouseRoom room = club.room();
        int[] at = room == null || !room.open() || b == null ? null : room.jukeboxBlock();
        return at != null && b.getWorld().getName().equalsIgnoreCase(room.world()) && b.getX() == at[0]
                && b.getY() == at[1] && b.getZ() == at[2];
    }

    // ---- taps --------------------------------------------------------------------------------------

    /**
     * A right-click (a Bedrock tap) on the room's jukebox, at LOWEST: the block is denied (vanilla never pops the
     * disc; nothing else sees the tap) and, for the main hand, the Songs screen opens.
     */
    void tapped(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || !ours(e.getClickedBlock())) {
            return;
        }
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) {
            openSongs(e.getPlayer());
        }
    }

    /** The Songs screen, or a line when the room has no jukebox right now. */
    void openSongs(Player p) {
        if (block() == null) {
            refuse(p, ClubBoardText.NO_JUKEBOX);
            return;
        }
        new SongMenu(club.plugin(), club, p, this).open(p);
    }

    /** {@code by} asked for {@code s}: it plays now, or next. */
    @Override
    public void request(Player by, Songs.Song s) {
        if (s == null || block() == null) {
            refuse(by, ClubBoardText.NO_JUKEBOX);
            return;
        }
        switch (queue.request(s, club.now())) {
            case PLAY -> {
                if (play(s)) {
                    tellRoom(ClubBoardText.picked(by.getName(), s.title()));
                } else {
                    queue.clear();
                    refuse(by, ClubBoardText.NO_JUKEBOX);
                }
            }
            case QUEUE, REPLACE_NEXT -> tellRoom(ClubBoardText.next(s.title()));
            case SAME -> by.sendActionBar(Text.of(ClubBoardText.nowPlaying(s.title())));
        }
    }

    /** {@code by} turned the music off. */
    @Override
    public void quiet(Player by) {
        boolean was = queue.now() != null;
        stop();
        if (was) {
            tellRoom(ClubBoardText.quiet(by.getName()));
        }
    }

    // ---- the block ---------------------------------------------------------------------------------

    private boolean play(Songs.Song s) {
        Block b = block();
        if (b == null || !(b.getState(false) instanceof Jukebox jb)) {
            return false;
        }
        if (disc.in()) {
            clearDisc(); // one of ours still in another block (the spot changed): out first
        }
        jb.stopPlaying();
        jb.setRecord(new ItemStack(s.disc()));
        jb.update(true, false);
        disc.putIn(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        return jb.startPlaying();
    }

    /** Once a second: the next song when the one playing is over; the disc out when nothing is left. */
    void second(long now) {
        if (!disc.in() && queue.now() == null) {
            return;
        }
        Songs.Song next = queue.due(now);
        if (next != null) {
            if (!play(next)) {
                stop();
            }
            return;
        }
        if (queue.now() == null && disc.in()) {
            clearDisc();
        }
    }

    /**
     * The music off and our disc out of the block it went into (the room closed, moved or switched off, the
     * Clubhouse stopped, Quiet). Called every second while the room is closed too, so a clear that couldn't
     * reach the block gets its next try.
     */
    void stop() {
        queue.clear();
        clearDisc();
    }

    /**
     * A disc left in the room's block (a crash) comes out when the room opens: ours or not, it fails the verify.
     * The block's item is what counts, not {@code has_record}: a boot converge writes the plan's empty jukebox
     * over the block and hides a left disc under {@code has_record=false}.
     */
    void sweep() {
        if (disc.in()) {
            clearDisc(); // one of ours remembered from before (a chunk that wasn't loaded): out first
        }
        Block b = block();
        if (b != null && b.getState(false) instanceof Jukebox jb && hasDisc(jb)) {
            disc.putIn(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
            clearDisc();
        }
    }

    private static boolean hasDisc(Jukebox jb) {
        ItemStack in = jb.getRecord();
        return in != null && !in.getType().isAir();
    }

    /**
     * Our disc out of the block it went into, wherever the room is now: nothing when its chunk isn't loaded
     * (remembered: tried again next second); forgotten when the block isn't a jukebox any more, when it was
     * cleared, or when the clear threw (one WARN, never one a second).
     */
    private void clearDisc() {
        Songs.DiscAt at = disc.at();
        if (at == null) {
            return;
        }
        World w = Bukkit.getWorld(at.world());
        if (w == null || !w.isChunkLoaded(at.x() >> 4, at.z() >> 4)) {
            return; // asked, never loaded
        }
        Block b = w.getBlockAt(at.x(), at.y(), at.z());
        if (!(b.getState(false) instanceof Jukebox jb)) {
            disc.cleared(true); // not a jukebox any more: nothing of ours to take out
            return;
        }
        try {
            jb.stopPlaying();
            jb.getInventory().clear(); // the block's disc, nobody's inventory; never eject(): that drops it
            jb.update(true, false);
        } catch (RuntimeException | LinkageError e) {
            club.log().warning("Clubhouse jukebox: could not take the disc out: " + e);
        }
        disc.cleared(true);
    }

    // ---- reading it --------------------------------------------------------------------------------

    /** The song playing, or {@code null}. */
    @Override
    public Songs.Song now() {
        return queue.now();
    }

    /** The song next, or {@code null}. */
    @Override
    public Songs.Song next() {
        return queue.next();
    }

    private void tellRoom(String line) {
        for (ClubVisits.Visit v : club.visits().all()) {
            Player p = online(v.id());
            if (p != null) {
                p.sendMessage(Text.of(line));
            }
        }
    }

    private static void refuse(Player p, String line) {
        p.sendMessage(Text.of(line));
        try {
            Sounds.refused(p);
        } catch (RuntimeException | LinkageError ignored) {
            // a sound is decoration
        }
    }

    private static Player online(UUID id) {
        try {
            return id == null ? null : Bukkit.getPlayer(id);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
