package com.dierks.homecraft.display;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.storage.PriceHistoryDao;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Draws a live price-history chart (plus name + current price) onto a map, for the
 * Phase 7 map-TV wall (§3.8). One renderer per map/tile; a grid of {@code cols×rows}
 * framed maps tiles into one big screen — each tile renders its window of the same
 * virtual chart, so a line flows across the whole wall.
 *
 * <p>Non-contextual (rendered once per tick, not per viewer) and throttled: it only
 * repaints when {@link DisplayService#snapshotVersion()} changes (i.e. once per
 * refresh interval), never every tick.
 */
public final class MapTvRenderer extends MapRenderer {

    private static final int TILE = 128;
    private static final int MARGIN = 3;

    private final HomeCraftManagement plugin;
    private final String itemId;
    private final int tileX;
    private final int tileY;
    private final int cols;
    private final int rows;

    private long appliedVersion = Long.MIN_VALUE;

    public MapTvRenderer(HomeCraftManagement plugin, String itemId, int tileX, int tileY, int cols, int rows) {
        super(false); // non-contextual: render() called once per tick with player == null
        this.plugin = plugin;
        this.itemId = itemId;
        this.tileX = tileX;
        this.tileY = tileY;
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
    }

    @Override
    public void render(MapView view, MapCanvas canvas, Player player) {
        long version = plugin.displayService() != null ? plugin.displayService().snapshotVersion() : 0;
        if (version == appliedVersion) {
            return; // data unchanged since last paint — keep the existing pixels
        }
        appliedVersion = version;

        MarketItem item = plugin.market().item(itemId);
        Color bg = new Color(18, 22, 30);
        for (int x = 0; x < TILE; x++) {
            for (int y = 0; y < TILE; y++) {
                canvas.setPixelColor(x, y, bg);
            }
        }
        if (item == null) {
            if (tileX == 0 && tileY == 0) {
                canvas.drawText(4, 4, MinecraftFont.Font, "No data");
            }
            return;
        }

        int width = TILE * cols;
        int height = TILE * rows;

        // Labels live on the top-left tile so they read cleanly across the wall.
        if (tileX == 0 && tileY == 0) {
            canvas.drawText(3, 3, MinecraftFont.Font, safe(item.label()));
            canvas.drawText(3, 13, MinecraftFont.Font,
                    plugin.economy().format(plugin.market().price(itemId)));
            double change = plugin.market().change24h(itemId);
            canvas.drawText(3, 23, MinecraftFont.Font, Trend.arrow(change) + " "
                    + String.format(java.util.Locale.ROOT, "%.1f%%", Math.abs(change)));
        }

        List<Double> prices = priceSeries();
        if (prices.size() < 2) {
            return;
        }
        double min = Collections.min(prices);
        double max = Collections.max(prices);
        if (max - min < 1e-6) {
            max = min + 1; // flat series — avoid divide-by-zero, draw a centred line
        }

        int topPad = MARGIN + 24;                 // leave room for the label block
        int plotLeft = MARGIN;
        int plotRight = width - MARGIN;
        int plotTop = topPad;
        int plotBottom = height - MARGIN;
        int plotW = plotRight - plotLeft;
        int plotH = plotBottom - plotTop;

        Color line = new Color(106, 168, 255);
        int n = prices.size();
        int prevVx = -1;
        int prevVy = -1;
        for (int i = 0; i < n; i++) {
            int vx = plotLeft + (int) Math.round((double) i / (n - 1) * plotW);
            double norm = (prices.get(i) - min) / (max - min);
            int vy = plotBottom - (int) Math.round(norm * plotH);
            if (prevVx >= 0) {
                drawVirtualLine(canvas, prevVx, prevVy, vx, vy, line);
            }
            prevVx = vx;
            prevVy = vy;
        }
    }

    /** Newest-last price series over the last window of snapshots. */
    private List<Double> priceSeries() {
        List<PriceHistoryDao.Snapshot> hist = plugin.market().recentHistory(itemId, 96); // newest-first
        List<Double> out = new ArrayList<>(hist.size() + 1);
        for (int i = hist.size() - 1; i >= 0; i--) {
            out.add(hist.get(i).price());
        }
        out.add(plugin.market().price(itemId)); // pin the live price as the latest point
        return out;
    }

    /** Bresenham line in virtual (whole-wall) space; only pixels in this tile are drawn. */
    private void drawVirtualLine(MapCanvas canvas, int x0, int y0, int x1, int y1, Color color) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0;
        int y = y0;
        while (true) {
            plotVirtual(canvas, x, y, color);
            if (x == x1 && y == y1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
    }

    /** Plot a virtual-space pixel onto this tile's canvas (thickened by 1 for visibility). */
    private void plotVirtual(MapCanvas canvas, int vx, int vy, Color color) {
        int localX = vx - tileX * TILE;
        int localY = vy - tileY * TILE;
        for (int ox = 0; ox <= 1; ox++) {
            for (int oy = 0; oy <= 1; oy++) {
                int lx = localX + ox;
                int ly = localY + oy;
                if (lx >= 0 && lx < TILE && ly >= 0 && ly < TILE) {
                    canvas.setPixelColor(lx, ly, color);
                }
            }
        }
    }

    private static String safe(String label) {
        String plain = label == null ? "" : label.replaceAll("(?i)&[0-9a-fk-or]", "");
        // MinecraftFont can only draw characters it has; strip anything unsupported.
        StringBuilder sb = new StringBuilder();
        for (char c : plain.toCharArray()) {
            if (MinecraftFont.Font.isValid(String.valueOf(c))) {
                sb.append(c);
            }
        }
        return sb.length() > 19 ? sb.substring(0, 19) : sb.toString();
    }
}
