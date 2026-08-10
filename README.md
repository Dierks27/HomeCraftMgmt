# HomeCraft Management

A Minecraft **Paper** plugin. See [`DESIGN.md`](DESIGN.md) for the full spec and
[`CLAUDE-CODE-PHASE1.md`](CLAUDE-CODE-PHASE1.md) for the Phase 1 brief.

- **Target server:** Paper **26.2**, Java **25**
- **Build:** Gradle (toolchain pinned to Java 25), shaded runnable jar
- **Status:** **Phase 2.5** — Phase 1 (skeleton + PC + Mini Workbench) plus the
  **finite-stock commodities market**. Amazon store GUI (Phase 3) and Minis
  (Phase 4) are stubs where they connect.

---

## What Phase 1 delivers

| Piece | What it does |
|---|---|
| **Skeleton** | `plugin.yml`, main class with clean enable/disable, `/hcm` command, LuckPerms-ready permission nodes. |
| **Config** | `config.yml` matching the design schema. The `crafting` section is fully wired; `market`/`shipping`/`minis` are stubs. `/hcm reload` re-reads it live. |
| **Persistence** | SQLite (JDBC) with a clean DAO layer and a forward-only migration framework. Phase 1 table: placed custom-block locations + owner. |
| **Mini Workbench** | A placeable custom block (tagged vanilla `CRAFTER`). Right-click opens a custom crafting GUI. Placement + owner persisted. |
| **The PC** | A custom item **crafted at the Mini Workbench** via an admin-defined, **empty-by-default** recipe. Placeable; right-click opens a placeholder **Amazon** GUI (real market is Phase 3). |
| **Recipes** | Fully **data-driven and reloadable** — nothing hardcoded. Workbench recipe (vanilla, bootstrap) and PC recipe (Workbench-GUI) both live in `config.yml`. |
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

## Testing in-game

1. **Get the items** (admin): `/hcm give workbench` and `/hcm give pc`.
2. **Place the Workbench**, right-click it → the **Mini Workbench** GUI opens
   (3×3 input grid + result slot).
3. **Define a PC recipe** in `config.yml` under `crafting.pc.recipe` (see the
   commented example), then `/hcm reload`.
4. In the Workbench GUI, lay the ingredients into the grid → the **PC** appears
   in the result slot → click it to craft.
5. **Place the PC**, right-click it → the placeholder **Amazon** GUI opens.
6. Break either block → it drops the correct custom item and its record is
   removed. Restart the server → placements persist (SQLite).

### Making the blocks craftable in survival

Both recipes ship **empty** (nothing is craftable until you fill them in):

- **Mini Workbench** — a normal crafting-table recipe. Fill
  `crafting.workbench.recipe.shape` + `.ingredients`.
- **PC** — crafted only at the Workbench. Fill `crafting.pc.recipe`.

Edit `config.yml`, run `/hcm reload`, done — no restart needed.

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

**Requires Vault + an economy plugin** (EssentialsX). Without one, buy/sell are
refused with a clear message (everything else still works).

**Tuning** lives in `config.yml` under `market`: `elasticity`, `inertia`,
`spread`, `default_full_stock`, `sell_limits`, `price_history`, and the `catalog`
(per-item `floor` / `ceiling` / `initial_stock` / `full_stock`). Edit and
`/hcm reload` — existing stock/price is preserved; new items seed fresh.

**Verify the engine:**
1. `/hcm market price diamond` → starts **OUT OF STOCK** at the ceiling; `buy` is refused.
2. `/hcm market sell diamond 64` → market stock becomes **+64** and the price drops.
3. `/hcm market buy diamond 16` → stock falls, price ticks back up; `sell`/`buy` prices differ (spread).
4. `/hcm market price cobblestone` → seeded with stock, cheap and buyable from day one.
5. Restart the server → prices and stock come back **unchanged** (persisted).

---

## Permissions

| Node | Default | Grants |
|---|---|---|
| `hcm.admin` | op | All admin commands (`/hcm …`) + all child nodes |
| `hcm.use` | all | Run `/hcm` (view market list/prices) |
| `hcm.market.order` | all | Buy from / sell to the dynamic market |
| `hcm.pc.use` | all | Open the Amazon GUI on a placed PC |
| `hcm.pc.craft` | all | Craft the PC at a Workbench |
| `hcm.workbench.place` | all | Place a Mini Workbench |
| `hcm.workbench.use` | all | Open a placed Workbench's GUI |
| `hcm.protection.bypass` | op | Bypass Towny/WorldGuard checks for our blocks |

---

## Project layout

```
src/main/java/com/dierks/homecraft/
  HomeCraftManagement.java     main class / wiring
  block/                       custom-block type, service, listeners
  command/                     /hcm
  config/                      typed config.yml view
  crafting/                    Workbench GUI + data-driven recipe matching
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
