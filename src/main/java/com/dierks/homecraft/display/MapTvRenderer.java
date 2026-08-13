package com.dierks.homecraft.display;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.market.MarketItem;
import com.dierks.homecraft.market.MarketState;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;

import java.awt.Color;
import java.util.Locale;

/**
 * Draws a clean, sign-style TEXT board onto a map-TV (§3.8): commodity name, a big
 * price, a coloured up/down trend arrow, and the current stock. Deliberately
 * <b>text only</b> — no price line, grid, axis, or chart. Painting the price chart
 * onto a map proved unreliable across many rebuilds; the real Chart.js graph is
 * opened in the browser instead (right-click the board → the web dashboard).
 *
 * <p>A {@code cols×rows} grid of framed maps tiles into one wall-sized board: each
 * element is laid out in <em>virtual</em> (whole-wall) space and drawn on whichever
 * tile it lands on, so the text scales across the wall. Non-contextual (rendered
 * once per tick) and throttled — it repaints only when
 * {@link DisplayService#snapshotVersion()} changes.
 */
public final class MapTvRenderer extends MapRenderer {

    private static final int TILE = 128;
    private static final int CHAR_W = 6; // MinecraftFont advance per glyph (approx)
    private static final String S = "§"; // section sign — drawText honours colour codes

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

        fill(canvas, new Color(18, 22, 30));

        MarketItem item = plugin.market().item(itemId);
        if (item == null) {
            if (topLeft()) {
                canvas.drawText(4, 4, MinecraftFont.Font, "No data");
            }
            return;
        }

        int width = TILE * cols;
        int height = TILE * rows;
        double price = plugin.market().price(itemId);
        double change = plugin.market().change24h(itemId);
        MarketState state = plugin.market().state(itemId); // may be null — guard before use

        // Sign-style layout, top → bottom: name, big price, trend arrow, stock.
        // The commodity's item floats in front of the upper area (a real ItemDisplay
        // entity, not painted here), so the name sits just below that.
        drawCentered(canvas, width, (int) (height * 0.16), "f", safe(item.label(), 20), true);
        drawCentered(canvas, width, (int) (height * 0.42), "f", safe(plugin.economy().format(price), 16), true);
        drawTrend(canvas, width, (int) (height * 0.64), change);
        if (state != null) {
            drawCentered(canvas, width, (int) (height * 0.82), "7", "Stock: " + state.stock(), false);
        }
    }

    // ---- element drawing ------------------------------------------------------

    /** A colour-coded line, horizontally centred across the whole board at virtual y. */
    private void drawCentered(MapCanvas canvas, int boardWidth, int vy, String colour, String plain, boolean bold) {
        int w = plain.length() * CHAR_W;
        int vx = Math.max(1, (boardWidth - w) / 2);
        String text = S + colour + plain;
        drawString(canvas, vx, vy, text);
        if (bold) {
            drawString(canvas, vx + 1, vy, text); // 1px offset re-draw = our "bold" (map font is fixed-size)
        }
    }

    /** A centred trend row: a small green/red triangle (▲/▼) drawn by hand + the % change. */
    private void drawTrend(MapCanvas canvas, int boardWidth, int vy, double change) {
        boolean up = change > 0.05;
        boolean down = change < -0.05;
        String colour = up ? "a" : (down ? "c" : "7");
        Color tri = up ? new Color(120, 230, 140) : (down ? new Color(255, 107, 107) : new Color(150, 150, 150));
        String pct = String.format(Locale.ROOT, "%.1f%%", Math.abs(change));

        int triW = 9;
        int gap = 3;
        int textW = pct.length() * CHAR_W;
        int total = triW + gap + textW;
        int vx = Math.max(1, (boardWidth - total) / 2);

        if (up || down) {
            drawTriangle(canvas, vx, vy, triW, 7, up, tri);
        } else {
            drawDash(canvas, vx, vy + 3, triW, tri);
        }
        drawString(canvas, vx + triW + gap, vy, S + colour + pct);
    }

    /** Draw {@code text} (with colour codes) at a virtual position, clipped to this tile. */
    private void drawString(MapCanvas canvas, int vx, int vy, String text) {
        int lx = vx - tileX * TILE;
        int ly = vy - tileY * TILE;
        if (ly <= -8 || ly >= TILE) {
            return; // this line isn't on this tile at all
        }
        canvas.drawText(lx, ly, MinecraftFont.Font, text);
    }

    /** A filled triangle (apex up or down) in virtual space, clipped per tile. */
    private void drawTriangle(MapCanvas canvas, int vx, int vy, int w, int h, boolean up, Color color) {
        int centre = vx + w / 2;
        for (int r = 0; r < h; r++) {
            double frac = up ? (double) r / (h - 1) : (double) (h - 1 - r) / (h - 1);
            int half = (int) Math.round(frac * (w / 2.0));
            for (int x = centre - half; x <= centre + half; x++) {
                plot(canvas, x, vy + r, color);
            }
        }
    }

    /** A short horizontal bar (the "flat" trend marker) in virtual space. */
    private void drawDash(MapCanvas canvas, int vx, int vy, int w, Color color) {
        for (int x = vx; x < vx + w; x++) {
            plot(canvas, x, vy, color);
            plot(canvas, x, vy + 1, color);
        }
    }

    /** Plot one virtual-space pixel onto this tile (bounds-checked). */
    private void plot(MapCanvas canvas, int vx, int vy, Color color) {
        int lx = vx - tileX * TILE;
        int ly = vy - tileY * TILE;
        if (lx >= 0 && lx < TILE && ly >= 0 && ly < TILE) {
            canvas.setPixelColor(lx, ly, color);
        }
    }

    private void fill(MapCanvas canvas, Color c) {
        for (int x = 0; x < TILE; x++) {
            for (int y = 0; y < TILE; y++) {
                canvas.setPixelColor(x, y, c);
            }
        }
    }

    private boolean topLeft() {
        return tileX == 0 && tileY == 0;
    }

    /** Strip colour codes, drop glyphs the map font can't draw, and clamp width. */
    private static String safe(String label, int max) {
        String plain = label == null ? "" : label.replaceAll("(?i)[&§][0-9a-fk-or]", "");
        StringBuilder sb = new StringBuilder();
        for (char c : plain.toCharArray()) {
            if (MinecraftFont.Font.isValid(String.valueOf(c))) {
                sb.append(c);
            }
        }
        return sb.length() > max ? sb.substring(0, max) : sb.toString();
    }
}
