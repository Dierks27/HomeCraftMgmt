# HomeCraft Management — Plugin Design Specification (v11)

> **Purpose of this document:** the build spec for a custom Paper plugin. It is written to be handed to Claude Code (or any implementer) as the source of truth. Design decisions still open are marked **[DECISION]** with a recommended default.
>
> **v11 changelog:** Rebranded the online store from "Amazon" to **Crate** (`[www.Crate.craft](https://www.Crate.craft)`), with **Rush** (fast shipping), the **Pallet** (player seller box) and the **Locker** (delivery holding). Added the **Crate Marketplace** (universal player-to-player selling — *everything* is sellable, incl. Minis), **auto-categorization into departments** using the game's own item categories, an **admin ban list**, and the **PC-as-a-browser / "Sites"** architecture. Added **in-game economy displays** (TVs/tickers/boards) and a consolidated **Economy Risks & Safeguards** section. Recorded the **Phase 2.5.1 pricing fix** (proportional elasticity + integrated bulk pricing). Marked Phases 2.5 and 3 done.

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
- **Economy Dashboard Site** *(future, §3.7)* — the live stock-market view, in-game.
- Later: mail, a town directory, etc.

**Implementation note:** design the PC's root menu as a **Site launcher** (a grid/list of Sites) rather than opening the store directly, so new Sites slot in without reworking the PC. Each Site is a loosely-coupled GUI module. `[www.Crate.craft](https://www.Crate.craft)` is the display "URL" shown in the store header — a **fake TLD (`.craft`) on purpose**, so nothing resolves to a real website (safe on a kids' server).

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
- **Daily per-player sell limit (anti-whale):** cap money and/or units a player can sell/day; resets daily; per-player persistent; optional per-rank ceiling; GUI shows "resets in Xh."
- *(Optional — DECISION):* a **daily buy limit / anti-cornering** cap. **Recommendation: launch WITHOUT it; add only if hoarding becomes a real problem.**

**Curated & lean on purpose.** The house commodity list is a **deliberately small** set of core resources (ores, staples) — NOT every item in the game. Everything else is sold player-to-player via the **Crate Marketplace (§3.2b)**, which avoids turning farmable items (bread, etc.) into house-money faucets. Rule of thumb: **raw/limited stuff → house market; farmable/crafted/long-tail stuff → the Marketplace.**

**Scope:** these dynamic prices are the **Crate house-market side ONLY.** QuickShop sets its own prices and is untouched. This market **replaces DynamicShopGUI entirely.**

### 3.2 Crate — Ordering & Shipping

- **Access:** ONLY through a placed PC (see §3.3), via the `[www.Crate.craft](https://www.Crate.craft)` Site. No global command opens it.
- **GUI-first principle (whole plugin):** ALL player buying/selling/browsing happens in **clickable GUIs** — players **never type market commands.** `/hcm ...` commands are **admin-only**.
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
- Represented by a computer-textured custom head/block. *(Cosmetic TODO: give it a proper computer/monitor head texture instead of a Steve head.)*
- It is the **gate** to all online commerce — no PC, no Crate.
- **[DECISION]** Respect Towny/WorldGuard build perms so only owners/residents place & use in claimed land. **(Rec: yes.)**

### 3.4 Collectibles System — "Minis" (Heads & Armor Stands)

The showpiece. Branded **"Minis."** Two Mini types: **decorative heads** and **posed armor stands** (from minecraft-heads.com). Both are ultra-rare collectibles and status symbols; everything below (series, rarity, mint caps, Museum & Shop, circulation) applies to **both**.

**Curated, never swept — hard requirement.** The catalog is **hand-picked by the admin** via a **checkmark selective-import** — never a wholesale sweep of the site.

- **Series:** every Mini belongs to a named series (e.g., "Woodland Critters," "Legendary Relics"), themed and released over time.
- **Rarity tiers:** Common → Uncommon → Rare → Epic → Legendary.
- **Mint cap (fully configurable):** uncapped or hard-capped per series/rarity. A minted-out cap becomes **trade-only**. Every behavior is a toggle.
- **Museum & Shop:** displays each Mini with name, series, rarity, **minted X / cap**, **# in circulation**. Buying = **minting** a new copy up to the cap at **dynamic scarcity prices**. Reachable **in person at the Mall** (instant) and **online via Crate** (dynamic + shipping).
- **Pricing:** **fixed-per-rarity** *or* **escalating**, per series/rarity.
- **Commons:** uncapped & cheap by default.
- **Craftable option (per entry):** a `craftable` flag; if enabled the admin defines a recipe (crafted at the Workbench) and **crafted copies still count toward the mint cap/circulation**.
- **Texture source:** curated from minecraft-heads.com [heads](https://minecraft-heads.com/) and [armor stands](https://minecraft-heads.com/armor-stands). Data-driven per entry (texture/config + metadata). **The one field we actually need is the head's `Value` (Base64 texture string)** — we render it natively via `PlayerProfile`/`PlayerTextures`; no Head Database plugin and no runtime dependency on the site.

**Organizing & displaying Minis — Type, Rarity color & filters (NEW).** Minis need **three independent tag dimensions**, because to the game every Mini is just a `PLAYER_HEAD` and can't be auto-sorted like normal items — we tag them ourselves:
- **Type / category (admin-defined open list):** the *subject* — Animal, Food, Letter, Symbol, Character, Vehicle, Holiday, … You define the list and assign a primary Type per Mini. *(Stored as `category` in config to avoid clashing with the `type: HEAD|ARMOR_STAND` form field.)*
- **Series:** the release/collection grouping (existing).
- **Rarity:** Common → Uncommon → Rare → Epic → Legendary — **and rarity drives the visual style automatically, so you assign the tier, not the color.**

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

**Wild Drops — rare loot-table minting (NEW, high-want feature).** Beyond buying and crafting, a Mini can be configured to **drop randomly from the world** — e.g. a "Cobblestone Mini" that pops out of mined cobblestone at a tiny chance (say 0.0001%). This makes Minis feel like **treasure you stumble on**, not just something you buy.
- **Per-Mini drop config:** a **source** (block break / mob kill / fishing / chest loot) + a **drop chance**. Multiple sources allowed per Mini.
- **A wild drop MINTS a new copy** — so it **respects the mint cap** (stops dropping once minted out) and **counts toward circulation**, keeping the finite promise honest. The Workbench/mint pipeline (§3.5) stays the single source of truth: a drop is just another mint path.
- **Anti-farm guard (critical):** only **naturally-generated, non-player-placed** blocks roll the drop — track placed blocks (PDC/placed-block set) and ignore silk-touch-and-replace, so nobody cheeses it with a place-break cobblestone farm. Same idea for spawner-farmed mobs if mob drops are enabled.
- **Import tie-in:** minecraft-heads.com exports a **loot-table JSON per head** — we can lift the texture value from it and reference its structure, but the *drop roll + cap enforcement live in our plugin* (a `BlockBreakEvent`/entity-death listener), not a raw vanilla loot table, because vanilla loot tables can't enforce a mint cap.

### 3.5 Crafting System & the Mini Workbench (custom bench)

Crafting the **PC** and any **Mini flagged `craftable`** happens at a dedicated custom **Mini Workbench** — not the vanilla table — so we can **enforce mint caps + track every mint at craft time**. A placeable block; right-click opens a custom crafting GUI. **All recipes admin-defined & editable — empty by default (nothing craftable until set).** *(Live log confirms: "Mini Workbench recipe is empty — not craftable until configured." → the admin must fill the Workbench + PC recipes before players can build them — see §11.)*

### 3.6 The Mall — Spawn Market District

A central **commercial district at spawn.**
- **Rentable stalls (paid):** rent a Mall stall for money for a prime central spot (holds your QuickShops, Mini Vending Machines, Pallets).
- **Anchor tenants:** the official **Mini Museum & Shop**, the **Mini Auction House**, and the **Arcade** (§3.9 — tokens, loot boxes, lotto) all live here.
- **Free vs prime:** **town shops = FREE** (your land, low traffic) vs **Mall stalls = PAID RENT** (central, high traffic). A real commercial-real-estate decision — the same free-vs-paid logic mirrored by QuickShop-vs-Pallet online.
- **Implementation — reuse Towny:** the Mall = an **admin Towny town at spawn** using Towny's own plot renting. Minimal custom code.

### 3.7 The Market Web Dashboard (the "trading floor")

A **live web page for the economy** — the BlueMap of your market. Shows every commodity: current price, **price-history chart** (ticker/candlestick), **stock on hand**, 24h change/trend. Served by the plugin itself (embedded web server + static HTML/JS frontend) on its own port; tunnel the port (second Playit tunnel) for outside access. Data source: the market engine's live stock + price + **price-history** tables (§5). Also surfaced **in-game as a Site** on the PC (§2.2).

### 3.8 In-Game Economy Displays (TVs, Tickers & Boards) (NEW)

Bring the stock-market feel into the world itself — physical displays in the Mall.

- **"TV screens" = maps in item frames.** Render live pixels (charts, numbers, scrolling ticker) onto map items via a `MapRenderer`, refreshed on a timer; tile a grid of framed maps into a big-screen chart wall. The premium version of the idea.
- **Holographic tickers = floating text.** Expose live values via **PlaceholderAPI** (`%hcm_price_<item>%`, trend arrow) and render them as text-display entities / holograms above market stalls.
- **Stock board = wall of auto-updating signs** — one row per commodity with price + ▲/▼. Simple, instantly readable.
- **TAB / scoreboard** — optionally show a price in the tablist/sidebar.
- **Data feed:** the same price-history the dashboard uses (already logged since Phase 2.5 via `/hcm market history`).

### 3.9 Rewards, Tokens & the Arcade (NEW)

A wholesome **arcade** loop that rewards showing up and playing — **all earned in-game, never bought with real money** (keeps it a fun arcade, not gambling — right for a kids' server).

- **Tokens (soft currency, earned by playing):** sources include **login streaks** (e.g., 3 days in a row → 1 token), playtime milestones, events, and achievements. Configurable sources/amounts; anti-abuse cooldowns (one streak reward per real day).
- **Loot Boxes / Crates:** spend tokens to open a themed crate for a **weighted-random reward** — cash, items, or (rarely) a **Mini**. **Cap-aware:** a Mini prize mints against its cap and stops appearing once minted out. Reuses the wild-drop weighting engine + rarity/glint visuals.
- **Pity / guaranteed exchange (anti-frustration):** spend **N tokens (e.g., 3) for a guaranteed Rare+** instead of gambling — so a bad-luck streak never fully burns you. (Your idea — and it's genuinely good design.)
- **Lotto / scratch tickets:** glinty ticket items with randomized payouts — a money sink + hype.
- **Physical home — the Arcade at the Mall:** a dedicated installation (an **anchor tenant** alongside the Museum & Auction House) where players redeem tokens, open crates, and scratch tickets — a destination that makes a big pull an *event*. *(Framed as an Arcade, not a casino: earned tokens, not real money.)*
- **Config:** token sources/amounts, per-crate weighted loot tables (cap-aware), pity threshold, cooldowns.

Leans entirely on tech we're already building (drop-roll weighting, rarity/glint, mint-cap enforcement) — so it's mostly *content* on top of existing systems.

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

worlds:
  economy_enabled_worlds: [ world, resource ]   # sandbox: NO market/marketplace in creative (§11)

crafting:
  workbench_recipe: [ ... ]
  pc_recipe: [ ... ]          # empty = not craftable yet
  respect_town_perms: true

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
- **Daily limits:** per-player sell (and optional buy) counters, reset daily.

Everything survives restarts. **Back up the DB before every migration (see §11).**

---

## 6. Phased Build Plan (for Claude Code)

Build and test each phase before the next.

- **Phase 1 — Skeleton + PC + Mini Workbench ✅ done, merged.**
- **Phase 2 — Basic Market Engine ✅ done, merged** *(abstract demand counter — superseded).*
- **Phase 2.5 — Finite-Stock Market Revision ✅ done, merged** (real positive stock, out-of-stock, per-item starting stock, spread, daily sell limit, price-history).
- **Phase 3 — Crate Store + Market GUIs ✅ done, merged** (`[www.Crate.craft](https://www.Crate.craft)` behind the PC, shipping tiers → Locker, restart-safe timers, clickable market GUI).
- **Phase 2.5.1 / 3.1 — Pricing & GUI polish (in verification):**
  - **Proportional elasticity** (scale to each item's range — kill the cobblestone whipsaw) + **integrated bulk pricing** (price moves across a multi-unit order).
  - **GUI quantity fix:** show the real amount in the item **name/lore** (the stack-count badge caps at 64, so an x600 order wrongly shows "64").
  - Cosmetics: bump `api-version` if logged legacy; fix the "(Phase 1)" enable-log label; PC computer-head texture.
- **Phase 4 — Minis Collectible System:** series/rarity/caps, Museum & Shop, minting, circulation + **dupe protection**, curated import, Vending Machine + Auction House.
- **Phase 5 — The Crate Marketplace:** Pallets, universal listings (everything sellable), **departments + auto-categorization + ban list**, fees, Minis in Collectibles. *(Can be pulled earlier if "sell anything" is wanted before Minis.)*
- **Phase 6 — Market Web Dashboard (§3.7):** the live stock-market website.
- **Phase 7 — In-Game Displays (§3.8):** map-TVs, holographic tickers, sign boards.
- **Future — more PC Sites (§2.2):** Towny plots Site, etc.
- **Phase 8 — Rewards & Arcade (§3.9):** tokens (login streaks/playtime), loot boxes/crates, lotto/scratch tickets, the pity exchange, and the Arcade installation at the Mall. Reuses the drop/rarity/cap tech — mostly content.

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
- **Pricing:** finite-stock, **elasticity scaled to each item's range**, **integrated bulk pricing**, buy/sell spread, daily sell limit.
- Minis: configurable mint caps, fixed/escalating pricing, per-entry craftable flag, custom Mini Workbench, admin-defined empty-by-default recipes.
- Shipping: real 1/2/3-day at 20% / 10% / free (percentage default; flat/Prime available).

**Still open:**
- **Daily buy limit / anti-cornering** — *rec: launch without it.*
- **Marketplace pricing** — seller-set fixed price (rec) vs. optional dynamic drift.
- **PC protection** — respect Towny/WG perms (rec: yes).
- **Series concepts & rarity names** — creative call (ongoing).

---

## 8. Notes for the Implementer

- Collectibles cover **both heads and armor stands**; catalog is **admin-curated via checkmark import** (no blind sweep).
- Reference DynamicShopGUI's pricing for concepts; write original code — we're eliminating that dependency.
- Keep modules loosely coupled so phases ship independently; the PC's **Site launcher** keeps future features pluggable.
- All player-facing money flows through Vault.
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
- **Displays:** `MapView`/`MapRenderer` for map-TVs; text-display entities/holograms via PAPI.
- **Recipes:** inside the Workbench GUI (config-driven), NOT vanilla recipes.
- **Timers:** Bukkit scheduler + durable SQLite timestamps.
- **Storage:** SQLite via JDBC.

---

## 10. Commands & Permission Nodes

**Commands (most interaction is block/GUI-based, admin commands aside):**
- `/hcm reload`, `/hcm admin …` (curate Minis, caps/prices/series, give items, Mall anchor, manage departments/ban list), `/hcm market …` (admin/test buy/sell/price/list/history).
- `/minis` (or Mall block) — Museum & Shop.
- PC / Workbench / Vending Machine / Auction House / **Pallet** interaction = **right-click the block**.
- Avoid colliding with existing commands (e.g. QuickShop's `/qs finditem`).

**Permission nodes (LuckPerms-manageable):**
- `hcm.admin`
- `hcm.pc.use`, `hcm.pc.craft`
- `hcm.workbench.place`, `hcm.workbench.use`
- `hcm.market.order`, `hcm.market.use`
- `hcm.marketplace.sell` (place/use a Pallet), `hcm.marketplace.buy`
- `hcm.mini.buy`, `hcm.mini.sell`, `hcm.mini.craft`
- `hcm.vending.create`, `hcm.auction.list`, `hcm.auction.bid`
- `hcm.mall.rent` (or delegate to Towny)
- `hcm.market.limit.bypass`

---

## 11. Economy Risks & Safeguards (holes to close before real players)

**Ordered roughly by urgency.**

1. **Creative-world money exploit (URGENT).** If a player can spawn free items in a creative world and sell them to the market/Marketplace for real money, the economy breaks instantly. **Sandbox the economy per-world** (`worlds.economy_enabled_worlds`): no selling in creative/exempt worlds, and keep the creative world in its **own Multiverse-Inventories group** so items can't cross into survival. **Must-fix before creative goes live.**
2. **Recipes empty = loop locked (URGENT).** The Workbench and PC recipes ship empty, so *nothing is craftable* until the admin fills them. Define the Mini Workbench + PC recipes before the daughter can start.
3. **Mini dupe protection (Phase 4).** The "finite mint" promise breaks if Minis can be duplicated. Guard against item-dupe glitches (periodic audit: total minted == ledger) and ensure **GravesX** returns a dead player's Mini cleanly without duplicating it.
4. **Delivery when offline / inventory full.** Solved by the **Locker** (§3.2) — orders wait there instead of dropping/vanishing. Same for Marketplace deliveries.
5. **CoreProtect down on 26.2.** No block-logging/rollback right now — real grief exposure on a kids' server. Find a 26.2-compatible logger/fork before friends join. *(Not a plugin task; server-side.)*
6. **Back up the SQLite DB before every migration.** It holds market state, orders, marketplace listings, and Mini provenance — the server's "money." Auto-backup like Towny/QuickShop do.
7. **Starting capital + money sinks.** Ensure new players can earn a first stake; keep sinks healthy (shipping fees, Mall rent, **Marketplace commission**) so currency holds value.
8. **BlueMap resources not accepted.** Live map won't render until `accept-download: true` in `plugins/BlueMap/core.conf` → `/bluemap reload`. *(Server-side; relevant since the Phase 6 dashboard shares that spirit.)*
