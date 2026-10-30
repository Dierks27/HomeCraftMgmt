package com.dierks.homecraft.gui.games.clubhouse;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.games.clubhouse.ClubBoardText;
import com.dierks.homecraft.games.clubhouse.Clubhouse;
import com.dierks.homecraft.games.clubhouse.Songs;
import com.dierks.homecraft.gui.Menus;
import com.dierks.homecraft.gui.games.GameMenu;
import com.dierks.homecraft.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Songs (54; 0.38.4), from a tap on the Clubhouse's jukebox or the Songs tile on the Games screen's Together
 * tab: one disc tile per record the server knows, its title in the NAME (Bedrock shows lore only on
 * tap-and-hold) and its length under it; a tap plays it for the room, or queues it while one plays. Quiet stops
 * the music.
 *
 * <pre>
 *  row 0     4 "Pick a record - it plays for the room" (lore: now playing, next)
 *  rows 1-4  9-44 one tile per record, by title
 *  row 5     47 Quiet · 49 Close
 * </pre>
 * It belongs to the Clubhouse, so it opens in the Clubhouse's session; the jukebox corner does the playing.
 */
public final class SongMenu extends GameMenu {

    static final int HEADER = 4;
    static final int FIRST = 9;
    static final int LAST = 44;
    static final int QUIET = 47;

    /** What the screen asks of the jukebox corner (the corner itself is the Clubhouse package's own). */
    public interface Corner {
        /** The records the server knows, by title. */
        List<Songs.Song> songs();

        /** The one playing, or {@code null}. */
        Songs.Song now();

        /** The one next, or {@code null}. */
        Songs.Song next();

        /** {@code by} tapped {@code song}: play it, or queue it. */
        void request(Player by, Songs.Song song);

        /** {@code by} tapped Quiet. */
        void quiet(Player by);
    }

    private final Corner corner;

    public SongMenu(HomeCraftManagement plugin, Clubhouse clubhouse, Player viewer, Corner corner) {
        super(plugin, clubhouse, viewer, null);
        this.corner = corner;
        init(54, Text.of(ClubBoardText.SONGS_TITLE));
    }

    @Override
    protected void build() {
        fill();
        Songs.Song now = corner.now();
        Songs.Song next = corner.next();
        String playing = now == null ? ClubBoardText.NOTHING_PLAYING : ClubBoardText.nowPlaying(now.title());
        set(HEADER, next == null ? Menus.icon(Material.JUKEBOX, ClubBoardText.SONGS_HEADER, playing)
                : Menus.icon(Material.JUKEBOX, ClubBoardText.SONGS_HEADER, playing, ClubBoardText.next(next.title())),
                null);
        int slot = FIRST;
        for (Songs.Song s : corner.songs()) {
            if (slot > LAST) {
                break;
            }
            boolean on = now != null && now.key().equals(s.key());
            set(slot++, Menus.glint(Menus.icon(s.disc(), (on ? "&d" : "&f") + s.title() + (on ? " &7- playing" : ""),
                    "&7" + Songs.length(s.seconds())), on), e -> {
                corner.request(viewer, s);
                refresh();
            });
        }
        set(QUIET, Menus.icon(Material.BARRIER, ClubBoardText.QUIET), e -> {
            corner.quiet(viewer);
            refresh();
        });
        exitTile();
    }
}
