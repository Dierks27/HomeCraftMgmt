package com.dierks.homecraft.games.golf;

import com.dierks.homecraft.games.EndReason;
import com.dierks.homecraft.games.GamesService;
import com.dierks.homecraft.games.GeneratedCourses;
import com.dierks.homecraft.games.Refusal;
import com.dierks.homecraft.games.RewardKind;
import com.dierks.homecraft.games.ScoreResult;
import com.dierks.homecraft.games.gen.api.GenCopy;
import com.dierks.homecraft.games.gen.api.GenTag;
import com.dierks.homecraft.games.world.KitItems;
import com.dierks.homecraft.gui.arcade.BigWin;
import com.dierks.homecraft.gui.games.daily.DailyLookup;
import com.dierks.homecraft.gui.games.daily.DailyText;
import com.dierks.homecraft.gui.games.golf.GolfGroupCardMenu;
import com.dierks.homecraft.gui.games.golf.GolfScorecardMenu;
import com.dierks.homecraft.storage.GamesDao;
import com.dierks.homecraft.util.Sounds;
import com.dierks.homecraft.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * The rounds being played (spec §12): the kit, the ball on the move, each hole's end, and the
 * finish with its scores and rewards.
 *
 * <p><b>A round</b> starts at the course screen's Start: the gate, then the world session into the
 * first tee (the player's things are kept safe and the kit handed out when they arrive). Each hole
 * puts the ball on its tee; the player putts with a club — any click, when the ball is still and
 * they are within {@value #REACH} blocks — and the ball rolls under {@link BallPhysics} one tick at
 * a time. In the cup, or picked up at par + {@code max_over_par}, the hole ends: a title, a chat
 * line and the scorecard, then the next tee a few seconds later (or at once with "Next hole").
 * After the last hole the score goes on the course's board, the rewards are paid (while the
 * player is still in the game, where they earn), and the session ends; the final scorecard, with
 * "Play again", opens once they are home.
 *
 * <p>Several players can be on one course at once, each with their own ball; balls don't meet.
 * Everything here runs inside the game's guard (the tick, the kit, the session callbacks), so a
 * bug switches golf off — and the framework ends its sessions — rather than touching anything
 * else.
 *
 * <p>A Fresh course (GEN-SPEC §3.4) is played on the layout the round started on: a round keeps
 * counting while that layout still stands, even after the next set's layout went live, and what
 * it records and pays is {@link GolfFinish}'s.
 *
 * <p>Golf together's turn flow (D4) is {@link GolfGroups}'s; this class is its server side (the
 * {@link GolfGroups.Port}): a group player's round is finished and recorded at their own last hole,
 * and they go home when the group's round is over.
 */
public final class GolfRounds {

    /** How close to the ball a putt may be made from, in blocks. */
    static final double REACH = 4.0;
    /** The scorecard between holes shows this long before the next tee (ticks). */
    static final long BETWEEN_TICKS = 100;
    /** How often the status line is shown while the ball is still (ticks). */
    static final long STATUS_TICKS = 40;
    /** How often Bedrock players near a ball are shown it (ticks). */
    static final long BEDROCK_TICKS = 20;
    /** Golf together's once-a-second check (ticks). */
    static final long SECOND_TICKS = 20;

    /** The clubs, power 1 to 5: their names and items (hoes look like clubs). */
    static final String[] CLUBS = {"Tap", "Putt", "Chip", "Swing", "Drive"};
    private static final Material[] CLUB_ITEMS = {Material.WOODEN_HOE, Material.STONE_HOE, Material.IRON_HOE,
            Material.GOLDEN_HOE, Material.DIAMOND_HOE};
    /** Kit actions. */
    static final String CLUB = "club";
    static final String GO = "go";
    static final String RESET = "reset";
    static final String CARD = "card";
    static final String LEAVE = "leave";

    private final MiniGolf golf;
    private final Map<UUID, LiveRound> live = new HashMap<>();
    /** Golf together (D4): the groups being played and their turn flow. */
    private final GolfGroups groups;
    private long tick;

    GolfRounds(MiniGolf golf) {
        this.golf = golf;
        this.groups = new GolfGroups(new Server());
    }

    private GamesService games() {
        return golf.games();
    }

    /** The player's round, or {@code null}. */
    LiveRound round(UUID player) {
        return live.get(player);
    }

    /** Every round in progress. */
    Collection<LiveRound> all() {
        return new ArrayList<>(live.values());
    }

    /** The scorecard of the player's round as it stands, or {@code null}. */
    public GolfCard card(UUID player) {
        LiveRound r = live.get(player);
        return r == null ? null : r.card();
    }

    // ---- starting ---------------------------------------------------------------------------------

    /**
     * Take the player to the course's first tee (after the gate). The kit is handed out when they
     * arrive.
     *
     * @return whether the trip started (the player has been told why not)
     */
    boolean start(Player player, String courseId) {
        return start(player, courseId, null);
    }

    /** {@link #start(Player, String)}, into {@code group}'s round when it is golf together. */
    private boolean start(Player player, String courseId, GolfGroup group) {
        GolfCourse course = golf.playableCourse(courseId);
        if (course == null) {
            games().tell(player, Refusal.of("That course is closed right now."));
            return false;
        }
        Refusal refusal = games().canOpen(player, golf);
        if (refusal != null) {
            games().tell(player, refusal);
            return false;
        }
        World world = golf.plugin().getServer().getWorld(course.world());
        if (world == null) {
            games().tell(player, Refusal.of("That course's world isn't loaded right now."));
            return false;
        }
        ItemStack look = golf.ballItem(player); // what they own is read once, here
        int maxOverPar = golf.settings().maxOverPar();
        GolfCourse.Tee tee = course.hole(1).tee();
        Location start = new Location(world, tee.x(), tee.y(), tee.z(), tee.yaw(), 0);
        return games().sessions().enter(player, golf, course.id(), start,
                p -> begin(p, course, look, maxOverPar, group));
    }

    /**
     * Golf together (D4): everyone in {@code players} goes to hole 1 of {@code course} at once, in one
     * group. A player who can't go (told why) is simply not in it; the rest play.
     *
     * @return whether anyone went
     */
    boolean startGroup(long partyId, GolfCourse course, java.util.List<Player> players) {
        java.util.Map<UUID, String> names = new java.util.LinkedHashMap<>();
        for (Player p : players) {
            if (names.size() < GolfGroup.MAX) {
                names.put(p.getUniqueId(), p.getName());
            }
        }
        return !names.isEmpty() && groups.start(partyId, course, names);
    }

    /**
     * They're in, saved and cleared: the kit, the ball on the first tee — unless an admin closed
     * the course or changed its layout while they were on their way, when they go straight home.
     */
    private void begin(Player p, GolfCourse course, ItemStack look, int maxOverPar, GolfGroup group) {
        GolfCourse now = golf.playableCourse(course.id());
        boolean standing = course.generated() && games().generated().standing(course.gen());
        if ((now == null || now.rev() != course.rev()) && !standing) {
            p.sendMessage(Text.of(now != null ? changedLine(course) : course.generated()
                    ? games().generated().closedLine(course.id()) : "&7" + course.name() + " was closed by an admin."));
            games().sessions().leave(p, EndReason.ADMIN);
            return;
        }
        LiveRound r = new LiveRound(p.getUniqueId(), course, new GolfRun(course.pars(), maxOverPar),
                new BallView(golf.plugin(), golf, p.getUniqueId(), look));
        LiveRound old = live.put(p.getUniqueId(), r);
        if (old != null) {
            old.view.remove();
        }
        r.group = groups.joins(p.getUniqueId(), group); // a round alone: out of any old group first
        if (group != null && r.group == null) {
            p.sendMessage(Text.of("&7Your group went on without you, so this round is just yours."));
        }
        giveKit(p);
        p.sendMessage(Text.of("&d" + course.name() + " &7- " + MiniGolf.holes(course.holes().size()) + ", par "
                + course.par() + ". &7Click with a club to putt the way you look."));
        startHole(p, r, false);
    }

    private void giveKit(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < CLUBS.length; i++) {
            int power = i + 1;
            inv.setItem(i, KitItems.item(golf, CLUB + power, CLUB_ITEMS[i], "&a" + CLUBS[i] + " &7- power " + power,
                    "&7Any click putts your ball", "&7the way you look.", "&7Stand within 4 blocks of it."));
        }
        inv.setItem(5, KitItems.item(golf, GO, Material.COMPASS, "&bGo to my ball",
                "&7Takes you right next to it."));
        inv.setItem(6, KitItems.item(golf, RESET, Material.RECOVERY_COMPASS, "&eReset ball &7(+1 stroke)",
                "&7Puts it back where you", "&7last putted from."));
        inv.setItem(7, KitItems.item(golf, CARD, Material.PAPER, "&fScorecard", "&7Your strokes so far."));
        inv.setItem(8, KitItems.item(golf, LEAVE, Material.BARRIER, "&cLeave game",
                "&7Click twice to leave.", "&7Your things come back."));
        inv.setHeldItemSlot(1);
    }

    /** The ball on the current hole's tee; the player there too unless it's the first (they're already there). */
    private void startHole(Player p, LiveRound r, boolean teleport) {
        r.state = LiveRound.State.PLAYING;
        World world = p.getWorld();
        GolfCourse.Hole h = r.hole();
        world.getChunkAt((int) Math.floor(h.tee().x()) >> 4, (int) Math.floor(h.tee().z()) >> 4); // the tee's chunk
        world.getChunkAt(h.cup().x() >> 4, h.cup().z() >> 4); // and the cup's, to read how high it is
        LiveBlocks blocks = new LiveBlocks(world);
        r.tee(blocks);
        BallPhysics.settle(r.ball, blocks);
        r.markSpot();
        if (teleport) {
            games().sessions().teleport(p, new Location(world, h.tee().x(), h.tee().y(), h.tee().z(), h.tee().yaw(), 0));
        }
        show(p, r);
        int n = r.run.hole() + 1;
        p.showTitle(Title.title(Text.of("&dHole " + n), Text.of("&7Par " + h.par()
                + (r.run.holes() > 1 ? " &8· &7" + n + " of " + r.run.holes() : "")),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1500), Duration.ofMillis(400))));
    }

    // ---- the tick ----------------------------------------------------------------------------------

    /** Every tick: roll the moving balls, keep the still ones showing, tell Bedrock players what's near. */
    void tick() {
        tick++;
        for (LiveRound r : new ArrayList<>(live.values())) {
            Player p = golf.plugin().getServer().getPlayer(r.player);
            if (p == null) {
                drop(r);
                continue;
            }
            if (r.state == LiveRound.State.PLAYING) {
                if (r.ball.moving()) {
                    roll(p, r);
                } else {
                    if (!r.view.shown()) {
                        show(p, r); // its chunk unloaded it: back it comes
                    }
                    if (tick % STATUS_TICKS == 0) {
                        status(p, r);
                    }
                }
            }
            if (tick % BEDROCK_TICKS == 0 && live.get(r.player) == r) {
                r.view.showToBedrock();
            }
        }
        if (tick % SECOND_TICKS == 0) {
            groups.second(); // after the offline rounds went: nobody waited for who isn't there
        }
    }

    private void roll(Player p, LiveRound r) {
        LiveRound.Result result = r.roll(new LiveBlocks(p.getWorld()));
        show(p, r);
        switch (result) {
            case IN_CUP -> inCup(p, r);
            case BACK -> p.sendMessage(Text.of(r.outcome == BallPhysics.Outcome.WATER
                    ? "&bSplash! &7Back to your last spot, &f+1 stroke&7."
                    : "&eOut of bounds! &7Back to your last spot, &f+1 stroke&7."));
            case PICKED_UP -> pickedUp(p, r);
            case STILL -> status(p, r);
            case ROLLING -> {
                // on its way
            }
        }
        if (result == LiveRound.Result.BACK) {
            Sounds.miss(p);
        }
    }

    private void show(Player p, LiveRound r) {
        r.view.move(p.getWorld(), r.ball.x(), r.ball.y(), r.ball.z(), r.yaw);
    }

    /** The action bar while the ball is still: the hole, the strokes, how far the cup is. */
    private void status(Player p, LiveRound r) {
        GolfCourse.Hole h = r.hole();
        double dx = h.cup().x() + 0.5 - r.ball.x();
        double dz = h.cup().z() + 0.5 - r.ball.z();
        long far = Math.round(Math.hypot(dx, dz));
        int clock = r.group == null ? -1 : r.group.clock();
        p.sendActionBar(Text.of("&dHole " + (r.run.hole() + 1) + "/" + r.run.holes() + " &7· par " + h.par()
                + " · &fstrokes " + r.run.strokes() + " &7· cup " + far + (far == 1 ? " block" : " blocks") + " away"
                + (clock >= 0 ? " &8· &e" + GolfGroup.clockText(clock) + " left" : "")));
    }

    // ---- the kit -----------------------------------------------------------------------------------

    /** A kit click (already cancelled by the kit guard). */
    void kit(Player p, String action) {
        LiveRound r = live.get(p.getUniqueId());
        if (r == null || action == null) {
            return;
        }
        if (action.startsWith(CLUB)) {
            int power;
            try {
                power = Integer.parseInt(action.substring(CLUB.length()));
            } catch (NumberFormatException e) {
                return;
            }
            putt(p, r, power);
            return;
        }
        switch (action) {
            case GO -> goToBall(p, r);
            case RESET -> reset(p, r);
            case CARD -> {
                if (r.group != null) {
                    new GolfGroupCardMenu(golf.plugin(), golf, p, groups.card(r.group), null).open(p);
                } else {
                    new GolfScorecardMenu(golf.plugin(), golf, p, r.card(), null).open(p);
                }
            }
            default -> {
                // "leave" is the kit guard's; anything else isn't ours
            }
        }
    }

    private void putt(Player p, LiveRound r, int power) {
        if (r.state != LiveRound.State.PLAYING) {
            notNow(p, r);
            return;
        }
        if (r.ball.moving()) {
            p.sendActionBar(Text.of("&7Wait for the ball to stop."));
            return;
        }
        Location at = p.getLocation();
        double dx = r.ball.x() - at.getX();
        double dy = r.ball.y() - at.getY();
        double dz = r.ball.z() - at.getZ();
        if (dx * dx + dy * dy + dz * dz > REACH * REACH) {
            p.sendActionBar(Text.of("&eGet within 4 blocks of your ball &7- or use &bGo to my ball&7."));
            Sounds.refused(p);
            return;
        }
        r.putt(at.getYaw(), power);
        p.playSound(at, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 0.9f + 0.15f * power);
        p.sendActionBar(Text.of("&fStroke " + r.run.strokes() + " &7· " + CLUBS[Math.max(1, Math.min(5, power)) - 1]));
    }

    /** Teleport (ours) to a safe spot beside the ball, facing it. */
    private void goToBall(Player p, LiveRound r) {
        if (r.state != LiveRound.State.PLAYING) {
            notNow(p, r);
            return;
        }
        Location spot = besideBall(p.getWorld(), r);
        if (!games().sessions().teleport(p, spot)) {
            p.sendActionBar(Text.of("&cCouldn't take you there right now."));
        }
    }

    /**
     * A place to stand one block from the ball — behind it as seen from the cup if that's clear,
     * else any clear side — on something solid and inside the hole; the ball's own spot if
     * nowhere is. Facing the ball.
     */
    private Location besideBall(World w, LiveRound r) {
        double bx = r.ball.x();
        double by = r.ball.y();
        double bz = r.ball.z();
        GolfCourse.Hole h = r.hole();
        double away = Math.atan2(bz - (h.cup().z() + 0.5), bx - (h.cup().x() + 0.5));
        for (int i = 0; i < 8; i++) {
            double a = away + (i % 2 == 0 ? 1 : -1) * ((i + 1) / 2) * Math.PI / 4;
            double x = bx + Math.cos(a);
            double z = bz + Math.sin(a);
            if (r.area.over(x, z) && clear(w, x, by, z)) {
                return facing(w, x, by, z, bx, bz);
            }
        }
        return facing(w, bx, by, bz, h.cup().x() + 0.5, h.cup().z() + 0.5);
    }

    /** Room to stand at (x, y, z): feet and head free, something solid (not water or lava) just below. */
    private static boolean clear(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) {
            return false;
        }
        Block feet = w.getBlockAt(bx, by, bz);
        Block head = w.getBlockAt(bx, by + 1, bz);
        if (!feet.isPassable() || !head.isPassable() || feet.isLiquid() || head.isLiquid()) {
            return false;
        }
        for (int d = 1; d <= 2; d++) {
            Block below = w.getBlockAt(bx, by - d, bz);
            if (below.isLiquid()) {
                return false;
            }
            if (!below.isPassable()) {
                return true;
            }
        }
        return false;
    }

    private static Location facing(World w, double x, double y, double z, double tx, double tz) {
        float yaw = (float) Math.toDegrees(Math.atan2(-(tx - x), tz - z));
        return new Location(w, x, y, z, yaw, 30f);
    }

    /** Between holes, or done and waiting for the group: nothing to putt. */
    private static void notNow(Player p, LiveRound r) {
        p.sendActionBar(Text.of(r.state == LiveRound.State.DONE ? "&7Your round is done - the others are finishing."
                : "&7The next hole is coming..."));
    }

    private void reset(Player p, LiveRound r) {
        if (r.state != LiveRound.State.PLAYING) {
            notNow(p, r);
            return;
        }
        if (r.atSpot()) {
            p.sendActionBar(Text.of("&7Your ball is already at your last spot."));
            return;
        }
        LiveRound.Result result = r.back();
        show(p, r);
        if (result == LiveRound.Result.PICKED_UP) {
            pickedUp(p, r);
        } else {
            p.sendMessage(Text.of("&eBall reset. &7Back to your last spot, &f+1 stroke&7."));
            Sounds.miss(p);
        }
    }

    // ---- a hole ends ---------------------------------------------------------------------------------

    private void inCup(Player p, LiveRound r) {
        r.view.remove();
        GolfRun.HoleScore s = r.last;
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.2f);
        if (s.holeInOne()) {
            BigWin.celebrate(p, "&6&lHole in one!", "&f" + GolfRun.strokesText(s.strokes()) + " (par " + s.par() + ")");
        } else {
            p.showTitle(Title.title(Text.of("&a" + GolfRun.holeWord(s)), Text.of("&fIn the cup &7- "
                    + GolfRun.strokesText(s.strokes()) + " (par " + s.par() + ")"),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1800), Duration.ofMillis(400))));
        }
        holeLine(p, r, s);
        afterHole(p, r);
    }

    private void pickedUp(Player p, LiveRound r) {
        showPickedUp(p, r);
        afterHole(p, r);
    }

    /** A picked-up hole's title, sound and chat line. */
    private void showPickedUp(Player p, LiveRound r) {
        r.view.remove();
        GolfRun.HoleScore s = r.last;
        p.showTitle(Title.title(Text.of("&7Picked up"), Text.of("&7Hole " + r.run.hole() + " scores "
                + GolfRun.strokesText(s.strokes())),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1800), Duration.ofMillis(400))));
        Sounds.miss(p);
        holeLine(p, r, s);
    }

    /** "&amp;dHole 3 &amp;7(par 4): &amp;f3 strokes &amp;7- Birdie!" */
    private static void holeLine(Player p, LiveRound r, GolfRun.HoleScore s) {
        p.sendMessage(Text.of("&dHole " + r.run.hole() + " &7(par " + s.par() + "): " + GolfCard.colour(s)
                + GolfRun.strokesText(s.strokes()) + " &7- " + GolfRun.holeWord(s)));
    }

    /** The next hole in a moment, with the scorecard meanwhile; or the finish. */
    private void afterHole(Player p, LiveRound r) {
        if (r.group != null) {
            groups.holeDone(p.getUniqueId(), r);
            return;
        }
        if (r.run.finished()) {
            finish(p, r);
            return;
        }
        r.state = LiveRound.State.BETWEEN;
        long token = ++r.between;
        p.sendMessage(Text.of(r.card().line()));
        new GolfScorecardMenu(golf.plugin(), golf, p, r.card(), null).open(p);
        UUID id = p.getUniqueId();
        games().later(golf, BETWEEN_TICKS, () -> nextHole(id, token));
    }

    /** "Next hole" on the scorecard: don't wait (a tick later: the screen closes outside its own click). */
    public void nextHoleNow(Player p) {
        LiveRound r = live.get(p.getUniqueId());
        if (r != null && r.group != null) {
            if (!groups.nextNow(r.group)) {
                p.sendActionBar(Text.of("&7Waiting for " + names(r.group.waitingFor()) + "..."));
            }
            return;
        }
        if (r != null && r.state == LiveRound.State.BETWEEN) {
            UUID id = p.getUniqueId();
            long token = r.between;
            games().later(golf, 1, () -> nextHole(id, token));
        }
    }

    private void nextHole(UUID id, long token) {
        LiveRound r = live.get(id);
        Player p = golf.plugin().getServer().getPlayer(id);
        if (r == null || p == null || r.state != LiveRound.State.BETWEEN || r.between != token) {
            return;
        }
        if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof GolfScorecardMenu) {
            p.closeInventory();
        }
        startHole(p, r, true);
    }

    // ---- the finish ----------------------------------------------------------------------------------

    /** A round alone is over: its lines and record, then home, and the final scorecard there. */
    private void finish(Player p, LiveRound r) {
        r.state = LiveRound.State.DONE;
        finishLines(p, r);
        GolfCard card = r.card();
        games().sessions().leave(p, EndReason.FINISH);
        showWhenHome(p.getUniqueId(), card, null, 0);
    }

    /**
     * A finished round's lines, then its score and rewards unless the course changed meanwhile: a
     * round alone's at its end, a group player's at their own last hole ({@link GolfGroups}).
     */
    private void finishLines(Player p, LiveRound r) {
        GolfRun run = r.run;
        GolfCourse c = r.course;
        p.sendMessage(Text.of("&d" + c.name() + " &7done: &f" + GolfRun.strokesText(run.total()) + " &7("
                + GolfRun.vsParText(run.total() - c.par()) + ")"));
        String code = c.gen() == null ? null : DailyLookup.code(games(), c.gen());
        if (code != null) {
            p.sendMessage(Text.of("&7" + GenCopy.courseCode(code))); // so players can ask for it back
        }
        p.sendMessage(Text.of(r.card().line()));
        if (changed(c)) {
            p.sendMessage(Text.of("&7This course was changed while you played, so this round isn't recorded."));
        } else {
            record(p, r);
        }
    }

    /** Whether an admin changed (or closed) the course since the round began, and its layout no longer stands. */
    private boolean changed(GolfCourse c) {
        try {
            return stale(c, games().dao().course(c.id()), games().generated()::standing);
        } catch (SQLException e) {
            golf.log(Level.WARNING, "Mini golf: could not check a course before recording a round", e);
            return true;
        }
    }

    /**
     * Whether a round begun on {@code c} can't count against the course's row as it is now: it is
     * gone, it isn't golf's, its layout changed, or it was closed.
     */
    static boolean stale(GolfCourse c, GamesDao.CourseRow now) {
        return now == null || now.rev() != c.rev() || !CourseCodec.GAME.equals(now.game()) || !now.enabled();
    }

    /**
     * {@link #stale(GolfCourse, GamesDao.CourseRow)} with Fresh Courses' "still standing" rule
     * (GEN-SPEC §3.4): a round on a Fresh course can't count only if the old rule says so AND the
     * layout it began on no longer stands (its half is being cleared). A hand-built course keeps the
     * old rule exactly.
     *
     * @param standing whether a layout still stands ({@code GeneratedCourses#standing})
     */
    static boolean stale(GolfCourse c, GamesDao.CourseRow now, Predicate<GenTag> standing) {
        boolean changed = stale(c, now);
        if (!changed || c.gen() == null) {
            return changed;
        }
        return standing == null || !standing.test(c.gen());
    }

    /**
     * The score on the board, then the rewards — while the player is still in the game, where they
     * earn ({@link GolfFinish}). A Fresh course's part comes from the layout the round began on.
     */
    private void record(Player p, LiveRound r) {
        GolfRun run = r.run;
        GolfCourse c = r.course;
        MiniGolfSettings s = golf.settings();
        long day = golf.plugin().clock().dayKey();
        // today's pick is this course, or golf as a whole (the reward is once a day either way)
        boolean featured = games().featured().isFeatured(c.id()) || games().featured().isFeatured(golf.id());
        GolfFinish.Daily daily = null;
        if (c.generated()) { // the set of the layout the round started on, the Star Chart week of today
            GenTag t = c.gen();
            GeneratedCourses g = games().generated();
            long week = DailyLookup.weekKey(games());
            daily = new GolfFinish.Daily(t, week, g.dailyClear(t.slot(), t.cadence()), g.goals(week));
        }
        GolfFinish.settle(new GolfFinish.Round(c.id(), golf.name() + ": " + c.name(),
                run.total(), c.par(), c.holes().size(), run.parOrBetter(), run.holesInOne(), day, featured,
                s.firstClear(), s.parReward(), s.holeInOneReward(), games().config().common().featuredBonus(), daily),
                ledger(p, c));
    }

    /**
     * What a recorded round tells the quests and achievements (EXTRAS E4, guarded; {@link GolfFinish#settle}
     * calls it once, last): the round with its par, holes-in-one and whether it set the record, and on
     * a Fresh course the stars it added, the week's top goal and a whole set finished. A round on a
     * course changed while it was played is never recorded, so never told.
     */
    private void progress(Player p, GolfCourse c, GolfFinish.Round r, GolfFinish.Summary summary) {
        int strokes = r.strokes();
        int par = r.par();
        int holesInOne = r.holesInOne().size();
        boolean record = summary.result().record();
        games().tellProgress(g -> g.golfFinished(p, c.id(), strokes, par, holesInOne, c.generated(), record));
        GolfFinish.Daily daily = r.daily();
        if (daily != null && daily.tag() != null) {
            DailyLookup.freshProgress(games(), p, daily.tag(), summary.added(), daily.weekKey(), daily.goals());
        }
    }

    /** Where a round of {@code p} on {@code c} is recorded and paid. */
    private GolfFinish.Ledger ledger(Player p, GolfCourse c) {
        UUID id = p.getUniqueId();
        return new GolfFinish.Ledger() {
            @Override
            public ScoreResult submit(String board, int strokes) {
                return games().scores().submit(id, golf.id(), board, strokes, true);
            }

            @Override
            public void announce(ScoreResult result, boolean daily) {
                String was = result.previous() == null ? ""
                        : " &7(was " + GolfRun.strokesText(result.previous().intValue()) + ")";
                int cadence = GenCopy.words(c.gen()); // a Classic: "the best on ... so far", never "this week"
                if (result.personalBest()) {
                    p.sendMessage(Text.of((daily ? "&a✦ " + GenCopy.yourBest(cadence) + " on " : "&a✦ New best on ")
                            + c.name() + "!" + was));
                }
                if (result.record()) {
                    p.sendMessage(Text.of(daily ? setRecordLine(cadence, c.name()) : "&6★ That's the course record!"));
                }
            }

            @Override
            public int pay(RewardKind kind, String ref, int tokens, String detail) {
                return games().rewards().pay(p, golf, golf.source(), kind, ref, tokens, golf.settings().dailyCap(),
                        detail);
            }

            @Override
            public int payWhole(RewardKind kind, String ref, int tokens, String detail) {
                return games().rewards().payWhole(p, golf, golf.source(), kind, ref, tokens,
                        golf.settings().dailyCap(), detail, GenCopy.clearLimit(GenCopy.words(c.gen())));
            }

            @Override
            public GamesDao.StarsAdded addStars(String dayBoard, String weekBoard, int stars) {
                return DailyLookup.addStars(games(), id, dayBoard, weekBoard, stars);
            }

            @Override
            public void finished(GolfFinish.Round round, GolfFinish.Summary summary) {
                progress(p, c, round, summary);
            }

            @Override
            public void stars(int stars, GamesDao.StarsAdded added) {
                p.sendMessage(Text.of(DailyText.golfFinish(stars, c.par(), c.holes().size(),
                        added == null ? -1 : added.weekTotal())));
            }

            @Override
            public int payGoal(String ref, int tokens, String detail) {
                return DailyLookup.payGoal(games(), p, ref, tokens, detail);
            }
        };
    }

    /**
     * A Fresh set's new best, in the set's words: "&amp;6★ That's this week's best on Tiny Golf!",
     * "... today's best ...", or "&amp;6★ That's the best on Tiny Golf so far!" for another cadence.
     */
    static String setRecordLine(int cadence, String name) {
        return cadence == 1 || cadence == 7
                ? "&6★ That's " + GenCopy.bestOf(cadence).toLowerCase(java.util.Locale.ROOT) + " on " + name + "!"
                : "&6★ That's the best on " + name + " so far!";
    }

    /** The final scorecard, with "Play again", once the player is back home (checked twice a second, up to 20 s). */
    private void showWhenHome(UUID id, GolfCard card, GolfGroup.Card together, int tries) {
        games().later(golf, 10, () -> {
            Player p = golf.plugin().getServer().getPlayer(id);
            if (p == null || live.containsKey(id)) {
                return;
            }
            if (games().sessions().home(p) && games().sessions().session(p) == null) {
                if (together != null) {
                    new GolfGroupCardMenu(golf.plugin(), golf, p, together, null).open(p);
                } else {
                    new GolfScorecardMenu(golf.plugin(), golf, p, card, null).open(p);
                }
            } else if (tries < 40) {
                showWhenHome(id, card, together, tries + 1);
            }
        });
    }

    // ---- ends ----------------------------------------------------------------------------------------

    /** The session ended (after the player's things came back): the round goes with it. */
    void ended(Player p, EndReason reason) {
        LiveRound r = live.remove(p.getUniqueId());
        groups.leave(p.getUniqueId(), r); // a group round done at its last hole is recorded already
        if (r == null) {
            return;
        }
        r.view.remove();
        if (r.state != LiveRound.State.DONE && reason != EndReason.DISCONNECT && reason != EndReason.STOP
                && reason != EndReason.ADMIN) {
            p.sendMessage(Text.of("&7Your round of &d" + r.course.name() + " &7ended early, so it isn't recorded."));
        }
    }

    /** They quit: take the ball away (the session's end follows). */
    void quit(Player p) {
        LiveRound r = live.remove(p.getUniqueId());
        if (r != null) {
            r.view.remove();
        }
        groups.leave(p.getUniqueId(), r);
    }

    /** They fell out of the world (and are back at their last safe spot): next to the ball again. */
    void voided(Player p) {
        LiveRound r = live.get(p.getUniqueId());
        if (r != null && r.state == LiveRound.State.PLAYING) {
            goToBall(p, r);
        }
    }

    /** What a player is told when an admin changes the layout of the course they are playing. */
    static String changedLine(GolfCourse c) {
        return "&7" + c.name() + " was changed by an admin, so this round can't count. Your things are back.";
    }

    /** An admin closed or changed a course: end the rounds on it. */
    void closeCourse(String courseId, String line) {
        for (LiveRound r : all()) {
            if (!r.course.id().equals(courseId)) {
                continue;
            }
            Player p = golf.plugin().getServer().getPlayer(r.player);
            if (p != null) {
                p.sendMessage(Text.of(line));
                games().sessions().leave(p, EndReason.ADMIN);
            } else {
                drop(r);
            }
        }
    }

    /** The game stops: every ball goes (the framework ends the sessions). */
    void stopAll() {
        for (LiveRound r : live.values()) {
            r.view.remove();
        }
        live.clear();
        groups.stop();
    }

    private void drop(LiveRound r) {
        live.remove(r.player);
        r.view.remove();
        groups.leave(r.player, r);
    }

    // ---- golf together (EVENTS-OWNER-DECISIONS D4): GolfGroups' server side -------------------------

    private Player online(UUID player) {
        return golf.plugin().getServer().getPlayer(player);
    }

    /** {@link GolfGroups.Port} on this server. */
    private final class Server implements GolfGroups.Port {

        @Override
        public LiveRound round(UUID player) {
            return live.get(player);
        }

        @Override
        public boolean inSession(UUID player) {
            Player p = online(player);
            return p != null && games().sessions().session(p) != null;
        }

        @Override
        public boolean enter(UUID player, GolfGroup group) {
            Player p = online(player);
            return p != null && start(p, group.courseId(), group);
        }

        @Override
        public void tell(UUID player, String line) {
            Player p = online(player);
            if (p != null) {
                p.sendMessage(Text.of(line));
            }
        }

        @Override
        public void showCard(UUID player, GolfGroup.Card card) {
            Player p = online(player);
            if (p != null) {
                new GolfGroupCardMenu(golf.plugin(), golf, p, card, null).open(p);
            }
        }

        @Override
        public void nextTee(UUID player, LiveRound round) {
            Player p = online(player);
            if (p == null) {
                return;
            }
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof GolfGroupCardMenu) {
                p.closeInventory();
            }
            startHole(p, round, true);
        }

        @Override
        public void finished(UUID player, LiveRound round) {
            Player p = online(player);
            if (p != null) {
                finishLines(p, round); // each round is a normal round: its own board and rewards
            }
        }

        @Override
        public void pickedUp(UUID player, LiveRound round) {
            Player p = online(player);
            if (p != null) {
                showPickedUp(p, round);
            }
        }

        @Override
        public void home(UUID player, LiveRound round, GolfGroup.Card card) {
            Player p = online(player);
            if (p == null) {
                return;
            }
            GolfCard own = round.card();
            games().sessions().leave(p, EndReason.FINISH);
            showWhenHome(player, own, card, 0);
        }

        @Override
        public void later(long ticks, Runnable task) {
            games().later(golf, ticks, task);
        }

        @Override
        public void roundOver(long partyId) {
            golf.together().roundOver(partyId);
        }

        @Override
        public String name(UUID player) {
            return GolfTogether.name(player);
        }
    }

    /** "Sam", "Sam and Ava", "Sam, Ava and Lee". */
    static String names(java.util.List<String> names) {
        return GolfGroups.names(names);
    }
}
