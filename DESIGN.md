# HomeCraft Management — Plugin Design Specification (v9)

> **Purpose of this document:** the build spec for a custom Paper plugin. It is written to be handed to Claude Code (or any implementer) as the source of truth. Design decisions still open are marked **[DECISION]** with a recommended default.

---

## 1. Vision & Core Economic Loop

The plugin creates a deliberate tension between two ways to get goods — mirroring real life:

| Channel | Price | Speed | Cost to access |
|---|---|---|---|
| **Physical player shops** (QuickShop) | Player-set (owners choose) | **Instant** | You must *travel* to the shop |
| **The "Amazon" market** (this plugin) | **Dynamic** (supply/demand) | **Delayed** (1/2/5-day shipping) | You must *build a PC* + pay shipping |

**The point:** need it *now*? Go to a shop and travel. Can wait? Order online — cheaper per-item, but you pay shipping and wait for delivery. Both channels stay viable.

**Design north star:** "just like life." Scarcity, shipping, convenience-vs-cost tradeoffs, and collectible status all behave the way they do in the real world.

---

## 2. Architecture Overview

One plugin, four modules:

1. **Dynamic Market Engine** — our own supply/demand pricing (replaces the DynamicShopGUI dependency entirely).
2. **Amazon Ordering + Shipping** — the online store, accessed only through a PC, with real-time shipping tiers.
3. **The PC** — a rare-crafted item that is the gate to the Amazon market.
4. **Heads Collectible System** — a rarity/mint/circulation system with a Museum & Shop.

**Repository:** `https://github.com/Dierks27/HomeCraftMgmt`
**Environment:** Paper 26.2, Java 25.
**Depends on:** Vault (economy → EssentialsX).
**Coexists with:** QuickShop-Hikari (player shops — untouched, keep their own prices).
**Replaces:** DynamicShopGUI (we reimplement its pricing so we own the code and it isn't fragile). We may reference DynamicShopGUI's open-source code for algorithm/feature ideas, but write our own clean implementation.

---

## 2.1 Target Server Environment

> **The implementer cannot see the live server.** This section IS the environment — build against it.

**Server:** Paper **26.2**, Java **25**, ~8 GB RAM.

**Full installed plugin stack** (most are irrelevant to this plugin — listed so you know the surroundings):
Towny 0.103.1.0 (+ TownyChat) · Vault · EssentialsX 2.22.1 · LuckPerms · CoreProtect · PlaceholderAPI · WorldEdit 7.4.5 · WorldGuard 7.0.18 · QuickShop-Hikari 6.3.0.0 (+ addons: FindItem, Dialog, list, limited, displaycontrol, discount, bluemap) · ProtocolLib · DynamicShopGUI v3.8 *(being replaced by this plugin)* · Multiverse-Core 5.7.3 (+ Inventories, Portals, NetherPortals, SignPortals) · BlueMap 5.23 (+ BlueMap-Towny, Marker Manager) · PlasmoVoice · RoadSpeedMounts · Graves · TAB · DiscordSRV.

**Integration-relevant (what this plugin actually touches):**
- **Vault** (backed by EssentialsX economy) — **all** money flows through Vault. Soft-depend; hook in the economy phases.
- **Towny 0.103.1.0** — the Mall is an admin Towny town with rentable plots; respect Towny build permissions for placing/using custom blocks; Minis can be displayed on town land.
- **WorldGuard 7.0.18 / WorldEdit 7.4.5** — respect WorldGuard region build permissions for custom-block placement/use.
- **LuckPerms** — define clean permission nodes for every command/feature so they're LuckPerms-manageable (e.g. `hcm.admin`, `hcm.pc.use`, `hcm.mall.rent`).
- **QuickShop-Hikari 6.3** — **DO NOT touch or integrate.** Player shops are intentionally separate; Minis are never sold through QuickShop.
- **DynamicShopGUI** — being retired; we reimplement dynamic pricing ourselves. Do not depend on it.
- **PlaceholderAPI** — expose placeholders (mint counts, circulation, town prestige, order status) so TAB/other plugins can display them. Nice-to-have.

Declare Vault / Towny / WorldGuard / LuckPerms / PlaceholderAPI as **soft-dependencies** in `plugin.yml` (load after them) and **degrade gracefully** if any is missing.

---

## 3. Module Specifications

### 3.1 Dynamic Market Engine

The market's prices move with supply and demand — an **elasticity model**:

- Each catalog item has a **base price**, a **current price**, and a **stock/demand counter**.
- **Buying** raises the price (demand up, stock down); **selling** lowers it (supply up).
- **Price bounds:** each item has a configurable **floor** and **ceiling** so prices never run away.
- **Inertia:** prices glide toward their target instead of snapping, for a smooth market feel.
- **Only the Amazon market uses these prices.** Player shops (QuickShop) are completely independent.

Reference: DynamicShopGUI uses an elasticity-based model with market inertia — a good conceptual starting point.

### 3.2 Amazon Ordering + Shipping

- **Access:** ONLY through a placed PC (see 3.3). No global command opens it.
- **Flow:** browse catalog (live dynamic prices) → add to cart → choose shipping tier → pay (item cost + shipping) → order enters transit.
- **Shipping tiers:** **1-day, 2-day, 3-day** — measured in **REAL time**. **[CONFIRMED: real days]**
- **Shipping cost:** configurable, with a **mode switch**:
  - `mode: PERCENTAGE` → shipping = X% of order total, per tier.
  - `mode: FLAT` → shipping = a flat fee, per tier.
  - Support a **Prime-style option**: e.g., a flat fee for 2-day regardless of order size.
  - Faster tier = higher cost, **but tuned so ordering stays competitive with physical shops even after shipping** (convenience premium, not a punishment).
  - **Confirmed defaults (percentage mode):** 1-day = **20%**, 2-day = **10%**, 3-day = **free (0%)**. Free 3-day keeps patient buyers paying nothing extra (very competitive vs. traveling); 20% next-day is a real premium on impatience.
- **Delivery:** when the real-time timer elapses, the order becomes a **package** the player collects at their PC (right-click PC → "My Orders" shows *In Transit* with a countdown, and *Ready to Collect*).
- **Persistence:** orders and delivery times survive restarts (store real timestamps; check on a scheduler and on player login).

### 3.3 The PC

- A **custom item crafted from rare parts** at the Mini Workbench — building one is a milestone/achievement. **The recipe is admin-defined and editable** (you fill in the parts and change them whenever — nothing hardcoded). Ingredients to consider for the rare tier: Netherite, Redstone, Glass Panes, Amethyst/Echo Shard, and a capstone rare (Nether Star / Heart of the Sea).
- **Placeable** as a block; **right-click** opens the Amazon GUI.
- Represented visually by a computer-textured custom head/block.
- It is the **gate** to online ordering — no PC, no Amazon.
- **[DECISION]** Should the PC be unbreakable-by-others / protected in a town? (Recommend: respects Towny/WorldGuard build perms so only owners/residents place & use in claimed land.)

### 3.4 Collectibles System — "Minis" (Heads & Armor Stands)

The showpiece. The collectibles are branded **"Minis."** Two Mini types: **decorative heads** and **posed armor stands** (from minecraft-heads.com/armor-stands). Both are ultra-rare collectibles and status symbols, and everything below (series, rarity, mint caps, Museum & Shop, circulation tracking) applies to **both**.

**Curated, never swept — hard requirement.** The catalog is **hand-picked by the admin**; every entry is deliberately chosen. We do **not** bulk-import the site (it's full of junk). See *Curation Workflow* below.

- **Series:** every head belongs to a named series (e.g., "Woodland Critters," "Legendary Relics"). Series can be themed and released over time.
- **Rarity tiers:** Common → Uncommon → Rare → Epic → Legendary.
- **Mint cap (fully configurable — an option you can change anytime):** each head/series can be **uncapped** or **hard-capped** at any number, per series/rarity. A capped Legendary (e.g., 5) that's minted out becomes **trade-only** (only from a player who owns one). Every behavior here is a toggle — you're never locked into one model.
- **Museum & Shop:** displays every Mini with name, series, rarity, **minted X / cap**, and **# in circulation**. Buying = **minting** a new copy up to the cap, at **dynamic scarcity-driven prices** (climbs as a Mini nears its cap / demand rises). Reachable **in person at the Mall** (spawn, instant) and **online via the PC** (dynamic + shipping) — see §3.6.
- **Pricing (both modes, configurable, changeable anytime):** **fixed-per-rarity** *or* **escalating** (each successive mint pricier as it gets scarcer), set per series/rarity. Both always available.
- **Commons:** uncapped & cheap by default — an accessible entry tier; higher rarities typically capped. (Still just config, so adjustable.)
- **Craftable option (per entry — a checkbox):** each collectible has a **`craftable`** flag. If enabled, the admin defines a **recipe** and players can craft that head/stand — **a crafted copy still counts toward its mint cap and circulation.** If disabled, it's shop/trade-only. So per item you choose: buyable, craftable, both, or neither. (Crafting is handled by the Workbench — see 3.5.)
- **Texture source:** curated from **minecraft-heads.com** — both the [heads](https://minecraft-heads.com/) catalog and the [armor stands](https://minecraft-heads.com/armor-stands) catalog. System is **data-driven** — each entry added by its texture/config value + metadata (type, series, rarity, cap, price).

**Armor Stands (second collectible type).** minecraft-heads.com/armor-stands provides *posed decorative armor stands* — an armor stand entity with a custom pose + equipment (often including a custom head). These are richer than a single head item: the plugin spawns a **fully configured armor stand entity** from stored pose/equipment data when placed. They follow the **same** series / rarity / mint-cap / circulation rules as heads, and make for premium town-decoration flexes. *Implementation note:* store each armor-stand collectible as pose + equipment data (the summon-style config from the site) and reproduce it on placement.

**Curation Workflow — selective, admin-controlled (hard requirement).** The admin controls exactly what lands in the catalog — but adding one-by-one would be painful, so the **primary tool is a checkmark import**:
- **Selective import GUI (primary):** admin opens an import browser, picks a source **category/collection** from minecraft-heads.com, and sees its entries laid out with **checkboxes**. Tick the ones you want, skip the junk, click **Import Selected** → the whole batch is added at once. Then assign metadata (series, rarity, cap, price) — **in bulk to the batch**, or tweak per-entry.
- **In-game capture (secondary):** hold a head (or target an armor stand) → `/collect admin add` → prompt for metadata → saved. Good for one-offs or custom heads you made yourself.
- **Manual entry:** paste a texture value / armor-stand config with metadata.
- **The rule:** nothing is added *blindly*. The admin either checkmarks it in the import browser or hand-adds it — never a wholesale sweep of the entire site.
- *Implementation note:* the import browser pulls per-category entry lists from minecraft-heads.com (heads + armor-stands sections). Paginate; cache fetched categories so the admin isn't re-hitting the site.

**Tracking honesty:** minted count is tracked **exactly**. "In the wild" / circulation is tracked **best-effort** (mints minus detected destructions); rare edge cases (e.g., a head destroyed in an unloaded chunk) may not register. For a friends-scale server this is effectively accurate.

**Every Mini is a unique, tagged item.** Each minted Mini carries a hidden **unique ID** + metadata (series, rarity, "Mint #N of cap") in its item data. It stays individually trackable no matter how it moves — dropped, chested, hand-traded — which both hardens circulation tracking and lets us stamp **provenance** ("Mint #3 of 5").

**Trading Minis — the Mini Vending Machine (our own, tracked).** Players will want to resell Minis to each other. We deliberately do **not** route this through QuickShop — those sales would be invisible to our tracking. Instead, a dedicated custom **Mini Vending Machine** block:
- A player lists a Mini at their **own price** (ChestShop-style, player-set — this is the *secondary market*).
- Buyers browse/purchase; the sale is **fully tracked** (ownership transfer + price history logged), and the machine shows the Mini's series, rarity, and live mint/circulation stats at the point of sale.
- Keeps every Mini transaction inside our system — powering provenance and a "who owns the rares" leaderboard.
- *Bonus:* a **Display Case** variant — show a Mini off in your town as a trophy (no sale). The ultimate flex.

**Mini Auction House (for the rares — tracked).** A fixed vending price undersells a Legendary; some Minis deserve a bidding war. A **timed auction** system:
- A seller lists a Mini with a **starting bid** and **duration**; players bid; **highest bid at close wins**.
- **Escrow:** bids hold the bidder's funds; losers are auto-refunded; on close the winner pays, the seller is paid, and ownership transfers — **fully tracked** (provenance + price history).
- **Anti-snipe:** a bid in the final seconds extends the timer — no last-millisecond steals.
- **Optional Buy-It-Now** price for instant purchase.
- **Notifications:** outbid / won / your auction sold.
- **Location: physically in the Mall** — a dedicated Auction House installation at spawn where everyone gathers to watch and bid. (You can still check bids / get outbid alerts online via the PC, but the auction house *lives* at the Mall.) Perfect for Legendaries and retired-series pieces — a big sale becomes an **event people show up for**.

**Mini economy = primary + secondary (just like real collectibles):** the **official primary market** mints new Minis up to caps, reachable two ways — the **Mall** (physical, at spawn, instant) and the **Online Mini Retailer** (via PC, dynamic pricing + shipping). The **secondary market** is player-driven and tracked: the **Vending Machine** (fixed-price instant resale) and the **Auction House** (timed bidding for rares). QuickShop stays for ordinary goods only.

### 3.5 Crafting System & the Mini Workbench (CONFIRMED: custom bench)

Crafting the **PC** and any **Mini flagged `craftable`** happens at a dedicated custom **Mini Workbench** — not the vanilla crafting table. Why custom:
- **Mint-cap enforcement:** the bench *blocks* a craft when a Mini is minted out, and counts each craft toward its cap/circulation — reliably.
- **Control & theming:** gates rare-crafting, avoids conflicts with other recipe plugins, keeps the vanilla table clean.
- **Single source of truth:** every mint (bought *or* crafted) flows through one place, so counts stay honest.

A placeable **Mini Workbench** block; right-click opens a custom crafting GUI where the PC and craftable Minis are made. The Workbench itself is a mid-tier craft that unlocks the system.

**All recipes are admin-defined and editable — never hardcoded.** The PC's recipe and every craftable Mini's recipe are filled in by the admin (config/GUI) and can be changed anytime. Ship recipes **empty by default** — nothing is craftable until the admin sets its recipe.

### 3.6 The Mall — Spawn Market District

A central **commercial district at spawn** — the high-traffic heart of commerce, where everyone passes through.

- **Rentable stalls (paid):** players **rent a Mall stall for money** to set up shop in a prime, central spot. Rent is the cost of visibility.
- **What goes in a stall:** a player's QuickShops (ordinary goods) and Mini Vending Machines (Minis) — their storefront at spawn.
- **Anchor tenants:** the official **Mini Museum & Shop** (physical "in person, instant" channel) *and* the **Mini Auction House** (§3.4) both live in the Mall — the collectible heart of spawn.
- **The tradeoff — free vs prime (just like real life):**
  - **Town shops = FREE** — set up on your own town land at no cost, but customers must *travel to your town*. Cheap, but low foot-traffic.
  - **Mall stalls = PAID RENT** — central at spawn where everyone passes, but you pay for the spot. Pricey, but high foot-traffic.
  - Players weigh traffic vs cost — a real commercial-real-estate decision.
- **Implementation — reuse Towny:** Towny already supports **plot renting**, so the Mall = an **admin Towny town at spawn** with rentable plots. The rental mechanic already exists; our plugin just anchors the official Mini shop in the district. Minimal custom code, maximum reuse.

---

## 4. Configuration Schema (sketch)

```yaml
market:
  elasticity: 0.05        # how hard prices react to buys/sells
  inertia: 0.2            # price glide smoothing
  # per-item floor/ceiling defined in the catalog

shipping:
  mode: PERCENTAGE        # PERCENTAGE | FLAT   <-- the switch
  tiers:
    one_day:   { real_hours: 24, percent: 20, flat: 500 }
    two_day:   { real_hours: 48, percent: 10, flat: 250, prime_flat: true }
    three_day: { real_hours: 72, percent: 0,  flat: 0 }   # free tier

crafting:
  workbench_recipe: [ ... ]             # how to craft the Mini Workbench
  pc_recipe: [ ... ]                    # admin-filled; empty = not craftable yet
  respect_town_perms: true

minis:
  pricing_mode: ESCALATING              # FIXED | ESCALATING (overridable per series)
  series:
    - name: "Woodland Critters"
      rarity: COMMON
      cap: -1                           # -1 = uncapped
      entries:
        - { name, type: HEAD, texture, price, craftable: false, recipe: [] }
    - name: "Legendary Relics"
      rarity: LEGENDARY
      cap: 5
      entries:
        - { name, type: ARMOR_STAND, config, price, craftable: true, recipe: [ ... ] }
```

---

## 5. Data Model / Persistence

Use a real datastore (SQLite recommended — file-based, no server needed):

- **Market:** per-item current price + stock/demand counters.
- **Orders:** player, items, shipping tier, cost paid, **placed-at timestamp**, **deliver-at timestamp**, status (in-transit / ready / collected).
- **Minis:** per-type minted count + circulation; **per-individual unique ID, current owner, and provenance/price history**; active **Vending Machine listings** and **Auction House listings + escrowed bids + close times** (powers the secondary market + "who owns the rares" leaderboard).
- **Custom blocks:** placed PC / Mini Workbench / Mini Vending Machine / Display Case locations + owners (for the access gate + protection).

Everything must survive server restarts (real-time shipping demands durable timestamps).

---

## 6. Phased Build Plan (for Claude Code)

Build and test each phase before starting the next.

- **Phase 1 — Skeleton + The PC:** project setup, plugin.yml, main class, the rare-parts recipe, the PC item/block, right-click opens a placeholder menu. *Deliverable: craftable, placeable PC that opens a GUI.*
- **Phase 2 — Dynamic Market Engine:** catalog, elasticity pricing, buy/sell, price bounds, persistence.
- **Phase 3 — Amazon + Shipping:** the ordering GUI behind the PC, the shipping-cost config (%/flat/Prime switch), real 1/2/5-day delivery, package collection, restart-safe timers.
- **Phase 4 — Heads Collectible System:** series/rarity/caps, Museum & Shop GUI, minting, circulation tracking, minecraft-heads.com-sourced data-driven catalog.

---

## 7. Decisions

**Resolved:**
- **Name:** HomeCraft Management (repo: github.com/Dierks27/HomeCraftMgmt).
- **Collectibles branded "Minis"** — heads + posed armor stands.
- **Mint model:** fully configurable per series/rarity (uncapped ↔ hard-capped, trade-only after cap) — changeable anytime.
- **Pricing:** both fixed-per-rarity and escalating, configurable.
- **Commons:** uncapped & cheap entry tier (default).
- **Craftable Minis:** per-entry `craftable` flag; crafted copies still count toward caps.
- **Crafting:** dedicated **custom Mini Workbench** (not the vanilla table) — enforces caps + centralizes minting.
- **Recipes:** PC recipe and every Mini recipe are **admin-defined & editable** (empty by default; nothing craftable until set).
- **Shipping:** real 1/2/3-day tiers at 20% / 10% / free (percentage default; flat/Prime available).

**Still open:**
- **PC protection** — respect Towny/WorldGuard perms? (rec: yes.)
- **Series concepts & rarity names** — your creative call (ongoing).

---

## 8. Notes for the Implementer

- Collectibles cover **both heads and armor stands**. The catalog is **admin-curated via a checkmark selective-import** (browse a category → tick the ones you want → batch-import with metadata). No blind wholesale sweep of the site.
- Reference DynamicShopGUI's open-source pricing for concepts, but write original code — we are eliminating that dependency on purpose.
- Keep the four modules loosely coupled so phases can ship independently.
- All player-facing money flows through Vault so it shares the server economy.
- Do **not** touch QuickShop shops — the two shop systems are intentionally separate.

---

## 9. Integration Reference — how we hook every external system

> Written because the implementer only sees the repo. For each system: **what it is**, **why we use it**, and **how we wire in**. Treat API specifics as the *intended approach* — verify exact signatures against the installed version.

### 9.1 Vault (economy) — required for all money
- **What it is:** an abstraction layer that lets us talk to whatever economy plugin is installed (here, EssentialsX) without coding against EssentialsX directly.
- **Why:** every money movement — online orders, shipping fees, Mini minting, vending sales, auction bids/escrow, Mall rent — flows through Vault so it all shares the one server economy.
- **How we hook:** on enable, grab the `Economy` service from Bukkit's ServicesManager (`getServicesManager().getRegistration(Economy.class)`). Use `has()`, `withdrawPlayer()`, `depositPlayer()`. Soft-depend; if absent, disable money features and log a clear warning.

### 9.2 Towny — the Mall + build permissions
- **What it is:** the town/nation land-management plugin; players form towns and claim/rent plots.
- **Why:** (a) the **Mall** is an admin Towny town at spawn whose stalls are **rented via Towny's own `/plot forrent`** — we do NOT reimplement renting; (b) respect Towny build permissions before placing/using our custom blocks on claimed land; (c) Minis displayed on town land feed town prestige.
- **How we hook:** use `TownyAPI` — resolve the `TownBlock` / `Town` / `Resident` at a location and check build permission via Towny's permission util before allowing placement/interaction. Read town/resident data for prestige. Soft-depend; skip town checks if absent.

### 9.3 WorldGuard / WorldEdit — region build permissions
- **What it is:** region protection (WorldGuard) built on the selection engine (WorldEdit).
- **Why:** respect region BUILD flags so custom blocks can't be placed/used where a region forbids it (protected spawn, roads, etc.).
- **How we hook:** query the WorldGuard `RegionContainer` at the target location and test the BUILD state for the player before allowing placement/interaction. Soft-depend.

### 9.4 LuckPerms — permissions
- **What it is:** the permissions manager.
- **Why:** gate every command/feature behind nodes admins manage in LuckPerms.
- **How we hook:** we generally do **not** call LuckPerms' API — we declare permission nodes in `plugin.yml` and check `player.hasPermission(...)`; LuckPerms resolves them. (Optional: Vault-chat/LuckPerms meta for prefixes if we ever show ranks.) Node list in §10.

### 9.5 PlaceholderAPI — data display
- **What it is:** lets other plugins (TAB, holograms, scoreboards) show dynamic text values.
- **Why:** surface our data (mint counts, circulation, town prestige, order status) elsewhere on the server.
- **How we hook:** register a `PlaceholderExpansion` (identifier `hcm`) exposing e.g. `%hcm_mini_minted_<id>%`, `%hcm_mini_circulation_<id>%`, `%hcm_prestige_<town>%`, `%hcm_order_status%`. Soft-depend; register only if present.

### 9.6 Native Paper/Bukkit APIs we rely on (no plugin needed)
- **Custom heads (Minis):** `PlayerProfile` + `PlayerTextures` to apply minecraft-heads.com texture values to head items.
- **Armor-stand Minis:** spawn + configure `ArmorStand` entities (pose, equipment) from stored data on placement.
- **Item/block tagging:** `PersistentDataContainer` for unique Mini IDs, PC/Workbench markers, and placed-block identity.
- **GUIs:** inventory menus via `InventoryHolder` for the Workbench, online store, Museum & Shop, Vending Machine, Auction House.
- **Recipes:** handled inside our **Workbench GUI** (config-driven), NOT vanilla Bukkit recipes — so we can enforce mint caps + tracking at craft time.
- **Timers (shipping, auctions):** Bukkit scheduler + **durable timestamps in SQLite** so real-day deliveries and auction closes survive restarts.
- **Storage:** SQLite via JDBC.

---

## 10. Commands & Permission Nodes

**Commands (indicative — most interaction is block-based, not command-based):**
- `/hcm reload` — reload config.
- `/hcm admin …` — admin tools: add/curate Minis, set caps/prices/series, give items, manage the Mall anchor.
- `/minis` (or block/NPC at the Mall) — open the Museum & Shop.
- Interaction with the **PC**, **Mini Workbench**, **Vending Machine**, and **Auction House** is by **right-clicking the placed block**, not commands.
- Avoid colliding with existing commands (e.g., QuickShop's `/qs finditem`).

**Permission nodes (indicative — all LuckPerms-manageable):**
- `hcm.admin` — all admin functions.
- `hcm.pc.use`, `hcm.pc.craft`
- `hcm.workbench.place`, `hcm.workbench.use`
- `hcm.market.order` — order from the online retailer
- `hcm.mini.buy`, `hcm.mini.sell`, `hcm.mini.craft`
- `hcm.vending.create`, `hcm.auction.list`, `hcm.auction.bid`
- `hcm.mall.rent` (or delegate to Towny's rent permissions)
