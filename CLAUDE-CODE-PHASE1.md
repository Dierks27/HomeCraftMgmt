# Claude Code — Phase 1 Kickoff Prompt

**How to use:** In the `Dierks27/HomeCraftMgmt` repo, add the design doc as `DESIGN.md` (from `HomeCraft-Plugin-Design.md`), then paste the prompt below into Claude Code.

---

## The prompt

> I'm building a Minecraft **Paper** plugin called **HomeCraft Management** in this repo (`Dierks27/HomeCraftMgmt`). The full design is in **`DESIGN.md`** — read it first and treat it as the source of truth for the entire project.
>
> **Tech stack:** Paper **26.2**, Java **25**. Use **Gradle** with the Java toolchain pinned to 25 and the Paper API dependency for 26.2 — **verify the exact artifact coordinates/version that's actually published** (e.g. `io.papermc.paper:paper-api` for 26.2) before locking the build. Produce a runnable (shaded) jar.
>
> **Build Phase 1 ONLY** (see `DESIGN.md` §6): project scaffolding + the PC + the Mini Workbench. Do not build the market, shipping, or Minis yet — just stubs where they connect.
>
> 1. **Project setup:** Gradle Paper-plugin skeleton, `plugin.yml` (name `HomeCraftManagement`, correct `api-version`, main class), a clean main class with proper enable/disable, and a build that outputs a working jar.
> 2. **Config scaffolding:** load a `config.yml` matching the schema in `DESIGN.md` §4 (`crafting`, `shipping`, `market`, `minis`). For Phase 1 fully wire the `crafting` section; the rest can be stubs. Add a `/hcm reload` command.
> 3. **Persistence:** set up an **SQLite** datastore (JDBC) with a clean DAO layer. Phase 1 only needs a table for **placed custom-block locations + owner**, but design the schema so later phases (orders, minis, auctions) extend it cleanly.
> 4. **The Mini Workbench:** a placeable "custom block." Bukkit has no true custom blocks, so implement it as a **tagged vanilla block via PersistentDataContainer** (pick a sensible base block). On **right-click**, open a **custom crafting GUI**. Persist placed Workbench locations + owner. **Respect Towny/WorldGuard build permissions** for placing/using it.
> 5. **The PC:** a custom-named item, **crafted at the Mini Workbench** via an **admin-defined, config-driven recipe that is empty by default** (not craftable until the admin fills it in; reloadable). When placed and right-clicked, open a **placeholder "Amazon" GUI** (a stub — the real market is Phase 3). Persist placed PC locations + owner.
> 6. **Recipes are data-driven and reloadable — never hardcoded.** The Workbench recipe, the PC recipe, and (later) Mini recipes all live in config and are changeable anytime.
>
> **Rules:**
> - **You can't see the live Minecraft server** — its full plugin environment is documented in `DESIGN.md` §2.1, and **exactly how to hook each integration (Vault, Towny, WorldGuard, LuckPerms, PlaceholderAPI) is in §9**. Build against those. Declare integration-relevant plugins as soft-dependencies and degrade gracefully if missing. Verify exact API signatures against the installed versions (that's the "formalities" pass).
> - Follow `DESIGN.md`. Keep the four modules loosely coupled so phases ship independently.
> - Do **NOT** integrate with or modify QuickShop. The two shop systems are intentionally separate.
> - Economy (Vault) hooks come in later phases — don't wire them yet.
> - Commit in logical steps with clear messages.
>
> **When Phase 1 builds and the PC + Mini Workbench work in-game, STOP** and summarize what's done + how to test, so I can verify before we start Phase 2 (the dynamic market engine).

---

## Notes for whoever runs this

- **Custom blocks:** there's no native custom-block API — everything "custom-block" is a real placed block tagged with PDC + event listeners. If you later want true custom models, that's a resource-pack/ItemsAdder discussion (out of scope for now).
- **Paper 26.2 API:** confirm the published artifact before building; a wrong version string is the most likely early snag.
- **Phased build:** resist doing more than Phase 1. Each phase gets tested on the live server before the next.
- **Bring questions back to the designer** (that's the Cowork session that wrote `DESIGN.md`) — design changes get made there and flow back into `DESIGN.md`.
