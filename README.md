# HomeCraft Management

A Minecraft **Paper** plugin. See [`DESIGN.md`](DESIGN.md) for the full spec and
[`CLAUDE-CODE-PHASE1.md`](CLAUDE-CODE-PHASE1.md) for the Phase 1 brief.

- **Target server:** Paper **26.2**, Java **25**
- **Build:** Gradle (toolchain pinned to Java 25), shaded runnable jar
- **Status:** **Phase 2** — Phase 1 (skeleton + PC + Mini Workbench) plus the
  **Dynamic Market Engine**. Amazon store GUI (Phase 3) and Minis (Phase 4) are
  stubs where they connect.

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
| **Dynamic Market** (Phase 2) | Config-driven catalog (base price / floor / ceiling), elasticity + inertia pricing, Vault-backed buy/sell, per-item price + demand persisted to SQLite. Test commands: `/hcm market list|price|buy|sell`. |

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

## Dynamic Market (Phase 2)

The online-market prices move with supply and demand — **buying raises** a price,
**selling lowers** it, each clamped to the item's floor/ceiling and eased toward
its target by an inertia factor. This is the **Amazon side only**; QuickShop
player-shops are untouched. The store GUI arrives in Phase 3 — for now these
commands drive the engine directly:

| Command | Does |
|---|---|
| `/hcm market list` | List every catalog item and its current price. |
| `/hcm market price <item>` | Show price, base/floor/ceiling, and net demand. |
| `/hcm market buy <item> <qty>` | Withdraw money via Vault, give items, raise the price. |
| `/hcm market sell <item> <qty>` | Take items, deposit money via Vault, lower the price. |

**Requires Vault + an economy plugin** (EssentialsX). Without one, buy/sell are
refused with a clear message (everything else still works).

**Tuning** lives in `config.yml` under `market`: `elasticity` (how hard prices
react), `inertia` (glide smoothing), and the `catalog` list (per-item
`base_price` / `floor` / `ceiling`). Edit and `/hcm reload` — existing prices and
demand are preserved; new items start at their base price.

**Verify the engine:**
1. `/hcm market price diamond` → note the price.
2. `/hcm market buy diamond 16` → price rises; `/hcm market buy diamond 64` → rises more (toward the ceiling, never past it).
3. `/hcm market sell diamond 32` → price falls (toward the floor, never below).
4. Restart the server → `/hcm market price diamond` shows the **same** moved price (persisted).

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
