package com.dierks.homecraft.pack;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * 0.58.0: the playing cards' art in HCM's pack, painted here in code (the owner: "Any way we can make a bit of art for
 * the cards to show what the cards are a bit better than a paper?"). 52 faces and a back, each a flat 32 x 32 item
 * icon: a white card with rounded corners, its rank in a small pixel font top-left and (upside down, as on a real card)
 * bottom-right, its pips in the middle (red Hearts and Diamonds, black Spades and Clubs); an Ace one big pip; a Jack,
 * Queen or King a two-headed court figure in a gold frame (a crown for the King, a tiara and long hair for the Queen, a
 * feathered cap for the Jack); the back a blue lattice.
 *
 * <p><b>Why painted in code.</b> HCM's pack is built from the files it writes at start-up ({@link PackModels}); a
 * picture painted the same way every time needs no image files in the jar and no tools, and the bytes come out the
 * same on every start, so CraftBridge rebuilds the pack only when the art itself changes. The PNG is written here
 * too ({@link #png}: one deflated RGBA image, no time stamp), not through {@code javax.imageio}, so a headless server
 * never loads AWT for it.
 *
 * <p><b>In the pack</b>, for each card ({@link #files}): an item definition {@code assets/hcm/items/card_<id>.json}
 * (what {@code item_model = hcm:card_<id>} draws), its model under {@code models/item/placeholder/} (HCM's place, never
 * one the owner's models use) as {@code minecraft:item/generated} with {@code layer0 = hcm:item/cards/<id>}, a flat
 * icon like any vanilla item's, and the picture at {@code textures/item/cards/<id>.png}, where vanilla keeps its own
 * item pictures. The owner can replace a whole card (an item definition of that name in a drop-in: HCM leaves out all
 * three of its files) or only repaint it (a PNG at the same {@code textures/item/cards/} path: HCM leaves out its own
 * picture and its model draws the owner's). Pure: no server.
 */
public final class CardArt {

    /** Each picture is this many pixels square. */
    public static final int SIZE = 32;

    /** {@code assets/hcm/textures/item/cards/<id>.png} anywhere in a source (a zip may have one extra folder). */
    private static final Pattern TEXTURE = Pattern.compile("(?:^|.*/)assets/" + ClubModels.NS
            + "/textures/item/cards/([a-z0-9_]+)\\.png$");

    // ---- colours (ARGB) ---------------------------------------------------------------------------------------------
    private static final int CLEAR = 0x00000000;
    private static final int EDGE = 0xFF2B2B2B;
    private static final int PAPER = 0xFFFAF8F2;
    private static final int RED = 0xFFD12A2A;
    private static final int BLACK = 0xFF1B1B1F;
    private static final int GOLD = 0xFFE7B024;
    private static final int SKIN = 0xFFF2C79B;
    private static final int HAIR = 0xFF7A4A22;
    private static final int BLONDE = 0xFFE9C750;
    private static final int BEARD = 0xFF8C8C8C;
    private static final int NAVY = 0xFF203C86;
    private static final int LATTICE = 0xFF4469C2;

    // ---- the card's shape: x 1..29, y 1..31 (29 x 31: one middle column, one middle row) ----------------------------
    // 0.58.0 (the owner: "make them a tiny bigger so the whole card is on the card itself ... the number and letters
    // bleed past the card borders"): two pixels wider each side (it was x 3..27), the ranks a clear pixel in from the rim
    private static final int X0 = 1;
    private static final int X1 = 29;
    private static final int Y0 = 1;
    private static final int Y1 = 31;
    /** The pip columns' and the middle row's centres. */
    private static final int LEFT = 11;
    private static final int MID = 15;
    private static final int RIGHT = 19;
    private static final int MIDDLE = 16;

    /** A 3 x 5 pixel font: the ranks. "1" is one pixel wide (it only comes in "10"). */
    private static final Map<Character, String[]> FONT = Map.ofEntries(
            Map.entry('A', new String[]{".#.", "#.#", "###", "#.#", "#.#"}),
            Map.entry('J', new String[]{"..#", "..#", "..#", "#.#", ".#."}),
            Map.entry('Q', new String[]{".#.", "#.#", "#.#", "##.", ".##"}),
            Map.entry('K', new String[]{"#.#", "#.#", "##.", "#.#", "#.#"}),
            Map.entry('0', new String[]{"###", "#.#", "#.#", "#.#", "###"}),
            Map.entry('1', new String[]{"#", "#", "#", "#", "#"}),
            Map.entry('2', new String[]{"##.", "..#", ".#.", "#..", "###"}),
            Map.entry('3', new String[]{"##.", "..#", ".#.", "..#", "##."}),
            Map.entry('4', new String[]{"#.#", "#.#", "###", "..#", "..#"}),
            Map.entry('5', new String[]{"###", "#..", "##.", "..#", "##."}),
            Map.entry('6', new String[]{".##", "#..", "###", "#.#", "###"}),
            Map.entry('7', new String[]{"###", "..#", ".#.", ".#.", ".#."}),
            Map.entry('8', new String[]{"###", "#.#", "###", "#.#", "###"}),
            Map.entry('9', new String[]{"###", "#.#", "###", "..#", "##."}));

    /** The 5 x 5 pips of the number cards, by suit. */
    private static final String[][] PIPS = {
            {"..#..", ".###.", "#####", "#.#.#", "..#.."},  // spade: a point, two lobes and its stem
            {".#.#.", "#####", "#####", ".###.", "..#.."},  // heart
            {"..#..", ".###.", "#####", ".###.", "..#.."},  // diamond
            {".###.", ".###.", "#####", "#####", "..#.."}}; // club: three lobes and its stem

    /**
     * Where a number card's pips go (their centres), as on a real card: up to three rows down the middle column, or
     * three or four down each side, with the middle column between.
     */
    private static int[][] layout(int rank) {
        return switch (rank) {
            case 2 -> new int[][]{{MID, 5}, {MID, 27}};
            case 3 -> new int[][]{{MID, 5}, {MID, MIDDLE}, {MID, 27}};
            case 4 -> new int[][]{{LEFT, 5}, {RIGHT, 5}, {LEFT, 27}, {RIGHT, 27}};
            case 5 -> new int[][]{{LEFT, 5}, {RIGHT, 5}, {MID, MIDDLE}, {LEFT, 27}, {RIGHT, 27}};
            case 6 -> new int[][]{{LEFT, 5}, {RIGHT, 5}, {LEFT, MIDDLE}, {RIGHT, MIDDLE}, {LEFT, 27}, {RIGHT, 27}};
            case 7 -> new int[][]{{LEFT, 5}, {RIGHT, 5}, {MID, 10}, {LEFT, MIDDLE}, {RIGHT, MIDDLE}, {LEFT, 27},
                    {RIGHT, 27}};
            case 8 -> new int[][]{{LEFT, 5}, {RIGHT, 5}, {MID, 10}, {LEFT, MIDDLE}, {RIGHT, MIDDLE}, {MID, 22},
                    {LEFT, 27}, {RIGHT, 27}};
            case 9 -> new int[][]{{LEFT, 5}, {RIGHT, 5}, {LEFT, 12}, {RIGHT, 12}, {MID, MIDDLE}, {LEFT, 20},
                    {RIGHT, 20}, {LEFT, 27}, {RIGHT, 27}};
            case 10 -> new int[][]{{LEFT, 5}, {RIGHT, 5}, {MID, 9}, {LEFT, 12}, {RIGHT, 12}, {LEFT, 20}, {RIGHT, 20},
                    {MID, 23}, {LEFT, 27}, {RIGHT, 27}};
            default -> new int[0][];
        };
    }

    private CardArt() {
    }

    // ---- the pictures ------------------------------------------------------------------------------------------------

    /** A card's face, {@link #SIZE} square, as ARGB pixels row by row. */
    public static int[] face(CardFace card) {
        int[] px = blank();
        int ink = card.red() ? RED : BLACK;
        if (card.rank() == 1) {
            bigPip(px, card.suit(), ink);
        } else if (card.rank() >= 11) {
            court(px, card.rank(), card.suit(), ink);
        } else {
            for (int[] at : layout(card.rank())) {
                bitmap(px, PIPS[card.suit()], at[0] - 2, at[1] - 2, ink);
            }
        }
        // the rank in both corners, against the rim; the lower one upright too: turned half round, a 9 in so small a
        // font is a 6 (and a 6 a 9) to a child reading it
        String mark = card.rankMark();
        text(px, mark, X0 + 2, Y0 + 2, ink);
        text(px, mark, X1 - 1 - width(mark), Y1 - 7, ink);
        return px;
    }

    /** The back: a blue lattice inside a white rim, a gold diamond in the middle. */
    public static int[] back() {
        int[] px = blank();
        for (int y = Y0 + 2; y <= Y1 - 2; y++) {
            for (int x = X0 + 2; x <= X1 - 2; x++) {
                set(px, x, y, (x + y) % 4 == 0 || (x - y + 64) % 4 == 0 ? LATTICE : NAVY);
            }
        }
        for (int dy = -4; dy <= 4; dy++) {
            int half = 3 - Math.abs(dy) * 3 / 4;
            for (int dx = -half; dx <= half; dx++) {
                set(px, MID + dx, MIDDLE + dy, Math.abs(dx) == half || Math.abs(dy) == 4 ? PAPER : GOLD);
            }
        }
        return px;
    }

    /** The card's outline, rounded at the corners, filled white. */
    private static int[] blank() {
        int[] px = new int[SIZE * SIZE];
        for (int y = Y0; y <= Y1; y++) {
            for (int x = X0; x <= X1; x++) {
                boolean xEdge = x == X0 || x == X1;
                boolean yEdge = y == Y0 || y == Y1;
                if (xEdge && yEdge) {
                    continue; // the rounded corner
                }
                boolean inCorner = (x == X0 + 1 || x == X1 - 1) && (y == Y0 + 1 || y == Y1 - 1);
                set(px, x, y, xEdge || yEdge || inCorner ? EDGE : PAPER);
            }
        }
        return px;
    }

    /** An Ace: one big pip, 13 wide, in the middle. */
    private static void bigPip(int[] px, int suit, int ink) {
        int size = 13;
        int left = MID - size / 2;
        int top = MIDDLE - size / 2;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int in = 0;
                for (int sy = 0; sy < 4; sy++) { // four by four samples a pixel: crisp, and the same every time
                    for (int sx = 0; sx < 4; sx++) {
                        double u = ((x + (sx + 0.5) / 4.0) / size) * 2 - 1;
                        double v = ((y + (sy + 0.5) / 4.0) / size) * 2 - 1;
                        in += inPip(suit, u, v) ? 1 : 0;
                    }
                }
                if (in >= 8) {
                    set(px, left + x, top + y, ink);
                }
            }
        }
    }

    /** Whether the point (u, v), each -1..1 with v downwards, is inside a suit's shape. */
    static boolean inPip(int suit, double u, double v) {
        return switch (suit) {
            case 0 -> circle(u, v, -0.44, 0.08, 0.47) || circle(u, v, 0.44, 0.08, 0.47)
                    || below(u, v, 0, -0.98, -0.9, 0.2, 0.9, 0.2) || below(u, v, 0, 0.25, -0.38, 0.98, 0.38, 0.98);
            case 1 -> circle(u, v, -0.45, -0.38, 0.5) || circle(u, v, 0.45, -0.38, 0.5)
                    || below(u, v, 0, 0.95, -0.94, -0.2, 0.94, -0.2);
            case 2 -> Math.abs(u) / 0.78 + Math.abs(v) <= 0.98;
            default -> circle(u, v, 0, -0.5, 0.4) || circle(u, v, -0.48, 0.12, 0.4) || circle(u, v, 0.48, 0.12, 0.4)
                    || below(u, v, 0, 0.0, -0.36, 0.98, 0.36, 0.98) || circle(u, v, 0, 0.0, 0.2);
        };
    }

    private static boolean circle(double u, double v, double cu, double cv, double r) {
        return (u - cu) * (u - cu) + (v - cv) * (v - cv) <= r * r;
    }

    /** Inside the triangle (a, b, c). */
    private static boolean below(double u, double v, double au, double av, double bu, double bv, double cu, double cv) {
        double d1 = (u - bu) * (av - bv) - (au - bu) * (v - bv);
        double d2 = (u - cu) * (bv - cv) - (bu - cu) * (v - cv);
        double d3 = (u - au) * (cv - av) - (cu - au) * (v - av);
        boolean neg = d1 < 0 || d2 < 0 || d3 < 0;
        boolean pos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(neg && pos);
    }

    /**
     * A Jack, Queen or King: a gold frame, and in it the court figure's head and shoulders twice, the lower one upside
     * down, as on a real card. The robe is the suit's colour.
     */
    private static void court(int[] px, int rank, int suit, int ink) {
        int fx0 = 8;
        int fx1 = 22;
        int fy0 = 4;
        int fy1 = 28;
        for (int x = fx0; x <= fx1; x++) {
            set(px, x, fy0, GOLD);
            set(px, x, fy1, GOLD);
        }
        for (int y = fy0; y <= fy1; y++) {
            set(px, fx0, y, GOLD);
            set(px, fx1, y, GOLD);
        }
        int[] half = figure(rank, ink);
        // the figure is 13 x 11; the top one from (9, 5), the bottom one turned half round, ending at (21, 27)
        for (int y = 0; y < 11; y++) {
            for (int x = 0; x < 13; x++) {
                int c = half[y * 13 + x];
                if (c != CLEAR) {
                    set(px, 9 + x, 5 + y, c);
                    set(px, 21 - x, 27 - y, c);
                }
            }
        }
        // where the two robes meet, the suit's pip in white: a court card says its suit too
        for (int y = -3; y <= 3; y++) {
            for (int x = -3; x <= 3; x++) {
                set(px, MID + x, MIDDLE + y, ink);
            }
        }
        bitmap(px, PIPS[suit], MID - 2, MIDDLE - 2, PAPER);
    }

    /** One court figure, 13 wide and 11 tall: its hat, its face and its robe. */
    private static int[] figure(int rank, int ink) {
        String[] rows = switch (rank) {
            case 13 -> new String[]{ // the King: a crown, a beard
                    "....g.g.g....",
                    "....ggggg....",
                    "....sssss....",
                    "....sksks....",
                    "....sssss....",
                    "....bbbbb....",
                    "...ggbbbgg...",
                    "..rrrgggrrr..",
                    ".rrrrrgrrrrr.",
                    "rrrrrrgrrrrrr",
                    "rrrrrrgrrrrrr"};
            case 12 -> new String[]{ // the Queen: a small crown, long fair hair
                    ".....g.g.....",
                    "....ygggy....",
                    "...yssssy....",
                    "...ysksksy...",
                    "...yssssy....",
                    "...yysssyy...",
                    "...y.ggg.y...",
                    "..rrrgrgrrr..",
                    ".rrrrrgrrrrr.",
                    "rrrrrrrrrrrrr",
                    "rrrrrrrrrrrrr"};
            default -> new String[]{ // the Jack: a cap with a feather, short hair
                    "........gg...",
                    "...rrrrrg....",
                    "...hsssssh...",
                    "...hsksksh...",
                    "....sssss....",
                    ".....sss.....",
                    "...ggggggg...",
                    "..rrrrgrrrr..",
                    ".rrrrrgrrrrr.",
                    "rrrrrrgrrrrrr",
                    "rrrrrrgrrrrrr"};
        };
        int[] out = new int[13 * 11];
        for (int y = 0; y < 11; y++) {
            for (int x = 0; x < 13; x++) {
                out[y * 13 + x] = switch (rows[y].charAt(x)) {
                    case 'g' -> GOLD;
                    case 's' -> SKIN;
                    case 'k' -> BLACK;
                    case 'b' -> BEARD;
                    case 'y' -> BLONDE;
                    case 'h' -> HAIR;
                    case 'r' -> ink;
                    default -> CLEAR;
                };
            }
        }
        return out;
    }

    /** The width of a rank in the pixel font: 3, or 5 for "10". */
    private static int width(String text) {
        int w = 0;
        for (char c : text.toCharArray()) {
            w += FONT.get(c)[0].length() + 1;
        }
        return w - 1;
    }

    /** Text in the pixel font, from its top-left. */
    private static void text(int[] px, String text, int x, int y, int ink) {
        for (char c : text.toCharArray()) {
            String[] g = FONT.get(c);
            bitmap(px, g, x, y, ink);
            x += g[0].length() + 1;
        }
    }

    private static void bitmap(int[] px, String[] rows, int x, int y, int ink) {
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length(); c++) {
                if (rows[r].charAt(c) == '#') {
                    set(px, x + c, y + r, ink);
                }
            }
        }
    }

    private static void set(int[] px, int x, int y, int argb) {
        if (x >= 0 && y >= 0 && x < SIZE && y < SIZE) {
            px[y * SIZE + x] = argb;
        }
    }

    // ---- PNG ---------------------------------------------------------------------------------------------------------

    /** {@link #SIZE}-square ARGB pixels as a PNG: 8-bit RGBA, unfiltered, deflated, nothing else in it. */
    public static byte[] png(int[] argb) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int y = 0; y < SIZE; y++) {
            raw.write(0); // no filter
            for (int x = 0; x < SIZE; x++) {
                int p = argb[y * SIZE + x];
                raw.write(p >>> 16 & 0xFF);
                raw.write(p >>> 8 & 0xFF);
                raw.write(p & 0xFF);
                raw.write(p >>> 24 & 0xFF);
            }
        }
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(raw.toByteArray());
        deflater.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!deflater.finished()) {
            z.write(buf, 0, deflater.deflate(buf));
        }
        deflater.end();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        int32(ihdr, SIZE);
        int32(ihdr, SIZE);
        ihdr.writeBytes(new byte[]{8, 6, 0, 0, 0}); // 8 bits, RGBA, deflate, no filter method, not interlaced
        chunk(out, "IHDR", ihdr.toByteArray());
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        int32(out, data.length);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        int32(out, (int) crc.getValue());
    }

    private static void int32(ByteArrayOutputStream out, int v) {
        out.write(v >>> 24 & 0xFF);
        out.write(v >>> 16 & 0xFF);
        out.write(v >>> 8 & 0xFF);
        out.write(v & 0xFF);
    }

    // ---- the pack's files --------------------------------------------------------------------------------------------

    /** Every card's model name: the 52 faces ({@code card_ace_spades} …) and {@value CardFace#BACK}. */
    public static List<String> names() {
        List<String> out = new ArrayList<>(53);
        for (CardFace f : CardFace.deck()) {
            out.add(f.modelName());
        }
        out.add(CardFace.BACK);
        return out;
    }

    /** A card's picture id from its model name: {@code card_ace_spades} is {@code ace_spades}, the back {@code back}. */
    static String pictureId(String modelName) {
        return modelName.substring("card_".length());
    }

    /** Where a card's picture lives: {@code assets/hcm/textures/item/cards/<id>.png}. */
    public static String texturePath(String modelName) {
        return "assets/" + ClubModels.NS + "/textures/item/cards/" + pictureId(modelName) + ".png";
    }

    /**
     * The card model names whose picture a drop-in source repaints (a PNG at the card's {@link #texturePath}): HCM
     * leaves its own picture out for these and keeps its model, which then draws the owner's.
     */
    public static Set<String> repaintedBy(Collection<String> paths) {
        Set<String> names = new TreeSet<>(names());
        Set<String> out = new TreeSet<>();
        for (String p : paths) {
            Matcher m = TEXTURE.matcher(p.replace('\\', '/'));
            if (m.matches() && names.contains("card_" + m.group(1))) {
                out.add("card_" + m.group(1));
            }
        }
        return out;
    }

    private static volatile Map<String, byte[]> pictures;

    /** Every card's PNG by model name, painted once. */
    static Map<String, byte[]> pictures() {
        Map<String, byte[]> p = pictures;
        if (p == null) {
            Map<String, byte[]> made = new LinkedHashMap<>();
            for (CardFace f : CardFace.deck()) {
                made.put(f.modelName(), png(face(f)));
            }
            made.put(CardFace.BACK, png(back()));
            p = Collections.unmodifiableMap(made);
            pictures = p;
        }
        return p;
    }

    /**
     * HCM's card files by path: for each card an item definition, its model and its picture, but none of the three
     * for a name in {@code owners} (the owner's drop-in has that card) and no picture for one in {@code repainted}
     * (the drop-in has a picture of its own at that path).
     */
    public static Map<String, byte[]> files(Set<String> owners, Set<String> repainted) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> card : pictures().entrySet()) {
            String name = card.getKey();
            if (owners != null && owners.contains(name)) {
                continue;
            }
            out.put(ClubModels.itemPath(name), ("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + ClubModels.NS
                    + ":item/placeholder/" + name + "\"}}\n").getBytes(StandardCharsets.UTF_8));
            out.put(ClubModels.placeholderModelPath(name), ("{\"parent\":\"minecraft:item/generated\",\"textures\":"
                    + "{\"layer0\":\"" + ClubModels.NS + ":item/cards/" + pictureId(name) + "\"}}\n")
                    .getBytes(StandardCharsets.UTF_8));
            if (repainted == null || !repainted.contains(name)) {
                out.put(texturePath(name), card.getValue());
            }
        }
        return out;
    }
}
