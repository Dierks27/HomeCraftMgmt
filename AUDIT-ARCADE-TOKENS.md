# Audit — tokens, arcade, quests, achievements, packs, crates, lotto, printer, Minis

**Date:** 2026-09-13
**Audited tree:** `main` @ `8dac7e3`, version `0.24.0-wild-spawns`
**Compared against:** `73cbfdf`, version `0.17.0-buy-limits` — the jar running on the live server
**Status:** Part 0 deliverable. Nothing has been built. Decisions needed before Part 1.

---

## 0. Read this first — the brief describes a build that is six releases old

The live server runs `HomeCraftManagement-0.17.0-buy-limits.jar`. That is commit `73cbfdf`,
PR #27. `main` is at `0.24.0-wild-spawns`, commit `8dac7e3`, PR #37.

**Ten merged PRs (#28–#37) are built and never deployed.** The config summary dated
2026-08-24 was taken from a server running 0.17.0, so most of the eight suspected problems
describe code that has already been rewritten — and the two most serious holes in the token
economy were closed in PR #31, which is sitting in `main` right now.

This changes what the rebuild should be. A large part of Parts 2, 3 and 4 is a deploy, not a
build.

| # | Your suspicion | Live (0.17.0) | `main` (0.24.0) | Verdict |
|---|---|---|---|---|
| 1 | Tokens have no designed faucet or sink | True | Still true | **Real, and unfixed.** §2 |
| 2 | Two currencies doing one job | True, badly | Partly fixed | **Real, narrower.** §3 |
| 3 | Pack pool's rarest outcome is cobblestone | Your authored config | Your authored config | **Yours, not the jar's.** §5 |
| 4 | Lotto returns nothing 50% of the time | True | True | **Real.** §6 |
| 5 | Quests reward the grind | True | True | **Real.** §7 |
| 6 | Achievements never rescaled | True | True | **Real.** §7 |
| 7 | Three competing prestige axes | True | **Already fixed** in #30 | **Deploy it.** §8 |
| 8 | Shiny may cost less than base | False | False | **Non-issue.** §8 |

---

## 1. The finding that outranks everything else: the live crate mints money

`arcade.crates.starter` on 0.17.0:

```yaml
rewards:
  - { type: money, amount: 100,  weight: 50 }
  - { type: item,  material: DIAMOND,    amount: 1, weight: 20 }
  - { type: item,  material: GOLD_INGOT, amount: 4, weight: 20 }
  - { type: mini,  mini: piggy_mini, weight: 5 }
cost_tokens: 1
```

And `ArcadeService` at that commit, line 313:

```java
case MONEY -> {
    plugin.economy().deposit(player, r.amount());
```

**One token buys a 52.6% shot at $100, deposited straight into the Vault balance.** Expected
value is **$52.63 per token**, before the diamond and the four gold ingots — both sellable to
the market, worth another $29–$210 depending on stock level.

At the current earn rate of roughly 13 tokens a day, that is **~$684/day/player of newly
minted money**, and it does not touch the market, so the `$5,000/day` sell cap never sees it.
The diamonds and gold do count against the sell cap when sold, but the cash reward is
invisible to every safeguard in the plugin.

This is the laundering loop, it is live, and it is the most likely explanation for whatever
the dollar balances on the server currently look like.

**It is already fixed in `main`.** PR #31 restricted crate rewards to `card | mini | pack |
filament | tokens` and made `money` and `item` rejected at load with a warning
(`PluginConfig.java:883`). The starter crate was rewritten to pay Cards, filament and packs.

> **Recommendation:** deploy `main` before we build anything. That single action closes the
> money faucet, closes the item faucet, moves pity from 3 tokens to 25, and brings in the
> grade/rarity hierarchy from #30 that Part 3 asks for. Everything else in this brief is
> tuning on top of a jar that isn't leaking.

One caveat on deploying: PR #33 fixed runtime config saves clobbering hand edits to
`config.yml`, and PR #32 fixed the migration reading the jar's defaults instead of the file.
Both are good, but they mean the first boot on 0.24.0 will run migrations 3→7 against your
live file. **Take a copy of `plugins/HomeCraftManagement/config.yml` before starting it.** The
plugin takes its own pre-migration backup, but a second one costs nothing.

---

## 2. Token faucets and sinks — the actual rates

### Every place tokens are granted

| Source | Code | Rate (jar defaults) | Capped? |
|---|---|---|---|
| Login streak | `ArcadeService.onJoin` | `[1,1,2,2,3,3,5]`, last repeats → **5/day** at maturity | one claim per UTC day |
| Playtime | `ArcadeService.grantPlaytime` | **1 per 60 min** of `PLAY_ONE_MINUTE` | no cap, but milestone-based |
| Daily quests | `QuestService.complete` | 2 + 1 + 2 = **5/day** | once per quest per day |
| Weekly quests | `QuestService.complete` | 8 + 6 + 6 = **20/week** | once per quest per week |
| Achievements | `AchievementService.tryAward` | 3+2+2+1+2+5 = **15 lifetime** | once per player, ever |
| Crate `tokens` reward | `ArcadeService.grantFromPool` | **0** — not in the shipped table | **no validation at all** |
| Admin | `adminAdd` / `adminSet` | unbounded | by design |

Playtime is better than it looks: it reads the lifetime `PLAY_ONE_MINUTE` statistic and
tracks how many milestones were already paid in `playtime_tokens`, so it is catch-up-correct
and cannot be farmed by relogging. Leave that mechanism alone.

**Active day (3h online, all dailies): ~15.9 tokens.**
**Casual day (1h online, some dailies): ~9 tokens.**

Your Part 2 targets are 45 and 15. The faucet needs roughly **3x** on the active path.

### Every place tokens are consumed

| Sink | Cost | Notes |
|---|---|---|
| Starter crate | **1 token** | the only repeatable sink |
| Pity exchange | **25 tokens** | guaranteed RARE+ Card |

**That is the entire sink surface.** Two items. The lotto costs money. Packs cost money. The
printer costs money and filament. There is no machine play, no buff, no cosmetic, no
consumable, nothing.

A 1-token crate that pays out a Card worth hundreds is not a sink in any meaningful sense —
it is a faucet with a rounding error attached. Your "huge token balance and nothing to spend
it on" is exactly what this table predicts.

### No token cap exists anywhere

`TokenDao.save` clamps at `>= 0` and nothing else. There is no ceiling on a balance, no daily
earn cap, and no validation that a crate's `tokens` reward is smaller than the crate's
`cost_tokens` — an admin can configure a net-positive token loop today and nothing will warn
them. Worth adding a guard in Part 2 regardless of what the numbers end up being.

---

## 3. Currency mixing — where dollars and tokens actually cross

**On `main`, there is no direct token → dollar path.** The rule in §11 of DESIGN.md holds for
crate rewards: `money` and `item` types are rejected at load.

But the arcade still both takes and pays dollars, in two places:

- **Lotto** (`ArcadeService.scratch`): `economy().withdraw($250)` in, `economy().deposit()` out,
  up to $2,000.
- **Crate paid odds** (`ArcadeService.openCrate`): `economy().withdraw($750)` for a guaranteed
  RARE+ floor.

Both violate the Part 1 rule that the arcade never accepts and never pays dollars. The lotto
is the one that matters — a kid can lose real balance in there, which is precisely what you
said you wanted to make impossible.

**And there is an indirect token → dollar route that Part 1 should account for:**

```
tokens → pity/crate → Card → Printer (money + filament) → Mini → Auction/Vending → dollars
```

`AuctionService` and `VendingService` both pay sellers in Vault money. This is a
player-to-player transfer, not minting, so it does not inflate the supply — but it does mean
"tokens are one-way" is not literally true and never can be while Minis are sellable. The
honest formulation is **tokens never create dollars**, which is achievable; "no path converts
tokens back to dollars" is not, short of making Minis untradeable.

### The pity exchange is badly underpriced, and mis-rolls

Two separate problems in `ArcadeService.pity`:

1. **It picks uniformly at random,** not by rarity:
   ```java
   MiniDef chosen = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
   ```
   Every RARE+ Mini in the catalog is equally likely. A LEGENDARY is as probable as a RARE.
   `MiniService.pickByRarity` exists and is used by crates and wild drops — pity should use
   it. **This is a straightforward bug.**

2. **With the shipped catalog it is catastrophic.** The only RARE+ Mini is Golden Idol
   (LEGENDARY, `default_price` 50,000, cap 5). So 25 tokens — under two days of play — buys a
   *guaranteed* Golden Idol Card. Print it at Mint grade with scarcity at 2.0 and
   `MiniValue` computes 50,000 × 6.0 × 2.0 = **$600,000**. Five of those exist, and the
   exchange hands them out for 25 tokens each until they are gone, after which pity is
   permanently dead content.

   On the live 0.17.0 jar the price is **3 tokens**.

Your live catalog is larger than the shipped one, so the exact numbers will differ — but the
shape of the problem (uniform roll, no rarity weighting, price set against nothing) is in the
code, not the config.

---

## 4. Things in the brief that do not exist

Flagging these before they become build tasks:

- **There is no claw machine.** `grep -ri claw src/` returns one unrelated comment in
  `AuctionService`. Part 2's headline sink and Part 4's "pity applies to the claw machine
  only" have no subject. It would be new construction.
- **There is no pity *counter*.** Pity is a flat 25-token exchange with no counter, no
  accumulation and no reset. "Verify it resets correctly on a hit" has nothing to verify.
  Part 4 is asking for a mechanic that would need to be built from scratch.
- **There is no daily-reset scheduler.** Part 6 says to reuse it. The codebase deliberately
  has no scheduler: `DailySellDao`, `DailyBuyDao` and `QuestService.periodKey` all key rows
  by **UTC epoch-day computed at read time**, so they reset at midnight UTC with no timer and
  no restart risk. That pattern is better than a scheduler and Deliveries should reuse *it*.
  (One wart: `QuestService` weekly keys are `epochDay / 7`, and epoch day 0 was a Thursday, so
  weeklies roll over **Thursday 00:00 UTC**. Surprising, harmless, easy to change.)
- **The `deliveries` name is already taken.** `marketplace/DeliveryService`,
  `storage/DeliveryDao` and the SQL table `deliveries` (schema v12) are all the Mailbox
  queue — Marketplace purchases that ship to a player's Mailbox after a delay. A new
  `deliveries` module would collide on all three. Needs a different name (`haulage`,
  `freight`, `jobs`) or at minimum a `delivery_jobs` table and a distinct package.
- **Nine of the eleven Part 5 quest verbs have no hook.** `QuestType` is
  `{SELL_MARKET, OPEN_CRATE, PRINT_MINI, OPEN_PACK, SCRATCH}` and all five are correctly
  wired. Fish, crops, distance, hostiles, breeding, cooking, smelting, villager trades and
  new biomes would all be new listeners or new statistic snapshots.

---

## 5. Packs

**Shipped (`main`):** `starter` $250 for 3 cards (piggy 50 / chick 50); `premium` $750 for 3
(piggy 45 / chick 45 / golden_idol 1).

**Yours (from the summary):** five commons at 10, `cobblestone` UNCOMMON at 45,
`1_up_mushroom` RARE at 5.

The cobblestone-dominant weighting is **in your authored config, not in the jar.** Packs are
GUI-authored through `/hcm packs` → `PackService.save()` → `writeConfig("packs", …)`, so
whatever is in the live file was built in-game. Same for the crate's cobblestone Mini reward.
This is good news — it is content, not code, and Part 4 can fix it by rewriting the pool.

Two code-level notes for Part 4:

- `count: 3` is per-pack config, so "exactly one Mini per pack" is a config change, not a code
  change. `PackService.open` loops `def.cardCount()` times.
- **`type: card` and `type: mini` in a crate do the same thing.** Both fall through
  `case CARD, MINI ->` and issue a *Card*. The config comment claims they differ. Either
  collapse them or make `mini` mean something; right now it is a trap for whoever edits the
  crate table next.

---

## 6. Lotto

Shipped: ticket **$250**, payouts `0/100/500/2000` at weights `50/30/15/5`.

EV = (0×50 + 100×30 + 500×15 + 2000×5) / 100 = **$205** on a $250 ticket → **18% house edge**,
**50% nothing**.

Your summary's numbers ($50 ticket, 0/25/100/500, EV $47.50, 5% edge) are a different table —
again, your live config, not the jar. Either way the structural complaint is correct and
identical: *the single most likely outcome is nothing*. The edge is fine; the shape is wrong.

There is no cooldown and no daily cap on scratching. It is EV-negative so it self-limits, but
a player with a large balance can scratch indefinitely.

---

## 7. Quests and achievements

**Quests.** Both sell-based quests are exactly as described: daily `SELL_MARKET 500`, weekly
`SELL_MARKET 5000`. Against a ~$200 session that is 2.5 and 25 sessions. The other four
(`OPEN_CRATE ×1`, `PRINT_MINI ×1`, `OPEN_PACK ×3`, `OPEN_CRATE ×10`) are fine in shape but
`crate_weekly` at 10 crates pays 6 tokens for 10 tokens of crates — a net loss the player
cannot see.

**Achievements.** `rich_10k` at $10,000 against a $200 session is ~50 sessions. Your read is
right. Note the global sell cap is **$5,000/day**, so the *ceiling* is ~2 days — but the
binding constraint is what players can actually produce, not the cap. That gap is itself the
finding: the cap is set 25x above the real earn rate, so it is not doing anything.

`checkBalance` only fires on join and after a market sell (`MarketService:431`), so `rich_10k`
can sit unnoticed until the next login. Minor.

---

## 8. Minis — two of your suspicions are already resolved

**Prestige hierarchy (#7): already built, in PR #30, undeployed.** `MiniItems` javadoc:

> styled by **rarity** (name colour + glint always come from the rarity palette), with the
> **grade** shown as a star suffix on the name and in lore — never as a competing colour.

That is exactly the hierarchy Part 3 asks for: rarity owns colour, grade owns stars, shiny is
a finish. `Grade` carries `symbol()` and `valueMultiplier()` and no colour at all. A Common
cannot outrank a Rare in colour because grade never touches colour. What still needs checking
once deployed is **sort order** in the Museum/Binder GUIs — I have not confirmed those sort
rarity-first.

**Shiny pricing (#8): not a bug, in any version.** `PrinterService:143`:

```java
double fee = cfg.fee() + (wantShiny ? cfg.shinyFee() : 0);
```

Shiny is unambiguously additive, plus 2 extra MAGENTA filament. At 0.17.0 that is $50 + $25;
on `main` it is $0 + $25; with your summary's numbers it would be $10 + $5. **Surcharge in
every case.** Nothing to fix.

**Wild spawns.** Part 3's broadcast-on-spawn, no-coordinates, rarity-coloured find broadcast,
and real despawn are all already implemented (`AnnounceService.spawnHint` / `found` /
`slippedAway`, `minis.loot.natural.despawn_minutes: 3`). PR #37 tuned spawns to 96–128 blocks,
3 minutes, `max_live: 2`, `player_cooldown_minutes: 120` — which is very close to your "two to
four per active evening".

What does **not** exist and would be new: **escalating hints** (biome at 30%, 500-block radius
at 60%, BlueMap ping at 85%). There is no BlueMap integration in the codebase at all.

Note `minis.loot.sources: []` ships empty — nothing drops until configured. Your live server
has sources configured; I cannot see them, so I cannot audit your actual drop rates. See §10.

---

## 9. Smaller findings

| Finding | Where | Severity |
|---|---|---|
| Pity rolls uniformly instead of `pickByRarity` | `ArcadeService.pity` | **bug** |
| Crate `tokens` reward has no `amount < cost_tokens` guard | `PluginConfig.readCrateReward` | footgun |
| No token balance cap or daily earn cap | `TokenDao` | design gap |
| `type: card` and `type: mini` are aliases but documented as distinct | `ArcadeService.grantFromPool` | trap |
| `arcade.machines.*` — four retired block types still parsed and styled | `PluginConfig:869-872` | dead-ish config |
| `arcade.tokens.login_streak.reward_per_day` only read when `rewards` is empty | `PluginConfig:824` | vestigial, documented |
| Weekly quests roll over Thursday 00:00 UTC | `QuestService.periodKey` | surprising |
| `spend`/`grant` are read-modify-write with no transaction | `ArcadeService` | safe on main thread only |
| `grantPlaytime` does a DB read per online player every 5 min on the main thread | `ArcadeService.start` | fine at current scale |
| `checkBalance` only fires on join and market sell | `AchievementService` | minor |

---

## 10. What I cannot see, and why it matters

**I audited the jar's defaults. Your problems are largely in your live config, which is not in
this repo.** Specifically I could not verify:

- Your actual Mini catalog (the summary implies Minis called `cobblestone` and
  `1_up_mushroom`, neither of which is in the shipped catalog)
- Your actual `minis.loot.sources` — so I cannot answer "anything in the Minis wild-drop path
  that fires more often than intended"
- Your actual market catalog — the shipped one has **six rows, all raw materials**
- Your real per-session earn rate

**Could you attach the live `plugins/HomeCraftManagement/config.yml`?** Most of the tuning in
Parts 2–5 is guesswork without it.

### This directly blocks Part 5's catalog change

You asked me to propose a list of crafted goods to raise above their raw inputs — bread above
wheat, glass above sand, bricks above clay, bookshelves above books.

**None of those items exist in the shipped catalog.** It is `cobblestone`, `oak_log`, `wheat`,
`iron_ingot`, `gold_ingot`, `diamond`. There is no bread, sand, glass, clay, brick, book or
bookshelf to raise.

So Part 5 is not "raise some floors" — it is **adding new catalog rows**, which means choosing
`floor`, `ceiling`, `initial_stock`, `full_stock` and both daily caps for each one. That
creates new dollar prices rather than moving existing ones, so it is arguably inside your
stated boundary, but it is a much larger piece of work than the brief implies and I don't want
to invent an economy's worth of numbers without your catalog in front of me.

### Two numbers in the brief that are off

- **The daily sell cap is $5,000, not $500** (`market.sell_limits.max_money_per_day`, $12,500
  for VIP). Part 6 paces delivery payouts against a "$500 daily sell cap" — five foot jobs at
  ~$310 is 62% of $500 but **6% of $5,000**. Either the deliveries can pay considerably more,
  or the sell cap should come down, but the stated ceiling check doesn't hold as written.
- **`Statistic` constants are unverified.** This environment has no JDK 25 and the proxy
  blocks `repo.papermc.io`, so **the project cannot be compiled locally at all** — every build
  has to go through CI. I could not check the Part 6 statistic list against Paper 26.2.
  `CROUCH_ONE_CM`, `WALK_ON_WATER_ONE_CM` and `WALK_UNDER_WATER_ONE_CM` are the ones I'd
  expect trouble from. A bad constant is a compile error, so CI will catch it definitively on
  the first Deliveries PR — but it means no local verification of anything, for any part.

---

## 11. Recommended order

1. **Deploy `main` (0.24.0) to the live server.** Closes the money-minting crate, the item
   faucet, and brings pity to 25. Back up `config.yml` first. Nothing below is worth doing on
   0.17.0.
2. **Send me the live `config.yml`** so Parts 2–5 are tuned against reality.
3. Then the namespace split, tokens, Minis/arcade content, missions, Deliveries — as you had
   them.

## 12. Decisions I need from you

1. **Deploy 0.24.0 first?** Strong recommendation yes.
2. **Can you attach the live `config.yml`?**
3. **Claw machine and pity counter — build them?** Both are new construction, not repairs.
4. **Part 5 catalog: add new rows, or raise floors on rows you already have?** Depends on your
   live catalog. If it is still the six shipped rows, there is nothing to raise.
5. **Deliveries ceiling: $5,000/day cap changes the maths.** Raise delivery payouts, lower the
   sell cap, or leave deliveries as a deliberately minor income stream?
6. **What do we call the deliveries module,** given `deliveries` is taken by the Mailbox queue?
7. **"Tokens are one-way"** — accept "tokens never *create* dollars" (achievable), or make
   Minis untradeable (not recommended)?
8. **Lotto and crate paid-odds currently take dollars.** Part 1 says the arcade takes tokens
   only. Confirm the lotto moves to 10 tokens and paid-odds is removed or re-priced in tokens?
