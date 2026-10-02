# HomeCraft Management

A Minecraft **Paper** plugin that turns a family survival server into a small town with a
working economy: a computer in every house, a shop that ships, a market with real stock, a
collectible line of numbered **Minis**, wild Minis to hunt, delivery jobs, and an **Arcade**
that rewards playing. Built for a server where the youngest player is just learning to read,
so every screen is short, plain and readable on **Bedrock** as well as Java.

- **Target server:** Paper **26.2 / 26.3**, Java **25** (compiled against the 26.2 API; the
  code is also compiled and tested against the 26.3 API with no errors or removals)
- **Version:** `0.36.0-course-variety`
- **Build:** Gradle (toolchain pinned to Java 25), shaded jar with SQLite bundled
- **Design spec:** [`DESIGN.md`](DESIGN.md) · **Player guide:** [`docs/how-it-works.md`](docs/how-it-works.md) · **Games guide (for the website):** [`docs/games-guide.md`](docs/games-guide.md)

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
| `/hcm config reset <section> [confirm]` | admin | Put `arcade` (or `arcade.<part>`), `games` (or `games.<part>`), `packs`, `minis.loot.natural`, `minis.effects` or `clock` back to the bundled defaults. Dry run without `confirm`; snapshots config.yml first. It never changes where a Games place stands: every `origin` and `half_gap`, `keep.area`, `keep.plot_gap`, `games.worlds` and `games.fresh.world` are kept as they are (the dry run lists them) |
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

**The events batch** adds things to do together and a weekly goal, on the same house rules:
**warm-ups** before a timed run (on as shipped: `games.trials.warmup_seconds: 180`), **Race with
friends** (party races on any time-trial course but a Dropper) and **golf together** (with a 2:00
hole clock), the **Weekly Cup** (on as shipped, on Fresh parkour, Sky Rings, Ice Boat and Dropper
courses), **the Dropper** (two Fresh Courses, on inside `games.fresh`, which itself ships off), and
two that ship off: **Race Night** and **Falling Floors**. See [Time trials](#time-trials), [The
Weekly Cup](#the-weekly-cup), [Mini golf](#mini-golf), [The Dropper](#the-dropper), [Falling
Floors](#falling-floors) and [Race Night](#race-night); `docs/games-guide.md` is the players' and the
web developer's version.

**Course Variety (0.36)** changes two of the Fresh Courses, and nothing a builder made by hand:
**Adventure Golf** (Golf of the Week and Tiny Golf: eleven new hole shapes, a weekly variety quota,
sand that slows the ball, ponds and a creek in play on Medium and Hard holes only, trees, hills and
tee signs that name each hole's feature) and the Ice Boat's **Mountain Run** (a downhill sprint that
spirals round the viewing stand: 4 to 6 drops, sandy bends, sand pits, pick-a-path splits, an ice
cave and a forest; Race Night races it as "3 downhill races"). Ice Boat still ships off: run the boat
test strip and a preview first. See [Adventure Golf](#adventure-golf) and [Ice Boat: the Mountain
Run](#ice-boat-the-mountain-run). The same release moves every Games place out of sight of the others
and adds a built-in void world (see "Where they are" in [Fresh Courses](#fresh-courses)).

### What players get

- **The Games screen:** `/hcm play`, or the Arcade hub's **Play** row. Tabs for All, **Luck**
  (games of chance, plus links to the Scratch Ticket and each crate), **Cabinets**, **Courses**,
  **Golf** and **Together** (Race Night and Falling Floors; it shows only while one of them is
  open). The bottom row has Today's pick (46), High scores (47), Take a break (48) and How
  the games work (50). A closed game shows no tile at all.
- **`/hcm play <game>`** opens one game, rules first. **`/hcm play <course>`** from chat or a join
  sign skips the course screen and starts a time trial (after the warm-up choice while warm-ups are
  on; its tile on the Games screen opens the course screen first); a golf course always opens its
  course screen. `/hcm play blackjack` opens Twenty-One.
- **The hub's Play row** (row 4, only while the games are on): All games, Today's pick, Luck,
  Cabinets, Courses, Mini golf and Take a break. Row 1's label changes from "Games" to "Luck", and
  its crates and Scratch Ticket follow Take a break: a player on a break sees the "Taking a break"
  tile there, and one without `hcm.games.chance` sees none of them. With the games off, the hub is
  exactly what it was.
- **Today's pick:** one skill game or course a day, the same for everyone, changing at local
  midnight. Its first finish of the day pays `games.featured_bonus`. A game of chance is never
  the pick. An admin who pins `trials` or `golf` makes every course of that game the pick.
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
`/hcm config reset games` (or `games.<part>`) puts it back as shipped, all but where the Games places
stand: every `origin` and `half_gap`, `keep.area`, `keep.plot_gap`, `games.worlds` and
`games.fresh.world` stay as they are (the dry run lists them as kept), since putting those back would
move what is built. Places you set move by hand only (see "Moving an area by hand"); one an update
moves or grows is moved, and its old area emptied, by itself ("When an update moves or grows an area").

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` | The master switch. Off, the rest of the plugin is exactly as before |
| `worlds` | `[games]` | The Games worlds: where courses are built and world games pay. Read-only for non-admins while the games are on |
| `play_worlds` | `[]` | Extra worlds (besides the economy worlds and `worlds`) where the games open, such as a hub that isn't an economy world |
| `click_cooldown_ms` | `600` | The pause between two plays of a game of chance (never below 250) |
| `chance_daily_tokens` | `100` | Most tokens a player can put into all games of chance in a day (`0` = off, at most 10000). While the games are on it also counts Crates, Scratch Tickets and token Card Packs |
| `max_payout` | `250` | Most any single play of a game of chance can pay (never below the game's largest stake) |
| `skill_daily_cap` | `6` | Most tokens all skill games together pay a player in a day (first clears don't count) |
| `featured` | `auto` | `auto` picks a skill game or course each day; a game or course id pins it (`/hcm games feature`). `trials` or `golf` pins every course of that game |
| `featured_bonus` | `1` | Tokens for the first finish of today's pick (counts toward `skill_daily_cap`) |
| `feed_top` | `5` | The website's leaderboards: how many of each game's and course's best scores `/api/arcade` lists as `top` (0-25; 0 = none). Past Fresh courses list at most 3. Names only with `web.dashboard.arcade_show_names` |
| `restart_times` | `["04:00", "16:00"]` | When the host restarts the server each day: the minute it actually stops, not when its warnings start (quoted 24-hour times in `clock.time_zone`; midnight is `"00:00"`). Just before each one, and through that minute until the server stops (a server back up inside the minute isn't held for the restart it just had), nothing a restart would cut off starts: courses and golf, a new Twenty-One or Higher or Lower hand (an open one plays on), a cabinet's scored daily try (today's board isn't dealt until after, and the try is kept; practice after a used try and Classic play go on). Spins and flips aren't held, and the plugin sends no warnings of its own. `[]` = off; an entry that isn't a time is dropped with a WARN |
| `restart_hold_minutes` | `5` | How many minutes before each restart that hold starts (1-60) |
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
  `/hcm play <course> <player>` (admins and the console) skips the course screen (while warm-ups
  are on it first asks "Warm up (3:00)" or "Go straight to the timed run"). A tile on the Games
  screen, the course list or the hub's Today's pick opens the **course screen** first: the rules,
  your best, this week's best, the record and who holds it, what it pays, and Start.
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
- **Fair play.** Flying, a game mode other than adventure, any potion effect, a walk speed other
  than the usual 0.2 (`/speed`) or a changed movement voids the run the moment it's seen ("This
  run won't count - …"); you can still finish it for fun. Movement means movement speed, jump
  strength, step height, gravity and safe fall distance: each must be at its usual value, with no
  modifier on it but sprinting or powder snow's slow-down. At the finish, a run quicker than the
  course's shortest time (`min_seconds`) doesn't count, and nor does a leg between two checkpoints
  covered faster than the kind allows (parkour 14, elytra 80, boat 75 blocks a second, over the
  gap between the two checkpoints' spheres). Either way the finish says "That run didn't count."
  with why, and nothing is recorded. Every leg gets 0.05 s of slack, and a server stall (over
  250 ms between two ticks) never voids an honest run: a leg it touched isn't checked. Nothing is
  recorded either for a run whose course changed layout while it ran, or was deleted and made
  again.
- **Nobody else can touch a run.** For anyone in a world game, an effect from anything but a
  plugin or the `/effect` command (a splash potion, a beacon, an arrow), all knockback (a wind
  charge, a hit) and a fishing rod reeling them in are cancelled. A bystander can't spoil a run,
  or carry it across a gap.
- **The finish.** The time goes on the course's all-time board and this week's board. Chat shows
  your time, "★ Your first finish…" or "★ New best!", then the record and who holds it (or "★ New
  course record!"), and "★ Best time this week!" when you set it without setting the record. "Your
  first finish" shows only when that first finish is being paid now: after a layout change clears
  the board, a later finish reads "★ New best!". Then you're sent home with your things, and a
  result screen offers Play again.
- **Rewards:** a course's first finish pays by tier, once ever and outside every cap; setting the
  week's best time on a course pays `weekly_best_bonus`, once per course per week; finishing the
  course of the week pays `course_of_week_bonus`, once a day; and today's pick (the course, or any
  course while `trials` is pinned) pays `games.featured_bonus`. A new personal best pays nothing.
  A course's tile shows "First finish: N tokens" only while you haven't had it, and the course
  screen's tokens item names the amount or says it's done. The course of the week is picked by
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
| `trials.warmup_seconds` | `180` | A warm-up's length before a timed run; `0` turns warm-ups off (0-600) |
| `trials.party_max` | `8` | The most racers in one party race (2-12) |

<!-- ---- WP-R1: warm-ups, party races and race mode ---- -->
**Warm-ups.** While `warmup_seconds` is above 0, starting a course (Start on its screen,
`/hcm play <course>`, a join sign, Play again) first asks "Warm up (3:00)" or "Go straight to the
timed run". Going straight is the run as it always was. A warm-up takes you to the start and lets
you run the course freely, as many laps as you like: checkpoints guide you and Back to checkpoint
works, but nothing is timed for the record, recorded, paid, or counted for the Weekly Cup. The
action bar reads "Warm-up 2:14 left - not counted", and each lap says "Warm-up lap: 0:48.2 (not
counted)" and goes round again from the start. On a one-way course such as the Ice Boat's Mountain
Run each go is a run from the top, and the words say so: "3:00 of free runs" and "Warm-up run:
0:42.1 (not counted)". When the time is up, or you tap the **Start timed run** kit
item, you go back to the start and the normal 3-2-1 begins; that run is timed and counted as
usual. A run gets one warm-up, and a restart due soon (the restart hold) ends it at once. An admin's
test run never warms up, and the Dropper has its own practice drop instead.

**Race with friends (party races).** Any open time-trial course (hand-built, Fresh or Classic;
parkour, elytra or boat, never a Dropper) can be raced together, any time, free and just for fun.
Click **Race with friends** on the course screen (bottom row, left of the way out; the Weekly Cup's
item is right of it), or type `/hcm play race <course>`, to open your party. Anyone in it
can invite through the usual invites ([Accept] on Java, `/hcm play accept` on Bedrock; the 30 s
cooldown stays, and `/hcm play invites off` turns party invites off too, including for a player who
turned them off before party races existed), up to `party_max`. The party screen shows who's in and
who's ready, and the course's Weekly Cup item when it runs one. Only the host starts, and the host
chooses whether everyone warms up first (the same `warmup_seconds`, with a **Ready** kit item to be done early).
Then everyone goes to one grid and starts on **one shared 3-2-1**: boats in rows of two behind the
start line (single file on a narrow track), runners and flyers on the start itself. A bar shows your
place ("2nd of 5 · Lap 1/2"), finishes and photo finishes go to the group, and the results (the
Clubhouse board and its **Results** item, or a results screen at home) rank everyone. The race
ends when everyone is in, 2 minutes after the first finish, or 10 minutes after Go. Each racer's
finish is also their **normal counted run** on the course, exactly once and
under every fair-play rule: its boards, its first finish and other rewards, and the Weekly Cup. A
party race has no entry, no fees and no prizes of its own. Boats bump, as at Race Night; runners
and flyers can't push each other (they are on the `hcm_nopush` scoreboard team while they race, and
back on any team they were on before as soon as their run ends). Anyone
can leave at any time (Leave game, or Leave the party on the party screen) and the others carry
on; a disconnect is the same. A host who leaves passes the party to the next one who joined. A
restart due soon refuses a new start (and ends a shared warm-up: straight to the grid), and a course
held for Race Night can't be party-raced: when Race Night takes the track, a party race still on it is
called off, its racers go home with their things, and nothing they hadn't finished counts.

**Race mode** is the engine party races and Race Night share: racers are held on the grid until one
go tick and all start on one clock, a finish is judged on the course as it is (its layout, and the
still-standing rule for Fresh Courses), finishers wait on Fresh Ice Boat's **viewing stand** (a railed
platform in the middle of the track, which the boat planner builds into every layout from its algo 2
on: the Mountain Run spirals down round it), and a racer sent back to a checkpoint
is re-seated clear of the other boats. Where there is no stand, finishers go to the Clubhouse at the
line if it is open in the course's world, `party_after` / `race_night_after` is on and it isn't
closing for a restart; otherwise they go home. A boat that creeps off its grid spot before Go is
put back on it and starts once back, on the shared clock (it never gains a head start). A racer
whose trip to the track falls through (hands full, hurt) is dropped from the race at once, so
nobody waits for them.
<!-- ---- end WP-R1 ---- -->

**Building a course** (`hcm.games.admin`, standing in a `games.worlds` world; the full list is in
[Commands](#commands)):

1. `/hcm games course create <id> <parkour|elytra|boat> [tier]`. A course id is 2-32 lower-case
   letters, digits or `_`, starting with a letter. It can't be a game id or alias, another
   course, a Fresh Courses id (such as `fresh_parkour`), or one of `accept`, `deny`, `break`,
   `leave`, `invites`, `news`, `cup`, `watch`, `cheer`, `cheers`, `rider`. Nor can it be `auto`,
   which is how `/hcm games feature` lets the day pick.
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
A fall height must be under the course's lowest point (the start, a checkpoint or the finish),
or every run would go straight back, and not under the world's floor, where no run ever gets. A
start, checkpoint or finish at or under the fall height is refused too.

<!-- ---- cup (WP-C) ---- -->
#### The Weekly Cup

A course can run a **Weekly Cup**: a player pays a small entry once per course per week
(`games.cup.entry`, 5 tokens: "Enter this week's Cup: 5 tokens. Best time wins the pool."), and
their best counted time that week, from a run started after entering, is their Cup time (a party
race's finish counts: it is a normal run). Warm-ups, practice drops, test runs, Race Night heats and
runs that didn't count never set one. The course screen (slot 24, right of the way out), the party
screen (slot 44) and the course's tile show the pool live ("Cup pool: 35 tokens · 5 in"; the live
pool counts the top-up once 2 or more are in, and it is paid only if 2 or more set a Cup time).
At the week's rollover (the quests' week start at 04:00, when Fresh Courses change) the pool is
shared by Cup time: 70/30 with 2 Cup times, 50/30/20 with 3 or more, rounded down with the rest
to 1st. An entrant with no Cup time gets no share: their entry stays in the
pool, so the Cup stops taking entries in its last `games.restart_hold_minutes` (5) before the
rollover, and during a restart hold that runs into them ("This week's Cup is nearly over, so it takes
no new entries."): no run started then could set a Cup time. It is settled once, and a rollover the
server was down for is settled at the next start. The
server keeps nothing: the pool is every entry, plus `games.cup.server_topup` (10) when 2 or more
set a Cup time. A lone entrant, fewer than 2 Cup times, a Cup an admin calls off, or a course
deleted, changed (a layout edit, or a Fresh course re-rolled) or closed mid-week gets every entry
back, with the reason. Cup prizes aren't under the
daily skill caps: it's the players' own pool, and a cap would destroy tokens. Nothing in it is
chance, so Take a break's chance rules don't apply; `/hcm play cup off` hides it for a player.
Fresh parkour, Sky Rings, Ice Boat and Dropper courses run one by default (while Fresh Courses
change once a week), and take entries once the week's own course is up ("The Cup starts when
this week's course is up"); a hand-built course only after `/hcm games cup on <course>`. Admins
have `/hcm games cup status [course]`, `on|off|default <course>`, `settle <course> confirm` and
`void <course> confirm` (see [Commands](#commands)).
Deleting, closing (`disable`) or changing the layout of a course whose Cup has entrants asks for
`confirm`, then refunds them; a deleted course's Cup switch goes with it.

**For the owner:** an entry pool can mean the youngest players pay into a pool the oldest win.
It is small, opt-in per player and refunded when a player is alone. To switch the Cup off
server-wide, set `games.cup.enabled: false` and `/hcm reload`: nobody can enter, and Cups already
paid into still finish their week and pay out or refund. A `games.cup` block that can't be read
does the same until it is fixed (`/hcm games cup status` says "entries closed (games.cup can't be
read - see the console)").

| Key | Default | Meaning |
|---|---|---|
| `cup.enabled` | `true` | `false`: no new entries and no Cup on the screens; Cups already paid into still finish their week and pay out or refund |
| `cup.entry` | `5` | Tokens to enter one course's Cup for one week (1-100) |
| `cup.server_topup` | `10` | Tokens the server adds to a pool in which 2 or more set a Cup time (0-100); never with fewer |
<!-- ---- /cup ---- -->

### Mini golf

Mini golf in the Games world, where **your Mini is the ball**. Each open course is its own tile
on the Golf tab ("Meadow Links - 9 holes, par 27") and its own `/hcm play <course>` id;
`/hcm play golf` lists them.

- **Starting.** Golf always opens the **course screen** first, however you get there (a tile,
  `/hcm play <course>`, a join sign): the holes and par, your best, the record, **Pick your
  ball**, How to play and **Start**. Only Start takes you anywhere.
- **Pick your ball:** any Mini you own that has a head. Only its look is borrowed, on a plain new
  head: the Mini itself stays in your collection, untouched. The choice is remembered. With no
  Mini it's a plain white ball, and so is a picked Mini you no longer own; a Bedrock player's
  ball is always a white block (Bedrock can't draw head textures reliably).
- **Playing.** Start takes you to hole 1's tee with only the kit: five clubs (Tap, Putt, Chip,
  Swing and Drive, power 1 to 5), Go to my ball, Reset ball (+1 stroke), Scorecard and Leave game
  (click twice). Any click with a club putts the ball the way you look, once it's still and you're
  within 4 blocks. Slime bounces, ice slides, and soul sand, soul soil and honey slow it down; a
  half-block step is climbed only at speed. Water, lava or leaving the hole's bounds puts it back
  on your last spot, +1 stroke. A fast ball can roll over a flush cup; a slow one drops in. A
  ball that drops into the hole, or stops anywhere on the cup block, is in. A ball still rolling
  after 30 seconds is stopped where it is.
- **Adventure Golf's three rules** apply only to Fresh Courses' Adventure Golf (a golf layout from
  golf planner algo 3 on, a Classic Golf recall of one, or a course kept from one): smooth
  sandstone is sand and slows the ball like soul sand, a ball that comes to rest over water has
  fallen in (+1, back to its spot), and a ball that only wobbles on the spot at a step comes to rest
  within about a second. A hand-built course plays exactly as before, its smooth sandstone ordinary
  stone. See [Adventure Golf](#adventure-golf).
- **Picked up.** Once the ball stops with the strokes at par + `max_over_par` (3), the hole is over
  and scores exactly that.
- **Between holes** the scorecard shows for 5 seconds (or until Next hole). After the last hole
  your score goes on the course's board (strokes, lower is better), the rewards are paid, you go
  home with your things, and the final scorecard offers Play again. Several players can play one
  course at once, each with their own ball; balls don't meet.
- **Golf together** (owner decision D4). The course screen (`/hcm play golf <course>` or
  `/hcm play <course>`) has **Play with friends**: a party of up to 4 through C1's parties (one party
  per player, shared with party races). Anyone in it can invite a friend (the usual invites:
  [Accept] on Java, `/hcm play accept` on Bedrock; the pair cooldown stays, and `/hcm play invites off`
  turns golf together invites off too, including for a player who turned them off earlier); only
  the host starts, and everyone goes to hole 1 at once. Everyone plays the same hole at the same
  time, each with their own ball; a player whose ball is in waits, and when every ball is in (or
  picked up) everyone moves to the next tee together. The first ball of a hole in starts a 2:00
  **hole clock** (on the action bar of anyone still out, and "picked up in 1:45" in the card's
  Still playing name); when it runs out, every ball still out is picked up at par +
  `max_over_par`. The kit's Scorecard (and the card between
  holes) is the **shared scorecard**: one row per player, 8 holes a page, and at the end the group
  ranking (fewest strokes first, level totals sharing a place). Each round is a normal round for
  the boards and rewards, with nothing extra for being in a party, recorded once at the player's
  own last hole (on the day and set it was played); after that they stay only for the shared card.
  Leaving is fine at any time (your row stays as "left" and the others carry on; after your last
  hole it stays as played and your round is kept); if you were the last ball out, the hole ends. A
  friend whose trip to the course never arrives stops being waited for within a second, and the
  others are told. After
  the round the party opens again: "Play again together". A restart hold refuses a new start.
- **Rewards:** a course's first finish (once ever, outside every cap), finishing at par or better
  (once per course per day), each hole-in-one in a round you finish (once per hole per day; the
  title says "Hole in one!", with a harmless firework), and today's pick (the course, or any
  course while `golf` is pinned). A new personal best pays nothing. A round left early records
  nothing, and nor does one on a course an admin closes or changes the layout of: either ends
  the round at once.
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
   alias, another course, a word `/hcm play` keeps, or `create`, `list`, `help` or `auto`.
2. On each hole's tee, facing down the hole: `<id> hole add <par>` (par 2-6; at most 18 holes).
3. Look at the cup block (within 6 blocks): `<id> hole <n> cup`. The cup is the block the ball
   ends up resting on: the floor of a sunken hole, a bottom slab, or a block flush with the green.
   `cup` refuses a block with nothing to rest on (air, a flower), anything under 0.2 of a block
   high (carpet, a thin snow layer, a trapdoor laid flat, a lily pad) and hollow blocks (a
   cauldron, composter or hopper). A hollow block sitting on the cup is fine: a ball stuck in it
   counts. Stand at two opposite corners of the hole: `<id> hole <n> bounds 1` and `bounds 2`.
   Out of bounds is leaving that box across the ground, or dropping more than 2 blocks below its
   lower corner.
4. `<id> enable` opens it once every hole has a tee, a cup and both corners, with the tee and cup
   inside the bounds.

**Layout edits** are a tee, a cup, bounds, and adding or removing a hole. Each bumps the course's
`rev` and clears its high scores, after a trailing `confirm` when there are scores to lose. It
also ends every round on that course at once: the players read "<Course> was changed by an
admin, so this round can't count. Your things are back." and go home. Par and the name aren't
layout. Closing or deleting a course sends anyone playing it home too. A player still on the way
to the first tee when the course is closed, changed or deleted goes straight home.

### Fresh Courses

Right after the 04:00 restart, the plugin builds a new set of courses by itself: **Easy Parkour,
Parkour and Hard Parkour** (three courses, each with its own board), **Sky Rings** (an elytra course:
fly through the rainbow rings, no rockets needed), **Golf of the Week** (9 holes) and **Tiny Golf**
(3 short holes for the youngest), both Adventure Golf with sand, ponds, trees and hills (see
"Adventure Golf" below), and two droppers, **Easy Dropper** and **Dropper** (see "The Dropper"
below). **Ice Boat**, a downhill race down a mountain (see "Ice Boat: the Mountain Run" below), ships
switched off. By default a new set goes up **every Monday** and stays all week;
it can also change every day, or every few days (`cadence`, below). Everyone gets the same courses.
Kids earn 1 to 3 stars on each course in each set (1 just for finishing) and fill a weekly **Star
Chart**. Nothing is built by hand, and your own courses are never touched.

**Turning it on (once):** set `games.fresh.enabled: true`, then `/hcm reload`. The first set is up
within about two minutes; `/hcm games gen status` shows each course, how often they change and when
next. That's all.

**How often they change (`games.fresh.cadence`).** One setting, three kinds of value:

| `cadence` | New courses | Golf's big course is called |
|---|---|---|
| `weekly` (shipped) | every Monday at 4:00 AM (`rebuild_day` picks another day, `rebuild_at` another time) | Golf of the Week |
| `daily` | every day at 4:00 AM | Golf of the Day |
| `3` (any number of days from 1 to 28) | every 3 days at 4:00 AM, on fixed dates: counted from the first `rebuild_day` on or after 5 Jan 2026, so `/hcm games gen status` can tell you the next one and it never depends on when you changed the setting | Fresh Golf |

- Change it and `/hcm reload`: **the courses that are up now stay** until the first change day of
  the new setting (or their own end, if that comes first) - never rebuilt halfway through just
  because the setting changed. Weekly to daily: they stay until the next 4:00 AM, then change every
  day. Daily to weekly: today's stay until the next 4:00 AM, then the week's set goes up and stays
  until the next Monday. This holds across a restart. `/hcm games gen reroll` still replaces one at
  once.
- Moving `rebuild_day` works the same way, with one catch: **moving the change day later keeps
  this set until the new day comes round.** Sets are numbered (`7:38` is the week of Mon 28 Sep),
  and a new day before the next Monday would give the set that is up its own number again. That
  day is not a change, so the courses stay until the new day a week later (up to six extra days;
  accepted as is). Monday to Friday, changed on Wed 30 Sep: the week's courses stay through Fri 2
  Oct, the new set goes up on Fri 9 Oct, and from then on they change every Friday.
  `/hcm games gen status` always shows the real date ("next: Fri 9 Oct 4:00 AM").
- Anything `cadence`, `rebuild_at` or `rebuild_day` can't use (a typo, `cadence: 30`) is one WARN in
  the console and the shipped value (weekly, 04:00, the quests' week start); it never switches the
  courses off.
- Each set has its own boards, its own first-finish token and its own stars; the Star Chart is per
  week whatever the cadence.

**Where they are.** In your existing Games world (`games.fresh.world: ""` means the first of
`games.worlds`), far from spawn and high in the sky. Everything the games build stands in x
1760-9599, z 2880-10367, y 96-303: the courses and the Classics in two columns (x 6080 and x 7488,
z 4096-7967), Golf of the Week and Classic Golf in a column of their own (x 8768-9599, z 4096-5119,
their halves 128 x 16 x 224 from v4), the Ice Boat's Mountain Run north of everything (x 6080-7615,
z 2880-3519, y 96-271: a whole mountain, each half 480 x 176 x 640), the Clubhouse and Falling Floors
south of them (z 8544), and the kept courses' plots to the west (from x 1760, z 7296). **Every place is 576 blocks (36 chunks) from every other**, a
course's own spare half included, so from any course players see only that course, whatever view
distance the server uses (the server sends chunks up to its view distance plus one; 576 blocks is
clear up to view distance 34, and Paper's largest is 32). Each course owns two halves 576 blocks
apart along x: the current course stands in one while the next is built and checked in the other,
and the switch is one database write, so nobody ever plays a half-built course and a run already
going always counts. The old half is emptied once nobody is on it.

- **Three distances.** A course isn't built within 16 blocks of a hand-built course, the world's
  spawn or `safe_spot`, nor within 32 blocks of another of the games' places (another course, a
  Classic, Falling Floors, the Clubhouse; the kept courses' area must stay 16 blocks from every
  course, or keeping is off); 576 keeps it out of sight. A place closer than 576
  still works: `/hcm games check` warns that players can see it (see "What players can see").
- **Keep this sky free ("don't build here").** Don't build anything at y 96 or above in the Games
  world between x 1760 and 9599, z 2880 and 10367 (further south if you raise `keep.max_plots`: 36
  plots reach z 12191, 100 plots z 22223). A spot with your blocks in it isn't used: that course stays
  off and says where.
- **The world border must be at least 21,000 across** (centred on 0,0; vanilla's is 60 million).
  A course, the Clubhouse or the arena past a smaller one stays off, a kept-course plot past it is
  never used, and `/hcm games check` names each.

- **Nothing of yours is ever cleared.** The first time a course uses its area, the area is checked
  block by block. If anything is there (a mountain top, a build), that course stays off and
  `/hcm games gen status` says how many blocks and where the first is: "Region has 1,234 blocks
  that aren't Fresh Courses' (first at x,y,z)". Then either run `/hcm games gen claim <course>
  confirm` to clear that area, or use a flat world (below). A course is also refused, with a line in
  the console and in status, when a hand-built course is within 16 blocks of its area, when the
  world's spawn (or `safe_spot`) is, when a hand-built course already uses its id, or when its area
  comes within 32 blocks of the Falling Floors arena (`games.falling_floors.origin`) or the Clubhouse
  (`games.clubhouse.origin`), on or off; `claim` is refused then too, so it never clears one of your
  courses, the spawn, the arena or the Clubhouse.
- **A flat world instead** (the fallback when your Games world has hills; no extra plugin needed):
  1. `/mv create games_fresh normal --world-type flat --no-structures` (Multiverse-Core 5; on 4.x
     it was `-t flat -a false`; check `/mv create --help`).
  2. `/mv modify games_fresh set gamemode adventure` and `/mv modify games_fresh set difficulty
     peaceful`.
  3. Add `games_fresh` to `games.worlds`, and to the **same Multiverse-Inventories group** as your
     Games world (see "Turning it on, and the Games world", step 4).
  4. Set `games.fresh.world: games_fresh`, then `/hcm reload`.
- **Nobody can change them.** While Fresh Courses is on, placing, breaking, buckets, signs, fire,
  pistons, explosions and the like are refused inside its areas for everyone, admins included
  ("This area is built by Fresh Courses - use /hcm games gen"). WorldEdit can't be stopped; the
  next restart (or `/hcm games gen rebuild`) puts the blocks back.
- **After a crash** every course is checked against its plan before it opens, and anything the
  world didn't save is put back first (the console says so). A build a restart interrupts simply
  carries on after it. A course that can't be vouched for (say `trials.fall_depth` was lowered and
  one of its jumps would now send players back) stays closed and is replaced by a new one for the
  same set (a pinned course is built again from its seed), on a fresh board.
- **In that world** (`world_rules: true`): no mobs, fire or weather, no random ticks, always noon.

**A void world** (the best look: only sky round the courses, nothing below them). The plugin has a
void world built in: no ground, caves, trees, mobs or structures, the_void biome everywhere, and a 5 x
5 smooth-stone platform with a light at its spawn (0, 100, 0), there from the first load, so nobody
arriving falls. Someone who drops off a course is put back on it, as in any Games world.

1. `/mv create sky normal -g HomeCraftManagement` (the same on Multiverse-Core 4 and 5; `-g` names the
   generator, and anything after `HomeCraftManagement:` is ignored).
2. `/mv modify sky set gamemode adventure` (Multiverse-Core 5; on 4.x `/mv modify set mode adventure
   sky`).
3. Put `sky` in your Games world's Multiverse-Inventories group, or give it one of its own (see
   "Turning it on, and the Games world", step 4).
4. `games.worlds: [games, sky]` and `games.fresh.world: sky`, then `/hcm reload`. Fresh Courses, the
   Classics, the kept courses, the Clubhouse and Falling Floors are built in `games.fresh.world`;
   hand-built courses stay in `games`.

Do this **before** anything is built. Once courses stand in a world, changing `games.fresh.world` is a
move by hand (below): clear first. `/hcm games check` says for each Games world whether it is void
("Games world 'sky' is void (nothing below the courses)") and warns when a world's spawn has nothing
under it ("... someone arriving there would fall").

**After a restart.** The generator isn't saved with the world: at every start Multiverse makes `sky`
again and asks for HomeCraftManagement's generator by name, and the server hands out only the
generator of a plugin that is already running. So HomeCraftManagement starts before Multiverse-Core
(plugin.yml: `loadbefore: [Multiverse-Core]`; neither Multiverse plugin is a soft dependency), and
`sky` stays void after every restart. Nothing else changes for you: the games wait a tick for their
world, and a game going on during a stop still gives every player their own things back before
Multiverse-Inventories saves them. Make `sky` with Multiverse only (not in `bukkit.yml`, which the
server reads before any plugin is running), and check it once:

1. Make `sky` (above), stand in it, and look: nothing below the platform.
2. Restart the server. In the start-up log, HomeCraftManagement is enabled before Multiverse-Core, and
   there is no line "Could not set generator for world 'sky'".
3. `/hcm games check` still says "Games world 'sky' is void (nothing below the courses)".
4. Fly a few hundred blocks from the platform, into chunks nobody has been to yet: still only sky.

If the check says "isn't a void world" after a restart, the chunks made since then have ordinary
ground, biomes and mobs: don't build there. Make sure HomeCraftManagement's jar is this version, then
delete the world with Multiverse (`/mv delete sky`, then confirm) and make it again (step 1 of the list
above).

**When an update moves or grows an area** (v4: Golf of the Week and Classic Golf grow to 128 x 16 x
224 and move to x 8768; the Ice Boat's Mountain Run grows to 480 x 176 x 640 and moves north to z
2880), nothing is asked of you. The update moves each shipped spot you never changed (an origin you set
yourself stays, and the course grows in place there). At the restart the course is built again at its
new spot, this set's next course on a fresh board (a first-finish reward isn't paid twice), and its old
area is emptied by itself: only once nothing else is being built (so the course is down as briefly as
it can be), the water drained before any wall goes, and only the plugin's own blocks taken away
(anything else is left where it is and listed). Nothing is written if something of yours, a kept
course, the Clubhouse or the arena is within 16 blocks of the old area: it stays guarded and listed,
with one WARN. `/hcm games check` shows each old area ("Golf of the Week moved to its new area; its old
area is empty") and what is in the way of one; `/hcm games gen tidy <course> confirm` (also called
`retire`) empties one by hand. A stop in the middle is finished at the next start.

**Moving an area by hand.** Nothing you set moves by itself: a place stays where it was built until you
empty it and give it another spot. Change an `origin` (or `half_gap`, or `games.fresh.world`) without
emptying first and the course is treated as a new one: a new course on a fresh board at the new
spot, and the old blocks left standing (a Dropper's or a golf course's old area stays guarded, its
water with it, until you empty it with `/hcm games gen tidy <course> confirm`, which drains it first:
status says "drain first").

- **A Fresh course:** `/hcm games gen clear <course> confirm` (both halves emptied, water first, and
  the course switched off; its board and stars stay). Then change `games.fresh.slots.<course>.origin`
  (and take out its `half_gap`, or set it to 576), `/hcm reload` and `/hcm games gen on <course>`: the
  new area is checked empty and the next course is built there.
- **A Classic:** `/hcm games gen unrecall <classic> confirm` (it empties both halves once nobody is on
  them) and wait for the console's "<classic>'s halves are empty" (status says "empty"). Then change
  `games.fresh.classics.slots.<classic>.origin` (and its `half_gap`) and `/hcm reload`: the console
  says "Its old halves were emptied first", and the next recall checks the new spot empty and is
  built there. Changed before its halves are empty, it WARNs and leaves the old halves as they are (a
  Classic Dropper's or Classic Golf's stay guarded until `/hcm games gen tidy <classic> confirm` empties
  them, water first).
- **The Clubhouse** (`games.clubhouse.origin`) and **Falling Floors** (`games.falling_floors.origin`):
  change the origin and `/hcm reload`. The new box must be empty; the old room's or arena's blocks stay
  where they are, for you to take down.
- **Kept courses** stay where they were kept. `keep.area` and `keep.plot_gap` only place the courses
  kept from then on.
- **A typo in a spot** (say `half_gap: "32"` in quotes, or an origin with a word in it) never moves
  anything: that course or Classic stays where it stands, switched off (even after `/hcm games gen on`),
  until the value reads again; the WARN names the key and `/hcm games gen status` says why. A bad
  `enabled` or tier switches the course off where its origin puts it. An unreadable
  `games.clubhouse.origin` or `games.falling_floors.origin` closes that room until it is fixed. Nothing
  is ever built at the shipped spot in its place. (A key that is missing is different: the next start
  fills it in from the shipped config.yml. On a server that kept 0.35's spots, don't delete an `origin`
  or `half_gap`, or write a course as a bare `false`: write the value you want instead.)
- A spot 576 blocks from every other place (x and z) is out of sight; `/hcm games check` shows what
  can still be seen. The shipped spots (config.yml) are all free once nothing else is built there.

**What players can see** (`/hcm games check`, after the Clubhouse). One OK line per Games world (void
or not), a WARN for a spawn with nothing under it, then the view distance the server really uses (the
largest of the Games world's view and send distances and every online player's send distance there;
the server's own when the world isn't loaded), then either "Nothing else built by the games can be
seen from any course, the Clubhouse or the arena (view distance 10; the closest two places are 36
chunks apart, clear up to view distance 34)" or one WARN per pair of places close enough to see each
other, nearest first (at most 8, then "...and N more"), like "From Tiny Golf, players can see the
Clubhouse (9 chunks away; view distance 10)", each with how to move one by hand, and the view distance
that would hide them when there is one. Places are the courses' and Classics' halves, the kept
courses' plots, the Clubhouse, the arena, the Race Night stands set for kept and hand-built courses
("From the Race Night stand of "Cool Jumps", players can see Sky Rings", fixed with `/hcm games event
stand <course> set` nearer its course), the world's spawn and `safe_spot`. Two spots where nothing is
built (the spawn, `safe_spot`, a stand) are never a pair, nor is a stand and its own kept course. It is
never a FAIL: a course that can see another still works.

**Coming from 0.35 (a server that already built).** At the first start after the update, before the
games start, the plugin looks in its database for anything 0.35 built: a Fresh set or a claimed area,
a Classic holding a recall, a kept course, the Clubhouse or the arena claimed. **If there is anything,
every Games place keeps its 0.35 spot and shape:** config.yml gets the 0.35 origins written out,
`half_gap: 32` under each course and Classic and `keep.plot_gap: 0`, and the console WARNs once.
Nothing is moved, rebuilt or rerolled; boards, codes and kept courses stay as they were. `/hcm games
check` then says so ("This server had built Games places before the update, so on 30 Sep 2026 every
one kept its 0.35 spot and shape") and lists which places can see each other, each with how to move
it by hand (above). If nothing was built, every spot you never changed takes the new layout; one you
set yourself stays, with `half_gap: 32` (or `plot_gap: 0`) so its shape stays too, and a WARN names
it. A fresh install needs none of this: its config.yml is the new layout. The answer is found once and
stored (`gen.layout.guard` in `hcm_meta`); a stop halfway just finds the same answer at the next
start, and the games don't start until it is stored. If config.yml can't be written at that start (a
read-only file), the console says so and the games stay off until it can: they never start on a file
that doesn't hold the answer yet. Never delete the `gen.layout.guard` row: if it is ever damaged the
console names the word to write back (`legacy|...` or `new|...`). `/hcm config reset games` (or
`games.fresh`, `games.clubhouse`, `games.falling_floors`, ...) never puts the new spots in: it leaves
every `origin`, `half_gap`, `keep.area`, `keep.plot_gap` and the Games worlds as they are (the dry run
lists them as kept).

**Stars and tokens.** Parkour and Sky Rings: 3 stars under the gold time, 2 under the silver time
(both fixed when the course is made, from its expert time: see `stars`), 1 for finishing. Golf: 3 at
par or better, 2 within one stroke per three holes over par, 1 for finishing. Only a counted run
earns stars. The first counted finish of each course in each set pays from `rewards`: weekly Easy 2,
Parkour 3, Hard 4, Sky Rings 3, Golf 3, Tiny Golf 2; daily Easy 1, Parkour 2, Hard 3, Sky Rings 2,
Golf 2, Tiny Golf 1; every 2 to 6 days, in between (`round(daily + (weekly - daily) * (days - 1) /
6)`). A finish pays by its own set's length: after a switch from weekly to daily, a finish on the
weekly set still up pays the weekly amount, and the first daily set pays the daily one. A second
finish in the same set, or a finish on a reroll of it, pays no second first-finish token. A course's
first finish ever pays the usual first clear once; golf pays par and holes-in-one once per set. The
Star Chart pays each goal its own tokens: at 6 stars (+1) and 12 stars (+2) a week when weekly, 10
and 25 (+1 each) when daily, and in between for 2 to 6 days - never above 80% of what the week's
courses can give. A run's stars go on the chart of the week it is played in, even when its set
began the week before. **A week's goals are fixed** the first time they are shown or paid, so
switching a course off (or the cadence) mid-week changes next week's goals, not this week's - even
when that leaves the top goal above 80% of what the courses still on can give (accepted: goals never
move under a player's feet).

**All or nothing.** The daily caps are unchanged and still apply, and no weekly amount is above the
cap of the game that pays it (Hard Parkour's weekly first finish is 4 for that reason). A set's
first-finish token and a Star Chart goal are paid in full or not at all: if what is left of today's
caps (the course game's or the server's) is smaller than the reward, nothing is paid and nothing is
used up, and the player reads once "You've reached today's token limit - finish it again another day
this week for its tokens." when the set is weekly (every 2 to 6 days: "... finish it again another
day before the courses change for its tokens."; daily, where the set has no other day: "You've
reached today's token limit - your time and stars still count!"; a Star Chart goal always reads the
weekly line). A finish on another day of the same set pays it (a goal: any counted
finish later that week). Every other reward keeps paying what is left of the caps. The parkour and
Sky Rings courses share `games.trials.daily_cap` (4 a day), the golf courses `games.golf.daily_cap`
(4), the Star Chart `games.fresh.daily_cap` (2), and every skill game together
`games.skill_daily_cap` (6). So a player who plays a whole weekly set in one day earns at most 6
tokens that day (plus the once-ever first clears), and the rest is waiting on the other days of the
week; reaching both weekly goals on the same day pays the 6 goal's 1, and the 12 goal's 2 the next
day that player finishes a course.

**What players see.** `/hcm play fresh_courses` (the Fresh Courses tile on the Courses and Golf
tabs) opens "This week's courses - Mon 28 Sep-Sun 4 Oct" ("Today's courses" when daily, "The
current courses" for any other cadence): one tile per course, whose NAME carries the player's stars
in this set, a golf course's holes and par, and its **course code** ("Hard Parkour - ★★☆ · Course
code HARD-40"); then the four Classics (Classic Parkour, Classic Sky Rings, Classic Golf and
Classic Dropper: a course an admin brought back, one being built, or empty
with the tip "Loved an old course? Tell an admin its course code..."), the Star Chart (this week's
stars, the next goal and what it pays) and How stars work. Every line follows the cadence: "This
week's best", "Your best this week", "First finish this week: +2 tokens", "(new this week)" on a
course's Courses-tab tile and "(last week's)" while the new set is still being built; "today" only
when daily. A finish ends with the course code in chat ("Course code HARD-40"), and the course and
result screens show it in their header's NAME. `/hcm play fresh_parkour_tiers` is "Parkour Levels".

| Key | Default | Meaning |
|---|---|---|
| `fresh.enabled` | `false` | Fresh Courses' switch (also needs `games.enabled`) |
| `fresh.world` | `""` | The world they are built in; `""` = the first of `games.worlds` (it must be listed there) |
| `fresh.cadence` | `weekly` | How often the courses change: `weekly`, `daily`, or a number of days from 1 to 28 (like `3`). Junk: one WARN, weekly |
| `fresh.rebuild_at` | `"04:00"` | When a new set starts (`clock.time_zone`, in quotes); keep it at a restart |
| `fresh.rebuild_day` | `""` | The day a weekly set starts (`"monday"`); `""` = the quests' week start (`arcade.quests.week_starts`). Other cadences count their days from it too. Moving the change day later keeps this set until the new day comes round (see above) |
| `fresh.startup_delay_seconds` | `60` | After the server is up, before the first build |
| `fresh.avoid_before_restart_minutes` | `15` | No build starts this close to one of `games.restart_times` |
| `fresh.retry_minutes` | `30` | A failed build is tried again after this |
| `fresh.max_tries_per_day` | `4` | Tries per course per day; after that the last course stays up until the next day |
| `fresh.clear_wait_minutes` | `20` | How long a player still on a course the next build needs gets (after a second quick reroll only) |
| `fresh.keep_days` | `35` | Course boards and star rows older than this are pruned; each course always keeps its last 8 sets (Star Charts: 12 weeks) |
| `fresh.world_rules` | `true` | No mobs, fire, random ticks or weather in that world; always noon |
| `fresh.safe_spot` | `""` | "x y z" where people standing in a building area are moved; `""` = the world's spawn |
| `fresh.daily_cap` | `2` | Most Star Chart tokens a player earns a day (a goal is paid whole or waits for another day that week) |
| `fresh.announce` | `true` | When a new set is up, each player reads one chat line about it, once per set: "New courses this week! Easy, Parkour, Hard, Sky Rings, Golf and Dropper - /hcm play" ("today" when daily, "new every 3 days" for 3). It waits until every course of the set is up (or 15 minutes after the first), for players in a world the games are played in and not in a world game, and until a screen is closed (or a minute). Players who log in later read it a few seconds after joining. `/hcm play news off` turns it off for one player; `false` for everyone |
| `fresh.rewards.clear_weekly.*` / `fresh.rewards.clear_daily.*` | see above | Each course's first-finish tokens at a weekly and at a daily cadence; other cadences are worked out from the two. Paid whole or not at all (on a day whose caps can't hold it all, nothing is paid and it waits for another day of the set). An amount bigger than a whole day's cap (the paying game's `daily_cap`, or `games.skill_daily_cap`) pays that cap once, so keep each at or under them (4) |
| `fresh.star_goals.weekly` / `.weekly_tokens` | `[6, 12]` / `[1, 2]` | The weekly Star Chart goals and what each pays, at a weekly cadence (a week's goals are fixed once shown: a change counts from the next week) |
| `fresh.star_goals.daily` / `.daily_tokens` | `[10, 25]` / `[1, 1]` | The same at a daily cadence |
| `fresh.budget.*` | `500` / `5000` / `4` / `4` / `2` / `40` | Blocks per tick online / idle, ms per tick, snapshots per tick, chunk loads at once, and the average tick time (ms) above which building pauses (it goes on below 3/4 of it) |
| `fresh.stars.gold.*` / `fresh.stars.silver.*` | easy 2.0 / 3.0, medium 1.5 / 2.2, hard 1.25 / 1.8 | The 3-star and 2-star times as a factor of each course's expert time (tune after the first week) |
| `fresh.slots.<course>` | see config.yml | Each course: `enabled`, `tier` (or `mix` of golf holes, E/M/H) and `origin` (x y z of its area, x and z a multiple of 16). Move one by hand only (see "Moving an area by hand") |
| `fresh.slots.<course>.half_gap` | not set (576) | Optional: blocks between a course's two halves along x (32-4096, a multiple of 16). 576 keeps the spare half, where the next course is built, out of sight; a server that built in 0.35 has `32` written |

The courses (their ids are also their `/hcm play` ids): `fresh_parkour_easy`, `fresh_parkour`,
`fresh_parkour_hard`, `fresh_rings`, `fresh_golf`, `fresh_tiny_golf`, `fresh_boat` (off), and the
droppers `fresh_dropper_easy` and `fresh_dropper` (on, with the rest of Fresh Courses; see "The Dropper" below).
`/hcm play fresh_courses` opens the Fresh Courses screen and `/hcm play fresh_parkour_tiers` the
parkour level picker.

**Picking a good course.** Don't like how this week's came out? **Regenerate it**:
`/hcm games gen regenerate <course> confirm` (or `retry`, the same as `reroll`) makes a new one
for this week on a fresh board; anyone on the old one finishes first, and a running Weekly Cup there
is called off and refunded. Or find next week's a week early: `/hcm games gen preview <course> next`
builds a candidate for next week (next week's tier or mix, a random seed it tells you) in the spare
half, where nobody can play it; `/hcm games gen test <course>` takes you round it on a test run (its
real start, checkpoints, finish, clock and kit; nothing is recorded or paid; golf is walked with
`tp <course> idle`); don't like it, preview next again for another; like it,
`/hcm games gen choose <course>` makes it next week's course. Monday 4:00 AM it goes up as usual, on
fresh boards with its own course code (the website shows its code and seed), with almost nothing
left to build, and the week after goes back to normal. `/hcm games gen unchoose <course>` changes
your mind; `/hcm games gen status` shows "next set: chosen seed ...". The same tools are on each
Fresh course's own screen for admins: the **Admin tools** item (bottom left) opens "Make a new
course now (regenerate)", "Build one to try (preview)", "Build next week's to try (preview next)",
and once a preview stands "Try the preview (test run)", "Use it now (promote)" and "Use it next week
(choose)"; regenerate, promote and choose ask "Sure?" first. A Yes acts only on the preview its Sure
screen showed: if a new one was built while it was open, nothing happens and it says so; and promote
and choose wait while the course is being built (its name says so). A pick that a later change
drops (another tier, a fall depth that shapes it differently, a moved rebuild day, in config or by
command) says so on the Admin tools and in `status` until its week is over or you pick again, across
restarts. Players never see them.

**Trying a course that is switched off** (new in 0.36, for Ice Boat, which ships off). `preview`,
`preview next`, `test` and `tp <course> idle` work on a Fresh course that is switched off (Fresh
Courses itself must be on). The first preview checks and claims its area like a first build would
(foreign blocks: "Region has N blocks that aren't Fresh Courses' ... - /hcm games gen claim <course>
confirm clears them"), builds into the spare half and never opens it: every reply ends "<Course> stays
off: only an admin's test run can play the preview, and nothing is recorded or paid. Switching it on
(/hcm games gen on <course>) opens its own course for this set, not the preview." `promote` and
`choose` need the course on (they say what to do instead), and the Admin tools leave out "Use it now"
and "Use it next week" while it is off. `on` builds the set's own course in the spare half, over the
preview, and opens it once built ("Ice Boat is on. Its course for <set> is built next, in half A over
the preview there, and opens once it's built."); a preview still on its way is dropped. To open on a
course you have ridden: `plan <course>` names this set's seed, `preview <course> <that seed>`, `test`,
then `on` builds that same course. A restart forgets a preview made while off (its blocks stay):
preview again before `test`.

**Commands** (`hcm.games.admin`). `reroll` (and `retry`/`regenerate`), `clear` and `claim` (to clear
an area) need `confirm`; `promote` needs it when the course's board already has times, and `choose`
when it would replace another pick. `reroll`, `preview` (and `preview next`), `test` and `promote`
are refused within `avoid_before_restart_minutes` of a restart. Every change is logged with who made
it.
The course and golf editors refuse a generated course ("This course is made by Fresh Courses - use
/hcm games gen.") and anything of a hand-built course within 16 blocks of a Fresh Courses area: a
point, a checkpoint or finish with its radius, a golf hole's whole bounds box.

| Command | What |
|---|---|
| `/hcm games gen status [course]` | First how often the courses change and when next ("weekly (Mondays at 4:00 AM) · next: Mon 5 Oct 4:00 AM (in 6d 14h)"), then each course: on or off, its tier or mix, which set is live (and its key, like `7:38`), its half, rev and seed, who is playing it, and the last build (plan time, blocks, ticks, chunks, verify). With a course: its set's key and end, its area and pin too |
| `/hcm games gen plan <course> [seed\|next]` | A dry run, no blocks: what a build would make (its blocks, hash and the planner's own summary), for this set or the `next` one |
| `/hcm games gen preview <course> [seed]` | Build a new course into the spare half without switching, to try it (`test`) or walk it (`tp <course> idle`). The next scheduled build clears it away (unless it was chosen: see `choose`). Works while the course is switched off too: the first one checks and claims its area, and the course stays closed (see "Trying a course that is switched off") |
| `/hcm games gen preview <course> next [seed]` | A candidate for the NEXT set: its tier or mix and settings, and a seed (random unless you give one; the reply says it), in the spare half. Previewing again replaces it |
| `/hcm games gen test <course>` | A test run on the preview: its real start, checkpoints, finish, clock and kit (the Dropper offers its practice drop as usual). Records nothing: no board, token, star, Cup time, quest or achievement. Refused with no preview ("No preview yet - /hcm games gen preview <course> first"), near a restart, and while the course is off for a problem ("Ice Boat is off: Region has ..."); a course that is only switched off can be tested. Golf previews are walked instead (`tp <course> idle`) |
| `/hcm games gen promote <course> [seed] [confirm]` | The preview (of this set) becomes the current course, on a fresh board. With a seed, only while the preview is still that seed (what the Admin tools' Yes sends). Refused while the course is switched off |
| `/hcm games gen choose <course> [seed] [confirm]` | The preview's seed becomes the course of exactly the next set: it goes up at the scheduled change on fresh boards, with its own course code and seed on the website, and the set after goes back to its own seed. Kept across restarts (the spare half keeps the chosen preview, which is checked again after a restart, so the change has very little to build). `confirm` when it replaces another pick. With a seed, only while the preview is still that seed. The pick is built at the change as it was tried, even when its seed is the live one's. Refused for a Classic, while the course is off or being built, with no preview, or when the preview was built at another tier, mix or (medium and hard parkour) fall depth. A waiting pick is dropped if the tier or mix changes, if a `fall_depth` change would reshape it, or if a `cadence` or `rebuild_day` change moves the next set. You, the console, `status` and the Admin tools are told (the last two until that set is over or you pick again, across restarts), and you choose again. Its pin, if any, comes back after it |
| `/hcm games gen unchoose <course>` | Cancel the pick: the next set gets its own new course |
| `/hcm games gen reroll <course\|all> confirm` | A new course for the current set, on a fresh board; anyone playing the old one finishes there, and a running Weekly Cup on it is called off and refunded. No second first-finish token |
| `/hcm games gen retry\|regenerate <course\|all> confirm` | The same as `reroll` |
| `/hcm games gen rebuild <course>` | Check the current course against its plan and put back anything missing (same course). After a `clear`, its area is checked for other blocks first and a new course is built |
| `/hcm games gen on\|off <course>` | Open or close one (kept across restarts). `off` ends runs on it ("Easy Parkour is closed for now."); its blocks stay |
| `/hcm games gen tier <course> <easy\|medium\|hard>` | Its difficulty from the next build (kept across restarts). A change drops a waiting pick: choose again |
| `/hcm games gen mix <golf course\|dropper> <E, M and H>` | The big golf course's or Tiny Golf's holes, or a dropper's levels, from the next build (like `EEEMMMMHH`, `EEMHH`). A change drops a waiting pick: choose again |
| `/hcm games gen pin <course> <seed\|live> [days]` / `unpin <course>` | Keep a good course: the same blocks in every new set (each on fresh boards) until unpinned, or for that many days. A pin ends by itself after its days; one made before a plugin update that changed that course's generator is ignored (the console and status say so). `pin <course> live` is refused, and nothing is pinned, when the course up now was made by an older planner (after an update: "... so it can't be made again after the update"); it stays until its set ends, and `/hcm games gen keep <course> current <new-id> confirm` keeps it for good. A typed seed that an archived set of an older planner was made from is pinned, with a warning: the pin makes a new course from that seed, and the reply says how to have the old one back as it was (`recall <code>`, or `keep`) |
| `/hcm games gen tp <course> [live\|idle]` | Go to the current course, or the spare half |
| `/hcm games gen claim <course> [confirm]` | Count what is in a new area; with `confirm`, clear it and let the course use it (refused while a hand-built course or the spawn is within 16 blocks) |
| `/hcm games gen clear <course> confirm` | Empty both halves and switch the course off (do this before moving a course's `origin`: see "Moving an area by hand") |
| `/hcm games gen tidy <course> [confirm]` (also `retire`) | The old area a course (or Classic) left when an update moved or grew it, or an owner's move left: lists it, and with `confirm` empties it now (only the plugin's own blocks, water first; anything else stays and is listed). An area an update moved is emptied by itself, so this is the fallback (see "When an update moves or grows an area") |

The overrides (`on`/`off`, `tier`/`mix`, `pin`, `choose` (a one-set pin), rerolls per set, the claimed area, and the schedule
with when it was first seen) live in `hcm_meta` under `gen.*`. Generated courses are ordinary
`game_courses` rows with a `gen:` block (its `day` is the set's first day, its `cadence` the set's
length), and their boards are ordinary `game_scores` rows: `gfresh:<course>:<set>` (that course's
board for one set; the set is `<days>:<number>`, like `7:38` for the week of 28 Sep 2026, so a daily
and a weekly set never share one), `gstars:<course>:<set>` and `gweek:<week>` (the Star Chart). No
new tables.

<!-- ---- Course Variety (COURSE-VARIETY-SPEC) ---- -->
#### Adventure Golf

New in 0.36 (golf planner algo 3): **Golf of the Week and Tiny Golf are Adventure Golf.** Each hole
is still drawn from a shape with seeded lengths, widths and a mirror, and still proven before a block
is placed, but the planner now has eleven more shapes, sand, water and trees to draw with, and a
weekly quota that makes every course a mix of them. Hand-built golf courses don't change at all.

| Shape | Tiers | What it is |
|---|---|---|
| Sand trap | Easy, Medium | Easy: a flush 3 x 3 patch of sand before the cup. Medium: a sunken bunker across the middle, with turf either side |
| Hump | Easy | A slab up, two or three rows a block up, a slab down, then the cup |
| Hill | Medium | Slab steps up to a crest a block up, then a 1-block lip down, the cup past it |
| Volcano | Hard | The cup on a summit two blocks up, ringed by a terrace: too soft stops on the terrace, too hard rolls off the far side |
| Terraces | Medium, Hard | The tee two blocks up, a drop to one up, a drop to the turf and the cup; the terraces' edges are light blue glass (the "glass waterfall"). Hard: a pond beside the bottom terrace |
| Dogleg down | Medium, Hard | A right-angle dogleg whose first leg is a block up and drops at the corner. Medium: a slime bank at the corner; Hard: a sunken bunker there |
| Tree garden | Easy, Medium, Hard | A straight with trees in play: 1 on Easy, 1-2 on Medium, 2-3 on Hard |
| Pond side | Easy, Medium | Easy: a pond beyond the side wall, to look at. Medium: a pond along one side of the lane, no wall between |
| Island pond | Hard | A causeway with water both sides, to a green with water round it |
| Two ways | Medium, Hard | The lane splits round a tree island: a short way past a pond (sand first on Hard) or a long dry way |
| Creek | Medium | A creek across the lane with a 3-wide turf bridge |

The eight shapes it had (straight, bumpers, ramp, dogleg, ice run, island, S-bend, narrow ice) are
still dealt, and the flat safe straight is still the fallback a hole always has.

- **The weekly variety quota.** Each tier's shapes are dealt in a seeded order, and of 64 deals the
  course takes the first that gives it its targets (or the one that comes closest): 7 holes or more
  (Golf of the Week's 9) 2 holes with water in play, 2 with sand, 3 with height (a ramp, a raised
  green, a hump, a hill, the volcano, terraces or the dogleg down), 1 tree hole and 1 big drop (a
  whole block the ball flies off: a hill, the volcano, terraces or the dogleg down); 4-6 holes 1, 1,
  2, 1 and 0; Tiny Golf's 3 holes a sand hole, a height and a tree garden or a pond to look at. Each
  target is capped at what the mix's tiers can hold (water in play needs Medium or Hard holes). A
  hole that fails its proof falls back to another shape, so a course can end up short of a target;
  `/hcm games gen plan <course>` says what it got, a line per hole with its features and then
  "quota: water 2/2, sand 2/2, height 3/3, trees 1/1, big drop 1/1 (deal 0)".
- **Sand** is smooth sandstone, flush with the turf or a sunken bunker half a block down, and slows
  the ball as soul sand does. Tee sign: "Sand is slow! / Hit it harder".
- **Ponds and the creek are in play only on Medium and Hard holes.** Easy holes never have water in
  play (an Easy pond is behind the wall, to look at), and **Tiny Golf never deals a shape with water
  in play, whatever its mix**. Into the water is the usual "Splash! Back to your last spot, +1
  stroke.", and a ball that comes to rest over water (hanging on a pond's edge) has fallen in too.
  A pond is one block deep and sealed, never beside the tee or within 2 blocks of the cup's ring;
  anyone who walks in can step straight out. On a hole with water in play, par is the safe line:
  every putt of the expert's line is proven to stay dry a few degrees either side.
- **Trees.** Trees in play are log trunks with real leaves above head height, kept clear of the tee
  and the cup so the flag is always seen from the tee: putt round them or bank off a trunk. Every
  plot also has 2-5 trees of oak, birch or cherry on moss planters, outside the hole where no ball
  goes. Leaves are placed as they would grow, so they never decay and verify never sees them change.
- **The wobble rule.** A soft putt can wedge the ball's edge against a half-block step, where it
  jiggles on the spot. On Adventure Golf a ball that only wobbles on the spot for about a second
  comes to rest on what is under it: in the cup if that is the cup, a splash if it is water. (On
  every other course a ball still rolling after 30 seconds is stopped where it is, as before.)
- **Tee signs** say what the hole holds, under "HOLE n" / "Par p": a pond, creek or island "Mind the
  pond! / Splash = +1"; sand "Sand is slow! / Hit it harder"; a hump or hill "Up and over / the
  hill!"; terraces "Down the steps! / Watch it roll"; the volcano "Up the volcano! / Not too hard!";
  trees "Bank off the / trees!"; two ways "Pick a path! / Short or safe?"; the dogleg down "Round the
  bend / and down!"; anything else (Easy's pond to look at too) "Hit the ball / to the flag!".
- **Still proven.** Par is the expert's fewest putts plus 1 (Easy holes par 2-3, Medium and Hard
  3-4); the sloppy player always finishes within par + 1 and is never wet; every spot a ball comes to
  rest on is lane; and the expert's line is played again on the real blocks after every build and
  boot. Walls stand where a ball could reach them: a block above the lane beside them, higher where a
  ball flying off a lip could get there.
- **Recalled and kept courses keep the rules.** A Classic Golf recall of an Adventure course plays
  Adventure Golf's rules, and so does a course kept from one (`/hcm games golf <id> info` says "Kept
  from Adventure Golf: its smooth sandstone plays as sand, and a ball that stops over water falls in
  (+1, back to its spot), as when it was made."). Hand-built courses, and golf layouts from an older
  planner, play exactly as they always did: smooth sandstone is ordinary stone there.
- **Water, safely**, as for the Dropper: ponds are written last with no physics and drained first,
  the area guard stops any flow into or out of a golf area, `clear` drains them first, and a golf
  course whose `origin` moves without a `clear` keeps its old area guarded ("drain first": `/hcm games
  gen tidy <course> confirm` empties it, ponds first). The boot
  check of a golf layout from an older planner (or a Classic Golf course re-made from its seed)
  scans every hole's whole plot, a pond to look at included, and only full blocks count as sealing a
  pond (not a slab, sign or leaves): a gap keeps that course closed, logs a SEVERE line that contains
  "a pond isn't sealed", and a new course is built.
- **No new settings.** Shapes, quotas and the rules are part of the planner's version, not config,
  so a pin or a pick always names the same blocks. `fresh_golf.mix` (`EEEMMMMHH`) and
  `fresh_tiny_golf.mix` (`EEE`) are as before; the quota adapts to any mix.

**Verify in game** (on Java and on Bedrock)

1. `/hcm games gen plan fresh_golf`: one line per hole with its shape and features, and the quota
   line. `/hcm games gen tp fresh_golf` and walk it: each tee sign names its hole's feature.
2. On a Medium pond hole, putt into the pond: "Splash! Back to your last spot, +1 stroke.", and the
   ball is back where you putted from. Roll one gently onto a pond's edge: it falls in the same way.
3. The wobble: roll the ball gently until it rests against the lip of a sunken bunker or a slab
   step, then tap it (club 1) straight into the lip. It jiggles for about a second and comes to rest
   on the lower side, never hanging in the air; putting again from there plays normally.
4. Putt across sand: the ball slows sharply. A soft tap may stay in a sunken bunker; a harder putt
   gets out.
5. Terraces, the volcano and a hill: the ball tumbles down the steps and keeps rolling, and nobody
   and no ball is ever trapped.
6. Walk into a pond and out again, on Bedrock too: you can always step out.
7. Play under trees: the flag is visible from the tee, and jumping only bumps the leaves.
8. Tiny Golf: no hole has water in play (a pond, if any, is behind the wall).
9. `/hcm games gen rebuild fresh_golf`, `/hcm games gen reroll fresh_golf confirm` and a restart
   with a pond hole up: no water ever flows.
10. A hand-built course with smooth sandstone in it plays as it did: the ball doesn't slow there.
11. After a week of daily sets: the console shows no blocks put back on leaves, ponds or sand, and no
    leaves have decayed.

#### Ice Boat: the Mountain Run

New in 0.36 (boat planner algo 3): **Ice Boat is a downhill race**, not a flat loop. Each set's track
is a sprint from the top of a mountain to the bottom: it starts high on the rim of its area and
spirals in round the viewing stand as a rounded square, stepping down at **4 to 6 drops** to the gold
finish line at the foot of the stand, so everyone who has finished watches the rest come in. A
**Hop** drops 1 block; on Medium and Hard some drops are a **Big Drop** of 2. Every drop lands on a
long straight. In both walls, yellow blocks under a light mark each drop's edge, light blue blocks
each checkpoint and gold blocks the finish; magenta arrows point the way; and a sign on the wall a few
blocks before each drop, sandy bend and piece (but a boost strip) says what comes next. There is no
water anywhere on the track, and sand is never forced: an all-ice line always runs from the top to
the bottom.

| Tier | Lane | Ice | Drops | Its deck of pieces |
|---|---|---|---|---|
| Easy | 9 wide | packed | 4-5 Hops (at most 5 blocks down in all) | sandy bends, a sand pit, a split, sometimes an ice cave |
| Medium (shipped) | 7 wide | packed | 5-6, at most two Big Drops (7 down at most) | sandy bends with kerbs, a sand pit, a split, an ice cave, a forest, 1-2 boost strips |
| Hard | 5 wide | blue | 4-6, at most three Big Drops (7 down at most) | sandy bends with kerbs, 1-2 sand pits, 1-2 splits, 1-2 ice caves, a forest |

Each piece goes only where it fits (never in the first 40 blocks, on a drop's run-up or landing, or
at the finish), so a run often has fewer than its deck: sand pits and caves need a flat stretch away
from every drop, so they are commoner on Hard than on Easy and Medium.

- **Sandy bends** ("SANDY BEND / Sand is slow, / ice is fast!"): every bend tighter than a radius of
  24 has sand on its outside (1 column on Easy, 2 on Medium and Hard) and, on Medium and Hard, a sand
  kerb on its inside. Go wide and you crawl; the full lane of ice is still there.
- **The sand pit** ("SAND PIT! / Stay on the ice / to go fast!"): the lane widens round a pit of sand,
  with an ice way past it on both sides.
- **Pick a path** ("PICK A PATH! / Left or right?"): the lane splits round a moss island with a tree;
  both ways are wide enough and at the same height, and on Medium and Hard one has a patch of sand.
- **The Ice Cave** ("ICE CAVE / Lights on!"): 12-20 blocks under a light blue glass roof, with sea
  lanterns in the walls.
- **The forest** ("FOREST / Weave through / the trees!", Medium and Hard): tree trunks to weave
  round, alternately near each wall under a leafy roof: 3 on Medium and 4 on Hard, or one fewer where
  the checkpoints need the room.
- **Boost strips** (Medium): the middle 3 columns blue ice for 10-20 blocks, never just before a drop.
- **The drops**: "HOP! / Little drop" and "BIG DROP! / Hold on!". On some Medium runs (about one in
  three) the last drop, the **Final Drop**, is right in front of the stand with the gold finish line
  next: its sign says "FINAL DROP! / Then the gold / finish line!", and each racer sees a big "Final
  drop!" title at the checkpoint before it. Everywhere else the last drop is further up the mountain,
  with its own HOP! or BIG DROP! sign and no title.
- **The mountain**: moss terraces between the rings, a stepped moss cone under the stand, and oak,
  birch and cherry trees, all kept clear of the track and below the stand's floor, so the view from
  the stand is clear.
- **The start** ("DOWNHILL RACE! / Follow arrows / down to the / gold finish!") is in a launch pit
  where the automatic grid seats 12 boats in rows of two.
- **Checkpoints** are at most about 60 blocks apart, with one between every two drops. Back to
  checkpoint, getting out of the boat (a sneak on Bedrock) or a fall puts you back at the last one, at
  most about 60 blocks up the track, facing down it.
- **The tile** shows the drops: "Ice Boat - 5 drops · ★★☆" on the Fresh Courses screen and the
  Courses tab. A kept Mountain Run is an ordinary boat course, and its tile doesn't count drops.
- **Race Night** races it as **"3 downhill races"** (one run each, no laps): the heads-up and
  join-now chat lines add "This week: 5 drops down the mountain!" (`games.race_night.hype`, shipped
  `true`), the warm-up is "Warm-up runs", the grid title says "Ice Boat · you start 3rd" with no lap,
  and finishers wait on the stand. See [Race Night](#race-night).
- **Still proven.** Before a block is placed, every Mountain Run is proven from its blocks alone,
  without simulating anyone's driving: downhill only (a boat can't climb), walls 2 blocks above
  anything a boat can reach and round the whole of every drop's flight zone (how far a boat at top
  speed could fly before it lands), at most one drop between two checkpoints, checkpoints nobody can
  skip, four blocks of headroom, 12 grid spots, and the stand out of reach. A plan gets 20 tries
  (the last ten with fewer pieces); if none is proven, a plain safe spiral with the tier's fewest
  drops is built, so the course is never missing. `/hcm games gen plan fresh_boat` shows the run:
  "Ice Boat v3 medium: Mountain Run, ... blocks, clockwise from north, try 3/20", its drops, Final
  Drop and pieces, then the grid, checkpoints and blocks, and its reference time (the centreline at
  30 blocks a second).
- **An older flat loop** still up when the plugin is updated keeps its stored layout until its set
  ends (it races as 2 laps); every new set is a Mountain Run.

**Before switching it on (the owner's checklist).** Ice Boat ships switched off on purpose: the
Mountain Run's proof rests on how far a boat really flies off a drop on Java and on Bedrock, which is
checked by hand first.

*Gate 0, the boat test strip* (about 30 minutes, no plugin commands). In a test area, build a
straight of packed ice and one of blue ice, each with a 1-block and a 2-block drop; walls of wood with
glass on top, 2 above the ice; a smooth sandstone patch; a 4-wide gap between log posts; and a 4-high
glass-roofed tunnel. Then, on a Java client and on a Bedrock one:

| # | Do this | It passes if |
|---|---|---|
| TS1 | Drive into a 1-block step and into the 2-high wall, at full speed and at an angle | You never climb onto either |
| TS2 | Take each drop at full speed and mark the landing | Every landing is within 40 blocks (1-block drop) or 51 (2-block drop) of the edge |
| TS3 | 2-block landings, 10 times | The boat never breaks, the rider never gets out, no damage, nothing dropped |
| TS4 | Watch the console at the drops on Bedrock | No "moved too quickly" or "moved wrongly" lines |
| TS5 | Drive onto sand, and out of sand while facing a wall | It crawls; you can always turn and paddle out |
| TS6 | Blue ice at top speed | Under 75 blocks a second (a clean run is never voided) |
| TS7 | Two or three boats over a drop together | Stacked boats stay inside the walls |
| TS8 | Push against a wall and turn | You can always turn away |

*Then a preview, while it is still off:*

1. `/hcm games gen status fresh_boat` shows it off.
2. `/hcm games gen tier fresh_boat easy`, then `/hcm games gen preview fresh_boat`: "A preview of Ice
   Boat (seed ...) is on its way into half A. Ice Boat stays off: ...", then "The preview of Ice Boat
   is ready in half A (seed ...)". Watch the tick time while it builds. If the area has blocks in it
   ("Ice Boat: Region has N blocks that aren't Fresh Courses' ... - /hcm games gen claim fresh_boat
   confirm clears them"), run that claim and preview again.
3. `/hcm games gen test fresh_boat` on Java, then again as a Bedrock admin; `/hcm games gen tp
   fresh_boat idle` to walk it. Do the checks marked *preview* below.
4. The same with `medium` and `hard` (for more seeds, `preview fresh_boat <seed>` or `preview
   fresh_boat next`).
5. On one day, so the set doesn't change: set the tier you want live. For this set's own course,
   `/hcm games gen plan fresh_boat` names its seed: `preview fresh_boat <that seed>`, wait for
   "ready", `test`. For another seed you liked: preview it, `test`, then `/hcm games gen pin fresh_boat
   <seed> 1`.
6. At a quiet hour, `/hcm games gen on fresh_boat`: "Ice Boat is on. Its course for <set> is built
   next, in half A over the preview there, and opens once it's built." (`on` before "ready" drops the
   preview; the set's course still opens, untested.)
7. `/hcm games gen tp fresh_boat live` and ride it, then the checks marked *live*. If anything is
   wrong, `/hcm games gen off fresh_boat`: runs end, the course closes, its blocks stay.
8. Later weeks: `preview fresh_boat next`, `test`, `choose fresh_boat`.

**Verify in game** (each on Java and on Bedrock)

1. *Preview:* ride all three tiers top to bottom: every drop feels like a little jump, and the sand
   pit and splits are easy to read.
2. *Preview:* go wide on purpose at every sandy bend: slower, never stuck.
3. *Preview:* cross checkpoints fast, and right after landings: every one counts, in order.
4. *Preview:* on Hard, use Back to checkpoint at the sand pit, a split or the cave: you come back
   within about 60 blocks, facing down the track. Get out (sneak on Bedrock) on a lower ring, in the
   cave, on a sandy bend and just after a drop: back to the last checkpoint, on flat ice, facing
   onward.
5. *Preview:* the Ice Cave: no head bump, the camera is fine on Bedrock, and it is bright enough.
6. *Preview:* on Easy and Hard there is no "Final drop!" title; on a Medium run with the FINAL DROP!
   sign, the title shows at the checkpoint before it.
7. *Live:* the automatic grid in the pit (`/hcm games event grid fresh_boat show`): the layout
   proves 12 spots, and Race Night seats up to `race_night.max_racers` of them (8 by default);
   boats are held until Go.
8. *Live:* 4-8 boats over the first drop and the Final Drop: nobody leaves the track.
9. *Live:* watch from the stand: you see every drop and the finish, and the trees stay below eye
   level.
10. *Live:* a warm-up: each run at the finish goes back to the top.
11. *Live:* a 4-year-old on a tablet rides Easy: they finish, or at least enjoy the back seat (Take a
    rider).
12. *Live:* the tick time while a Mountain Run is built (about 15,000 blocks) stays under
    `games.fresh.budget.pause_above_mspt`.
13. A family Race Night of 3 to 5 races: the drops line in the heads-up, the bar with no lap, the
    finish below the stand.
<!-- ---- end Course Variety ---- -->

<!-- ---- dropper (EVENTS-DROPPER-SPEC §B.1, WP-D) ---- -->
#### The Dropper

Two more Fresh Courses, new with every set and **shipped on** (like the others, they are built only once
`games.fresh.enabled` is on): **Easy Dropper** (3 easy levels) and
**Dropper** (5 levels, easy to hard). A level is a glass shaft in its own colour: step off a lime
ledge ("LEVEL 2 of 5 / Step off and / fall into the / WATER!"), steer through the holes in the
coloured floors below, and land in the water at the bottom. On Easy every hole on the way down is
ringed with glowing sea lanterns ("follow the light") and the whole floor is water. A splash clears
the level ("Level 2! of 5 - keep going!") and a quarter of a second later you are on the next ledge;
the last splash is the finish ("Splash! 0:21.4 · ★★★"). Landing on anything but water (the rim of a
pool too, even with half of you over the water: only a body wholly over the water splashes) is a **bonk**:
"Bonk! Back to the top of level 2.", the clock keeps running, and the first one adds the tip "Steer
while you fall to go through the holes!". The result screen says "No bonks - perfect drop!" or
"Bonks: 2". Every level is proven solvable before it is built: a walk-only witness path with room to
spare, and 171 late and sloppy walk-only pilots per level, all in vanilla physics.

- **Practice drop** (the owner's warm-up, D3): before the timed drop, the hotbar offers **Practice
  drop (not timed)** and **Go straight to the timed run**. A practice drop is one untimed drop of
  level 1 ("Practice drop - not counted"); it ends at its first splash, its first bonk, or **Start
  timed run**, then you are back on the ledge for the 3-2-1. It is never timed, recorded, paid or
  counted for the Weekly Cup, and its bonks don't count. One per run; `games.trials.warmup_seconds: 0`
  turns it off with the other warm-ups.
- **Score and rewards:** the time from Go to the last splash, lower is better, on the set's board.
  Stars use the mix's rounded tier (EEE is easy, EEMMH medium): 3-star times of about 20 s on Easy
  Dropper and 27 s on the Dropper. First finish in a set: Easy Dropper 2 tokens a week (1 a day),
  Dropper 3 (2 a day), under `games.trials.daily_cap` like the other trials; the first clear once ever
  by tier; the Star Chart counts its stars. A clean counted run unlocks the achievement "Reach the
  bottom of a Dropper with no bonks" (20 tokens; config revision 18 adds it to an unedited list, or
  WARNs with the line to paste).
- **Fair play:** as any time trial (flying, potions - slow falling too - or a changed gravity or
  safe-fall attribute void the run). The game's own hops to the next ledge are never speed-checked;
  a run quicker than 90% of the levels' walk-off falls doesn't count. A run going at the weekly change
  finishes and counts on its own set's board. Two fallers in one shaft can't push each other (a
  no-push scoreboard team for the run; a player on another plugin's team, for nametags, goes back on
  it when the run ends).
- **Water, safely:** water only ever sits in sealed pools at least a block inside the area, written
  with no physics, after every wall of the area is up, and drained before any wall is taken down; the
  area guard stops water flowing into an area **and out of one**; verify puts back a missing water
  block like any other. Time Trials holds every Dropper course's pools itself too, so no pool spills
  even while Fresh Courses is off; a kept Dropper's plot keeps its water in; and a Dropper whose
  `origin` is moved without a `clear` keeps its old area guarded ("drain first": `/hcm games gen tidy
  <course> confirm` empties it, pools first; status lists it) (see "Moving an area by hand").
- **Where:** in the east column: half A x 7488-7551 and half B x 8128-8191, y 160-223, z 6768-6783
  (Easy Dropper) and 7360-7375 (Dropper); Classic Dropper (recalls of either) at z 7952-7967. Each
  half is 64 x 64 x 16. The keep plot size is unchanged.
- **Admin:** `/hcm games gen on fresh_dropper` (or `slots.fresh_dropper.enabled: true`) and it is built
  with the next set, or at once with `/hcm games gen reroll fresh_dropper confirm`. `/hcm games gen mix
  fresh_dropper EMHHH` changes the levels (1-5 of E, M and H) from the next build. Droppers can't be
  made by hand (`/hcm games course create <id> dropper` is refused); keep one instead. A kept dropper
  can be renamed, re-tiered, enabled, tested and featured, but not re-shaped.

| Key | Default | Meaning |
|---|---|---|
| `fresh.slots.fresh_dropper_easy` | `{enabled: true, mix: EEE, origin: [7488, 160, 6768]}` | Easy Dropper |
| `fresh.slots.fresh_dropper` | `{enabled: true, mix: EEMMH, origin: [7488, 160, 7360]}` | The Dropper (at most 5 levels) |
| `fresh.rewards.clear_weekly.fresh_dropper_easy` / `.fresh_dropper` | `2` / `3` | First finish in a weekly set |
| `fresh.rewards.clear_daily.fresh_dropper_easy` / `.fresh_dropper` | `1` / `2` | First finish in a daily set |
| `fresh.classics.slots.fresh_classic_dropper.origin` | `[7488, 160, 7952]` | Where Classic Dropper is built |
| `trials.warmup_seconds` | `180` | 0 turns off the practice drop (and the other warm-ups) |

**Verify in game** (Java and Bedrock):

1. With Fresh Courses on (both droppers ship on and are built with the rest of the set), `/hcm games
   gen status` shows `fresh_dropper_easy` and `fresh_dropper` live; on the first set the Fresh
   Courses screen shows "Easy Dropper - 3 levels · Course code EDROP-1" and "Dropper - 5 levels ·
   Course code DROP-1". `/hcm games gen reroll fresh_dropper confirm` (and the same for Easy) makes
   a new one within about 2 minutes, with the next code (DROP-2).
2. Open Easy Dropper's screen: the Start tile reads "Start - practice drop optional" (Bedrock too).
   Start it: the hotbar shows Practice drop (not timed) and Go straight to the timed run.
   Take the practice drop: nothing is timed, and the splash puts you back on the ledge for the 3-2-1.
3. Finish Easy Dropper on a tablet without sprinting, following the lights.
4. Finish the Dropper on Java cleanly: 3 stars (under the gold time) and "No bonks - perfect drop!".
5. Land on a coloured floor on purpose: "Bonk!", back on that level's ledge, the clock still running,
   and the result says "Bonks: 1".
6. Stand on the light-blue floor beside a Medium pool: bonk. Land on the rim of a Hard pool with half
   of you over the water: bonk too, not a splash.
7. `/effect give @s slow_falling` mid-run: "This run won't count".
8. Stand in a pool during `/hcm games gen preview fresh_dropper`: you are moved out. After the build
   there is no flowing water anywhere near the area.
9. `/hcm games gen reroll fresh_dropper confirm` while someone is mid-run: their run finishes and
   counts on the old board.
10. Set `games.trials.warmup_seconds: 0` and `/hcm reload`: the practice drop is no longer offered.
<!-- ---- end dropper ---- -->

#### Bring back or keep a course

Every set that goes up is archived with its whole layout, its **course code** (`HARD-40`: the
course's code word and how many sets it has had, never reused), its dates, seed and board.
`games.fresh.archive.keep` (0 = forever) removes old ones after that many days; kept and recalled
ones never go. A course's layout is a few KB in the database, a Mountain Run about 50 KB, so a year
of weekly sets for every course is about 6 MB with Ice Boat on. Players see the code on every Fresh
course's tile, on its screen and in the finish line, so they can ask for a favourite back.

| Command | What |
|---|---|
| `/hcm games gen history <course\|all> [page]` | The archive, 8 a page, newest first: code, dates, short seed, record, plays, and whether it is kept or back now |
| `/hcm games gen history <code>` | One set, with its top 5 |
| `/hcm games gen recall <code> [days\|forever] [confirm]` | Bring it back into its Classics slot (Classic Parkour, Classic Sky Rings, Classic Golf or Classic Dropper) for `games.fresh.classics.days` (7) or as asked. `confirm` only when someone is playing that Classics slot |
| `/hcm games gen recall <classic\|parkour\|rings\|golf\|dropper> <course> <last\|number\|date 2026-10-05\|seed:<hex>> [days\|forever]` | The same by course and set; `seed:` makes it again from its seed with today's generator (marked "(re-made)") |
| `/hcm games gen unrecall <classic> [confirm]` | Close a Classics slot |
| `/hcm games gen keep <code> <new-id> [name…] [--fresh-board] confirm` | Keep it for good as a normal course (`/hcm play <new-id>`) in the next free plot of the keep area, with its records copied (not with `--fresh-board`) |
| `/hcm games gen keep <course> [current\|last\|number\|date d\|seed:<hex>] <new-id> …` | The same by course and set |
| `/hcm games gen plots`, `clear-plot <n> confirm`, `claim plot <n> [confirm]`, `tp plot <n>` | The keep area's plots |

- A course up now can't be recalled (by `current`, its code, its number or its date): recall an older one.
- A set is kept once: a second keep of it is refused and names the course it was kept as (clear
  that plot to keep it again). `keep <course> last` without an id is refused ("Give the new course
  an id too").
- The keep area is hand-built territory that nothing guards, so every keep checks its plot is empty
  first, however often it was cleared before; blocks there refuse it, and `claim plot <n> confirm`
  clears them. `claim plot <n>` (and every plot job) is refused while keeping is off (the keep area
  too near a Fresh Courses area, or within 32 blocks of the Falling Floors arena or the Clubhouse),
  when the plot overlaps a kept course's old plot (the keep area moved), or when a registered course
  stands in it or within 16 blocks: it says which, and changes nothing.
- Refused within `avoid_before_restart_minutes` of a restart: recall and keep. A keep, clear-plot or
  claim plot still waiting when that window begins isn't started (its admin is told to run it again
  after the restart); one cut off by the restart after it began building goes on after the restart.
- **A recalled course plays on its original set's board**, so its old records are the ones to beat,
  and its first-finish token is the original set's: whoever had it back then isn't paid again, a new
  player is, once. Its stars are its own, so they count toward this week's Star Chart. A kept course
  is a normal course: the usual boards and rewards, edited with `/hcm games course|golf`.
- Example: kids loved HARD-40? `/hcm games gen recall HARD-40` puts it in Classic Parkour for a
  week; `/hcm games gen keep HARD-40 dragon_run "Dragon Run" confirm` keeps it for good as
  `/hcm play dragon_run`.

| Key | Default | Meaning |
|---|---|---|
| `fresh.archive.keep` | `0` | Days an old set stays in the archive after it was replaced; 0 = forever (kept and recalled sets never go) |
| `fresh.feed_history` | `26` | The website's `freshHistory`: at most this many past sets per course |
| `fresh.classics.days` | `7` | How long a recall lasts unless it says otherwise |
| `fresh.classics.slots.<id>.origin` | see config.yml | Where each Classics slot is built (32 blocks from every other area; the shipped spots are 576 apart, out of sight). A bare `[x, y, z]` works too |
| `fresh.classics.slots.<id>.half_gap` | not set (576) | Optional, as for a course: blocks between its two halves |
| `fresh.keep.area` | `[1760, 128, 7296]` | Where kept courses go: plots 144 x 176 x 336, six to a row, rows along z; 16 blocks from every Fresh Courses area, or keeping is off. Each plot must be inside the world border (24 plots reach z 10367) |
| `fresh.keep.plot_gap` | not set (576) | Optional: blocks between neighbouring plots (0-4096, a multiple of 16). 576 keeps kept courses out of sight of each other; a server that kept courses in 0.35 has `0` written (its plots touch) |
| `fresh.keep.max_plots` | `24` | How many plots |

**Verify in game** (on Java and on Bedrock, before switching it on for the family)

1. `games.fresh.enabled: true`, `/hcm reload`. Within 3 minutes `/hcm games gen status` shows
   "weekly" and next Monday 4:00 AM, and every course live for this week; `/hcm games status` says
   "8 courses up for ..." (the six plus the two droppers). On Tuesday the courses are the same.
2. Set `games.fresh.cadence: daily`, `/hcm reload`: status says "daily", the current courses stay
   and it says they stay until the next 4:00 AM; after 4:00 AM they are new, and new every day.
3. Set `games.fresh.cadence: 3`, `/hcm reload`: status shows the next change date on the fixed
   3-day grid, and the courses change on that day.
4. Finish Easy Parkour on a tablet without sprinting, and every other parkour tier on Java (Hard's
   tightest jump is the one to watch on Bedrock).
5. Fly Sky Rings with no rockets. Miss a ring on purpose, then fly on from the checkpoint.
6. Play Tiny Golf at par, then play one hole badly on purpose: it finishes by par + 1 without the
   ball being picked up.
7. `/hcm games gen reroll fresh_golf confirm` while someone plays the big golf course: their round
   finishes and counts on the old board; the new course is up a few seconds later.
8. Stop the server with `kill -9` during a `reroll`. After the boot the old or the new course opens
   intact (the console may say it put blocks back).
9. Stand in the spare half during `/hcm games gen preview <course>`: you are moved out ("A new
   course is being built here, so we moved you somewhere safe.").
10. Try to break a block of a course as an admin: "This area is built by Fresh Courses - use /hcm
    games gen".
11. In Easy Parkour, land on the outside corner of a blue checkpoint where the path turns and hop on
    at once: the checkpoint still counts (and the finish after it).
12. The big golf course's and Tiny Golf's ramp and island holes have walls two blocks above the
    approach (one above the raised green): check a ball can't leave and a player can step out from
    the green.
13. `/hcm play fresh_courses`: the title says "This week's courses", the header the week's dates,
    and each tile's name ends "Course code HARD-1" (and so on); finish Easy Parkour: the chat says
    "Course code EASY-1" and "First finish this week", never "today".
14. On one day, finish Easy Parkour for the first time this week (+2 of time trials' `daily_cap` of
    4), then Hard Parkour for the first time: 2 left and 4 asked, so no "first finish this week"
    token (the once-ever first clear, which no cap limits, still pays), and one line "You've reached
    today's token limit - finish it again another day this week for its tokens."; `/hcm tokens
    history <you>` shows no Hard Parkour "first finish this week" line. The next day a finish pays
    the week's 4. Then set `games.trials.daily_cap: 3` and `/hcm reload`: on the next set, Hard
    Parkour's first finish on a day with nothing else earned pays 3 (the whole cap) and says "You've
    won all the game tokens you can today", never "another day".
15. Finish a Fresh course twice on two days of the same week: the second pays no first-finish token.
16. `/hcm games gen history fresh_parkour_hard` → the week's course is HARD-1 "(up now)" with its seed.
17. `/hcm games gen recall HARD-1` (the next week) → "Bringing back HARD-1…"; within a minute
    `/hcm games gen status` shows `fresh_classic_parkour holds HARD-1 …`; the Fresh Courses screen's
    Classic Parkour tile is gold, "Classic: Hard Parkour (week of …) · Course code HARD-1", with
    "Back until …"; `/hcm play fresh_classic_parkour` plays it, and its board shows last week's times.
18. Finish it as someone who cleared it last week → no first-finish token; a new player → the token.
19. `/hcm games gen unrecall parkour` → closed; its tile reads "Classic Parkour - empty" with the tip.
20. `/hcm games gen keep HARD-1 dragon_run "Dragon Run"` → what it would do; add `confirm` → "Kept!";
    `/hcm play dragon_run`; `/hcm games course dragon_run info` shows a normal course.
21. Picking a good course: `/hcm games gen preview fresh_parkour next` → "A preview of Parkour for
    Mon 5 Oct-Sun 11 Oct (medium, seed …)", then "… is ready in half B". `/hcm games gen test
    fresh_parkour` → you are at the preview's start with the course kit; finish: "Test run - nothing
    was recorded", and "Play again" takes you round the preview again. `/hcm games gen choose
    fresh_parkour` → "Parkour's course for Mon 5 Oct-Sun 11 Oct is this preview"; status shows "next
    set: chosen seed …". After Monday's change `/hcm games gen status fresh_parkour` shows the chosen
    seed live and its last build with (almost) no ops; the week after, a new seed of its own.
22. As an admin, open Parkour's screen: bottom left, "Admin tools - new course, preview, try it,
    pick one". Its "Admin: Make a new course now (regenerate)" asks "Sure?" first; as a player, the
    item isn't there.

### Falling Floors

<!-- ---- Falling Floors (EVENTS-DROPPER-SPEC §B.3, WP-F) ---- -->
TNT Run without any TNT: three glass floors hang in the sky above the Games world, and every block
you step on turns red and falls away half a second later. **Nothing explodes**: a block turns red,
then it's gone. The last one standing wins; alone it's "how long can you last?". It ships switched
off (`games.falling_floors.enabled: false`).

- **Joining is one tap.** `/hcm play falling_floors` (or `/hcm play tnt_run`), or its tile on the
  **Together** tab ("Falling Floors - 2 playing · join!"), takes you straight into the **gallery**, a
  railed walkway round the arena's edge. As in every world game your things are kept safe and come
  back when you leave. The gallery is the lobby, the stand and where you go when you're out; it is
  glass, walk and rails (no mob can spawn on glass), and its rails are 2 high on both sides, so
  nobody can jump in or fall out. The kit's **Leave game** (click twice) or `/hcm leave` is the way
  home.
- **A round.** In the gallery the kit has **Ready**, **Play solo** (only while you're alone, with
  `solo` on) and **Leave game**. A round starts after a 10-second bar once `min_players` (2) press
  Ready, or by itself 20 seconds after a second player arrives. Everyone in the gallery plays, up to
  `max_players`. Players go to spread-out spots on the top floor two a tick, wait 3-2-1 (held in
  place), then the floors start falling; anyone the server couldn't move to a spot watches that
  round from the gallery instead. Nobody can push anybody during a round: the round's players are on
  a main-scoreboard team, `hcm_nopush`, that never collides. A player on another plugin's
  main-scoreboard team (nametag colours, say) comes off it for the round and goes back on it after.
- **The floors.** Three floors 8 blocks apart: yellow on top, pink, light blue at the bottom. A block
  you stand on turns red at once and is gone `fade_ticks` (10 ticks) later, so standing still or
  jumping in place doesn't help: keep moving. Fall below the bottom floor and you're out, back in the
  gallery with your time ("You lasted 0:42 - 3rd of 6!"). After `round_seconds` (180) the edges fall
  in, one ring every 2 seconds, so every round ends. Players out on the same tick share their place.
- **A new arena every week**, the same for everyone: each floor is a disc, a rounded square, a ring
  with an island, a plus or a diamond, about 450 blocks, with no walls (a wall top would be a safe
  spot).
- **Between rounds** the arena puts itself back: every block of the box is checked against the
  week's plan and fixed (about 1,400 blocks at `reset_blocks_per_tick` 400, at most 3 ms a tick,
  paused above 40 MSPT), and the lobby opens on whole floors a couple of seconds after the results.
  Anyone in the arena who isn't in the gallery is moved out of the way first.
- **Scores and tokens.** Solo times go on this week's solo board (`ffsolo`, longest first) and
  multiplayer wins on this week's wins board (`ffwins`, a count). Tokens are the normal skill rewards,
  under `daily_cap` (3): **1** for your first full round of the day (a round played out with others,
  or 20 seconds solo), the solo milestones of 30, 60 and 120 seconds (**1**, **2**, **3**, once ever),
  and today's pick. **A win pays nothing extra**, so there's nothing to gain by taking turns to lose.
  Leaving a round earns nothing. Lasting a whole minute counts toward the "Last a whole minute on
  Falling Floors" achievement (15).
- **The restart hold:** in the minutes before a scheduled restart no new round starts (and nobody
  new comes in); a round already going finishes. A countdown or solo round also doesn't start when
  it might still be going at the next restart (at its longest: the 10-second countdown,
  `round_seconds`, then about 40 seconds more while the edges fall in), and `/hcm games check` warns
  when `round_seconds` is longer than `games.restart_hold_minutes`.
- **Safety.** The arena is one box, 48 x 40 x 48 at `origin` (shipped x 6688-6735, y 176-215, z
  8544-8591, 576 blocks from everything else). Before anything is written it must be 32 blocks from
  every Fresh Courses area
  (switched on or not), the kept courses and the Clubhouse, 16 from every hand-built course and from
  the world's spawn and `games.fresh.safe_spot`, and inside the world's heights and border. The
  first time, the box must be empty: anything in it closes the game, touching nothing, until `/hcm
  games floors claim confirm`. After that nobody, admins included, can change a block in the box
  while the game is on ("This is the Falling Floors arena - it puts itself back. Use /hcm games
  floors."), and during a round the only blocks that change are floor blocks turning red, then air
  (at most 128 a tick). After a crash, the next start puts every floor back before anyone comes in,
  and everyone's things come back as from any world game. A reset that can't put the floors back
  three times closes the game and names where; `/hcm games floors reset` opens it again.

| Key | Default | Meaning |
|---|---|---|
| `falling_floors.enabled` | `false` | The game's switch |
| `falling_floors.origin` | `[6688, 176, 8544]` | The box's lowest corner; x and z are rounded down to the 16-block grid |
| `falling_floors.fade_ticks` | `10` | How long a stepped-on block stays red, 6-20 ticks |
| `falling_floors.min_players` | `2` | Ready players that start the countdown |
| `falling_floors.max_players` | `12` | Most players in the arena, 2-16 |
| `falling_floors.solo` | `true` | Whether a lone player may play a solo round |
| `falling_floors.round_seconds` | `180` | Then the edges fall in, 30-900 |
| `falling_floors.reset_blocks_per_tick` | `400` | How fast the reset between rounds writes |
| `falling_floors.daily_reward` | `1` | Tokens for the first full round of the day |
| `falling_floors.milestones` | `[30, 60, 120]` | Solo seconds for the three milestones |
| `falling_floors.milestone_rewards` | `[1, 2, 3]` | Tokens for each milestone, once ever |
| `falling_floors.daily_cap` | `3` | Most tokens Falling Floors pays a player a day |

**Verify in game** (on Java and on Bedrock, before switching it on for the family)

1. `games.falling_floors.enabled: true`, `/hcm reload`. Within a few seconds `/hcm games floors
   status` shows "lobby" and "resets: 1 done", and `/hcm games check` says "Falling Floors: box fits
   (...), claimed, floors ready".
2. On a tablet, `/hcm play tnt_run`: you're in the gallery holding Ready, Play solo and Leave game.
3. Play solo and stand still after Go: the block under you turns red, then it's gone. Fall through the
   three floors: you're back in the gallery with "You lasted 0:..!".
4. Three players: two press Ready, the bar counts 10, everyone is on the top floor, 3-2-1, Go. The
   last one standing wins: the results go to the gallery, the win is on this week's wins board, and
   the winner's tokens are the same as everyone else's.
5. The next round starts on whole floors a couple of seconds after the results.
6. Stop the server mid-round: after the restart `/hcm games floors status` shows a reset first, the
   floors are whole before anyone can come in, and everyone's things are back.
7. As an admin, try to break a floor or gallery block: refused, naming `/hcm games floors`.
   `/hcm games floors tp` (refused until the floors are built and checked) takes you to the gallery
   to watch.
<!-- ---- end Falling Floors ---- -->

### The Clubhouse

<!-- ---- The Clubhouse (CLUBHOUSE-SPEC, WP-CH) ---- -->
One room in the Games world where racers wait before a race and hang out after it: "a waiting room,
and the same room after the race so folks can hang out and joke around and talk about what
happened." It is part of the world games, not a new game: you are in it inside your normal world-game
session, so your things are kept safe and come back when you leave. It ships switched on
(`games.clubhouse.enabled: true`), builds itself, and **while it is off, not built or not checked,
every race and round works exactly as before**.

- **The room.** A glass-floored hall, 32 x 16 x 32, with windows, a lit roof, lanterns, benches, two
  tables, a three-step podium (1st in the middle and highest) and a results board. Sixteen spread-out
  arrival spots, so nobody lands on top of anybody. The walls are high and the roof is closed: there
  is no way out on foot, and nothing a mob can spawn on.
- **Visit any time:** `/hcm play clubhouse`. The kit is **Leave game** (click twice), **Results**
  (the last race's results) and, while in a party, **Party** (the party screen). No damage, no pushing,
  and nobody can change a block of the room. Chat is normal chat.
- **Before a party race** every member of the lobby can tap **Go to the Clubhouse** on the party
  screen and wait there. When the host presses Start, everyone waiting is taken straight to the grid;
  members who didn't go are seated exactly as before. It's optional.
- **Before Race Night** the join message and the Race Night screen offer **Wait in the Clubhouse**;
  racers there are taken to the grid at seating time. Nothing else about the night changes.
- **After a party race** a racer who finishes (or doesn't) comes back to the Clubhouse instead of
  going home ("Back in the Clubhouse! Look at the board for the results."). When the race is over the
  board shows the order, times and gaps, and the host's party screen has **Race again**, which takes
  everyone still in the Clubhouse back to the grid. While a party is racing, its screen hides **Go to
  the Clubhouse** and **Take a rider** (like Invite, Ready and the Cup); **Watch** stays for anyone not
  in the race.
- **After Race Night** everyone goes to the Clubhouse. The night's top three stand on the podium,
  "Photo time!" shows for 10 seconds, a firework (no damage) goes off over 1st, and the board shows
  the night's standings. Ties on points follow the night's own ranking.
- **After golf together** the group comes to the Clubhouse and the board shows the group's order.
  **Play again together** works from there: the Results item's card offers it, and the host's Start
  on the golf party screen takes everyone waiting in the Clubhouse straight to hole 1.
- **Solo runs** go home as always.
- **The board** shows, while a party race, Race Night or golf group is going, the live positions
  (place, name, lap or checkpoint, gap; golf: the group's card), at most once a second; after it ends,
  the final result. It is one floating text, cleared and made again on every start.
- **Watchers.** Anyone can come and watch without racing: **Watch** on the party screen and the Race
  Night screen, or `/hcm play clubhouse`. A watcher is never put in a race, never counted and never
  paid, and is on no racer list.
- **Watch live.** **Watch live** in the Clubhouse kit (while a race or golf group is going), or
  `/hcm play watch [<player>]`, takes you to the course being raced, in **spectator mode**: fly round
  it, look into the Dropper's shafts, follow a racer by clicking them. You can't leave the course's
  area (a move out of it just stops at the edge, and the area never reaches below the world's floor)
  and can only follow players in that race or group. Other players
  don't see you at all; watchers see each other. The action bar shows the race's positions
  ("Watching live" before there are any), then "| /hcm play clubhouse to go back"; `/hcm play
  clubhouse` brings you back in adventure mode with the kit, and so does the race's end, in time for
  the results and the photo. Every way out (Leave game, a quit, the restart hold, the games off, a
  crash) puts back the game mode you came with; a watcher who leaves or quits is first brought down
  to the Clubhouse's floor, never left in mid-air where they flew.
- **Cheer.** `/hcm play cheer` sends the racers "<name> cheers for you!" on the action bar, once
  every 10 seconds. A racer who'd rather not: `/hcm play cheers off` (kept; `on` turns them back on).
- **Ride along.** A boat driver can take **one** passenger in the back seat, on a solo boat run, a
  party race or Race Night: **Take a rider (back seat)** on a boat course's screen, on the party screen
  of a boat course, and on the Race Night screen once joined, or `/hcm play rider <player>`. It's an
  invite (key `rider`: [Accept] on Java, `/hcm play accept` on Bedrock; `/hcm play invites off` covers
  it). The rider hops in behind the driver at every start, grid and re-grid, holds the item "Riding
  with Dad - hold on tight!", can't get out mid-run, and is never timed, counted, paid or on a board.
  Their **Leave game** ends only their ride; when the driver finishes, stops, leaves or disconnects,
  the rider goes with them (to the Clubhouse after a party race or Race Night, home after a solo run),
  their things back. A ride that waits in the Clubhouse goes on at the driver's next race from there
  (Race Night on a track with no viewing stand, a party's Race again), and ends when either leaves
  the Clubhouse or after 5 minutes with no race. A rider can't be pushed by other players. Off the
  boat (the driver on the stand) the rider is held to the stand by the racers' own rule and radius
  (`stand_radius`), so a rider is never on the racing line (being unpushable doesn't stop a boat a
  player is driving), and elsewhere is kept within 4 blocks of the driver. Nobody racing, watching, in
  a party or in another game can ride, nor anyone the play gate would refuse (no `hcm.games.play`, or
  not in a world games are played in: the picker leaves them out, `/hcm play rider` refuses them, and
  the gate is asked again on Accept and when they get in), and no new rides start in the restart hold. A passenger doesn't change a boat's speed, so the driver's run
  counts as normal; with `games.trials.rider_runs_count: false` a run with a rider (at any point of it)
  is just for fun (no board, record, rewards or Cup time, told before the invite and in the finish
  line; in a party race the driver's place still stands) and Race Night takes no riders. Ride along is
  part of the Clubhouse: while it isn't open (off, or not built and checked) there's no Take a rider
  anywhere.
- **Time limits.** Anyone in the Clubhouse for `max_minutes` (30) with no race or party going is sent
  home, with a warning a minute before. In the restart hold nobody new comes in, except arriving from
  a race already going: a party race, golf group or Race Night that ends then still brings its players
  here. Everyone there is sent home a minute after the hold starts (or after they arrive), with a
  warning, and always at least 5 seconds before the restart's minute (sooner than the minute with
  `restart_hold_minutes: 1`), so nobody is in it across a restart. From 66 seconds before the restart
  to the end of its minute, too late for that minute, it takes nobody at all: a party race or golf
  group that ends then sends its players home with their things, reading "The Clubhouse is closed for
  the restart, so you're going home.", and a Race Night sends its racers home with the night's own line
  (its results skip the board). With a one-minute hold that is the whole hold. After a crash, their
  things come back at the next join as from any world game.
- **One place at a time.** A solo run, another game or golf from the Clubhouse is refused, as from
  any world game: "Finish your game first (/hcm leave)". Going from the Clubhouse to a race hands your
  session over; nothing is saved or given back twice.
- **Nothing here pays or counts.** The Clubhouse moves no tokens and counts toward no board, quest,
  achievement or Cup; a race from it is the same race as always.
- **Safety.** The generated room is one box, 32 x 16 x 32 at `origin` (shipped x 6080-6111, y
  160-175, z 8544-8575, 576 blocks from everything else), checked like the Falling Floors arena: 32
  blocks from every Fresh Courses
  area, the kept courses and the arena, 16 from hand-built courses, spawn and `games.fresh.safe_spot`,
  inside the world's heights and border. The first time, the box must be empty: anything in it closes
  the Clubhouse, touching nothing, until `/hcm games clubhouse rebuild confirm`. It is built with the
  Fresh Courses writer (3 ms a tick, paused when the server is busy), checked block by block, and
  nobody comes in until the check passes; a failed check closes it, and every flow goes back to
  today's. Nobody, admins included, can change a block of the generated room while it's on.
- **Your own room.** Build one by hand, stand where visitors should arrive and run `/hcm games
  clubhouse here` (in a Games world: another world is refused, and `/hcm games check` warns about a room
  stored in one); set the podium with `/hcm games clubhouse podium <1|2|3>` and the board with `/hcm
  games clubhouse board`. Nothing is built or guarded then. `/hcm games clubhouse generated` goes back.
- **Your things.** Nothing in the Clubhouse ever empties your inventory: a kit change takes only the
  kit, and anything that arrives while you're in it (an auction win, a Mini) comes home with you.

| Key | Default | Meaning |
|---|---|---|
| `clubhouse.enabled` | `true` | The Clubhouse's switch (and Watch, Watch live, cheers and Take a rider). Nothing happens while `games.enabled` is false |
| `clubhouse.origin` | `[6080, 160, 8544]` | The box's lowest corner; x and z are multiples of 16 |
| `clubhouse.max_minutes` | `30` | Minutes with no race or party going before a visitor is sent home |
| `clubhouse.party_after` | `true` | Party racers come back here after the race |
| `clubhouse.race_night_after` | `true` | Everyone comes here at the end of Race Night, the top three on the podium; on a track with no viewing stand, racers (and their riders) also wait here between races |
| `clubhouse.golf_after` | `true` | A golf-together group comes here when its round ends |
| `trials.rider_runs_count` | `true` | `false`: a run with a rider is just for fun, and Race Night takes no riders |

A bad value logs a WARN and uses its shipped value. `/hcm games check` has Clubhouse rows: on or off,
the box and its problems, the claim, built and checked, and an owner-built room's spots.

**Verify in game** (on Java and on Bedrock)

1. `/hcm reload`. Within a few seconds `/hcm games clubhouse status` shows "ready, built and checked"
   and `/hcm games check` says "The Clubhouse: box fits (...), claimed, built and checked".
2. `/hcm play clubhouse`: you're in the room holding Results and Leave game. Walk into a wall and jump
   on a table: no way out. Leave game: home, your things back.
3. Two players: one opens a boat course's **Race with friends**, both tap **Go to the Clubhouse**, the
   host presses Start: both on the grid. Finish: both back in the Clubhouse, the board shows the
   order and gaps. The host's **Race again** puts both on the grid again.
4. A third player taps **Watch** on the party screen, then **Watch live**: they fly round the course
   in spectator mode, the racers can't see them, they can't leave the course's area, and the race's
   end brings them back to the Clubhouse in adventure mode. `/hcm play cheer` shows on the racers'
   action bar.
5. On a boat course's screen tap **Take a rider (back seat)** and choose a friend; they accept (on
   Bedrock: `/hcm play accept`). Start: they're in the back seat. Fall off at a checkpoint: they come
   back with you. Their Leave game: you carry on. Your finish: they go home with their things.
6. Start a Race Night with `/hcm games event start`, wait in the Clubhouse, race it out: everyone ends
   in the Clubhouse, the top three on the podium with "Photo time!".
7. `/hcm games clubhouse off`: everyone in it goes home, and a party race ends at home as before.
   `/hcm games clubhouse generated` opens it again.
8. **Owner live checks:** a **Bedrock** player can Watch live (Geyser supports spectator mode) and
   comes back in their own mode; with **Multiverse**, the Games world's game mode isn't forced back
   while someone is watching (they stay in spectator mode until they come back).
<!-- ---- end the Clubhouse ---- -->

### Commands

Admin actions on the Games are logged with who did them, mini golf course edits included. A
bug in a course or golf command answers "That didn't work - see the console." and never
switches the game off. `[confirm]` on a course command is needed when a layout edit would
clear times or high scores, and when a layout edit or `disable` would call off a Weekly Cup that has
entrants. A player in a world game can use only `/hcm play`, `/hcm leave`,
`/hcm games` and `/hcm help`; anything else says "Finish or leave your game first — /hcm leave".

| Command | Who | What |
|---|---|---|
| `/hcm play` | `hcm.games.play` | The Games screen |
| `/hcm play <game\|course>` | `hcm.games.play` | Open a game (rules and odds first). A time trial first asks "Warm up (3:00) - then the timed run" or "Go straight to the timed run" (it starts straight away only with `games.trials.warmup_seconds: 0`, or on a Dropper); a golf course opens its course screen |
| `/hcm play <game\|course> <player>` | `hcm.games.admin` or the console | The same for someone else: NPCs, command blocks, a hub |
| `/hcm play break` | `hcm.games.play` | The Take a break screen |
| `/hcm play accept\|deny` | `hcm.games.play` | Answer your latest invite |
| `/hcm play invites [on\|off]` | `hcm.games.play` | Your invite settings: Connect Four, Tic-Tac-Toe, party races, Ride along and golf together (an older `off` covers any added since; Connect Four's or Tic-Tac-Toe's own screen switch is just for that game). `off` also turns Coin Flip invites off; only the Take a break screen turns them on |
| `/hcm play news [on\|off]` | `hcm.games.play` | The one chat line that says new Fresh Courses are up ("New courses this week! ..."). On unless you turn it off |
| `/hcm leave` | `hcm.games.play` | Leave the world game you're in; your things come back. Also finishes a trip home that didn't complete |
| `/hcm games status` | `hcm.games.admin` | Every game, open or closed and why, with the odds of the open games of chance; players in world games, saved things waiting to go back, unfinished rounds, today's pick, and the next scheduled restart and when its hold starts |
| `/hcm games check` | `hcm.games.admin` or the console | Is the server set up for the games? One line per check, OK, WARN or FAIL with the fix, then "All good." or "N things to fix." It only looks, and works with the games on, off or failed to start (see "Turning it on, and the Games world") |
| `/hcm games feature <game\|course\|auto>` | `hcm.games.admin` | Pin today's pick (writes `games.featured`), or let the day pick again. `trials` or `golf` makes every course of that game the pick. Never a game of chance |
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
| `/hcm games course <id> fall <y\|off> [confirm]` | `hcm.games.admin` | Below this height a run goes back to its last checkpoint, on any kind of course; `off` goes back to the parkour default (none for elytra and boat). It must be under the course's lowest point and not under the world's floor |
| `/hcm games course <id> tier <tier>` | `hcm.games.admin` | Its tier (a first clear already paid isn't paid again) |
| `/hcm games course <id> name <words…>` | `hcm.games.admin` | Its player-facing name |
| `/hcm games course <id> minseconds <n\|default>` | `hcm.games.admin` | Its own shortest believable time, 0-3600 seconds, or back to `trials.min_seconds` |
| `/hcm games course <id> enable\|disable [confirm]` | `hcm.games.admin` | Open it (it needs a start, a finish and a world in `games.worlds`) or close it (runs already going finish as normal) |
| `/hcm games course <id> tp` | `hcm.games.admin` | To its start |
| `/hcm games course <id> test` | `hcm.games.admin` | Run it, open or not: nothing is recorded, and the finish says whether it would have counted |
| `/hcm games course <id> feature [off]` | `hcm.games.admin` | Pin it as the course of the week, or unpin it |
| `/hcm games course <id> delete confirm` | `hcm.games.admin` | Delete it and all its times |
| `/hcm games golf list` | `hcm.games.admin` | Every golf course: open, closed or not ready |
| `/hcm games golf create <id> [name…]` | `hcm.games.admin` | A new, closed course in the Games world you stand in (named from its id unless given) |
| `/hcm games golf <id> [info\|list]` | `hcm.games.admin` | Its holes (par, tee, cup, bounds), what's missing, and how many are playing it now |
| `/hcm games golf <id> tp [hole]` | `hcm.games.admin` | To a tee (hole 1 unless given) |
| `/hcm games golf <id> hole add <par> [confirm]` | `hcm.games.admin` | A new last hole, par 2-6, its tee where you stand, facing the way you face (at most 18 holes) |
| `/hcm games golf <id> hole <n> cup [confirm]` | `hcm.games.admin` | The block you look at, within 6 blocks, is the cup: the block the ball rests on. Refuses air, anything under 0.2 high (carpet) and hollow blocks (a cauldron) |
| `/hcm games golf <id> hole <n> tee [confirm]` | `hcm.games.admin` | Move the tee to where you stand |
| `/hcm games golf <id> hole <n> par <2-6>` | `hcm.games.admin` | Change par (not a layout edit) |
| `/hcm games golf <id> hole <n> bounds <1\|2> [confirm]` | `hcm.games.admin` | One corner of the hole's bounds, at your feet |
| `/hcm games golf <id> hole <n> remove [confirm]` | `hcm.games.admin` | Remove the hole; the holes after it move up |
| `/hcm games golf <id> name <name…>` | `hcm.games.admin` | Rename it |
| `/hcm games golf <id> enable\|disable` | `hcm.games.admin` | Open it (only when nothing is missing) or close it (anyone playing it, or on the way in, is sent home) |
| `/hcm games golf <id> delete confirm` | `hcm.games.admin` | Delete it and its high scores (anyone playing it is sent home) |
| `/hcm play race` | `hcm.games.play` | The Race Night screen: when, the track, the prizes, Join/Leave, Watch, the season, race news |
| `/hcm play golf <course>` | `hcm.games.play` | A golf course's screen, with **Play with friends** (golf together) |
| `/hcm play race <course>` | `hcm.games.play` | Race with friends: open a party race on a time-trial course (never a Dropper) |
| `/hcm play cup [on\|off]` | `hcm.games.play` | Your Weekly Cups this week (pool and your Cup time each) and when they are paid; `off` hides the Cup on your course screens, `on` shows it again |
| `/hcm play falling_floors` (or `tnt_run`) | `hcm.games.play` | Straight into the Falling Floors gallery |
| `/hcm games cup status [course]` | `hcm.games.admin` | The Weekly Cup: entries open or closed (and why), the entry and top-up, when it pays, and every course that runs one with its pool; or one course's Cup |
| `/hcm games cup on\|off\|default <course> [confirm]` | `hcm.games.admin` | A course's Cup switch (kept per course id; `default` forgets it). `off` on a Cup with entrants asks for `confirm`, then calls this week's off and refunds everyone first. A Fresh course can't be switched on while Fresh Courses change more often than weekly |
| `/hcm games cup settle <course> [confirm]` | `hcm.games.admin` | Without `confirm`, what paying the course's running Cup now would pay; with it, pay it out now by the Cup times so far. It then takes no more entries or times that week |
| `/hcm games cup void <course> [confirm]` | `hcm.games.admin` | Call this week's Cup on the course off: every entry back, with the reason |
| `/hcm games event status\|list [days]` | `hcm.games.admin` | Race Night now (state, times, racers, prize nights, the next restart, the tick time); the scheduled nights, each fits or skipped with why |
| `/hcm games event start [course] [races N] [laps N] [in M] [fun]` | `hcm.games.admin` | Open a Race Night (joining now or in M minutes; `fun` pays no tokens) |
| `/hcm games event go\|cancel [confirm]` | `hcm.games.admin` | Start now (needs `min_racers`); call it off (`confirm` once racers are at the track) |
| `/hcm games event skip <id\|next>\|unskip <id>\|pause\|resume` | `hcm.games.admin` | The schedule, without editing config |
| `/hcm games event results [id]` | `hcm.games.admin` | A night's results, prizes and who is still owed |
| `/hcm games event grid <course> show\|auto\|add\|remove <n>\|clear` | `hcm.games.admin` | A hand-built track's starting grid |
| `/hcm games event stand <course> set\|clear` | `hcm.games.admin` | A hand-built track's viewing stand |
| `/hcm games floors [status]` | `hcm.games.admin` | Falling Floors: this week's floors, the round, the floor writer, the resets, and why it is closed |
| `/hcm games floors reset` | `hcm.games.admin` | Put the floors back and check them now (a round going finishes first); opens a closed arena again |
| `/hcm games floors claim [confirm]` | `hcm.games.admin` | Whether its box is claimed; `confirm` claims it even with blocks in it (the next reset clears them) and opens it again |
| `/hcm games floors tp` | `hcm.games.admin` | Into the gallery to watch (a plain teleport: not a game, nothing is taken); refused until the floors are built and checked, when there may be nothing to stand on |
| `/hcm play clubhouse` | `hcm.games.play` | Visit the Clubhouse; while watching live, come back to it |
| `/hcm play watch [<player>]` | `hcm.games.play` | Watch live, from the Clubhouse: the race going on (or that player's race or golf group) in spectator mode. While watching, with no name: back to the Clubhouse. From outside, only where `/hcm play clubhouse` works (a world games are played in) |
| `/hcm play cheer` | `hcm.games.play` | Cheer the racers on (once every 10 seconds) |
| `/hcm play cheers [on\|off]` | `hcm.games.play` | Whether cheers reach you |
| `/hcm play rider <player>` | `hcm.games.play` | Take a friend in the back seat of your boat (an invite) |
| `/hcm games clubhouse [status]` | `hcm.games.admin` | The Clubhouse: generated or owner-built, built and checked, why it is closed, how many are in it |
| `/hcm games clubhouse tp` | `hcm.games.admin` | Into the Clubhouse to look (a plain teleport); refused until it is built and checked |
| `/hcm games clubhouse here\|podium <1\|2\|3>\|board` | `hcm.games.admin` | Use a room you built: its arrival spot (facing your way), podium places and board, where you stand |
| `/hcm games clubhouse generated` | `hcm.games.admin` | Back to the generated room (built and checked first) |
| `/hcm games clubhouse rebuild [confirm]` | `hcm.games.admin` | Build the generated room again and check it; claims the box even with blocks in it |
| `/hcm games clubhouse off` | `hcm.games.admin` | Close it (kept across restarts): everyone in it goes home, every race works as before |
| `/hcm arcade odds` | `hcm.arcade.use` | Players: one line per open game of chance. Admins: the per-stake detail, the Scratch Ticket and the crates |
| `/hcm guide games` | `hcm.guide.use` | The Games page of How It Works |

### Leaderboards on the hub (`@board`)

The hub's displays can show the games' best, like `@news` shows the market's headlines. Look at a
sign, a block or a wall and run `/hcm display sign|hologram|tv @board:<id>` (`hcm.admin`; tab
completion lists the ids):

- `<id>` is an arcade cabinet (`@board:snake`: the board it publishes, Classic for most; another of
  its boards by name, `@board:creeper_sweeper:hard`), a hand-built course or golf course (its
  all-time board), or a Fresh course (`@board:fresh_parkour_hard`: **its current set's board**, which
  moves on to the new set by itself) or a Classics slot (the course it holds, with its old records).
  `@board:falling_floors` shows this week's longest solo Falling Floors times.
  A game of chance has no leaderboard, and an id nothing has is refused with a message, as is a
  board the cabinet doesn't have (`@board:creeper_sweeper:hardd` names easy, normal and hard).
- A hologram or TV shows a title ("Hard Parkour - this week"), the top 5 as "1. Sam 0:42.1" (ties
  share a rank; golf in strokes, cabinets in their own unit) and "/hcm play <id>"; a sign, the title
  in whole words that fit its 15 characters ("Hard Parkour") and the top 3. An empty board reads
  "No times yet - be the first!" (a golf board, or a cabinet board that isn't timed: "No scores
  yet - be the first!").
- Names are shown: these are players on the server. `web.dashboard.arcade_show_names` is only the
  website's rule.
- They are drawn on the display timer (`displays.refresh_seconds`), and again within a second of a
  new score on their board (a burst of finishes is one redraw). `/hcm display remove` unbinds one.

<!-- ---- race_night (WP-R2) ---- -->
### Race Night

Boat races for everyone at once (EVENTS-DROPPER-SPEC §A). **Three short races on one track**, points
in every race, and small token prizes from the server. **Entry is free: nobody can lose tokens.** It
runs at set times (Fridays at 7:00 PM as shipped) or whenever an admin starts one. **Ships off.**

- **Turning it on.** Race Night needs Time Trials and a boat track. The default track is Fresh
  Courses' **Ice Boat** (`games.fresh.slots.fresh_boat`, which ships off): a new walled Mountain Run
  every week, a downhill sprint with the viewing stand built in (see [Ice Boat: the Mountain
  Run](#ice-boat-the-mountain-run), and its checklist before switching it on). Turn that on, then
  `games.race_night.enabled: true` and `/hcm reload`. `/hcm games check` says whether the schedule
  fits the restarts and whether the track can be raced.
- **On the Mountain Run** a race is one run from the top to the finish below the stand, so a night is
  "3 downhill races" (the Race Night screen, and `/hcm games status` while a night is on; `/hcm games
  event status` says "a downhill sprint"), with no laps anywhere: not in the grid title ("Race 1 of
  3" / "Ice Boat · you start 3rd") or the bar, and the feed's `laps` is 1. The warm-up is "Warm-up
  runs" (on the hub sign, the Race Night screen and tile, the watchers' bar and in chat). The
  heads-up and join-now lines end "This week: 5 drops down the mountain!" while `hype` is on, and a
  racer sees a "Final drop!" title where the run has a FINAL DROP! sign. A sprint is short, so for
  family nights try `races: 5` and `finish_window_seconds: 90` (shipped 3 and 60). Keep `laps: 0`: a
  sprint can't be given laps (`event start ... laps 2` there says "Ice Boat isn't a loop, so it
  can't have laps").
- **Joining.** Race Night's lines and bar never reach a player without `hcm.games.play` (a [Join]
  they couldn't use). 30 minutes before, one chat line (news on, not in a world game); 10 minutes before
  (`join_minutes`), joining opens: a chat line, a draining bossbar for everyone with news on who could
  join (in a world games are played in, or joined already), the
  Together tab glints and the hub's `@event` TV reads JOIN NOW!. Players join from the Race Night
  screen (`/hcm play race`, the Race Night tile on the **Together** tab, or an `[Arcade] race_night`
  sign): one tap on **Join**. Joining moves nobody; keep playing. Leaving the list before the racing
  is free. At most `max_racers` (8), fewer if the track's grid has fewer spots.
- **The track.** 2 minutes before the start it is reserved (new solo runs and party races on it are
  refused, and a party race still on it is called off), and 1
  minute before, solo runs still on it end. 15 seconds before, every joined racer who is free goes to
  the track in their own oak boat, two a tick (their things are kept safe, as in every world game),
  from whatever world they are in, as long as they still have `hcm.games.play`. Anyone busy is tried
  again every second and asked, at most every 5 seconds, to stand still or use Leave game ("Race
  Night is starting! Stand still, or use Leave game, to join."), until just before Go, and is then
  out of race 1 ("you'll be in the next one"). A racer whose `hcm.games.play` was taken away after
  joining is told "Games aren't open to you." once and is out of every race until it is back.
- **The warm-up** (owner decision D3). With `warmup_seconds` above 0 (180 as shipped), racers first
  get free warm-up laps (warm-up runs from the top on the Mountain Run), never timed; each can tap
  **Ready**. The grid waits for the window to run
  out, or for everyone who joined (and is online) to be at the track and ready; race 1 never starts
  before its advertised time. A restart due soon ends the warm-up at once.
- **A race.** Everyone on the grid, held still, "Race 1 of 3", 3-2-1-Go on one tick. The bossbar
  shows your place ("2nd of 5 · Lap 1/2" on a loop, "2nd of 5" on a one-way track like the Mountain
  Run; on a loop's last lap, in yellow, "LAST LAP! 2nd of 5"). At the
  line: "You came 2nd! 0:41.2", then onto the stand to watch. A race ends when everyone is in, 60 s
  after the first finisher, or after 4 minutes. Points `[10, 8, 6, 5, 4, 3, 2]` by place, 2 for a
  finisher beyond that list, **1 for anyone still racing at the end** ("Race over - you still get a
  point. Great racing!"), 0 for leaving or a voided run (flying, an effect, a changed game mode or
  speed). A 20 s break shows the standings; the next grid puts the **fewest points tonight in
  front** (race 1: the fewest season points).
- **Leaving.** Leave game (or `/hcm leave`) during the night is leaving for good: your points so far
  stand, your things come back. A disconnect scores 0 in that race; back online and free before the
  next grid, you are pulled back in.
- **Prizes** (EVENTS-RECONCILED 1). After the last race the night is ranked by points, then
  countback (more 1st places, then more 2nd places...); racers still level share the place. **1st 5,
  2nd 3, 3rd 2 tokens, and 1 to everyone else who finished a race.** 2nd needs 3 or more racers at
  race 1 and 3rd needs 4 or more, so nobody wins a podium prize for coming last (2 racers get 5 and
  1; 3 get 5, 3 and 1). A podium prize (and "Win a Race Night") needs at least one finished race
  that night and someone ranked below you: racers tied for last came last, and a night where nobody
  finished pays nothing. A racer who only warmed up (never in a race) gets no place. At most **5
  tokens a player a night**, and at most **3 prize nights a week**
  server-wide (`prize_events_per_week`, the week the weekly boards use, counted in the week race 1
  starts in, even for a night announced the evening before). A 4th night that week says
  "Just for fun tonight - points only" and pays nothing; so does an admin's `fun` night. Prizes are a
  new reward kind (`EVENT_PRIZE`) **outside the daily skill cap**, paid once per player per night
  (ledger source "Race Night"). A racer offline or somewhere tokens can't be earned (watching live,
  creative) is owed, and reads a line that says so (at once, or at their next join): it is paid at
  their next join and every minute while they're online, even after Race Night is switched off.
- **The season.** Every race's points also go on the month's season board (`rnseason:2026-10`,
  "Race Night · October" on the high-score screen); each night's result on its own board
  (`rnnight:<id>`, "Race Night · Fri 2 Oct"). `season: off` turns the season board off.
- **Hub displays.** Bind a TV (`/hcm display tv @event`) for Race Night's own board: `RACE NIGHT /
  Fri 7:00 PM / in 2h 14m / Ice Boat`, `JOIN NOW! / /hcm play race / 3 of 8 in / starts 7:00`, `RACE
  2 OF 3 / 1. Sam 18 / 2. Ava 16 / 3. Lee 10`, then `WINNER / Sam / 2. Ava / 3. Lee` for 30 minutes
  (`RACE NIGHT / No race set / Ask an admin!` when nothing is set). It redraws within a second of a
  change, never per tick. `@board:race_night` is the season table and `@board:race_night:last` the
  last night. (`/hcm display sign @event` and `/hcm display hologram @event` don't bind it: those
  two commands pass only `@board:` targets through, and open the commodity picker instead.)
- **Watch.** The Race Night screen's **Watch** gives a bossbar with the leader and the finishes in
  chat, from anywhere, until 30 s after the results (or a second tap).
- **Crash safety** (§A.9). Each race is stored in one transaction (its rows, the night's points, the
  season points, `races_done`). A stop, a reload that closes Race Night, or switching it (or Time
  Trials) off while racers are at the track calls the night off at once: the races done stand, and
  prizes are paid (or owed) if it held a prize slot. A crash does the same at the next boot. A night
  still in its join window resumes after a restart if its start is at least 2 minutes away. If its
  track isn't ready yet (the Games world still loading, or a Fresh track before its boot check), it
  waits (`/hcm games status` shows "resuming · ...") and is called off only once its start is 2
  minutes away. Prizes
  can never be paid twice (the payment's ref is the night's id). A night called off before any race
  was stored gives its prize night back to the week. An admin's `start ... in M` is saved at once, so
  a restart before its window keeps it. `/hcm reload` while racers are at the track says first that a
  reload switching Race Night or Time Trials off calls it off.
- **The restart hold.** A scheduled night runs only if its whole window, from joining to its worst
  case (`races × (max_race_minutes + break) + 2 min`, plus the warm-up: 18 minutes as shipped), ends 2
  minutes before a restart's hold; otherwise it is skipped, with the reason in `event list` and
  `/hcm games check`. On a Fresh course it also keeps 15 minutes from `games.fresh.rebuild_at`.
- **Boats bump.** Vanilla boats are solid to each other and that can't be switched off: race only on
  tracks with walls on both sides. Java and Bedrock boats can feel slightly different on ice.
- **Achievements:** "Race at Race Night" (10) and "Win a Race Night" (30), counters, so they unlock
  back home (config revision 18 adds them to a shipped list). Every racer who started a race of the
  night is counted when it ends, wherever they are then (offline, or watching the others live), and so
  is a night settled at the next boot after a crash.

| Key | Default | Meaning |
|---|---|---|
| `race_night.enabled` | `false` | Race Night's switch (it also needs Time Trials) |
| `race_night.schedule` | `["FRI 19:00"]` | `"<days> <HH:mm>"` in `clock.time_zone`: `FRI 19:00`, `SAT,SUN 15:00`, `DAILY 18:30`, `WEEKDAYS 17:00`, `WEEKENDS 10:30`. `[]` = admin-started only. A bad entry is dropped with one WARN |
| `race_night.course` | `auto` | `auto` takes turns among the boat tracks it can race on (by the night's id, never at random), or a course id |
| `race_night.races` | `3` | Races a night, 1-5 (a track without a stand holds 1) |
| `race_night.laps` | `0` | 0 = the course's own laps; 1-5 on a loop track |
| `race_night.announce_minutes` | `30` | The chat heads-up before the start (0 = none) |
| `race_night.join_minutes` | `10` | Joining opens this long before a scheduled start |
| `race_night.admin_join_minutes` | `5` | ... and before one an admin starts |
| `race_night.min_racers` / `max_racers` | `2` / `8` | Fewer at the start calls it off; the most (2-12, and the grid's spots) |
| `race_night.finish_window_seconds` | `60` | A race ends this long after its first finisher |
| `race_night.max_race_minutes` | `4` | ... or after this long |
| `race_night.break_seconds` | `20` | The break between races |
| `race_night.warmup_seconds` | `180` | Free warm-up laps before the grid ("Warm-up runs" on the Mountain Run; 0 = none); "Ready" skips |
| `race_night.points` | `[10, 8, 6, 5, 4, 3, 2]` | Points by place in each race |
| `race_night.finish_points` / `still_racing_points` | `2` / `1` | A finisher beyond the list; anyone still racing at the end |
| `race_night.prizes` | `[5, 3, 2]` | Tokens for the night's 1st, 2nd, 3rd (each 0-10; never more than 5 a player a night) |
| `race_night.finisher_prize` | `1` | Tokens for everyone else who finished a race (0-2) |
| `race_night.prize_events_per_week` | `3` | Nights a week that pay tokens, server-wide (0-7) |
| `race_night.season` | `month` | `month` (a monthly season board) or `off` |
| `race_night.stand_radius` | `4` | How far a racer may wander from the stand (race mode puts them back) |
| `race_night.hype` | `true` | On the Ice Boat Mountain Run, the heads-up and join-now chat lines end with its drops: "This week: 5 drops down the mountain!" ("Today: ..." on a daily set). Words only: it never changes a track |

**Admin** (`/hcm games event ...`, `hcm.games.admin`):

| Command | What it does |
|---|---|
| `status` | The night: state, times, racers with points, prize nights this week, the next restart, the night's tick time |
| `list [days]` | The scheduled nights (7 days), each "fits" or "skipped: why" |
| `start [course] [races N] [laps N] [in M] [fun]` | Open a join window now (or in M minutes); the start is `admin_join_minutes` later. `fun` = season points only. Refused while another night is on, near a restart, too close to a scheduled night, with Race Night or Time Trials closed, or on a track (or with a lap count) that can't be raced |
| `go` | Close the window and start in 15 s (needs `min_racers`) |
| `cancel [confirm]` | Call it off (`confirm` once racers are at the track; points so far count) |
| `skip <id\|next>` / `unskip <id>` | Skip one scheduled night |
| `pause` / `resume` | Stop or restart the schedule without editing config |
| `results [id]` | A night's results (the last one) |
| `grid <course> show\|auto\|add\|remove <n>\|clear` | A hand-built track's starting grid (add: where you stand, behind the start, 2.5 apart, at most 8). `auto` says how many the automatic grid seats. A layout change drops it with a WARN |
| `stand <course> set\|clear` | A hand-built track's viewing stand (set: where you stand, 10+ blocks from the racing line, solid below and 2 air above). Without one a night there is 1 race |

`/hcm games status` has a Race Night line ("waiting · next Fri 7:00 PM on Ice Boat (3 races) ·
fits before the 4:00 AM restart · prize nights 1/3 this week").

**Race mode (WP-R1).** The races are Time Trials runs in race mode (`TimeTrials.race`, `regrid`,
`park`, `endRace`, `reserve`), the same race mode party races use: one automatic grid (rows of two
behind the start, else single file) and, on Fresh Ice Boat from boat planner algo 2 on, the one
built-in stand (used only while it really stands; otherwise finishers go at the line to the
Clubhouse, when it is open in the track's world, `race_night_after` / `party_after` is on and it
isn't closing for a restart, or else home). A heat
never goes on the course's boards or records and never counts for the Weekly Cup (its start is a
grid spot), but each finished race counts once toward "finish a course" quests.
<!-- ---- end race_night ---- -->

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
6. **Check it: `/hcm games check`.** It only looks (nothing is changed, and it works with the games
   on, off or failed to start), and prints one line per check, each OK, WARN or FAIL with the fix:
   `games.enabled`; every `worlds.economy_enabled` world exists; every Games world is loaded, not
   an economy world, and set to adventure in Multiverse (when Multiverse can't say, it tells you to
   check `/mv info <world>`); Multiverse-Inventories gives the Games worlds a group of their own and
   keeps no per-game-mode profiles (read from its `groups.yml` and `config.yml`; when they can't be
   read, what to check by hand); `games.restart_times` reads, with the next restart and hold; Fresh
   Courses (on and how often, every area inside the world border and height and clear of hand-built
   courses and the other areas, each claimed, empty, or with foreign blocks and how many, the next
   change, each course live or why not, the keep area and the Classics); Falling Floors (its box fits
   and is claimed, or what is wrong and the fix); the Clubhouse (off, or its world loaded, its box
   allowed and claimed, built and checked; or an owner-built room's arrival, podium and board set);
   every hand-built course is ready and its world loaded; Race Night (off, or Time Trials open, the
   schedule reads, each coming night fits or why it is skipped, the track can be raced with its grid
   spots, and its viewing stand); what players can see (each Games world void or not, the view
   distance used, and every pair of the games' places close enough to see each other, as WARNs:
   see "What players can see"); the website feed (the dashboard, a feed token, and `/api/arcade`
   built in memory, never over the network); and the LuckPerms line that takes games of chance away
   from one player (`/lp user <player> permission set hcm.games.chance false`). It ends "All good."
   or "N things to fix." (every WARN and FAIL).

Players take nothing in and lose nothing. Entering saves everything (inventory, XP, health,
food, effects, game mode, where they stood) and hands them the game's kit.
Leaving puts it all back exactly, however they leave: the kit's Leave item (click twice),
`/hcm leave`, finishing, quitting, a restart or a teleport away. Anything that reached them
during the game (an auction delivery, say) is handed over once they're home. They can't be hurt,
get hungry, drop things or open other screens while they play, and the kit never leaves the game.
Other players can't reach them either: effects from anything but a plugin or `/effect`, all
knockback and fishing-rod pulls are cancelled.

**Nobody pushes anybody where players crowd.** `setCollidable(false)` stops mobs pushing a
player but not another player, so the places players share (Falling Floors' rounds, the Dropper's
shafts, the Clubhouse, and parkour and elytra party races; boats bump, as the owner chose) put their
players on one main-scoreboard team, **`hcm_nopush`**, whose collision rule is never. A player is on
at most one main-scoreboard team, so someone on another plugin's team (nametag colours, a tab-list
prefix) comes off it for the round or race and goes back on it as soon as that ends, if the team
still exists. That earlier team is remembered in memory only: stopping the games puts everyone back,
but after a crash the next start empties `hcm_nopush` and WARNs how many were still on it, and their
earlier teams can't be restored.

Things come back **exactly once**, even when something goes wrong. If what arrived during the
game doesn't all fit at home, the rest waits in their saved row: "Some of your things didn't fit.
Make room, then type /hcm leave to get the rest." If the database refuses a write partway, they
wait with their things, on the game's floor if they were playing ("Type /hcm leave in a moment to go
home"), and nothing is applied twice. Something delivered in the middle of a game (an auction win, a Mini) is never
written over by a game's items: it moves to a free slot and comes home with them. Nobody lands
home with a fall from a game: leaving, being sent home or disconnecting halfway down a drop ends
with no fall damage. Nobody is let go in mid-air either: a game that has to stop where the player is
(a trip home that failed, a write refused) first puts them on its start (the course's start platform,
or the Clubhouse's arrival spot), never back up on a ring or a watcher's view point, and in a Games
world a fall costs nothing until their things are home, with the games on or off. After a crash, a
Clubhouse watcher is never left in spectator mode, and never dropped from where they were flying:
they are brought down to the Games world's spawn first, and only there get their own mode back. A game refuses to start while they hold something on the cursor ("Put down what
you're holding first."). `/hcm leave` and `/hcm games saved` work even while the games are off,
and an admin's `restore` or `return` says what really happened.

### Quests and achievements from the games

The skill games count toward quests and achievements; **games of chance never do** (they never
report a finish, and only a catalog cabinet is taken as a cabinet finish).

- **Three quest types**, pushed by the games: `FINISH_CABINET` (a finished cabinet run, practice
  included; a run closed early or a friend game someone quit is not a finish), `FINISH_COURSE` (a
  counted time-trial run or a finished round of golf, hand-built or Fresh) and `EARN_STARS` (Fresh
  Courses stars, one step a star). They count where the games pay tokens (an economy world, a Games
  world or a play world, never in creative or spectator). A quest finished in the Games world, where
  no tokens are paid, is paid as soon as the player is back in an economy world (at once on the world
  change, or within 30 seconds). A game quest is dealt only while a game that can push it is open, so
  nobody draws "Play 3 arcade cabinets" with the games off.
- **The pool rows** (appended after the rows you had):

  | Pool | id | type | target | reward | Says |
  |---|---|---|---|---|---|
  | daily | `cabinet_daily` | `FINISH_CABINET` | 3 | 4 | Play 3 arcade cabinets |
  | daily | `course_daily` | `FINISH_COURSE` | 1 | 5 | Finish a course or a round of golf |
  | weekly | `cabinet_weekly` | `FINISH_CABINET` | 15 | 20 | Play 15 arcade cabinets |
  | weekly | `course_weekly` | `FINISH_COURSE` | 5 | 20 | Finish 5 courses or golf rounds |
  | weekly | `stars_weekly` | `EARN_STARS` | 6 | 20 | Earn 6 Fresh Courses stars |

- **The "Games" achievements**, all `COUNTER`s so one earned in the Games world unlocks once the
  player is home (on the world change, at join, or at the five-minute check):

  | id | counter | target | tokens | Says |
  |---|---|---|---|---|
  | `game_first_cabinet` | `cabinet_finishes` | 1 | 10 | Finish an arcade cabinet game |
  | `game_gold` | `cabinet_golds` (scored runs, not practice) | 1 | 20 | Earn a gold medal in a cabinet |
  | `game_all_cabinets` | `cabinets` (different cabinet games) | 8 | 30 | Finish every arcade cabinet game |
  | `game_first_course` | `course_finishes` (a golf round counts) | 1 | 15 | Finish a course |
  | `game_hole_in_one` | `holes_in_one` | 1 | 25 | Get a hole-in-one |
  | `game_under_par` | `golf_under_par` | 1 | 25 | Finish a golf course under par |
  | `game_fresh_all` | `fresh_sets` | 1 | 40 | Finish every Fresh Course in one set |
  | `game_star_chart` | `star_chart_tops` | 1 | 30 | Reach the top Star Chart goal in a week |
  | `game_record` | `course_records` | 1 | 30 | Set a course record |

- **Upgrading (config revision 17).** A pool or achievements list still exactly as shipped gains
  these rows at its end. One you have changed is yours and is left alone: the console WARNs once,
  naming it, followed by the lines to paste at its end. A row already there by id is never added
  twice.

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
  The website feed already leaves `jackpot` out; `first_crate` is still published. Nothing new is
  tied to a game of chance, and **you may remove the `jackpot` and `first_crate` rows** from
  `arcade.achievements` if you prefer no chance-linked achievements at all (unlocks already earned
  stay in the database; the rows just stop showing and paying).

### Data

Schema **v34** adds seven tables: `game_rounds` (every round of a game of chance, and each
cabinet's scored daily try), `game_breaks` (Take a break), `game_scores`, `game_rewards`,
`game_saved_state` (a player's things during a world game), `game_courses` and `game_prefs`
(invites, the golf ball, lines waiting for the next join). No `config_revision` bump: every
`games` key is new and back-filled with its comments. A bare `games: false` (or
`games.<id>: false`, or a Fresh course's `games.fresh.slots.<id>: false`) is first rewritten as its
`enabled` key, so a game or course you switched off stays off (and one you switched on stays on);
the backfill never replaces a single value you wrote where a section belongs, it warns instead. Every token a game moves is in the ledger under that game's own source, so
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
   `/hcm arcade odds` says "Games of chance aren't open to you." (as a non-op; with `hcm.admin` you
   still get the full odds). Unset it after.
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
    number. `games.max_payout: 1`: a WARN for every stake that can no longer give back 85 to 95 of
    every 100 tokens (Ore Slots' 5, and the top stakes of Twenty-One, the Wheel, Higher or Lower and
    Coin Flip too), and Ore Slots' 5-token stake is gone.

**Cabinets**

22. `/hcm play creeper_sweeper`: pick Normal; the first dig is safe; clearing says "✔ Cleared in
    m:ss.t!" and "★ New best - and the server record!" (just "★ New best!" when someone else holds
    the record). Today's board: the first try is scored, later ones are practice.
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
29. `/hcm play cliffs` in chat first opens a small screen: "Warm up (3:00) - then the timed run" or
    "Go straight to the timed run". Go straight takes you to the start with only Back to checkpoint
    and Leave game; a 3-2-1 holds you there, then "Go!". Its tile on the Courses tab opens the
    course screen, with Start, instead.
30. Each checkpoint shows "Checkpoint 1 of 2 - 0:04.1" with a ping. Fall well below the
    checkpoints: "Back to checkpoint 1", and the clock keeps going.
31. Finish: your time, "★ Your first finish on Cliffs!", "★ New course record!", "+5 tokens (Cliffs:
    first finish)" and the best-this-week reward (up to the daily cap). You're sent home with your
    things, and the result screen offers Play again. Finish faster: "★ New best! (was …)", and no
    tokens for the best itself.
32. `/effect give @s minecraft:speed` mid-run: "This run won't count - a potion effect…" (`/fly`
    says "- flying" instead). The finish says "That run didn't count. (a potion effect)", and the
    board doesn't change.
33. `/hcm games course cliffs test`: "test run: nothing is recorded"; the finish says "Test run -
    nothing was recorded. It would have counted."
34. `cliffs finish confirm` with times on the board: the layout number goes up and the times are
    cleared. A run going at the time finishes with "the course changed during your run". Your
    next finish reads "★ New best!", not "Your first finish", and the course screen's tokens item
    says the first finish is done.
35. An elytra course: a worn elytra and 3 rockets, back to 3 at each ring; landing outside a ring
    sends you back to the last one, even a landing within half a second of a Back to checkpoint.
    A boat course: getting out sends you back to the last checkpoint, and after `/hcm leave` the
    boat is gone.
36. With a second player, mid-run: their splash potion, wind charge and fishing rod do nothing to
    you (no effect, no push, no pull), and the run still counts.
37. `/speed walk 2` mid-run: "This run won't count - your walk speed changed" (`/speed walk 1`
    after). `/attribute @s minecraft:jump_strength base set 0.6` mid-run: "…your movement changed"
    (set it back to 0.42). Sprinting the whole way, or `/save-all flush` mid-run, doesn't stop a
    run counting.
38. `/hcm games course create auto parkour` is refused ("'auto' is a word /hcm games feature keeps
    for itself."). `cliffs fall 200` is refused (a fall height must be under the course's lowest
    point), and so is a y under the world's floor.
39. `/hcm games feature trials`: Cliffs' course screen says "★ Today's pick", and finishing it pays
    "+1 token (Cliffs: today's pick)" if no pick has paid you yet today. `auto` after.

**Mini golf**

40. As admin in the Games world: `/hcm games golf create meadow Meadow Links`; on the tee,
    `meadow hole add 3`; looking at the cup, `meadow hole 1 cup`; at two opposite corners,
    `meadow hole 1 bounds 1` and `bounds 2` ("It's ready. Open it with /hcm games golf meadow
    enable"); then `meadow enable`. The console has an INFO line for each edit ("Games: <you>
    created golf course meadow - …").
41. Looking at a carpet, `meadow hole 1 cup` says "The white_carpet is too thin to hold a ball."
    and "Look at the floor of the hole instead…"; a cauldron is refused as hollow. The cup
    doesn't change.
42. `/hcm play meadow` opens the course screen, never a teleport. Pick your ball lists your Minis
    (Bedrock: "Your ball: a white block"). Start: hole 1's tee, holding Tap to Drive, Go to my
    ball, Reset ball, Scorecard and Leave game.
43. Click a club within 4 blocks: the ball rolls the way you look. Further off: "Get within 4
    blocks of your ball…". Into water: "Splash! Back to your last spot, +1 stroke." A slow putt
    drops in, with the hole's title and "In the cup - …"; a hole-in-one says "Hole in one!", with
    a firework. On a sunken 1×1 hole, an off-centre putt that drops in counts too, and on the
    first stroke it's a hole-in-one.
44. Keep missing on a par 3: at 6 strokes the hole is "Picked up" and scores 6.
45. The ball is only a picture: you can't pick it up, push it or hit it, the Mini you picked is
    still in your collection, and after the round (or a restart mid-round) no ball is left in the
    world.
46. Finish: the total against par and "New best", then +5 for the first finish and +2 at par or
    better. A second round at par the same day pays no par reward. `meadow disable` while someone
    plays sends them home: "Meadow Links was closed by an admin." It does the same just after a
    player clicks Start, with no scorecard and no tokens.
47. `meadow enable` again. Standing on hole 1's tee, `meadow hole 1 tee` (add `confirm` if asked)
    while someone plays: they go home at once with their things, told "Meadow Links was changed
    by an admin, so this round can't count. Your things are back."
48. `/hcm games feature golf`, then finish Meadow Links on a day no pick has paid you yet:
    "+1 token (Mini Golf: Meadow Links is today's pick)". `auto` after.
49. Pick a Mini as your ball, then trade it away: the course screen shows the plain white ball.

**World sessions (any course)**

50. With a full inventory, an effect and some XP, `/hcm play cliffs`: you're in adventure mode
    with only the kit, and `/hcm games saved <you> show` says ACTIVE. The Leave item (click twice)
    brings everything back.
51. `/give` yourself diamonds mid-game: they're in your bag once you're home, once.
52. Disconnect and rejoin mid-game: you're home with everything and no kit anywhere. `/kill`: no
    death screen, you're home ("You're out of the game. Your things are back."). A teleport far
    away or to another world ends the game; a 2-block one doesn't. `/gamemode survival` doesn't
    stick.
53. `/stop` mid-game, then start and join: you're home, "Your things are back — the server
    restarted during your game."
54. In a game, `/hcm auction` says "Finish or leave your game first — /hcm leave"; the binder,
    chests, the ender chest and dropping do nothing. Setting `games.enabled: false` and
    `/hcm reload` mid-game sends you home.
55. Fill your inventory but one slot, start a course, `/give` yourself a diamond and an emerald,
    then leave: you're home with one, told "Some of your things didn't fit…", and
    `/hcm games saved <you> show` says RETURN. Free a slot, `/hcm leave`: the second arrives and
    the row goes DONE.
56. With `games.trials.warmup_seconds: 0` (otherwise the command opens the warm-up choice first),
    open your inventory holding an item on the cursor, then from the console
    `/hcm play cliffs <you>`: "Put down what you're holding first." and nothing drops.
57. With a RETURN row waiting, set `games.enabled: false`, `/hcm reload`, then `/hcm leave`: it
    finishes.
58. During a session, `/data get entity <you> BukkitValues` shows
    `homecraftmanagement:games_session_mark` (the mark that keeps a crash from applying the same
    things twice); once you are home with your things it is gone again. If Multiverse-Inventories
    swaps it per world group, tell us.

**The website feed**

59. With the games off, `/api/arcade` has only the `scratch_ticket` entry (plus the pot, prizes,
    packs and achievements), no `featured` and no `jackpot` achievement. Its `rtp` (77.6) matches
    `/hcm arcade odds`.
60. With the games on, each open game appears, its `rtpByStake` matching the admin odds; `cliffs`
    appears as `"kind":"parkour","tier":"easy"` with its `record`, and `meadow` as `"kind":"golf"`
    with `holes` and `par`. `web.dashboard.arcade_show_names: true` adds `holder` to records;
    `false` takes it away.

**The restart hold**

61. At about 3:02 PM, set `games.restart_times: ["15:10"]` (any time 8 minutes ahead) and
    `/hcm reload`: `/hcm games status` says "Next restart: 3:10 PM (new runs held from 3:05 PM)".
    Deal a Twenty-One hand and leave it open. From 3:05, `/hcm play cliffs` still opens the warm-up
    choice, and either button says "The server restarts at 3:10 PM. New runs open again after it.";
    the open hand plays to the end, but the next Deal is refused the same way, and so is a cabinet's
    daily board if you haven't had today's try (Classic still deals), right through the 3:10 minute.
    From 3:11 all of them work again. Put `["04:00", "16:00"]` back and reload.

**The setup check, the new-courses line, and the games' quests and achievements**

62. `/hcm games check` (in game and from the console): one line per check and "All good." on a
    server set up as above. Put your Games world into `worlds.economy_enabled` and `/hcm reload`:
    it says "[FAIL] Games world 'games' is also an economy world - take it out of
    worlds.economy_enabled ..." and "1 thing to fix."; put it back. With `games.enabled: false` it
    still runs, and says so as a WARN.
63. Switch Fresh Courses on (or wait for Monday's new set): once every course is up, everyone in a
    world the games are played in reads "New courses this week! Easy, Parkour, Hard, Sky Rings, Golf
    and Dropper - /hcm play" once; a player mid-course reads it after leaving the course, one on a
    screen after closing it, one who logs in later a few seconds after joining. A relog doesn't
    repeat it, and neither does `/hcm games gen reroll`: a reroll is the same set.
64. `/hcm play news off`: "No more new-course lines in chat."; the next set says nothing to you.
    `/hcm play news` shows the setting; `on` turns it back on. Tab completion offers `news`, then
    `on`/`off`.
65. With the games on, `/hcm quests` can deal "Play 3 arcade cabinets" or "Finish a course or a
    round of golf" (with the games off it never does). Play three cabinet games (practice counts;
    closing one early doesn't; losing a Connect Four or Tic-Tac-Toe game against the Arcade, or
    digging up a creeper, does): "Quest complete" and the tokens. Finish a course in the Games
    world: the quest's tokens come the moment you are back home. Finish one from a Games-world
    lobby, log off there, and log in the next day in your home world: the quest is paid then.
66. `/hcm achievements`: the Games group. Finish a cabinet game: "Achievement! Finish an arcade
    cabinet game" (+10). Finish a course: that one unlocks when you get home. Set a golf course's
    record: "Set a course record" counts it. Games of chance never move any of them.

**The events batch: warm-ups, party races, golf together, the Weekly Cup and Race Night** (the
Dropper and Falling Floors have their own lists in their sections)

67. Start `cliffs`: a small screen offers "Warm up (3:00)" and "Go straight to the timed run". Warm
    up: the action bar reads "Warm-up 2:14 left - not counted", crossing the finish says "Warm-up
    lap: 0:48.2 (not counted)" with nothing on the board and no tokens. Tap **Start timed run**:
    back to the start, 3-2-1, and that run counts as usual.
68. With a restart a few minutes ahead (as in 61), warm up during the hold: "The server restarts at
    3:10 PM - so the warm-up is over and your timed run starts now." Choose Warm up, then log out
    before arriving: the next start asks again.
69. On `cliffs`' screen, **Race with friends** (slot 20) opens a party; invite a friend (Bedrock:
    `/hcm play accept`). The party screen shows the Cup's item at slot 44 when the course runs one.
    The host starts: first a shared warm-up ("Warm up first: on (3:00)" at slot 42 is the default;
    it ends early once everyone taps Ready), then one shared 3-2-1 for everyone. Walk off your spot
    before Go: you're put back ("Stay on your grid spot until it says Go!"). On parkour and elytra
    nobody can push anybody, and a player on another plugin's nametag team is back on it after the
    race; boats bump. Back in the Clubhouse, the board ranks everyone, and so does the screen behind
    the kit's **Results** item (with the Clubhouse off, the results screen opens at home). Each finish
    is also a normal run on the course (its board, its rewards, the Cup).
70. `/hcm play race fresh_dropper`: "The Dropper has no party races - try its practice drop
    instead." A party race on the Race Night track 2 minutes before a night: "Your party race is
    called off - Race Night needs this track now." and everyone goes home with their things.
71. Golf together: **Play with friends** on a golf course's screen, invite, Start. When the first
    ball of a hole drops: "Hole clock: 2:00 to finish this hole - then any ball still out is picked
    up."; the others' action bar counts down and the card reads "Still playing: Ava - picked up in
    1:45"; at 0:00 "Time's up on this hole - your ball is picked up." (par + 3). Finish your last
    hole: your round is recorded and paid at once, even if you leave before the others. A friend
    whose trip never arrives: "Ava didn't make it to the course - the rest of you carry on."
72. The Weekly Cup: on a Fresh parkour course (or after `/hcm games cup on cliffs`), slot 24's name
    reads "Enter this week's Cup: 5 tokens. Best time wins the pool. Cup pool: 0 tokens · 0 in".
    Click it: the Cup screen's book says "How it's paid: 70/30 for 2 Cup times, 50/30/20 for 3 or
    more". Enter (5 tokens), finish a counted run: "New Cup time on ...". A warm-up lap sets none.
    `/hcm play cup` lists your Cups and when they are paid; `/hcm play cup off` hides the Cup on
    your course screens (`on` brings it back).
73. `/hcm games cup settle cliffs`: what it would pay; with `confirm`, prizes by Cup time and a line
    to each entrant. With two entrants and only one Cup time, both get their entry back and no
    top-up is added. `/hcm tokens audit` shows the entries, prizes and refunds netting exactly the
    top-ups paid. The course screen still shows the Cup item, now "Weekly Cup - already paid out
    this week" (its tile shows no Cup until next week).
74. `/hcm games cup off cliffs` with entrants asks for `confirm`, then "This week's was called off:
    N tokens back to M player(s)."; `disable` or `delete` on a course with entrants asks too.
    Put junk in `games.cup` and `/hcm reload`: `/hcm games cup status` says "entries closed
    (games.cup can't be read - see the console)", and running Cups still pay. `games.cup.enabled:
    false` takes the Cup off the screens; Cups already paid into still pay at the rollover.
75. Race Night: switch on Ice Boat (after its checklist in "Ice Boat: the Mountain Run") and
    `games.race_night.enabled`, reload,
    `/hcm games event start in 1`, and join from `/hcm play race` with two friends. One player
    tapping Ready doesn't end the warm-up while a joined racer isn't at the track; it never ends
    before the advertised time. Three races, the stand between them, then 5, 3 and 1 tokens with 3
    racers (a 4th racer adds 2 for 3rd), and everyone goes to the Clubhouse: "Race Night is over -
    great racing! Everyone to the Clubhouse!" (with `games.clubhouse.race_night_after: false` or no
    Clubhouse: "Race Night is over - great racing! Your things are back.") `/hcm reload` while
    racers are at the track warns first. `event cancel confirm` during race 1 gives the prize night
    back (`/hcm games event status` shows x/3). `event start in 30`, then a restart: the night is
    still set.
76. `/api/arcade`: a course running a Cup has `"cup":{"entry":5,"pool":...,"entrants":...,
    "endsAt":...}`; with Race Night on there is an `events` object; with Falling Floors on,
    `falling_floors` is in `games` with `"kind":"arena"`. With `arcade_show_names: false`, no
    `holder` appears anywhere.

**Where the places stand: out of sight, and a void world** (see "Where they are")

77. At the first start of this version the console says once "Games layout: a new install, so the
    Games places use the new spots, far apart, and from any course you can't see another." (on a
    server that built in 0.35: the WARN of "Coming from 0.35"). `/hcm games check`, under what
    players can see: "View distance used: 10 (...)" and "Nothing else built by the games can be seen
    from any course, the Clubhouse or the arena (view distance 10; the closest two places are 36
    chunks apart, clear up to view distance 34)".
78. `/hcm games gen tp fresh_parkour`, press F5 and look all round at your largest render distance:
    sky and that course only, not another course and not its spare half (576 blocks east). The same
    from `fresh_golf`, the Clubhouse and the Falling Floors gallery.
79. `/mv create sky normal -g HomeCraftManagement`, then `/mv tp sky`: you stand on a 5 x 5
    smooth-stone platform with a light, and there is nothing else, not even below. With `sky` in
    `games.worlds` and `/hcm reload`, `/hcm games check` says "Games world 'sky' is void (nothing
    below the courses)" (finish the steps in "A void world", or take it out again).
80. Restart the server with `sky` made. The start-up log enables HomeCraftManagement before
    Multiverse-Core and has no "Could not set generator for world 'sky'" line; `/hcm games check`
    still says "Games world 'sky' is void", and flying to chunks nobody has visited shows only sky.
    Start a course in `sky`, then stop the server while you are on it: after the next start you are
    sent home with your own things, and entering `sky` again shows no kit items left over.

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
| `games` | Every open game in catalog order, after the Scratch Ticket; the time trials and mini golf publish one entry per course. `kind` is `chance`, `cabinet`, `parkour`, `elytra`, `boat`, `dropper`, `golf` or `arena` (Falling Floors). An id is published once, so the site can key on `id` |
| `stakes` / `rtp` / `rtpByStake` | A game of chance's stakes, and what each gives back in percent: the engine's exact value **floored** to one decimal, the same number `/hcm arcade odds` gives admins. `rtp` is the lowest of them. A stake is published only with its RTP, and a game with no stake left is left out |
| `dailyLimit` | Plays per player per day (the Scratch Ticket has none) |
| `paytable` | What each result pays. `chance` has 4 significant digits and never reads `0`; `oneIn` is the number the game's screen shows. With `stake`, `pays` is tokens at that stake; without it (Ore Slots), `pays` is a multiple of the tokens put in. The Wheel counts `spaces` of `of` (24) instead of `chance`, one row per stake per prize, "your N back" and nothing included. A row without its odds is never published |
| `rules` | One plain line, where the game gives one |
| `payouts` | Twenty-One: per stake, what a `win` and a `twentyOne` pay back, plus a `doubleWin` when that stake offers a Double (no paytable) |
| `maxMultiplier` / `maxGuesses` | Higher or Lower: where a run cashes out by itself |
| `board` / `unit` / `lowerIsBetter` / `best` | A cabinet's published board, its unit (`ms`, `points`, `flips`, `apples` or `wins`) and the server record, `best` (absent until there is one) |
| `tier` / `record` | A course's tier (`easy`…) and record `{ms, at}`. Golf has `holes`, `par` and `record` `{strokes, at}`. `at` is epoch ms |
| `featured` | Today's pick (a game or course id) and `until`, the next local midnight. Only when that id is in `games`, or `trials` / `golf` when the owner pinned a whole world game: then every course of that kind (`parkour`/`elytra`/`boat`/`dropper`, or `golf`) is the pick, and it is published while at least one of them is in `games` |
| `jackpots` | The Scratch Ticket's pot now. Whenever it is there, `games` has the `scratch_ticket` entry too, so the site never shows a pot without its odds. Its `rtp` is the steady-state figure `/hcm arcade odds` prints for admins, floored to one decimal like every other RTP (the command rounds it, so the two can differ by 0.1) |
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

**Leaderboards and Fresh Courses (0.35, final round).** Added fields, all optional, so a site that
ignores them is unaffected; every existing field is unchanged:

```json
{ "id": "snake", "name": "Snake", "kind": "cabinet", "board": "classic", "unit": "apples",
  "lowerIsBetter": false, "best": 31, "holder": "Sam",
  "top": [ { "rank": 1, "value": 31, "unit": "apples", "at": 1790100000000, "holder": "Sam" },
           { "rank": 1, "value": 31, "unit": "apples", "at": 1790200000000 },
           { "rank": 3, "value": 24, "unit": "apples", "at": 1790300000000 } ] }
{ "id": "fresh_parkour_hard", "name": "Hard Parkour", "kind": "parkour", "tier": "hard",
  "record": { "ms": 62300, "at": 1790100000000 },
  "daily": { "day": "2026-09-28", "nextAt": 1790604800000, "goldMs": 45000, "silverMs": 70000,
             "cadence": 7, "lastDay": "2026-10-04" },
  "fresh": { "code": "HARD-40", "seed": "3f2a9c01b7de", "from": 1790000000000, "to": 1790604800000,
             "cadenceDays": 7 },
  "top": [ { "rank": 1, "value": 62300, "unit": "ms", "at": 1790100000000 } ] }
{ "id": "fresh_classic_parkour", "…": "…", "classic": { "code": "HARD-40", "from": 1795000000000 } }
```

| Field | Meaning |
|---|---|
| `top` | On every entry with a board (a cabinet, a course, a golf course, a Fresh course, a Classic): its best `games.feed_top` (5) rows `{rank, value, unit, at, holder?}`, best first. Ties share a rank (1, 1, 3). `value` is in `unit` (`ms`, `strokes`, `points`, `flips`, `apples`, `wins`); `holder` only with `arcade_show_names: true`, and never a UUID. Absent for an empty board. `record` / `best` are unchanged |
| `daily.cadence` / `daily.lastDay` | A Fresh course's set: its length in days and its last day (`day` is its first). `nextAt` is absent on a Classic |
| `fresh` | A Fresh course's live set: its course code, short seed (12 hex), when it went up, when it changes (`to`, absent while pinned for good) and its cadence |
| `classic` | A Classics slot's entry: the recalled course's code and the recall's window (`to` absent for "forever") |
| `freshHistory` | Top-level, after `starChart`: every past (and the current) Fresh set, newest first, at most `games.fresh.feed_history` (26) per course: `{code, slot, name, kind, tier?, from, to?, seed, plays, record?: {ms or strokes, at, holder?}, kept?, classic?, top?}`. `to` is absent for a set up for good; `top` is its board's best 3 (fewer if `games.feed_top` is lower, none at 0; rows as above). Never a set that isn't up yet |
| `cup` | On a time-trial course's entry (never golf) while that course runs this week's **Weekly Cup** and it isn't paid out yet: `{entry, pool, entrants, endsAt}`, all whole numbers. `entry` is the tokens to enter (`games.cup.entry`), `pool` the pool now (every entry, plus `games.cup.server_topup` once 2 or more are in; the top-up is paid only if 2 or more set a Cup time), `entrants` a head count, `endsAt` when it is paid out (epoch ms: the quests' week start at 04:00). Never a player, a Cup time or a prize. Absent on a course without a Cup and with Time Trials closed; with `games.cup.enabled: false` (or a `games.cup` that can't be read) only a Cup that already has entrants is published, until it is paid out |
| `arena` entries | Falling Floors (`kind: "arena"`, id `falling_floors`, only while it is open): `{id, name, kind, shape?, top?}`. `shape` is this week's top floor (`disc`, `square`, `ring`, `plus` or `diamond`); `top` is this week's longest solo times (`unit: "ms"`, higher is better) |
| `events` | Top-level, after `freshHistory` and before `jackpots` (Race Night, EVENTS-DROPPER-SPEC §A.7): `next {id, name, joinAt, startsAt, course?: {id, name}, races, laps, entry: "free", prizes, finisherPrize, prizeNight, racers, maxRacers}` (`course` absent until `course: auto` has picked a track), `upcoming` (the start times after `next`, at most 4, 14 days ahead), `live {id, state: open\|racing\|break\|results, race, of, racers, standings?: [{rank, points, lap, laps, holder?}]}` (at most 8 rows; a finished night stays as `results` for 30 minutes), `recent` (the last 5 nights, newest first: `{id, at, course?, racers, state: done\|called_off, top?: [{rank, value, unit: "points", holder?}]}`, at most 8 rows each) and `season? {key, name, until, top?}` (this month's table; absent with `season: off`). `racers` is a count; no prize, UUID or balance is ever published. Absent while Race Night is off (it ships off), and empty parts are left out |

`holder` anywhere (a record, a cabinet's best, a `top` row, `freshHistory`) follows the one rule
above: only while `arcade_show_names` is `true`, and no name is even looked up while it is `false`.

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
| `hcm.games.play` | all | Play the Games: `/hcm play`, the Games screen, the cabinets, courses and mini golf, invites and Take a break, and `/hcm leave`. The games only open once `games.enabled: true`; Take a break (`/hcm play break`) and `/hcm leave` work even while they're off. Race Night's chat lines and join bar only reach players who have it |
| `hcm.games.chance` | all | The games of chance. Revoke it for a group to keep them out of every game of chance, Crates, Scratch Tickets and token Card Packs included. While the games are on, the Games screen and the Arcade don't show them the games of chance, the crates or the Scratch Ticket, and Tab after `/hcm play` doesn't offer the games of chance. Token Card Packs still show in the pack shop, and so do the crates and Scratch Ticket in the Arcade while the games are off; trying one says "Games of chance aren't open to you." Skill games are not affected |
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

## License

Proprietary - all rights reserved. See [LICENSE](LICENSE). The bundled SQLite JDBC driver keeps its own Apache 2.0 license.
