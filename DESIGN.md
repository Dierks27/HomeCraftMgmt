# HomeCraft Management — Plugin Design Specification (v17.16)
> **Purpose of this document:** the build spec for a custom Paper plugin. It is written to be handed to Claude Code (or any implementer) as the source of truth. Design decisions still open are marked **[DECISION]** with a recommended default.
>
> **v11 changelog:** Rebranded the online store from "Amazon" to **Crate** (`[www.Crate.craft](https://www.Crate.craft)`), with **Rush** (fast shipping), the **Pallet** (player seller box) and the **Locker** (delivery holding). Added the **Crate Marketplace** (universal player-to-player selling — *everything* is sellable, incl. Minis), **auto-categorization into departments** using the game's own item categories, an **admin ban list**, and the **PC-as-a-browser / "Sites"** architecture. Added **in-game economy displays** (TVs/tickers/boards) and a consolidated **Economy Risks & Safeguards** section. Recorded the **Phase 2.5.1 pricing fix** (proportional elasticity + integrated bulk pricing). Marked Phases 2.5 and 3 done.
>
> **v17.16 changelog (0.33.0 — the live market):** Crate's prices only moved when somebody traded, so on a server with a handful of players the market sat still for days: nothing to watch, no reason to check back, never a "sell it now!". Prices now **move a little on their own**, inside limits the code enforces whatever the config says (the owner's **hard rule**), at the swing size the owner chose, **Tight**. **The model:** the balanced price is exactly what it was (`state.currentPrice`, the stock curve and its glide; only player trades and `setstock`/`resetstock` move it), and at quote time the sim multiplies it by one **mood** `M` per item: `price = clamp(balanced × M, floor, ceiling)` with `M = 1 + drift + events + clamp(season + real, ±c_pred)`. **`M` is clamped in code to [0.75, 1.25]**, and config can narrow that band but never widen it. **The sim never writes stock and never moves the balanced price.** An item with no stock sits at exactly its ceiling whatever `M` is. **`market.sim.enabled: false` makes `M` exactly 1.0**, and every price, quote, order total and feed is then bit-for-bit 0.32 (`b × 1.0 == b` in IEEE-754; a test holds the new order maths to a verbatim copy of the 0.32 code at `M = 1`). **The layers, every one zero-mean:** a quiet **drift**, an exact Ornstein–Uhlenbeck step every 5 minutes with a 66-hour half-life: about 1% a day on staples and 2% on items where √(floor × ceiling) ≥ $10, never past 8%. **HOT** and **DEAL**: ±8–15% for 30–54 hours, about one of each a week, then a 7-day rest for that item. They **rise silently for 4 h and are announced only at full strength.** **NEWS FLASH** UP/DOWN: 15–25%, **instant in the tick it is announced**, half gone in 6 h and gone at 30 h. About 5 a week, at most 2 a local day, 07:00–21:00 only, and held until somebody has been online a few minutes. **WANTED** says "Crate is looking for …" about a sold-out item and moves nothing. **Seasons** come from a shipped calendar (Harvest Time, wheat −4%, runs on ship day). **Real-world** commodity closes are off by default. Seasons and real prices together are held to **min(4.5%, 45% of the spread)**, so knowing the calendar or yesterday's gold close can never beat a round trip through the spread. Each event is sized to at most 90% of the item's remaining headroom before it is created, so every announced percentage really happens. **Event limits bind everyone, ops included:** while an item is HOT or UP its daily sell cap applies to `hcm.market.limit.bypass` holders too, and while it is on a DEAL or DOWN so does "Limit N a day" (half the item's daily buy cap). That is the one place the bypass stops working, because it is the one place a price was moved on purpose. **A stale quote is refused** ("Prices just moved! Take another look.") when an item's mood jumps more than 1% between opening the Sell quantity screen or checkout and clicking confirm. **What players see:** badges (★ HOT, ✦ DEAL, ▲, ▼, » WANTED) on Store and Sell tiles, signs, holograms and TVs; "Usually $X" lines; a Market News button (the Sell screen's free slot 50) and screen; a "Hot & Deals" sort; "While you were away…" with a "Right now" line on join; `/hcm market news [on|off]`; an `@news` board for holograms and TVs; and new PlaceholderAPI keys. A broadcast is chat, a title, the action bar, a namespaced note-block sound and particles at the item's displays. There is **at most one per tick, 20 minutes apart, 6 a local day**, and nothing is ever broadcast late. **Kid-safe headlines** are about people *wanting* things. They are checked at load against a banned-word list and a shelf-word list, because the shelf never moves and no headline may say it did. **Deterministic and restart-proof:** a counter-based random stream keyed on the wall-clock tick, with a 64-bit seed that lives only in the database, so a restart replays the missed drift exactly (at most 48 h, no events, no broadcasts) and can re-roll nothing. **Audit:** every trade made at a moved price books its difference from the same trade at `M = 1` in a daily ledger, and `/hcm market sim audit` prints it. **Feeds:** only while the sim runs, `/api/market` gains `usual`, `moodPct`, `status`, `statusEndsAt` and `events` per item and `live`/`hot`/`deals`/`season`/`news` at the top level, and there is a new `GET /api/news`. **Admin:** `/hcm market sim status|hot|deal|stop|reset|pause|resume|preview|audit|real` and `/hcm market news <item> <up|down|wanted>`, under the new op node `hcm.market.sim`. **Sounds** ship as `minecraft:block.note_block.*`, because `AnnounceService.sound` turns a plain `BLOCK_NOTE_BLOCK_BELL` into a key that does not exist and falls back to the XP orb. **Schema v32** adds six tables. **No `config_revision` bump:** every `market.sim` key is new and is back-filled with its comments. **Nothing moves stock:** proposals that healed stock or delivered goods (a warehouse heal, a shelf heal, DELIVERY) were dropped for exactly that reason, and a stuck balanced price is fixed on demand with `setstock` at the current stock, never on a timer. See §3.1 "The live market (0.33)", §3.7, §3.8, §10, §11 #11 and the README's "Live market (0.33)".
>
> **v17.15 changelog (0.32.0 — the website's feeds):** The LilahCraft website (the WordPress theme) reads the dashboard's JSON from its own server only — fetched, cached, the last good copy kept, labelled sample data when a feed is missing — so each part of this switches a piece of the site to live and none of it can break the site. **`GET /api/minis`**, built exactly like `/api/market`: the main-thread refresh builds it and the handler only serves the cached bytes. One entry per catalog Mini in config order: `id`; `name` and `series` with colour codes stripped; `rarity`; `category` upper-case (`MISC` when blank); `cap` (−1 uncapped); `printed`, the `mini_counts.minted` tally (one query per refresh, `MiniDao.mintedCounts`); `soldOut`, capped and `printed >= cap`; and `skin`, the Base64 texture decoded to `textures.SKIN.url`, forced to https and sent only when it is exactly `https://textures.minecraft.net/texture/<hex>`, the one shape the site accepts. Nothing else — no owners, holders, provenance, UUIDs or balances; the builder is only ever handed definitions and counts. **`history7d` / `history30d`** on every `/api/market` item: the last 168 epoch-aligned hours and the last 120 six-hour buckets, oldest first, each point the **latest snapshot in its bucket** (a real row, not an average), `p` to 4 decimals. An array with no points is left out, which is how the site knows to keep that range off. `history` and every other field are byte-for-byte what they were, pinned by a test against a verbatim copy of the old builder. **No new table.** The spec asked for one if the 96 half-hour points lived in memory; they never did — `market_price_history` has taken a row per item every 30 minutes since Phase 2.5 and was never pruned. Both arrays come from it (`PriceHistoryDao.sampled`, one `GROUP BY recorded_at / bucket` query per item and range on the existing `(item_id, recorded_at)` index), so they survive restarts as they are and fill on the first refresh of a server with history; they are re-read only after a new snapshot and at most every ten minutes, not on every refresh. **Pruning:** `market.price_history.keep_days` (30; 0 keeps everything) deletes older snapshots after each snapshot run, at most 50,000 rows a run (`PriceHistoryDao.PRUNE_BATCH`), so the first prune of months of history spreads over a few ticks instead of stalling one. An upgraded server loses its history older than 30 days unless `keep_days: 0` is added first. **gzip** for `/api/*` when `Accept-Encoding` allows it, compressed at most once per rebuild (on the first request that asks), with `Vary: Accept-Encoding` — each item can now carry 96 + 168 + 120 points. **The feed token:** `web.dashboard.feed_token`, blank by default, and blank is the old, open behaviour. When set, every `/api/*` feed goes through one gate that wants `Authorization: Bearer <token>` and answers anything else `401` with `WWW-Authenticate: Bearer` and `{"error":"unauthorized"}`. `FeedAuth` keeps only the token's SHA-256 and compares digests with `MessageDigest.isEqual` — constant-time, and the length doesn't leak; the token is never logged or echoed (the startup line says on or off, and `WebDashboard.toString` masks it). **The dashboard page** fetches `/api/market` from the browser and has no token. Of the spec's two options this takes **"the page gets its data another way"**: **the page asks for the token** — on a 401 it forgets any stored token, stops refreshing, shows a password box, keeps what is typed in that browser (`localStorage` `hcmFeedToken`) and sends it as a Bearer header, so each browser asks once. The spec's other option is there as **`web.dashboard.lan_skips_token`, off by default** — loopback, private (10/8, 172.16/12, 192.168/16), link-local and IPv6 unique-local addresses skip the token, an IPv4-mapped IPv6 address is judged as its IPv4, CGNAT/Tailscale's 100.64/10 does not count, and a request carrying `Forwarded`, `X-Forwarded-For`, `X-Real-IP`, `CF-Connecting-IP` or any of six other forwarding headers never counts as local, whatever address it came from. **Why off, when the spec suggested on:** §3.7's way out to the internet is a Playit tunnel, and a Playit agent on this PC (like ssh -R, WireGuard, frp or cloudflared) hands internet traffic over from 127.0.0.1 or a LAN address with no forwarding header, so a default-on switch would let the whole internet past the token — and the spec's own acceptance check ("the same request answers 401" from the server PC) could never pass. The config comment and the README say when it is safe to turn on. **The server itself:** each request now gets its own virtual thread (the JDK reads headers with no time limit, so two half-sent requests could stall the old two-thread pool and every feed with it), the executor is shut down on stop (each `/hcm reload` used to leave two idle threads), and `HEAD` answers with the headers and no body instead of a log warning per request. The token should be plain ASCII (the page can only send that; a UTF-8 token from WordPress or curl is re-read as UTF-8 at the door, and a non-ASCII one logs a warning naming only the key). **No schema change; no `config_revision` bump** — `feed_token`, `lan_skips_token` and `keep_days` are new keys, back-filled with their comments. Part 4 of the spec (per-point volume and `/api/trades`) is not built: its shape gets agreed with the theme first.
>
> **v17.14 changelog (0.31.1 — homes that add up, a reset button, local midnight everywhere):** **+1 Home.** EssentialsX gives a player the *highest* `sethome-multiple` tier they hold, never the sum — checked against its `Settings.getHomeLimit` — so the Second/Third Home perks, which granted `homes2`/`homes3`, gave a mayor with 5 homes nothing. The new `home_slot` row (`costs: [400, 600]`, two per player) works out the player's own limit the way EssentialsX does (1; `default` with `essentials.sethome.multiple`; the best tier they hold, ignoring `hcm_*`), adds the slots bought, and grants exactly one tier `hcm_<total>` through the LuckPerms API — only when that differs from what they hold, so it never loops. It does **not** also grant `essentials.sethome.multiple` (the brief assumed tiers need it; in EssentialsX they don't, and it would switch on `default: 3` for a base-1 player). Recomputed on purchase, join, `/hcm reload`, group changes (LuckPerms events, ignoring its own `hcm_` writes) and `/hcm homes refresh [player]`; a missing `hcm_<N>` tier refuses the purchase before any charge and logs the line to add; hidden for unlimited players and bases of 20+. The tile shows the result per viewer ("+1 Home (you'll have 6)", "Owned (2 of 2)"). config_revision 16 swaps the shipped rows for `home_slot`; schema v31 credits Second/Third Home buyers with slots, and the perk clears the `homes2`/`homes3` nodes it granted. **`/hcm config reset <section> [confirm]`** puts `arcade` (or any `arcade.<part>`), `packs`, `minis.loot.natural`, `minis.effects` or `clock` back to the bundled defaults — a dry run first, then a snapshot, the swap (comments kept, section in place), a live reload, and a quest redraw when the pools changed; every other section is refused. **Local midnight:** the market's and the Courier's daily limits now roll over with `clock.time_zone` too (§11 #10 closed).
>
> **v17 changelog — 0.31.0, the Arcade overhaul (v17.8–v17.13):** Every token sink ended in Minis, the Scratch Ticket took and paid dollars, crates sold odds for dollars, every token "day" was a UTC day, wild Minis minted at spawn and burned their numbers when they escaped, packs were placeholder lists that never learned about a new Mini, and nothing explained any of it. Six releases fix that, in order: **v17.8** local midnight (`clock.time_zone`) for everything token-side, a `TokenService` with a guarded ledger (`/hcm tokens audit|history`), honest reveals and one cap check (`CardService.canIssue`); **v17.9** the wild hunt — blueprints that mint on pickup, reservations, free numbers (`/hcm mini repair-escaped`), real ground, hints and a beam (`/hcm hunt`); **v17.10** the token economy — no dollars in the Arcade, the tabbed Prize Counter with boosts, hunt gear, cosmetics, perks and a trophy, `hcm:token_prize`, the token Scratch Ticket with a jackpot, quest pools and 26 achievements; **v17.11** one-Card packs on rarity odds, sold for dollars or tokens, with a shop that shows what's inside; **v17.12** the Arcade hub, Wallet, Achievements and How It Works screens, the crate spin and scratch animations, Bedrock-safe icons; **v17.13** this documentation — §3.9 rewritten to match the code and §11 #9 tightened. Config revisions 12–15 carry every change onto a live config.yml, changing a value only while it still holds what we shipped and warning on everything an admin changed. Schema v28–v30. **Still open:** the market's and the Courier's daily money limits roll over at **UTC** midnight (§11 #10) — deliberately untouched here.
>
> **v17.12 changelog (the Arcade hub):** The Arcade was a handful of tiles and four retired machine screens that duplicated it; the earn explainer (Token Counter) was reachable only from a retired block, there was no screen for achievements and nothing explained how any of it worked. **The hub** is one 54-slot screen in coloured zones that read as rows on Bedrock too: the **Wallet** (balance, streak and tomorrow's reward, minutes to the next playtime token) on top; **Games** — the first three crates (an "All crates" tile when there are more), the Scratch Ticket with the jackpot in its name, and the wild-Mini status ("A Rare Mini is loose near Kaden! 3:12 left", hints so far in the lore, counting down while open); the **Prize Counter** tabs and **Card Packs**; and **You** — Quests ("2 of 3 done today"), Achievements ("7 / 26") and How It Works; Close on 49. **Wallet** (replaces `TokenCounterMenu`): streak, playtime, an armed lure, the active trail with a toggle, the ways to earn, and the last ten ledger lines in plain words ("+5 Quest: Catch 8 fish", "−25 Mini Radar"). **Achievements** screen: grouped, bright with a ✔ when unlocked, gray with progress ("37 / 100") when not, paged on 45/53; `/hcm achievements` (`hcm.achievements.use`). **How It Works**: four picture pages — Tokens, Minis, Wild Minis, Arcade games — from the hub, the pack shop's "?", a new **Guide** site on the PC and `/hcm guide [page]` (`hcm.guide.use`); `docs/how-it-works.md` is the plain version for the website. **Animations** are shows over outcomes already granted — closing early just prints the result: a **crate spin** (a strip of the crate's rewards slows over `arcade.reveal.spin_ticks` 60 in `frames` 24 steps, `bedrock_frames` 6 on Bedrock, and stops under two hoppers), a **Scratch Ticket** with three squares to tap (the symbols follow scratch order, so the suspense lands on the last), and **big wins** (a crate's Mini jackpot, the lotto jackpot, a Rare+ Card) add a title and a harmless firework to the server-wide shout. **Icons**: `arcade.icons.<key>` gives every hub and counter button a head texture and a colourful plain fallback; Bedrock players (Floodgate) always get the fallback. `CratePickMenu`, `ScratchMenu`, `PityMenu` and `TokenCounterMenu` are gone; placed Crate/Scratch/Pity/Token machines still work and open the matching hub screen.
>
> **v17.11 changelog (one Card, and you can see what's inside):** Pack pools were explicit lists of Mini ids — the shipped ones were piggy/chick placeholders — so a new Mini never joined a pack, the shop showed no contents or odds, a pack said "Value: $250", and nothing explained Card → Printer → Mini. **Pack schema v2:** `price` (dollars) and `price_tokens`, `count`, an optional `tag`, and `rarity_odds`. Opening rolls a rarity by the odds — only rarities with a Card left take part, scaled back to 100% (`PackOdds`) — then any issuable Mini of that rarity (with the tag, if set), through the one cap check. Nothing left means **Sold out**: the shop won't sell it and a sealed one stays sealed. A legacy hand-picked `pool:` still works and wins over the odds. A pack of several Cards that comes up short refunds the missing share in whatever currency paid for it (the sealed item carries `hcm:pack_paid`); a pack sealed back when packs held three still opens for three. **Shop:** the same `PackShopMenu` from the PC's Card Packs site and a new door in the Arcade (Back returns to whichever opened it); tiles carry the price in dollars and tokens in their name, "1 Card inside", the live odds and "You're missing N of the Minis in this pack"; a **detail page** lists every Mini it can give, marked ✓ / missing / sold out with its chance per Card, and has Buy buttons for each currency. Copy: pack lore is "Open it to get 1 Card. / Take the Card to a Printer to make a Mini. / Right-click to open." (no "Value:"); Card lore ends "Print this at a Printer to make your {rarity} Mini."; the launcher says "Open a pack, get a Card, print it into a Mini." **Reveal:** one Card builds up — a gray "?" shimmers, the frame lights in the rarity's colour, then the Card; Bedrock players (Floodgate, a soft dependency read by reflection) get the short version. `PackEditMenu` edits dollar and token prices, the tag and the five rarity odds beside the legacy pool. **config_revision 15:** every pack holds one Card; the shipped placeholder pools become the rarity-odds defaults (Starter $100 or 50 tokens: 62/28/9/1/0; Premium $300: 0/45/38/13/4); shipped prices 250 → 100 and 750 → 300; an admin's pool stays a pool, with a warning naming it.
>
> **v17.10 changelog (tokens buy fun):** Every Arcade token sink ended in Minis content, the Scratch Ticket cost and paid **dollars**, crates sold better odds for dollars (`paid_odds`), and tokens had nothing to buy but more Minis — so a player who didn't care for Minis had no reason to earn them. The rule is now **dollars buy things you keep, tokens buy fun, Minis come from the hunt**. **Prize Counter v2:** tabs (Boosts, Hunt, Cosmetics, Perks, Trophies, Minis), one screen each, with the price in every tile's name, "Need N more", a progress bar for expensive rows, "1 left today", "Owned", and a confirm screen at 100+ tokens. New prize types: **boost** (a right-click potion item that stacks up to 3× its time), the **Mini Radar** (Cold/Warm/Hot/Burning! toward the live wild Mini, with a rising ping), the **Mini Lure** (the next wild spawn for anyone lands near you; one armed per player, kept across restarts), a harmless **Firework Show**, 7-day **particle trails** (`/hcm trail`), **hats** (hidden while their texture is blank), **command** rows (EssentialsX homes 2 and 3 and `/hat` through LuckPerms; name colours and chat titles ship disabled), and a numbered **Arcade Trophy**. Limits are per local day/week/lifetime in `prize_purchases`. Every physical prize is tagged **`hcm:token_prize`** and refused by every money path — Pallet, vending, auction, market, Courier cargo — with "Arcade prizes can't be sold." **Games:** the Starter Crate is replaced by the 15-token **Arcade Crate** (boosts, radar, fireworks, trails, hats, filament, a token refund, and every Mini as the 2% jackpot); a crate reward can be `prize:` (one row, or one of a list) or `trail:`. The **Scratch Ticket** costs 10 tokens and pays tokens, decided at purchase, with the near-miss as the most common result and a **persisted jackpot pot** (seed 50, +1 a ticket, cap 1000, a server-wide shout when won) — about 78% back over time; `/hcm arcade odds` prints the RTP (steady-state pot = seed + per_ticket / p) and each crate's value at counter prices. **Pity** is 150 tokens, once a local week. **Card trade-in** swaps Cards for 3/5/12/25/50 tokens by rarity (a nine-slot tray; anything left in it goes back on close). **Quests v2:** each player draws 3 dailies and 2 weeklies from pools, no two of one type (`quest_assignments`), with a 10-token **Quest Reroll**; new types PLANT_CROPS, HARVEST_CROPS (grown only), COOK_FOOD, SMELT_ORE, MINE_BLOCKS (natural blocks only — shares the `player_placed` ledger), VISIT_BIOMES, COMPLETE_DELIVERY and FIND_WILD_MINI. **Achievements v2:** a 26-row list in groups — EVENT, STAT, COUNTER, COLLECTION, GRADE, FINISH, STREAK, BALANCE — checked where they happen, on join and on the five-minute tick, with a title and a sound on unlock; the six old ids are kept, so no unlock is lost. Login streak `[1,1,2,2,3,3,5]` → `[2,2,3,3,4,4,5]`. Schema v30 (`prize_purchases`, `cosmetics_owned`, `quest_assignments`, `quest_biomes`, `player_biomes`, `player_counters`, `arcade_state`). **config_revision 14** rewrites each of those sections only where it still holds what we shipped; an admin's own crate, prize row, pity price, streak table, quest list or achievement settings is kept, with a warning naming it. The money Scratch Ticket cannot be kept by anyone — its old table is written to the log.
>
> **v17.9 changelog (the wild hunt mints on pickup):** A wild spawn used to **mint the moment it appeared** and retire the copy when it escaped — so every Mini that got away burned a mint number and a cap slot forever, and the Museum's "Minted" counted Minis nobody ever held. A spawn is now a **blueprint** (`wild_spawns`, schema v29: which Mini, grade, finish, who it appeared near, where they stood, when it goes, hints sent); catching it mints the copy right then, and an escape — the timer, an explosion, a piston, water — removes the head, adds to `mini_counts.escaped` and mints nothing. While it stands it **reserves** its cap slot: `MiniService.mintedOut` counts live spawns, so every issue and mint path (including `CardService.canIssue`) sees them. Numbers now come from one transaction in `MiniDao.mint`: the lowest number the new **`/hcm mini repair-escaped [confirm]`** handed back (`mini_free_numbers`), else one past the highest ever issued; `UNIQUE(mini_id, mint_number)` goes on when the data allows. Every new copy records its `origin`. The spot finder uses the Courier's `Ground` (real ground, not treetops) and refuses anywhere within 16 blocks of a player. A hunt is a **shared event**: one at a time, announced with the name of the player it appeared near, then **escalating hints** (biome at 30%, an 8-way direction from where that player stood at 60%, a rarity-coloured **light beam** at 85%) — never coordinates. Wild spawns get their own effect profile for every rarity (seen from 32 blocks, a light). config_revision 13 retunes the September numbers only where still shipped: 48–96 blocks, 5 minutes, `max_live` 1, a 90-minute cooldown. New admin commands: `/hcm hunt spawn [rarity] [player] | status | clear`. Museum/info card: "Got away: N" when N > 0.
>
> **v17.8 changelog (local midnight, and a ledger):** Every token "day" was a **UTC** day, so the login streak, the dailies and the weeklies rolled over at **7 PM in Minnesota** (6 PM in winter) — mid-evening, when the family plays. A new `clock.time_zone` (**America/Chicago** shipped) now decides where a day ends for the streak, quests and every new Arcade per-day/per-week limit; the market's and the Courier's daily money limits deliberately stay on UTC (a separate decision). The streak is also checked on the five-minute tick, so staying online across midnight pays the new day without a relog. **Transition:** a stored streak day written as a UTC day that is ahead of local today counts as already claimed today and is pulled back, so nobody is paid twice and no streak breaks; **quest period keys shift once** on the day this ships (progress made after 7 PM that evening is keyed to the old UTC day). Token code moved out of the Arcade into a `TokenService`, and every balance change is now **one guarded transaction** (`tokens = tokens + ? WHERE tokens + ? >= 0`) that also writes a `token_ledger` row (schema v28) — `/hcm tokens audit [days] [player]` and `/hcm tokens history <player> [n]` read it. A pull that pays nothing now says **"So close!"** with a soft note instead of "You won!" and the fanfare. Card packs, crates and the pity exchange share **one cap check** (`CardService.canIssue`: Cards left AND the Mini not minted out), so a pack can no longer hand out a dead Card. config_revision 12 drops the four shipped quests that paid for Mini output (print, packs, selling) — an admin-retuned row is kept, with a warning naming it.
>
> **v17.7 changelog (the crate had Steve's face):** The package handed out at the job board was a **default player head** — Steve, held in hand for the entire delivery, which is not what you want to give a four-year-old to carry. Two separate faults, and either one alone was enough. **The loader never read the textures.** config.yml has shipped three crate skins under `skins.courier_package` since v17.6, and `readSkins` reads every other named slot — `pallet_used`, `vending_upper`, `mailbox.*` — and not that one, so the lookup answered `""` and a blank value builds a plain head. **And the upgrade could never have fixed it anyway.** Those three keys shipped *blank* in v17.3 and were filled in later; the blind backfill only adds keys that are **missing**, so an existing config.yml keeps its `""` forever. `config_revision` **11** fills a blank crate skin from the defaults and leaves a texture an admin chose alone. A band with no texture now borrows another band's rather than falling back to a blank head: a crate of the wrong size is a far better answer than a stranger's face. No schema change.
>
> **v17.6 changelog (Crate buys, Crate ships):** The instant Market **sells only** now, and the shipping system is no longer dead content. Buying there was at the live price with **no shipping cost and no wait**, so no player would ever choose a tier — which left the tiers, the Locker and in-transit orders as things that existed and were never used. The split the two systems were always for: *you* deliver when you sell, so selling is instant and free; goods coming *to* you get shipped, and that costs something. Express already covers "I want it now" at ~5 minutes for +20%. **The real hole was not the button.** `hcm.market.order` defaulted to `true` and granted "buy from and sell to the dynamic market", and `/hcm` has no gate of its own — so **any player could run `/hcm market buy`** and skip shipping entirely; closing the GUI button alone would have moved the hole, not shut it. Buying by command is now `hcm.market.buy`, op-only, with a test pinning that default. Renamed to **Sell to Crate** — an action rather than a branded destination, since naming it after a place is what invited "why can't I buy there". Selling already added to Crate's stock and still does; that is the dynamic half and was never broken. Marketplace, Pallets, Vending and Auction are untouched, and **no price moved**. `config_revision` **10** — a targeted migration rewrites the shipped menu title, which the blind backfill cannot do.
>
> **v17.5 changelog (whose field, and whose house):** Two holes found reviewing the delivery site. **An unclaimed build was invisible.** The waypoint refuses land that belongs to somebody, but Towny only knows about *claimed* land — and the thing a player is most likely to have in the wild is the base they never got round to claiming. The footprint is now read for **evidence rather than ownership**: planks, a crafting table, a bed, a pane of glass. None fall out of terrain generation, so one means the field is already someone's. It runs at **waypoint** time, like terrain, because a base does not appear during an hour-long delivery and a reroll beats a four-thousand-block walk to a house that refuses to appear; at placement it rides the container scan's existing loop for free. It also rejects near villages, shipwrecks and ruins, which is deliberate. **And the restore asked about the wrong player.** The distance rule measured against *anyone online*, so a neighbour minding their own business two hundred blocks away held somebody else's house up until the backstop. It now asks about the player whose delivery it is and nobody else (`courier_sites.player`, schema **v27**) — which also makes a disconnect restore promptly, since an offline player is not within any distance. The footprint rule stays **everyone's**: it exists so a restore does not suffocate whoever is standing there, and that does not depend on whose delivery it was. `config_revision` stays 9.
>
> **v17.4 changelog (what the crate costs):** The crate now has a **price for losing it**, which is the only thing that makes carrying one a responsibility rather than a decoration. The fee is what the run would have paid **on foot** × `package.loss_multiplier` (1.0 shipped), so a lost crate cancels the run out: you walked it for nothing. It is charged when a run ends **without the crate coming back** — never merely for failing the run, since a player who returns with it in their bag has already lost the payout and that is the whole of it. What cannot be paid becomes **debt**, which closes the courier board and *nothing else*: the market, the shops, the Marketplace and every other way of earning stay open, because a debt that locks a child out of the economy with no way to work it off is the opposite of a consequence. The same figure **buys a replacement at any PC**, which is what keeps the fee fair — without it a creeper four thousand blocks out ends the run and charges for the privilege. Settlement is **deferred across a logout** (`courier_jobs.package_settled`), because otherwise logging off would be a free way to lose a crate — the cheapest and most discoverable dodge there is. Schema **v26** (`courier_debt`, plus the settled flag); `config_revision` stays 9.
>
> **v17.3 changelog (the package):** A courier run now hands you a **crate** to carry, and the recipient wants it **in your hand**. It is worth nothing and is built to stay that way: unplaceable, unwearable, unstackable, unlistable, never cargo for a second job, and destroyed the moment the delivery ends by any route — with an orphan sweep as the backstop for anything that forgets. **Unwearable is done in the item** via its `equippable` component rather than by catching equip events, because a player head is a helmet and there are a dozen ways to put a hat on. **The rejection audit turned out small**: the market catalog does not buy heads, Auction and Vending demand a Mini reference, and the Printer consumes a Card, so all of those refuse a crate structurally — the Marketplace Pallet is the one genuinely open surface and is where the explicit guard lives. No schema change; `config_revision` stays 9.
>
> **v17.2 changelog (what the first real delivery found):** The house appears, the villager works, the payout is right and the woods come back — the five things wrong were what you notice standing there. **Jigsaw blocks survived placement**: village templates are worldgen pieces whose `JIGSAW` markers the assembly process normally consumes, and `Structure.place` does not, so one stood in a wall beside a front door. **The recipient spawned indoors and facing a wall** — the outward-side test was fed the structure's minimum corner instead of its centre, a regression from v17.1's re-centred capture box, and the spawn set no yaw. They now face outward, turn to watch a nearby player, and speak on hand-over. **Trees were cut at a fixed height**, leaving canopy in the sky; clearing is a flood fill of whole trees now and the captured region grows to bound it, because orphaned leaves *decay* and decay is a change no restore undoes. **The field came back while the player was still standing in it** — `linger_seconds` worked exactly as written, and a timer is the wrong shape: the restore now waits for the player to leave and never fires while somebody is inside the footprint. No schema change; `config_revision` stays 9.
>
> **v17.1 changelog (the Courier finds the ground):** A delivery into any forest silently built nothing. Ground level was read from `MOTION_BLOCKING`, which counts leaves — so a flat wood measured as a ten-block cliff against `max_slope: 3` and placement refused, with **no log line at any level** to say so. The refusal is now found by walking down through canopy and undergrowth to real terrain, the growth is cleared inside the captured region rather than the field being rejected for having trees on it, and **every refusal names itself** (`building.debug`). Five more defects found in the same pass and fixed here: the waypoint itself was recorded at **canopy height** (leaves report `isSolid()`), which aimed the placed-Mini and placed-entity safety scans at empty sky in exactly the forests where they mattered; `placeFor` had **no in-flight guard**, so a slow chunk load let a second placement snapshot a field that already had a house on it and overwrite the real undo record; the refusal was **retried every second** for the rest of the run, 25 chunk loads and an NBT reparse each time; the 60-second expiry sweep **beat `linger_seconds`** and pulled the house out from under the player; and the capture box was centred on the waypoint rather than the placement origin, so a template 11 wide **overflowed the snapshot** — and a block outside it is never restored. Schema unchanged; `config_revision` stays 9.
>
> **v17 changelog (the Courier's destination):** A delivery now **arrives somewhere**. A vanilla village house is placed at the waypoint as the player approaches, with a **villager** outside it to hand the crate to, and the field is **restored exactly as it was** when the run ends — from a palette-and-indices snapshot taken before the first block changed and held in SQLite, so the module needs **no WorldEdit dependency** and cleans up after a crash on its own. Also fixes two Phase 1 faults found while building it: **waypoint generation ran on the main thread**, loading and generating chunks up to four thousand blocks out (up to `max_rerolls` times per click) despite a code comment claiming otherwise — it is asynchronous now, and `accept` returns a future; the waypoint claim check used `canBuild`, which **short-circuits to "yes" for ops**, so an admin could be sent to deliver into somebody else's town; and the region scan only loaded the waypoint's own chunk, so reading the rest of the box would have loaded its neighbours one at a time on the main thread — the very thing the async placement exists to avoid. Schema v25 adds `courier_sites`. `config_revision` stays 9 — the new `courier.building` keys arrive through the leaf backfill.
>
> **v16 changelog (the Courier):** A new **Deliveries Site** on the PC (§3.10). Take a crate to a rolled waypoint and get paid a **travel fee** for the distance, scaled by **how you actually travelled** — read from the same vanilla movement statistics the quest verbs use, snapshotted at accept and **blended by the fraction of centimetres in each group**, so walking the route four times pays exactly what walking it once pays. Creative flight pays **nothing**; an arrival with less than 70% of the distance tracked pays **20%**, which is the anti-teleport floor. Three distance bands with per-UTC-day caps (2 / 2 / 1). A **trade run** carries market cargo to the drop-off and sells it there **at the live rate** — only the fee half is new money (§3.1). **No market price, stock level, daily limit, shipping tier or catalog entry was touched**; the courier adds a money *faucet* sized under mining and nothing else. Schema v24 adds `courier_jobs`; `courier:` is new config with no migration step — an absent key falls back to the shipped defaults.
>
> **v16 changelog (the PC is a browser):** The PC's root screen is now the **Site launcher** §2.2 always specified, instead of opening the Store directly. The Store had become the hub by accident and its nav row was full at nine slots, so the Courier module would have had to displace something to land. Card Packs moves to the launcher; the Store's slot 49 becomes an ordinary Back now that it is one Site rather than the root. `menus.pc_title` names the launcher.\n>\n> **v16 changelog (quest verbs):** Quests are **mostly verbs** now — catch fish, travel on foot, defeat hostiles, breed animals, trade with villagers — read from **vanilla statistics** by a single 30s poll rather than a listener each, banking the difference between polls so nothing earned before the period counts and time in an economy-disabled world is skipped. **Selling drops from 40% of quest reward value to 13%** and is no longer the largest line in either period. **Weekly rollover moves to Monday** (`arcade.quests.week_starts`) from the Thursday that `epochDay / 7` produced by accident. `config_revision` 9; schema v23 adds the watermark column. A quest pool an admin has edited is left alone.\n>\n> **v16 changelog (token sinks):** Added the **Prize Counter** (`arcade.prizes`) — fixed-price, known-outcome token purchases in the Arcade hub, so tokens have a floor value and a non-gambling way to spend. The **Starter Crate moves from 1 token to 5**: against roughly 16 tokens earned in an active evening a one-token pull is not a sink, and a balance that can only grow is what that produced. `config_revision` 8 migrates both, seeding the counter for existing servers and lifting the crate price only where it is still the shipped 1.\n>\n> **v16 changelog (spec reconciliation):** **§3.9 now says what the code does.** It was written in the Phase 8 era and never revised when v15 landed the two-currency rule, so for two releases it claimed crates pay cash and items (rejected at config load since v15), that a `mini` crate reward mints a finished Mini (it issues a **Card**), and that pity costs about 3 tokens (it is `arcade.pity.tokens`, 25). A spec section that contradicts the code does not just mislead a reader — briefs written from it inherit the errors, which is exactly what happened. **Where §3.9 and §11 #9 disagreed, §11 #9 was right**, and §3.9 now matches it. Also reconciled: §3.2's GUI-first rule ("`/hcm …` commands are admin-only") against §10, which deliberately designs `/hcm auction` and `/hcm museum [id]` as **player** routes — the rule is about *market* commands, and the player-facing openers are gated by their own nodes (`hcm.auction.list`, `hcm.museum.use`, `hcm.arcade.use`, `hcm.quests.use`, all default-true) rather than removed.
>
> **v15 changelog (economy safety):** **Per-world economy sandbox** (`worlds.economy_enabled`, main world by default; refusals logged) closes §11 #1. **SQLite backups** (§11 #6): a copy before every schema migration, a daily online-API backup with pruning, `/hcm backup now`. **Economy rebalance:** per-item daily caps (~2% sell / ~4% buy), $5k/day limits ($12.5k VIP), 8% commission, pity 25, starter pack 250 (no Legendary) + a 750 premium pack, public printers charge $150 and cover filament while private ones consume filament (fee 0) and unlock Shiny, a string+dye+slime filament recipe per colour. **Two-currency rule (§11 #9): tokens never become money** — crates pay only cards, packs, filament or tokens, enforced at config load; crate Mini prizes are Cards again (wild drops / natural spawns still mint the graded Mini). Presentation: Display Cases keep a pedestal skin (plain / royal by recipe) and float the Mini above as an ItemDisplay; any placed Mini head gets its rarity effects, a look-at hologram and a guaranteed drop-back; the Vending Machine's upper half is a flush ItemDisplay with a nearby-only glow, a listing hologram and an action-bar peek. Fixed the live-API Card lore/PDC loss (head profile committed before decoration) with a startup self-test.
>
> **v14 changelog (Mini presentation):** **Rarity owns the look, grade owns the stars** — a Mini's name colour and glint always come from `rarity_styles`; the grade is a star suffix (☆ / ★★ / ★★★) and a lore line. The five-grade ladder is replaced by three config-driven grades (**Standard 1.0× / Graded 2.5× / Mint 6.0×**, `minis.grades`); stored copies migrate on sight (Gray/Green → Standard, Blue/Purple → Graded, Gold → Mint). **The Museum is browse-only** (appraisal, circulation, who owns it; no money mint — `hcm.mini.buy` removed). **World effects** for placed Minis (`minis.effects`: particles, name hologram, hidden light, rotating/full-bright ItemDisplay, placement sound/burst, Mint chime, Shiny ring), rebuilt from the datastore on load. **Wild drops** gain **tag pools** (`tags:` on catalog entries, `tag:` on sources and crate rewards, rarity-weighted), roll **grade + Shiny** with the Printer's odds, a **NATURAL_SPAWN** trigger that places a claimable Mini head 24–48 blocks from a player (despawns after a lifetime, retiring the copy), and server-wide **announcements** (found broadcast with a hover card, spawn hint, slipped-away). Wild drops and crates now mint finished Minis directly; Cards → Printer, drops, natural spawns and crates are the only mint paths.
>
> **v13 changelog (skins & recipes):** Every catalog item and custom block now ships with a **head texture** (`skins.*`, Mini `texture` fields). The **Pallet** has two visual states (`pallet_empty` / `pallet_used`, swapped live as listings come and go). The **Vending Machine is two blocks tall** (`vending_lower` is the real block; `vending_upper` is auto-placed and acts as one block for use/break/protection; existing machines are migrated on load). The **Mailbox comes in eight colour variants** (`skins.mailbox.<variant>`, PDC-tagged, one recipe each; old placements read as wood). **Every craftable block has a data-driven, reloadable vanilla SHAPED recipe** under `recipes:` (tag ingredients like `#planks` supported; unlocked in the recipe book on join). The **Arcade is hub-only**: the four machine blocks and `/hcm give auction` are no longer handed out (placed ones keep working).
>
> **v12 changelog:** Shipped **Phase 3.1** (on-screen rebrand to Crate; config-driven `store.name`/`store.display_url`) and **Phase 4 core** (Minis catalog, minting, Museum & Shop, per-copy UUID anti-dupe, caps + circulation; migration v7). Recorded the decided **web-login model** (username+password, hashed, Minecraft-UUID-anchored, no email), the **config-everything** principle, and the **`[www.Crate.com](https://www.Crate.com)`** in-game display choice. Phase-4 follow-ups (Vending Machine, Auction House, Wild Drops, armor-stand spawning, web-import) and the GUI x600 quantity fix remain open.
---
## 1. Vision & Core Economic Loop
The plugin creates a deliberate tension between ways to get goods — mirroring real life:
| Channel | Price | Speed | Cost to access |
|---|---|---|---|
| **Physical player shops** (QuickShop) | Player-set (owners choose) | **Instant** | You must *travel* to the shop |
| **The Crate market** (house commodities, this plugin) | **Dynamic** (supply/demand) | **Delayed** (1/2/3-day shipping) | You must *build a PC* + pay shipping |
| **The Crate Marketplace** (player listings via Pallets) | **Player-set** (fixed) | **Delayed** (shipping) | Build a PC to buy; a **Pallet** + **fee** to sell |
**The point:** need it *now*? Go to a shop and travel. Can wait? Order online — you pay shipping and wait for delivery. Both channels stay viable.
**Design north star:** "just like life." Scarcity, shipping, convenience-vs-cost tradeoffs, seller fees, and collectible status all behave the way they do in the real world.
---
## 2. Architecture Overview
One plugin, five modules:
1. **Dynamic Market Engine** — our own finite-stock supply/demand pricing (replaces DynamicShopGUI entirely).
2. **Crate — Ordering, Shipping & Marketplace** — the online store, accessed only through a PC: the house commodity store **plus** the player-to-player Marketplace (Pallets, departments, fees), with real-time shipping tiers.
3. **The PC** — a rare-crafted item that is the gate to online commerce. **Architecturally it is a "browser": Crate is the first Site; more Sites come later (§2.2).**
4. **Minis Collectible System** — a rarity/mint/circulation system with a Museum & Shop, Vending Machines, and an Auction House.
5. **Displays & Dashboard** — a live web dashboard (BlueMap-style) *and* in-game economy displays (TVs/tickers/boards).
**Repository:** `https://github.com/Dierks27/HomeCraftMgmt`
**Environment:** Paper 26.2, Java 25.
**Depends on:** Vault (economy → EssentialsX).
**Coexists with:** QuickShop-Hikari (player shops — untouched, keep their own prices).
**Replaces:** DynamicShopGUI (we reimplement pricing so we own the code). Reference its open-source code for algorithm ideas only; write our own clean implementation.
### 2.2 The PC is a Browser; features are "Sites"
The PC is not just "the store" — it is the server's **computer/browser**, and each feature is a **Site** you open on it. This is the spine the whole project hangs on: every future feature has an obvious home — it's just a new Site.
- **`[www.Crate.craft](https://www.Crate.craft)`** — the store + Marketplace (Phase 3 / 5). *Live-ish now.*
- **Towny Site** *(future)* — browse every plot/town listed for sale in the world: location, price, town, jump-to-map. Towny already exposes this data.
- **Courier (§3.10)** — the delivery job board. *Live in v16.*
- **Economy Dashboard Site** *(future, §3.7)* — the live stock-market view, in-game.
- Later: mail, a town directory, etc.
**Implementation note (built in v16):** the PC's root menu **is** a **Site launcher** — a grid of Sites, and nothing else. It opened the Store directly until v16, and the Store's nine-slot navigation row had become the de-facto hub; with every slot taken, the next Site had nowhere to go. Adding a Site is now adding a row to `SiteLauncherMenu.sites()`, with nothing rearranged to make room. Each Site wears the same icon wherever a player meets it, and the Store's slot 49 went back to being an ordinary way out once it stopped being the root screen. The launcher's title is config-driven (`menus.pc_title`). Each Site is a loosely-coupled GUI module. The store header is **config-driven** (`store.name`, `store.display_url`) and changeable live with `/hcm reload`. **In-game it currently displays `[www.Crate.com](https://www.Crate.com)`** (Dierks's call) — it's **non-clickable display text**, not a real link. Note: `.com` is a live TLD (so `crate.com` could be a real site a curious kid types in), whereas the doc's default **`.craft`** can't resolve to anything — the strict kid-safe option. Since it's display-only, either works; it's a preference, not a bug.
---
## 2.1 Target Server Environment
> **The implementer cannot see the live server.** This section IS the environment — build against it.
**Server:** Paper **26.2**, Java **25**, ~8 GB RAM.
**Full installed plugin stack** (most are irrelevant — listed so you know the surroundings):
Towny 0.103.1.0 · Vault · EssentialsX 2.22.1 · LuckPerms · CoreProtect *(currently not 26.2-compatible — see §11)* · PlaceholderAPI · WorldEdit 7.4.5 · WorldGuard 7.0.18 · QuickShop-Hikari 6.3.0.0 (+ addons: FindItem, Dialog, list, limited, displaycontrol, discount, bluemap) · ProtocolLib · DynamicShopGUI *(being replaced by this plugin)* · Multiverse-Core 5.7.3 (+ Inventories, Portals, NetherPortals, SignPortals) · BlueMap 5.23 (+ BlueMap-Towny, Marker Manager) · PlasmoVoice · RoadSpeedMounts · GravesX · TAB · Sleeper.
**Integration-relevant (what this plugin actually touches):**
- **Vault** (backed by EssentialsX) — **all** money flows through Vault. Soft-depend.
- **Towny 0.103.1.0** — the Mall is an admin Towny town with rentable plots; respect Towny build permissions for placing/using custom blocks; the future Towny Site reads its plot/town data.
- **WorldGuard 7.0.18 / WorldEdit 7.4.5** — respect WorldGuard region build permissions for custom-block placement/use.
- **LuckPerms** — clean permission nodes for every command/feature.
- **Multiverse** — **per-world economy sandboxing is critical (see §11): the market/Marketplace must be disabled in creative/exempt worlds** to prevent spawned-item money exploits.
- **QuickShop-Hikari 6.3** — **DO NOT touch or integrate.** Player shops are intentionally separate; Minis are never sold through QuickShop.
- **PlaceholderAPI** — expose placeholders (prices, stock, mint counts, circulation, order status) so TAB/holograms/scoreboards can display live economy data — this also powers the in-game tickers (§3.8).
- **GravesX** — Minis can end up in graves on death; ensure recovery without duplication (§11).
Declare Vault / Towny / WorldGuard / LuckPerms / PlaceholderAPI as **soft-dependencies** and **degrade gracefully** if any is missing.
---
## 3. Module Specifications
### 3.1 The Market — a Finite, Conserved Commodities Exchange
Not a shop with an infinite vending machine — a **real commodities market** with a **finite, conserved supply.** This is the economic heart of the server and the house-run store inside Crate.
**Finite, conserved stock (closed-loop — the core rule):**
- The market holds a **real inventory (stock count) per commodity.** Nothing is vaporized into an infinite sink — everything is **logged.**
- **Selling to the market** removes the item from the player and **adds it to the market's stock** (+N); the player is paid.
- **Buying from the market** removes it from stock (−N) and gives it to the player; the player pays.
- **At ZERO stock, the item is OUT OF STOCK — you cannot buy it at all.** *(Confirmed live.)*
- **Stock cap:** each commodity has a **max stock ("full")**; when the market is full it **stops accepting sells** of that item ("flooded → stops taking it"). *(Confirmed live: cobblestone capped at 20,000.)*
- Material enters only when players **mine and sell it in**; it leaves only when players **buy and hold/use it.** Supply is real and conserved.
**Scarcity pricing (price = a function of real stock):**
- Low stock → price climbs toward the **ceiling**; high stock → falls toward the **floor**. Zero stock ⇒ ceiling; full stock ⇒ floor.
- **[PHASE 2.5.1 — proportional elasticity, IMPORTANT]** Price movement must be **relative to each item's own floor→ceiling range**, not an absolute per-unit step. A trade that changes stock by X% must move price by a **comparable %** for *any* item — cobblestone or diamond alike. *(Bug found in live test: buying 600 cobblestone — ~3% of stock — leapt the price from $0.10 to $3.38 because the step was absolute. Fixed by normalizing elasticity to the item's price range.)*
- **[PHASE 2.5.1 — integrated bulk pricing]** For an N-unit order, the price moves **across the order** (integrated over quantity) so large orders cost progressively more (buying) / earn progressively less (selling), rather than charging the start price for the whole order and jumping the displayed price afterward. Total charged must be consistent with the resulting displayed price. *(Confirmed working on the sell side: 600 cobblestone → integrated avg, price glided down.)*
- **inertia** still smooths glide; **elasticity** now scales to range.
**Starting stock is per-item — this is what makes it feel right:**
- Each commodity has a configurable **initial stock**, which sets its starting price.
- **Abundant staples (cobblestone, dirt):** seed with generous stock → available and cheap from day one.
- **Valuable ores (gold, diamond):** start at **ZERO stock → ceiling price, unbuyable** until players mine and sell them in. Scarcity is *earned*.
**Market rules & safeguards:**
- **Cash is effectively infinite; only *item* stock is finite.** The market is the money faucet *and* sink; only inventory can run dry.
- **Buy/sell spread (margin):** buy price sits slightly above sell price (configurable). *(Confirmed live: mid $0.30 → buy $0.31.)*
- **Daily per-player sell limit (anti-whale):** cap money and/or units a player can sell/day; resets daily (UTC); per-player persistent; optional per-rank ceiling (most generous wins); shared `hcm.market.limit.bypass`; GUI shows "resets in Xh."
- **Daily per-player buy limit (anti-drain / anti-cornering) ✅:** the sell-side mirror — caps money spent and/or units bought per UTC day (own `buy_limits` config + separate SQLite tally), so no one drains a commodity the moment it's stocked. Same rank-escalation + shared bypass; also applies to Crate/Amazon store orders.
- **Per-item daily caps ✅:** each catalog entry may set `max_daily_sell` / `max_daily_buy` (per-player, per-item, per-day unit caps) that stack **on top of** the global limits — the tighter wins — so rare items are tightly controlled while commons stay high-volume. Rule of thumb: sell ≈ 2% / buy ≈ 4% of `full_stock`.
- **Prices are hard-clamped to `floor`/`ceiling` ✅:** every stored, displayed, and quoted price is clamped to the item's band. Inertia may smooth price *within* the band, **never outside it**. On `/hcm reload` any price cached under an older config (different floor/ceiling) is snapped back into range and written through — otherwise the glide would interpolate from an illegal price indefinitely.
- **Stock must never reach `full_stock` ✅:** `full_stock` is the *denominator of the price curve*, not a target to hit — the market always keeps room for players to sell into. `initial_stock >= full_stock` is clamped to 85% with a warning; `setstock` caps at `full_stock - 1`. A player *may* still sell stock all the way up, at which point the item sits at floor price and further sells earn almost nothing — the market's own "we're saturated" signal.
- **Admin stock management ✅:** `/hcm reload` preserves existing stock by design, so a new economy design needs `/hcm market resetstock <item|all>` — it reseeds stock from config **and** snaps price onto the curve (a stock-only reset would leave inertia holding the old price). `/hcm market setstock <item> <amount>` fine-tunes one item. Both write SQLite + in-memory state immediately.
**Curated & lean on purpose.** The house commodity list is a **deliberately small** set of core resources (ores, staples) — NOT every item in the game. Everything else is sold player-to-player via the **Crate Marketplace (§3.2b)**, which avoids turning farmable items (bread, etc.) into house-money faucets. Rule of thumb: **raw/limited stuff → house market; farmable/crafted/long-tail stuff → the Marketplace.**
**Scope:** these dynamic prices are the **Crate house-market side ONLY.** QuickShop sets its own prices and is untouched. This market **replaces DynamicShopGUI entirely.**

#### The live market (0.33)
**The rule it is built inside.** Everything above still holds word for word. The live market **never writes a stock count and never writes the balanced price** (`state.currentPrice`, the curve and its glide, moved only by player trades and `setstock`/`resetstock`). Nothing it does creates or destroys a single item, and a test runs 90 simulated days with no trades and checks that stock is unchanged. What it adds is one number per item, the **mood** `M`, applied where every price is read (`MarketService.price`, the one chokepoint):
```
raw   = 1 + d + e + q          d = drift, e = HOT/DEAL/UP/DOWN events, q = clamp(season + real, ±c_pred)
M     = (sim running AND item sim-enabled) ? clamp(raw, Lo, Hi) : 1.0
Lo    = max(0.75, 1 − max_down_percent/100)        Hi = min(1.25, 1 + max_up_percent/100)
price = clamp(balanced × (stock > 0 ? M : 1), floor, ceiling)      // what every screen, quote and trade uses
ask   = clamp(price × (1 + spread/2), floor, ceiling)   bid = clamp(price × (1 − spread/2), floor, ceiling)
usual = balanced                                        pct = (price / usual − 1) × 100   // every % a player sees
```
**Hard limits, in code** (`market/sim/SimLimits`). A config value outside them is clamped with a WARN naming the key, so config can make the market calmer, never wilder:

| What | Limit | Shipped (the owner's **Tight** choice) |
|---|---|---|
| Whole mood `M` | **0.75–1.25** (also re-clamped in `OrderMath`; a NaN or infinite mood reads as exactly 1.0) | `max_up_percent` / `max_down_percent` 25 |
| Drift | ±8% | typically 1% a day on staples, 2% a day on items where √(floor × ceiling) ≥ $10 |
| HOT / DEAL | 15% | 8–15% |
| NEWS FLASH | 25% | 15–25% |
| Seasons + real world together (`c_pred`) | min(4.5%, 45% of the spread) | 4.5% at the shipped 10% spread |
| Per-item `volatility` | 1.5 | 1.0 |

The multiplier is linear and every part is zero-mean and symmetric, so `E[M] = 1` needs no correction. The 90-day soak test holds the mean over all item-ticks to 0.985–1.015. At zero stock `M` is ignored and the item sits at exactly its ceiling, as above. **`enabled: false` means `M ≡ 1.0`.** `b × 1.0 == b` exactly, and the stored price is always inside the band, so every price, quote, order total, history point and feed is bit-for-bit 0.32. `OrderMathTest` compares against a verbatim copy of the 0.32 order code across the shipped items, four quantities and 1,000 random start states.

**The layers.** **Drift** is an exact Ornstein–Uhlenbeck step each tick (5 minutes) with a 66-hour half-life and σ = `volatility` × 1.5% (calm) or 3.0% (lively). Its daily move is `0.667σ`, which is 1.0% or 2.0% a day. The per-tick standard deviation stays under 0.13% against a 10% spread, so there is nothing to scalp. **HOT / DEAL** (±`A`, A ∈ [8%, 15%]) rise silently over 4 h, hold 30–54 h and fade over 10 h. **UP / DOWN** (±`J`, J ∈ [15%, 25%]) jump instantly and decay: `k(τ) = (2^(−τ/6h) − 2^(−30h/6h)) / (1 − 2^(−30h/6h))`, 48% left at 6 h, 23% at 12 h, gone at 30 h. **WANTED** moves nothing: it is news about a sold-out item. **Seasons** are local `MM-DD` windows that fade in and out over 2 days; overlaps add up, and a `SEASON` row is written once per season per year. **REAL** is an optional impulse from a real commodity's daily close, gain 2 and capped at 4% (the code allows 4.5% at most), with a 24-hour half-life and gone at 96 h. Before an event is created it is checked against the item's **headroom**, `min(Hi, ceiling/balanced) − M0` going up and `M0 − max(Lo, floor/balanced)` going down. It is created only if 90% of that headroom covers its minimum, and its strength is capped at that 90%, so every announced percentage really happens. A cobblestone HOT cannot promise $5: at today's stock cobblestone tops out at $0.22.

**Scheduling** (`EventPlanner`). At most one start per tick, in the order news, then HOT, then DEAL. Slots are `clamp(1 + floor(items / 40), 1, 3)` of each, and HOT and DEAL together are capped at `max(2, floor(items / 3))`. An item is picked by `sim_weight` × 2 when anyone traded it in the last 14 days (`popular_weight`), and only from items that are sim-enabled, have `floor < ceiling` and no running HOT/DEAL/UP/DOWN. "Popular" raises an item's chance of an up and of a down equally, so trading an item to attract an event gains nothing on average. The planner never looks at trades. **HOT/DEAL:** the item needs stock, 7 days since its last HOT/DEAL ended, and 24 h since its last news. A DEAL also needs stock ≥ max(16, 3% of `full_stock`). The next start comes `(the event's length + 48–120 h) / slots` later, which works out to about one of each a week. A DEAL that sells out stops with a 1 h fade and a log-only line. **News:** it runs only inside `news.hours` (07:00–21:00 local), at most 2 a local day, at least 10 h apart plus a random wait averaging 14 h and capped at 48 h (about 5 a week), and at most one per item per 72 h. The direction leans back toward normal: an item already up is likelier to go down. DOWN needs 5% of `full_stock` in stock, and a flash the limits would shrink below 10% is skipped. A due flash **waits until somebody has been online longer than its join delay**, 2–8 minutes, fixed per scheduled flash so logging in and out cannot re-roll it. It never waits past the day's news hours. With the shipped 07:00–21:00 window, a flash still held at 21:00 moves to the next morning. Only with news hours longer than `max_hold_hours` (14) does it fire silently after 14 h, and then players read it in the catch-up, on the website and in `/hcm market news`. **WANTED** takes `wanted_share` (15%) of flashes, for an item at stock 0, once a week per item. Gold and diamond are sold out on day one. They sit at exactly their ceiling, show » WANTED on the Sell screen and get the occasional "Crate is looking for …" flash. They come into play only when a player sells some in.

**Announcing** (`AnnounceGate`, `MarketNewsService`). An UP/DOWN takes effect **in the same tick it is broadcast**, so afterwards the price only decays back. A HOT/DEAL is **announced at full strength**, so afterwards the price is flat and then fading. That closes the front-running hole (buying ahead of an announcement you can see coming): someone who spots a ramp on the chart gets at most about 2%, has to place a shipped order, and is bound by the event limits below. A broadcast needs news hours, 20 minutes since the last market broadcast, fewer than 6 that local day, and somebody online past the join delay. There is **at most one per tick**, and the priority is intro (once ever) → this tick's flash or WANTED → HOT/DEAL start → season (window 7 days) → real-world (12 h) → "Last call!" (`[end of hold − 3 h, end of hold − 30 min]`) → ending line (within 30 min). Last call and the ending line follow only a HOT/DEAL whose start was announced, and the ending only follows a natural end or `sim stop`. **Nothing is broadcast late:** a replayed tick broadcasts nothing, and anything past its window stays in the log, on the website and in the catch-up. Admin-forced events skip the gate, and `quiet` skips everything but the record. Muted players (`/hcm market news off`) get no chat, title, action bar, sound or catch-up.

**Trades** (`OrderMath`, pure). `M` is **frozen for the whole order**, and the balanced price is integrated per unit exactly as before: each unit is priced at `clamp(balanced × m, floor, ceiling)` ± the spread, and the balanced price then glides toward the curve at the new stock. The first unit sold into an empty item is priced at `m = 1`, so at the ceiling. `commit` writes the balanced end price, so the balanced price stays trade-driven. `TradeResult.priceAfter` is the displayed price, which keeps "total consistent with the resulting displayed price" above. A quote equals the execution within the same tick. The price-history snapshot records the displayed price, which equals the old `currentPrice` whenever `M = 1`. `setstock`/`resetstock` still snap the balanced price to the curve and now report the displayed price, with the usual price beside it when the mood has moved it. Courier trade runs sell through the same path, so they get `M` and the caps like any sale.

**Event limits bind everyone, the bypass included.** While an item has an active **DEAL** (in any phase, ramp included) or **DOWN**, each player may buy at most `max(1, floor(buy_limit_share × (max_daily_buy, or ceil(4% of full_stock) when unset)))` a day, for example 40 iron or 160 oak at the shipped 0.5. While it has an active **HOT** or **UP** (and `hot.sell_limit`), each player may sell at most `max_daily_sell`, or `ceil(2% of full_stock)` when unset. That is the number normal players already have, so it only newly binds bypass holders and items with no per-item cap. Both are counted from the existing daily tallies, which record every player, and are checked before the normal limits. A tally that cannot be read is logged and treated as 0, failing open as the normal limits do. The refusals read "Sale limit: you can buy N … a day while it's on sale." and "Crate buys up to N … a day while the price is up.".

**Stale quotes.** Each item keeps an in-memory jump counter, bumped whenever its `M` moves more than 1% between evaluations: news starts, forced events, stops, resets, real-world impulses, pause and resume. Drift (≤ 0.2% a tick) and HOT/DEAL ramps (≤ 0.47% a tick) never trip it. The Sell quantity screen and checkout record the counter when they quote. If it has changed at confirm, they refuse once with "Prices just moved! Take another look." and show the new price. No method signature changed.

**The ledger.** When a trade goes through at `M ≠ 1`, the same trade is priced again at `M = 1`. `sell_bonus += total − neutral` for sells and `buy_discount += neutral − total` for buys, both signed, buffered per local day and item and written in the tick's transaction (`market_sim_ledger`). Trades at exactly `M = 1` are not booked, so its unit counts are trades made at a moved price. `/hcm market sim audit [days]` prints it per item, with a total of "net paid out by the sim". The worst case is measured, not estimated.

**What it can cost** (shipped catalog, one player, one day, selling each item's full daily cap). Normal proceeds are $8,486.72. The absolute ceiling of extra money is +$597.81, which needs every item at +25% at once and cannot happen. A plausible worst day is **$548.10**: two UP flashes on iron and oak, a HOT on wheat, and drift at +8% on the rest. A player at the **$5,000/day** money cap earns $5,000 either way: the sim changes how many items it takes, not the total. Buying half the cap at −25% and selling it back later at normal is worth at most +$150.88 (iron), once a week at best. Someone who sells the same things every day whatever the market does gains about $0 on average.

**Clock, randomness, restarts.** Ticks sit on epoch-aligned boundaries (`tick_minutes`, 5), and local-time decisions use `clock.time_zone`. A main-thread pump runs every 20 s and handles every boundary since the last one. Older boundaries are **replays**: expiry, drift and season rows only, no planner and no broadcasts, at most `max_catchup_hours` (48; capped at 168) of them. The newest boundary runs live. Every roll comes from `SimRandom(seed)`, a counter-based stream keyed on the stream name and a counter (the tick's minute index, or a stored counter for admin commands), so the same tick always draws the same numbers. The 64-bit seed comes from `SecureRandom` on first start, lives only in `market_sim_meta`, and is never logged, printed or configurable. Config holds only ranges, and the item, direction, size and time of an event are rolled when it fires. `sim status` shows the next *times*, never items or directions. A restart cannot re-roll anything and generates no events for the downtime. A tick is **one database transaction**: states, events, meta, the ledger buffer and `last_tick_at`. If it fails, it is logged SEVERE, the in-memory state is kept, and `last_tick_at` does not advance, so the next start replays deterministically.

**Reload, pause, disable.** `/hcm reload` re-reads `market.sim`, rebuilds each item's parameters, re-clamps drift to the new bound, and silently ends the events of items removed from the catalog or set `sim: false`. Drift, events and the schedule are kept. `enabled: false` or `/hcm market sim pause` (kept across restarts) ends every event at once without a broadcast, sets `M = 1` for every item, stops the pump, drops the new feed fields and makes `/api/news` answer `live:false`. The market is then exactly 0.32. Re-enabling or `resume` starts drift from 0 and schedules the first HOT, DEAL and flash 10–40, 60–180 and 20–60 minutes out, with no replay across the time it was off. The one-time intro line is not repeated.

**Persistence: schema v32.** `market_sim_state` (drift, last news, featured-until and last-WANTED per item), `market_events` (one row per event; a partial unique index on `(kind, tag)` makes the yearly SEASON row and each REAL impulse idempotent), `market_sim_meta` (the seed, `last_tick_at`, the schedule, broadcast counters, the last-4 headline memory, real-world fetch state), `market_sim_ledger`, `market_news_seen` (last event seen and mute, per player) and `market_real_quotes`. The first tick after local midnight prunes events that ended more than `keep_days` (60) ago, ledger rows older than 400 days and quotes older than 30 days. The pre-migration backup (§11 #6) runs as always.
### 3.2 Crate — Ordering & Shipping
- **Access:** ONLY through a placed PC (see §3.3), via the `[www.Crate.craft](https://www.Crate.craft)` Site. No global command opens it.
- **GUI-first principle (whole plugin):** ALL player buying/selling/browsing happens in **clickable GUIs** — players **never type market commands**, and every `/hcm market …` / `/hcm admin …` command is **admin-only**. *(v16: this is a rule about **commerce**, not a ban on player commands. §10 deliberately designs a few `/hcm` subcommands as player routes — `/hcm auction` is the only way to reach the Auction House and `/hcm museum [id]` is the click target of every found-broadcast. Those are gated by their own default-true nodes, listed in §10; nothing that moves money or stock is reachable that way.)*
- **Flow:** browse catalog (departments, live prices) → choose quantity → choose shipping tier → pay (item cost + shipping) → order enters transit → delivered to the buyer's **Locker**.
- **Shipping tiers (REAL time):** **1-day = "Rush"**, **2-day**, **3-day**. **[CONFIRMED: real days]**
- **Shipping cost:** configurable, `mode: PERCENTAGE | FLAT`, with a **Prime-style** flat option. **Confirmed defaults (percentage):** 1-day/Rush = **20%**, 2-day = **10%**, 3-day = **free**. Tuned so ordering stays competitive with traveling to a shop (convenience premium, not a punishment).
- **Delivery → the Locker:** when the timer elapses, goods land in the buyer's **Locker** — a holding inbox they collect from the PC ("My Orders" → *In Transit* w/ countdown, *Ready to Collect*). **The Locker solves offline/full-inventory delivery:** if the player is offline or their inventory is full when an order arrives, it waits safely in the Locker instead of dropping/vanishing.
- **Persistence:** orders + delivery times survive restarts (durable timestamps; checked on scheduler + login). *(Migration v6 added the pending-orders table.)*
### 3.2b The Crate Marketplace — Pallets, Departments & Fees (NEW)
Crate is not just the house store — it's a **universal marketplace** where players sell **anything** to each other, Amazon-Marketplace / "Fulfilled-by-Crate" style. This is how *everything* becomes sellable (bread, tools, gear, Minis — anything not banned).
**The Pallet (player seller box).**
- A placeable, tagged block (same tech as the PC/Workbench — PDC + persisted location/owner).
- The owner loads items into the Pallet and **sets a fixed price** per item/stack; the listing appears online on Crate.
- When someone orders, the item is pulled from the Pallet, shipped (buyer picks a tier) to their Locker, and the seller is paid **minus the fee**.
- **When a Pallet runs dry**, its listings auto-go inactive until restocked.
- **Protection:** a Pallet should only function inside protected land (Towny claim / WorldGuard) so its stock can't be broken and looted. *(Respect the same build-perm checks as the PC.)*
**The fee (the money sink).**
- **[DECISION — default]** A **small % commission per sale** (referral-fee style) — keeps listing cheap but every sale feeds the sink. Configurable.
- **Optional** small **daily storage fee** per Pallet so dead listings don't pile up forever. Off by default.
- This directly addresses the "not enough money sinks" risk in §11.
**Everything is sellable → so we need departments + a ban list + auto-sorting.**
- **Departments ("Sites within the store"):** Crate is organized like real Amazon — **Blocks, Food, Tools, Weapons, Armor, Redstone, Collectibles, Misc** (configurable set). Buyers browse by department.
- **Auto-categorization (no hand-sorting thousands of items):** use the game's **own item metadata**:
  - **Primary:** the item's **creative-menu category** (Building Blocks, Redstone, Combat, Food & Drinks, Tools & Utilities, Spawn Eggs, etc.) → maps ~1:1 to departments. *(Verify the exact current Bukkit/Paper API on 26.2 — the creative-category/item-group API has shifted across versions; this is a "formalities" bind.)*
  - **Refinement:** Minecraft **item tags** (`logs`, `planks`, `wool`, `flowers`, `swords`, `ores`…) and property flags (edible = Food, etc.) for tighter buckets.
  - **Custom:** **Minis carry our PDC tag** → always land in **Collectibles**.
  - **Admin override map:** a small config map to relocate the handful that auto-sort wrong.
- **Ban list (admin):** items that can **never** be listed/sold — bedrock, command blocks, barriers, spawn eggs, structure blocks, other creative/exploit items. Ships with sensible defaults; fully editable.
**Minis in the Marketplace.** Minis are fully sellable: they auto-file into the **Collectibles** department. Fixed-price resale flows through the **Mini Vending Machine / Pallet** (tracked); the **Auction House** (§3.4) remains the venue for bidding on the rares. Everything stays inside our tracking so provenance + "who owns the rares" stay honest.
**Relationship to QuickShop (unchanged):** QuickShop = free, physical, walk-up shops in town. The **Pallet/Crate = online**, order-from-anywhere, **fee for the convenience** — the same free-vs-paid logic as free town shops vs paid Mall stalls (§3.6).
### 3.3 The PC
- A **custom item crafted from rare parts** at the Mini Workbench — a milestone. **Recipe is admin-defined and editable** (empty by default). Rare-tier ingredients to consider: Netherite, Redstone, Glass Panes, Amethyst/Echo Shard, capstone rare (Nether Star / Heart of the Sea).
- **Placeable** as a block; **right-click** opens the **PC Site launcher** (§2.2) — currently launches `[www.Crate.craft](https://www.Crate.craft)`; future Sites appear here.
- Represented by a computer-textured custom head/block (`skins.pc`; `crafting.pc.head_texture` mirrors it as the legacy fallback).
- **Recipe:** a vanilla SHAPED 3x3 recipe under `recipes.pc` (glass panes / copper + comparator / iron + quartz), also matched by a Printer's craft grid. Every craftable HomeCraft block follows the same pattern — see §4.
- It is the **gate** to all online commerce — no PC, no Crate.
- **[DECISION]** Respect Towny/WorldGuard build perms so only owners/residents place & use in claimed land. **(Rec: yes.)**
### 3.4 Collectibles System — "Minis" (Heads & Armor Stands)
The showpiece. Branded **"Minis."** Two Mini types: **decorative heads** and **posed armor stands** (from minecraft-heads.com). Both are ultra-rare collectibles and status symbols; everything below (series, rarity, mint caps, Museum & Shop, circulation) applies to **both**.
**Curated, never swept — hard requirement.** The catalog is **hand-picked by the admin** via a **checkmark selective-import** — never a wholesale sweep of the site.
- **Series:** every Mini belongs to a named series (e.g., "Woodland Critters," "Legendary Relics"), themed and released over time.
- **Rarity tiers:** Common → Uncommon → Rare → Epic → Legendary.
- **Mint cap (fully configurable):** uncapped or hard-capped per series/rarity. A minted-out cap becomes **trade-only**. Every behavior is a toggle.
- **Museum (browse-only, v14):** displays each Mini with name, series, rarity, **minted X / cap**, **# in circulation**, the **Standard→Mint value appraisal**, and a detail card with **who currently holds live copies** and how to get one. **Nothing is minted or bought here** — the only mint paths are Cards → Printer (§3.5), wild drops, natural spawns and Arcade crates. `/hcm museum [id]` (also the click target of every found-broadcast).
- **Pricing:** **fixed-per-rarity** *or* **escalating**, per series/rarity.
- **Commons:** uncapped & cheap by default.
- **Craftable option (per entry):** a `craftable` flag; if enabled the admin defines a recipe (crafted at the Workbench) and **crafted copies still count toward the mint cap/circulation**.
- **Texture source:** curated from minecraft-heads.com [heads](https://minecraft-heads.com/) and [armor stands](https://minecraft-heads.com/armor-stands). Data-driven per entry (texture/config + metadata). **The one field we actually need is the head's `Value` (Base64 texture string)** — we render it natively via `PlayerProfile`/`PlayerTextures`; no Head Database plugin and no runtime dependency on the site.
**Organizing & displaying Minis — Type, Rarity color & filters (NEW).** Minis need **three independent tag dimensions**, because to the game every Mini is just a `PLAYER_HEAD` and can't be auto-sorted like normal items — we tag them ourselves:
- **Type / category (admin-defined open list):** the *subject* — Animal, Food, Letter, Symbol, Character, Vehicle, Holiday, … You define the list and assign a primary Type per Mini. *(Stored as `category` in config to avoid clashing with the `type: HEAD|ARMOR_STAND` form field.)*
- **Series:** the release/collection grouping (existing).
- **Rarity:** Common → Uncommon → Rare → Epic → Legendary — **and rarity drives the visual style automatically, so you assign the tier, not the color.** The **grade** (§3.5) never competes: it is shown only as a star suffix on the name and a lore line.
- **Tags (v14):** free-form drop tags per entry (`tags: [mining, fish]`, editable in the Admin Studio). A loot source or crate reward may target a **tag pool** — every Mini carrying the tag, weighted by rarity (`minis.loot.rarity_weights`). Untagged Minis never drop from a pool.
**Rarity color system (recommended: derive style from rarity; edit the palette map once):**
| Rarity | GUI frame (stained-glass pane) | Name color | Glint | Suggested default cap |
|---|---|---|---|---|
| **Legendary** | **Gold / Yellow** | gold | ✅ | 5 |
| **Epic** | Purple | light purple | ✅ | 20 |
| **Rare** | Blue | aqua | — | 100 |
| **Uncommon** | Green | green | — | 500 |
| **Common** | none / light-gray | gray | — | uncapped |
- **How the "gold background" works in a GUI:** each Mini sits **framed by a rarity-colored stained-glass pane** in the surrounding slots (that's the colored background effect), its **display name is colored** by rarity, and Epic/Legendary get an **enchant glint** (shiny). Tooltip lore shows: **Type · Series · Rarity · Mint #N/cap · # in circulation.**
- **You assign rarity + cap; the color follows.** The rarity→style map lives in config, so you tweak the whole palette once instead of coloring every Mini. (Per-Mini style override allowed if you ever want it.)
- **Smart defaults by rarity (optional, saves work):** each rarity carries a default **cap** and **price** (table above), pre-filled when you assign the tier — you just override the exceptions.
- **Filtering/browsing:** the Museum & Shop and the Crate **Collectibles** department are filterable/sortable by **Type**, **Series**, and **Rarity** (a tab/button row) — browse "all Legendary Animals" or "everything in Woodland Critters."
- **Bulk assign on import:** the checkmark import assigns Type + Rarity + cap to the whole selected batch at once, then per-entry tweaks.
**Armor Stands (second collectible type).** Posed decorative armor stands — spawned as fully configured `ArmorStand` entities from stored pose/equipment data on placement. Same series/rarity/cap/circulation rules as heads.
**Curation Workflow (admin-controlled).** Primary tool = **selective import GUI** (browse a category → tick checkboxes → **Import Selected** → assign metadata in bulk or per-entry). Secondary = in-game capture (`/collect admin add`) and manual paste. Cache fetched categories; paginate.
**Every Mini is a unique, tagged item** — hidden unique ID + metadata (series, rarity, "Mint #N of cap") in item data (PDC), individually trackable through drops/chests/trades. Powers circulation tracking + **provenance** ("Mint #3 of 5"). **Dupe protection is a hard requirement — see §11.**
**Trading Minis — the Mini Vending Machine (tracked).** A custom block: a player lists a Mini at their own price (secondary market); buyers browse/purchase; the sale is **fully tracked** (ownership transfer + price history), showing live mint/circulation at point of sale. *Bonus:* a **Display Case** variant to show one off (no sale). We deliberately do **not** route Mini sales through QuickShop (invisible to tracking).
**Mini Auction House (for the rares — tracked).** Timed auctions: starting bid + duration; escrowed bids; auto-refund losers; anti-snipe timer extension; optional Buy-It-Now; outbid/won/sold notifications. **Lives physically in the Mall** at spawn — a big sale becomes an event. (Bids/alerts also reachable online via the PC.)
**Mini economy = primary + secondary** (like real collectibles): official **primary** mint (Mall physical + Crate online) up to caps; player-driven **secondary** (Vending Machine fixed-price + Auction House bidding), all tracked. In the Crate store, Minis surface in the **Collectibles department (§3.2b)**.
**World effects for placed Minis (v14).** A Mini on display — in a **Display Case**, as a posed **armor stand**, or a **natural wild spawn** — shows its tier: per-rarity **ambient particles**, a floating **name hologram** (TextDisplay), a hidden **LIGHT block** in the nearest air neighbour, and for Epic/Legendary a slowly **rotating ItemDisplay** (Legendary: full-bright, a totem sound + burst on placement). **Mint**-grade copies chime on print and on place; **Shiny** copies add a ring of glow particles. One repeating task serves every placed Mini, skipping any with no player within `minis.effects.radius` and any in an unloaded chunk; everything is torn down on removal / chunk unload / disable and rebuilt from the datastore on load. All of it is `minis.effects` config with sane defaults.

**Announcements (v14, `minis.announce`).** When a Mini is **found** (wild drop, natural spawn, crate — never the Printer or a pack) one line is broadcast: "*Dierks found a [Chick Mini ★★] while mining!*" — the name is a hover card with the item's full tooltip and click-runs `/hcm museum <id>`; the verb comes from the trigger. When a natural spawn lands: "*A Epic Mini has spawned within 100 blocks of a player! Good luck!*" (rarity coloured; never coordinates or the player), and "*The Mini slipped away...*" if it despawns untouched. A short sound plays to everyone; `min_rarity` and per-rarity toggles can silence low tiers.

**Wild Drops — rare loot-table minting (NEW, high-want feature).** Beyond buying and crafting, a Mini can be configured to **drop randomly from the world** — e.g. a "Cobblestone Mini" that pops out of mined cobblestone at a tiny chance (say 0.0001%). This makes Minis feel like **treasure you stumble on**, not just something you buy.
- **Sources:** a trigger (**BLOCK_BREAK / MOB_KILL / FISHING / NATURAL_SPAWN**) + a match + a pool (`list:` or `tag:`) + a chance. A drop rolls **grade and Shiny** with the Printer's odds (per-source override allowed).
- **NATURAL_SPAWN (v14):** every `minis.loot.natural.interval_ticks`, for each online player, roll the source chance; on a hit place a minted Mini **head** on a random surface spot 24–48 blocks away (solid ground, air above, no water, somewhere the player may build). It shows a small "A wild Mini!" hologram plus its rarity effects, hands itself to whoever touches or breaks it (found-broadcast, verb "exploring"), and **despawns after `despawn_minutes`**, retiring the copy. Persisted (`mini_spawns`) so lifetimes survive restarts.
- **A wild drop MINTS a new copy** — so it **respects the mint cap** (stops dropping once minted out) and **counts toward circulation**, keeping the finite promise honest. The mint pipeline (§3.5) stays the single source of truth: a drop is just another mint path.
- **Anti-farm guard (critical):** only **naturally-generated, non-player-placed** blocks roll the drop — track placed blocks (PDC/placed-block set) and ignore silk-touch-and-replace, so nobody cheeses it with a place-break cobblestone farm. Same idea for spawner-farmed mobs if mob drops are enabled.
- **Import tie-in:** minecraft-heads.com exports a **loot-table JSON per head** — we can lift the texture value from it and reference its structure, but the *drop roll + cap enforcement live in our plugin* (a `BlockBreakEvent`/entity-death listener), not a raw vanilla loot table, because vanilla loot tables can't enforce a mint cap.
**Computed value + Museum appraisal (Part F).** Each printed Mini has a live value: `base(rarity) × grade_mult(Standard 1.0 / Graded 2.5 / Mint 6.0) × finish_mult(Shiny) × scarcity_factor(circulation vs cap)`, blended with real market data (last sale) when available; a Card has a simpler sealed-collectible value. Value surfaces on tooltips / the Museum appraisal and is the **suggested floor** in Vending/Auction listings. *(Planned follow-on: physical value plaques behind displayed Minis, and admin **display-only** Showcase copies flagged `display_only` that don't count toward cap or circulation and can't be sold/pocketed/traded.)*
### 3.5 The Card & Printer Collectible Economy (Cards → filament → Printer → graded Minis)
*(Supersedes the retired Mini Workbench crafting model.)* Minis are no longer crafted — they are **printed from Cards**. Two distinct collectibles:

- **Cards** — flat, tradeable collectible items (one Card type per Mini). Cards are what you find/buy/trade/collect; a Card's tooltip shows the Mini's rarity, the printable **grade odds**, and the **filament cost**. Cards are capped per type (rare finite, common uncapped) and are what **Wild Drops, Arcade crates, quests, and Card Packs** hand out — never finished Minis.
- **Minis** — the 3D **printed figures** you make from a Card at a **Printer**, and the thing you display. Feeding a Card into a Printer **consumes the Card** and outputs one **graded** Mini.

**The Mini Printer (the single mint source).** A placeable, owned, protected, skinnable block (`skins.printer`) that replaces the Workbench. Right-click → GUI: hold a Card → validate filament + fee → **roll a grade** from the card's odds → print (animation: rising figure + particles + sound) → output the graded Mini through the **one** cap-aware, anti-dupe mint pipeline (§3.4). No print cap; the Card is consumed per print.
- **Grades (v14)** ☆ **Standard** → ★★ **Graded** → ★★★ **Mint**, rolled at print from the card's published weighted odds (`card.grades: { standard, graded, mint }`; names/symbols/multipliers in `minis.grades`). **Grade owns the stars, rarity owns the look:** the grade is a star suffix on the name and a lore line — colour and glint always come from the rarity palette. Grade feeds value (Part F).
- **Filament** — colour-tagged items (mapped to dye colours) the Printer consumes in the exact per-colour amounts a card lists. Craftable/buyable.
- **Finishes** — a premium **Shiny** finish (private/home printers only) adds an extra glint + "✦ Shiny" in lore, for extra filament + fee; wild drops and crates roll Shiny at `minis.loot.shiny_percent`. Shiny is independent of grade.
- **Public vs private printers** — a printer flagged **public** (the Mall printer) prints **free** (house covers filament + fee, basic finishes only, no Shiny); a **private/home** printer requires the player's filament + fee and unlocks Shiny. (Free-town-shop vs paid-Mall-stall logic, applied to printing.)

**Card Packs (booster packs; money sink).** Buyable packs pay money → **N weighted-random Cards** (respecting card caps) with a pack-opening reveal GUI. Pack types are **admin-authored via a GUI pack-builder** (name, price, card count, weighted card pool) — persisted to config, no YAML by hand — and sold in the store (and later the Arcade).

**The PC** is now an easy "chill" recipe (cheap admin-defined ingredients), craftable at a Printer's light craft grid (or a legacy Workbench placement, which is retired but kept usable for graceful migration).

**Card Packs are physical items.** A pack is a sealed booster **item** you buy from the store (PC), get via `/hcm give pack`, or comp-open from the pack-builder's "Test Open"; right-click it to open into the reveal GUI. **The Card Binder** is a single-slot album item (`/hcm give binder`, right-click to open) that stores a player's Cards, supports deposit/withdraw, and doubles as a set tracker (owned cards lit, missing greyed, per-series completion).
### 3.6 The Mall — Spawn Market District
A central **commercial district at spawn.**
- **Rentable stalls (paid):** rent a Mall stall for money for a prime central spot (holds your QuickShops, Mini Vending Machines, Pallets).
- **Anchor tenants:** the official **Mini Museum & Shop**, the **Mini Auction House**, and the **Arcade** (§3.9 — tokens, loot boxes, lotto) all live here.
- **Free vs prime:** **town shops = FREE** (your land, low traffic) vs **Mall stalls = PAID RENT** (central, high traffic). A real commercial-real-estate decision — the same free-vs-paid logic mirrored by QuickShop-vs-Pallet online.
- **Implementation — reuse Towny:** the Mall = an **admin Towny town at spawn** using Towny's own plot renting. Minimal custom code.
### 3.7 The Market Web Dashboard (the "trading floor")
A **live web page for the economy** — the BlueMap of your market. Shows every commodity: current price, **price-history chart** (ticker/candlestick), **stock on hand**, 24h change/trend. Served by the plugin itself (embedded web server + static HTML/JS frontend) on its own port; tunnel the port (second Playit tunnel) for outside access. Data source: the market engine's live stock + price + **price-history** tables (§5). Also surfaced **in-game as a Site** on the PC (§2.2).
**Transactional web shop — buy (and manage sales) from the browser (YES, possible; extension of the dashboard).** The read-only dashboard is the easy 90%. Turning it into a *store you can spend on* adds two things:
- **Secure login (so only YOU can spend your money) — decided model:** a simple **username + password** account, created in-game with `/crate register <user> <password>`. The **password is stored hashed, never plaintext**; the permanent anchor is the player's **Minecraft account/UUID**, so **no email is needed**. **Recovery:** re-run `/crate register` in-game, or an admin `/crate admin resetpw <player>` (admins can reset a password, never *see* it). Read-only browsing is **public**; **spending requires login**. (No real money anywhere — in-game currency only — a custom auth'd app, not a payment system.)
- **Authenticated buy endpoints** on the *same* embedded server, calling the *same* market/Marketplace engine (Vault withdraw → order → Locker).
- **The Locker (§3.2) is exactly what makes web shopping work:** a browser purchase can't drop an item into your hand (you may be offline), so it **delivers to your in-game Locker** to collect at your PC later — the identical pipeline we already built for PC/Rush orders. Order from the couch on your phone; the goods are waiting when you log in.
- **Buying is straightforward; *selling* is trickier** (taking items needs you online), so web selling is limited to **restocking/repricing your Pallet listings**; instant hand-over-the-counter selling stays in-game.
- **Build order:** dashboard first (read-only, safe), then layer on auth + buy buttons — same server, same data.

**Website feeds (0.32).** The same server feeds the LilahCraft website, which fetches from its WordPress server only, caches, and falls back to labelled sample data, so a feed can switch part of the site to live but never break it. `/api/market` keeps every field it had and gains `history7d` (hourly, up to 168 points) and `history30d` (six-hourly, up to 120): the latest snapshot per epoch-aligned bucket, read from the existing price-history table, which `market.price_history.keep_days` (30) now prunes. `/api/minis` is the catalog with `printed` counts, `soldOut` and head-skin URLs, and no player data. Both are built by the main-thread refresh like the market feed always was, and gzipped on request. An optional `web.dashboard.feed_token` gates every `/api/*` feed (Bearer, constant-time compare, 401 otherwise). The dashboard page prompts for the token when it meets a 401 and remembers it in that browser. `web.dashboard.lan_skips_token` (off by default) lets loopback and LAN requests skip it, unless they carry a forwarding header. **The Playit tunnel suggested above hands internet traffic over from this PC (or the LAN), so with one, `lan_skips_token` must stay `false`.** The token is a shared secret for one machine reader (WordPress), not the player login the transactional shop needs; that model is unchanged. See the README's "Website feeds (0.32)" for the shapes and the acceptance commands.

**Live-market feeds (0.33).** **Nothing new is written unless the live market is running**, so with it off or paused `/api/market` is byte-for-byte 0.32 (the old builder is pinned by the existing tests). While it runs, each sim-enabled item gains, after its last history array: `usual` and `moodPct` always; `status` and `statusEndsAt` while a badge shows; and `events` when there are any, meaning up to 20 HOT/DEAL/UP/DOWN/REAL marks from the last 30 days, oldest first, for chart markers. The top level gains `live:true`, `hot` and `deals` (item ids), `season` (left out when there is none; `endsAt` is local midnight after its last day), and `news`, the 5 newest within 7 days. **`GET /api/news`** is new and goes through the same token gate, gzip and main-thread rebuild. It carries up to 50 news rows from 7 days, newest first (`kind`, `dir`, signed `pct`, `before`/`after` money when both are known, `headline` and `line` with colour codes stripped, `source` `sim|admin|calendar|real`, `active`), every running HOT/DEAL/UP/DOWN under `active`, and the season. With the sim off it answers `{"generatedAt":…,"live":false,"active":[],"news":[]}`. **The website never sees an event before the players do:** rows are built only through `NewsFeed`'s factories, which leave out a HOT/DEAL still in its silent ramp or stopped during it. An admin-forced event says `source: "admin"`. The dashboard page gains a news ticker, badge pills with "ends in", "Usually $X" when the mood is at least 3%, chart event markers and a 48H/7D/30D toggle. The LilahCraft site ignores all of it until a theme release shows it.
### 3.8 In-Game Economy Displays (TVs, Tickers & Boards) (NEW)
Bring the stock-market feel into the world itself — physical displays in the Mall.
- **The price "TV" = a flat wall-mounted `TextDisplay` panel.** `/hcm display tv <commodity> [scale]` mounts one tracked `TextDisplay` flat against the wall you're looking at — commodity name, a big current price, the ▲/▼ trend with %, and stock (the same content a sign board / the `hcm` PlaceholderAPI expansion produces), live-updated on the refresh timer. It reads as a *screen*: a solid dark opaque background, `FIXED` billboard (stays flat, never rotates to face the player), scaled up via the display transformation (`displays.tv.scale`, or the per-panel `[scale]` arg), centred text, nudged just off the wall face. **Crisp Minecraft font — no maps, no `ItemDisplay`, no floating item.** One placement = exactly one `TextDisplay`, persisted (kind `TV`, facing = wall face, data = scale) and restored (not duplicated) on reload/chunk-load; removed with `/hcm display remove` and swept by `/hcm display cleanup`. *(The earlier map-on-item-frame "map-TV" and the browser-link "TV block" were both retired: painting text pixel-by-pixel onto a 128×128 low-palette `MapView` was chunky and kept breaking to black, and a later rework leaked unremovable spinning `ItemDisplay` holograms. A `TextDisplay` renders real, crisp font — the text was never the problem, the map was.)*
- **Holographic tickers = floating text.** Expose live values via **PlaceholderAPI** (`%hcm_price_<item>%`, trend arrow) and render them as text-display entities / holograms above market stalls.
- **Stock board = wall of auto-updating signs** — one row per commodity with price + ▲/▼. Simple, instantly readable.
- **TAB / scoreboard** — optionally show a price in the tablist/sidebar.
- **Data feed:** the same price-history the dashboard uses (already logged since Phase 2.5 via `/hcm market history`).
- **Live-market badges (0.33).** Every display shows the displayed price, with the mood in it. A badge is added where one shows, with precedence UP/DOWN > HOT/DEAL > WANTED. Sign line 3 is prefixed `★HOT `, `✦DEAL `, `▲NEWS ` or `▼NEWS ` before the trend; the longest case, `✦DEAL ▼ 14.20%`, is 14 visible characters, inside the 15 a sign holds. A hologram adds ` ★ HOT`, ` ✦ DEAL`, ` ▲ NEWS` or ` ▼ NEWS`. A TV's fifth line is `★ HOT ★`, `✦ DEAL ✦`, `▲ PRICE UP!`, `▼ PRICE DOWN!` or `» WANTED` (with "(cooling off)" / "(ending soon)" while a HOT/DEAL fades), and its sixth line is "Usually $X" when the price is at least 3% from usual. Every string comes from one table, `gui/MarketLabels`, which the Store and Sell tiles, the chat lines and PlaceholderAPI share, and which a test holds to glyphs Bedrock can draw (nothing above U+FFFF). When a flash or a HOT/DEAL is announced (`announce.particles`), `DisplayService.celebrate` puts happy-villager sparkles (good news) or a puff of cloud (a drop) on every loaded display bound to that item and on every `@news` board.
- **The `@news` board (0.33).** `@news` is a pseudo-commodity that holograms and TVs accept (`/hcm display hologram`, or `/hcm display tv @news`, or the BELL "» Market News" in slot 0 of the commodity picker). It shows "» CRATE NEWS «", the newest headline wrapped at 32 characters (at most 3 lines), its age, and "★ Hot: …" / "✦ Deal: …", or "The Crate Market is calm today.". A sign refuses it ("The news board needs a TV or a hologram - a sign is too small."). Because catalog ids starting with `@` are now skipped at load with a warning, and the GUI's id rule is `[a-z0-9_]`, no real item can ever be called `@news`.
- **PlaceholderAPI (0.33).** Exact keys `%hcm_news%` (the latest headline, at most 60 characters, or "The Crate Market is calm today."), `%hcm_news_age%`, `%hcm_hot_list%` / `%hcm_deal_list%` (names, or `none`) and `%hcm_season%`. Per item: `%hcm_status_<item>%` (`HOT`, `DEAL`, `UP`, `DOWN`, `WANTED` or empty), `%hcm_badge_<item>%` (`★ HOT +12%`), `%hcm_usual_<item>%`, `%hcm_mood_<item>%` (`+11.7%`) and `%hcm_endsin_<item>%` (`20h`, `2d`). `%hcm_price_<item>%` and `%hcm_trend_<item>%` include the mood automatically. All of them read the sim's immutable snapshot, never the database.
### 3.9 Rewards, Tokens & the Arcade
> **Rewritten for 0.31.0 (v17.13) to match the code.** The rule: **dollars buy things you keep,
> tokens buy fun, Minis come from the hunt.** §11 #9 is the money boundary this section lives
> inside; where they ever disagree, §11 #9 wins.

A wholesome **arcade** loop that rewards showing up and playing — **all earned in-game, never bought with real money, and never turned into dollars** (a fun arcade, not gambling — right for a kids' server).

**Tokens — earned by playing.** Every balance change is one guarded SQLite transaction (`tokens = tokens + ? WHERE tokens + ? >= 0`) that also writes a `token_ledger` row with its source (`LOGIN_STREAK`, `PLAYTIME`, `QUEST`, `ACHIEVEMENT`, `CRATE`, `LOTTO`, `PRIZE`, `PITY`, `PACK`, `TRADE_IN`, `HUNT`, `ADMIN`, `REFUND`); `/hcm tokens audit [days] [player]` and `/hcm tokens history <player> [n]` read it. Earning is gated by the world sandbox (§11 #1). Days and weeks are **local** (`clock.time_zone`, America/Chicago): the streak, quests and every per-day/per-week limit roll over at local midnight, and the streak is also checked on the five-minute tick so staying online across midnight pays. Sources, on an active day (≈28 tokens):
- **Login streak** `[2,2,3,3,4,4,5]` (the last repeats), one claim per local day.
- **Playtime** — a token per `minutes_per_token` (60).
- **Quests** — each player draws **3 dailies and 2 weeklies** from `daily_pool` / `weekly_pool`, no two of one type, stored in `quest_assignments`. Pushed types come from gameplay (PLANT_CROPS, HARVEST_CROPS — grown only, COOK_FOOD, SMELT_ORE, MINE_BLOCKS — natural blocks only, sharing the `player_placed` ledger, VISIT_BIOMES, COMPLETE_DELIVERY, FIND_WILD_MINI, plus the older SELL_MARKET, OPEN_CRATE, PRINT_MINI, OPEN_PACK, SCRATCH kept for admins); pulled types (CATCH_FISH, KILL_HOSTILES, BREED_ANIMALS, TRADE_VILLAGER, TRAVEL_ON_FOOT) bank the difference between 30-second polls of vanilla statistics. A **Quest Reroll** (10 tokens, once a day) swaps one unfinished daily for one not dealt.
- **Achievements** — a 26-row list in groups (Getting Started, Mini Hunter, Collector, Adventure, Dedication, Work, Money, Arcade), each paying once. Types: EVENT, STAT, COUNTER (`player_counters`: wild finds, rare wild finds, quests done, deliveries, crates, jackpots, biomes from `player_biomes`), COLLECTION, GRADE, FINISH, STREAK, BALANCE — checked where they happen, on join and on the five-minute tick; an unlock gets a chat line, a sound and an "Achievement!" title.
- **The wild hunt** (§3.4) — catching one counts toward quests and achievements; a caught Mini that can no longer be minted (a cap lowered mid-hunt) crumbles and pays 25 tokens instead.
- **Card trade-in** — spare Cards for 3/5/12/25/50 tokens by rarity (the one small money→token path besides pack token prices, §11 #9).

**Spending — the Arcade hub** (`/hcm arcade`, or an Arcade Machine block). One 54-slot screen in coloured zones: the **Wallet** on top (balance, streak and tomorrow's reward, playtime progress; opens to an armed lure, the active trail with a toggle, the ways to earn and the last ten ledger lines in plain words); **Games**; the **Prize Counter** tabs and **Card Packs**; and **You** (Quests, Achievements, How It Works). Everything important is in each button's *name*, because Bedrock shows lore only on tap-and-hold; buttons take `arcade.icons.<key>` head textures with colourful material fallbacks, and Bedrock players (Floodgate) always get the material.
- **Crates** (`arcade.crates`) — a token price and a weighted table. A reward may be `card`/`mini` (a **Card**, never a finished Mini: a fixed id, a `tag:` pool, or `tag: "*"` for every Mini — the crate's jackpot), `pack`, `filament`, `tokens` (less than the crate costs), `prize` (one Prize Counter row, or one of a list, free and ignoring its limit) or `trail` (for `days`). `money` and `item` rewards are rejected at load; a crate's `paid_odds` is gone. Card rewards are cap-aware. The shipped **Arcade Crate** is 15 tokens: boosts 40, Firework Show 15, radar 12, filament 10, 8 tokens 10, a 1-day trail 8, hats 3, any Mini's Card 2. Odds are published on the crate screen.
- **Scratch Ticket** (`arcade.lotto`) — 10 tokens in, tokens out, decided and paid at purchase. The near-miss (+3 back) is the most common result; a persisted **jackpot pot** (`arcade_state`: seed 50, +1 a ticket, cap 1000) is shouted server-wide when won and resets. RTP ≈ 78% over time; `/hcm arcade odds` prints it (steady-state pot = seed + per_ticket / p) and each crate's value at counter prices.
- **Prize Counter** (`arcade.prizes`) — known-outcome prices in six tabs (Boosts, Hunt Gear, Cosmetics, Perks, Trophies, Minis): **+1 Home** (adds to the homes a player already has; see v17.14), right-click **boosts** (stack to 3× their time), the **Mini Radar** (Cold/Warm/Hot/Burning! toward the live hunt), the **Mini Lure** (the next natural spawn for anyone lands near the earliest-armed holder; never changes how often Minis spawn), a harmless **Firework Show**, 7-day **trails** (`cosmetics_owned`, `/hcm trail`), **hats** (hidden while untextured), **command** perks (extra homes and `/hat` via LuckPerms; name colours and chat titles ship disabled), a numbered **Arcade Trophy**, the **Rare Card** (pity: 150 tokens, one a local week, Rare-or-better, cap-aware), filament, a Display Case and **Card trade-in**. Limits are per local day/week/lifetime in `prize_purchases`; 100+ tokens asks "are you sure?"; the item is built before anything is charged and a failed delivery refunds.
- **Card Packs** (§3.5) — also sold behind a door in the hub, some for tokens.
- **Animations are only shows.** Every outcome is decided and granted before its animation: the crate **spin** (`arcade.reveal`: 60 ticks, 24 frames, 6 on Bedrock), the three-square **scratch**, the pack **build-up**. Closing early prints the result. **Big wins** (a crate's any-Mini Card, the jackpot, a Rare+ Card) add a title, a harmless firework and a server-wide shout.
- **How It Works** — four picture pages (Tokens, Minis, Wild Minis, Arcade games) from the hub, the pack shop's "?", the PC's Guide site and `/hcm guide`; `docs/how-it-works.md` is the website version.

**Token prizes can't be sold.** Every boost, radar, lure, firework, hat and trophy is tagged `hcm:token_prize`; the house market, Pallets, vending machines, the Auction House and Courier cargo all refuse it ("Arcade prizes can't be sold."), a shulker box or bundle holding one counts as one, and crafting, crafters, brewing and villager trades refuse them as ingredients. Cards, packs, filament and HomeCraft blocks are deliberately untagged.

**Hub only.** The Arcade Machine is the one placeable Arcade block (`/hcm give arcade`, `recipes.arcade`, `skins.arcade`). The old Crate Machine / Scratch-Ticket Booth / Pity Exchange / Token Counter blocks are no longer given; any already placed open the matching hub screen (a Crate Machine opens its crate, or the list of every crate when there are several).

**Physical home — the Arcade at the Mall:** an anchor tenant alongside the Museum & Auction House, where a big pull is an *event*. *(Framed as an Arcade, not a casino: earned tokens, not real money.)*

### 3.10 The Courier — Deliveries (NEW)

A **Site on the PC** (§2.2) that pays players to move things around the map. It exists because §3.1's
market is a *finite, conserved* exchange: selling into it moves stock and moves the price, so it cannot
be the only way a kid earns money without either the price collapsing or the catalog growing into a
faucet — which §3.1 explicitly refuses. The Courier is the deliberate alternative: **effort in the
world, paid in money, with nothing produced and nothing sold.**

**The loop.** Open the board, pick a distance band, take a run. A **waypoint** is rolled then and there
— a random bearing and distance, resolved to the highest solid block, rejected and re-rolled if it is
liquid, an ocean biome, or land the player has no build rights to (up to `waypoint.max_rerolls`; after
that the run is simply not offered, because a drop-off in the middle of an ocean is worse than a short
board). Walk there, click **Hand it over** within `turn_in_radius` blocks, get paid. One run at a time,
per player.

**What it pays.** `fee = (base + per_block × distance) × travel_multiplier`. Distance is the
**horizontal straight line** from where the job was accepted to the waypoint, **clamped to
`[min_distance, max_distance]`** and **locked at acceptance** — so going the long way round, or picking
a fight on the way, earns nothing extra and the fee cannot be re-priced by anything that happens en
route. At the shipped `base: 4.0` / `per_block: 0.012`, the five daily runs at their band midpoints come
to **about $93 on foot** — a day's honest income that sits *below* what the same time spent mining and
selling returns, which is the point: the Courier is a floor under a slow day, not a better grind.

**The travel multiplier is the mechanism.** Every movement statistic is snapshotted when the job is
accepted and diffed at turn-in, then **blended by the fraction of centimetres moved in each group** —
never by a total, and never by whichever vehicle was touched last. That distinction is what makes it
un-gameable in both directions: a player who walks the route once and one who walks it four times both
score **1.00**, so padding the trip is worthless, and someone who rides half and walks half lands
**between** the two multipliers rather than at the better one. Vanilla's accounting is mutually
exclusive — exactly one counter increments per tick — so the fractions are a real partition and cannot
sum to more than the distance covered. `FALL_ONE_CM` is deliberately in **no** group (falling is not
travelling) and the denominator is the grouped statistics only, so an ungrouped counter can never
dilute a multiplier.

| Group | Statistics | Shipped |
|---|---|---|
| `foot` | WALK / SPRINT / CROUCH / SWIM / WALK_ON_WATER / WALK_UNDER_WATER / CLIMB | **1.00** |
| `mount` | HORSE / STRIDER / PIG / NAUTILUS | 0.85 |
| `boat` | BOAT | 0.80 |
| `ghast` | HAPPY_GHAST | 0.70 |
| `rail` | MINECART | 0.65 |
| `elytra` | AVIATE | 0.45 |
| `creative` | FLY | **0.00** |

**A Nautilus is grouped with the mounts, not the boats.** It reads as water travel, but the API says
otherwise: `AbstractNautilus` is `Tameable, InventoryHolder, Vehicle` with an
`ArmoredSaddledMountInventory` — a saddled, armoured, tameable mount like a Strider, and priced like
one. It is one line of config if that call turns out to feel wrong in play. `HAPPY_GHAST_ONE_CM` and
`NAUTILUS_ONE_CM` are resolved **by name** rather than referenced directly, because the pinned API may
predate them: a server without one simply does not count it, and neither case needs a code change.

**Anti-teleport.** If total tracked movement comes to less than `anti_teleport.floor` (70%) of the
locked distance, the travel fee pays `anti_teleport.payout` (20%) instead. Cargo on a trade run still
pays in full, because that half is a real sale. **A zero blend is 0.00, not 1.00** — a player who
arrives having moved nothing is not handed the on-foot rate by default. It is logged at `FINE` and
never called out in chat: a nether-portal shortcut is not cheating, it is just not walking it, and the
smaller number is the whole message.

**Trade runs.** Right-click a band with market cargo in hand and the run carries it: the stack is
quoted at acceptance, and at the drop-off it is **sold through the market for real** — stock moves +N,
daily sell limits apply, commission applies, exactly as if it had been sold at home. It is paid at the
**live** rate, **not the locked quote**. The quote is recorded on the job row and shown on the board
for reference only. Paying the locked figure would mint the difference, and **only the travel fee is
meant to be new money**. A trade run refuses at turn-in if the cargo is no longer in the inventory.

**Caps and expiry.** Three bands — `local` 200–600 (2/day), `regional` 600–1800 (2/day), `long_haul`
1800–4000 (1/day) — keyed by **UTC epoch-day at read time**, the house pattern (§8: no scheduler).
Runs expire after `expire_minutes` (60); a sweep closes stale ones every minute so the band slot comes
back. **Abandoning does not spend the slot** — the day's count is ACTIVE + DELIVERED, so dropping a run
you can't finish costs nothing but the walk. Every accept and every turn-in goes through the economy
sandbox (§11 #1): there is no courier money in a creative world.

**The destination (Phase 2).** A delivery now arrives somewhere rather than at a coordinate. Once the
player is within `building.place_at_blocks` of the waypoint a **small house** goes up with a
**villager** outside it; right-click them to hand the crate over. When the run ends — delivered,
expired, abandoned, or the plugin shutting down — **the field is put back exactly as it was.**

*Houses are vanilla.* Mojang already maintains a correct-looking small village house per biome, so the
shipped table names those structure keys rather than shipping schematics: `village/<family>/houses/…`
across plains, desert, savanna, taiga and snowy, with everything unmapped (jungle, swamp, mangrove)
falling to plains. **Every key is resolved once at startup** and any that no longer exists is dropped
with a warning naming it, because Mojang renames these between versions and the alternative is a
delivery that fails after the player has walked eighteen hundred blocks. Rotation is random from the
four quarter-turns — free variety, no extra assets.

*Trees are cleared; hills are refused.* Ground level is found by looking **down** through leaves,
trunks and undergrowth to real terrain (`Ground`), not by reading a heightmap — **every heightmap
Minecraft keeps answers a different question**. `MOTION_BLOCKING`, which is what `getHighestBlockYAt`
returns by default, is "the highest block that blocks motion or holds a fluid", so in a wood it
reports the canopy and beside a lake the water surface. `OCEAN_FLOOR` is no better: its predicate is
`blocksMotion()`, which leaves satisfy. `MOTION_BLOCKING_NO_LEAVES` drops the leaves and still stops
on a trunk. There is no heightmap that means "the ground", so the walk down is the only honest answer
— and it is also the only thing that can tell "a tree is in the way" (clear it) from "this is a lake"
(deliver elsewhere), which look identical from above and need opposite answers.

Whole trees, not slices. The clearing is a **flood fill** through connected logs and leaves from
everything rooted in the building's footprint, and **the captured region grows to bound it**
(`DeliverySite.unionAxis`). Cutting at a fixed height left canopy hanging in the sky, and that is
worse than it looks: orphaned leaves **decay**, decay happens whether or not the block was inside
the region, and a restore cannot put back what Minecraft deleted on its own. Caps (`max_clear_blocks`,
`max_region_side`) stop one delivery in a dark forest asking to snapshot half a chunk; when a cap
bites, whatever falls outside the region is **left standing rather than cleared**, because a few
leaves hanging for an hour is cosmetic and a block changed outside the snapshot is permanent.

Real relief still refuses: ground varying by more than `max_slope` across the footprint, or a
footprint more than `max_liquid_percent` water, is rerolled. Terraforming somebody's hillside cannot
be undone by putting blocks back; picking a different field can. Vanilla puts villages in forests
constantly, so a wood is a buildable field — the growth inside the region is cleared before placement
and restored with everything else afterwards. A shallow gap under the house is closed by a foundation
layer of the local surface material, filled only downward under blocks the structure placed and only
inside the snapshotted region.

*The terrain test runs while the waypoint is chosen*, not only at placement. Terrain does not change
during a delivery — a canopy will not grow in an hour — so a field that cannot be built on costs a
reroll rather than a walk. Only the container scan stays at placement, because somebody really can
put a chest down in the meantime. When placement is refused anyway, it is refused **once**, logged
with the reason (`building.debug`), and the player is told at the waypoint rather than left to find
an empty field.

*The undo record is the whole design.* Before the first block changes, the region is captured as a
**palette-plus-indices blob, GZIPped, into SQLite** (`courier_sites`) — a 28×28×16 box of mostly air
compresses to almost nothing, which is why this needs **no WorldEdit dependency**: the undo record
lives in the same database, the same transaction and the same backup as the job it belongs to. The row
is deleted **only once the restore has actually run**. A restore that cannot run right now — the usual
case, because a player who gives up walks home and the chunk unloads behind them — is parked as
`RESTORE_PENDING` and picked up by the next `ChunkLoadEvent` for that region, or failing that by the
**sweep on the next plugin enable**, which is the first thing the module does. A house that outlives
the plugin that knows how to remove it is the one outcome this is built to make impossible.

*Two things the building is not.* It is not **salvage** — edits inside a standing site are refused, both
because mining it would hand out free blocks the restore then deletes and because every changed block
is one the snapshot no longer describes. And it is not **loot**: village templates ship chests carrying
loot tables, and a building that reappears at the end of every run would turn that into a per-delivery
**item faucet** in an economy whose whole premise is that material enters only when somebody mines it —
so the furniture stays and the contents do not.

*What a site refuses to be built over.* One rule, four cases: **a snapshot restores block data and
nothing else**, so anything whose value lives elsewhere has to be refused rather than built over,
because the restore that makes the rest of this safe does not reach it.
- **Blocks with contents.** A chest is not recoverable from its block data. Somebody's unclaimed
  storage is still somebody's.
- **Entities that were placed.** Item frames, armour stands, chest minecarts, boats, displays. These are
  not in the snapshot *at all*, so anything that destroys one during the job destroys it for good.
  Wandering mobs are ignored — refusing a field because a cow walked through it would refuse most
  fields — which is why the test names armour stands before it exempts living entities, and why a horse
  (a `Vehicle` *and* an `InventoryHolder`) is deliberately not caught.
- **Anything HomeCraft already tracks there.** One query across the six tables that key on
  `(world, x, y, z)`. The case that matters is a **placed Mini**: a numbered, capped, uniquely-minted
  collectible. The snapshot would dutifully restore the head; it would not restore the owner's access
  during the job, and a failure in that window loses a copy that cannot be re-minted.
- **Block types only the world knows about** (`building.avoid_blocks`, player heads by default) — a head
  in a field is either a decoration or a death-storage grave, and neither should spend an hour behind a
  wall. Chest- and armour-stand-based graves are already caught by the two cases above.

The cheap half of that — the tracked-placement query and the entity sweep — also runs **while the
waypoint is being rolled**, so a bad spot is rerolled onto a different field rather than becoming a job
the player walks to and finds empty. The full block scan is thousands of reads, so it runs once, at
placement, where it is also the last word: an hour is long enough for somebody to put a chest down.

*The capture box is centred on where the building is put*, not on the waypoint — the two differ by
half the structure. Since Bukkit does not say which corner a rotation pivots around, the structure can
land in any quadrant around its placement origin, so the box is sized `(span + padding) × 2 + 1` about
that origin, which provably contains every rotation. `CaptureBoxTest` pins it: a block outside the
snapshot is captured by nothing and restored by nothing, so it stays in the world for good.

*The villager is a fixture, not a mob.* No AI, invulnerable, silent, persistent, profession matched to
the biome family, PDC-tagged with the job id. Right-click opens the hand-over and **never a trade
window** — a courier villager with vanilla trades would be an emerald pipeline no part of this economy
accounts for. Anyone else who clicks them gets a line of flavour text.

They spawn **facing away from the building**, which is to say towards whoever walks up, and rotation
still works with the AI off — so they turn to watch a nearby player, make an occasional noise, and say
something on hand-over. That is the cheap half of being alive; without it they read as a prop.

*Worldgen scaffolding is stripped after placement.* Village pieces are assembled by the jigsaw
generator, which consumes each `JIGSAW` block and replaces it with a recorded final state.
`Structure.place` runs none of that, so the markers survive as real, visible, op-interactable blocks —
one stood beside a front door on the first live delivery. They become air: **Bukkit exposes no way to
read the recorded final state** (`org.bukkit.block.Jigsaw` is an empty marker interface), and for the
village connectors these templates carry, air is what the generator would have left anyway.

*The building comes down when the player leaves, not on a clock.* Three rules, in order of how much
they matter: **never while somebody is inside the footprint** — restoring logs into the space a player
occupies suffocates them, and that defers even past the backstop; not before `linger_seconds`, so it
does not vanish in the same breath as the payout; then once nobody is within `restore_distance` (it is
out of sight, so it simply is not there next time they look) or `max_linger_seconds` has elapsed, which
covers somebody logging off on the doorstep. A timer alone was tried and is wrong by construction: it
can always run out while the player is standing in the doorway, which is what it did.

*The PC hand-in never goes away.* If placement fails for any reason — no template resolved, no flat
ground, a claim appeared since the waypoint was chosen — the run is still completable from the job
board at the drop-off. Nobody gets stranded four thousand blocks from home because a structure did not
paste.

**The package.** A courier run hands you a **crate** to carry — a player head, textured per band so
it reads as the size of the trip, and never an untextured one: a blank skin is a default Steve head,
so a band with no texture of its own borrows another band's — and the recipient wants it **in your hand**, not merely owned.
Trade runs get none: their cargo is the player's own goods, which is the point of that kind of run.

*It is worth nothing and must stay worth nothing.* Anything that survives its job is an item you can
mint on demand by taking Courier work, in an economy whose premise is that material enters only when
somebody mines it. So it is **unplaceable, unwearable, unstackable, unlistable**, cannot become cargo
for a second job, and is **destroyed the moment the delivery ends by any route** — handed over,
abandoned, expired, or the job gone entirely. A crate whose job is over is removed on sight, which is
also the backstop for any path that forgets to tidy up.

*Kept strictly apart from Minis.* A package is a player head; so is a Mini. They are told apart by
their PDC keys and never by looking like a head — a Mini by the `MINI_*` family, a package by
`hcm:courier_package` and nothing else. So a package is never minted, never given a serial, never in
the Museum, never counted in circulation, appraised or auctionable: every one of those paths asks for
a Mini reference, and a package has none.

*Unwearable is done in the item, not in events.* A player head is a helmet, and there are a great many
ways to put a hat on — the armour slot, shift-click, the hotbar swap key, right-clicking in the air, a
dispenser, an armour stand. The crate's own `equippable` component moves its slot off the head, which
disables all of them at once, wherever the item ends up. The armour-slot click is guarded as a second
line, because that component is a newer API than the plugin's floor and `PlayerArmorChangeEvent`
cannot be cancelled.

*Where it had to be refused, and where it did not.* The audit is smaller than it looks: the market
catalog does not buy player heads, the Auction and Vending House both demand a Mini reference, and the
Printer consumes a Card — so those reject a crate structurally, without a line of code. The genuinely
open surface is the **Marketplace Pallet**, which accepts an arbitrary held stack, and that is where
the explicit refusal lives, alongside the one for trade-run cargo.

*Death drops it, by default.* The grave is the recovery path and losing it for good is a real
consequence rather than a bug. `package.keep_on_death` exists because this is a family server.

**What losing it costs.** A crate is worth nothing, so the fee is not compensation for an item —
it is the only reason carrying one is a responsibility. It is **what the run would have paid on
foot** × `package.loss_multiplier`, which at the shipped 1.0 means a lost crate cancels the run
out. Deliberately *not* the actual payout: that depends on how the player travelled and is not
known until they arrive, and a price you cannot see until after you have lost the thing is a
surprise rather than a deterrent. It uses the job's **locked** distance, so the figure implied by
the board when the run was taken is the figure charged at the end of it.

*Charged for the crate, never for the failure.* The fee lands when a run ends **without the crate
coming back** — abandoned, expired, or closed any other way. A player who walks back with it still
in their bag has lost the payout, and that is the entire consequence. Handing it over is of course
free.

*Debt, and what it does and does not close.* What cannot be paid becomes debt, clamped at zero —
there is no credit with the courier office, and a negative number would turn paying one off into
banking against the next. It closes **the courier board and nothing else**. The market, the
Marketplace, the shops, the Pallets and every other way of earning stay open, because a debt that
locks a ten-year-old out of the economy with no way to work it off is the opposite of a
consequence. It is paid down at the PC, in part or in full; partial payment matters, since a debt
clearable only in one go traps somebody below the threshold indefinitely.

*A replacement, at the same price.* Bought at any PC for exactly the loss fee, which is what makes
the fee fair rather than merely punitive: without it a crate lost to a creeper four thousand blocks
out ends the run **and** charges for it, with nothing the player can do. Pricing the replacement
identically means there is no cheaper way out and no reason to prefer losing one over replacing it.
Never two crates for one job — the hand-over takes one, and the second would be an orphan wearing a
live job's id, the one case the stale-crate sweep cannot catch.

*Settled across a logout.* A run that ends with nobody there to look in cannot be settled on the
spot, so it is marked unsettled and resolved on the player's **next join** — where the question is
simply whether the crate came back with them. Without that, logging out would be a free way to lose
a crate: the cheapest and most obvious dodge, and precisely the behaviour the fee exists to
discourage. The check runs *before* the stale-crate sweep, because the sweep destroys exactly the
crates the settlement needs to see.

**Still open:** the Fragile and Perishable cargo modifiers, stubbed disabled in config.

---
## 4. Configuration Schema (sketch)
```yaml
store:
  name: "Crate"
  display_url: "www.Crate.craft"   # fake TLD on purpose (never resolves)
market:
  elasticity: 0.05        # NOW scaled to each item's floor..ceiling range (Phase 2.5.1)
  inertia: 0.2
  integrated_bulk: true   # price moves across a multi-unit order (Phase 2.5.1)
  # per-item floor / ceiling / initial_stock / max_stock in the catalog
shipping:
  mode: PERCENTAGE        # PERCENTAGE | FLAT
  tiers:
    rush:      { real_hours: 24, percent: 20, flat: 500 }   # "Rush" = 1-day
    two_day:   { real_hours: 48, percent: 10, flat: 250, prime_flat: true }
    three_day: { real_hours: 72, percent: 0,  flat: 0 }     # free
  locker: { enabled: true }   # holds deliveries when offline / inventory full
marketplace:                  # the Pallet/Crate player-to-player market
  fee:
    commission_percent: 5     # % cut per sale (money sink) — DECISION default
    daily_storage_fee: 0      # optional per-Pallet/day; 0 = off
  require_protected_land: true
  ban_list: [ BEDROCK, COMMAND_BLOCK, BARRIER, STRUCTURE_BLOCK, "*_SPAWN_EGG", JIGSAW ]
  departments: [ BLOCKS, FOOD, TOOLS, WEAPONS, ARMOR, REDSTONE, COLLECTIBLES, MISC ]
  category_overrides:         # relocate the few that auto-sort wrong
    HONEYCOMB: MISC
courier:                      # the Deliveries Site (§3.10) — a money faucet, not a market
  payout: { base: 4.0, per_block: 0.012, min_distance: 200, max_distance: 4000 }
  travel:  { foot: 1.00, mount: 0.85, boat: 0.80, ghast: 0.70, rail: 0.65, elytra: 0.45, creative: 0.00 }
  anti_teleport: { floor: 0.70, payout: 0.20 }   # <70% of the distance tracked pays 20%
  bands:
    local:     { min: 200,  max: 600,  per_day: 2 }
    regional:  { min: 600,  max: 1800, per_day: 2 }
    long_haul: { min: 1800, max: 4000, per_day: 1 }
  expire_minutes: 60
  turn_in_radius: 10
worlds:
  economy_enabled_worlds: [ world, resource ]   # sandbox: NO market/marketplace in creative (§11)
crafting:
  respect_town_perms: true    # the Workbench is retired (no recipe); the PC recipe lives under recipes:
recipes:                      # every craftable block: vanilla SHAPED 3x3, reloadable, recipe-book unlocked
  pc:      { shape: ["GGG","CKC","IQI"], ingredients: { G: GLASS_PANE, C: COPPER_BLOCK, K: COMPARATOR, I: IRON_BLOCK, Q: QUARTZ_BLOCK } }
  printer: { shape: ["IHI","CSC","PAP"], ingredients: { … } }
  vending: { … }   # two-tall block
  pallet:  { shape: ["SSS","TBT","SSS"], ingredients: { S: "#wooden_slabs", T: STICK, B: BARREL } }   # "#tag" = any item of the tag
  arcade:  { … }
  mailbox: { wood: { … X: "#planks" … }, light_blue: { … X: LIGHT_BLUE_DYE … }, black: …, white: …, purple: …, blue: …, orange: …, yellow: … }
skins:                        # Base64 head values; blank = the block's plain base material
  pc: "…", printer: "…", arcade: "…"
  pallet_empty: "…", pallet_used: "…"          # a placed Pallet swaps as listings come/go
  vending_lower: "…", vending_upper: "…"       # the Vending Machine is two heads tall
  mailbox: { wood: "…", light_blue: "…", black: "…", white: "…", purple: "…", blue: "…", orange: "…", yellow: "…" }
minis:
  pricing_mode: ESCALATING    # FIXED | ESCALATING (overridable per series)
  rarity_styles:              # assign the TIER; color + defaults follow (edit palette once)
    LEGENDARY: { pane: YELLOW,     name_color: gold,         glint: true,  default_cap: 5,   default_price: 50000 }
    EPIC:      { pane: PURPLE,     name_color: light_purple, glint: true,  default_cap: 20,  default_price: 15000 }
    RARE:      { pane: BLUE,       name_color: aqua,         glint: false, default_cap: 100,  default_price: 4000 }
    UNCOMMON:  { pane: GREEN,      name_color: green,        glint: false, default_cap: 500,  default_price: 800 }
    COMMON:    { pane: LIGHT_GRAY, name_color: gray,         glint: false, default_cap: -1,   default_price: 150 }
  categories: [ ANIMAL, FOOD, LETTER, SYMBOL, CHARACTER, VEHICLE, HOLIDAY, MISC ]   # your "Type" list (open/editable)
  series:
    - name: "Woodland Critters"
      rarity: COMMON
      entries:
        # category = the "Type" (subject); type = the form (HEAD | ARMOR_STAND)
        - { name, type: HEAD, category: ANIMAL, texture, cap: -1, price, craftable: false, recipe: [],
            wild_drop: { source: BLOCK_BREAK, block: COBBLESTONE, chance: 0.000001, natural_only: true } }
```
---
## 5. Data Model / Persistence
SQLite via JDBC. Tables:
- **Market:** per-item current price + **stock** + **max_stock**; **price-history** snapshots.
- **Orders / Locker:** player, items, shipping tier, cost paid, placed-at + deliver-at timestamps, status (in-transit / in-locker / collected).
- **Marketplace:** Pallet locations + owners; listings (item, price, qty, seller); accrued fees.
- **Minis:** per-type minted count + circulation; per-individual unique ID, current owner, provenance/price history; Vending Machine listings; Auction House listings + escrowed bids + close times.
- **Custom blocks:** placed PC / Mini Workbench / Vending Machine / Display Case / **Pallet** locations + owners.
- **Courier sites (§3.10):** one row per placed delivery building — the region's origin and size, the template and rotation, the doorstep, the recipient villager's id, and `snapshot`, a GZIPped blob of the **original** blocks. Written before the first block changes and deleted only once the restore has run.
- **Courier (§3.10):** one row per job — player, type, state, band, accepted-at world/coords, waypoint coords, the **locked** distance, trade-run cargo + quoted value, the movement-statistic **snapshot** taken at acceptance, the UTC epoch-day it counts against, and its expiry. Daily band caps are counted from these rows; there is no separate tally table.
- **Daily limits:** per-player sell + buy counters (per-item too), reset daily (UTC).
- **Live market (schema v32, §3.1 "The live market (0.33)"):** per-item drift and cooldown clocks (`market_sim_state`), one row per event (`market_events`), the seed, schedule and counters (`market_sim_meta`), the daily sim-money ledger (`market_sim_ledger`), each player's last-seen news and mute (`market_news_seen`), and cached real-world closes (`market_real_quotes`). It holds no stock and no prices: those stay in the market tables and are only ever read.
Everything survives restarts. **Back up the DB before every migration (see §11).**
---
## 6. Phased Build Plan (for Claude Code)
Build and test each phase before the next.
- **Phase 1 — Skeleton + PC + Mini Workbench ✅ done, merged.**
- **Phase 2 — Basic Market Engine ✅ done, merged** *(abstract demand counter — superseded).*
- **Phase 2.5 — Finite-Stock Market Revision ✅ done, merged** (real positive stock, out-of-stock, per-item starting stock, spread, daily sell limit, price-history).
- **Phase 3 — Crate Store + Market GUIs ✅ done, merged** (`[www.Crate.craft](https://www.Crate.craft)` behind the PC, shipping tiers → Locker, restart-safe timers, clickable market GUI).
- **Phase 3.1 — Rebrand + config ✅ done, merged (`v0.3.1`):** on-screen "Amazon"→**Crate**; config-driven `store.name`/`store.display_url`; DESIGN.md v11 committed. *(Pricing proportional-elasticity + integrated-bulk fix rode in with Phase 3's Part A — confirm with the buy-600 test.)*
  - **Still-open polish:** the **GUI x600→"64" quantity fix** (show the real amount in the item name/lore); cosmetics — `api-version` (still `1.21`), the "(Phase 1)" log label, PC computer-head texture (send a Base64 value to wire it in).
- **Phase 4 — Minis core ✅ done, merged (`v0.4.0`):** config-driven catalog (`minis:` — `rarity_styles`, `categories`, `series→entries`), rarity→style (coloured name, glint, provenance tooltip), **minting as the single source of truth** (per-copy UUID anti-dupe + Mint #), caps enforced, circulation tracked, **Museum & Shop GUI** (mint via Vault), migration v7.
  - **Phase 4 follow-ups (a "Phase 4b"):** Mini Vending Machine, Auction House, **Wild Drops**, posed **armor-stand** spawning, and the minecraft-heads.com **web-import**.
- **Phase 5 — The Crate Marketplace:** Pallets, universal listings (everything sellable), **departments + auto-categorization + ban list**, fees, Minis in Collectibles. *(Can be pulled earlier if "sell anything" is wanted before Minis.)*
- **Phase 6 — Market Web Dashboard (§3.7):** the live stock-market website. *(Extension: a **transactional web shop** — secure `/crate web` login + buy-from-browser, delivering to the Locker — builds on this same embedded server.)*
- **Phase 7 — In-Game Displays (§3.8):** wall-mounted `TextDisplay` price panels, holographic tickers, sign boards.
- **Courier — Deliveries Site (§3.10) ✅ Phases 1 and 2 done (v16–v17):** job board, three distance bands with daily caps, rolled waypoints, the statistic-blended travel multiplier, the anti-teleport floor, trade runs that sell cargo into the market at the live rate — and a **vanilla village house with a villager** at the far end, placed on approach and restored from a snapshot when the run ends. *The crate landed in v17.3 and was priced in v17.4 — carried in hand, destroyed at the end of every run, with a loss fee, courier debt and a PC-bought replacement. Still open: the complete-a-delivery quest verb this unblocks, and the Fragile/Perishable cargo modifiers.*
- **Future — more PC Sites (§2.2):** Towny plots Site, etc.
- **Phase 8 — Rewards & Arcade (§3.9):** tokens (login streaks/playtime), loot boxes/crates, lotto/scratch tickets, the pity exchange, and the Arcade installation at the Mall. Reuses the drop/rarity/cap tech — mostly content.
- **Phase 9–11 — Arcade as a place + earning sources (§3.9):** the Arcade was built from placeable, owned/protected, skinnable **machine blocks** you right-click to play — a **Crate Machine**, **Scratch-Ticket Booth**, **Pity Exchange Kiosk**, and **Token Counter**. *(v13: these are retired in favour of the single Arcade hub block — see §3.9; placed ones keep working.)* Token earning now has four sources: login streaks, playtime, **one-time achievements** (first Mini, first sale, first PC, first crate, first pack, $10k), and **daily/weekly quests** (`/hcm quests` — repeatable objectives like "sell $500 to the market", "print a Mini", "open a crate/pack" that pay tokens on completion and reset each day/week). Every earn shows a "+N token" toast. Minting still happens only through the cap-aware Printer pipeline.
Then: retire DynamicShopGUI now that Phase 3's market GUI is live (server-side removal — not a code task).
---
## 7. Decisions
**Resolved:**
- **Plugin name:** HomeCraft Management (repo: github.com/Dierks27/HomeCraftMgmt).
- **Online store name:** **Crate**, shown as **`[www.Crate.craft](https://www.Crate.craft)`** (fake TLD, safe). Fast tier = **Rush**; seller box = **Pallet**; delivery inbox = **Locker**.
- **Everything is sellable** via the Crate Marketplace (Pallets); the **house market stays lean** (curated staples only).
- **Auto-categorization** into departments via the game's item categories + tags, with admin override + **ban list**.
- **PC = a browser; features are Sites** (Crate now; Towny plots + dashboard later).
- **Minis** are sellable (Collectibles department) via Vending Machine (fixed) + Auction House (bidding).
- **Marketplace fee = small % commission** (default), optional per-Pallet daily storage fee.
- **Pricing:** finite-stock, **elasticity scaled to each item's range**, **integrated bulk pricing**, buy/sell spread, daily sell + buy limits with per-item caps.
- Minis: configurable mint caps, fixed/escalating pricing, per-entry craftable flag, custom Mini Workbench, admin-defined empty-by-default recipes.
- Shipping: real 1/2/3-day at 20% / 10% / free (percentage default; flat/Prime available).
**Still open:**
- **Marketplace pricing** — seller-set fixed price (rec) vs. optional dynamic drift.
- **PC protection** — respect Towny/WG perms (rec: yes).
- **Series concepts & rarity names** — creative call (ongoing).
---
## 8. Notes for the Implementer
- Collectibles cover **both heads and armor stands**; catalog is **admin-curated via checkmark import** (no blind sweep).
- Reference DynamicShopGUI's pricing for concepts; write original code — we're eliminating that dependency.
- Keep modules loosely coupled so phases ship independently; the PC's **Site launcher** keeps future features pluggable.
- All player-facing money flows through Vault.
- **Config everything (standing principle):** anything an admin might ever change lives in `config.yml` and **reloads live** (`/hcm reload`) — no hardcoded values. Hold this for every future phase.
- Do **not** touch QuickShop.
---
## 9. Integration Reference — how we hook every external system
> Verify exact signatures against the installed versions (the "formalities" pass).
### 9.1 Vault (economy) — required for all money
Grab the `Economy` service from Bukkit's ServicesManager on enable; use `has()`, `withdrawPlayer()`, `depositPlayer()` for orders, shipping, marketplace fees, Mini minting, vending, auction escrow, Mall rent. Soft-depend; disable money features + warn if absent.
### 9.2 Towny — the Mall + build permissions + (future) Towny Site
Use `TownyAPI`: resolve `TownBlock`/`Town`/`Resident` and check build permission before placing/using custom blocks (PC, Workbench, **Pallet**, Vending Machine); the Mall uses Towny's `/plot forrent`; the future Towny Site reads plots-for-sale data. Soft-depend.
### 9.3 WorldGuard / WorldEdit — region build permissions
Query the `RegionContainer` and test BUILD for the player before placement/interaction. Soft-depend.
### 9.4 LuckPerms — permissions
Declare nodes in `plugin.yml`; check `player.hasPermission(...)`. Node list in §10.
### 9.5 PlaceholderAPI — data display + in-game tickers
Register a `PlaceholderExpansion` (identifier `hcm`) exposing e.g. `%hcm_price_<item>%`, `%hcm_stock_<item>%`, `%hcm_trend_<item>%`, `%hcm_mini_minted_<id>%`, `%hcm_mini_circulation_<id>%`, `%hcm_order_status%`. Powers TAB/holograms/sign boards (§3.8). Soft-depend.
### 9.6 Native Paper/Bukkit APIs we rely on
- **Custom heads (Minis):** `PlayerProfile` + `PlayerTextures`.
- **Armor-stand Minis:** spawn + configure `ArmorStand` from stored data.
- **Item/block tagging:** `PersistentDataContainer` (Mini IDs, PC/Workbench/**Pallet** markers).
- **Auto-categorization:** item **creative-category / item-group** API + Minecraft item **tags** + property flags (verify exact 26.2 API).
- **GUIs:** `InventoryHolder` menus (PC Site launcher, Crate store, Museum & Shop, Vending Machine, Auction House, Pallet).
- **Displays:** wall-mounted `TextDisplay` price panels + holograms (text-display entities); PAPI powers tickers/TAB.
- **Recipes:** inside the Workbench GUI (config-driven), NOT vanilla recipes.
- **Timers:** Bukkit scheduler + durable SQLite timestamps.
- **Storage:** SQLite via JDBC.
---
## 10. Commands & Permission Nodes
**Commands (most interaction is block/GUI-based, admin commands aside):**
- `/hcm reload`, `/hcm admin …` (curate Minis, caps/prices/series, give items, Mall anchor, manage departments/ban list), `/hcm market …` (admin/test buy/sell/price/list/history).
- `/hcm give <printer|pc|vending|display|mailbox [variant]|pallet|arcade> [player]` and `/hcm give <card <id>|pack <id>|binder|filament <color> <n>> [player]` (admin). The Mailbox variant is one of `wood` (default), `light_blue`, `black`, `white`, `purple`, `blue`, `orange`, `yellow`. **Not given out any more:** the Auction House block (use `/hcm auction`) and the four Arcade machine blocks (the Arcade hub covers them).
- `/hcm auction` — the Mini Auction House (the only way to reach it).
- `/hcm museum [id]` — the browse-only Mini Museum; with an id, straight onto that Mini's detail card (the click target of found-broadcasts).
- `/hcm courier` — the Courier job board (§3.10). Also a Site on the PC.
- `/hcm market news [on|off]` (0.33, everyone, `hcm.market.price`) — "Right now" (HOT/DEAL with time left, and the season), then the last 8 headlines from 7 days with their ages; `on`/`off` turns market news in your own chat on or off. `/hcm market price <item>` also shows the usual price, the mood and any badge while the live market runs.
- `/hcm market news <item> <up|down|wanted> [percent] [quiet]` and `/hcm market sim status [item] | hot|deal <item> [percent] [hours] | stop <item|all> | reset <item|all> | pause | resume | preview <item> [days] | audit [days] | real status|test <symbol>|fetch` (0.33, admin, `hcm.market.sim`) — run and test the live market (§3.1). A forced flash is held to 10–25%, a forced HOT/DEAL starts at full strength (≤ 15%), and both keep the headroom check and are refused below 5%. `reset all` asks for a confirm within 10 s. `preview` draws one possible future from a throwaway random source and changes nothing. Every forced event is logged with who forced it and carries `source: admin`.
- `/minis` (or Mall block) — Museum & Shop.
- PC / Workbench / Vending Machine / Auction House / **Pallet** interaction = **right-click the block**.
- Avoid colliding with existing commands (e.g. QuickShop's `/qs finditem`).
**Permission nodes (LuckPerms-manageable):**
- `hcm.admin`
- `hcm.pc.use`, `hcm.pc.craft`
- `hcm.workbench.place`, `hcm.workbench.use`
- `hcm.market.order` (all — **sell** only); `hcm.market.buy` (**op** — buying by command skips shipping, so it must never be open to players); `hcm.market.list` (op — full catalog dump) / `hcm.market.price` (all — one item + `/hcm balance`), both children of the back-compat parent `hcm.use`
- `hcm.marketplace.sell` (place/use a Pallet), `hcm.marketplace.buy`
- `hcm.mini.sell`, `hcm.mini.craft` (`hcm.mini.buy` retired with the browse-only Museum)
- `hcm.vending.create`, `hcm.auction.list`, `hcm.auction.bid`
- **Player-facing openers (all `default: true`, v16):** `hcm.auction.list` (`/hcm auction`), `hcm.museum.use` (`/hcm museum [id]`), `hcm.arcade.use` (`/hcm arcade`), `hcm.quests.use` (`/hcm quests`), `hcm.courier.use` (`/hcm courier`). These subcommands open a player-facing GUI — read-only, token-only, or (the Courier) earn-only — and are the documented player route (§3.2); the nodes exist so an admin can withdraw one without removing the feature, not because they are restricted by default.
- `hcm.mall.rent` (or delegate to Towny)
- `hcm.market.limit.bypass` (op, a child of `hcm.admin`) — skips the daily buy/sell limits and per-item caps, **but not the live market's event limits** (the HOT/UP sell limit and the DEAL/DOWN buy limit bind everyone, §3.1)
- `hcm.market.sim` (0.33; op, a child of `hcm.admin`) — run and test the live market: forced flashes and HOT/DEALs, stop, reset, pause/resume, preview, audit, real-world tests
---
## 11. Economy Risks & Safeguards (holes to close before real players)
**Ordered roughly by urgency.**
1. **Creative-world money exploit (URGENT).** If a player can spawn free items in a creative world and sell them to the market/Marketplace for real money, the economy breaks instantly. **Done (v15):** the economy is sandboxed per world by `worlds.economy_enabled` (the server's main world on first run). Outside listed worlds there is no Market buy/sell, Marketplace listing/buying, pack purchase, Printer use, Vending sale, auction listing/bid, wild drop / natural spawn, or token earning, and HomeCraft blocks cannot be placed (existing ones answer "the economy is disabled in this world"); refusals are logged with player + world (`worlds.log_blocked_attempts`). Keep the creative world in its **own Multiverse-Inventories group** so items can't cross into survival (README shows the pairing).
2. **Recipes empty = loop locked (URGENT).** The Workbench and PC recipes ship empty, so *nothing is craftable* until the admin fills them. Define the Mini Workbench + PC recipes before the daughter can start.
3. **Mini dupe protection (Phase 4).** The "finite mint" promise breaks if Minis can be duplicated. Guard against item-dupe glitches (periodic audit: total minted == ledger) and ensure **GravesX** returns a dead player's Mini cleanly without duplicating it.
4. **Delivery when offline / inventory full.** Solved by the **Locker** (§3.2) — orders wait there instead of dropping/vanishing. Same for Marketplace deliveries.
5. **CoreProtect down on 26.2.** No block-logging/rollback right now — real grief exposure on a kids' server. Find a 26.2-compatible logger/fork before friends join. *(Not a plugin task; server-side.)*
6. **Back up the SQLite DB before every migration.** It holds market state, orders, marketplace listings, and Mini provenance — the server's "money." Auto-backup like Towny/QuickShop do. **Done (v15):** a file copy lands in `plugins/HomeCraftManagement/backups/hcm-<timestamp>-pre-migration.db` before any schema migration; a scheduled backup (`backups.interval_hours`, default 24) uses SQLite's online backup API and prunes beyond `backups.keep` (14); `/hcm backup now` writes one on demand. Every backup is logged with size and path.
7. **Starting capital + money sinks.** Ensure new players can earn a first stake; keep sinks healthy (shipping fees, Mall rent, **Marketplace commission**) so currency holds value.
8. **BlueMap resources not accepted.** Live map won't render until `accept-download: true` in `plugins/BlueMap/core.conf` → `/bluemap reload`. *(Server-side; relevant since the Phase 6 dashboard shares that spirit.)*
9. **The two-currency rule — tokens never become money, and never stand in for it (tightened in 0.31.0).** Dollars buy things you keep; tokens buy fun. **No Arcade path takes or pays dollars:** the money Scratch Ticket and crate `paid_odds` are gone (config_revision 14 removes them, logging the old table). **Tokens → dollars is forbidden by code:** a crate reward may only be a Card (`card`/`mini`), `pack`, `filament`, `tokens`, `prize` or `trail`, and `money` / `item` rewards are rejected at load; every item bought or won with tokens — boosts, radar, lure, firework, hats, trophies — carries **`hcm:token_prize`**, and the house market, Pallets, vending machines, the Auction House and Courier cargo refuse it ("Arcade prizes can't be sold."), including inside a shulker box or bundle, and it cannot be crafted, brewed or traded to a villager into something that could be sold. Cards, packs, filament and HomeCraft blocks stay untagged: they only ever become Minis. **No token perk touches a dollar flow** — no Courier extras or rerolls, no shipping discounts, no market-limit raises, no printer-fee waivers. **The currencies meet in exactly two places:** a **pack's token price** (the same pack sells for dollars or tokens) and **Card trade-in** (a Card that may have been bought with dollars becomes 3–50 tokens) — a small, one-way trickle into tokens, never out. A pack that comes up short refunds in whichever currency bought it. Daily per-item caps (~2% of `full_stock` to sell, ~4% to buy) and the $5,000/day money limits keep any single player from moving a price more than a few percent a day.
10. **Closed in 0.31.1: every daily limit rolls over at local midnight.** The market's and the Courier's daily money limits now use `clock.time_zone` (`GameClock.dayKey()`), like the Arcade. On the day it ships, the day key changes shape once: a limit used after 7 PM Central the evening before may count again, or not, for that one evening.
11. **Live-market bounds (0.33).** Prices that move on their own are a money faucet unless the bounds are in the code rather than the config, so they are. **`M ∈ [0.75, 1.25]` in code**: `SimLimits` clamps it, `OrderMath` clamps it again (a NaN or infinite mood reads as exactly 1.0), and config can only narrow the band. Drift is held to ±8%, HOT/DEAL to 15%, news to 25% and per-item `volatility` to 1.5, each clamped with a WARN naming the key. **The sim never writes stock and never moves the balanced price.** No heal, no deliveries, no restock on a timer, and a 90-day soak test checks stock is unchanged. **Predictable layers stay under half the spread:** seasons and real-world prices together are held to min(4.5%, 45% of the spread), so the largest predictable swing (1.045/0.955 = 1.094) is below the round-trip cost (1.05/0.95 = 1.105). Nobody can buy before a season and sell after it for a sure profit. **Event limits bind ops:** `hcm.market.limit.bypass` (which every op gets through `hcm.admin`) does not lift the HOT/UP sell limit or the DEAL/DOWN buy limit. It does still lift every normal cap, so drift and the predictable layers (at most 12.5% together) apply to an op's unlimited volume. The README's must-do is to switch the bypass off on the owner's own account (owner decision). **Ledger audit:** every trade at a moved price books its difference from the same trade at `M = 1` (`market_sim_ledger`), and `/hcm market sim audit` shows the real number, not an estimate. The bound it should stay inside is a plausible worst day of about $548 extra for one player selling every daily cap, and a player at the $5,000/day money cap earns the same total either way. **`enabled: false` is 0.32 exactly** (`M ≡ 1.0`, pinned by `OrderMathTest` against a verbatim copy of the 0.32 order code).
