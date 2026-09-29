package com.dierks.homecraft.web;

import com.dierks.homecraft.games.FeedWriter;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Weekly Cup's {@code cup} object on a course's {@code games[]} entry (EVENTS-OWNER-DECISIONS
 * §D2: "the pool is published live on ... the website feed"): exactly {@code entry}, {@code pool},
 * {@code entrants} and {@code endsAt}, never a player; joined to its course by id whatever order
 * the games wrote in; dropped when no such course was written or its numbers make no Cup; gone
 * with a game's writes when that game fails mid-feed; and a course without one is byte for byte
 * what it was.
 */
class ArcadeFeedCupTest {

    private static final long T = 1_790_000_000_000L;
    private static final long ENDS = 1_790_300_000_000L;

    private static String json(ArcadeFeed feed) {
        return feed.json(T, null, null, null, null, null);
    }

    private static JsonObject entry(String json, String id) {
        for (JsonElement e : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("games")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("id").getAsString().equals(id)) {
                return o;
            }
        }
        return null;
    }

    private static ArcadeFeed courses() {
        ArcadeFeed feed = new ArcadeFeed(false);
        feed.course("lava_leap", "Lava Leap", "parkour", "easy", 41_230L, T - 1, "Steve");
        feed.course("river_run", "River Run", "boat", "medium", null, null, null);
        return feed;
    }

    @Test
    void aCourseThatRunsACupCarriesItsEntryPoolEntrantsAndEnd() {
        ArcadeFeed feed = courses();
        feed.cup("lava_leap", new FeedWriter.Cup(5, 35, 5, ENDS));
        String out = json(feed);
        assertTrue(out.contains(",\"cup\":{\"entry\":5,\"pool\":35,\"entrants\":5,\"endsAt\":" + ENDS + "}"),
                "the Cup, in this key order: " + out);
        JsonObject cup = entry(out, "lava_leap").getAsJsonObject("cup");
        assertNotNull(cup, "on the course's own entry");
        assertEquals(Set.of("entry", "pool", "entrants", "endsAt"), cup.keySet(), "those four and nothing else");
        assertFalse(entry(out, "river_run").has("cup"), "a course without a Cup has none");
        assertFalse(out.contains("holder\":\"Steve\""), "names stay off with arcade_show_names off, Cup or not");
    }

    @Test
    void aCourseWithoutACupIsByteForByteWhatItWas() {
        ArcadeFeed plain = courses();
        ArcadeFeed withElsewhere = courses();
        withElsewhere.cup("nowhere", new FeedWriter.Cup(5, 10, 2, ENDS));
        assertEquals(json(plain), json(withElsewhere), "a Cup for a course that wasn't written goes nowhere");
    }

    @Test
    void theCupJoinsItsCourseWhateverOrderTheyCameIn() {
        ArcadeFeed feed = new ArcadeFeed(false);
        feed.cup("LAVA_LEAP", new FeedWriter.Cup(5, 5, 1, ENDS));
        feed.course("lava_leap", "Lava Leap", "parkour", "easy", null, null, null);
        JsonObject cup = entry(json(feed), "lava_leap").getAsJsonObject("cup");
        assertNotNull(cup, "written before its course, and in another case, it still lands on it");
        assertEquals(1, cup.get("entrants").getAsInt());
    }

    @Test
    void numbersThatMakeNoCupAreLeftOutOrKeptSane() {
        ArcadeFeed feed = courses();
        feed.cup("lava_leap", new FeedWriter.Cup(0, 10, 2, ENDS));
        feed.cup("river_run", new FeedWriter.Cup(5, 10, 2, 0));
        String out = json(feed);
        assertFalse(entry(out, "lava_leap").has("cup"), "no entry fee, no Cup");
        assertFalse(entry(out, "river_run").has("cup"), "no end, no Cup");
        ArcadeFeed odd = courses();
        odd.cup("lava_leap", new FeedWriter.Cup(5, -3, -1, ENDS));
        JsonObject cup = entry(json(odd), "lava_leap").getAsJsonObject("cup");
        assertEquals(0, cup.get("pool").getAsInt(), "never a negative pool");
        assertEquals(0, cup.get("entrants").getAsInt(), "never a negative head count");
        ArcadeFeed blank = courses();
        blank.cup(" ", new FeedWriter.Cup(5, 5, 1, ENDS));
        blank.cup("lava_leap", null);
        assertEquals(json(courses()), json(blank), "a blank id or no Cup writes nothing");
    }

    @Test
    void aGameThatFailsMidFeedTakesItsCupWithIt() {
        ArcadeFeed feed = courses();
        int mark = feed.size();
        feed.cup("lava_leap", new FeedWriter.Cup(5, 35, 5, ENDS));
        feed.truncate(mark);
        assertFalse(entry(json(feed), "lava_leap").has("cup"), "the Cup's part goes with the failed game's writes");
    }
}
