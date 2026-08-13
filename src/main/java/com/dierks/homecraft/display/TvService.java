package com.dierks.homecraft.display;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.storage.TvDao;
import org.bukkit.Location;

import java.sql.SQLException;

/**
 * TV blocks (Round 3a, §3.11): each placed TV stores a stream URL; right-clicking it
 * sends the player a clickable link that opens the stream in their browser (full
 * video + sound, zero server lag — we never paint video onto the map).
 */
public final class TvService {

    private final HomeCraftManagement plugin;
    private final TvDao dao;

    public TvService(HomeCraftManagement plugin, TvDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    /** The stream URL bound to a TV, or null if none. */
    public String url(Location loc) {
        try {
            return dao.getUrl(loc);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to read TV url: " + e.getMessage());
            return null;
        }
    }

    /** Bind a stream URL to a TV. */
    public void setUrl(Location loc, String url) {
        try {
            dao.setUrl(loc, url);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to set TV url: " + e.getMessage());
        }
    }

    /** Clear a TV's URL (on break). */
    public void clear(Location loc) {
        try {
            dao.deleteAt(loc);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to clear TV url: " + e.getMessage());
        }
    }
}
