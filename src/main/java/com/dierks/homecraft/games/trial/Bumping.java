package com.dierks.homecraft.games.trial;

/**
 * Whether racers bump into each other, by the course's tier (the owner's decision after the 0.37.1 live test:
 * "for easy and med you remove the bumping and keep it for hard", for races on courses with a tier). Pure and
 * tested.
 *
 * <ul>
 *   <li><b>Easy and Medium</b>: in a race (Race Night, a party race, the Clubhouse's) nobody pushes anybody,
 *       from seating to the finish or Leave game. A course with no tier counts as Medium; every course has one
 *       in practice ({@link Course} makes a missing one Easy, and {@code /hcm games course create} defaults to
 *       Easy), so only a course marked Hard (or harder) keeps the bumping.</li>
 *   <li><b>Hard and harder</b>: vanilla. A racer is neither set not collidable nor put on the no-push team.</li>
 *   <li><b>Boats</b> keep their own rule on every tier: the rider is set not collidable (mobs can't push them)
 *       and never joins the team, because boats bump, as the owner chose.</li>
 *   <li><b>A solo run</b> is left as it always was, on every tier: the decision was about races. (The Dropper
 *       keeps every faller apart in its shafts on every tier, {@link DropperRun}, as before.)</li>
 * </ul>
 *
 * <p>"Apart" is both halves, as everywhere in the Games: {@code setCollidable(false)} (it stops mobs) and the
 * games' no-push team ({@code games/NoPush}), because only a team's collision rule reaches a Java client, which
 * predicts player pushing itself. Geyser carries a team's prefix, suffix, colour and name-tag rule to a Bedrock
 * client, but not its collision rule, so a Bedrock player's own client may still nudge them off someone they walk
 * into; the Java player no longer pushes, or is pushed.
 */
final class Bumping {

    private Bumping() {
    }

    /** What the rule does to a racer. */
    enum Rule {
        /** Nobody pushes anybody: not collidable, and on the no-push team. */
        APART,
        /** A boat race's rider: not collidable, never on the team (boats bump). */
        RIDER,
        /** Collisions are left as they are: players bump. */
        NONE
    }

    /** Whether racers bump on a course of {@code tier}: Hard and harder. Easy, Medium and no tier at all don't. */
    static boolean bumps(Tier tier) {
        return tier == Tier.HARD || tier == Tier.EXTREME;
    }

    /** A racer's rule on a course of {@code kind} and {@code tier}, from seating until the race run ends. */
    static Rule race(TrialKind kind, Tier tier) {
        if (kind == TrialKind.BOAT) {
            return Rule.RIDER; // as before, on every tier
        }
        return bumps(tier) ? Rule.NONE : Rule.APART;
    }
}
