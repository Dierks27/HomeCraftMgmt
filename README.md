# HomeCraft Management

A Minecraft **Paper** plugin that turns a family survival server into a small town with a
working economy: a computer in every house, a shop that ships, a market with real stock, a
collectible line of numbered **Minis**, wild Minis to hunt, delivery jobs, and an **Arcade**
that rewards playing. Built for a server where the youngest player is just learning to read,
so every screen is short, plain and readable on **Bedrock** as well as Java.

- **Target server:** Paper **26.2 / 26.3**, Java **25** (compiled against the 26.2 API; the
  code is also compiled and tested against the 26.3 API with no errors or removals)
- **Version:** `0.35.0-arcade-games`
- **Build:** Gradle (toolchain pinned to Java 25), shaded jar with SQLite bundled
- **Design spec:** [`DESIGN.md`](DESIGN.md) · **Player guide:** [`docs/how-it-works.md`](docs/how-it-works.md)

---

## Features

### The PC and its Sites
A placeable computer. Right-click opens a launcher of **Sites**: the Crate store, Sell to
Crate, the Marketplace, Card Packs, the Mini Museum, Mailbox & Orders, the Courier job board
and the How It Works **Guide**.

### Economy
- **Finite-stock market:** real conserved stock per commodity; selling adds to it and buying
  takes from it. Scarcity pricing with a buy/sell spread, daily per-item caps, daily money
  limits and a price history. All of it is Vault-backed and stored in SQLite.
- **Live market (0.33):** prices move a little on their own. There is a slow drift, a HOT item
  and a DEAL about once a week each, a NEWS FLASH on most evenings someone plays, and calendar
  seasons. Limits locked in code keep every price within 25% of its usual price. Stock never
  changes by itself. Players see badges, Market News and a "While you were away" when they join.
- **Crate storefront:** order with real-time shipping tiers (Rush and slower). Orders wait in
  the **Locker** if you're offline or your bag is full.
- **Marketplace & Pallets:** player-to-player selling through placeable Pallet seller boxes,
  auto-sorted into departments, with commission and an admin ban list.
- **Mailboxes** in eight colours, the **Auction House** for Minis, and **in-game displays**
  (signs, holograms, TVs) plus a web **dashboard**, whose JSON feeds (`/api/market`,
  `/api/minis`, `/api/news`, `/api/arcade`) also drive the LilahCraft website, behind an
  optional token.
- **Safeguards:** the economy is sandboxed per world (no creative-world money), protection
  hooks cover Towny and WorldGuard, and the database is backed up automatically before every
  migration and on a schedule.

### Minis — numbered collectibles
- **Numbered and capped:** every Mini has a mint number, a grade (Standard up to Mint) and
  sometimes a **Shiny** finish, with provenance tracked from its first owner.
- **Card Packs:** a pack gives a **Card**, and a Card prints into a Mini at the **Mini
  Printer** using filament.
  - Packs roll by rarity odds, so new Minis join automatically, and show "Sold out" when
    nothing is left.
  - Packs sell for dollars or tokens.
  - The shop shows each pack's odds, what's inside, and which Minis you're still missing.
- **Where Minis live:** the **Museum** shows every Mini there is. Display Cases, Vending
  Machines, the Auction House and a Card Binder hold them.
- **The wild hunt:** now and then a Mini appears in the wild near a player.
  - Everyone hears about it, then hints follow: the biome, then a direction, then a light beam.
  - Whoever reaches it first catches it. It's only minted when it's caught, so Minis that get
    away never waste a number.
  - The Mini Radar and the Mini Lure help.

### The Arcade — tokens buy fun
Tokens are **earned by playing and never become dollars**.
- **Earning:** a login streak, playtime, three daily and two weekly **quests** (drawn per
  player from a pool: fishing, farming, cooking, mining, biomes, deliveries…), **26
  achievements**, wild hunts and Card trade-in.
  - Days roll over at **local midnight** (`clock.time_zone`).
  - Every token change is written to an auditable ledger.
- **The hub:** one screen with your **Wallet**, the games, the Prize Counter, Card Packs,
  Quests, Achievements and How It Works. A live countdown shows any wild Mini that's out.
- **Games:**
  - **Crates** spin and land on their prize.
  - The **Scratch Ticket** has three squares to scratch and a growing jackpot.
  - The Rare Card is a once-a-week pity pick.
  - **The Games (0.35):** five games of chance for tokens that show what they give back before
    you play, eight free arcade cabinets (Creeper Sweeper, Snake, Connect Four and more), and time
    trials and mini golf in a Games world. A Take a break screen sets your own limits. They ship
    off; see [Games (0.35)](#games-035).
- **Prize Counter:** boosts (speed, haste, night vision, water breathing, luck), the Mini
  Radar and Lure, a Firework Show, particle trails, hats, perks (extra homes and `/hat` via
  LuckPerms and EssentialsX), and a numbered Arcade Trophy.
- **Prizes can't be sold:** everything bought with tokens is tagged and refused by every
  money path.
- **Animations are only a show:** every result is decided and paid first, so closing early
  just prints the result. Bedrock players get shorter animations and plain icons.

### Courier — delivery jobs
Take a run from the PC's job board, carry the parcel to a village house built at the far end,
and get paid by distance and how you travelled (on foot pays more than by elytra).

### Sound Mufflers — hush the noisy stuff
A block you put next to a noisy farm. Its menu opens the moment it's placed. Pick what to hush
from 32 groups (chickens, villagers, pistons, dispensers, doors and more) or any single sound,
including ones it has just heard nearby. Then pick how much: **Quieter** or **Silent**. Sounds
made inside its box are hushed for everyone, wherever they stand. Needs **ProtocolLib**.

### Data-driven and safe to upgrade
- **Config-driven:** recipes, skins, prices, odds, prizes, quests and achievements all live in
  `config.yml` and reload live.
- **Upgrades keep your edits:** a numbered config migration (`config_revision`) changes a value
  only while it still holds the shipped default, and warns about anything an admin changed.
- **Tested:** more than 1,000 unit tests cover migrations, odds, the token ledger, mint numbering,
  player-facing copy, Bedrock glyphs, the live market's hard limits, determinism and 90-day soak
  runs, and the games' exact give-back maths, crash-safe rounds and saved things.

### Build coordinates
- **Paper API:** `io.papermc.paper:paper-api:26.2.build.107-stable`, which is published only
  on `https://repo.papermc.io/repository/maven-public/` (not Maven Central).
- **Java toolchain:** 25.
- **Soft dependencies:** Vault, Towny, WorldGuard/WorldEdit, LuckPerms, PlaceholderAPI,
  Floodgate and ProtocolLib (`net.dmulloy2:ProtocolLib:5.4.0` from Maven Central, compile
  only, for the Sound Muffler). All are optional, and the plugin degrades gracefully without
  them.

---

## Building

**Requirements:** JDK 25 available to Gradle (installed locally, or let Gradle
auto-provision it), and network access to `repo.papermc.io`.

```bash
./gradlew build
# -> build/libs/HomeCraftManagement-<version>.jar   (shaded; SQLite bundled)
```

Drop the jar into your server's `plugins/` folder and start Paper (26.2 or 26.3).

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
- **Courier crate** — one skin per distance band under
  `skins.courier_package.<local|regional|long_haul>`, so the package reads as the
  size of the trip. A single string in place of the map textures every band.
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
  `/hcm give auction`, are no longer handed out (already-placed machines open the
  matching Arcade screen; auctions are reached with `/hcm auction`).

---

## Finite-Stock Market (Phase 2.5)

A real commodities exchange with **finite, conserved stock** — not an infinite
vending machine. The market holds a real **stock** count per commodity:

- **Selling adds** to stock; **buying subtracts**. Stock never goes negative.
- **At zero stock an item is OUT OF STOCK** — you can't buy it at all.
- **Price is a function of stock:** empty ⇒ **ceiling** price (and unbuyable);
  full ⇒ **floor** price; `elasticity` shapes the curve, `inertia` glides it.
  This is the *usual* price: the live market adds a small mood on top (see
  [Live market (0.33)](#live-market-033)).
- **Buy/sell spread:** the market sells to you slightly above, and buys from you
  slightly below, the mid price — kills round-trip arbitrage.
- **Per-item starting stock:** staples (cobblestone) seed with generous stock
  (cheap & available); ores (gold, diamond) start at **0** → ceiling price and
  unbuyable until players sell some in.
- **Daily per-player sell limit** (anti-whale): caps money and/or units sold per
  local day (`clock.time_zone`); resets daily; bypass with `hcm.market.limit.bypass` or
  raise per rank. The live market's event limits (0.33) still apply to bypass holders.
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
- **Price history** is snapshotted periodically (for the Phase 5 dashboard). Since 0.33 it
  records the displayed price, mood included; see [Live market (0.33)](#live-market-033).

Amazon side only; QuickShop is untouched. Money flows through **Vault** (cash is
infinite; only item stock runs dry). The store GUI arrives in Phase 3 — for now
these commands drive the engine:

| Command | Does |
|---|---|
| `/hcm market list` | Every commodity with its buy/sell price and stock. |
| `/hcm market price <item>` | Stock (vs full), buy/sell/mid price, floor/ceiling. While the live market runs, also the usual price, the mood and any badge. |
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
- **Sell to Crate** (a button in the store): click to **sell now** at the live
  price against the same finite stock. Instant because you are the one delivering
  the goods — there is nothing to ship. Every sale **raises Crate's stock**, which
  is what moves the usual price (the live market's mood only adds a little on top).

  It is sell-only on purpose. It used to buy as well, at the live price with no
  shipping and no wait, and nothing would ever have made a player pick a shipping
  tier over that — which left the tiers, the Locker and in-transit orders as
  content that existed and was never used. Buying goes through the store, where
  Express already covers "I want it now" at about five minutes for a fifth more.

**Verify (needs Vault + EssentialsX):**
1. Craft/`/hcm give pc`, place it, right-click → the **Amazon Store** opens.
2. Order cobblestone with **1-Day** vs **3-Day** — see the shipping cost/ETA differ.
3. Set a tier's `real_hours` low (e.g. `0.02`), reload, place an order → it lands
   in **My Orders** as *in transit*, then flips to **ready**; click to collect.
4. Restart mid-transit → the order still delivers on schedule.
5. Open **Sell to Crate** → click an item to sell; stock rises and the price drops.
6. As a non-op, run `/hcm market buy cobblestone 1` → refused, and pointed at the
   store. Selling by command still works.

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

## The Arcade, tokens & packs (0.31)

Tokens are earned by playing (login streak, playtime, quests, achievements, wild
hunts) and spent in the Arcade; they never become dollars, and nothing bought with
them can be sold for dollars. See DESIGN §3.9 and `docs/how-it-works.md` (the
player-facing guide, also in game with `/hcm guide`).

| Command | Who | What |
|---|---|---|
| `/hcm arcade` | `hcm.arcade.use` | The Arcade hub: Wallet, crates, Scratch Ticket, wild-Mini status, Prize Counter, Card Packs, Quests, Achievements, How It Works |
| `/hcm quests` | `hcm.quests.use` | Your daily and weekly quests |
| `/hcm achievements` | `hcm.achievements.use` | Your achievements, grouped, with progress |
| `/hcm guide [tokens\|minis\|wild\|arcade\|games]` | `hcm.guide.use` | How It Works |
| `/hcm trail [name\|off]` | all | Switch your particle trail |
| `/hcm packs` | all / admin | The pack shop; admins get the pack editor |
| `/hcm tokens` | all | Your token balance |
| `/hcm arcade odds` | `hcm.arcade.use` | Players: one line per open game of chance (what it gives back). Admins: the per-stake detail, the Scratch Ticket RTP and each crate's value at counter prices |
| `/hcm tokens give\|set\|take <player> <n>` | admin | Adjust tokens (ledger source `ADMIN`) |
| `/hcm tokens audit [days] [player]` | admin | Tokens earned and spent, by source |
| `/hcm tokens history <player> [n]` | admin | A player's last n token changes |
| `/hcm hunt spawn [rarity] [player]` | admin | A wild Mini now — with no player named it lands like a natural roll (a Mini Lure wins) |
| `/hcm hunt status\|clear` | admin | Live hunts; clear them |
| `/hcm mini repair-escaped [confirm]` | admin | Give back mint numbers old wild escapes burned (dry run first) |
| `/hcm config reset <section> [confirm]` | admin | Put `arcade` (or `arcade.<part>`), `games` (or `games.<part>`), `packs`, `minis.loot.natural`, `minis.effects` or `clock` back to the bundled defaults. Dry run without `confirm`; snapshots config.yml first |
| `/hcm homes refresh [player]` | admin | Recheck the +1 Home perk (re-reads Essentials' `sethome-multiple`) |

Every daily limit — the Arcade's, the market's and the Courier's — rolls over at local
midnight (`clock.time_zone`).

**+1 Home** (Prize Counter › Perks) adds to the homes a player already has. It needs a tier
per total under `sethome-multiple` in `plugins/Essentials/config.yml` (`hcm_2: 2`, `hcm_3: 3`,
… up to your biggest base + 2); a missing one refuses the purchase and logs the line to add.

---

## Games (0.35)

Games to play for tokens, next to the Crates and the Scratch Ticket: five **games of chance**,
eight free **arcade cabinets** (little video games in a menu), and **time trials** and **mini
golf** in a world of their own. All of it sits behind **`games.enabled: false`**, so installing
the jar changes nothing until you set `games.enabled: true` and run `/hcm reload`. DESIGN §3.12
says how it is built; [`docs/how-it-works.md`](docs/how-it-works.md) is the players' version.

### What players get

- **The Games screen:** `/hcm play`, or the Arcade hub's **Play** row. Tabs for All, **Luck**
  (games of chance, plus links to the Scratch Ticket and each crate), **Cabinets**, **Courses**
  and **Golf**. The bottom row has Today's pick (46), High scores (47), Take a break (48) and How
  the games work (50). A closed game shows no tile at all.
- **`/hcm play <game>`** opens one game, rules first. **`/hcm play <course>`** from chat or a join
  sign starts a time trial straight away (its tile on the Games screen opens the course screen
  first); a golf course always opens its course screen. `/hcm play blackjack` opens Twenty-One.
- **The hub's Play row** (row 4, only while the games are on): All games, Today's pick, Luck,
  Cabinets, Courses, Mini golf and Take a break. Row 1's label changes from "Games" to "Luck", and
  its crates and Scratch Ticket follow Take a break: a player on a break sees the "Taking a break"
  tile there, and one without `hcm.games.chance` sees none of them. With the games off, the hub is
  exactly what it was.
- **Today's pick:** one skill game or course a day, the same for everyone, changing at local
  midnight. Its first finish of the day pays `games.featured_bonus`. A game of chance is never
  the pick.
- **High scores:** your best on a board, then its top ten with their names. Only the website
  feed leaves names out.
- **Take a break:** the Wallet's blue bed (slot 51), the hub's Play row, the Games screen, or
  `/hcm play break`. A player sets their own daily limit on the tokens they put into games of
  chance, or pauses them for 1, 7 or 30 days. It works while the games are off, and it also
  covers Crates, Scratch Tickets and Card Packs bought with tokens.
- **Invites:** Connect Four and Tic-Tac-Toe can be played against a friend, and Coin Flip is
  always two players. Java players click **[Accept]**; Bedrock players type `/hcm play accept`
  (or `deny`). Friend-game invites are on until a player turns them off; Coin Flip invites are
  off until the player turns them on, on the Take a break screen.
- **`[Arcade]` join signs:** an admin writes `[Arcade]` on line 1 and a game or course id on
  line 2. A tick later the sign reads **[Arcade] / the game's name / Click to play** and is
  waxed. A click opens the game through the same checks as `/hcm play`. Anyone else who writes
  `[Arcade]` loses that line, even while the games are off, so nobody can make an
  official-looking sign.

### House rules

- **Tokens only.** No game takes or pays dollars, items that can be sold, Cards or Minis. The one
  thing a world game hands you is its kit, and the kit never leaves the game.
- **Every game of chance gives back less than it takes.** Each is worked out, stake by stake, to
  give back between 85 and 95 of every 100 tokens put in, over lots of plays. Config picks the
  target (`rtp`, shipped 90); the 85-95 band is locked in code. A stake that can't land inside it
  is dropped with one WARN, and a game with no stake left stays closed.
- **The odds are shown before you play, from the same numbers the game plays with.** The screen
  says "gives back about 89 of every 100 tokens" (the engine's own number, floored: never the
  config's 90), what each result pays and how often, your plays left and today's tokens.
  `/hcm arcade odds`, the How It Works page and `/api/arcade` read the same engine.
- **Decided first, then shown.** The tokens in, the result and the tokens back are saved in one
  step before any animation. Closing the screen early just prints the result.
- **No near misses, nothing dressed up.** Reels and the Wheel show exactly what was drawn, and a
  spinning frame never shows a paying line. After a loss the screen says "No win this time.":
  no teasing line, no play-again button, no win sound. Getting some tokens back is never called
  a win, and getting exactly your tokens back reads "Your 10 back". A win gets a private title at
  most: no fireworks, no shout.
- **Daily limits.** Each game of chance has its own plays a day (`daily_limit`), and
  `games.chance_daily_tokens` (100) caps the tokens a player puts into all of them in a day. The
  lowest of that, the player's own limit and an admin's limit applies.
- **A cooldown.** Two plays of a game of chance are at least `games.click_cooldown_ms` apart
  (600 ms, never under 250). A click that comes sooner does nothing, silently.
- **Take a break leans the careful way.** A lower limit starts now. A higher one, or none, waits
  `games.break.raise_delay_days` (7) and then starts at midnight. A pause can be made longer,
  never shorter. A parent or admin can set a limit or pause the player can't lift. If the
  settings can't be read, games of chance stay closed.
- **Finished fairly after a crash.** A card game left open (a quit, a restart, or 10 minutes
  untouched) is finished for the player by a fixed rule: Twenty-One stands, and Higher or Lower
  cashes out (a run with no guess yet takes the likelier side first). The player is told on their
  next join. A round is never paid twice, and a round whose game can't finish it gives the
  tokens back.
- **Nothing rewards playing a game of chance:** no quest, achievement, featured bonus or skill
  reward. Skill games pay small, capped rewards, and scores always count.
- **A game that breaks switches itself off** ("That game is taking a break. Try another one!")
  until `/hcm reload`. Its screens close, its world sessions end and its open rounds are
  finished. The rest of the plugin carries on.

### Owner knobs (`games`)

Everything under `games:` reloads with `/hcm reload`. A value out of range is clamped with one
WARN naming the key. A value that can't be read at all closes what it belongs to: junk in these
common keys turns the games off, junk in one game's block closes that game.
`/hcm config reset games` (or `games.<part>`) puts it back as shipped.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` | The master switch. Off, the rest of the plugin is exactly as before |
| `worlds` | `[games]` | The Games worlds: where courses are built and world games pay. Read-only for non-admins while the games are on |
| `play_worlds` | `[]` | Extra worlds (besides the economy worlds and `worlds`) where the games open, such as a hub that isn't an economy world |
| `click_cooldown_ms` | `600` | The pause between two plays of a game of chance (never below 250) |
| `chance_daily_tokens` | `100` | Most tokens a player can put into all games of chance in a day (`0` = off, at most 10000). While the games are on it also counts Crates, Scratch Tickets and token Card Packs |
| `max_payout` | `250` | Most any single play of a game of chance can pay (never below the game's largest stake) |
| `skill_daily_cap` | `6` | Most tokens all skill games together pay a player in a day (first clears don't count) |
| `featured` | `auto` | `auto` picks a skill game or course each day; a game or course id pins it (`/hcm games feature`) |
| `featured_bonus` | `1` | Tokens for the first finish of today's pick (counts toward `skill_daily_cap`) |
| `break.daily_choices` | `[10, 25, 50, 100]` | The daily limits a player can pick (they can also pick none) |
| `break.pause_days` | `[1, 7, 30]` | The pauses a player can pick, in days |
| `break.raise_delay_days` | `7` | How long a raised or removed limit waits before it starts (at least 1) |

### Games of chance

| Game (`/hcm play` id) | How it plays | Tokens in | Gives back, as shipped |
|---|---|---|---|
| **Ore Slots** (`ore_slots`) | Three reels, one line. Three the same pays that ore's line, a Wild stands in for any ore, two the same pays ×2, Stone never pays | 1, 2, 5 | 89.9% at every stake |
| **Twenty-One** (`twenty_one`, or `blackjack`) | Beat the Arcade's hand without going over 21. Hit, Stand, or Double on your first two cards. The Arcade draws to 17 and stops on any 17. At 5 in, a win gives back 9, Twenty-One! 11, and a doubled win (10 in) 18 | 5, 10, 20 | 89.7% with the best play |
| **The Wheel** (`wheel`) | 24 spaces, all just as likely, each showing what it gives | 5, 10, 20 | 87.5%, 89.5%, 90.0% |
| **Higher or Lower** (`higher_lower`) | Guess the next card. Each right guess grows the pot; cash out any time after one. Aces are high; the same card loses | 10, 20, 50 | 89.6%, 90.0%, 89.7% |
| **Coin Flip** (`coin_flip`, ships off) | Two players near each other put in the same; one flip. The winner gets 9, 18 or 45 and the rest is gone | 5, 10, 25 | 90.0% |

The percentages are the engines' exact values floored to one decimal, as admins and the website
see them; players read the whole number ("about 89"). Change an `rtp` and reload: the game works
its payouts out again and shows the new number, with an INFO line if only a value above the
target fits.

| Key | Default | Meaning |
|---|---|---|
| `<game>.enabled` | `true` (`coin_flip`: `false`) | The game's own switch |
| `<game>.stakes` | as above | The tokens a player can put in (1-1000 each; Ore Slots shows at most 7) |
| `<game>.daily_limit` | Ore Slots `50`, Coin Flip `5`, the rest `30` | Plays per player per day |
| `<game>.rtp` | `90` | Target tokens back per 100 put in; held to 85-95 in code |
| `ore_slots.reels.<coal\|copper\|iron\|gold\|diamond\|wild>` | `10`, `8`, `6`, `4`, `2`, `2` | How often each lands (0-10000). Turn one off with 0, never by deleting it. Stone's weight is worked out |
| `ore_slots.pays.<two\|coal\|copper\|iron\|gold\|diamond\|wild>` | `2`, `4`, `6`, `10`, `20`, `40`, `50` | What a line pays, as a multiple of the tokens in (three wilds at 5 in = 250, the `max_payout`) |
| `twenty_one.natural_bonus` | `1.5` | A two-card 21 adds this many times what a normal win adds (1-3) |
| `wheel.segments` | 24 spaces: 13 × 0, 5 × 1, 3 × 2, 2 × 4, 1 × 5 | Clockwise from the top-left: 0 is nothing, 1 your tokens back, above 1 a win scaled per stake. Nothing between 0 and 1. A list of 8 or 12 repeats around the ring |
| `higher_lower.max_multiplier` | `20` | Cash out automatically at this many times the tokens in (or `max_payout`, if lower) |
| `higher_lower.max_guesses` | `10` | Cash out automatically after this many right guesses |
| `coin_flip.pair_daily_limit` | `2` | Flips between the same two players per day |
| `coin_flip.max_distance` | `32` | How close the two must be, in blocks (0 = anywhere in the same world) |
| `coin_flip.invite_seconds` | `60` | How long an invite stays open (10-600) |

Every flip is logged with both names and its seed, and a flip that was called off is logged with
why.

### Arcade cabinets

Free to play. Each solo cabinet has three one-time **milestones** (bronze, silver, gold), each
paying once ever, and a **daily challenge**: today's board is the same for everyone, the first
try is the scored one, and meeting the goal pays a token. Later tries are practice. A personal
best is announced but pays nothing, and friend games never pay.

| Cabinet (`id`) | The game | Daily goal | Milestones |
|---|---|---|---|
| **Creeper Sweeper** (`creeper_sweeper`) | Dig every safe square of a 9×5 board; easy, normal or hard. The first dig is always safe | Clear today's board | Clear times per difficulty |
| **Ore Merge** (`ore_merge`) | Slide and merge ores from coal up to a dragon egg | Make a diamond | Biggest tile: 256, 512, 1024 |
| **Snake** (`snake`) | Turn left or right to eat apples; a little faster every 5 apples | 15 apples | 10, 20, 30 apples |
| **Mini Match** (`mini_match`) | Find the 8 pairs of Minis in as few flips as you can | 24 flips or fewer | 30, 24, 20 flips |
| **Simon Says** (`simon_says`) | Repeat the growing pattern of lights and notes | A pattern of 8 | 5, 10, 15 |
| **Whack-a-Zombie** (`whack_a_zombie`) | Bonk zombies (+1), not villagers (−1), for 30 seconds | 20 points | 15, 25, 35 points |
| **Connect Four** (`connect_four`) | Four in a row on a 7×5 board, against the Arcade (easy, normal, hard) or a friend | The day's first win against the Arcade on normal or hard | none |
| **Tic-Tac-Toe** (`tic_tac_toe`) | Against the Arcade (easy, or hard, which never loses) or a friend | The day's first easy win, or a draw or better on hard | none |

| Key | Default | Meaning |
|---|---|---|
| `<cabinet>.enabled` | `true` | The cabinet's own switch |
| `<cabinet>.milestone_reward` | `1` | Tokens for each milestone, once ever (the six solo cabinets) |
| `<cabinet>.daily_reward` | `1` | Tokens for the daily goal |
| `<cabinet>.daily_cap` | `2` (`connect_four`, `tic_tac_toe`: `1`) | Most tokens that cabinet pays a player a day. `games.skill_daily_cap` still applies on top |
| `<cabinet>.milestones` | as above | Three values (bronze, silver, gold), each better than the last. Mini Match's can't ask for fewer than 8 flips, nor Simon Says' for more than 99 |
| `creeper_sweeper.mines.<easy\|normal\|hard>` | `6`, `8`, `10` | Creepers per board (1-30). The daily board uses normal's |
| `creeper_sweeper.milestones.<easy\|normal\|hard>` | `[180, 90, 45]`, `[240, 120, 75]`, `[300, 180, 120]` | Clear times in seconds (at or under) |
| `snake.tick_java` / `snake.tick_bedrock` | `6` / `10` | Ticks between moves (3-40; 20 = 1 second). It speeds up, never below 3 |
| `whack_a_zombie.seconds` | `30` | How long a round lasts (10-120) |

A player in creative or spectator mode, or in a world where games aren't played, earns nothing
("No tokens can be earned here — scores still count!"), and a one-time reward stays there to earn
later. Today's board there is practice, and the scored try waits for later. Past a cap: "You've
won all the game tokens you can today — scores still count!".

Games screens ignore a double click's extra clicks, and hold clicks for a moment after a board is
dealt, after a "click again" is armed, and after each Twenty-One or Higher or Lower move, so a
double click can't dig a board that just appeared, confirm a quit or take a card nobody saw. The
daily boards are seeded from a secret only the server knows, so nobody can work tomorrow's board
out ahead of time. Creeper Sweeper's daily board arrives already dug open, so its clock starts
when it is dealt.

### Time trials

Parkour, elytra and boat courses in the Games world, built by admins and kept in the database.
Each open course is its own tile on the Courses tab ("River Run (Boat · Medium) - best 1:02.3")
and its own `/hcm play <course>` id; `/hcm play trials` lists them all, easiest first. Tiers are
Easy, Medium, Hard and "Why did we build this?".

- **Starting.** `/hcm play <course>` typed in chat, an `[Arcade]` join sign, or
  `/hcm play <course> <player>` starts the run **straight away**. A tile on the Games screen, the
  course list or the hub's Today's pick opens the **course screen** first: the rules, your best,
  this week's best, the record and who holds it, what it pays, and Start.
- **A run.** You arrive at the start line with only the course kit: Back to checkpoint and Leave
  game (click twice), plus a worn elytra and 3 rockets on an elytra course (back to 3 at every
  ring), or a boat of your own on a boat course. A 3-2-1 countdown holds you at the start (you can
  still look around), and the clock starts on "Go!". Reach every checkpoint in order, then the
  finish. Each move is checked as the line it really travelled, so a fast glide through a ring
  counts and no checkpoint can be skipped. The action bar shows the clock and the checkpoints
  so far.
- **Going wrong** sends you back to your last checkpoint, facing the next one, and the clock keeps
  running. On parkour that's a fall `fall_depth` (6) blocks below the lower of the last and next
  checkpoint, or below the course's own fall height. On elytra it's landing or touching water
  anywhere but the start, a checkpoint or the finish. On a boat it's getting out. Anywhere, it's
  the void, or a short teleport by someone else (a long one ends the game).
- **Fair play.** Flying, a game mode other than adventure, or any potion effect voids the run the
  moment it's seen ("This run won't count - …"); you can still finish it for fun. At the finish, a
  run quicker than the course's shortest time (`min_seconds`) doesn't count, and nor does a leg
  between two checkpoints covered faster than the kind allows (parkour 14, elytra 80, boat 75
  blocks a second, over the gap between the two checkpoints' spheres). Either way the finish says
  "That run didn't count." with why, and nothing is recorded. Nothing is recorded either for a run
  whose course changed layout while it ran.
- **The finish.** The time goes on the course's all-time board and this week's board. Chat shows
  your time, "★ Your first finish…" or "★ New best!", then the record and who holds it (or "★ New
  course record!"), and "★ Best time this week!" when you set it. Then you're sent home with your
  things, and a result screen offers Play again.
- **Rewards:** a course's first finish pays by tier, once ever and outside every cap; setting the
  week's best time on a course pays `weekly_best_bonus`, once per course per week; finishing the
  course of the week pays `course_of_week_bonus`, once a day; and today's pick pays
  `games.featured_bonus`. A new personal best pays nothing. The course of the week is picked by
  the week (the same week as the weekly quests) unless an admin pins one.

| Key | Default | Meaning |
|---|---|---|
| `trials.enabled` | `true` | Every time-trial course's switch |
| `trials.first_clear.<easy\|medium\|hard\|extreme>` | `5`, `10`, `20`, `40` | Tokens for a course's first finish, once ever, by tier (not capped; changing the tier later pays nothing more) |
| `trials.weekly_best_bonus` | `5` | Tokens for setting the week's best time on a course (once per course per week) |
| `trials.course_of_week_bonus` | `2` | Tokens for finishing the course of the week (once a day; counts toward `games.skill_daily_cap` only) |
| `trials.daily_cap` | `4` | Most tokens time trials pay a player a day (first clears don't count) |
| `trials.fall_depth` | `6` | Parkour: blocks below the lower of the last and next checkpoint that count as a fall |
| `trials.min_seconds` | `5` | A run faster than this doesn't count (a course can set its own) |

**Building a course** (`hcm.games.admin`, standing in a `games.worlds` world; the full list is in
[Commands](#commands)):

1. `/hcm games course create <id> <parkour|elytra|boat> [tier]`. A course id is 2-32 lower-case
   letters, digits or `_`, starting with a letter. It can't be a game id or alias, another
   course, or one of `accept`, `deny`, `break`, `leave`, `invites`.
2. Stand where it starts, facing the way it goes: `<id> start`.
3. At each checkpoint, in order: `<id> checkpoint add [radius]`. The default radius is 1.5 for
   parkour, 4 for elytra and 3 for boats (0.5-16, at most 64 checkpoints).
4. `<id> finish [radius]`, then `<id> test` (a run that records nothing and says whether it would
   have counted) and `<id> enable`.

**Layout edits** are the start, a checkpoint added or removed, the finish, and the fall height.
Each bumps the course's layout number (`rev`) and clears its all-time and this week's times, since
a time on the old layout isn't a time on the new one. When there are times to lose, the command
asks you to type it again with `confirm` on the end. The tier, name, shortest time and switches
aren't layout. Deleting a course clears every board of it; first clears already paid stay paid.

### Mini golf

Mini golf in the Games world, where **your Mini is the ball**. Each open course is its own tile
on the Golf tab ("Meadow Links - 9 holes, par 27") and its own `/hcm play <course>` id;
`/hcm play golf` lists them.

- **Starting.** Golf always opens the **course screen** first, however you get there (a tile,
  `/hcm play <course>`, a join sign): the holes and par, your best, the record, **Pick your
  ball**, How to play and **Start**. Only Start takes you anywhere.
- **Pick your ball:** any Mini you own that has a head. Only its look is borrowed, on a plain new
  head: the Mini itself stays in your collection, untouched. The choice is remembered. With no
  Mini it's a plain white ball; a Bedrock player's ball is always a white block (Bedrock can't
  draw head textures reliably).
- **Playing.** Start takes you to hole 1's tee with only the kit: five clubs (Tap, Putt, Chip,
  Swing and Drive, power 1 to 5), Go to my ball, Reset ball (+1 stroke), Scorecard and Leave game
  (click twice). Any click with a club putts the ball the way you look, once it's still and you're
  within 4 blocks. Slime bounces, ice slides, and soul sand, soul soil and honey slow it down; a
  half-block step is climbed only at speed. Water, lava or leaving the hole's bounds puts it back
  on your last spot, +1 stroke. A fast ball rolls over the cup; a slow one drops in.
- **Picked up.** Once the ball stops with the strokes at par + `max_over_par` (3), the hole is over
  and scores exactly that.
- **Between holes** the scorecard shows for 5 seconds (or until Next hole). After the last hole
  your score goes on the course's board (strokes, lower is better), the rewards are paid, you go
  home with your things, and the final scorecard offers Play again. Several players can play one
  course at once, each with their own ball; balls don't meet.
- **Rewards:** a course's first finish (once ever, outside every cap), finishing at par or better
  (once per course per day), each hole-in-one in a round you finish (once per hole per day; the
  title says "Hole in one!", with a harmless firework), and today's pick. A new personal best pays
  nothing. A round left early, or on a course an admin changed meanwhile, records nothing.
- **The ball is only a picture.** Entities follow the game's own physics to draw it: an item
  display for Java players, a small invisible marker stand for Bedrock players. Nobody can pick
  it up, push it or hit it; it is never saved with the world, and any left by a crash are swept at
  start and whenever a chunk loads.

| Key | Default | Meaning |
|---|---|---|
| `golf.enabled` | `true` | Every mini golf course's switch |
| `golf.par_reward` | `2` | Tokens for finishing a course at par or better (once per course per day) |
| `golf.hole_in_one_reward` | `1` | Tokens for a hole-in-one in a round you finish (once per hole per day) |
| `golf.first_clear` | `5` | Tokens for a course's first finish, once ever (not capped) |
| `golf.daily_cap` | `4` | Most tokens mini golf pays a player a day (first clears don't count) |
| `golf.max_over_par` | `3` | Strokes over par before a hole is picked up |

**Building a course** (`hcm.games.admin`, standing in a `games.worlds` world; the full list is in
[Commands](#commands)):

1. `/hcm games golf create <id> [name…]` makes a closed course in the world you stand in. An id is
   up to 32 lower-case letters, digits or `_`, starting with a letter, and can't be a game id or
   alias, another course, a word `/hcm play` keeps, or `create`, `list` or `help`.
2. On each hole's tee, facing down the hole: `<id> hole add <par>` (par 2-6; at most 18 holes).
3. Look at the cup block (within 6 blocks): `<id> hole <n> cup`. Stand at two opposite corners of
   the hole: `<id> hole <n> bounds 1` and `bounds 2`. Out of bounds is leaving that box across the
   ground, or dropping more than 2 blocks below its lower corner.
4. `<id> enable` opens it once every hole has a tee, a cup and both corners, with the tee and cup
   inside the bounds.

**Layout edits** are a tee, a cup, bounds, and adding or removing a hole. Each bumps the course's
`rev` and clears its high scores, after a trailing `confirm` when there are scores to lose. Par and
the name aren't layout. Closing or deleting a course sends anyone playing it home.

### Commands

Admin actions on the Games are logged with who did them; mini golf course edits are the one
exception. `[confirm]` on a course command is needed only when a layout edit would clear times or
high scores. A player in a world game can use only `/hcm play`, `/hcm leave`, `/hcm games` and
`/hcm help`; anything else says "Finish or leave your game first — /hcm leave".

| Command | Who | What |
|---|---|---|
| `/hcm play` | `hcm.games.play` | The Games screen |
| `/hcm play <game\|course>` | `hcm.games.play` | Open a game (rules and odds first). A time trial starts straight away; a golf course opens its course screen |
| `/hcm play <game\|course> <player>` | `hcm.games.admin` or the console | The same for someone else: NPCs, command blocks, a hub |
| `/hcm play break` | `hcm.games.play` | The Take a break screen |
| `/hcm play accept\|deny` | `hcm.games.play` | Answer your latest invite |
| `/hcm play invites [on\|off]` | `hcm.games.play` | Your invite settings. `off` also turns Coin Flip invites off; only the Take a break screen turns them on |
| `/hcm leave` | `hcm.games.play` | Leave the world game you're in; your things come back. Also finishes a trip home that didn't complete |
| `/hcm games status` | `hcm.games.admin` | Every game, open or closed and why, with the odds of the open games of chance; players in world games, saved things waiting to go back, unfinished rounds and today's pick |
| `/hcm games feature <game\|course\|auto>` | `hcm.games.admin` | Pin today's pick (writes `games.featured`), or let the day pick again. Never a game of chance |
| `/hcm games break <player> show\|pause <days>\|limit <tokens\|none>\|clear\|clear-own confirm` | `hcm.games.admin` | A player's Take a break, online or not. `pause` (1-365 days) and `limit` set an admin pause or limit the player can't lift; `clear` removes only those; `clear-own confirm` lifts the player's OWN settings and logs a WARNING |
| `/hcm games scores reset <game> [board\|all] [player] [confirm]` | `hcm.games.admin` | Clear high scores. Without `confirm` it only counts what it would clear |
| `/hcm games saved <player> show\|restore\|return\|discard confirm` | `hcm.games.admin` | A player's things saved by a world game. `show` works offline; `restore` and `return` need them online; `discard confirm` deletes the row (logged) |
| `/hcm games course list` | `hcm.games.admin` | Every time-trial course: open or closed, checkpoints, layout number, the course of the week |
| `/hcm games course create <id> <parkour\|elytra\|boat> [tier]` | `hcm.games.admin` | A new, closed course (tier `easy` unless given: `easy`, `medium`, `hard` or `extreme`) |
| `/hcm games course <id> [info]` | `hcm.games.admin` | Its world, start, checkpoints, finish, fall height, shortest time and record, and what stops it opening |
| `/hcm games course <id> start [confirm]` | `hcm.games.admin` | The start: where you stand, facing the way you look |
| `/hcm games course <id> checkpoint add [radius] [confirm]` | `hcm.games.admin` | A checkpoint here, after the others (radius 0.5-16; default parkour 1.5, elytra 4, boat 3) |
| `/hcm games course <id> checkpoint remove <n> [confirm]` | `hcm.games.admin` | Remove one; the ones after it move up |
| `/hcm games course <id> checkpoint list` | `hcm.games.admin` | The checkpoints, in order, with their radii |
| `/hcm games course <id> finish [radius] [confirm]` | `hcm.games.admin` | The finish, here |
| `/hcm games course <id> fall <y\|off> [confirm]` | `hcm.games.admin` | Below this height a run goes back to its last checkpoint, on any kind of course; `off` goes back to the parkour default (none for elytra and boat) |
| `/hcm games course <id> tier <tier>` | `hcm.games.admin` | Its tier (a first clear already paid isn't paid again) |
| `/hcm games course <id> name <words…>` | `hcm.games.admin` | Its player-facing name |
| `/hcm games course <id> minseconds <n\|default>` | `hcm.games.admin` | Its own shortest believable time, 0-3600 seconds, or back to `trials.min_seconds` |
| `/hcm games course <id> enable\|disable` | `hcm.games.admin` | Open it (it needs a start, a finish and a world in `games.worlds`) or close it (runs already going finish as normal) |
| `/hcm games course <id> tp` | `hcm.games.admin` | To its start |
| `/hcm games course <id> test` | `hcm.games.admin` | Run it, open or not: nothing is recorded, and the finish says whether it would have counted |
| `/hcm games course <id> feature [off]` | `hcm.games.admin` | Pin it as the course of the week, or unpin it |
| `/hcm games course <id> delete confirm` | `hcm.games.admin` | Delete it and all its times |
| `/hcm games golf list` | `hcm.games.admin` | Every golf course: open, closed or not ready |
| `/hcm games golf create <id> [name…]` | `hcm.games.admin` | A new, closed course in the Games world you stand in (named from its id unless given) |
| `/hcm games golf <id> [info\|list]` | `hcm.games.admin` | Its holes (par, tee, cup, bounds), what's missing, and who is playing it |
| `/hcm games golf <id> tp [hole]` | `hcm.games.admin` | To a tee (hole 1 unless given) |
| `/hcm games golf <id> hole add <par> [confirm]` | `hcm.games.admin` | A new last hole, par 2-6, its tee where you stand, facing the way you face (at most 18 holes) |
| `/hcm games golf <id> hole <n> cup [confirm]` | `hcm.games.admin` | The block you look at, within 6 blocks, is the cup |
| `/hcm games golf <id> hole <n> tee [confirm]` | `hcm.games.admin` | Move the tee to where you stand |
| `/hcm games golf <id> hole <n> par <2-6>` | `hcm.games.admin` | Change par (not a layout edit) |
| `/hcm games golf <id> hole <n> bounds <1\|2> [confirm]` | `hcm.games.admin` | One corner of the hole's bounds, at your feet |
| `/hcm games golf <id> hole <n> remove [confirm]` | `hcm.games.admin` | Remove the hole; the holes after it move up |
| `/hcm games golf <id> name <name…>` | `hcm.games.admin` | Rename it |
| `/hcm games golf <id> enable\|disable` | `hcm.games.admin` | Open it (only when nothing is missing) or close it (anyone playing it is sent home) |
| `/hcm games golf <id> delete confirm` | `hcm.games.admin` | Delete it and its high scores (anyone playing it is sent home) |
| `/hcm arcade odds` | `hcm.arcade.use` | Players: one line per open game of chance. Admins: the per-stake detail, the Scratch Ticket and the crates |
| `/hcm guide games` | `hcm.guide.use` | The Games page of How It Works |

### Turning it on, and the Games world

1. **Check the economy worlds.** The games open in `worlds.economy_enabled`, the Games worlds and
   `games.play_worlds`. In the live config.yml, `worlds.economy_enabled` should list your main
   world and your hub world by their real names.
2. **Switch it on:** `games.enabled: true`, then `/hcm reload`. Vault isn't needed: tokens are
   HomeCraft's own.
3. **`games.play_worlds` stays `[]`** unless a world that is NOT an economy world should still
   have the games.

Cabinets and games of chance need nothing more. **Courses and mini golf need a Games world:**

1. Make the world with Multiverse, and put its name in `games.worlds` (shipped `[games]`).
2. **Keep it out of `worlds.economy_enabled`.** The market, PCs and Pallets stay out of it. World
   games still pay tokens there: the session is what proves the play was real.
3. **Set its Multiverse game mode to adventure:** `/mv modify <world> set gamemode adventure`
   (Multiverse-Core 5; on 4.x it was `/mv modify set mode adventure <world>`). Players
   in a game are held in adventure mode anyway; with the world set the same, Multiverse has
   nothing to switch on the way in.
4. **Give it its own Multiverse-Inventories group** with only that world in it (like the creative
   group in [Economy safety](#economy-safety-021)), and keep per-game-mode profiles **off**
   (`share-handling.enable-gamemode-share-handling: false` in Multiverse-Inventories 5's
   `config.yml`, the shipped value; `use_game_mode_profiles: false` on 4.x). A
   player's things are saved after Multiverse-Inventories has swapped them for the Games world's,
   and the game switches them to adventure mode, which a per-game-mode profile would treat as one
   more swap.
5. **Build as an admin.** While the games are on, nobody without `hcm.games.admin` can change a
   Games world ("The Games world can't be changed."), so a friend can't bridge a shortcut across
   a course.

Players take nothing in and lose nothing. Entering saves everything (inventory, XP, health,
food, effects, game mode, where they stood) and hands them the game's kit.
Leaving puts it all back exactly, however they leave: the kit's Leave item (click twice),
`/hcm leave`, finishing, quitting, a restart or a teleport away. Anything that reached them
during the game (an auction delivery, say) is handed over once they're home. They can't be hurt,
get hungry, drop things or open other screens while they play, and the kit never leaves the game.

### For the owner: the older games of chance

The Scratch Ticket and Crates now follow the house rules' copy (no teasing lines, a part refund
reads "Tokens back", no "Open another" button), and Take a break covers them. Their tables are
unchanged and are **not** held to 85-95 in this release: the ticket gives back about 77 of every
100 tokens (77.6%). Decisions left for you:

- retune the ticket into 85-95;
- drop its 3-tokens-back row;
- decide whether its jackpot still gets a server-wide shout;
- the `first_crate` and `jackpot` achievements and the OPEN_CRATE and SCRATCH quest types still
  reward a game of chance (changing shipped quests and achievements needs a `config_revision`).
  The website feed already leaves `jackpot` out; `first_crate` is still published.

### Data

Schema **v34** adds seven tables: `game_rounds` (every round of a game of chance, and each
cabinet's scored daily try), `game_breaks` (Take a break), `game_scores`, `game_rewards`,
`game_saved_state` (a player's things during a world game), `game_courses` and `game_prefs`
(invites, the golf ball, lines waiting for the next join). No `config_revision` bump: every
`games` key is new and back-filled with its comments. A bare `games: false` (or
`games.<id>: false`) is first rewritten as its `enabled` key, so a game you switched off stays
off. Every token a game moves is in the ledger under that game's own source, so
`/hcm tokens audit` shows each game's real flow.

### Verify in game

**The screens and Take a break**

1. With `games.enabled: false`: `/hcm play` says "The games are closed right now.", the hub looks
   as before, and the Wallet has a blue bed (Take a break) at slot 51.
2. Set `games.enabled: true` and `/hcm reload`: no games WARN in the console. `/hcm games status`
   lists every game, open or closed with the reason, and today's pick.
3. `/hcm arcade`: row 1 reads "Luck" and row 4 is the Play row. `/hcm play` opens the Games
   screen; the Luck tab shows the Scratch Ticket ("gives back about 77 of every 100 tokens") and
   the crates.
4. `/hcm play blackjack` opens Twenty-One. `/hcm play nope` says there's no game called "nope".
5. Take a break: pick 25 (it starts now), then 100 (it waits; the clock tile at 17 cancels it).
6. The pause tiles and the pause confirm say "can't be undone" in their names. Pause for 1 day
   and confirm: the hub (row 1 and slot 39) and the Luck tab read "Taking a break until … 12 AM",
   the Scratch Ticket, a crate and a token pack are refused, and cabinets still open.
   `/hcm games break <you> clear-own confirm` lifts it, with a WARNING in the console.
7. `/hcm games break <you> limit 5`, then spin Ore Slots at 5: the next spin says "That's your
   limit for today (5 tokens). It resets at midnight." `show` lists every limit and today's total;
   `clear` removes it.
8. `lp user <you> permission set hcm.games.chance false`, with the games on: `/hcm arcade` shows no
   crate or Scratch Ticket tiles, `/hcm play` has no Luck tab and no games of chance, and
   `/hcm arcade odds` says "Games of chance aren't open to you." Unset it after.
9. As a non-op, `/hcm arcade odds` gives one line per open game of chance; as op, the per-stake
   detail and the crate values too.
10. A Scratch Ticket that pays 3 back shows three "Tokens back: 3" squares and "No win this time.
    You got 3 of your 10 tokens back." A crate result has one button at 22, "Back to the crate",
    and a miss says "No prize this time."
11. As admin, write a sign `[Arcade]` / `snake`: it becomes "[Arcade] / Snake / Click to play",
    waxed, and a click opens Snake. A non-admin's `[Arcade]` line is blanked.
12. `/hcm games feature ore_slots` is refused ("Games of chance are never featured…");
    `/hcm games feature snake` pins it; `auto` lets the day pick again.
13. `/hcm games scores reset snake` gives the count it would clear; add `confirm` to clear it.

**Games of chance**

14. `/hcm play ore_slots`: before the first spin the screen lists every line's pay with "1 in N",
    "Gives back about 89 of every 100 tokens", 50 plays left and "Today: 0 of 100 tokens". A spin
    stops left to right in about a second (Bedrock: 3 steps). Close it mid-spin: the result prints
    once. `/hcm tokens history <you>` shows "Ore Slots: 5 in" and, on a win, "Ore Slots: won 10".
15. After 50 spins the Spin button is grey: "No plays left today".
16. `/hcm play twenty_one`: at 5 in the button says a win pays 9, Twenty-One! 11 and a doubled win
    18; the screen says "With the best play it gives back about 89 of every 100 tokens". Close
    mid-hand and reopen: the same hand. A tie reads "Same total. Your 5 back". Double-click Hit:
    exactly one card is dealt.
17. Log out mid-hand and back in: about 2 seconds later, "Your Twenty-One game from before was
    finished for you: …".
18. Higher or Lower: each button says what you'd have if right, a guess that can't grow the pot
    isn't offered, and Cash out lights up after a right guess.
19. The Wheel at 10: the spaces show 44, 35, 17, "Your 10 back" and nothing; the screen says about
    89 back (87 at 5). "Your 10 back" plays no win sound and doesn't glint.
20. Coin Flip: set `games.coin_flip.enabled: true` and reload. B turns Coin Flip invites on (Take
    a break, slot 31); A invites B; B's screen says "10 tokens each - winner gets 18". After the
    flip, one INFO line in the console. After two flips, the same pair can't flip again that day.
21. `games.ore_slots.rtp: 85` and reload: an INFO line per stake, and the screen shows the new
    number. `games.max_payout: 1`: one WARN, and the 5-token stake is gone.

**Cabinets**

22. `/hcm play creeper_sweeper`: pick Normal; the first dig is safe; clearing says "✔ Cleared in
    m:ss.t!" and "★ New best!". Today's board: the first try is scored, later ones are practice.
    Double-click "Today's board": the board appears and no square is dug.
23. Snake: Start, turn with the side buttons, Back pauses. 10 apples pays +1 token once, ever.
    Bedrock players move every 10 ticks.
24. Ore Merge (arrows at 47, 48, 50, 51; End game at 46, tap twice), Mini Match ("All pairs found
    in N flips!"), Simon Says and Whack-a-Zombie (3 seconds to get ready, then a 30-second clock)
    each play and record a score.
25. Connect Four on hard: the day's first win pays "+1 token (Connect Four: today's win)", once.
    Tic-Tac-Toe on hard: a draw pays.
26. Play a friend: pick them, they accept, you take turns; one leaving says "<name> left the
    game."; nobody is paid.
27. Past 6 tokens from skill games in a day: "You've won all the game tokens you can today —
    scores still count!"

**Time trials**

28. As admin in the Games world: `/hcm games course create cliffs parkour easy` ("Made cliffs
    (Parkour · Easy)"), then `cliffs start`, `cliffs checkpoint add` at two spots, `cliffs finish`
    and `cliffs enable` ("Cliffs is open: /hcm play cliffs"). `cliffs info` says layout 5.
29. `/hcm play cliffs` in chat takes you straight to the start with only Back to checkpoint and
    Leave game; a 3-2-1 holds you there, then "Go!". Its tile on the Courses tab opens the course
    screen, with Start, instead.
30. Each checkpoint shows "Checkpoint 1 of 2 - 0:04.1" with a ping. Fall well below the
    checkpoints: "Back to checkpoint 1", and the clock keeps going.
31. Finish: your time, "★ Your first finish on Cliffs!", "★ New course record!", "+5 tokens (Cliffs:
    first finish)" and the best-this-week reward (up to the daily cap). You're sent home with your
    things, and the result screen offers Play again. Finish faster: "★ New best!", and no tokens
    for the best itself.
32. `/effect give @s minecraft:speed` (or `/fly`) mid-run: "This run won't count - a potion
    effect…". The finish says "That run didn't count. (a potion effect)", and the board doesn't
    change.
33. `/hcm games course cliffs test`: "test run: nothing is recorded"; the finish says "Test run -
    nothing was recorded. It would have counted."
34. `cliffs finish confirm` with times on the board: the layout number goes up and the times are
    cleared. A run going at the time finishes with "the course changed during your run".
35. An elytra course: a worn elytra and 3 rockets, back to 3 at each ring; landing outside a ring
    sends you back to the last one. A boat course: getting out sends you back to the last
    checkpoint, and after `/hcm leave` the boat is gone.

**Mini golf**

36. As admin in the Games world: `/hcm games golf create meadow Meadow Links`; on the tee,
    `meadow hole add 3`; looking at the cup, `meadow hole 1 cup`; at two opposite corners,
    `meadow hole 1 bounds 1` and `bounds 2` ("It's ready. Open it with /hcm games golf meadow
    enable"); then `meadow enable`.
37. `/hcm play meadow` opens the course screen, never a teleport. Pick your ball lists your Minis
    (Bedrock: "Your ball: a white block"). Start: hole 1's tee, holding Tap to Drive, Go to my
    ball, Reset ball, Scorecard and Leave game.
38. Click a club within 4 blocks: the ball rolls the way you look. Further off: "Get within 4
    blocks of your ball…". Into water: "Splash! Back to your last spot, +1 stroke." A slow putt
    drops in, with the hole's title and "In the cup - …"; a hole-in-one says "Hole in one!", with
    a firework.
39. Keep missing on a par 3: at 6 strokes the hole is "Picked up" and scores 6.
40. The ball is only a picture: you can't pick it up, push it or hit it, the Mini you picked is
    still in your collection, and after the round (or a restart mid-round) no ball is left in the
    world.
41. Finish: the total against par and "New best", then +5 for the first finish and +2 at par or
    better. A second round at par the same day pays no par reward. `meadow disable` while someone
    plays sends them home: "Meadow Links was closed by an admin."

**World sessions (any course)**

42. With a full inventory, an effect and some XP, `/hcm play cliffs`: you're in adventure mode
    with only the kit, and `/hcm games saved <you> show` says ACTIVE. The Leave item (click twice)
    brings everything back.
43. `/give` yourself diamonds mid-game: they're in your bag once you're home, once.
44. Disconnect and rejoin mid-game: you're home with everything and no kit anywhere. `/kill`: no
    death screen, you're home ("You're out of the game. Your things are back."). A teleport far
    away or to another world ends the game; a 2-block one doesn't. `/gamemode survival` doesn't
    stick.
45. `/stop` mid-game, then start and join: you're home, "Your things are back — the server
    restarted during your game."
46. In a game, `/hcm auction` says "Finish or leave your game first — /hcm leave"; the binder,
    chests, the ender chest and dropping do nothing. Setting `games.enabled: false` and
    `/hcm reload` mid-game sends you home.

**The website feed**

47. With the games off, `/api/arcade` has only the `scratch_ticket` entry (plus the pot, prizes,
    packs and achievements), no `featured` and no `jackpot` achievement. Its `rtp` (77.6) matches
    `/hcm arcade odds`.
48. With the games on, each open game appears, its `rtpByStake` matching the admin odds; `cliffs`
    appears as `"kind":"parkour","tier":"easy"` with its `record`, and `meadow` as `"kind":"golf"`
    with `holes` and `par`. `web.dashboard.arcade_show_names: true` adds `holder` to records;
    `false` takes it away.

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

**You carry a crate.** A courier run hands you one — a head textured for the band
(`skins.courier_package.<local|regional|long_haul>`) — and the recipient wants it
**in your hand**, not just in your bag. Trade runs don't get one; their cargo is your
own goods. A band you leave blank borrows another band's texture rather than showing
a plain head, because a plain player head is Steve's and you carry this thing for the
whole delivery. A config.yml that still has these three skins blank (they shipped
empty for three releases) has them filled in from the defaults on first start; a
texture you chose yourself is left alone.

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

## Website feeds (0.32)

The dashboard's port also serves the JSON the **LilahCraft website** reads (the WordPress
theme, `Dierks27/Lilah-Craft-Theme`). WordPress fetches the feeds **from its own server**,
caches them (60 s by default), keeps the last good copy and serves its own pages to
visitors, so browsers never talk to the game server, and a feed that is missing or down
only puts that part of the site back on its labelled sample data. The field meanings below
are the ones in the website team's handoff (`HCM-SITE-FEEDS.md`); the site ignores anything
else in the JSON.

| Endpoint | What it is | Needs the token |
|---|---|---|
| `GET /api/market` | Every commodity: prices, stock, 24 h change and three price histories | when one is set |
| `GET /api/minis` | Every Mini in the catalog, with how many have been printed | when one is set |
| `GET /api/news` | **New in 0.33.** Market News: headlines, what is HOT or on sale, the season. See [Live market (0.33)](#live-market-033) | when one is set |
| `GET /api/arcade` | **New in 0.35.** The Arcade: open games with their odds or records, the featured game, the Scratch Ticket's pot, prizes, token packs, achievements. See [`/api/arcade`](#apiarcade) | when one is set |
| `GET /` | The dashboard page. It holds no data; its script fetches `/api/market` from the browser, and `/api/news` too while the live market runs | never |

The feeds are rebuilt by a main-thread task every `web.dashboard.refresh_seconds`; the HTTP
handlers only serve the last build and never touch the game or the database. The real
feeds have no whitespace; the examples are spread out to read.

### `/api/market`

```json
{ "title": "Crate Market", "generatedAt": 1790000000000, "refreshSeconds": 30,
  "items": [ { "id": "iron_ingot", "name": "Iron Ingot", "material": "IRON_INGOT",
    "price": 2.40, "buy": 2.52, "sell": 2.28, "stock": 4200, "maxStock": 10000,
    "change24h": 0.90,
    "history":    [ { "t": 1790000000000, "p": 2.38,   "s": 4150 } ],
    "history7d":  [ { "t": 1790000000000, "p": 2.3125, "s": 4020 } ],
    "history30d": [ { "t": 1790000000000, "p": 2.1,    "s": 3900 } ] } ] }
```

| Field | Meaning |
|---|---|
| `price` | The market price. `buy` / `sell` are what a player pays / is paid. Two decimals. |
| `stock` / `maxStock` | On the shelf now (`0` = sold out) / a full shelf (the item's `full_stock`). |
| `change24h` | A **percent** (`0.9` = +0.9 %) against the oldest snapshot inside the last 24 hours. |
| `history` | The newest 96 snapshots, **oldest first**: 48 hours at the shipped `market.price_history.interval_minutes: 30`. `t` epoch ms, `p` price then (two decimals), `s` stock then. |
| `history7d` | **New.** One point per hour, up to 168 (7 days), oldest first. Same point shape. |
| `history30d` | **New.** One point per six hours, up to 120 (30 days), oldest first. Same point shape. |

Everything that was in the feed before 0.32 is still there, in the same order and written
byte for byte the same (a test pins it against the old builder). About the two long arrays:

- **Buckets are epoch-aligned:** whole UTC hours for `history7d`, and 00:00 / 06:00 / 12:00 /
  18:00 UTC for `history30d`; the newest bucket is the one holding "now". Each point is the
  **latest snapshot in its bucket**, a real row with its own `t`, not an average. A bucket
  with no snapshot in it (the server was off) has no point.
- **`p` is rounded to 4 decimals** (trailing zeros dropped) to keep the JSON small.
- **An array with no points is left out** (the key is absent), so the site keeps that range
  switched off until there is data.
- **Built from the table that was already there.** The half-hourly snapshots have gone into
  `market_price_history` since Phase 2.5, so the arrays survive restarts as they are, and on
  a server that has been running they fill on the first refresh. No new table, no schema
  change. They are re-read after a new snapshot, at most every ten minutes, not on every refresh.
- **Pruning:** `market.price_history.keep_days` (30 shipped; `0` keeps everything) deletes
  snapshots older than that many days after each snapshot, at most **50,000 rows per
  snapshot tick**, so the first prune of a table that has grown for months finishes over a
  few ticks instead of stalling one. Below 30 the 30-day chart comes up short.
- **Upgrading trims old history.** Before 0.32 nothing was ever deleted. To keep everything,
  add `keep_days: 0` under `market.price_history` before the first start on 0.32; a missing
  key is filled in with 30.

### `/api/minis`

```json
{ "generatedAt": 1790000000000,
  "minis": [ { "id": "blue_amethyst", "name": "Blue Amethyst", "rarity": "COMMON",
    "category": "MISC", "series": "…", "cap": 50, "printed": 12, "soldOut": false,
    "skin": "https://textures.minecraft.net/texture/<hex>" } ] }
```

| Field | Meaning |
|---|---|
| `id` | The Mini's id. One entry per Mini, in `minis:` config order. |
| `name`, `series` | With legacy colour codes stripped. |
| `rarity` | `COMMON`, `UNCOMMON`, `RARE`, `EPIC` or `LEGENDARY`. |
| `category` | Upper-case; `MISC` when blank. Categories beyond the shipped list are fine (the site adds them to its filter). |
| `cap` / `printed` | `cap` is `-1` for uncapped. `printed` is how many have been minted so far (`mini_counts.minted`). |
| `soldOut` | Capped and `printed >= cap`. |
| `skin` | The Mini's Base64 `texture` decoded to its `textures.SKIN.url`, forced to `https://`. Sent only when it is exactly `https://textures.minecraft.net/texture/<hex>` (the one shape the site accepts); missing or undecodable, the field is left out. |

**No player data of any kind:** no owners, holders, provenance, UUIDs or balances. Counts only.

### `/api/arcade`

**New in 0.35.** What the site's Arcade page shows: every open game with the odds it publishes
(games of chance) or its record (skill games), today's pick, the Scratch Ticket's pot, the Prize
Counter, the Card Packs sold for tokens and the achievements. Each game writes its own entries
from the same engine it plays with, so the site's numbers are the game's numbers. The example
is the test's (`ArcadeFeedTest`), shortened where it says `…`; a server publishes its own.

```json
{ "generatedAt": 1790000000000,
  "games": [
    { "id": "scratch_ticket", "name": "Scratch Ticket", "kind": "chance", "stakes": [10],
      "rtp": 77.6, "rtpByStake": { "10": 77.6 },
      "paytable": [ { "stake": 10, "combo": "3 tokens", "pays": 3, "chance": 0.4, "oneIn": 3 },
                    "…",
                    { "stake": 10, "combo": "the jackpot", "pays": 137, "chance": 0.01, "oneIn": 100 } ] },
    { "id": "ore_slots", "name": "Ore Slots", "kind": "chance", "stakes": [1, 2, 5], "rtp": 89.7,
      "rtpByStake": { "1": 89.7, "2": 89.7, "5": 90.0 }, "dailyLimit": 50,
      "paytable": [ { "combo": "3 diamond", "pays": 40, "chance": 0.002421, "oneIn": 413 },
                    { "combo": "two the same", "pays": 2, "chance": 0.25, "oneIn": 4 } ] },
    { "id": "wheel", "name": "The Wheel", "kind": "chance", "stakes": [5, 10], "rtp": 89.5,
      "rtpByStake": { "5": 90.0, "10": 89.5 }, "dailyLimit": 30,
      "paytable": [ { "stake": 5, "combo": "17 tokens", "pays": 17, "spaces": 3, "of": 24 },
                    { "stake": 10, "combo": "your 10 back", "pays": 10, "spaces": 21, "of": 24 } ] },
    { "id": "twenty_one", "name": "Twenty-One", "kind": "chance", "stakes": [5], "rtp": 90.1,
      "rtpByStake": { "5": 90.1 }, "dailyLimit": 30,
      "rules": "Beat the Arcade's hand without going over 21.",
      "payouts": { "5": { "win": 9, "twentyOne": 11, "doubleWin": 19 } } },
    { "id": "higher_lower", "name": "Higher or Lower", "kind": "chance", "stakes": [10], "rtp": 90.5,
      "rtpByStake": { "10": 90.5 }, "dailyLimit": 30,
      "rules": "Guess higher or lower. Cash out any time.", "maxMultiplier": 8, "maxGuesses": 6 },
    { "id": "coin_flip", "name": "Coin Flip", "kind": "chance", "stakes": [5], "rtp": 90.0,
      "rtpByStake": { "5": 90.0 }, "dailyLimit": 5,
      "paytable": [ { "stake": 5, "combo": "win the flip", "pays": 9, "chance": 0.5, "oneIn": 2 } ] },
    { "id": "creeper_sweeper", "name": "Creeper Sweeper", "kind": "cabinet", "board": "normal",
      "unit": "ms", "lowerIsBetter": true, "best": 18400 },
    { "id": "river_run", "name": "River Run", "kind": "boat", "tier": "medium",
      "record": { "ms": 61234, "at": 1789990000000 } },
    { "id": "golf_meadow", "name": "Meadow Links", "kind": "golf", "holes": 9, "par": 27,
      "record": { "strokes": 24, "at": 1789980000000 } } ],
  "featured": { "game": "river_run", "until": 1790035200000 },
  "jackpots": [ { "game": "scratch_ticket", "tokens": 137 } ],
  "prizes": [ { "id": "night_vision", "name": "Night Vision", "category": "boosts", "cost": 6,
                "description": "See in the dark for 20 minutes." }, "…" ],
  "packs": [ { "id": "starter", "name": "Starter Pack", "cost": 50,
               "odds": { "COMMON": 62, "UNCOMMON": 28, "RARE": 9, "EPIC": 1 } } ],
  "achievements": [ { "id": "first_sale", "name": "Sell something to Crate",
                      "description": "Sell something to Crate", "tokens": 10 } ] }
```

| Field | Meaning |
|---|---|
| `games` | Every open game in catalog order, after the Scratch Ticket; the time trials and mini golf publish one entry per course. `kind` is `chance`, `cabinet`, `parkour`, `elytra`, `boat` or `golf`. An id is published once, so the site can key on `id` |
| `stakes` / `rtp` / `rtpByStake` | A game of chance's stakes, and what each gives back in percent: the engine's exact value **floored** to one decimal, the same number `/hcm arcade odds` gives admins. `rtp` is the lowest of them. A stake is published only with its RTP, and a game with no stake left is left out |
| `dailyLimit` | Plays per player per day (the Scratch Ticket has none) |
| `paytable` | What each result pays. `chance` has 4 significant digits and never reads `0`; `oneIn` is the number the game's screen shows. With `stake`, `pays` is tokens at that stake; without it (Ore Slots), `pays` is a multiple of the tokens put in. The Wheel counts `spaces` of `of` (24) instead of `chance`, one row per stake per prize, "your N back" and nothing included. A row without its odds is never published |
| `rules` | One plain line, where the game gives one |
| `payouts` | Twenty-One: per stake, what a `win`, a `twentyOne` and a `doubleWin` pay back (no paytable) |
| `maxMultiplier` / `maxGuesses` | Higher or Lower: where a run cashes out by itself |
| `board` / `unit` / `lowerIsBetter` / `best` | A cabinet's published board, its unit (`ms`, `points`, `flips`, `apples` or `wins`) and the server record, `best` (absent until there is one) |
| `tier` / `record` | A course's tier (`easy`…) and record `{ms, at}`. Golf has `holes`, `par` and `record` `{strokes, at}`. `at` is epoch ms |
| `featured` | Today's pick (a game or course id) and `until`, the next local midnight. Only when that id is in `games` |
| `jackpots` | The Scratch Ticket's pot now. Whenever it is there, `games` has the `scratch_ticket` entry too, so the site never shows a pot without its odds. Its `rtp` is the steady-state figure `/hcm arcade odds` prints |
| `prizes` | The visible Prize Counter rows, without Trade In, Quest Reroll and the Rare Card. `category` is the counter's tab in lower case (`boosts`, `hunt`, `cosmetics`, `perks`, `trophies`, `minis`); the +1 Home gives its first price |
| `packs` | Packs sold for tokens, with the rarity odds they roll with, as percents |
| `achievements` | Enabled achievements. `name` and `description` are both the one line the screen shows. One won by a game of chance (`jackpot`) is left out |

`generatedAt` always comes first. A section with nothing in it is left out rather than sent empty:
with the games off there are no game entries and no `featured`, and with `arcade.enabled: false`
the Scratch Ticket, `jackpots`, `prizes`, `packs` and `achievements` go too. A game whose feed
fails is switched off like any failing game, and whatever it wrote is dropped, never the feed.
Colour codes are stripped.

**No player data:** no UUIDs, balances, per-player limits, winners or names. A record is a score
or a time and a date. The one exception is **`web.dashboard.arcade_show_names`**, shipped
`false`: only while it is `true` does a record carry its `holder`, the name of whoever set it. It
is read on every refresh, so `/hcm reload` applies it. A game's extra keys that would name a
person or a balance, and any UUID-shaped text, are dropped at any depth.

### The feed token

- **`web.dashboard.feed_token`**, blank by default. Blank is how it always worked: anyone
  who can reach the port can read the feeds. Set it (long, random, in quotes, **plain ASCII**:
  letters, digits and punctuation, e.g. `openssl rand -hex 32`) and put the same value in
  WordPress: Settings → LilahCraft → **Feed token**. `/hcm reload` applies it. A token with
  accented letters or symbols like € logs a warning at start: the dashboard page can't send it.
- **When it's set,** every `/api/*` feed goes through one gate that wants
  `Authorization: Bearer <token>`. Anything else gets **`401`** with `WWW-Authenticate: Bearer`,
  `Cache-Control: no-store` and the body `{"error":"unauthorized"}`.
- **Constant-time:** the gate (`FeedAuth`) keeps only the token's SHA-256 and compares digests with
  `MessageDigest.isEqual`, so the check takes the same time for every wrong guess and doesn't
  leak the length. The token is never logged or echoed; the startup line only says whether a
  token is on.

**Keeping the dashboard page working.** The page fetches `/api/market` (and, while the live
market runs, `/api/news`) from the browser, which has no token. Of the handoff's two options
this takes **"the page gets its data another way"**: the page asks for the token itself.
The LAN option is there too, but **off by default**:

- **The page asks for the token.** When a feed answers 401, the page forgets any token
  it had, stops refreshing and shows a password box with one line: "This dashboard needs the
  feed token (web.dashboard.feed_token in config.yml)." **Unlock** keeps the token in that
  browser (`localStorage`, key `hcmFeedToken`; when storage is blocked, only until the page
  is reloaded), sends it as `Authorization: Bearer …` from then on, and never shows it. So
  with a token set, each browser asks once.
- **`web.dashboard.lan_skips_token: false`** (the default). Set it `true` and a request from
  this PC or the home network skips the token: loopback, the private ranges (10.x,
  172.16–31.x, 192.168.x), link-local (169.254.x, `fe80::`) and IPv6 unique-local (`fc00::/7`); an IPv4
  address wrapped in IPv6 (`::ffff:192.168.1.5`) counts as that IPv4 address. Everything else
  needs the token, including CGNAT/Tailscale addresses (100.64.x to 100.127.x).
- **A proxied request never counts as local,** whatever address it arrives from. If any of
  `Forwarded`, `X-Forwarded-For`, `X-Forwarded-Host`, `X-Forwarded-Proto`, `X-Real-IP`,
  `CF-Connecting-IP`, `True-Client-IP`, `X-Client-IP`, `Fastly-Client-IP` or
  `X-Cluster-Client-IP` is present (any capitalisation), the token is required. A reverse
  proxy on this PC that adds one of them can't turn internet traffic into LAN traffic.

> **Why the LAN switch ships off: tunnels.** config.yml's own advice for reaching the port from
> outside is a Playit tunnel, and a Playit agent on this PC, like `ssh -R`, WireGuard, frp or
> cloudflared, delivers internet traffic from `127.0.0.1` or a LAN address without a forwarding
> header. With `lan_skips_token: true` behind one of those, the internet gets in **without the
> token**. Turn it on only when every outside request reaches the port from a public address
> (say, a VPS proxying to your router's forwarded port) or through a proxy that adds
> `X-Forwarded-For`.

### gzip

Every `/api/*` feed is gzipped (`Content-Encoding: gzip`) when the request's
`Accept-Encoding` allows `gzip` (or `x-gzip`, or `*`) with a q above 0. The compressed copy is
made the first time a client asks for it after each rebuild, then reused until the next one.
Every 200 from a feed sends `Vary: Accept-Encoding`,
`Content-Type: application/json; charset=utf-8` and `Cache-Control: no-store` (the 401 above
carries the last two). A `HEAD` request gets the same headers with no body. It matters
here: each item can carry up to 96 + 168 + 120 history points.

### Exposing the feeds (on the VPS)

Only the WordPress server needs the feeds. Put the port behind https on the VPS (a reverse
proxy or a tunnel to this PC's dashboard port, ideally allowing only the web host) and set
the token. Then in WordPress, Settings → LilahCraft: Market feed URL `https://…/api/market`,
Minis feed URL `https://…/api/minis`, and the same Feed token. Read the warning above before
choosing a tunnel.

Not built yet: part 4 of the handoff (a volume `v` on each history point with `volume24h`,
and `GET /api/trades`). Its exact shape gets agreed with the theme first.

**Verify** on the server PC (port 8080 as shipped; replace `<token>` with yours. On Windows,
type `curl.exe` so PowerShell doesn't substitute its own `curl`):

```bash
# 1. No token configured: every Mini in the shape above, and nothing in it names a player.
curl http://127.0.0.1:8080/api/minis

# 2. Token configured (web.dashboard.feed_token, then /hcm reload).
curl -i http://127.0.0.1:8080/api/minis
#    -> HTTP/1.1 401, WWW-Authenticate: Bearer, {"error":"unauthorized"}
curl -i -H "Authorization: Bearer <token>" http://127.0.0.1:8080/api/minis
curl -i -H "Authorization: Bearer <token>" http://127.0.0.1:8080/api/market
#    -> both HTTP/1.1 200
#    The dashboard page, http://127.0.0.1:8080/, asks for the token once, then shows its data.
#    (With lan_skips_token: true, the first request answers 200 from this PC; add
#    -H "X-Forwarded-For: 203.0.113.7" to make it look proxied and get the 401.)

# 3. history7d / history30d are there, and still there after a restart.
curl http://127.0.0.1:8080/api/market

# gzip: the response headers include Content-Encoding: gzip and Vary: Accept-Encoding.
#    (On Windows, write -o NUL in place of -o /dev/null. With a token set, add the
#    Authorization header to this and to step 3.)
curl -s -D - -o /dev/null -H "Accept-Encoding: gzip" http://127.0.0.1:8080/api/market
```

On lilahcraft.com, with the URLs and token in Settings → LilahCraft: Market and Minis show
live data (no "Sample data" label), the settings page says the last fetch worked, and the
Market page's 7D and 30D ranges turn on once `history7d` / `history30d` have points, with no
theme change.

---

## Live market (0.33)

Crate's prices now **move a little on their own**, so there is something to watch and a
reason to check back. Stock never moves by itself. The live market takes the price the stock
curve gives, the **usual price**, and multiplies it by a small **mood** for each item:

```
price = usual price × mood        (then kept inside the item's floor..ceiling)
```

- **The mood is locked in code to 0.75×–1.25×**, so no price is ever more than 25% from its
  usual price, whatever config.yml says. config.yml can make the market calmer, never wilder:
  a value past a code limit is clamped with a warning naming the key. Narrowing
  `max_up_percent` or `max_down_percent` narrows both sides, and `drift.half_life_hours` is at
  least 24.
- **The live market never changes stock and never changes the usual price.** Only players'
  trades (and `/hcm market setstock` / `resetstock`) move those, exactly as before.
- **An item with no stock is never moved by the mood.** It sits at its usual price: exactly
  its ceiling for gold and diamond on day one or after `setstock`, a hair under it after
  players buy it out (iron: $39.99 of $40.00). Buying the last unit on the shelf never gets
  the mood's discount, so selling one into an empty item and buying it straight back never
  pays.
- **`market.sim.enabled: false` puts every price back exactly as it was in 0.32**, down to the
  last cent of every order total and feed. Screens, displays, placeholders and the dashboard
  page go back to 0.32's too (a `@news` board bound earlier just reads "The Crate Market is
  calm today.").
- The maths is in DESIGN §3.1 "The live market (0.33)", and the bounds are in DESIGN §11 #11.

### How big the swings are: Tight

The owner chose the **Tight** swing size. These are the shipped numbers, with the hard limit
the code puts on each:

| Layer | What it does (shipped) | Hard limit in code |
|---|---|---|
| Drift | A quiet wander: about **1% a day** on cheap staples, **2% a day** on dearer items (√(floor × ceiling) ≥ $10). Half of any wander fades in 66 hours | ±8%, and never faster than about 3% a day (half-life at least 24 hours) |
| HOT / DEAL | +8–15% (HOT) or −8–15% (DEAL) for 30–54 hours, about one of each a week. The item then rests for 7 days | 15% |
| NEWS FLASH | A sudden 15–25% jump UP or DOWN, half gone in 6 hours and gone after 30. About 5 a week, at most 2 a day | 25% |
| Seasons + real world | Small calendar nudges (Harvest Time: wheat −4% until Oct 31) and real commodity prices (off by default) | 4.5% together, and never more than 45% of the spread |
| The whole mood | Everything above added up | **0.75×–1.25×**, always the same both ways |

On a model of these exact rules (3 seeds × 90 days, the shipped catalog, someone online most
evenings), there were 0.71–0.78 news flashes a day, all landing while someone was online. HOT
and DEAL each started 1.17–1.24 times a week. The mood ranged from 0.766 to 1.231 and averaged
0.996–1.001. From drift alone, staples stayed within ±3% of usual on 94–97% of days. With a
large catalog, most items are quiet most days.

### What players see

- **Badges** on the Store and Sell to Crate tiles, on signs, holograms and TVs:
  - **★ HOT**: Crate pays more, sell now. The tile gets an enchant glint.
  - **✦ DEAL**: cheaper in the store, "Limit N a day". The tile gets an enchant glint.
  - **▲** / **▼**: shown after a news flash.
  - **» WANTED**: on the Sell screen, for an item Crate has none of.

  A tile also says "Usually $X" when its price is 3% or more away from usual.
- **NEWS FLASH:**
  - three chat lines: a happy headline, then what Crate pays or charges, before → after;
  - a title across the screen, the action bar, a bell, and sparkles at that item's signs,
    holograms and TVs.

  The price moves the moment it is announced, never before.
- **HOT and DEAL** are announced once they are at full strength. A "Last call!" line comes
  about 3 hours before they start cooling, and one quiet line when they end. The % a HOT or
  DEAL announces is its price against the usual price at that moment, the same % its tile
  shows. Seasons and real-world moves get a line of their own: a season only if one of its
  nudges still applies, a real-world move only if it moved Crate's price by a whole percent or
  more. The very first time, everyone gets "The Crate Market is LIVE!".
- **Never spammy:** at most one market announcement at a time, 20 minutes apart, 6 a day, and
  only 07:00–21:00 local time. A news flash that comes due while nobody is on waits until
  someone has been online a few minutes. If nobody comes on before 21:00, it moves to the next
  morning. Time the server is off doesn't count as waiting: a flash that came due then is due
  again once it is back up.
- **"While you were away"** a few seconds after joining: the newest 3 things from the last 48
  hours that are newer than the last news you heard (a HOT or DEAL counts from when it reached
  full strength, even if it started quietly before), and a "Right now" line with what is HOT
  or on sale and how long it has left. It comes at most once per 10 minutes: not within 10
  minutes of your last catch-up or of the last announcement you heard (a "Last call!" or
  ending line doesn't count). On the first join after the upgrade, only the "Right now" line
  shows, so nobody is flooded with old news.
- **Market News:** the bell in the Sell to Crate screen (slot 50) opens a screen with:
  - what is going on right now;
  - the 9 newest stories (click an UP or HOT one to sell some, a DOWN or DEAL one to order
    some). Each shows what Crate pays (UP, HOT) or charges (DOWN, DEAL), before → after, as
    the chat flash did; WANTED and season stories show no prices;
  - a switch that turns news in your chat off.

  `/hcm market news` prints the same in chat, and `/hcm market news off|on` mutes it. Muted
  players get no chat, title, sound or catch-up.
- **Sort by "Hot & Deals"** in the Store and on the Sell screen, only while the live market
  runs. A saved or default HOT sort (`menus.store.default_sort: HOT`) shows as Name A–Z while
  it is off.
- **The Market News board:** bind a hologram or TV to `@news` (`/hcm display tv @news`, or the
  bell in slot 0 of the picker) to show the newest headline and what is HOT or on sale. It is
  offered only while the live market runs (the picker, `/hcm display tv @news` and tab
  completion). With it off, `@news` is refused as an unknown commodity, as in 0.32, and boards
  bound earlier stay and read "The Crate Market is calm today." Catalog ids starting with `@`
  are reserved and are skipped at load with a warning, even with the live market off; the
  admin GUI never makes one.
- **PlaceholderAPI:**
  - `%hcm_news%`, `%hcm_news_age%`, `%hcm_hot_list%`, `%hcm_deal_list%` and `%hcm_season%`;
  - per item: `%hcm_status_<item>%`, `%hcm_badge_<item>%`, `%hcm_usual_<item>%`,
    `%hcm_mood_<item>%` and `%hcm_endsin_<item>%`.

  These are answered only while the live market runs; with it off or paused PlaceholderAPI
  leaves them as typed, as in 0.32. `%hcm_price_<item>%` and `%hcm_trend_<item>%` include the
  mood.
- **"Prices just moved! Take another look."** If a price jumps while a Sell quantity or
  checkout screen is open, confirming is refused once and the new price is shown. Nobody pays
  a price they did not see.
- **Headlines** are short, happy and about people *wanting* things ("The villagers are having
  a party and need Wheat!"). They never claim Crate's shelf changed, because it didn't.
  Headlines you write under `market.sim.headlines` are checked at load and skipped with a
  warning if one:
  - is too long,
  - uses a banned word or one of its forms (war, fire, crash, crashed, fighting, stolen, …), or
  - talks about the shelf (stock, restock, sold out, sold-out, …).

  A list left empty falls back to the shipped one.

### Event limits apply to everyone, ops included

- While an item is **HOT** or **UP**, each player can sell at most its daily sell cap
  (`max_daily_sell`, or 2% of `full_stock` when unset). For an item with a `max_daily_sell`,
  normal players already had that cap and it now also binds anyone with
  `hcm.market.limit.bypass`; for an item without one, 2% of `full_stock` applies to everyone
  while it is HOT or UP. (`hot.sell_limit: false` turns this off, for HOT and UP alike.)
- While an item is on a **DEAL** or **DOWN**, each player can order at most half its daily buy
  cap (`buy_limit_share: 0.5`). That is 40 Iron Ingots or 160 Oak Logs a day, bypass or not.
  Once the DEAL or DOWN is showing its badge, the refusal says "Sale limit: you can buy … a
  day while it's on sale." (and a HOT or UP's says "Crate buys up to … a day while the price
  is up.").
- The limit shows on the tiles ("Limit N a day", "Up to N a day") only while the event's badge
  shows. During a HOT or DEAL's silent ramp, and in an UP or DOWN's tail after its badge has
  gone, the limit still applies but nothing names it: the refusal is the ordinary daily-limit
  message, so it never gives away an event that has not been announced.
- The usual daily money limits and per-item caps still apply, at the moved price. A Courier
  trade run sells at the moved price and under the same caps.

### Playing as op: the must-do

Every op gets `hcm.market.limit.bypass` through `hcm.admin`, and that skips **every** normal
daily cap. The event limits above still bind you. The quiet drift and the seasons, though
(at most 12.5% together), would apply to as much as you care to sell. To trade under every cap
like everyone else, switch the bypass off on your own account:

```
lp user <name> permission set hcm.market.limit.bypass false
```

You keep every other admin command. While anyone online holds the bypass,
`/hcm market sim status` says so. To give it back:
`lp user <name> permission unset hcm.market.limit.bypass`.

### How to un-stick a price

Two different things can look stuck.

- **The usual price.** It moves only when somebody trades. Inertia glides it toward the stock
  curve one traded unit at a time, and the live market never touches it. Moving it on a timer
  would change prices without stock changing, which is exactly what the live market must not
  do. To snap one item's usual price onto its curve without changing its stock, set its stock
  to what it already is:
  1. `/hcm market price <item>` shows the stock.
  2. `/hcm market setstock <item> <that same number>` snaps the price.

  The reply shows the new mid price, with the usual price beside it when the mood has moved it.
- **The mood.**
  - `/hcm market sim stop <item>` fades that item's HOT, DEAL or news out over an hour.
  - `/hcm market sim reset <item>` puts its drift back to 0 and ends its events at once.
    `reset all` asks you to confirm.
  - `/hcm market sim pause` turns the whole live market off, with every price back to usual,
    until `/hcm market sim resume`. It stays paused across restarts.
  - To keep an item still for good, set `sim: false` on its catalog row. To turn the whole
    thing off, set `market.sim.enabled: false`.

### Owner knobs

Everything is under `market.sim` in config.yml and reloads with `/hcm reload`. The code limits
above clamp anything written here.

| You want | Change |
|---|---|
| The live market off | `enabled: false` under `market.sim`, or `/hcm market sim pause` without touching config. A bare `market.sim: false` (or `off`/`no`) also works: the next start or `/hcm reload` rewrites it as `market.sim.enabled: false` and fills in the rest of the section, still off. The switch fails safe: a value that is not true or false (`enabled: 0`, `enabled: disabled`, a typo) turns the live market off, with a warning naming the key |
| HOT and DEAL more or less often | `hot.gap_hours` / `deal.gap_hours` (`[48, 120]`) |
| News more or less often | `news.gap_hours` (10) plus `news.extra_hours` (14), and `news.max_per_day` (2) |
| Calmer prices | Lower `drift.calm_percent` / `drift.lively_percent`, `hot.percent` / `deal.percent`, `news.percent`, or `max_up_percent` / `max_down_percent` (the narrower of the two is used both ways, with a warning when they differ). A longer `drift.half_life_hours` makes the drift slower; it is at least 24. A `calm_percent` or `lively_percent` that would move prices faster than the shipped liveliest at that half-life is held back, with a warning giving the size it is held to |
| Different announcement hours | `news.hours: "07:00-21:00"` (local, `clock.time_zone`) |
| Fewer effects | `announce.title`, `action_bar`, `particles`, `chat`, `endings`, `intro`, `catch_up` |
| Flashes that don't wait for players | `news.wait_for_players: false` |
| One item left alone, or tuned | On its `market.catalog` row: `sim: false` (never moves), `volatility` (0 = no drift, at most 1.5), `sim_weight` (0 = never picked for HOT, DEAL or news), `news_name: "Diamonds"` (the name headlines use, at most 24 characters) |
| Your own headlines | `headlines.up/down/hot/deal/wanted`, `real_up`, `real_down`. `{item}` is the plural name, `{Name}` the singular, `{real}` the real-world thing |
| Your own seasons | `seasons.list`: id, name, `from`/`to` as local `MM-DD` (inclusive, may wrap the year), percent per item id or `"*"`, and a headline. With no usable name, players see the id as words (`harvest_time` → Harvest Time). `seasons.enabled` turns them off |
| Different sounds | `announce.sound_*`. Keep them namespaced (`minecraft:block.note_block.bell`). A plain `BLOCK_NOTE_BLOCK_BELL` would turn into the XP-orb sound, so the plugin swaps it for the shipped sound with a warning |

The pace is two numbers: `gap_hours` for HOT and DEAL, and `gap_hours` plus `extra_hours` for
news.

### Commands

| Command | Who | What |
|---|---|---|
| `/hcm market news` | everyone | Right now (HOT and DEAL with time left, and the season), then the last 8 headlines from 7 days, with ages |
| `/hcm market news on\|off` | everyone | Market news in your own chat |
| `/hcm market price <item>` | everyone | Now also the usual price, the mood and any badge |
| `/hcm market news <item> <up\|down\|wanted> [percent] [quiet]` | `hcm.market.sim` | A news flash now, sized 10–25% (default: a roll in `news.percent`). Skips the schedule, cooldowns, hours, waiting and daily caps, but still respects the limits and the item's headroom, and is refused if that leaves under 5%. `wanted` is only for an item with no stock. `quiet` leaves out chat, title, action bar, sound and particles |
| `/hcm market sim status [item]` | `hcm.market.sim` | What the live market is doing. With an item: its drift, event, season, multiplier and headroom |
| `/hcm market sim hot\|deal <item> [percent] [hours]` | `hcm.market.sim` | A HOT or DEAL at full strength now, announced (at most 15%; `12` or `12%` is the size, `24h` the hours). Refused if the item already has an event |
| `/hcm market sim stop <item\|all>` | `hcm.market.sim` | End events with a 1-hour fade |
| `/hcm market sim reset <item\|all>` | `hcm.market.sim` | Drift back to 0 and events ended now. `all` asks you to confirm within 10 s |
| `/hcm market sim pause\|resume` | `hcm.market.sim` | Pause = the same as `enabled: false`, kept across restarts. Resume = the same as turning it back on (drift from 0, fresh schedule) |
| `/hcm market sim preview <item> [days]` | `hcm.market.sim` | One possible future (7 days by default) as a sparkline with its low and high. Changes nothing |
| `/hcm market sim audit [days]` | `hcm.market.sim` | What the live market paid out or saved players, per item, from the ledger. Only trades made at a moved price count |
| `/hcm market sim real status\|test <symbol>\|fetch` | `hcm.market.sim` | Real-world prices; see the checklist below |

Forced events are logged with who forced them and show `source: "admin"` in `/api/news`.

### Feeds

The feed token, gzip and refresh all work as in [Website feeds (0.32)](#website-feeds-032).
**Nothing new is written while the live market is off or paused:** `/api/market` is then
byte for byte what 0.32 wrote. A HOT or DEAL that is still quietly rising is never in either
feed until it has been announced in game.

`/api/market`: each sim-enabled item gains these fields after its history arrays:

| Field | Meaning |
|---|---|
| `usual` | The usual price, before the mood. `price`, `buy` and `sell` include the mood |
| `moodPct` | How far `price` is from `usual`, in percent (`11.70` = +11.7%) |
| `status` / `statusEndsAt` | `hot`, `deal`, `up`, `down` or `wanted` while a badge shows / when it ends, in epoch ms (none for `wanted`) |
| `events` | Up to 20 marks from the last 30 days, oldest first, for chart markers: `t`, `kind` (`hot`, `deal`, `up`, `down` or `real`), `pct` |

At the top level, after `items`, it gains:
- `"live": true`;
- `hot` and `deals`, as item ids;
- `season` (`id`, `name`, `endsAt`), left out when there is none;
- `news`, the 5 newest rows, shaped like `/api/news`.

```json
{ "id": "iron_ingot", "name": "Iron Ingot", "…": "…", "history30d": [ … ],
  "usual": 22.49, "moodPct": 11.70, "status": "hot", "statusEndsAt": 1790190000000,
  "events": [ { "t": 1789500000000, "kind": "down", "pct": -18.00 },
              { "t": 1789950000000, "kind": "hot",  "pct": 11.70 } ] }
```

`GET /api/news` (new):

```json
{ "generatedAt": 1790000000000, "live": true,
  "season": { "id": "harvest_time", "name": "Harvest Time", "endsAt": 1793509200000 },
  "active": [ { "item": "iron_ingot", "name": "Iron Ingot", "kind": "hot", "pct": 11.70,
                "startedAt": 1789950000000, "endsAt": 1790190000000 } ],
  "news": [ { "id": 57, "t": 1789999000000, "kind": "up", "item": "wheat", "name": "Wheat",
              "dir": 1, "pct": 22.00, "before": 3.46, "after": 4.22,
              "headline": "The villagers are having a party and need Wheat!",
              "line": "Wheat is going UP! Crate pays $3.29 → $4.01 (+22%) Sell now!",
              "source": "sim", "active": true } ] }
```

| Field | Meaning |
|---|---|
| `news` | Up to 50 rows from the last 7 days, newest first |
| `t` | When it became news. For a HOT or DEAL, that is when it reached full strength and was announced |
| `kind` | `up`, `down`, `hot`, `deal`, `wanted`, `season` or `real` |
| `dir` / `pct` | `1`, `-1` or `0` / the signed percent. For a `real` row it is how far Crate's price moved: `0` (and `dir` `0`) when the real-world move did not move it, for a sold-out item or one already at its limit |
| `before` / `after` | The price before and after, when both are known |
| `headline` / `line` | Plain text, colour codes stripped |
| `source` | `sim`, `admin`, `calendar` or `real` |
| `active` | Still running |
| `season.endsAt` | Local midnight after the season's last day |

`active` at the top level lists every running HOT, DEAL, UP and DOWN. With the live market off
or paused, the whole reply is `{"generatedAt":…,"live":false,"active":[],"news":[]}`. The
dashboard page shows a news ticker, badge pills with "ends in", "Usually $X" when the mood is
3% or more, chart event markers and a 48H/7D/30D toggle, all only while the live market runs.
With it off or paused the page is 0.32's: 48-hour charts, no toggle and no `/api/news`
requests. The LilahCraft site ignores all of this until a theme release shows it.

### Real-world prices: checklist

They ship **off**. A real commodity's daily close nudges the matching item a little, once a
trading day. Only the number comes from outside; the headline is always one of ours.

1. **The game server needs outbound HTTPS.**
2. **Test first; this writes nothing.** `/hcm market sim real test gc.f` fetches from Stooq,
   prints the closes and shows what would apply. If Stooq refuses or has no data, set
   `real_world.provider: yahoo`, `/hcm reload`, and try `/hcm market sim real test GC=F`.
3. **Check the symbol rows** under `real_world.symbols`:
   - gold: `gc.f` / `GC=F`;
   - wheat: `zw.f` / `ZW=F`;
   - iron ingot: copper, `hg.f` / `HG=F`, since there is no free daily iron-ore quote;
   - lumber for oak_log is not a row: Yahoo's `LBR=F` is unverified (there is no Stooq
     symbol), so set `provider: yahoo`, test it with `/hcm market sim real test LBR=F`, then
     add the row yourself (the comment above `symbols:` shows it).

   A symbol set to `""` is off.
4. **Turn it on:** `real_world.enabled: true`, then `/hcm reload`.
5. **Fetch now:** `/hcm market sim real fetch` fetches at once, and the move is applied at the
   next tick. Each trading day is applied only once per symbol, so repeating it is safe. A
   day counts as applied only once its move is in the market: one fetched but not applied yet
   (a restart, a pause, `real_world` switched off) is applied when the market next runs.
6. **Check it:** `/hcm market sim real status` shows the last fetch time and the last error.
7. **What to expect:**
   - It fetches on weekdays at `fetch_time` (17:30 local), after the US commodity markets close.
   - A real +1% day is +2% here (`gain`), never more than `max_percent` (4%). The code allows
     4.5% at most, and seasons and real prices together also stay within 4.5%.
   - A "move" over 8% is treated as bad data and skipped.
   - A move of 2% or more gets a news line, at most one per fetch: the biggest one that moved
     Crate's price by a whole percent or more. A sold-out item, or one whose seasons and real
     moves are already at the limit, is never the headline.
   - The nudge halves every 24 hours and is gone after 96.
   - If the source is down, it retries every 60 minutes, up to 3 times a day, with one warning
     per symbol per day. The market carries on without it.
8. **The rules:**
   - The URL must be `https://`, and a custom `url` must contain `{symbol}`.
   - An unknown `provider` or a non-https `url` turns `real_world` off with a warning.

### Verify in game

1. `/hcm market sim status` shows the ticks advancing ("last just now", or "last 2m ago" to
   "last 4m ago") and, from Sep 15 to Oct 31, `Harvest Time (wheat -4%)`.
2. `/hcm market news wheat up`:
   - a title, a bell and sparkles at any Wheat TV;
   - the price moves at once, and the Store and Sell tiles show ▲;
   - a Sell quantity screen left open from before refuses once with "Prices just moved!".
3. `/hcm market sim hot oak_log 20h` (20 hours):
   - the badge and glint appear, and the Sell tile says "Up to 160 a day";
   - as an op, selling past 160 Oak Logs that day is refused.

   If oak already has an event, `/hcm market sim reset oak_log` first.
4. `/hcm market sim deal iron_ingot`: the order limit is 40 a day, for an op too.
5. Wait 10 minutes after your last join and after the last announcement you heard (step 4's),
   then log out and back in: the "Right now" line lists the oak HOT and the iron DEAL with
   their time left. News from while you were out is listed above it under "While you were
   away"; what you already heard live is not repeated.
6. `curl -H "Authorization: Bearer <token>" http://127.0.0.1:8080/api/news` returns the events
   (leave out the header if no feed token is set).
7. `/hcm market sim audit` shows the sell bonus and the buy discount.
8. Set `market.sim.enabled: false` and run `/hcm reload`:
   - every price equals its usual price and the badges go;
   - `/api/market` drops the new fields, and `/api/news` says `"live":false`;
   - the Hot & Deals sort, the Market News button and `@news` in the display picker are gone,
     the new placeholders stay as typed, and the dashboard page shows 48-hour charts with no
     toggle.

## Sound Mufflers

A **Sound Muffler** is a block that hushes the sounds you choose near it. It is the answer to
the chicken farm under the bedroom, the villager trading hall and the dispenser clock that
never stops clicking.

- **Craft:** eight wool (any colour) around a note block. Admins: `/hcm give muffler [player]`.
- **Place it and its menu opens.** Right-click it any time after to change things.
- **It hushes sounds MADE inside its box:** every block within its range, in each direction
  (range 8 = a 17×17×17 box around the muffler). They are hushed for everyone who would hear
  them, wherever they stand, so a muffler by the farm quiets the farm from the house too.
- **Break it and the item remembers its settings**, so moving a muffler is pick up, put
  down, done. A muffler that was never changed drops a plain one that stacks.
- **Anyone can look** at a muffler's menu. **Only its owner** (or an admin) can change it.
- It works in **every world**. It is not part of the economy, so it skips the economy-world
  check the other HomeCraft blocks have.

### The menu

| Tile | What it does |
|---|---|
| **Muffler: ON / OFF** | Switch it off without losing your choices |
| **Range** | Left-click bigger, right-click smaller: 1, 2, 3, 4, 6, 8, 10, 12, 16… up to `max_radius` |
| **Quieter = 25% volume** | How loud "Quieter" still plays: 50%, 25% or 10% |
| **Show the area** | Outlines the box in the air for 10 seconds |
| **Heard nearby** | Every sound made in the box in the last 10 minutes, newest first. The quick way to find the noise: stand by the farm, open this, click the culprit |
| **Find a sound** | Type part of a name (`chicken`, `piston`, `zombie door`) and pick from every sound in the game |
| **Single sounds** | The sounds picked one at a time |
| **The 32 group buttons** | Normal → Quieter → Silent (right-click steps back): farm animals, pets, villagers, golems, every kind of monster, other players, footsteps, fishing, pistons, dispensers, doors and gates, buttons and levers, note blocks, bells, chests, workstations, beacons |
| **Clear every choice** | Back to hushing nothing (asks first) |

A single sound steps **Quieter → Silent → Always play → not picked**. A single pick beats its
group, so you can make Villagers Silent and set their trading sound to **Always play**. Where
two mufflers overlap, the stronger choice wins: Silent beats Quieter, two Quieters give the
quieter volume, and a neighbour's "Always play" can't undo your Silent.

### What no muffler can hush

Some sounds are made by each player's own game, and the server never sends them, so no
plugin can reach them: rain and thunder, music and jukeboxes, furnaces and campfires
crackling, portals humming, lava popping, minecarts rolling, bees buzzing in flight, and a
player's **own** footsteps, clicks and pickups (other players hear yours, and those can be
hushed). The menu says this too. The rule of thumb: **anything that shows up in "Heard
nearby" can be hushed.**

### How it works

Bukkit has no event for a sound being played, so the muffler catches sounds on their way to
each player through **ProtocolLib**, which is already on the server. It watches three packets:

- a sound at a position (almost everything);
- a sound attached to an entity (goat horns and a few others; hushed only when sent from the
  main thread);
- a "level event". The **dispenser/dropper click** travels this way, and so do anvils,
  brewing stands, grindstones, smithing tables, crafters, zombies banging on doors, and ghast
  and blaze shots. Level events that also draw particles (a block breaking, a composter) are
  left alone.

**Silent** drops the packet. **Quieter** drops it and plays a quieter copy to the same player
on the next tick. It never edits a packet in place, because the game sends one packet object
to every player in earshot, so turning it down in place would turn it down again for each
of them. The copy's seed carries a mark so it isn't hushed a second time.

It fails safe. A packet the muffler can't read (a future Minecraft change, a sound ProtocolLib
can't convert) goes through untouched and is logged once. **Without ProtocolLib**, mufflers
still place, save and show their menu, with a red tile saying why nothing is hushed.

A piston, fire, a wither or flowing water can't move or destroy a muffler, and explosions skip
it like every HomeCraft block. If one vanishes without being broken (WorldEdit, `/setblock`),
a 30-second sweep forgets it, so nobody is left with an invisible muffler hushing a farm.

### Owner knobs (`sound_muffler`)

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false`: mufflers stay placed and keep their settings but hush nothing |
| `block.material` / `block.name` | `WHITE_WOOL` / `&fSound Muffler` | The item and block. A texture in `skins.sound_muffler` makes it a textured head instead |
| `default_radius` | `8` | The range a new muffler starts at |
| `max_radius` | `16` | The biggest range a player can pick (hard limit 32, clamped with a warning) |
| `default_quiet_percent` | `25` | The volume "Quieter" starts at |

The recipe is `recipes.sound_muffler`. All of it back-fills into an existing `config.yml` on
the first start and reloads with `/hcm reload`.

### Data

Schema **v33** adds `sound_mufflers`: one row per muffler with its owner, on/off, range,
Quieter volume and choices. The block itself is still a `placed_blocks` row like every other
HomeCraft block, and the two are squared on start: settings without a block are dropped, and a
block without settings gets fresh ones.

### Verify in game

1. With ProtocolLib installed, the log says `Sound Muffler: hooked into ProtocolLib.`
2. `/hcm give muffler`, then place it next to some chickens: the menu opens.
3. Click **Chickens** twice (Silent): the clucking stops for you and for a friend standing
   further off. Click once more (Normal) and it's back. Set it to Quieter at 10%: it's faint.
4. Stand by a dispenser clock inside the box, open **Heard nearby**: `Dispenser — dispense`
   is listed. Click it to Silent: the clicking stops.
5. **Show the area** draws the box. Right-click **Range** and show it again: it's smaller.
6. Break the muffler: the dropped item says "Remembers its settings". Place it elsewhere: the
   same choices are there.
7. A second player right-clicks it: they can look, but a click says only the owner can change it.
8. Push it with a piston: the piston doesn't fire. Set it on fire: it doesn't burn.

---

## Permissions

| Node | Default | Grants |
|---|---|---|
| `hcm.admin` | op | All admin commands (`/hcm …`) + all child nodes |
| `hcm.use` | op | Parent node granting both market view nodes below (back-compat) |
| `hcm.market.list` | op | `/hcm market list` — dump the FULL catalog (players use the PC GUI) |
| `hcm.market.price` | all | `/hcm market price\|history <item>` + `/hcm balance` + `/hcm market news [on\|off]` |
| `hcm.market.order` | all | **Sell** to the dynamic market (`/hcm market sell`). Selling needs no shipping — you deliver the goods — so everyone has it |
| `hcm.market.buy` | op | **Buy** by command (`/hcm market buy`), with no shipping and no wait. Op-only, and it has to stay that way: a free instant buy makes every shipping tier pointless. Players order at the store |
| `hcm.market.limit.bypass` | op | Exempt from daily buy/sell limits AND per-item caps. **Not** from the live market's event limits (HOT/UP sell limit, DEAL/DOWN buy limit). Switch it off on your own account to trade under every cap: see [Playing as op](#playing-as-op-the-must-do) |
| `hcm.market.sim` | op | Run and test the live market: `/hcm market news <item> <up\|down\|wanted>` and `/hcm market sim …` (a child of `hcm.admin`) |
| `hcm.pc.use` | all | Open the Amazon GUI on a placed PC |
| `hcm.pc.craft` | all | Craft the PC at a Workbench |
| `hcm.workbench.place` | all | Place a Mini Workbench |
| `hcm.workbench.use` | all | Open a placed Workbench's GUI |
| `hcm.courier.use` | all | `/hcm courier` and the Courier Site on the PC |
| `hcm.arcade.use` | all | `/hcm arcade` — the Arcade hub, and `/hcm arcade odds` |
| `hcm.games.play` | all | Play the Games: `/hcm play`, the Games screen, the cabinets, courses and mini golf, invites and Take a break, and `/hcm leave`. Nothing opens until `games.enabled: true` |
| `hcm.games.chance` | all | The games of chance. Revoke it for a group to keep them out of every game of chance, Crates, Scratch Tickets and token Card Packs included; they then don't see those games at all. Skill games are not affected |
| `hcm.games.admin` | op | Run the Games (`/hcm games …`), open a game for another player (`/hcm play <game> <player>`), write `[Arcade]` join signs and build in the Games worlds (a child of `hcm.admin`) |
| `hcm.quests.use` | all | `/hcm quests` |
| `hcm.achievements.use` | all | `/hcm achievements` |
| `hcm.guide.use` | all | `/hcm guide` — How It Works |
| `hcm.muffler.use` | all | Place a Sound Muffler and change what your own mufflers hush (anyone may look; admins may change any) |
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
  market/                      dynamic market engine (catalog, pricing, service, OrderMath)
  market/sim/                  the live market: mood, events, seasons, headlines, news,
                               real-world prices (pure, unit-tested) and its service
  muffler/                     Sound Mufflers: groups, picks, the box maths (pure, unit-tested),
                               the service, and the one ProtocolLib class
  gui/muffler/                 the muffler's menu and its sound lists
  games/                       the Games framework: the catalog, the play gate, Take a break,
                               crash-safe rounds, skill rewards, scores, invites, today's pick,
                               join signs, the RTP band
  games/chance/                games of chance: Ore Slots, Twenty-One, the Wheel, Higher or
                               Lower, Coin Flip (pure engines with exact give-back maths)
  games/cabinet/               the arcade cabinets (pure engines, boards and AIs)
  games/world/                 world sessions: saved state, the kit and its guard, the Games-world
                               guard, recovery
  games/trial/                 time trials: parkour, elytra and boat courses
  games/golf/                  mini golf
  gui/games/                   the Games screen, Take a break, high scores, the invite picker,
                               and each game's screens
  storage/                     SQLite datastore + DAOs
  util/                        NamespacedKeys, text helpers
  web/                         dashboard web server, the website's JSON feeds (market,
                               Minis, news, arcade), the feed token check, gzip
src/main/resources/
  plugin.yml
  config.yml
  web/index.html               the dashboard page (asks for the feed token on a 401)
```
