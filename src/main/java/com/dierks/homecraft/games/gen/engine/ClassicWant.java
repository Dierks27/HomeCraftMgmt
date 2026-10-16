package com.dierks.homecraft.games.gen.engine;

/**
 * What an admin recalled into a Classics slot (GEN-SPEC-KEEP §3), as kept in {@code hcm_meta}
 * ({@code gen.<classic slot>.recall}).
 *
 * <p>It is the slot's wanted state, not a job: the engine makes the slot hold it (builds it into
 * the idle half, verifies it, flips), and closes the slot when it is gone or its time is up. So a
 * recall that stops halfway — a crash, a restart — is simply built again at the next start, like a
 * slot's due build, and a half-built recall is never playable.
 *
 * @param slot    the slot the archived edition was made for ({@code fresh_parkour_hard})
 * @param edition its edition key ({@code 7:40})
 * @param from    when it was recalled (epoch ms): each recall is its own
 * @param until   when it closes again (epoch ms), or 0 to stay until replaced or unrecalled
 * @param remade  made again from its seed ({@code Planner.remake}: its stored plan couldn't be read)
 */
public record ClassicWant(String slot, String edition, long from, long until, boolean remade) {

    /** Whether its time is up at {@code now}. */
    public boolean expired(long now) {
        return until > 0 && now >= until;
    }

    /** As stored: {@code slot|edition|from|until|remade}. */
    public String text() {
        return slot + "|" + edition + "|" + from + "|" + until + "|" + (remade ? 1 : 0);
    }

    /** The same recall with another end (0: forever). */
    public ClassicWant withUntil(long u) {
        return new ClassicWant(slot, edition, from, u, remade);
    }

    /** A stored recall, or {@code null} when unset or unreadable. */
    public static ClassicWant parse(String text) {
        if (text == null) {
            return null;
        }
        String[] p = text.split("\\|", -1);
        if (p.length != 5 || p[0].isBlank() || p[1].isBlank()) {
            return null;
        }
        try {
            return new ClassicWant(p[0].trim(), p[1].trim(), Long.parseLong(p[2].trim()), Long.parseLong(p[3].trim()),
                    "1".equals(p[4].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
