package com.dierks.homecraft.web;

import com.dierks.homecraft.config.PluginConfig;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The website-feed settings as they ship, and the one place the feed token could leak into a
 * log. The bundled config.yml ships the feeds open ({@code web.dashboard.feed_token} blank —
 * exactly how the feeds worked before there was a token), the LAN switch off
 * ({@code web.dashboard.lan_skips_token: false}: the shipped way in from outside is a Playit agent
 * on this PC, whose connections arrive from 127.0.0.1, so a default-on switch would let the
 * internet past the token; the dashboard page asks for the token instead) and 30 days of market
 * history ({@code market.price_history.keep_days}, which the
 * website's 30-day chart needs). Each new key carries its explanation, because the config
 * backfill copies a new key's comments into live configs along with its value — and the Playit /
 * tunnel warning has to travel with the LAN switch. Finally, {@link PluginConfig.WebDashboard#toString()}
 * says whether a token is set, never what it is: a record's generated {@code toString()} would
 * print it into any log line the record reached.
 *
 * <p>Needs paper-api on the test classpath for {@link YamlConfiguration}, like
 * {@code ConfigMigrationTest}.
 */
class FeedConfigTest {

    private static final List<String> NEW_KEYS = List.of(
            "web.dashboard.feed_token", "web.dashboard.lan_skips_token", "market.price_history.keep_days");

    /**
     * The bundled config.yml, loaded with the throwing {@code load(Reader)} so a YAML mistake
     * fails here with its line number (see {@code ConfigMigrationTest#bundled}).
     */
    private static YamlConfiguration bundled() throws IOException, InvalidConfigurationException {
        try (InputStream in = FeedConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the bundled config.yml is missing from the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration c = new YamlConfiguration();
                c.load(reader);
                return c;
            }
        }
    }

    private static PluginConfig.WebDashboard dashboard(String feedToken) {
        return new PluginConfig.WebDashboard(true, "0.0.0.0", 8080, 30, "t", feedToken, true);
    }

    @Test
    void theFeedsShipOpenWithTheLanSwitchOff() throws Exception {
        YamlConfiguration c = bundled();
        assertTrue(c.isString("web.dashboard.feed_token"), "feed_token should ship as a string");
        assertEquals("", c.getString("web.dashboard.feed_token"), "a blank token keeps the feeds open");
        assertTrue(c.isBoolean("web.dashboard.lan_skips_token"), "lan_skips_token should ship as a boolean");
        assertFalse(c.getBoolean("web.dashboard.lan_skips_token", true),
                "the LAN switch ships off: a Playit agent or a tunnel on this PC arrives from 127.0.0.1");
    }

    @Test
    void marketHistoryShipsThirtyDaysForTheWebsitesLongestChart() throws Exception {
        YamlConfiguration c = bundled();
        assertTrue(c.isInt("market.price_history.keep_days"), "keep_days should ship as a whole number");
        assertEquals(30, c.getInt("market.price_history.keep_days"));
    }

    @Test
    void theNewKeysShipWithTheirExplanations() throws Exception {
        YamlConfiguration c = bundled();
        for (String key : NEW_KEYS) {
            assertFalse(c.getComments(key).isEmpty(),
                    key + " should ship with a comment: the backfill copies it into live configs");
        }
        String lanComment = String.join("\n", c.getComments("web.dashboard.lan_skips_token"));
        assertTrue(lanComment.contains("Playit") && lanComment.contains("tunnel"),
                "the Playit / tunnel warning has to ship with the LAN switch, got: " + lanComment);
    }

    @Test
    void theDashboardSettingsNeverPrintTheToken() {
        PluginConfig.WebDashboard set = dashboard("s3cret-token");
        String shown = set.toString();
        assertFalse(shown.contains("s3cret-token"), shown);
        assertFalse(shown.contains("s3cret"), shown);
        assertTrue(shown.contains("feedToken=<set>"), shown);
        assertEquals("s3cret-token", set.feedToken(), "the accessor still hands the server the token");

        assertTrue(dashboard("").toString().contains("feedToken=<blank>"));
        assertTrue(dashboard(null).toString().contains("feedToken=<blank>"));
    }

    @Test
    void theSettingsStillShowEverythingElse() {
        String shown = dashboard("s3cret-token").toString();
        for (String part : List.of("enabled=true", "bind=0.0.0.0", "port=8080", "refreshSeconds=30",
                "title=t", "lanSkipsToken=true")) {
            assertTrue(shown.contains(part), "toString should still show " + part + ": " + shown);
        }
    }
}
