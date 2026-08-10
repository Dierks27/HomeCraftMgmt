# Claude Code — Phase 2 Kickoff Prompt

**How to use:** Phase 1 is merged to `main`. Paste the prompt below into Claude Code (in the repo).

---

## The prompt

> Phase 1 (skeleton + PC + Mini Workbench) is merged to `main`. Now build **Phase 2** from `DESIGN.md` — the **Dynamic Market Engine** (see §3.1, §5 data model, §9.1 Vault, and the phase plan in §6). Read `DESIGN.md` first; it is the source of truth.
>
> Start from `main`, work on a new branch, and open a PR when done (same flow as Phase 1).
>
> **Build Phase 2 ONLY** — the market engine and its data. Do NOT build the Amazon store GUI (that's Phase 3) or the Minis (Phase 4). Leave stubs where they connect.
>
> 1. **Item catalog (config-driven):** in `config.yml`, an admin-defined list of market items, each with a **base price**, **floor**, and **ceiling**. Reloadable via `/hcm reload`. Ship with a small example set, commented.
> 2. **Elasticity pricing engine:** each item has a **current price** and a **stock/demand counter**. **Buying raises** price, **selling lowers** it, scaled by a configurable **elasticity**. Clamp every price to that item's **floor/ceiling**, and glide toward targets with configurable **inertia** (no instant snapping). Wire the `market` config section from the design schema.
> 3. **Buy/sell via Vault:** wire the **Vault economy** now (per §9.1) — soft-depend, degrade gracefully if absent. Buying = withdraw money + give item + raise price. Selling = take item + deposit money + lower price.
> 4. **Persistence:** store per-item **current price + stock/demand** in SQLite via a **new forward migration** extending the Phase 1 datastore. Prices/stock **survive restarts**.
> 5. **Test surface (the store GUI is Phase 3, so add commands to verify the engine now):** e.g. `/hcm market list`, `/hcm market price <item>`, `/hcm market buy <item> <qty>`, `/hcm market sell <item> <qty>`. These let us watch prices move before the Phase 3 UI exists.
>
> **Rules:**
> - Follow `DESIGN.md`. These dynamic prices are the **Amazon/market** side ONLY — do NOT touch QuickShop or player-shop pricing (they set their own).
> - Reference DynamicShopGUI's elasticity concept, but original code (we're replacing that plugin).
> - Keep modules loosely coupled; commit in logical steps.
> - You can't see the live server — its environment is in `DESIGN.md` §2.1, integrations in §9.
>
> **When Phase 2 builds and the market commands work** (buy raises price, sell lowers it, floor/ceiling hold, values persist across a restart), **STOP** and summarize what's done + how to test, so I can verify before Phase 3.

---

## Build note (so the jar compiles — learned in Phase 1)

- Paper 26.2 API from `repo.papermc.io` (`io.papermc.paper:paper-api:26.2.build.+`), **JDK 25 toolchain**.
- **Gradle 8.14 cannot *run* on JDK 25.** Run Gradle on **JDK 21**, and point its toolchain at JDK 25 (`-Dorg.gradle.java.installations.paths=/path/to/jdk-25 -Dorg.gradle.java.installations.auto-download=false`). That's how Phase 1's jar was built.
- If the build sandbox returns 403 for `repo.papermc.io`, it can't compile — the design partner (Cowork) compiles it and publishes the release jar. Just push the code + open the PR.
