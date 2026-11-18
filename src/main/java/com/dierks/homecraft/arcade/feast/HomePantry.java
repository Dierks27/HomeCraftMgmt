package com.dierks.homecraft.arcade.feast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

/**
 * The Clubhouse's Feast Pot (0.42.0; the owner: "Lets just make it the real feast pot - will make it easy to get to
 * as well"). Inside the Clubhouse a visitor carries only the kit: their own things wait in their saved state
 * ({@code game_saved_state.items}) until they leave, when the restore puts that inventory back exactly. So the
 * Clubhouse pot feeds from there: a picture menu of the food at home, one tap one item.
 *
 * <p>Pure, so the order can be tested. Taking one item is two writes that must never lose or copy anything:
 * <ol>
 *   <li><b>the saved inventory without it</b>, a compare-and-swap on this session's live row (the row's items must
 *       still be the ones read, and the row still ACTIVE): a change in between (the session ending, another tap)
 *       writes nothing;</li>
 *   <li><b>then the Feast's points</b> ({@link FeastCore#give}). Anything but TAKEN (the day's points used up, the
 *       window closed, the database failing) <b>puts the item back</b> with the same compare-and-swap, so it is
 *       never gone without its points.</li>
 * </ol>
 * The restore reads the row when the visitor leaves, so they come home one item lighter, and the Feast has it.
 */
public final class HomePantry {

    private HomePantry() {
    }

    /** One slot of the saved inventory as the pot sees it: its material, how many, and whether the pot takes it. */
    public record Slot(String type, int amount, boolean takes) {
    }

    /** A food the menu shows: its material, how many are at home, and its points each. */
    public record Food(String type, int count, int points) {
    }

    /** What a tap on a food did. */
    public enum Fed {
        /** One went in. */
        TAKEN,
        /** None of it is at home any more. */
        GONE,
        /** The saved inventory changed under the tap (or the session ended): nothing happened. */
        CHANGED,
        /** The day's points are used up: the item stays at home. */
        FULL,
        /** The Feast isn't open: the item stays at home. */
        CLOSED,
        /** The database failed: the item stays at home. */
        FAILED,
        /** The points were refused and the item could not be written back: logged for an admin. */
        LOST
    }

    /** The foods at home the pot takes, one per material, most points first (then most of it), with counts. */
    public static List<Food> foods(List<Slot> slots) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Slot s : slots) {
            if (s != null && s.takes() && s.amount() > 0 && FeastRules.points(s.type()) > 0) {
                counts.merge(s.type(), s.amount(), Integer::sum);
            }
        }
        List<Food> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            out.add(new Food(e.getKey(), e.getValue(), FeastRules.points(e.getKey())));
        }
        out.sort((a, b) -> a.points() != b.points() ? Integer.compare(b.points(), a.points())
                : Integer.compare(b.count(), a.count()));
        return out;
    }

    /**
     * The slot one {@code type} is taken from: the smallest stack of it the pot takes (a full stack stays whole as
     * long as possible), the first of those; -1 when there is none.
     */
    public static int pick(List<Slot> slots, String type) {
        int best = -1;
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            if (s == null || !s.takes() || s.amount() <= 0 || !s.type().equals(type)) {
                continue;
            }
            if (best < 0 || s.amount() < slots.get(best).amount()) {
                best = i;
            }
        }
        return best;
    }

    /**
     * One {@code type} into the pot, in the safe order.
     *
     * @param without writes the saved inventory with one item fewer in that slot (compare-and-swap); false = nothing
     *                written
     * @param give    the Feast's points for one item
     * @param putBack writes the item back (compare-and-swap on what {@code without} wrote); false = it couldn't
     */
    public static Fed feed(List<Slot> slots, String type, IntPredicate without, Supplier<FeastCore.Gift> give,
                           java.util.function.BooleanSupplier putBack) {
        int slot = pick(slots, type);
        if (slot < 0) {
            return Fed.GONE;
        }
        if (!without.test(slot)) {
            return Fed.CHANGED;
        }
        FeastCore.Gift g = give.get();
        if (g != null && g.outcome() == FeastCore.Outcome.TAKEN && g.taken() > 0) {
            return Fed.TAKEN;
        }
        if (!putBack.getAsBoolean()) {
            return Fed.LOST;
        }
        if (g == null) {
            return Fed.FAILED;
        }
        return switch (g.outcome()) {
            case FULL -> Fed.FULL;
            case CLOSED -> Fed.CLOSED;
            default -> Fed.FAILED;
        };
    }
}
