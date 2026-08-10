# Claude Code — Phase 2.5 Kickoff Prompt (Finite-Stock Market Revision)

**How to use:** Phase 2 (basic market) is merged. Paste the prompt below into Claude Code (in the repo).

---

## The prompt

> The market from Phase 2 is merged, but its model needs revising. Read the **updated `DESIGN.md`** — especially the rewritten **§3.1 (The Market — a Finite, Conserved Commodities Exchange)** — it is the source of truth. Build **Phase 2.5: the finite-stock revision** of the market engine.
>
> Start from `main`, new branch, open a PR when done (same flow as before).
>
> **Convert the market engine from the current abstract "net demand" counter to real, finite, conserved stock:**
>
> 1. **Positive stock inventory:** each commodity has a real **stock count** = units the market currently holds. **Selling ADDS** to stock; **buying SUBTRACTS**. Stock is **floored at 0** — never negative. (Fixes the current behavior where selling 64 read as −64; it should read +64.)
> 2. **Out-of-stock enforcement:** at **stock = 0 the item cannot be bought at all** (not just expensive — unavailable). Return a clear "out of stock" result.
> 3. **Scarcity pricing:** price is a function of current stock — low stock → toward the **ceiling**, high stock → toward the **floor**, via configurable **elasticity** + **inertia**. **Zero stock ⇒ price at the ceiling.**
> 4. **Per-item starting stock (config):** each item's `config.yml` entry sets an **initial stock**. Staples (cobblestone) seed with generous stock (available/cheap); ores (gold, diamond) can start at **0** (ceiling price, out of stock until players sell in).
> 5. **Buy/sell spread:** the market's **buy price sits slightly above its sell price** (configurable spread) — kills round-trip self-arbitrage, gives the market a margin.
> 6. **Market cash is infinite; only item stock is finite.** The market always transacts at the current price; only inventory can run dry.
> 7. **Daily per-player sell limit (anti-whale):** cap how much a player can sell per day — configurable as **max money/day** and/or **max units of an item/day**; resets daily; tracked per-player in SQLite; optional per-LuckPerms-rank ceiling; clear "limit reached — resets in Xh" message.
> 8. **Price-history log:** record periodic price snapshots per commodity (feeds the Phase 5 web dashboard charts). Add a new SQLite migration extending the datastore.
>
> Keep the existing Vault money flow and the admin/test commands (`/hcm market buy|sell|price|list`) working against the new model. Player-facing GUIs are Phase 3 — commands stay admin/test for now.
>
> **Rules:** follow `DESIGN.md`. Market side ONLY — do NOT touch QuickShop. Migrate Phase 2 data forward cleanly (don't wipe it). Commit in logical steps.
>
> **When it builds and the finite-stock behavior works** (sell → +stock, buy → −stock, zero = out-of-stock at ceiling price, ores start empty, spread holds, daily cap enforces, values persist across a restart), **STOP** and summarize + how to test, so I can verify before Phase 3.

---

## Build note (unchanged from before)
- Paper 26.2 API from `repo.papermc.io` (`io.papermc.paper:paper-api:26.2.build.+`), **JDK 25 toolchain**.
- Gradle 8.14 can't *run* on JDK 25 — run Gradle on **JDK 21** with the JDK 25 toolchain (`-Dorg.gradle.java.installations.paths=...`).
- CI now auto-publishes the release jar on merge to `main`, so no manual build/release chase.
