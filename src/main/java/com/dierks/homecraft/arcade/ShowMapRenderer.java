package com.dierks.homecraft.arcade;

import com.dierks.homecraft.arcade.ShowMap.Marker;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.util.List;
import java.util.UUID;

/**
 * The Show Map's markers on top of vanilla's terrain (0.38.2, {@link ShowMap}): one renderer on its owner's map, drawn
 * the same for whoever looks at it (it shows its owner's set, not the viewer's), after the map's own renderer, which
 * stays first and draws the terrain and the player's arrow.
 *
 * <p>It redraws only when its owner's set or launchers changed ({@link RemoteService#version}); otherwise a frame is
 * one compare and the cursors set before stay on its canvas. A linked launcher is a banner flag in its number's colour
 * captioned "#n" (on Bedrock, Geyser drops every caption and draws the flags as small squares tinted in the banner's
 * colour, and the X's as black crosses, so they stay told apart); the owner's other launchers are a white X. It never
 * throws (a renderer that throws is logged every frame): a failure keeps the old markers. No NMS.
 */
final class ShowMapRenderer extends MapRenderer {

    /** A cursor's facing: upright. */
    private static final byte UPRIGHT = 8;

    private final RemoteService remotes;
    private final UUID owner;
    /** The owner's version last drawn: -1 before the first frame. */
    private int drawn = -1;

    ShowMapRenderer(RemoteService remotes, UUID owner) {
        super(false);
        this.remotes = remotes;
        this.owner = owner;
    }

    /** Whose map it is. */
    UUID owner() {
        return owner;
    }

    @Override
    public void render(MapView view, MapCanvas canvas, Player viewer) {
        int version = remotes.version(owner);
        if (version == drawn) {
            return;
        }
        try {
            World w = view.getWorld();
            if (w == null) {
                return;
            }
            List<Marker> markers = ShowMap.markers(remotes.linked(owner), remotes.ownLaunchers(owner), w.getName(),
                    view.getCenterX(), view.getCenterZ(), view.getScale().getValue());
            MapCursorCollection cursors = new MapCursorCollection();
            for (Marker m : markers) {
                String caption = m.caption();
                cursors.addCursor(new MapCursor(m.x(), m.y(), UPRIGHT, type(m), true,
                        caption == null ? null : Component.text(caption)));
            }
            canvas.setCursors(cursors);
            drawn = version;
        } catch (RuntimeException | LinkageError e) {
            // the old markers stay; the next change tries again
        }
    }

    /** A flag in number {@code n}'s colour for a linked one, a white X for the owner's others. */
    private static MapCursor.Type type(Marker m) {
        if (m.kind() == ShowMap.Kind.MINE) {
            return MapCursor.Type.TARGET_X;
        }
        return switch (RemoteRules.dye(m.number())) {
            case RED -> MapCursor.Type.BANNER_RED;
            case ORANGE -> MapCursor.Type.BANNER_ORANGE;
            case YELLOW -> MapCursor.Type.BANNER_YELLOW;
            case LIME -> MapCursor.Type.BANNER_LIME;
            case GREEN -> MapCursor.Type.BANNER_GREEN;
            case CYAN -> MapCursor.Type.BANNER_CYAN;
            case LIGHT_BLUE -> MapCursor.Type.BANNER_LIGHT_BLUE;
            case BLUE -> MapCursor.Type.BANNER_BLUE;
            case PURPLE -> MapCursor.Type.BANNER_PURPLE;
            case MAGENTA -> MapCursor.Type.BANNER_MAGENTA;
            case PINK -> MapCursor.Type.BANNER_PINK;
            case WHITE -> MapCursor.Type.BANNER_WHITE;
            case LIGHT_GRAY -> MapCursor.Type.BANNER_LIGHT_GRAY;
            case GRAY -> MapCursor.Type.BANNER_GRAY;
            case BLACK -> MapCursor.Type.BANNER_BLACK;
            case BROWN -> MapCursor.Type.BANNER_BROWN;
        };
    }
}
