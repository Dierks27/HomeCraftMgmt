# HomeCraft Management

A Minecraft **Paper** plugin. See [`DESIGN.md`](DESIGN.md) for the full spec and
[`CLAUDE-CODE-PHASE1.md`](CLAUDE-CODE-PHASE1.md) for the Phase 1 brief.

- **Target server:** Paper **26.2**, Java **25**
- **Build:** Gradle (toolchain pinned to Java 25), shaded runnable jar
- **Latest release:** [`v0.27.0-the-crate`](https://github.com/Dierks27/HomeCraftMgmt/releases/latest)
- **Status:** the plugin is well past the numbered phases. Shipped and in use:
  the **PC and its Sites**, the **finite-stock commodities market**, the **Crate
  storefronts** (store, Marketplace, Pallets, Locker, real-time shipping), the
  **Minis collectibles** line end to end (catalog, minting, provenance, Museum,
  Vending Machine, Auction House, wild spawns, the Mini Printer), the **Arcade**
  (achievements, daily/weekly quests, Prize Counter), the **web dashboard**, the
  **in-game displays**, and the **Courier** — paid delivery runs with a placed
  village house and a villager recipient at the far end.

  *Still open:* the Courier's Fragile/Perishable cargo modifiers, a
  complete-a-delivery quest verb, and Marketplace search + sort.

---

## What Phase 1 delivers

| Piece | What it does |
|---|---|
| **Skeleton** | `plugin.yml`, main class with clean enable/disable, `/hcm` command, LuckPerms-ready permission nodes. |
| **Config** | `config.yml` matching the design schema. The `crafting` section is fully wired; `market`/`shipping`/`minis` are stubs. `/hcm reload` re-reads it live. |
| **Persistence** | SQLite (JDBC) with a clean DAO layer and a forward-only migration framework. Phase 1 table: placed custom-block locations + owner. |
| **Mini Workbench** | A placeable custom block (tagged vanilla `CRAFTER`). Right-click opens a custom crafting GUI. Placement + owner persisted. |
| **The PC** | A custom item crafted at any crafting table (vanilla recipe under `recipes.pc`, also matched in a Printer's craft grid). Placeable; right-click opens the **Crate** store. |
| **Recipes** | Fully **data-driven and reloadable** — nothing hardcoded. Every craftable block (PC, Printer, Vending Machine, Pallet, Arcade, eight Mailbox colours) has a vanilla SHAPED recipe under `recipes:` in `config.yml`; the retired Workbench has none. |
| **Protection** | Towny + WorldGuard build-permission checks for place/use/break, via reflection so they stay optional soft-depends and degrade gracefully. |
| **Finite-Stock Market** (Phase 2.5) | Real conserved stock per commodity (sell adds, buy subtracts, floored at 0), out-of-stock enforcement, scarcity pricing (empty→ceiling, full→floor) with buy/sell spread, per-item starting stock, daily anti-whale sell limit, and a price-history log — all persisted to SQLite, Vault-backed. Commands: `/hcm market list|price|history|buy|sell`. |

### Verified build coordinates

- **Paper API:** `io.papermc.paper:paper-api:26.2.build.+` — verified against
  PaperMC's published javadocs (`26.2.build.107-stable`). This artifact is
  published **only** on `https://repo.papermc.io/repository/maven-public/`
  (it is **not** on Maven Central).
- **Java toolchain:** pinned to **25** (Paper 26.2's required runtime).
- `plugin.yml` `api-version` is set to `1.21` (a broadly-supported baseline).
  If the live 26.2 server logs it as unsupported/legacy, bump it to the newest
  token that server accepts.

---

## Building

**Requirements:** JDK 25 available to Gradle (installed locally, or let Gradle
auto-provision it), and network access to `repo.papermc.io`.

```bash
./gradlew build
# -> build/libs/HomeCraftManagement-<version>.jar   (shaded; SQLite bundled)
```

Drop the jar into your server's `plugins/` folder and start Paper 26.2.

> **Note on this repo's automated environment:** the sandbox that scaffolded
> this project **cannot** compile the jar — its egress policy returns **403**
> for both `repo.papermc.io` (so `paper-api` can't be fetched) and the foojay
> JDK download service (so a JDK 25 toolchain can't be provisioned), and only
> JDK 21 is installed. The build therefore must be run in an environment that
> can reach `repo.papermc.io` and has (or can download) **JDK 25**. All source
> and build config here is written against the real Paper 26.2 API.

If `./gradlew` complains about the Java 25 toolchain, either install JDK 25 and
point Gradle at it, or upgrade the wrapper:

```bash
./gradlew wrapper --gradle-version 9.0    # then re-run ./gradlew build
```

---

## Releases, and rolling one back

Pushing to `main` cuts a release tagged from `project.version` in `build.gradle`,
with the shaded jar attached. The version bump lives in its **own release PR**,
never in the feature PR — so the sequence is: merge the feature, then merge a
one-line release PR that bumps the version. A release that already exists is
never rewritten; to publish again, bump the version.

### Rolling back is not just swapping the jar

**Database migrations here are forward-only. There are no down-migrations.** A
newer jar upgrades the schema on first start and nothing brings it back down. So
rolling back is a two-part procedure and **both halves are required**:

1. **Restore the pre-migration copy of the database.** One is taken
   automatically immediately before any migration runs — see `BackupService`.
   Without this you are pointing an old jar at a newer schema.
2. **Run the jar that matches that schema** — the release you are rolling back
   *to*, not the one you are rolling back *from*.

Check which you have before trusting a downloaded jar:

```bash
unzip -p HomeCraftManagement-<version>.jar plugin.yml | grep '^version'
```

> **Releases before `v0.28.0` may have the wrong jar attached.** Until the
> release workflow was fixed, every push to `main` re-uploaded its build over
> whichever release `project.version` still named — so a *feature* merge
> replaced the **previous** release's jar with newer code, while the tag,
> target and notes stayed correct. `v0.26.2` and `v0.27.0` have since been
> rebuilt from their tags and are correct. For anything older, **the source at
> each tag is right and only the attached binary may be wrong** — build from
> the tag rather than trusting the download:
>
> ```bash
> git checkout v0.24.0-wild-spawns && ./gradlew build
> ```

---

## Testing in-game

1. **Get the items** (admin): `/hcm give pc`, `/hcm give printer`, or just craft
   them — every block's recipe is in the vanilla recipe book from the moment you join.
2. **Place the PC**, right-click it → the **Crate** store opens.
3. Break the block → it drops the correct custom item (skin, tags and — for a
   Mailbox — its colour intact) and its record is removed. Restart the server →
   placements persist (SQLite).

### Economy safety (0.21)

- **World sandbox.** The economy runs only in `worlds.economy_enabled` (your main
  world is written there on first start). Elsewhere nothing moves money or
  tokens and HomeCraft blocks can't be placed; refusals are logged. **Pair it
  with Multiverse-Inventories:** put the creative world in its own inventory
  group (e.g. `plugins/Multiverse-Inventories/groups.yml` → a `creative` group
  containing only that world, with `share: [all]`) so no item, Card or Mini can
  ride an inventory from creative into the survival world. The sandbox stops
  money moving in creative; the inventory group stops free items reaching the
  world where money moves.
- **Backups.** A copy of `homecraft.db` lands in `backups/` before any schema
  migration; a daily online backup runs on its own (`backups.interval_hours`,
  `backups.keep`); `/hcm backup now` writes one at will. Restore = stop the
  server, copy a backup over `plugins/HomeCraftManagement/homecraft.db`, start.
- **Two-currency rule.** Tokens never become money: crates pay Cards, packs,
  filament or tokens only (a `money`/`item` reward is rejected with a warning),
  pity costs 25 tokens, and the starter/premium packs, per-item daily caps and
  $5k/day limits are the new defaults (applied to an existing config once).
- **Printers.** A public printer charges `printer.public_fee` ($150) and the
  house covers filament, no Shiny; your own printer consumes your filament
  (`printer.fee`, default $0) and unlocks Shiny. Filament crafts from
  string + a dye + a slime ball (3 per craft, one recipe per colour).
- **JEI.** Recipes carry the `homecraft` group and load before JEIServerProxy
  so its join-time sync sees them. If a recipe still doesn't show in JEI, the
  result item's custom head/PDC is the likely reason on the proxy side.

### Recipes, skins and block variants (0.19)

- **Recipes** live under `recipes:` in `config.yml` — one SHAPED 3×3 entry per
  craftable block (`pc`, `printer`, `vending`, `pallet`, `arcade`, and
  `mailbox.<variant>` × 8). They register as normal vanilla recipes under the
  plugin's namespace, unlock in every player's recipe book on join (click-to-fill
  works), and re-register live on `/hcm reload`. An ingredient is a Material or a
  `#tag` (`#planks`, `#wooden_slabs`, `#wooden_fences` = any item of that tag).
  Set `shape: []` to disable one. The Mini Workbench is retired and has no recipe.
- **Skins** (`skins:`) are Base64 head values; every block now ships with one.
  Blank = the block keeps its plain base material.
- **Pallet** — two visual states: `skins.pallet_empty` while nothing is loaded,
  `skins.pallet_used` the moment a listing is stocked (and back when it's cleared).
- **Vending Machine** — **two blocks tall**: the lower head (`skins.vending_lower`)
  is the real block; the upper head (`skins.vending_upper`) is placed automatically
  (the space above must be air or placement is refused). Right-click either half
  to open it; break either half and the whole machine drops as one item. Machines
  placed before 0.19 get their upper head on the first start (blocked ones are
  logged with coordinates).
- **Mailbox** — eight colour variants (`wood`, `light_blue`, `black`, `white`,
  `purple`, `blue`, `orange`, `yellow`): `/hcm give mailbox [variant] [player]`
  (defaults to wood), one recipe each (the colour's dye; planks for wood), skins
  under `skins.mailbox.<variant>`, named e.g. "Blue Mailbox". Mailboxes placed
  before 0.19 read as wood.
- **Arcade** — hub only. `/hcm give arcade` (or craft it); the Crate Machine,
  Scratch-Ticket Booth, Pity Exchange and Token Counter blocks, and
  `/hcm give auction`, are no longer handed out (already-placed ones keep working;
  auctions are reached with `/hcm auction`).

---

## Finite-Stock Market (Phase 2.5)

A real commodities exchange with **finite, conserved stock** — not an infinite
vending machine. The market holds a real **stock** count per commodity:

- **Selling adds** to stock; **buying subtracts**. Stock never goes negative.
- **At zero stock an item is OUT OF STOCK** — you can't buy it at all.
- **Price is a function of stock:** empty ⇒ **ceiling** price (and unbuyable);
  full ⇒ **floor** price; `elasticity` shapes the curve, `inertia` glides it.
- **Buy/sell spread:** the market sells to you slightly above, and buys from you
  slightly below, the mid price — kills round-trip arbitrage.
- **Per-item starting stock:** staples (cobblestone) seed with generous stock
  (cheap & available); ores (gold, diamond) start at **0** → ceiling price and
  unbuyable until players sell some in.
- **Daily per-player sell limit** (anti-whale): caps money and/or units sold per
  UTC day; resets daily; bypass with `hcm.market.limit.bypass` or raise per rank.
- **Daily per-player buy limit** (anti-drain): the sell-side mirror, so no one
  drains a commodity the moment it's stocked. Same shape, same bypass node.
- **Per-item daily caps:** each catalog entry may set `max_daily_sell` /
  `max_daily_buy`, enforced per-player-per-item-per-day **on top of** the global
  caps — the tighter one wins. Rare items stay tight while commons run high volume.
- **Stock never reaches `full_stock`:** that value is the price curve's
  denominator, not a target — the market always keeps room to sell into.
  `initial_stock >= full_stock` is clamped to 85% with a warning.
- **Prices are hard-clamped to `floor`/`ceiling`** everywhere — stored, displayed,
  and quoted. Inertia may smooth price *within* the band, never outside it, and a
  price cached under an older config is snapped back into range on reload.
- **Price history** is snapshotted periodically (for the Phase 5 dashboard).

Amazon side only; QuickShop is untouched. Money flows through **Vault** (cash is
infinite; only item stock runs dry). The store GUI arrives in Phase 3 — for now
these commands drive the engine:

| Command | Does |
|---|---|
| `/hcm market list` | Every commodity with its buy/sell price and stock. |
| `/hcm market price <item>` | Stock (vs full), buy/sell/mid price, floor/ceiling. |
| `/hcm market history <item>` | Recent price/stock snapshots. |
| `/hcm market buy <item> <qty>` | Pay via Vault, receive items, stock −N (price rises). |
| `/hcm market sell <item> <qty>` | Hand over items, paid via Vault, stock +N (price falls). |
| `/hcm market resetstock <item\|all>` | **(admin)** Reseed stock from config's `initial_stock` **and** snap price onto the curve — the intended way to apply a new economy design. `all` asks for confirmation. |
| `/hcm market setstock <item> <amount>` | **(admin)** Set one item's stock (capped below `full_stock`), price recalculated. |
| `/hcm balance` | Your Vault money and Arcade tokens in one place. |

**Requires Vault + an economy plugin** (EssentialsX). Without one, buy/sell are
refused with a clear message (everything else still works).

**Tuning** lives in `config.yml` under `market`: `elasticity`, `inertia`,
`spread`, `default_full_stock`, `sell_limits`, `price_history`, and the `catalog`
(per-item `floor` / `ceiling` / `initial_stock` / `full_stock`). Edit and
`/hcm reload` — existing stock/price is preserved; new items seed fresh.

**Pricing (Phase 2.5.1):** price is a **geometric** curve of stock, so an equal %
stock change moves price a comparable % for any item (cheap staples no longer
whipsaw, dear items no longer sit still). Bulk orders **integrate** price across
the order — a large buy's total is the area under the rising price and the final
displayed price is where the order ended.

**Verify the engine (admin commands):**
1. `/hcm market price diamond` → starts **OUT OF STOCK** at the ceiling; `buy` is refused.
2. `/hcm market sell diamond 64` → market stock becomes **+64** and the price drops.
3. `/hcm market buy diamond 16` → stock falls, price ticks back up; `sell`/`buy` prices differ (spread).
4. `/hcm market buy cobblestone 600` → moves the price only a small %, not floor→near-ceiling.

---

## Store & Market GUIs (Phase 3)

**GUI-first — players never type market commands.** Everything is clickable
inventories; `/hcm …` stays admin/testing only.

- **The PC opens the Amazon Store.** Right-click a placed PC → paginated catalog
  with live buy price + stock. Click an item → pick a quantity → **checkout**:
  choose a shipping tier and pay (item cost + shipping) via Vault.
- **Shipping tiers** (from `config.yml` `shipping`): **3-Day free, 2-Day 10%,
  1-Day 20%** (percentage or flat, plus a Prime-style flat option). The order
  **delivers after the real-time delay** and is **collected at the PC** under
  **My Orders** (in-transit countdown → ready-to-collect). Orders persist and
  deliver on schedule across restarts.
- **Instant Market GUI** (a button in the store): click to **buy/sell now** at
  the live price against the same finite stock — no shipping, no commands.

**Verify (needs Vault + EssentialsX):**
1. Craft/`/hcm give pc`, place it, right-click → the **Amazon Store** opens.
2. Order cobblestone with **1-Day** vs **3-Day** — see the shipping cost/ETA differ.
3. Set a tier's `real_hours` low (e.g. `0.02`), reload, place an order → it lands
   in **My Orders** as *in transit*, then flips to **ready**; click to collect.
4. Restart mid-transit → the order still delivers on schedule.
5. Open **Instant Market** → left-click buy / right-click sell; stock & price move.

---

## Minis — Collectibles (Phase 4, core)

Rarity-tiered collectible heads (and, later, posed armor stands), all
**config-driven** under `minis:` in `config.yml`:

- **Assign a rarity; the style follows.** The `rarity_styles` palette maps each
  tier → pane colour, name colour, enchant glint, and default cap/price. Edit it
  once instead of styling every Mini.
- **Catalog** = hand-picked `series` → `entries` (name, `category`/Type,
  optional `texture` Base64 head value, `cap`, `price`, `craftable`). Entries
  inherit their series' rarity and the palette defaults unless overridden.
- **Minting** is the single source of truth: printing a Card, finding one in the wild / a crate / a natural spawn, or an admin
  give, **mints** a uniquely-tagged copy — a per-copy UUID in the item's PDC
  (anti-dupe), the type id, and the **Mint #N**. Caps are enforced; circulation
  is tracked (`minted − destroyed`).
- **Museum GUI (browse-only)** — a button on the PC store (or `/hcm museum [id]`):
  browse every Mini with live **minted/cap**, **circulation** and the
  **Standard→Mint value appraisal**; click one for its detail card (who holds
  live copies, how to get one). Nothing is minted or bought here — Cards → Printer,
  wild drops, natural spawns and Arcade crates are the only mint paths.
- **Grades** are three stars on the name — ☆ Standard, ★★ Graded, ★★★ Mint
  (`minis.grades`) — rolled from the Mini's card odds at the Printer or when found.
  Rarity owns the colour and glint; the grade never competes for colour. Shiny is
  an independent finish. Copies from the old five-grade ladder migrate on sight.
- **Placed Minis glow** (`minis.effects`): a Display Case trophy, armor-stand Mini
  or natural spawn gets per-rarity particles, a name hologram, a hidden light,
  and (Epic+) a slowly rotating display; Legendary is full-bright with a totem
  burst on placement. Everything is cleaned up on break/unload/disable.
- **Wild drops** use **tag pools** (`tags:` on a Mini, `tag:` on a source), roll
  grade + Shiny, and mint the finished Mini straight to the finder with a server
  broadcast (hover the name for its tooltip; click to open it in the Museum).
  **NATURAL_SPAWN** sources place a claimable Mini head 24–48 blocks from a player
  with a "spawned near a player" hint; untouched, it slips away after a timeout.

**Admin/testing:** `/hcm mini list`, `/hcm mini give <id> [player]`,
`/hcm museum`.

**Verify:** `/hcm reload` → `/hcm mini list` shows the example Minis → `/hcm mini
give golden_idol` → check the item's tooltip (Type/Series/Rarity/Grade/Mint #);
load it into a Display Case and watch its effects; a capped Legendary stops at its cap.

*Deferred to follow-ups:* Vending Machine, Auction House, wild-drops, posed
armor-stand spawning, and the checkmark web-import.

---

## Courier — Deliveries (v16)

A **Site on the PC** (also `/hcm courier`) that pays you to move things around the
map. It is money *earned by travelling*, not by selling — the market is a
finite-stock exchange, so it cannot be the only way to earn without the price
collapsing.

- **Take a run** from one of three distance bands — **local** 200–600 blocks
  (2/day), **regional** 600–1800 (2/day), **long haul** 1800–4000 (1/day). A
  waypoint is rolled when you accept: random bearing, resolved to solid ground,
  re-rolled off water, ocean biomes and land you can't build on. One run at a
  time, per player.
- **Get paid** `(base + per_block × distance) × travel_multiplier`. The distance
  is the straight line, clamped to the band and **locked at acceptance**, so the
  scenic route earns nothing extra. Five runs at their midpoints on foot come to
  **about $93** a day.
- **How you travel is most of the fee.** Vanilla movement statistics are
  snapshotted when you accept and blended by the **fraction of distance** in each
  group at turn-in — so walking the route once and walking it four times both
  score **1.00**, and riding half of it lands you between the two rates.
  Shipped: `foot` 1.00, `mount` 0.85 (horse/strider/pig/nautilus), `boat` 0.80,
  `ghast` 0.70, `rail` 0.65, `elytra` 0.45, **`creative` 0.00**.
- **Anti-teleport:** arrive with less than 70% of the distance actually tracked
  and the travel fee pays 20%. Logged at `FINE`, never announced — a portal
  shortcut isn't cheating, it just isn't walking.
- **Trade runs:** right-click a band holding something the market buys and the
  run carries it. At the drop-off it is **sold into the market for real** — stock
  moves, daily limits and commission apply — at the **live** rate, not the quote
  shown when you accepted. Only the travel fee is new money.
- **Dropping a run doesn't spend the slot.** Runs expire after 60 minutes and the
  band comes back either way. All of it is off in an economy-disabled world.

**The destination.** As you get near the drop-off, a small house appears with a
villager outside it — right-click them to hand the crate over. The houses are
vanilla village buildings picked to match the biome, so there's nothing extra to
install, and every structure key is checked once at startup (Mojang renames them
between versions; anything missing is dropped with a warning naming it).

Forests are fine — whole trees in the way are cleared and put back afterwards
(whole ones, so nothing is left hanging in the sky). The house stays until you've
actually walked away, and never disappears while you're standing in it. Only real relief (or a lake) makes it pick a different field, and it
works that out while choosing the waypoint, so a bad spot costs a reroll instead
of a walk. If it does refuse, it says so in the console and tells you at the
drop-off rather than leaving you to find an empty field.

When the run ends — delivered, expired, abandoned, or the server stopping — **the
field goes back exactly as it was**, from a snapshot taken before the first block
moved. That snapshot lives in the plugin's own database, so this needs no
WorldEdit, survives restarts, and tidies up after a crash on the next start. You
can't mine the house, and the chests in it are emptied — a village-loot faucet is
exactly what §3.1 refuses.

A site also refuses to be built over anything the snapshot couldn't put back:
a chest or other container, a **placed entity** (item frame, armour stand, chest
minecart, boat, display — none of which are in a block snapshot at all), anything
HomeCraft already tracks there (a placed PC, Pallet, or worst of all a placed
**Mini**, which is a numbered collectible that can't be re-minted), and any block
in `avoid_blocks` — player heads by default, so a GravesX death-storage grave
doesn't end up behind a wall. Wandering mobs don't count; a cow walking through
shouldn't cost you a field. The cheap half of that check runs while the waypoint
is being picked, so a bad spot gets rerolled instead of becoming a delivery that
arrives at nothing.

**You carry a crate.** A courier run hands you one — a head textured for the band —
and the recipient wants it **in your hand**, not just in your bag. Trade runs don't
get one; their cargo is your own goods.

The crate is worth nothing and stays that way: you can't place it, wear it, stack
it, list it on a Pallet or run it as cargo for another job, and it's destroyed the
moment the delivery ends however it ends. One belonging to a finished job is
removed on sight. It drops on death by default — the grave is how you get it back
— and `package.keep_on_death` makes it kinder for the younger players.

**Losing it costs money.** The fee is what the run would have paid on foot, times
`package.loss_multiplier` (1.0 out of the box), so a lost crate cancels the run
out — you walked it for nothing. It's charged when a run ends *without the crate
coming back*, never just for failing the run: come back with it in your bag and
you've only lost the payout. Log out to dodge it and it settles on your next join
instead, which is the point.

Can't pay it all? The rest becomes **debt**, and debt closes the courier board and
nothing else — the market, the shops, your Pallets and every other way of earning
stay open, so you can work it off. Pay it down at the PC, in part or in full.

**You can buy a replacement** at any PC for exactly the same amount, which is what
keeps the fee fair: a creeper four thousand blocks out costs you money, not the
whole delivery. Same price either way, so there's no clever choice to make.

**It won't build on your stuff.** The waypoint refuses claimed land, but claims only
cover what someone bothered to claim — so the footprint is also checked for
*evidence* of building: planks, a crafting table, a bed, glass. None of those
generate on their own, so finding one means the field is already somebody's,
claimed or not. It also steers clear of villages, shipwrecks and ruins, which use
the same materials. `reject_built_blocks: false` turns it off.

**The house comes down when *you* walk away** — the player who took the run, not
whoever happens to be online. It never comes down while *anyone* is standing in the
footprint, though, because that rule is about not burying someone in spruce logs and
it doesn't matter whose delivery it was. Log off and it restores right away.

**Handing in at the PC still works** if the house doesn't appear for any reason, so
nobody ends up stranded 1,800 blocks out because a structure failed to place.

Everything is tunable under `courier:` in `config.yml` — including turning it off
(`courier.enabled: false`, which also hides the Site from the PC) and turning just
the buildings off (`courier.building.enabled: false`, which leaves deliveries
working and hands in at the PC).

Commissioned house builds drop into `courier.buildings` as `.nbt` files in
`plugins/HomeCraftManagement/buildings/` — a config edit, not a code change. A
non-empty list replaces the vanilla set rather than adding to it.

**Verify:** `/hcm courier` → take a local run → walk to the coordinates it gives
you → the house should already be there when it comes into view → right-click the
villager → **Hand over the crate**. Walk away and come back after the linger and
the field should be bare again. Try the same run in a creative world and it should
refuse; fly it with elytra and the fee should be visibly smaller. To check the
cleanup path properly: take a run, walk out to it, then abandon it from the job
board and watch the house go.

---

## Permissions

| Node | Default | Grants |
|---|---|---|
| `hcm.admin` | op | All admin commands (`/hcm …`) + all child nodes |
| `hcm.use` | op | Parent node granting both market view nodes below (back-compat) |
| `hcm.market.list` | op | `/hcm market list` — dump the FULL catalog (players use the PC GUI) |
| `hcm.market.price` | all | `/hcm market price\|history <item>` + `/hcm balance` |
| `hcm.market.order` | all | Buy from / sell to the dynamic market |
| `hcm.market.limit.bypass` | op | Exempt from daily buy/sell limits AND per-item caps |
| `hcm.pc.use` | all | Open the Amazon GUI on a placed PC |
| `hcm.pc.craft` | all | Craft the PC at a Workbench |
| `hcm.workbench.place` | all | Place a Mini Workbench |
| `hcm.workbench.use` | all | Open a placed Workbench's GUI |
| `hcm.courier.use` | all | `/hcm courier` and the Courier Site on the PC |
| `hcm.protection.bypass` | op | Bypass Towny/WorldGuard checks for our blocks |

---

## Project layout

```
src/main/java/com/dierks/homecraft/
  HomeCraftManagement.java     main class / wiring
  block/                       custom-block type, service, listeners
  command/                     /hcm
  config/                      typed config.yml view
  courier/                     delivery jobs, waypoints, travel-statistic blending,
                               building placement + snapshot/restore
  crafting/                    data-driven vanilla recipes + recipe-book unlock + craft-grid matching
  gui/                         Amazon placeholder GUI (Phase 3 stub)
  integration/                 Towny + WorldGuard protection, Vault economy
  item/                        tagged custom items
  market/                      dynamic market engine (catalog, pricing, service)
  storage/                     SQLite datastore + DAOs
  util/                        NamespacedKeys, text helpers
src/main/resources/
  plugin.yml
  config.yml
```
