package com.dierks.homecraft.games.clubhouse;

import com.dierks.homecraft.games.Game;
import com.dierks.homecraft.games.chance.sportsbook.ScoreScreens;
import com.dierks.homecraft.games.chance.sportsbook.Sportsbook;
import com.dierks.homecraft.games.world.WorldEntities;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 0.60.0, the sports corner's TVs and score ticker, drawn (the owner: "For the bar, can you make several TVs? ... make
 * a ticker someplace around the bar with a ticker that shows the scores of the games"). Real video can't be shown to
 * everyone (Bedrock players among them), so each TV shows the live scores instead, as text displays everyone sees
 * with no pack.
 *
 * <ul>
 *   <li><b>The TVs</b>: the plan's screens ({@link ClubhousePlanner#parlorTvs}) and the owner's own ({@link ClubTvs}),
 *   each showing one game big, the next every {@value ScoreScreens#TV_SECONDS} seconds ({@link ScoreScreens#tv}). Their
 *   text changes only when what they say does.</li>
 *   <li><b>The ticker</b> ({@link ClubhousePlanner#parlorTicker}): a dark strip over the Sports Board's counter, every
 *   score along it, moved {@value ScoreScreens#TICKER_STEP} letters every {@value ScoreScreens#TICKER_TICKS} ticks, and
 *   only while someone is in the Parlor to see it.</li>
 * </ul>
 * Nothing here reads Kalshi: the Sports Board does ({@link Sportsbook#scoreItems}), and this only shows what it has.
 */
final class SportsBar {

    /** A TV's text size for each block across its screen, and the ticker's text size. */
    static final float TV_SCALE_PER_BLOCK = 0.42f;
    static final float TICKER_SCALE = 1.2f;
    /** About how many letters the ticker shows at once. */
    static final int TICKER_WIDTH = 40;
    /** How thick the ticker's strip is. */
    static final double TICKER_THICK = 0.08;
    /** About how tall one line of a text display is, in blocks, at size 1. */
    static final double LINE_HIGH = 0.25;
    /** Games a TV lists ahead when none is under way. */
    static final int NEXT = 6;

    private final Clubhouse club;
    /** What is up, by slot ("tv:0", "own:2", "ticker"): its signature and its entities (the text first). */
    private final Map<String, Up> up = new HashMap<>();
    /** What each text display last said. */
    private final Map<String, String> said = new HashMap<>();
    private List<ClubTvs.Tv> owned;
    private long seconds;
    private int tickerAt;
    private int ticks;
    private boolean anyoneInParlor;
    private String tickerText = ScoreScreens.NONE;

    private record Up(String sig, UUID text, List<UUID> all) {
    }

    SportsBar(Clubhouse club) {
        this.club = club;
    }

    // ---- once a second ------------------------------------------------------------------------------

    /** Once a second while the room is open: put up what's missing, and the scores on every TV. */
    void second(ClubhouseRoom room, long now) {
        World w = room == null ? null : Bukkit.getWorld(room.world());
        if (w == null) {
            remove();
            return;
        }
        seconds++;
        Sportsbook sb = sportsbook();
        List<ScoreScreens.Item> items = sb == null ? List.of()
                : club.games().guard(sb, sb::scoreItems, List.<ScoreScreens.Item>of());
        List<ScoreScreens.Upcoming> next = sb == null ? List.of()
                : club.games().guard(sb, () -> sb.nextGames(NEXT), List.<ScoreScreens.Upcoming>of());
        Set<String> keep = new HashSet<>();
        int n = 0;
        for (ClubhousePlanner.Tv tv : room.parlorTvs()) {
            String key = "tv:" + n;
            keep.add(key);
            put(w, key, tv.toString(), tv.at().x(), tv.at().z(), () -> screen(w, tv.at(), tv.width()));
            show(key, ScoreScreens.tv(items, next, seconds, n), tv.at(), (float) (tv.width() * TV_SCALE_PER_BLOCK));
            n++;
        }
        List<ClubTvs.Tv> mine = owned(room);
        for (int i = 0; i < mine.size(); i++) {
            ClubTvs.Tv t = mine.get(i);
            if (!t.world().equalsIgnoreCase(w.getName())) {
                continue;
            }
            String key = "own:" + i;
            keep.add(key);
            ClubhousePlanner.Place3 at = new ClubhousePlanner.Place3(t.x(), t.y(), t.z(), t.yaw());
            put(w, key, t.text(), t.x(), t.z(), () -> screen(w, at, t.size()));
            show(key, ScoreScreens.tv(items, next, seconds, n + i), at, t.size() * TV_SCALE_PER_BLOCK);
        }
        ClubhousePlanner.Ticker tk = room.parlorTicker();
        if (tk != null) {
            keep.add("ticker");
            put(w, "ticker", tk.toString(), tk.at().x(), tk.at().z(), () -> ticker(w, tk));
        }
        for (String key : new ArrayList<>(up.keySet())) {
            if (!keep.contains(key)) {
                take(key);
            }
        }
        tickerText = ScoreScreens.tickerText(items, next.subList(0, Math.min(next.size(), 4)));
        anyoneInParlor = false;
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            if (room.inParlor(w.getName(), l.getX(), l.getY(), l.getZ())) {
                anyoneInParlor = true;
                break;
            }
        }
        if (!ScoreScreens.scrolls(tickerText, TICKER_WIDTH)) {
            setTicker(); // a line that fits stands still: put it up as it is
        }
    }

    /** Every tick: the ticker moves along, while someone is in the Parlor to see it. */
    void tick() {
        if (++ticks < ScoreScreens.TICKER_TICKS) {
            return;
        }
        ticks = 0;
        if (!anyoneInParlor || !up.containsKey("ticker")) {
            return;
        }
        tickerAt += ScoreScreens.TICKER_STEP;
        setTicker();
    }

    private void setTicker() {
        Up t = up.get("ticker");
        if (t == null) {
            return;
        }
        String line = ScoreScreens.window(tickerText, tickerAt, TICKER_WIDTH);
        if (line.equals(said.get("ticker")) || !(Bukkit.getEntity(t.text()) instanceof TextDisplay d)) {
            return;
        }
        said.put("ticker", line);
        d.text(Component.text(line, NamedTextColor.YELLOW));
    }

    private Sportsbook sportsbook() {
        Game g = club.games().game(com.dierks.homecraft.games.chance.sportsbook.SportsbookSettings.ID);
        return g instanceof Sportsbook sb && club.games().enabled(g) ? sb : null;
    }

    // ---- the owner's TVs --------------------------------------------------------------------------------

    /** The owner's TVs, read once (and again after a change). */
    private List<ClubTvs.Tv> owned(ClubhouseRoom room) {
        if (owned == null) {
            try {
                owned = ClubTvs.parse(room.meta(ClubTvs.KEY));
            } catch (Exception e) {
                club.log().log(Level.WARNING, "Clubhouse: could not read the score TVs", e);
                owned = new ArrayList<>();
            }
        }
        return owned;
    }

    /** {@code tv add [size]}: a TV on the block face {@code p} looks at, in the Clubhouse. */
    String add(ClubhouseRoom room, Player p, int size) {
        org.bukkit.util.RayTraceResult hit = p.rayTraceBlocks(ClubTvs.REACH);
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null) {
            return "&cLook at the middle of your TV's screen (a wall block, " + ClubTvs.REACH + " blocks away at"
                    + " most), then try that.";
        }
        org.bukkit.block.BlockFace f = hit.getHitBlockFace();
        if (f.getModY() != 0 || (f.getModX() != 0 && f.getModZ() != 0)) {
            return "&cLook at the side of a block (a wall or a screen), not a floor or a ceiling.";
        }
        org.bukkit.block.Block b = hit.getHitBlock();
        double[] at = ClubTvs.onFace(b.getX(), b.getY(), b.getZ(), f.getModX(), f.getModZ());
        String world = b.getWorld().getName();
        if (!room.contains(world, at[0], at[1], at[2])) {
            return "&cThat isn't in the Clubhouse: a score TV goes on a block inside it.";
        }
        List<ClubTvs.Tv> list = new ArrayList<>(owned(room));
        if (list.size() >= ClubTvs.MAX) {
            return "&cThere are " + ClubTvs.MAX + " score TVs already: take one down first (&etv remove&c).";
        }
        list.add(new ClubTvs.Tv(world, at[0], at[1], at[2], (float) at[3], ClubTvs.clamp(size)));
        if (!save(room, list)) {
            return "&cThat can't be saved to the database right now.";
        }
        return "&aA score TV is on that block's face, " + ClubTvs.clamp(size) + " blocks across. &7It shows the live"
                + " scores for everyone, and stays through restarts and rebuilds. &e/hcm games clubhouse tv remove"
                + " &7takes the nearest one down.";
    }

    /** {@code tv remove}: the TV nearest the admin comes down. */
    String removeNearest(ClubhouseRoom room, Player p) {
        Location l = p.getLocation();
        List<ClubTvs.Tv> list = new ArrayList<>(owned(room));
        int i = ClubTvs.nearest(list, l.getWorld() == null ? "" : l.getWorld().getName(), l.getX(), l.getY(),
                l.getZ());
        if (i < 0) {
            return "&cNo score TV of yours within " + (int) ClubTvs.NEAR + " blocks. &7(The Parlor's own TVs are part"
                    + " of the room.)";
        }
        list.remove(i);
        if (!save(room, list)) {
            return "&cThat can't be saved to the database right now.";
        }
        return "&aThat score TV is down.";
    }

    /** {@code tv list}. */
    List<String> list(ClubhouseRoom room) {
        List<String> out = new ArrayList<>(ClubTvs.lines(owned(room)));
        int[] v = room.videoWall();
        if (v != null) {
            out.add("&6Video wall &7(for a Collins screen, in " + room.world() + "): look at one corner block and"
                    + " &e/collins pos1&7, the other and &e/collins pos2&7: &f" + v[0] + " " + v[1] + " " + v[2]
                    + " &7and &f" + v[3] + " " + v[4] + " " + v[5]);
        }
        return out;
    }

    private boolean save(ClubhouseRoom room, List<ClubTvs.Tv> list) {
        try {
            room.meta(ClubTvs.KEY, list.isEmpty() ? null : ClubTvs.text(list));
        } catch (Exception e) {
            club.log().log(Level.WARNING, "Clubhouse: could not save the score TVs", e);
            return false;
        }
        owned = list;
        for (String key : new ArrayList<>(up.keySet())) {
            if (key.startsWith("own:")) {
                take(key); // put up again where they are now
            }
        }
        return true;
    }

    // ---- the entities --------------------------------------------------------------------------------

    /** Put slot {@code key} up unless it already shows {@code sig} and is alive; never loads a chunk. */
    private void put(World w, String key, String sig, double x, double z, java.util.function.Supplier<Up> make) {
        Up u = up.get(key);
        if (u != null && u.sig().equals(sig) && alive(u)) {
            return;
        }
        if (u != null) {
            take(key);
        }
        if (!w.isChunkLoaded((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4)) {
            return;
        }
        Up made = make.get();
        up.put(key, new Up(sig, made.text(), made.all()));
    }

    private static boolean alive(Up u) {
        for (UUID id : u.all()) {
            Entity e = Bukkit.getEntity(id);
            if (e == null || !e.isValid()) {
                return false;
            }
        }
        return true;
    }

    private void take(String key) {
        Up u = up.remove(key);
        said.remove(key);
        if (u == null) {
            return;
        }
        for (UUID id : u.all()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
    }

    /** Everything comes down (the room closed, moved or stopped). */
    void remove() {
        for (String key : new ArrayList<>(up.keySet())) {
            take(key);
        }
        up.clear();
        said.clear();
        owned = null;
        anyoneInParlor = false;
    }

    /** How many TVs and tickers are up, for the status. */
    int count() {
        return up.size();
    }

    /** A TV's text: flat on its screen, facing the way the screen does, lit. */
    private Up screen(World w, ClubhousePlanner.Place3 at, double width) {
        float s = (float) (width * TV_SCALE_PER_BLOCK);
        TextDisplay text = w.spawn(new Location(w, at.x() + out(at.yaw())[0], at.y(), at.z() + out(at.yaw())[1],
                WallBoards.snap(at.yaw()), 0f), TextDisplay.class, e -> {
            WorldEntities.tag(e, club, null);
            e.setBillboard(Display.Billboard.FIXED);
            e.setTransformation(scale(s));
            e.setLineWidth((int) Math.max(40, (width - 0.2) * 40 / s));
            e.setDefaultBackground(false);
            e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            e.setBrightness(new Display.Brightness(15, 15));
            e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.text(Component.text("Loading the scores…", NamedTextColor.GRAY));
        });
        return new Up("", text.getUniqueId(), List.of(text.getUniqueId()));
    }

    /** The ticker: a dark strip under the beams, and its line on the strip's face. */
    private Up ticker(World w, ClubhousePlanner.Ticker tk) {
        ClubhousePlanner.Place3 at = tk.at();
        double[] o = out(at.yaw());
        boolean alongZ = o[0] != 0; // faces east or west: it runs along z
        double len = tk.length();
        double sx = alongZ ? TICKER_THICK : len;
        double sz = alongZ ? len : TICKER_THICK;
        // the strip's middle: its face's place moved back into it by half its thickness
        double cx = at.x() - (o[0] / 0.02) * (TICKER_THICK / 2);
        double cz = at.z() - (o[1] / 0.02) * (TICKER_THICK / 2);
        BlockDisplay strip = w.spawn(new Location(w, cx - sx / 2, at.y(), cz - sz / 2), BlockDisplay.class, e -> {
            WorldEntities.tag(e, club, null);
            e.setBlock(Material.BLACK_CONCRETE.createBlockData());
            e.setBrightness(new Display.Brightness(4, 4));
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f((float) sx, (float) tk.height(), (float) sz), new Quaternionf()));
        });
        double textUp = Math.max(0.02, (tk.height() - LINE_HIGH * TICKER_SCALE) / 2);
        TextDisplay text = w.spawn(new Location(w, at.x() + o[0] * 1.5, at.y() + textUp, at.z() + o[1] * 1.5,
                WallBoards.snap(at.yaw()), 0f), TextDisplay.class, e -> {
            WorldEntities.tag(e, club, null);
            e.setBillboard(Display.Billboard.FIXED);
            e.setTransformation(scale(TICKER_SCALE));
            e.setLineWidth((int) (len * 40 / TICKER_SCALE) + 40);
            e.setDefaultBackground(false);
            e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            e.setBrightness(new Display.Brightness(15, 15));
            e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.text(Component.text(ScoreScreens.NONE, NamedTextColor.YELLOW));
        });
        said.remove("ticker");
        return new Up("", text.getUniqueId(), List.of(text.getUniqueId(), strip.getUniqueId()));
    }

    /** {dx, dz} a hair out of a face that faces {@code yaw}: south (0) +z, north (180) -z, west (90) -x, east +x. */
    static double[] out(float yaw) {
        int[] a = ClubhouseRoom.ahead(yaw);
        return new double[]{a[0] * 0.02, a[1] * 0.02};
    }

    /** Put a TV's lines up when they changed, centred up and down on its screen's middle. */
    private void show(String key, List<ScoreScreens.Line> lines, ClubhousePlanner.Place3 at, float scale) {
        Up u = up.get(key);
        if (u == null || lines == null || lines.isEmpty()) {
            return;
        }
        StringBuilder sig = new StringBuilder();
        for (ScoreScreens.Line l : lines) {
            sig.append(l.kind()).append(':').append(l.text()).append('\n');
        }
        if (sig.toString().equals(said.get(key)) || !(Bukkit.getEntity(u.text()) instanceof TextDisplay d)) {
            return;
        }
        said.put(key, sig.toString());
        d.text(component(lines));
        Location l = d.getLocation();
        l.setY(at.y() - textHigh(lines.size(), scale) / 2);
        d.teleport(l);
    }

    /** How tall {@code n} lines are at {@code scale} (blocks; pure). */
    static double textHigh(int n, float scale) {
        return n * LINE_HIGH * scale;
    }

    /** A TV's lines: the heading in gold, each side in bold white (a finished game's winner in green), notes grey. */
    static Component component(List<ScoreScreens.Line> lines) {
        Component out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            ScoreScreens.Line l = lines.get(i);
            Component c = switch (l.kind()) {
                case HEAD -> Component.text(l.text(), NamedTextColor.GOLD);
                case TEAM -> Component.text(l.text(), NamedTextColor.WHITE).decorate(TextDecoration.BOLD);
                case LEAD -> Component.text(l.text(), NamedTextColor.GREEN).decorate(TextDecoration.BOLD);
                case NOTE -> Component.text(l.text(), NamedTextColor.GRAY);
            };
            out = out.append(i == 0 ? c : Component.newline().append(c));
        }
        return out;
    }

    private static Transformation scale(float s) {
        return new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf());
    }
}
