package com.dierks.homecraft.games.gen.api;

/**
 * A planner or a check could not produce a course this try (GEN-SPEC §3.5, §6 S8). Checked, so
 * nothing on the build path can forget it: the engine turns it into a failed try — the old layout
 * stays live, the slot records the message for {@code /hcm games gen status}, and the next try
 * comes {@code retry_minutes} later. A planner never lets anything else escape: whatever it hits
 * becomes one of these.
 *
 * <p>The message is admin words ("hole 2 couldn't be solved"), shown in status, never to players.
 */
public final class GenFailed extends Exception {

    /** What a planner that isn't built yet says (the C0 stubs). */
    public static final String NOT_BUILT = "not built yet";

    public GenFailed(String message) {
        super(message);
    }

    public GenFailed(String message, Throwable cause) {
        super(message, cause);
    }
}
