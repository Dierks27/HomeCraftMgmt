package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.gen.api.Box;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A top-down floor plan of the generated Clubhouse's wing (0.59.0, for the owner and whoever lays the rooms out: "a
 * picture of the rooms from above"): one picture per floor, a square per block coloured by what stands there (the
 * tallest thing from the floor up to head height, so a table reads as a table and a rug as a rug), and markers over it
 * for the places that are entities, not blocks (cabinets, machines, seats, pedestals, the party things). Test-side
 * only: nothing in the plugin draws it. {@code FloorPlansTest} writes the PNGs when asked.
 */
final class FloorPlans {

    /** Pixels a block. */
    static final int CELL = 22;
    /** The legend's width beside the plan. */
    static final int LEGEND = 300;
    /** The margin round the plan (coordinates go in it). */
    static final int MARGIN = 28;

    /** A marker: a place over the plan (block offsets from the box's corner, fractional), a short tag and its colour. */
    record Marker(double x, double z, String tag, Color colour, String what, float yaw) {
    }

    /** One floor to draw: its name, its floor's layer (box offset), and the markers on it. */
    record Floor(String name, int floorJ, int x0, int x1, int z0, int z1, List<Marker> markers) {
    }

    private FloorPlans() {
    }

    /** The wing's two floors in {@code box}: downstairs (the Arcade Room and the room east of it) and upstairs. */
    static List<Floor> floors(Box box) {
        List<Floor> out = new ArrayList<>();
        out.add(new Floor("downstairs: Arcade Room (west) and the east side room", 0, 0, 31, ClubhousePlanner.MAIN_Z - 1,
                box.sizeZ() - 1, downstairs(box)));
        out.add(new Floor("upstairs: Game Parlor (west) and the secret trophy room (east)", ClubhousePlanner.UP_FLOOR,
                0, 31, ClubhousePlanner.MAIN_Z, box.sizeZ() - 1, upstairs(box)));
        return out;
    }

    private static List<Marker> downstairs(Box box) {
        List<Marker> m = new ArrayList<>();
        int n = 0;
        for (ClubhousePlanner.Place3 c : ClubhousePlanner.cabinets(box)) {
            m.add(marker(box, c, "C" + n++, new Color(0xE040FB), "arcade cabinet place"));
        }
        int p = 0;
        for (ClubhousePlanner.Pedestal ped : ClubhousePlanner.pedestals(box)) {
            m.add(marker(box, ped.trophy(), p < 3 ? "P" + (p + 1) : "R", new Color(0xFFC400),
                    p < 3 ? "podium cup (1st, 2nd, 3rd)" : "record cup pedestal"));
            p++;
        }
        m.add(marker(box, ClubhousePlanner.secretDoor(box), "SD", new Color(0x5D4037), "secret room's door"));
        seats(m, box, ClubhousePlanner.arcadeSeats(box));
        seats(m, box, ClubhousePlanner.trophySeats(box));
        extraDown(m, box);
        return m;
    }

    private static List<Marker> upstairs(Box box) {
        List<Marker> m = new ArrayList<>();
        List<ClubhousePlanner.Place3> machines = ClubhousePlanner.parlorMachines(box);
        for (int n = 0; n < machines.size(); n++) {
            String g = ClubhousePlanner.PARLOR_GAMES.get(n);
            m.add(marker(box, machines.get(n), shortName(g), new Color(0x00E5FF), "Parlor machine / table: " + g));
        }
        List<ClubhousePlanner.Place3> row = ClubhousePlanner.parlorRow(box);
        for (int n = 0; n < row.size(); n++) {
            String g = ClubhousePlanner.PARLOR_ROW_GAMES.get(n);
            m.add(marker(box, row.get(n), shortName(g), new Color(0x76FF03), "back-row slot machine: " + g));
        }
        m.add(marker(box, ClubhousePlanner.parlorTicket(box), "TK", new Color(0xFFEA00), "Scratch Ticket booth"));
        for (ClubhousePlanner.Board b : ClubhousePlanner.parlorBoards(box)) {
            m.add(marker(box, b.at(), "SB", new Color(0xFF6D00), "Sports Board (on the wall, " + b.width() + " wide)"));
        }
        ClubhouseSite.Spot a = ClubhousePlanner.parlorArrival(box);
        m.add(new Marker(a.x() - box.minX(), a.z() - box.minZ(), "IN", Color.WHITE, "Parlor arrival", a.yaw()));
        ClubhouseSite.Spot s = ClubhousePlanner.secretArrival(box);
        m.add(new Marker(s.x() - box.minX(), s.z() - box.minZ(), "IN", Color.WHITE, "secret room arrival", s.yaw()));
        ClubhousePlanner.Place3 c = ClubhousePlanner.secretCentre(box);
        m.add(marker(box, c, "GC", new Color(0xFFD600), "the big gold cup"));
        seats(m, box, ClubhousePlanner.parlorSeats(box));
        return m;
    }

    /** Markers a later plan adds downstairs (none before plan 15): filled in by {@link #extraDownstairs}. */
    private static void extraDown(List<Marker> m, Box box) {
        m.addAll(extraDownstairs(box));
    }

    /** Plan 15: the Party Room's places, and the ball pit, in a grown wing (none at the old size). */
    static List<Marker> extraDownstairs(Box box) {
        List<Marker> m = new ArrayList<>();
        if (!ClubhousePlanner.grown(box)) {
            return m;
        }
        for (ClubhousePlanner.Place3 d : ClubhousePlanner.miniDoors(box)) {
            m.add(marker(box, d, "MW", new Color(0xD500F9), "Mini World doorway (you shrink through it)"));
        }
        m.add(marker(box, ClubhousePlanner.mazeGoal(box), "GO", new Color(0xFFEB3B), "maze prize: a cake and a star"));
        m.add(centre(box, ClubhousePlanner.trampoline(box), "TR", new Color(0x76FF03), "trampoline (it bounces you)"));
        m.add(centre(box, ClubhousePlanner.ballPitInside(box), "BP", new Color(0x40C4FF), "ball pit (the balls move)"));
        m.add(new Marker(ClubhousePlanner.HOUSE_X0 + 2.0, ClubhousePlanner.HOUSE_Z0 + 4.0, "DH", new Color(0xFF80AB),
                "doll's house (two floors, up the porch stairs)", 0f));
        m.add(new Marker((ClubhousePlanner.DANCE_X0 + ClubhousePlanner.DANCE_X1 + 1) / 2.0,
                (ClubhousePlanner.DANCE_Z0 + ClubhousePlanner.DANCE_Z1 + 1) / 2.0, "PF", new Color(0xFFFFFF),
                "party floor (kept open for party games)", 0f));
        return m;
    }

    private static Marker centre(Box box, Box b, String tag, Color c, String what) {
        return new Marker((b.minX() + b.maxX() + 1) / 2.0 - box.minX(), (b.minZ() + b.maxZ() + 1) / 2.0 - box.minZ(), tag,
                c, what, 0f);
    }

    private static void seats(List<Marker> m, Box box, List<ClubhousePlanner.Seat> seats) {
        for (ClubhousePlanner.Seat s : seats) {
            m.add(new Marker(s.x() - box.minX(), s.z() - box.minZ(), "", new Color(0xFFFFFF), "seat (cushion; the"
                    + " tick shows the way the sitter faces)", s.yaw()));
        }
    }

    static Marker marker(Box box, ClubhousePlanner.Place3 p, String tag, Color c, String what) {
        return new Marker(p.x() - box.minX(), p.z() - box.minZ(), tag, c, what, p.yaw());
    }

    static String shortName(String game) {
        return switch (game) {
            case "ore_slots" -> "OS";
            case "wheel" -> "WH";
            case "coin_flip" -> "CF";
            case "twenty_one" -> "21";
            case "higher_lower" -> "HL";
            case "bingo" -> "BN";
            case "video_poker" -> "VP";
            case "creeper_fuse" -> "CR";
            case "holdem" -> "TH";
            case "nether_slots" -> "NS";
            case "farm_slots" -> "FS";
            case "motherlode" -> "ML";
            case "deep_mine" -> "DM";
            case "mob_slots" -> "MS";
            default -> game.length() <= 2 ? game.toUpperCase() : game.substring(0, 2).toUpperCase();
        };
    }

    // ---- drawing ----------------------------------------------------------------------------------------------------

    /** The picture of {@code f} in the plan of {@code site}. */
    static BufferedImage draw(ClubhouseSite site, Floor f) {
        Box box = site.box();
        Map<Long, String> b = RegularSpots.blocks(site.plan());
        int w = f.x1() - f.x0() + 1;
        int d = f.z1() - f.z0() + 1;
        int width = MARGIN * 2 + w * CELL + LEGEND;
        int height = MARGIN * 2 + d * CELL + 40;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x1E1E24));
        g.fillRect(0, 0, width, height);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.setColor(Color.WHITE);
        g.drawString(f.name() + "  (plan " + ClubhousePlanner.ALGO + ", hash " + site.plan().hash() + ")", MARGIN,
                MARGIN - 8);
        Map<String, Color> legend = new LinkedHashMap<>();
        for (int i = f.x0(); i <= f.x1(); i++) {
            for (int k = f.z0(); k <= f.z1(); k++) {
                int px = MARGIN + (i - f.x0()) * CELL;
                int pz = MARGIN + 10 + (k - f.z0()) * CELL;
                String top = null;
                int topJ = -1;
                for (int j = f.floorJ() + 3; j >= f.floorJ(); j--) {
                    String s = b.get(RegularSpots.key(box.minX() + i, box.minY() + j, box.minZ() + k));
                    if (s == null || invisible(s)) {
                        continue;
                    }
                    if (j >= f.floorJ() + 3 && hung(s)) {
                        continue;
                    }
                    top = s;
                    topJ = j;
                    break;
                }
                Color c = top == null ? new Color(0x101014) : colour(top);
                if (top != null && topJ > f.floorJ() + 1 && !wall(top)) {
                    c = c.brighter();
                }
                g.setColor(c);
                g.fillRect(px, pz, CELL, CELL);
                if (top != null) {
                    legend.putIfAbsent(label(top), colour(top));
                    if (topJ > f.floorJ() + 1 && !ClubhousePlanner.id(top).endsWith("_carpet")) {
                        g.setColor(new Color(0, 0, 0, 90)); // raised: a dark edge, so a table or a wall reads raised
                        g.drawRect(px + 2, pz + 2, CELL - 5, CELL - 5);
                    }
                }
                g.setColor(new Color(255, 255, 255, 18));
                g.drawRect(px, pz, CELL, CELL);
            }
        }
        // coordinates (box offsets) every four blocks
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        g.setColor(new Color(0xB0B0B0));
        for (int i = f.x0(); i <= f.x1(); i += 4) {
            g.drawString("x" + i, MARGIN + (i - f.x0()) * CELL + 2, MARGIN + 8);
        }
        for (int k = f.z0(); k <= f.z1(); k += 2) {
            g.drawString("" + k, 2, MARGIN + 10 + (k - f.z0()) * CELL + 15);
        }
        // the markers
        Map<String, Color> marks = new LinkedHashMap<>();
        for (Marker m : f.markers()) {
            double cx = MARGIN + (m.x() - f.x0()) * CELL;
            double cz = MARGIN + 10 + (m.z() - f.z0()) * CELL;
            if (m.tag().isEmpty()) { // a seat: a small ring and a tick the way the sitter faces
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(2f));
                g.drawOval((int) cx - 5, (int) cz - 5, 10, 10);
                double rad = Math.toRadians(m.yaw());
                g.drawLine((int) cx, (int) cz, (int) (cx - Math.sin(rad) * 10), (int) (cz + Math.cos(rad) * 10));
                g.setStroke(new BasicStroke(1f));
                marks.putIfAbsent("( ) " + m.what(), Color.WHITE);
                continue;
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            FontMetrics fm = g.getFontMetrics();
            int tw = Math.max(16, fm.stringWidth(m.tag()) + 6);
            g.setColor(new Color(0, 0, 0, 170));
            g.fillRoundRect((int) cx - tw / 2 - 1, (int) cz - 8, tw + 2, 16, 8, 8);
            g.setColor(m.colour());
            g.drawRoundRect((int) cx - tw / 2 - 1, (int) cz - 8, tw + 2, 16, 8, 8);
            g.drawString(m.tag(), (int) cx - fm.stringWidth(m.tag()) / 2, (int) cz + 4);
            marks.putIfAbsent(m.tag().replaceAll("[0-9]+$", "#").replaceAll("^#$", m.tag()) + "  " + m.what(), m.colour());
        }
        // the legend: blocks, then markers
        int lx = MARGIN * 2 + w * CELL;
        int ly = MARGIN + 10;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.setColor(Color.WHITE);
        g.drawString("markers", lx, ly);
        ly += 6;
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        for (Map.Entry<String, Color> e : marks.entrySet()) {
            ly += 13;
            g.setColor(e.getValue());
            g.fillRect(lx, ly - 9, 9, 9);
            g.setColor(new Color(0xDDDDDD));
            g.drawString(trim(e.getKey(), 52), lx + 14, ly);
        }
        ly += 22;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.setColor(Color.WHITE);
        g.drawString("blocks (brighter: raised)", lx, ly);
        ly += 6;
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        for (Map.Entry<String, Color> e : legend.entrySet()) {
            if (ly > height - 12) {
                break;
            }
            ly += 12;
            g.setColor(e.getValue());
            g.fillRect(lx, ly - 9, 9, 9);
            g.setColor(new Color(0xDDDDDD));
            g.drawString(trim(e.getKey(), 52), lx + 14, ly);
        }
        g.dispose();
        return img;
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    /** Write each floor of {@code site} as {@code <prefix>-down.png} and {@code <prefix>-up.png} in {@code dir}. */
    static List<File> write(ClubhouseSite site, File dir, String prefix) throws IOException {
        List<File> out = new ArrayList<>();
        dir.mkdirs();
        String[] names = {"down", "up"};
        List<Floor> fs = floors(site.box());
        for (int n = 0; n < fs.size(); n++) {
            File file = new File(dir, prefix + "-" + names[n] + ".png");
            ImageIO.write(draw(site, fs.get(n)), "png", file);
            out.add(file);
        }
        return out;
    }

    // ---- what a block looks like from above ------------------------------------------------------------------------

    private static boolean invisible(String s) {
        String id = ClubhousePlanner.id(s);
        return id.equals("minecraft:barrier") || id.equals("minecraft:light");
    }

    /** Things hung from the ceiling: not on the floor plan at head height. */
    private static boolean hung(String s) {
        String id = ClubhousePlanner.id(s);
        return id.endsWith("chain") || id.equals("minecraft:lantern") || id.endsWith("froglight")
                || id.equals("minecraft:shroomlight");
    }

    private static boolean wall(String s) {
        String id = ClubhousePlanner.id(s);
        return id.equals(ClubhousePlanner.PLANKS) || id.equals(ClubhousePlanner.PLASTER)
                || id.equals("minecraft:stripped_dark_oak_log");
    }

    private static String label(String s) {
        return ClubhousePlanner.id(s).replace("minecraft:", "");
    }

    private static final Map<String, Color> DYES = Map.ofEntries(Map.entry("white", new Color(0xE9ECEC)),
            Map.entry("orange", new Color(0xF07613)), Map.entry("magenta", new Color(0xBD44B3)),
            Map.entry("light_blue", new Color(0x3AAFD9)), Map.entry("yellow", new Color(0xF8C527)),
            Map.entry("lime", new Color(0x70B919)), Map.entry("pink", new Color(0xED8DAC)),
            Map.entry("gray", new Color(0x3E4447)), Map.entry("light_gray", new Color(0x8E8E86)),
            Map.entry("cyan", new Color(0x158991)), Map.entry("purple", new Color(0x792AAC)),
            Map.entry("blue", new Color(0x35399D)), Map.entry("brown", new Color(0x724728)),
            Map.entry("green", new Color(0x546D1B)), Map.entry("red", new Color(0xA12722)),
            Map.entry("black", new Color(0x141519)));

    /** A block's colour from above: its dye for anything coloured, else a fixed colour for the room's blocks. */
    static Color colour(String s) {
        String id = label(s);
        for (String dye : new String[]{"light_blue", "light_gray", "white", "orange", "magenta", "yellow", "lime",
                "pink", "gray", "cyan", "purple", "blue", "brown", "green", "red", "black"}) {
            if (id.startsWith(dye + "_") && (id.endsWith("_carpet") || id.endsWith("_wool") || id.endsWith("_concrete")
                    || id.endsWith("_glass") || id.endsWith("_terracotta") || id.endsWith("_banner")
                    || id.endsWith("_bed") || id.endsWith("_candle"))) {
                return DYES.get(dye);
            }
        }
        return switch (id) {
            case "dark_oak_planks", "dark_oak_slab", "dark_oak_stairs", "dark_oak_fence", "dark_oak_trapdoor" ->
                    new Color(0x4A3221);
            case "stripped_dark_oak_wood", "stripped_dark_oak_log" -> new Color(0x6B4A32);
            case "calcite" -> new Color(0xD9D9D2);
            case "spruce_planks" -> new Color(0x7A5A36);
            case "smooth_quartz", "quartz_pillar" -> new Color(0xF0EAE0);
            case "polished_deepslate", "polished_blackstone" -> new Color(0x3A3A40);
            case "sea_lantern", "glowstone", "ochre_froglight", "verdant_froglight", "pearlescent_froglight",
                 "shroomlight" -> new Color(0xF5F0C0);
            case "glass", "glass_pane", "tinted_glass" -> new Color(0x9FD8E8);
            case "gold_block" -> new Color(0xF5C400);
            case "iron_block" -> new Color(0xD0D0D0);
            case "waxed_copper_block", "waxed_cut_copper" -> new Color(0xC2703F);
            case "moss_carpet" -> new Color(0x8BC34A); // grass to walk on
            case "moss_block", "flowering_azalea", "azalea", "oak_leaves", "azalea_leaves",
                 "flowering_azalea_leaves" -> new Color(0x2E5E1E); // a hedge
            case "slime_block" -> new Color(0x7DD36B);
            case "honey_block" -> new Color(0xF2A81E);
            case "bookshelf" -> new Color(0x8A6A3A);
            case "bamboo_slab", "bamboo_planks", "bamboo_mosaic", "bamboo_stairs" -> new Color(0xD9C25A);
            case "cherry_stairs", "cherry_planks", "cherry_slab" -> new Color(0xE3A9A4);
            case "warped_stairs", "warped_planks", "warped_slab" -> new Color(0x3A8E8C);
            case "acacia_stairs", "acacia_planks", "acacia_slab" -> new Color(0xBA6337);
            case "birch_slab", "birch_planks", "birch_stairs" -> new Color(0xD7C185);
            case "oak_planks", "oak_slab", "oak_stairs", "oak_fence" -> new Color(0xA88754);
            default -> {
                int h = id.hashCode();
                yield new Color(0x60 + Math.floorMod(h, 0x80), 0x60 + Math.floorMod(h >> 8, 0x80),
                        0x60 + Math.floorMod(h >> 16, 0x80));
            }
        };
    }
}
