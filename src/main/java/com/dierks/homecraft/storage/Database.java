package com.dierks.homecraft.storage;

import com.dierks.homecraft.HomeCraftManagement;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the single SQLite {@link Connection} and runs a tiny forward-only
 * migration framework so later phases (orders, minis, auctions) can extend the
 * schema without breaking existing installs.
 *
 * <p>SQLite is file-based (no server). At friends-scale, main-thread access is
 * fine; all DAO calls funnel through this one connection and synchronize on it.
 */
public final class Database {

    /**
     * Ordered DDL migrations. Index i (1-based) is schema version i. To evolve
     * the schema, APPEND a new statement block — never edit an existing one.
     */
    private static final String[] MIGRATIONS = {
            // v1 — placed custom-block registry (Phase 1).
            """
            CREATE TABLE IF NOT EXISTS placed_blocks (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                world      TEXT    NOT NULL,
                x          INTEGER NOT NULL,
                y          INTEGER NOT NULL,
                z          INTEGER NOT NULL,
                type       TEXT    NOT NULL,
                owner      TEXT    NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE (world, x, y, z)
            );
            CREATE INDEX IF NOT EXISTS idx_placed_owner ON placed_blocks (owner);
            CREATE INDEX IF NOT EXISTS idx_placed_type  ON placed_blocks (type);
            """,
            // v2 — dynamic market state: per-item current price + net demand (Phase 2).
            """
            CREATE TABLE IF NOT EXISTS market_state (
                item_id       TEXT    PRIMARY KEY,
                current_price REAL    NOT NULL,
                demand        INTEGER NOT NULL,
                updated_at    INTEGER NOT NULL
            );
            """,
            // v3 — finite-stock revision (Phase 2.5): replace the abstract signed
            // 'demand' with real held 'stock'. Existing rows get stock = -1 (an
            // "unseeded" sentinel) so the service seeds them from config on load.
            """
            ALTER TABLE market_state ADD COLUMN stock INTEGER NOT NULL DEFAULT -1;
            ALTER TABLE market_state DROP COLUMN demand;
            """,
            // v4 — per-player daily sell tallies (anti-whale limit). day = UTC epoch-day.
            """
            CREATE TABLE IF NOT EXISTS market_daily_sells (
                player_uuid TEXT    NOT NULL,
                day         INTEGER NOT NULL,
                item_id     TEXT    NOT NULL,
                units       INTEGER NOT NULL,
                money       REAL    NOT NULL,
                PRIMARY KEY (player_uuid, day, item_id)
            );
            CREATE INDEX IF NOT EXISTS idx_daily_sells_day ON market_daily_sells (player_uuid, day);
            """,
            // v5 — periodic price/stock snapshots (feeds the Phase 5 dashboard charts).
            """
            CREATE TABLE IF NOT EXISTS market_price_history (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                item_id   TEXT    NOT NULL,
                price     REAL    NOT NULL,
                stock     INTEGER NOT NULL,
                recorded_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_price_history_item ON market_price_history (item_id, recorded_at);
            """,
            // v6 — Amazon orders + real-time shipping (Phase 3). deliver_at is an
            // absolute epoch-ms timestamp so deliveries survive restarts.
            """
            CREATE TABLE IF NOT EXISTS market_orders (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid   TEXT    NOT NULL,
                item_id       TEXT    NOT NULL,
                qty           INTEGER NOT NULL,
                item_cost     REAL    NOT NULL,
                shipping_cost REAL    NOT NULL,
                tier          TEXT    NOT NULL,
                placed_at     INTEGER NOT NULL,
                deliver_at    INTEGER NOT NULL,
                status        TEXT    NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_orders_player ON market_orders (player_uuid, status);
            CREATE INDEX IF NOT EXISTS idx_orders_status ON market_orders (status, deliver_at);
            """,
            // v7 — Minis collectibles (Phase 4): per-type mint tallies + per-copy
            // provenance. mint_counts.minted is the single source of truth for caps;
            // circulation = minted - destroyed.
            """
            CREATE TABLE IF NOT EXISTS mini_counts (
                mini_id   TEXT    PRIMARY KEY,
                minted    INTEGER NOT NULL DEFAULT 0,
                destroyed INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS mini_individuals (
                uid         TEXT    PRIMARY KEY,
                mini_id     TEXT    NOT NULL,
                mint_number INTEGER NOT NULL,
                owner       TEXT,
                minted_at   INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_mini_ind_type ON mini_individuals (mini_id);
            """,
            // v8 — Mini secondary market (Phase 4c Part A): fixed-price Vending
            // Machine / Display Case listings keyed by block location, plus a sales
            // log for provenance + price history. The listed Mini item is stored
            // verbatim (Base64) so it hands back byte-for-byte.
            """
            CREATE TABLE IF NOT EXISTS mini_listings (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                world       TEXT    NOT NULL,
                x           INTEGER NOT NULL,
                y           INTEGER NOT NULL,
                z           INTEGER NOT NULL,
                kind        TEXT    NOT NULL,
                owner       TEXT    NOT NULL,
                uid         TEXT    NOT NULL,
                mini_id     TEXT    NOT NULL,
                mint_number INTEGER NOT NULL,
                price       REAL    NOT NULL,
                item_b64    TEXT    NOT NULL,
                listed_at   INTEGER NOT NULL,
                UNIQUE (world, x, y, z)
            );
            CREATE TABLE IF NOT EXISTS mini_sales (
                id       INTEGER PRIMARY KEY AUTOINCREMENT,
                uid      TEXT    NOT NULL,
                mini_id  TEXT    NOT NULL,
                price    REAL    NOT NULL,
                seller   TEXT,
                buyer    TEXT,
                venue    TEXT    NOT NULL,
                sold_at  INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_mini_sales_type ON mini_sales (mini_id, sold_at);
            """,
            // v9 — Mini Auction House (Phase 4c Part B): timed auctions with a single
            // escrowed top bid (previous leader auto-refunded on outbid), a durable
            // end_at so a scheduler can close them after a restart, and a simple
            // per-player notification queue delivered on login.
            """
            CREATE TABLE IF NOT EXISTS mini_auctions (
                id             INTEGER PRIMARY KEY AUTOINCREMENT,
                uid            TEXT    NOT NULL,
                mini_id        TEXT    NOT NULL,
                mint_number    INTEGER NOT NULL,
                seller         TEXT    NOT NULL,
                start_bid      REAL    NOT NULL,
                current_bid    REAL    NOT NULL DEFAULT 0,
                current_bidder TEXT,
                buy_now        REAL    NOT NULL DEFAULT 0,
                item_b64       TEXT    NOT NULL,
                end_at         INTEGER NOT NULL,
                status         TEXT    NOT NULL,
                created_at     INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_auctions_status ON mini_auctions (status, end_at);
            CREATE TABLE IF NOT EXISTS mini_notifications (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                player     TEXT    NOT NULL,
                message    TEXT    NOT NULL,
                created_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_notify_player ON mini_notifications (player);
            CREATE TABLE IF NOT EXISTS mini_pending (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                player     TEXT    NOT NULL,
                item_b64   TEXT    NOT NULL,
                reason     TEXT    NOT NULL,
                created_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_pending_player ON mini_pending (player);
            """,
            // v10 — Wild Drops anti-farm ledger (Phase 4c Part C): the coordinates of
            // player-placed blocks of a drop-eligible material, so place-then-break
            // (and silk-touch-and-replace) can't cheese a drop. Bounded because only
            // drop-source materials are recorded.
            """
            CREATE TABLE IF NOT EXISTS player_placed (
                world TEXT    NOT NULL,
                x     INTEGER NOT NULL,
                y     INTEGER NOT NULL,
                z     INTEGER NOT NULL,
                PRIMARY KEY (world, x, y, z)
            );
            """,
            // v11 — Multi-slot Vending Machine (Phase 4e): a machine holds many
            // Minis, each its own priced listing (no unique-per-location). Existing
            // single VENDING listings migrate over from mini_listings, which keeps
            // serving the one-per-block Display Case (kind = DISPLAY).
            """
            CREATE TABLE IF NOT EXISTS mini_vending_listings (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                world       TEXT    NOT NULL,
                x           INTEGER NOT NULL,
                y           INTEGER NOT NULL,
                z           INTEGER NOT NULL,
                owner       TEXT    NOT NULL,
                uid         TEXT    NOT NULL,
                mini_id     TEXT    NOT NULL,
                mint_number INTEGER NOT NULL,
                price       REAL    NOT NULL,
                item_b64    TEXT    NOT NULL,
                listed_at   INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_vending_loc ON mini_vending_listings (world, x, y, z);
            INSERT INTO mini_vending_listings (world,x,y,z,owner,uid,mini_id,mint_number,price,item_b64,listed_at)
                SELECT world,x,y,z,owner,uid,mini_id,mint_number,price,item_b64,listed_at
                FROM mini_listings WHERE kind = 'VENDING';
            DELETE FROM mini_listings WHERE kind = 'VENDING';
            """,
            // v12 — Mailbox deliveries (Phase 5): a unified queue of items owed to a
            // player (Marketplace purchases; future web-shop orders). The exact item
            // is stored verbatim (Base64). Collected at the Mailbox block or the PC.
            """
            CREATE TABLE IF NOT EXISTS deliveries (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                player     TEXT    NOT NULL,
                item_b64   TEXT    NOT NULL,
                label      TEXT    NOT NULL,
                source     TEXT    NOT NULL,
                placed_at  INTEGER NOT NULL,
                deliver_at INTEGER NOT NULL,
                status     TEXT    NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_deliveries_player ON deliveries (player, status);
            CREATE INDEX IF NOT EXISTS idx_deliveries_due ON deliveries (status, deliver_at);
            """,
            // v13 — Marketplace Pallets (Phase 5): a player's sell box holds one item
            // type at a fixed price with a stock count; a listing appears in the Crate
            // Marketplace and auto-deactivates when it runs dry. One listing per block.
            """
            CREATE TABLE IF NOT EXISTS pallet_listings (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                world      TEXT    NOT NULL,
                x          INTEGER NOT NULL,
                y          INTEGER NOT NULL,
                z          INTEGER NOT NULL,
                owner      TEXT    NOT NULL,
                item_b64   TEXT    NOT NULL,
                price      REAL    NOT NULL,
                stock      INTEGER NOT NULL,
                department TEXT    NOT NULL,
                active     INTEGER NOT NULL DEFAULT 1,
                listed_at  INTEGER NOT NULL,
                UNIQUE (world, x, y, z)
            );
            CREATE INDEX IF NOT EXISTS idx_pallet_active ON pallet_listings (active, department);
            """,

            // v14 — In-game economy displays (Phase 7): sign boards, holograms, and
            // map-TVs, each bound to a market commodity and re-rendered from live data
            // on a timer. `kind` is SIGN | HOLOGRAM | MAPTV; cols/rows size a map-TV
            // grid (1×1 otherwise); `data` holds kind-specific extra (e.g. the map ids
            // of a map-TV grid). One display per block position per kind.
            """
            CREATE TABLE IF NOT EXISTS displays (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                kind       TEXT    NOT NULL,
                world      TEXT    NOT NULL,
                x          INTEGER NOT NULL,
                y          INTEGER NOT NULL,
                z          INTEGER NOT NULL,
                item_id    TEXT    NOT NULL,
                cols       INTEGER NOT NULL DEFAULT 1,
                rows       INTEGER NOT NULL DEFAULT 1,
                facing     TEXT,
                data       TEXT,
                owner      TEXT,
                created_at INTEGER NOT NULL,
                UNIQUE (world, x, y, z, kind)
            );
            CREATE INDEX IF NOT EXISTS idx_displays_kind ON displays (kind);
            """,

            // v15 — Arcade (Phase 8): per-player token balance + login-streak state
            // + playtime-milestone marker. All in-game currency; Mini prizes still go
            // through the standard mint pipeline (this table holds no prizes, just tokens).
            """
            CREATE TABLE IF NOT EXISTS arcade_tokens (
                player            TEXT    PRIMARY KEY,
                tokens            INTEGER NOT NULL DEFAULT 0,
                streak            INTEGER NOT NULL DEFAULT 0,
                last_streak_day   INTEGER NOT NULL DEFAULT 0,
                playtime_tokens   INTEGER NOT NULL DEFAULT 0
            );
            """,

            // v16 — Phase 9. Retire minted copies on destruction (mint_number kept
            // forever, circulation decremented); per-player quest progress; and
            // one-time achievement unlocks. Arcade machines reuse placed_blocks.
            """
            ALTER TABLE mini_individuals ADD COLUMN retired_at INTEGER;
            CREATE TABLE IF NOT EXISTS quest_progress (
                player     TEXT    NOT NULL,
                quest_id   TEXT    NOT NULL,
                period_key TEXT    NOT NULL,
                progress   INTEGER NOT NULL DEFAULT 0,
                claimed    INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (player, quest_id, period_key)
            );
            CREATE TABLE IF NOT EXISTS achievements_unlocked (
                player       TEXT    NOT NULL,
                achievement  TEXT    NOT NULL,
                unlocked_at  INTEGER NOT NULL,
                PRIMARY KEY (player, achievement)
            );
            """,

            // v17 — Phase 9 Cards & Printer. Per-type Card issuance tally (for card
            // caps) and the set of printers flagged public (free Mall printers).
            """
            CREATE TABLE IF NOT EXISTS card_counts (
                card_id  TEXT    PRIMARY KEY,
                issued   INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS public_printers (
                world TEXT    NOT NULL,
                x     INTEGER NOT NULL,
                y     INTEGER NOT NULL,
                z     INTEGER NOT NULL,
                PRIMARY KEY (world, x, y, z)
            );
            """,

            // v18 — Round 3a. The per-player Card Binder (how many of each Card a player
            // has stored in their binder). (The TV block was retired; its tv_urls table
            // is no longer created — any leftover table on an old DB is harmless.)
            """
            CREATE TABLE IF NOT EXISTS binder_cards (
                player  TEXT    NOT NULL,
                card_id TEXT    NOT NULL,
                count   INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (player, card_id)
            );
            """,

            // v19 — per-player daily BUY tallies (anti-drain buy limit), mirroring
            // market_daily_sells. day = UTC epoch-day; one row per player/day/item.
            """
            CREATE TABLE IF NOT EXISTS market_daily_buys (
                player_uuid TEXT    NOT NULL,
                day         INTEGER NOT NULL,
                item_id     TEXT    NOT NULL,
                units       INTEGER NOT NULL,
                money       REAL    NOT NULL,
                PRIMARY KEY (player_uuid, day, item_id)
            );
            CREATE INDEX IF NOT EXISTS idx_daily_buys_day ON market_daily_buys (player_uuid, day);
            """,

            // v20 — Natural wild-Mini spawns: a minted head placed in the world that a
            // player claims by touching it, or that despawns (retiring the copy) after
            // its lifetime. Persisted so lifetimes survive restarts and nothing leaks.
            """
            CREATE TABLE IF NOT EXISTS mini_spawns (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                world       TEXT    NOT NULL,
                x           INTEGER NOT NULL,
                y           INTEGER NOT NULL,
                z           INTEGER NOT NULL,
                mini_id     TEXT    NOT NULL,
                uid         TEXT    NOT NULL,
                mint_number INTEGER NOT NULL,
                item_b64    TEXT    NOT NULL,
                spawned_at  INTEGER NOT NULL,
                expires_at  INTEGER NOT NULL,
                UNIQUE (world, x, y, z)
            );
            CREATE INDEX IF NOT EXISTS idx_mini_spawns_expires ON mini_spawns (expires_at);
            """,

            // v21 — Mini heads placed as plain blocks: registry for effects + a guarantee
            // that the exact copy is dropped back on any kind of destruction.
            """
            CREATE TABLE IF NOT EXISTS placed_minis (
                world     TEXT    NOT NULL,
                x         INTEGER NOT NULL,
                y         INTEGER NOT NULL,
                z         INTEGER NOT NULL,
                uid       TEXT    NOT NULL,
                mini_id   TEXT    NOT NULL,
                placed_at INTEGER NOT NULL,
                UNIQUE (world, x, y, z)
            );
            """,

            // v22 — Weapons and Armor merged into one Combat department. The Store and
            // Market tab rows hold "All" plus eight departments and no more, and Materials
            // needed the ninth slot. A listing keeps the department it was filed under, so
            // without this an existing Pallet listing would be reachable only from "All".
            """
            UPDATE pallet_listings SET department = 'Combat'
             WHERE department IN ('Weapons', 'Armor')
            """,

            // v23 — statistic-backed quests. A quest like "walk 500 blocks" is read from the
            // player's lifetime vanilla statistic rather than pushed by a listener, so it has
            // to remember the last total it saw. Progress is then accumulated from the
            // difference each poll rather than diffed from a fixed start, which is what lets
            // a stretch spent in a world the economy is disabled in (§11 #1) be skipped
            // instead of banked. -1 means "never observed", distinguishable from a genuine 0.
            """
            ALTER TABLE quest_progress ADD COLUMN stat_mark INTEGER NOT NULL DEFAULT -1;
            """,

            // v24 — Courier jobs (Phase 1). Everything that decides a payout is locked at
            // acceptance and stored here, so a restart mid-run cannot change what the job is
            // worth: the clamped distance, the cargo's value at the time, and the player's
            // movement counters as they stood. `day` is the UTC epoch-day of acceptance,
            // keyed the same way as market_daily_sells so the per-band cap needs no scheduler.
            """
            CREATE TABLE IF NOT EXISTS courier_jobs (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                player        TEXT    NOT NULL,
                type          TEXT    NOT NULL,
                state         TEXT    NOT NULL,
                band          TEXT    NOT NULL,
                world         TEXT    NOT NULL,
                accept_x      INTEGER NOT NULL,
                accept_y      INTEGER NOT NULL,
                accept_z      INTEGER NOT NULL,
                way_x         INTEGER NOT NULL,
                way_y         INTEGER NOT NULL,
                way_z         INTEGER NOT NULL,
                distance      INTEGER NOT NULL,
                cargo_material TEXT,
                cargo_amount  INTEGER NOT NULL DEFAULT 0,
                cargo_value   REAL    NOT NULL DEFAULT 0,
                stat_snapshot TEXT    NOT NULL,
                day           INTEGER NOT NULL,
                accepted_at   INTEGER NOT NULL,
                expires_at    INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_courier_active ON courier_jobs (player, state);
            CREATE INDEX IF NOT EXISTS idx_courier_daily ON courier_jobs (player, day, band, state);
            """,

            // v25 — Courier delivery sites (Phase 2). One row per placed building, holding
            // everything needed to put the field back exactly as it was: the region's origin
            // and size, and `snapshot`, a GZIPped palette+indices blob of the ORIGINAL blocks
            // taken before the first one was changed. The row outlives the job on purpose —
            // it is deleted only once the restore has actually run, so a crash mid-delivery
            // leaves a record to sweep on the next enable rather than a house on the map.
            """
            CREATE TABLE IF NOT EXISTS courier_sites (
                job_id      INTEGER PRIMARY KEY,
                world       TEXT    NOT NULL,
                origin_x    INTEGER NOT NULL,
                origin_y    INTEGER NOT NULL,
                origin_z    INTEGER NOT NULL,
                size_x      INTEGER NOT NULL,
                size_y      INTEGER NOT NULL,
                size_z      INTEGER NOT NULL,
                template    TEXT    NOT NULL,
                rotation    TEXT    NOT NULL,
                door_x      INTEGER NOT NULL,
                door_y      INTEGER NOT NULL,
                door_z      INTEGER NOT NULL,
                villager    TEXT,
                state       TEXT    NOT NULL,
                snapshot    BLOB    NOT NULL,
                placed_at   INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_courier_sites_state ON courier_sites (state);
            CREATE INDEX IF NOT EXISTS idx_courier_sites_chunk ON courier_sites (world, origin_x, origin_z);
            """,

            // v26 — the crate's loss fee and the debt it can leave behind. A package is
            // worthless, so the fee is not compensation for an item — it is what stops the
            // crate being something you can shrug off, and it is the only reason carrying it
            // is a responsibility rather than a decoration.
            //
            // `package_settled` is the piece that makes it honest when somebody logs off. A
            // run that closes without a hand-over cannot be settled there and then if nobody
            // is there to look in, so it is marked 0 and settled on their next join — where
            // the answer is simply whether the crate came back with them. Without it, logging
            // out would be a way to lose the crate for free, which is both trivial to find
            // and the exact behaviour the fee exists to discourage. Existing rows default to
            // 1: nothing owed, nothing to check.
            """
            CREATE TABLE IF NOT EXISTS courier_debt (
                player TEXT PRIMARY KEY,
                amount REAL    NOT NULL DEFAULT 0,
                since  INTEGER NOT NULL
            );
            ALTER TABLE courier_jobs ADD COLUMN package_settled INTEGER NOT NULL DEFAULT 1;
            """,

            // v27 — whose delivery a site belongs to. The restore waits for the player to walk
            // away, and "the player" has to mean the one who made the delivery: measuring
            // against ANYONE online let a neighbour going about their own business hold a house
            // standing on somebody else's run. Staying inside the footprint is the exception and
            // stays everyone's, because that rule is about not suffocating whoever is standing
            // there, and it does not matter whose delivery put the blocks above them.
            //
            // Null on rows written before this, which read as "no particular player" and fall
            // back to the old any-player behaviour rather than restoring under somebody.
            """
            ALTER TABLE courier_sites ADD COLUMN player TEXT;
            """,

            // v28 — the token ledger. Every change to a token balance writes one row here in
            // the same transaction as the balance update, so "where do tokens come from and
            // where do they go" is a query rather than a guess. balance_after makes a row
            // self-describing (no replay needed to know what a player had at the time), and
            // (at) alone serves the whole-server audit window.
            """
            CREATE TABLE IF NOT EXISTS token_ledger (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                player        TEXT    NOT NULL,
                delta         INTEGER NOT NULL,
                balance_after INTEGER NOT NULL,
                source        TEXT    NOT NULL,
                detail        TEXT,
                at            INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_token_ledger_player ON token_ledger (player, at);
            CREATE INDEX IF NOT EXISTS idx_token_ledger_at ON token_ledger (at);
            """,

            // v29 — the wild hunt mints on pickup. A live spawn is now a BLUEPRINT (which Mini,
            // which grade and finish, who it spawned near, when it goes) with no uid and no mint
            // number: mini_spawns required both, and SQLite cannot relax a NOT NULL, so the
            // blueprint gets its own table. Legacy mini_spawns rows (each holding a minted copy)
            // are converted in Java on the next enable, because the grade and finish live inside
            // the stored item. `escaped` counts spawns that got away (nothing is minted for
            // them); `origin` says how a copy came to exist; `mini_free_numbers` holds mint
            // numbers handed back by the escaped-copy repair, re-used lowest first. `hunt_lures`
            // is one armed Mini Lure per player, so an armed lure survives a restart.
            """
            CREATE TABLE IF NOT EXISTS wild_spawns (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                world       TEXT    NOT NULL,
                x           INTEGER NOT NULL,
                y           INTEGER NOT NULL,
                z           INTEGER NOT NULL,
                mini_id     TEXT    NOT NULL,
                grade       TEXT    NOT NULL,
                finish      TEXT,
                target      TEXT,
                anchor_x    INTEGER NOT NULL,
                anchor_z    INTEGER NOT NULL,
                spawned_at  INTEGER NOT NULL,
                expires_at  INTEGER NOT NULL,
                hint_stage  INTEGER NOT NULL DEFAULT 0,
                UNIQUE (world, x, y, z)
            );
            CREATE INDEX IF NOT EXISTS idx_wild_spawns_mini ON wild_spawns (mini_id);
            ALTER TABLE mini_counts ADD COLUMN escaped INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE mini_individuals ADD COLUMN origin TEXT;
            CREATE TABLE IF NOT EXISTS mini_free_numbers (
                mini_id     TEXT    NOT NULL,
                mint_number INTEGER NOT NULL,
                freed_at    INTEGER NOT NULL,
                PRIMARY KEY (mini_id, mint_number)
            );
            CREATE TABLE IF NOT EXISTS hunt_lures (
                player    TEXT    PRIMARY KEY,
                armed_at  INTEGER NOT NULL
            );
            """,

            // v30 — the token economy. prize_purchases counts buys against a Prize Counter
            // limit per period (d<day> / w<week> / life, local days). cosmetics_owned is a trail
            // (or later cosmetic) with its expiry and on/off switch. quest_assignments is each
            // player's own draw from the quest pools; quest_biomes and player_biomes are the
            // distinct biomes entered per quest period and ever. player_counters backs the
            // COUNTER achievements. arcade_state holds single numbers the Arcade keeps: the
            // Scratch Ticket pot and the trophy serial.
            """
            CREATE TABLE IF NOT EXISTS prize_purchases (
                player     TEXT    NOT NULL,
                prize_id   TEXT    NOT NULL,
                period_key TEXT    NOT NULL,
                count      INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (player, prize_id, period_key)
            );
            CREATE TABLE IF NOT EXISTS cosmetics_owned (
                player      TEXT    NOT NULL,
                cosmetic_id TEXT    NOT NULL,
                expires_at  INTEGER NOT NULL,
                enabled     INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (player, cosmetic_id)
            );
            CREATE TABLE IF NOT EXISTS quest_assignments (
                player     TEXT    NOT NULL,
                period_key TEXT    NOT NULL,
                slot       INTEGER NOT NULL,
                quest_id   TEXT    NOT NULL,
                PRIMARY KEY (player, period_key, slot)
            );
            CREATE TABLE IF NOT EXISTS quest_biomes (
                player     TEXT    NOT NULL,
                period_key TEXT    NOT NULL,
                biome      TEXT    NOT NULL,
                PRIMARY KEY (player, period_key, biome)
            );
            CREATE TABLE IF NOT EXISTS player_biomes (
                player   TEXT    NOT NULL,
                biome    TEXT    NOT NULL,
                first_at INTEGER NOT NULL,
                PRIMARY KEY (player, biome)
            );
            CREATE TABLE IF NOT EXISTS player_counters (
                player  TEXT    NOT NULL,
                counter TEXT    NOT NULL,
                value   INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (player, counter)
            );
            CREATE TABLE IF NOT EXISTS arcade_state (
                key   TEXT    PRIMARY KEY,
                value INTEGER NOT NULL
            );
            """,
            // v31 — the +1 Home perk replaces the Second Home / Third Home rows. Everyone who
            // bought either is credited that many home_slot purchases (at most two, the new
            // row's lifetime limit), so what they paid for carries over as slots on top of
            // their own homes. The old rows' purchases are kept: they are how the perk knows
            // which homes2/homes3 nodes it granted, and so may clear.
            Database.CREDIT_HOME_SLOTS,
            // v32 — the live market (0.33). The sim's own bookkeeping only: it never writes stock
            // or market_state, so nothing here can change what Crate holds or its balanced price.
            // market_sim_state is one row per item: drift is the quiet wander d as a fraction,
            // and the three *_at columns are the news, HOT/DEAL and WANTED cooldown clocks.
            // market_events is one row per event. Column notes:
            //   kind              HOT DEAL UP DOWN WANTED SEASON REAL
            //   source            SIM ADMIN CALENDAR REAL
            //   strength          signed fraction (+0.12 = 12% up)
            //   pct               the announced % (signed)
            //   headline, line    rendered with & codes, which the feeds strip
            //   tag               NULL except SEASON (id:year) and REAL (item:symbol:tradeDay), and
            //                     the partial unique index makes those two idempotent
            //   *_ms, *_at        milliseconds, and a NULL *_at has not happened
            // market_sim_meta holds the seed (hex), the schedule and the anti-spam counters.
            // market_sim_ledger is what the sim paid out (sell_bonus) or saved buyers
            // (buy_discount) per local day and item, where day is GameClock.dayKey().
            // market_news_seen is each player's catch-up mark and mute. market_real_quotes caches
            // real-world closes per symbol and trade day (an epoch day), and applied = 1 once the
            // impulse for that day has been queued.
            """
            CREATE TABLE IF NOT EXISTS market_sim_state (
                item_id          TEXT    PRIMARY KEY,
                drift            REAL    NOT NULL DEFAULT 0,
                last_news_at     INTEGER NOT NULL DEFAULT 0,
                featured_until   INTEGER NOT NULL DEFAULT 0,
                last_wanted_at   INTEGER NOT NULL DEFAULT 0,
                updated_at       INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS market_events (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                kind            TEXT    NOT NULL,
                source          TEXT    NOT NULL,
                item_id         TEXT,
                tag             TEXT,
                strength        REAL    NOT NULL DEFAULT 0,
                started_at      INTEGER NOT NULL,
                ramp_ms         INTEGER NOT NULL DEFAULT 0,
                hold_ms         INTEGER NOT NULL DEFAULT 0,
                fade_ms         INTEGER NOT NULL DEFAULT 0,
                half_life_ms    INTEGER NOT NULL DEFAULT 0,
                lasts_ms        INTEGER NOT NULL DEFAULT 0,
                ends_at         INTEGER NOT NULL,
                stopped_at      INTEGER,
                stop_reason     TEXT,
                pct             REAL    NOT NULL DEFAULT 0,
                price_before    REAL    NOT NULL DEFAULT 0,
                price_after     REAL    NOT NULL DEFAULT 0,
                headline        TEXT,
                line            TEXT,
                announce_due_at INTEGER,
                announced_at    INTEGER,
                last_call_at    INTEGER,
                end_line_at     INTEGER
            );
            CREATE INDEX IF NOT EXISTS idx_market_events_ends ON market_events (ends_at);
            CREATE INDEX IF NOT EXISTS idx_market_events_started ON market_events (started_at);
            CREATE INDEX IF NOT EXISTS idx_market_events_item ON market_events (item_id, started_at);
            CREATE UNIQUE INDEX IF NOT EXISTS idx_market_events_tag ON market_events (kind, tag) WHERE tag IS NOT NULL;
            CREATE TABLE IF NOT EXISTS market_sim_meta (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS market_sim_ledger (
                day          INTEGER NOT NULL,
                item_id      TEXT    NOT NULL,
                sell_bonus   REAL    NOT NULL DEFAULT 0,
                buy_discount REAL    NOT NULL DEFAULT 0,
                units_sold   INTEGER NOT NULL DEFAULT 0,
                units_bought INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (day, item_id)
            );
            CREATE TABLE IF NOT EXISTS market_news_seen (
                player        TEXT    PRIMARY KEY,
                last_event_id INTEGER NOT NULL DEFAULT 0,
                muted         INTEGER NOT NULL DEFAULT 0,
                seen_at       INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS market_real_quotes (
                symbol     TEXT    NOT NULL,
                trade_day  INTEGER NOT NULL,
                close      REAL    NOT NULL,
                fetched_at INTEGER NOT NULL,
                applied    INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (symbol, trade_day)
            )
            """,
            // v33 — Sound Mufflers. One row per placed muffler, next to its placed_blocks row
            // (which still says what the block is and who owns it). radius is blocks in each
            // direction, quiet_percent is the volume "Quieter" plays at, and rules is the picks as
            // text, one per line: "g <group id> <LEVEL>" or "s <sound key> <LEVEL>".
            """
            CREATE TABLE IF NOT EXISTS sound_mufflers (
                world         TEXT    NOT NULL,
                x             INTEGER NOT NULL,
                y             INTEGER NOT NULL,
                z             INTEGER NOT NULL,
                owner         TEXT    NOT NULL,
                enabled       INTEGER NOT NULL DEFAULT 1,
                radius        INTEGER NOT NULL,
                quiet_percent INTEGER NOT NULL,
                rules         TEXT    NOT NULL DEFAULT '',
                updated_at    INTEGER NOT NULL,
                PRIMARY KEY (world, x, y, z)
            )
            """,
            // v34 — the Games (0.35), every table in one block (GamesDao).
            // game_rounds: one row per round of a game of chance (OPEN while a multi-step round is
            //   played, SETTLED once paid — stake, payout and the row land in one transaction), plus
            //   one DAILY row per player per game per day for a skill game's one scored daily attempt.
            //   day is the LOCAL epoch day; opponent is the other player of a Coin Flip (one row each);
            //   touched_at moves on open, step and raise (a round left 10 minutes is settled for you).
            //   At most one OPEN round per player per game, and one DAILY row per day.
            // game_breaks: Take a break. -1 = no limit, pending_tokens -2 = nothing waiting.
            // game_scores: each player's best per board, and how many runs.
            // game_rewards: skill rewards. A one-time reward has a non-empty ref and the partial
            //   unique index refuses it twice; repeatable ones have ref ''. game is '*' for the
            //   once-a-day-across-games kinds; played is always the game that paid (its daily cap).
            // game_saved_state: a player's things while they are in a world game, one row per session,
            //   written BEFORE anything is changed and marked DONE only once they are back (phases
            //   ACTIVE, RETURN, DONE; DONE rows are pruned after a week). At most one live row a player.
            // game_courses: world-game courses (data = the game's own YAML); rev bumps on each edit.
            // game_prefs: per-player choices (invites on or off, the golf ball, queued join lines).
            """
            CREATE TABLE IF NOT EXISTS game_rounds (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                player     TEXT    NOT NULL,
                game       TEXT    NOT NULL,
                day        INTEGER NOT NULL,
                stake      INTEGER NOT NULL DEFAULT 0,
                payout     INTEGER NOT NULL DEFAULT 0,
                state      TEXT    NOT NULL,
                seed       INTEGER NOT NULL DEFAULT 0,
                data       TEXT,
                opponent   TEXT,
                created_at INTEGER NOT NULL,
                touched_at INTEGER NOT NULL DEFAULT 0,
                settled_at INTEGER
            );
            CREATE INDEX IF NOT EXISTS idx_game_rounds_player_day ON game_rounds (player, game, day);
            CREATE INDEX IF NOT EXISTS idx_game_rounds_open ON game_rounds (state, player);
            CREATE INDEX IF NOT EXISTS idx_game_rounds_pair ON game_rounds (game, day, player, opponent);
            CREATE UNIQUE INDEX IF NOT EXISTS idx_game_rounds_one_open ON game_rounds (player, game) WHERE state = 'OPEN';
            CREATE UNIQUE INDEX IF NOT EXISTS idx_game_rounds_daily ON game_rounds (player, game, day) WHERE state = 'DAILY';
            CREATE TABLE IF NOT EXISTS game_breaks (
                player             TEXT    PRIMARY KEY,
                daily_tokens       INTEGER NOT NULL DEFAULT -1,
                pending_tokens     INTEGER NOT NULL DEFAULT -2,
                pending_day        INTEGER NOT NULL DEFAULT 0,
                paused_until       INTEGER NOT NULL DEFAULT 0,
                admin_tokens       INTEGER NOT NULL DEFAULT -1,
                admin_paused_until INTEGER NOT NULL DEFAULT 0,
                updated_at         INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS game_scores (
                player TEXT    NOT NULL,
                game   TEXT    NOT NULL,
                board  TEXT    NOT NULL,
                score  INTEGER NOT NULL,
                at     INTEGER NOT NULL,
                runs   INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY (player, game, board)
            );
            CREATE INDEX IF NOT EXISTS idx_game_scores_board ON game_scores (game, board, score);
            CREATE TABLE IF NOT EXISTS game_rewards (
                id     INTEGER PRIMARY KEY AUTOINCREMENT,
                player TEXT    NOT NULL,
                game   TEXT    NOT NULL,
                played TEXT    NOT NULL DEFAULT '',
                day    INTEGER NOT NULL,
                kind   TEXT    NOT NULL,
                ref    TEXT    NOT NULL DEFAULT '',
                tokens INTEGER NOT NULL,
                at     INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_game_rewards_player_day ON game_rewards (player, day);
            CREATE UNIQUE INDEX IF NOT EXISTS idx_game_rewards_once ON game_rewards (player, game, kind, ref) WHERE ref <> '';
            CREATE TABLE IF NOT EXISTS game_saved_state (
                session_id    TEXT    PRIMARY KEY,
                player        TEXT    NOT NULL,
                game          TEXT    NOT NULL,
                ref           TEXT    NOT NULL DEFAULT '',
                phase         TEXT    NOT NULL,
                session_world TEXT    NOT NULL,
                items         BLOB,
                carry         BLOB,
                xp_level      INTEGER,
                xp_progress   REAL,
                xp_total      INTEGER,
                health        REAL,
                food          INTEGER,
                saturation    REAL,
                exhaustion    REAL,
                fire_ticks    INTEGER,
                air           INTEGER,
                game_mode     TEXT,
                allow_flight  INTEGER,
                flying        INTEGER,
                walk_speed    REAL,
                fly_speed     REAL,
                absorption    REAL,
                effects       TEXT,
                world         TEXT,
                x             REAL,
                y             REAL,
                z             REAL,
                yaw           REAL,
                pitch         REAL,
                created_at    INTEGER NOT NULL,
                done_at       INTEGER
            );
            CREATE UNIQUE INDEX IF NOT EXISTS idx_game_saved_state_live ON game_saved_state (player) WHERE phase <> 'DONE';
            CREATE TABLE IF NOT EXISTS game_courses (
                id         TEXT    PRIMARY KEY,
                game       TEXT    NOT NULL,
                kind       TEXT    NOT NULL,
                name       TEXT    NOT NULL,
                world      TEXT    NOT NULL,
                enabled    INTEGER NOT NULL DEFAULT 1,
                data       TEXT    NOT NULL,
                rev        INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS game_prefs (
                player TEXT NOT NULL,
                pref   TEXT NOT NULL,
                value  TEXT,
                PRIMARY KEY (player, pref)
            )
            """,
            // v35 — Fresh Courses' archive (GEN-SPEC-KEEP §1, §8): one row per edition that was ever
            //   playable, written in the same transaction as the flip that made it live (GenArchiveDao).
            //   edition is the board's key (7:40, 7:40r1); code is the slot's own count (HARD-40),
            //   unique and never reused (seq, with gen.<slot>.codes in hcm_meta); day is the edition's
            //   first local day; algo is <generator>/<version>; starts_at is when it went live and
            //   ends_at when the next flip replaced it (NULL while live); plan is the gzipped, versioned
            //   plan (PlanCodec), so a past course is rebuilt exactly and never planned again; gold_ms and
            //   silver_ms are its star times as they were; kept_as is the course it was kept as.
            """
            CREATE TABLE IF NOT EXISTS gen_editions (
                slot        TEXT    NOT NULL,
                edition     TEXT    NOT NULL,
                code        TEXT    NOT NULL,
                seq         INTEGER NOT NULL,
                day         INTEGER NOT NULL,
                seed        INTEGER NOT NULL,
                algo        TEXT    NOT NULL,
                kind        TEXT    NOT NULL,
                tier_or_mix TEXT    NOT NULL DEFAULT '',
                name        TEXT    NOT NULL,
                starts_at   INTEGER NOT NULL,
                ends_at     INTEGER,
                plan        BLOB,
                gold_ms     INTEGER NOT NULL DEFAULT 0,
                silver_ms   INTEGER NOT NULL DEFAULT 0,
                built_at    INTEGER NOT NULL,
                kept_as     TEXT,
                PRIMARY KEY (slot, edition)
            );
            CREATE UNIQUE INDEX IF NOT EXISTS idx_gen_editions_code ON gen_editions (code);
            CREATE INDEX IF NOT EXISTS idx_gen_editions_start ON gen_editions (slot, starts_at)
            """
    };

    /** Schema v31's one statement, named so a test can run it against seeded rows. */
    static final String CREDIT_HOME_SLOTS = """
            INSERT OR IGNORE INTO prize_purchases (player, prize_id, period_key, count)
                SELECT player, 'home_slot', 'life', MIN(2, SUM(count))
                FROM prize_purchases
                WHERE prize_id IN ('home_2', 'home_3') AND period_key = 'life' AND count > 0
                GROUP BY player
            """;

    /** One unit of work run inside {@link #transaction}. */
    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private final HomeCraftManagement plugin;
    private final java.util.logging.Logger log;
    private Connection connection;
    private File dbFile;

    public Database(HomeCraftManagement plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    /** A database over an already-open connection (tests use an in-memory SQLite). */
    private Database(Connection connection, java.util.logging.Logger log) {
        this.plugin = null;
        this.log = log;
        this.connection = connection;
    }

    /**
     * Wrap an open connection and bring it to the current schema. No file, no pre-migration
     * backup — this exists so a DAO can be exercised against a real SQLite without a server.
     */
    public static Database open(Connection connection, java.util.logging.Logger log) throws SQLException {
        Database db = new Database(connection, log);
        db.migrate();
        return db;
    }

    public Connection connection() {
        return connection;
    }

    /**
     * Run {@code work} as one transaction: every statement lands or none does.
     *
     * <p>Holds the connection's monitor for the whole unit, the same lock every DAO takes, so
     * nothing else can interleave a statement between a guarded UPDATE and the rows that depend
     * on it. Re-entrant: called from inside another transaction it simply joins it, so the
     * outer unit still commits (or rolls back) as one.
     */
    public <T> T transaction(SqlWork<T> work) throws SQLException {
        Connection c = connection;
        synchronized (c) {
            if (!c.getAutoCommit()) {
                return work.run(c); // already inside a transaction — join it
            }
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public void connect() throws SQLException {
        try {
            // Explicit load: the plugin classloader won't always honour the
            // ServiceLoader auto-registration of the shaded driver.
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Bundled SQLite JDBC driver not found on the classpath", e);
        }

        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new SQLException("Could not create data folder: " + dir);
        }
        File dbFile = new File(dir, "homecraft.db");
        this.dbFile = dbFile;
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        }
        migrate();
    }

    /** The on-disk database file. */
    public File file() {
        return dbFile;
    }

    private void migrate() throws SQLException {
        synchronized (connection) {
            try (Statement st = connection.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS hcm_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            }
            int current = schemaVersion();
            if (current < MIGRATIONS.length && current > 0 && plugin != null) {
                // A schema change is about to run: keep a copy of the file first (§11 #6).
                BackupService.preMigrationCopy(plugin, dbFile);
            }
            for (int v = current + 1; v <= MIGRATIONS.length; v++) {
                log.info("Applying database migration v" + v + "…");
                try (Statement st = connection.createStatement()) {
                    for (String stmt : MIGRATIONS[v - 1].split(";")) {
                        if (!stmt.isBlank()) {
                            st.execute(stmt);
                        }
                    }
                }
                setSchemaVersion(v);
            }
        }
    }

    private int schemaVersion() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT value FROM hcm_meta WHERE key = 'schema_version'")) {
            if (rs.next()) {
                try {
                    return Integer.parseInt(rs.getString(1));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
            return 0;
        }
    }

    private void setSchemaVersion(int v) throws SQLException {
        try (var ps = connection.prepareStatement(
                "INSERT INTO hcm_meta(key, value) VALUES('schema_version', ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, Integer.toString(v));
            ps.executeUpdate();
        }
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warning("Error closing database: " + e.getMessage());
            }
            connection = null;
        }
    }
}
