# HomeCraft Management — Plugin Design Specification (v17)
> **Purpose of this document:** the build spec for a custom Paper plugin. It is written to be handed to Claude Code (or any implementer) as the source of truth. Design decisions still open are marked **[DECISION]** with a recommended default.
>
> **v11 changelog:** Rebranded the online store from "Amazon" to **Crate** (`[www.Crate.craft](https://www.Crate.craft)`), with **Rush** (fast shipping), the **Pallet** (player seller box) and the **Locker** (delivery holding). Added the **Crate Marketplace** (universal player-to-player selling — *everything* is sellable, incl. Minis), **auto-categorization into departments** using the game's own item categories, an **admin ban list**, and the **PC-as-a-browser / "Sites"** architecture. Added **in-game economy displays** (TVs/tickers/boards) and a consolidated **Economy Risks & Safeguards** section. Recorded the **Phase 2.5.1 pricing fix** (proportional elasticity + integrated bulk pricing). Marked Phases 2.5 and 3 done.
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
### 3.8 In-Game Economy Displays (TVs, Tickers & Boards) (NEW)
Bring the stock-market feel into the world itself — physical displays in the Mall.
- **The price "TV" = a flat wall-mounted `TextDisplay` panel.** `/hcm display tv <commodity> [scale]` mounts one tracked `TextDisplay` flat against the wall you're looking at — commodity name, a big current price, the ▲/▼ trend with %, and stock (the same content a sign board / the `hcm` PlaceholderAPI expansion produces), live-updated on the refresh timer. It reads as a *screen*: a solid dark opaque background, `FIXED` billboard (stays flat, never rotates to face the player), scaled up via the display transformation (`displays.tv.scale`, or the per-panel `[scale]` arg), centred text, nudged just off the wall face. **Crisp Minecraft font — no maps, no `ItemDisplay`, no floating item.** One placement = exactly one `TextDisplay`, persisted (kind `TV`, facing = wall face, data = scale) and restored (not duplicated) on reload/chunk-load; removed with `/hcm display remove` and swept by `/hcm display cleanup`. *(The earlier map-on-item-frame "map-TV" and the browser-link "TV block" were both retired: painting text pixel-by-pixel onto a 128×128 low-palette `MapView` was chunky and kept breaking to black, and a later rework leaked unremovable spinning `ItemDisplay` holograms. A `TextDisplay` renders real, crisp font — the text was never the problem, the map was.)*
- **Holographic tickers = floating text.** Expose live values via **PlaceholderAPI** (`%hcm_price_<item>%`, trend arrow) and render them as text-display entities / holograms above market stalls.
- **Stock board = wall of auto-updating signs** — one row per commodity with price + ▲/▼. Simple, instantly readable.
- **TAB / scoreboard** — optionally show a price in the tablist/sidebar.
- **Data feed:** the same price-history the dashboard uses (already logged since Phase 2.5 via `/hcm market history`).
### 3.9 Rewards, Tokens & the Arcade (NEW)
> **Reconciled with §11 #9 in v16.** This section predates the v15 two-currency rule and
> described a crate economy that no longer exists. **Where this section and §11 #9 disagree,
> §11 #9 wins** — the text below has been corrected to match the code.

A wholesome **arcade** loop that rewards showing up and playing — **all earned in-game, never bought with real money** (keeps it a fun arcade, not gambling — right for a kids' server).
- **Tokens (soft currency, earned by playing):** **login streaks** (an escalating per-day reward, one claim per real day), **playtime milestones**, **one-time achievements**, and **daily/weekly quests**. Configurable sources/amounts. Every earn runs through one path (`ArcadeService.award`) so all of them get the same "+N token" feedback, and every earn is gated by the per-world economy sandbox (§11 #1) — tokens cannot be farmed in a creative world.
- **Quests (v16) — the verb pool:** daily and weekly objectives that pay tokens. Two kinds, and the difference is where the number comes from. **Pushed** types (`SELL_MARKET`, `OPEN_CRATE`, `PRINT_MINI`, `OPEN_PACK`, `SCRATCH`) are recorded by gameplay hooks already in the code. **Pulled** types (`CATCH_FISH`, `KILL_HOSTILES`, `BREED_ANIMALS`, `TRADE_VILLAGER`, `TRAVEL_ON_FOOT`) are read from **vanilla statistics**, polled every 30s for online players — one task instead of a listener per verb, and the server was already counting all of it. A pulled quest banks the **difference between polls**, never a lifetime total, so it cannot credit what a player did before the period opened and a stretch in an economy-disabled world (§11 #1) is stepped over rather than banked. Distance targets are written in **blocks**; walking, sprinting and sneaking count, a horse or boat or elytra does not. **Selling is deliberately a minor line** — it was 40% of the token reward on offer and the largest single objective in both periods, which rewarded the grind the rest of the economy is trying to move away from; it is now 13% and the smallest. **Weeks roll over on `arcade.quests.week_starts` (Monday by default)**, not on the Thursday that `epochDay / 7` silently produced.
- **Loot Boxes / Crates:** spend tokens to open a themed crate for a **weighted-random reward**. **A crate may only pay `card`, `mini`, `pack`, `filament` or `tokens`** — a `money` or `item` reward is **rejected at config load with a warning** (§11 #9), since either would turn playtime into Vault money. **`card` and `mini` are the same reward:** both hand out that Mini's **Card**, printed into a graded Mini later at a Printer (§3.5). *Nothing in the Arcade mints a finished Mini.* Either may name a fixed id **or a `tag:` pool** weighted by rarity, and both are **cap-aware** — a Card type that is capped out drops out of the table and the crate re-rolls rather than paying nothing.
- **Prize Counter (v16) — the known-outcome half:** fixed-price token purchases (`arcade.prizes`), reached from the Arcade hub. A crate is a **pull**; a prize is a **price** — you see exactly what you get before you spend. That is what gives tokens a *floor* value rather than only an expected one, and it gives a player who does not enjoy gambling somewhere to spend what they earned. Rows pay **filament** (the buyer picks the colour, since a Card names the colours it needs), a **HomeCraft block**, or a **sealed Card pack** — never money and never a market good, so §11 #9 holds. The item is built before anything is charged, so a prize that cannot be produced right now costs nothing.
- **Pity / guaranteed exchange (anti-frustration):** spend **`arcade.pity.tokens`** (25 by default) for a **guaranteed Rare+ Card** instead of gambling, so a bad-luck streak never fully burns you. (Your idea — and it's genuinely good design.) The floor guarantees the **minimum** rarity; within the eligible set the pick is **weighted by `minis.loot.rarity_weights`**, exactly like crates and wild drops, so a Legendary stays rare instead of becoming as likely as a Rare.
- **Lotto / scratch tickets:** glinty ticket items with randomized payouts — a **money** sink priced in **money** (`arcade.lotto.ticket_cost_money`), not a token sink. It is the one part of the Arcade that still takes and pays Vault money.
- **Physical home — the Arcade at the Mall:** a dedicated installation (an **anchor tenant** alongside the Museum & Auction House) where players redeem tokens, open crates, and scratch tickets — a destination that makes a big pull an *event*. *(Framed as an Arcade, not a casino: earned tokens, not real money.)*
- **Hub only (v13):** the Arcade is a single placeable, craftable **Arcade Machine** hub block (`/hcm give arcade`, `recipes.arcade`, skinned via `skins.arcade`) whose GUI holds crates, pity, scratch tickets and the token counter. The separate Crate Machine / Scratch-Ticket Booth / Pity Exchange / Token Counter blocks are **retired**: they are no longer given, crafted or listed, but any already placed keep working (their `CustomBlockType`s and handlers stay in code).
- **Config:** token sources/amounts, per-crate weighted loot tables (cap-aware), pity threshold, cooldowns.
Leans entirely on tech we're already building (drop-roll weighting, rarity/glint, mint-cap enforcement) — so it's mostly *content* on top of existing systems.
---
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

*Placement rejects rather than flattens.* If the ground under the footprint varies by more than
`max_slope`, the waypoint is rerolled. Terraforming somebody's hillside cannot be undone by putting
blocks back; picking a different field can. A shallow gap under the house is closed by a foundation
layer of the local surface material, filled only downward under blocks the structure placed and only
inside the snapshotted region, so every block it adds is one the restore takes away.

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

*The villager is a fixture, not a mob.* No AI, invulnerable, silent, persistent, profession matched to
the biome family, PDC-tagged with the job id. Right-click opens the hand-over and **never a trade
window** — a courier villager with vanilla trades would be an emerald pipeline no part of this economy
accounts for. Anyone else who clicks them gets a line of flavour text.

*The PC hand-in never goes away.* If placement fails for any reason — no template resolved, no flat
ground, a claim appeared since the waypoint was chosen — the run is still completable from the job
board at the drop-off. Nobody gets stranded four thousand blocks from home because a structure did not
paste.

**Still Phase 1 in scope:** a plain courier run carries no physical crate item.

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
- **Courier — Deliveries Site (§3.10) ✅ Phases 1 and 2 done (v16–v17):** job board, three distance bands with daily caps, rolled waypoints, the statistic-blended travel multiplier, the anti-teleport floor, trade runs that sell cargo into the market at the live rate — and a **vanilla village house with a villager** at the far end, placed on approach and restored from a snapshot when the run ends. *Still open: a real crate item for plain courier runs, and the complete-a-delivery quest verb this unblocks.*
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
- `/minis` (or Mall block) — Museum & Shop.
- PC / Workbench / Vending Machine / Auction House / **Pallet** interaction = **right-click the block**.
- Avoid colliding with existing commands (e.g. QuickShop's `/qs finditem`).
**Permission nodes (LuckPerms-manageable):**
- `hcm.admin`
- `hcm.pc.use`, `hcm.pc.craft`
- `hcm.workbench.place`, `hcm.workbench.use`
- `hcm.market.order`; `hcm.market.list` (op — full catalog dump) / `hcm.market.price` (all — one item + `/hcm balance`), both children of the back-compat parent `hcm.use`
- `hcm.marketplace.sell` (place/use a Pallet), `hcm.marketplace.buy`
- `hcm.mini.sell`, `hcm.mini.craft` (`hcm.mini.buy` retired with the browse-only Museum)
- `hcm.vending.create`, `hcm.auction.list`, `hcm.auction.bid`
- **Player-facing openers (all `default: true`, v16):** `hcm.auction.list` (`/hcm auction`), `hcm.museum.use` (`/hcm museum [id]`), `hcm.arcade.use` (`/hcm arcade`), `hcm.quests.use` (`/hcm quests`), `hcm.courier.use` (`/hcm courier`). These subcommands open a player-facing GUI — read-only, token-only, or (the Courier) earn-only — and are the documented player route (§3.2); the nodes exist so an admin can withdraw one without removing the feature, not because they are restricted by default.
- `hcm.mall.rent` (or delegate to Towny)
- `hcm.market.limit.bypass`
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
9. **The two-currency rule (v15) — tokens never become money.** Money → tokens is a **capped, one-way sink** (paid crate odds, the money fee on public printers); tokens → anything sellable to the house is **forbidden by code**: a crate reward may only be `card`, `mini` (also a Card), `pack`, `filament` or `tokens`, and a `money` / `item` reward is rejected at config load with a warning. Filament is craftable but not sellable to the house, Cards and packs only ever become Minis (never market goods), so no loop exists from playtime/login tokens back to Vault money. Daily per-item caps (~2% of `full_stock` to sell, ~4% to buy) and the $5,000/day money limits keep any single player from moving a price more than a few percent a day.
