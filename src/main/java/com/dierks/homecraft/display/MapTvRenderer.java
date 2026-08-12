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
 * Renders a map-TV (§3.8). Two modes:
 * <ul>
 *   <li><b>chart</b> — a live price-history line chart for one commodity: filled
 *       background, gridlines, min/max axis labels, a bright high-contrast line,
 *       name + price + trend. A {@code cols×rows} grid tiles into one big screen,
 *       each tile rendering its window of the same virtual chart.</li>
 *   <li><b>board</b> — a ticker/leaderboard listing several commodities (price +
 *       trend), paginated across tiles for a wide strip.</li>
 * </ul>
 *
 * <p>Non-contextual (rendered once per tick) and throttled: it repaints only when
 * {@link DisplayService#snapshotVersion()} changes, never every tick.
 */
public final class MapTvRenderer extends MapRenderer {

    private static final int TILE = 128;
    private static final int MARGIN = 4;
    private static final char S = '§'; // section sign — drawText honours colour codes

    private final HomeCraftManagement plugin;
    private final String itemId;
    private final boolean board;
    private final int tileX;
    private final int tileY;
    private final int cols;
    private final int rows;

    private long appliedVersion = Long.MIN_VALUE;

    public MapTvRenderer(HomeCraftManagement plugin, String itemId, int tileX, int tileY, int cols, int rows) {
        super(false); // non-contextual: render() called once per tick with player == null
        this.plugin = plugin;
        this.itemId = itemId;
        this.board = "*".equals(itemId);
        this.tileX = tileX;
        this.tileY = tileY;
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
    }

    @Override
    public void render(MapView view, MapCanvas canvas, Player player) {
        long version = plugin.displayService() != null ? plugin.displayService().snapshotVersion() : 0;
        if (version == appliedVersion) {
            return;
        }
        appliedVersion = version;
        fill(canvas, new Color(16, 20, 28));
        if (board) {
            renderBoard(canvas);
        } else {
            renderChart(canvas);
        }
    }

    // ---- single-commodity chart ----------------------------------------------

    private void renderChart(MapCanvas canvas) {
        MarketItem item = plugin.market().item(itemId);
        if (item == null) {
            if (topLeft()) {
                canvas.drawText(4, 4, MinecraftFont.Font, S + "cNo data");
            }
            return;
        }
        int width = TILE * cols;
        int height = TILE * rows;
        int headerH = 26;
        int plotTop = headerH + 2;
        int plotLeft = MARGIN;
        int plotRight = width - MARGIN;
        int plotBottom = height - MARGIN;

        double change = plugin.market().change24h(itemId);
        double price = plugin.market().price(itemId);

        List<Double> prices = priceSeries();
        double min = prices.isEmpty() ? 0 : Collections.min(prices);
        double max = prices.isEmpty() ? 1 : Collections.max(prices);
        if (max - min < 1e-6) {
            max = min + Math.max(1, Math.abs(min) * 0.05);
        }

        // Draw order matches the dashboard: band -> gridlines -> axis labels -> header -> line LAST.

        // 1) Header band on the top row of tiles so the text stands out.
        if (tileY == 0) {
            Color band = new Color(24, 30, 44);
            for (int x = 0; x < TILE; x++) {
                for (int y = 0; y < headerH; y++) {
                    canvas.setPixelColor(x, y, band);
                }
            }
        }

        // 2) Gridlines.
        Color grid = new Color(46, 56, 74);
        for (int g = 0; g <= 3; g++) {
            int vy = plotTop + (int) Math.round((plotBottom - plotTop) * (g / 3.0));
            for (int vx = plotLeft; vx <= plotRight; vx++) {
                plot(canvas, vx, vy, grid);
            }
        }
        for (int g = 0; g <= 4; g++) {
            int vx = plotLeft + (int) Math.round((plotRight - plotLeft) * (g / 4.0));
            for (int vy = plotTop; vy <= plotBottom; vy++) {
                plot(canvas, vx, vy, grid);
            }
        }

        // 3) Axis min/max labels on the left column of tiles.
        if (tileX == 0) {
            int localTop = plotTop - tileY * TILE;
            int localBottom = plotBottom - tileY * TILE;
            if (localTop >= 0 && localTop < TILE) {
                canvas.drawText(3, Math.max(0, localTop + 1), MinecraftFont.Font,
                        S + "7" + safe(plugin.economy().format(max), 11));
            }
            if (localBottom - 8 >= 0 && localBottom - 8 < TILE) {
                canvas.drawText(3, localBottom - 8, MinecraftFont.Font,
                        S + "7" + safe(plugin.economy().format(min), 11));
            }
        }

        // 4) Header text on the top-left tile: name, current price, trend.
        if (topLeft()) {
            canvas.drawText(3, 2, MinecraftFont.Font, S + "f" + safe(item.label(), 19));
            canvas.drawText(3, 13, MinecraftFont.Font,
                    S + "e" + safe(plugin.economy().format(price), 12)
                            + " " + trendCode(change) + asciiArrow(change) + fmtPct(change));
            drawSwatch(canvas, TILE - 11, 2, item.material());
        }

        // 5) Price line LAST, on top — bright green-up / red-down, thick, with a head dot.
        if (prices.size() >= 2) {
            Color lineColor = change < -0.05 ? new Color(240, 90, 90) : new Color(90, 225, 120);
            int n = prices.size();
            int prevX = -1;
            int prevY = -1;
            int lastX = -1;
            int lastY = -1;
            for (int i = 0; i < n; i++) {
                int vx = plotLeft + (int) Math.round((double) i / (n - 1) * (plotRight - plotLeft));
                double norm = (prices.get(i) - min) / (max - min);
                int vy = plotBottom - (int) Math.round(norm * (plotBottom - plotTop));
                if (prevX >= 0) {
                    drawVirtualLine(canvas, prevX, prevY, vx, vy, lineColor);
                }
                prevX = vx;
                prevY = vy;
                lastX = vx;
                lastY = vy;
            }
            Color dot = new Color(255, 255, 255);
            for (int ox = -1; ox <= 1; ox++) {
                for (int oy = -1; oy <= 1; oy++) {
                    plot(canvas, lastX + ox, lastY + oy, dot);
                }
            }
        }
    }

    private List<Double> priceSeries() {
        List<PriceHistoryDao.Snapshot> hist = plugin.market().recentHistory(itemId, 96); // newest-first
        List<Double> out = new ArrayList<>(hist.size() + 1);
        for (int i = hist.size() - 1; i >= 0; i--) {
            out.add(hist.get(i).price());
        }
        out.add(plugin.market().price(itemId));
        return out;
    }

    // ---- multi-commodity board ------------------------------------------------

    private void renderBoard(MapCanvas canvas) {
        List<MarketItem> all = new ArrayList<>(plugin.market().catalog());
        int perTile = 8;
        int tileIndex = tileY * cols + tileX;
        int start = tileIndex * perTile;
        int y = 4;
        if (tileIndex == 0) {
            canvas.drawText(3, y, MinecraftFont.Font, S + "6" + S + "lMARKET");
            y += 13;
        }
        for (int r = 0; r < perTile; r++) {
            int idx = start + r;
            if (idx >= all.size()) {
                break;
            }
            MarketItem it = all.get(idx);
            double change = plugin.market().change24h(it.id());
            String row = S + "f" + safe(it.label(), 10)
                    + " " + S + "e" + safe(plugin.economy().format(plugin.market().price(it.id())), 10)
                    + " " + trendCode(change) + asciiArrow(change);
            canvas.drawText(3, y, MinecraftFont.Font, row);
            y += 13;
            if (y > TILE - 8) {
                break;
            }
        }
    }

    // ---- drawing helpers ------------------------------------------------------

    private boolean topLeft() {
        return tileX == 0 && tileY == 0;
    }

    private void fill(MapCanvas canvas, Color c) {
        for (int x = 0; x < TILE; x++) {
            for (int y = 0; y < TILE; y++) {
                canvas.setPixelColor(x, y, c);
            }
        }
    }

    /** A small colour chip standing in for the commodity's item icon. */
    private void drawSwatch(MapCanvas canvas, int x, int y, org.bukkit.Material material) {
        int h = Math.abs(material.name().hashCode());
        Color c = new Color(80 + h % 150, 80 + (h / 3) % 150, 80 + (h / 7) % 150);
        for (int dx = 0; dx < 8; dx++) {
            for (int dy = 0; dy < 8; dy++) {
                int px = x + dx;
                int py = y + dy;
                if (px >= 0 && px < TILE && py >= 0 && py < TILE) {
                    boolean border = dx == 0 || dy == 0 || dx == 7 || dy == 7;
                    canvas.setPixelColor(px, py, border ? new Color(20, 24, 32) : c);
                }
            }
        }
    }

    private void drawVirtualLine(MapCanvas canvas, int x0, int y0, int x1, int y1, Color color) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0;
        int y = y0;
        while (true) {
            plotThick(canvas, x, y, color);
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

    /** Plot a single virtual-space pixel onto this tile. */
    private void plot(MapCanvas canvas, int vx, int vy, Color color) {
        int lx = vx - tileX * TILE;
        int ly = vy - tileY * TILE;
        if (lx >= 0 && lx < TILE && ly >= 0 && ly < TILE) {
            canvas.setPixelColor(lx, ly, color);
        }
    }

    /** Plot a virtual-space pixel thickened by 1 for a bold line. */
    private void plotThick(MapCanvas canvas, int vx, int vy, Color color) {
        for (int ox = 0; ox <= 1; ox++) {
            for (int oy = 0; oy <= 1; oy++) {
                plot(canvas, vx + ox, vy + oy, color);
            }
        }
    }

    private String trendCode(double change) {
        if (change > 0.05) {
            return S + "a";
        }
        if (change < -0.05) {
            return S + "c";
        }
        return S + "7";
    }

    private String asciiArrow(double change) {
        if (change > 0.05) {
            return "^";
        }
        if (change < -0.05) {
            return "v";
        }
        return "=";
    }

    private String fmtPct(double change) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", Math.abs(change));
    }

    /** Strip colour codes, drop glyphs the map font can't draw, and clamp width. */
    private static String safe(String label, int max) {
        String plain = label == null ? "" : label.replaceAll("(?i)&[0-9a-fk-or]", "");
        StringBuilder sb = new StringBuilder();
        for (char c : plain.toCharArray()) {
            if (MinecraftFont.Font.isValid(String.valueOf(c))) {
                sb.append(c);
            }
        }
        return sb.length() > max ? sb.substring(0, max) : sb.toString();
    }
}
