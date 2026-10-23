package com.dierks.homecraft.block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The rules that keep a placed HomeCraft block where it stands and what is in it with its owner (0.37.3, spec
 * §4.15, D22): who may break a Vending Machine, Display Case or Pallet, the order a final break works in, and which
 * piston moves, flows, explosion blocks, mob changes (a wither) and burns are refused. Pure, so every rule is tested
 * without a server;
 * {@link CustomBlockListener} asks them, with "ours" meaning a placed HomeCraft block, the upper head of a Vending
 * Machine, or a placed prize head (a hat or a trophy).
 */
public final class CustomBlockGuard {

    private CustomBlockGuard() {
    }

    /** The blocks that hold someone's stock: a Vending Machine, a Display Case and a Pallet. */
    public static boolean holdsStock(CustomBlockType type) {
        return type == CustomBlockType.MINI_VENDING_MACHINE || type == CustomBlockType.DISPLAY_CASE
                || type == CustomBlockType.PALLET;
    }

    /**
     * Whether breaking a placed block of {@code type} is refused: a Vending Machine, Display Case or Pallet only by
     * the player who placed it ({@code isOwner}) or an admin, empty or not. Every other block keeps the old rule:
     * anyone with build rights. Decided at HIGH, before the town check.
     */
    public static boolean breakRefusal(CustomBlockType type, boolean isOwner, boolean admin) {
        return holdsStock(type) && !isOwner && !admin;
    }

    /** What someone else is told when they try to break one. */
    public static String refusalLine(String owner, CustomBlockType type) {
        return "&cThis is " + owner + "'s " + name(type) + ". Only they can take it down.";
    }

    /** What a block is called in the lines its owner and a breaker see. */
    public static String name(CustomBlockType type) {
        return switch (type) {
            case MINI_VENDING_MACHINE -> "Vending Machine";
            case DISPLAY_CASE -> "Display Case";
            case PALLET -> "Pallet";
            default -> type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    /** The work of a break that is final (MONITOR), in the order {@link #finish} runs it. */
    public interface Finish {
        /**
         * Send everything in the block back to whoever stocked it (their Mailboxes, one transaction).
         *
         * @return false when it couldn't be written: nothing changed, and the block must stay
         */
        boolean returnStock();

        /** Forget the block: its record, a muffler's settings, a Vending Machine's display and upper head. */
        void forget();

        /** Drop the block's own item (exactly one). */
        void drop();
    }

    /**
     * Run a final break: the stock first (only a block that holds any), and only once it is safely written does the
     * block go and drop its item. Nothing here runs for a break another plugin cancelled after HIGH, because the
     * listener that calls it is MONITOR with {@code ignoreCancelled}.
     *
     * @return false when the stock couldn't be written: nothing was forgotten or dropped
     */
    public static boolean finish(CustomBlockType type, Finish work) {
        if (holdsStock(type) && !work.returnStock()) {
            return false;
        }
        work.forget();
        work.drop();
        return true;
    }

    /**
     * What an extending piston touches: every block it moves, and the one in front of the moving line, which it
     * breaks rather than pushes (a head's push reaction is DESTROY, and the broken block isn't in
     * {@code getBlocks()}). A retracting piston only moves, so it passes {@code null} for {@code inFront}.
     */
    public static <B> List<B> pistonTouches(List<B> moved, B inFront) {
        List<B> out = new ArrayList<>(moved);
        if (inFront != null) {
            out.add(inFront);
        }
        return out;
    }

    /** A piston move is refused when anything it moves or breaks is ours. */
    public static <B> boolean pistonRefused(List<B> moved, Predicate<B> isOurs) {
        for (B b : moved) {
            if (isOurs.test(b)) {
                return true;
            }
        }
        return false;
    }

    /** Water or lava flowing into one of ours is refused. */
    public static <B> boolean flowRefused(B to, Predicate<B> isOurs) {
        return to != null && isOurs.test(to);
    }

    /**
     * A mob changing one of ours (a hurt wither breaking the blocks around it, an enderman taking one), or fire
     * burning one, is refused: the world would drop a plain block and leave its record and stock behind.
     */
    public static <B> boolean changeRefused(B block, Predicate<B> isOurs) {
        return block != null && isOurs.test(block);
    }

    /** An explosion leaves ours standing: they come out of its block list. Returns how many were spared. */
    public static <B> int spare(List<B> blast, Predicate<B> isOurs) {
        int before = blast.size();
        blast.removeIf(isOurs);
        return before - blast.size();
    }
}
