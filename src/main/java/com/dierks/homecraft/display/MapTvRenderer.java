package com.dierks.homecraft.display;

import com.dierks.homecraft.HomeCraftManagement;
import com.dierks.homecraft.market.MarketItem;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;

import java.awt.Color;
import java.util.ArrayList;
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

    /**
     * A clean TEXT PRICE BOARD (Round 3a) — no chart. Renders the commodity name,
     * current price, trend arrow + %, and stock, centered and laid out in virtual
     * space so the same board scales across a tiled wall. Text on a map is reliable;
     * the old line graph was removed.
     */
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

        double change = plugin.market().change24h(itemId);
        double price = plugin.market().price(itemId);
        long stock = plugin.market().state(itemId).stock();
        char trendC = change > 0.05 ? 'a' : (change < -0.05 ? 'c' : 'f');

        String name = safe(item.label(), 20);
        String priceStr = safe(plugin.economy().format(price), 16);
        String trendStr = asciiArrow(change) + " " + fmtPct(change);
        String stockStr = "Stock: " + (stock <= 0 ? "OUT" : Long.toString(stock));

        // Vertical anchors as fractions of the whole (possibly multi-tile) board.
        drawCentered(canvas, width, (int) (height * 0.14), S + "f" + name, true);
        drawCentered(canvas, width, (int) (height * 0.36), S + trendC + priceStr, true);
        drawCentered(canvas, width, (int) (height * 0.60), S + trendC + trendStr, false);
        drawCentered(canvas, width, (int) (height * 0.80), S + "7" + stockStr, false);
    }

    /**
     * Draw a colour-coded string horizontally centered across the whole board at a
     * virtual y, on whichever tile it lands on. {@code bold} thickens it by a 1px
     * re-draw (the map font has no real large size, so this is our emphasis).
     */
    private void drawCentered(MapCanvas canvas, int boardWidth, int vy, String text, boolean bold) {
        String plain = text.replaceAll("(?i)" + S + "[0-9a-fk-or]", "");
        int w = plain.length() * 6;
        int vx = Math.max(2, (boardWidth - w) / 2);
        int lx = vx - tileX * TILE;
        int ly = vy - tileY * TILE;
        if (ly <= -8 || ly >= TILE) {
            return; // this line isn't on this tile
        }
        canvas.drawText(lx, ly, MinecraftFont.Font, text);
        if (bold) {
            canvas.drawText(lx + 1, ly, MinecraftFont.Font, text);
        }
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
