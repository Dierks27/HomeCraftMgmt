package com.dierks.homecraft.arcade;

import com.dierks.homecraft.config.PluginConfig.Icon;
import com.dierks.homecraft.config.PluginConfig.LimitPer;
import com.dierks.homecraft.config.PluginConfig.OnlyIf;
import com.dierks.homecraft.config.PluginConfig.Prize;
import com.dierks.homecraft.config.PluginConfig.PrizeExtra;
import com.dierks.homecraft.config.PluginConfig.PrizeLimit;
import com.dierks.homecraft.config.PluginConfig.PrizeTab;
import com.dierks.homecraft.config.PluginConfig.PrizeType;
import com.dierks.homecraft.marketplace.DeliveryService;
import com.dierks.homecraft.storage.Database;
import com.dierks.homecraft.storage.DeliveryDao;
import com.dierks.homecraft.storage.PrizeDao;
import com.dierks.homecraft.storage.TokenDao;
import org.bukkit.Material;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * A real in-memory SQLite with the real purchase counts, token ledger and Mailbox, for the shop's one-transaction
 * buys (SHOP-V2-SPEC D30): each confirm runs {@link PrizeService#commitLimited} with the steps
 * {@code PrizeService.deliverAsOne} gives it, reading and writing what the plugin does.
 */
final class ShopTx implements AutoCloseable {

    final Connection conn;
    final Database db;
    final PrizeDao prizes;
    final TokenDao tokens;
    final DeliveryDao deliveries;
    final UUID buyer = UUID.randomUUID();
    /** What a failed write handed {@code notSaved}. */
    final List<Exception> notSaved = new ArrayList<>();

    ShopTx(int balance) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        db = Database.open(conn, Logger.getAnonymousLogger());
        prizes = new PrizeDao(db);
        tokens = new TokenDao(db);
        deliveries = new DeliveryDao(db);
        if (balance > 0) {
            tokens.change(buyer, balance, "ADMIN", "seed", 0);
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }

    int balance() throws SQLException {
        return tokens.get(buyer).tokens();
    }

    /** The ledger's spends and refunds (the seed left out), oldest first. */
    List<Integer> ledger() throws SQLException {
        List<Integer> out = new ArrayList<>();
        for (TokenDao.LedgerRow r : tokens.history(buyer, 100)) {
            if (!"ADMIN".equals(r.source())) {
                out.add(0, r.delta());
            }
        }
        return out;
    }

    int bought(Prize p, String key) throws SQLException {
        return prizes.purchases(buyer, p.id(), key);
    }

    /** The price of the buyer's next one, as {@code PrizeService.price} reads it. */
    int price(Prize p) throws SQLException {
        return p.costs().isEmpty() ? p.costTokens() : p.priceAfter(prizes.purchases(buyer, p.id(), "life"));
    }

    /**
     * One confirm of a counted row in period {@code key}: the limit read again, the count ({@code failCount} makes
     * its write fail), a trophy's serial ({@code serialKey}, null for none), the charge, and with {@code fits}
     * false the Mailbox row. {@code why} is an extra refusal read inside the lock (a party on), or null.
     */
    String confirm(Prize p, String key, boolean failCount, String serialKey, boolean fits, String why) {
        int[] cost = new int[1];
        try {
            cost[0] = price(p);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return PrizeService.commitLimited(db, new PrizeService.LimitSteps(
                        () -> {
                            if (p.limit() != null && p.limit().count() - prizes.purchases(buyer, p.id(), key) <= 0) {
                                return "used up";
                            }
                            return price(p) != cost[0] ? PrizeService.PRICE_CHANGED : why;
                        },
                        () -> {
                            if (failCount) {
                                throw new SQLException("disk full");
                            }
                            prizes.addPurchase(buyer, p.id(), key);
                        },
                        () -> {
                            if (serialKey != null) {
                                prizes.setState(serialKey, prizes.state(serialKey, 0) + 1);
                            }
                        },
                        () -> {
                            try {
                                return tokens.change(buyer, -cost[0], "PRIZE", p.display(), 1) != TokenDao.REFUSED;
                            } catch (SQLException e) {
                                return false;
                            }
                        },
                        () -> {
                            if (!fits) {
                                new DeliveryService(null, deliveries).queueReady(buyer, "b64",
                                        "Bronze Trophy #" + prizes.state(serialKey, 0), PrizeService.MAILBOX_SOURCE);
                            }
                        }),
                "broke", notSaved::add);
    }

    String confirm(Prize p, String key) {
        return confirm(p, key, false, null, true, null);
    }

    static Prize row(String id, PrizeType type, int cost, PrizeLimit limit, List<Integer> costs, OnlyIf onlyIf) {
        return new Prize(id, PrizeTab.TROPHIES, "&6" + id, List.of("A numbered trophy"), cost,
                new Icon("", Material.GOLD_BLOCK), type, true, limit, 1, null, null, null, null, 0, 0, null, 0, "",
                type == PrizeType.COMMAND ? List.of("execute as %uuid% at @s run time set day") : List.of(), List.of(),
                null, null, costs, null, new PrizeExtra(null, 0, null, onlyIf, type == PrizeType.PARTY ? 10 : 0, null));
    }

    static PrizeLimit limit(LimitPer per, int count) {
        return new PrizeLimit(per, count);
    }
}
