# Audit — token economy, missions, packs, the hunt, and Deliveries

**Date:** 2026-09-13
**Audited:** `main` @ `582dc7f` (`0.24.0-wild-spawns`)
**Source of truth:** DESIGN.md v15, read first as instructed
**Status:** Part 0 deliverable. No code written. Decisions needed before Part 1.

---

## 0. Headline

Three things, in order of how much they change the plan.

1. **§3.9 is the stale section, not the code.** The brief is right that the token side was
   never balanced — but §3.9 also contradicts §11 #9 and the shipped code in three separate
   places. DESIGN.md is the source of truth and it disagrees with itself. §1 below.
2. **The PC Site launcher §2.2 specifies does not exist,** and the Store's navigation row is
   physically out of slots. Part 6 ("Deliveries as a PC Site") has a prerequisite nobody has
   costed. §7.
3. **Crates currently refund 0% in tokens** — the capability exists but the shipped table
   doesn't use it. The real sink problem is smaller and more specific than "crates refund
   tokens": there are only **two** sinks, and one of them costs **1 token**. §2.

Also worth knowing up front: **all fourteen `Statistic` constants in Part 6 verify** against
Paper's enum. But the list is missing two movement stats that exist on 26.2, and one of them
would make a legitimate mount read as teleporting. §8.

---

## 1. DESIGN.md contradicts itself — §3.9 vs §11 #9

§3.9 was written in the Phase 8 era and never revised when v15 landed the two-currency rule.
Three statements in it are now false:

| §3.9 says | Reality (§11 #9 + code) |
|---|---|
| Crates pay "**cash, items,** or (rarely) a Mini" | `money` and `item` are **rejected at config load** with a warning (`PluginConfig:883`) |
| A `mini` reward "**mints a finished, graded Mini directly**" | `CARD` and `MINI` both issue a **Card** (`ArcadeService.grantFromPool`, `case CARD, MINI ->`) |
| Pity is "**N tokens (e.g., 3)**" | `arcade.pity.tokens: 25` |

§11 #9 is correct and matches the code. §3.9 is the stale one.

This matters beyond tidiness: the earlier bad brief was largely generated *from* §3.9's
wording. Whatever we do in Part 1, **§3.9 needs rewriting first** or it will keep producing
wrong briefs. I'd fold that into the Part 1 PR rather than leaving it.

**One more doc/code gap:** §3.9 promises "anti-abuse cooldowns". Only the login streak has
one (one claim per UTC day). See §3.

---

## 2. Token faucets and sinks — the live map

### Faucets

| Source | Where | Rate | Cooldown / cap |
|---|---|---|---|
| Login streak | `ArcadeService.onJoin` | `[1,1,2,2,3,3,5]`, last repeats → **5/day** mature | one per UTC day ✅ |
| Playtime | `ArcadeService.grantPlaytime` | **1 per 60 min** of `PLAY_ONE_MINUTE` | milestone-based, **no cap** |
| Daily quests | `QuestService.complete` | 2 + 1 + 2 = **5/day** | once per quest per day ✅ |
| Weekly quests | `QuestService.complete` | 8 + 6 + 6 = **20/week** | once per quest per week ✅ |
| Achievements | `AchievementService` | 3+2+2+1+2+5 = **15 lifetime** | once per player ✅ |
| Crate `tokens` reward | `grantFromPool` | **0** — unused in the shipped table | **no validation** |

Playtime is better than it looks and should be left alone: it reads the *lifetime*
`PLAY_ONE_MINUTE` statistic and records how many milestones were already paid in
`playtime_tokens`, so it is catch-up-correct and cannot be farmed by relogging.

**Active evening (3h, all dailies): ~15.9 tokens. Casual (1h, some dailies): ~9.**

### Sinks — there are two

| Sink | Cost | Outcome |
|---|---|---|
| Starter crate | **1 token** | weighted pull: Card / filament / pack |
| Pity exchange | **25 tokens** | guaranteed RARE+ Card |

That is the entire sink surface. The lotto costs **money** ($250). Packs cost **money**
($250 / $750). The printer costs **money + filament**.

**This is your answer.** You earn ~16 a day against a 1-token crate. A sink priced at one
unit of a currency you earn sixteen of isn't a sink — the balance can only grow. Pity at 25
is the only thing that meaningfully spends, and it's ~1.5 days of play for a guaranteed
Rare+, which is too cheap to be a destination and too narrow to be a habit.

### Brief lead #2 — "crates refund tokens" — refuted, with a caveat

`RewardType.TOKENS` exists and `grantFromPool` pays it, but **no shipped crate uses it**, so
the live refund rate on jar defaults is **0%**. The concern is sound in principle and the
structural fix (remove `tokens` as a crate reward) costs nothing — I'd still do it, because
the config allows a reward larger than the crate's cost with **no validation at all**. An
admin can author a net-positive loop today and nothing warns them.

### Brief lead #7 — uncapped grants

- **No token balance cap anywhere.** `TokenDao.save` clamps at `>= 0` and nothing else.
- **No daily earn cap.**
- **Playtime has no cooldown** — it is self-limiting by real time, which is fine.
- `adminAdd` / `adminSet` are unbounded, which is correct for admin tools.

---

## 3. Brief lead #6 — token earning and `worlds.economy_enabled`

**Gating is present on every earn path**, and correctly: `onJoin`, `grantPlaytime`, `award`,
`openCrate`, `pity` and `scratch` all check `plugin.sandbox()`. §11 #1 holds.

**But there is a real bug in how it refuses.** `ArcadeService.award` checks the sandbox and
returns *silently* — after the caller has already committed the claim:

```java
// QuestService.record
if (progress >= q.target() && dao.markClaimed(id, q.id(), period)) {
    complete(player, q);          // -> award() -> sandbox refuses -> no tokens
}

// AchievementService.tryAward
if (dao.unlock(player.getUniqueId(), key, ...)) {
    ... plugin.arcade().award(player, def.reward(), ...);   // same
}
```

The quest is marked claimed and the achievement is marked unlocked **before** the payout is
attempted. If the payout refuses, the progression is consumed and nothing is paid. For a
daily quest that costs a day; **for a one-time achievement it is unrecoverable.**

Reachability is low — the upstream actions (market sell, printer use, crate open, block
placement) are themselves sandboxed, so `record`/`tryAward` rarely fire in a disabled world.
But `MiniService.mintGraded` and the admin give paths can reach `tryAward` without a world
check. **Fix is small:** check the sandbox before committing the claim, or grant first and
claim only on success.

---

## 4. Brief leads #3, #4, #5, #8, #9 — the numbers

**#3 Pity.** 25 tokens = **25 crates**. Reachable in ~1.5 active days or ~3 casual ones. Two
problems beyond the price:

- **It rolls uniformly, not by rarity.** `pool.get(rnd.nextInt(pool.size()))` over every
  RARE+ Mini — a Legendary is exactly as likely as a Rare. `MiniService.pickByRarity` exists
  and is used by crates and wild drops. **This is a bug**, and with the shipped catalog
  (whose only RARE+ entry is Golden Idol, cap 5) it means 25 tokens buys a *guaranteed*
  Legendary until five exist, after which pity is permanently dead content.
- **There is no pity *counter*.** The brief's lead asks about "pity counters that fail to
  reset" — pity is a flat exchange with no accumulation and nothing to reset. Nothing to fix;
  flagging so it isn't scoped as a repair.

**#4 Achievements.** Only `rich_10k` has a threshold, at **$10,000**. Against the ~$200
session you mentioned that is ~50 sessions; against the $5,000/day cap it is ~2 days. The
cap is a ceiling, not a rate, so your read stands — but note the gap itself is a finding:
**the daily limit is set roughly 25x above the actual earn rate**, so it isn't doing any
work. The other five achievements are event-triggered and fine in shape.

**#5 Quest pool — how much is sell-based.** Six quests, and by reward weight:

| Period | Quest | Type | Target | Reward |
|---|---|---|---|---|
| Daily | `sell_daily` | SELL_MARKET | $500 | 2 |
| Daily | `crate_daily` | OPEN_CRATE | 1 | 1 |
| Daily | `print_daily` | PRINT_MINI | 1 | 2 |
| Weekly | `sell_weekly` | SELL_MARKET | $5,000 | 8 |
| Weekly | `crate_weekly` | OPEN_CRATE | 10 | 6 |
| Weekly | `pack_weekly` | OPEN_PACK | 3 | 6 |

**Sell-based is 2 of 6 quests but 10 of 25 reward tokens — 40% by value**, and it is the
single largest line in both periods. `sell_weekly` at $5,000 is exactly one full day at the
cap, or ~25 sessions at $200.

Worth noting separately: **`crate_weekly` pays 6 tokens for 10 crates**, which cost 10
tokens. It is a net loss of 4 tokens that the player cannot see.

All five `QuestType` values are correctly wired to hooks. **Nine of the eleven verbs Part 2
proposes have no hook at all** — fish, crops, distance, hostiles, breeding, cooking,
smelting, villager trades, new biomes. Only `print a Mini` and `open a pack` exist today.
Most map to vanilla statistics, which is the cheap way in (and is the same snapshot-and-diff
mechanism Part 6 needs, so they share machinery).

**#8 Pack pool.** Shipped: `starter` $250 for 3 cards (piggy 50 / chick 50); `premium` $750
for 3 (piggy 45 / chick 45 / golden_idol 1). **No low-tier-dominant entry in the jar.** The
cobblestone-dominant pool from the old summary is in *your authored config* — packs are
GUI-built via `/hcm packs` → `PackService.save()` → `writeConfig("packs", …)`. So: refuted
for the shipped defaults, **unconfirmable for yours** without the live file. Good news
either way — it's content, not code.

**#9 Lotto.** $250 ticket; payouts 0 / 100 / 500 / 2000 at weights 50 / 30 / 15 / 5.

> EV = (0·50 + 100·30 + 500·15 + 2000·5) / 100 = **$205** → **18% house edge, 50% nothing.**

No cooldown, no daily cap. It's EV-negative so it self-limits, but a rich player can scratch
indefinitely. Note it is a **money** sink, not a token sink — so it does nothing for the
problem in §2.

---

## 5. Dead and near-dead config

| Key | State |
|---|---|
| `arcade.machines.{crate,scratch,pity,counter}` | Retired as obtainable in v13; still parsed and still styling already-placed blocks. Four `CustomBlockType`s + handlers remain. |
| `arcade.tokens.login_streak.reward_per_day` | Only read when `rewards` is empty. Vestigial but documented as a fallback. |
| `QuestType.SCRATCH` | Wired (`ArcadeService:477`) but **no shipped quest uses it**. |
| `MiniService.mintWild` | **No callers. Dead code.** |

---

## 6. Brief lead — minting outside the single pipeline

The javadoc claims "Every mint path ends here or in `mintGraded`; both share the same tally +
provenance pipeline." **That is not true — there are three implementations**, each
independently doing `dao.mintNext` + `dao.recordIndividual`:

| Method | Used by | Cap-checked? |
|---|---|---|
| `mintItem` | `mintFound` (wild drops, crates), `NaturalSpawnService` | yes |
| `mintGraded` | `PrinterService` | yes |
| `mintInternal` | `giveAdmin`, and dead `mintWild` | **no** |

`giveAdmin` is documented as "no charge and **no cap check**". That's a deliberate admin
escape hatch, but §11 #3 makes finite mint a hard requirement and the Museum publishes
"minted X / cap" as a fact — so an admin give can put the ledger past its own published cap
with no warning. **Not a dupe risk, and not urgent**, but I'd add a warning log at minimum.

`mintInternal` also hardcodes Standard/non-shiny (the 4-arg `items.minted` overload), so
admin-given Minis are always Standard. Probably intended; noting it because it isn't written
down anywhere.

---

## 7. Part 6's prerequisite — there is no Site launcher

§2.2 is explicit:

> design the PC's root menu as a **Site launcher** (a grid/list of Sites) rather than opening
> the store directly, so new Sites slot in without reworking the PC.

**The PC opens `StoreMenu` directly** (`CustomBlockListener:234`). The Store then acts as the
de-facto hub — its own source comment says "the Store is the hub" — with the other features
reached from its bottom navigation row.

That row is **slots 45–53, and all nine are used**: prev-page, balance, Instant Market,
Marketplace, Card Packs, Museum, Mailbox & Orders, Sort, next-page.

So "add Deliveries as a Site" is not a tile addition — **there is no free slot.** Options:

1. **Build the launcher §2.2 actually specifies.** PC root becomes a Site grid; Store becomes
   one Site among several. Clean, matches the design, and every future feature stops
   competing for the Store's nav row. Costs a GUI refactor and re-teaches navigation.
2. Overflow the Store nav into a "More" tile. Cheap, and directly against §2.2's stated
   reason for existing.

I'd recommend (1) as its own small PR *before* Deliveries, rather than smuggling it into the
Deliveries PR. **This is the biggest unpriced item in the brief and I'd like your call.**

Related, and relevant to Part 2's GUI-first note: **the Arcade and Quests are not reachable
from the PC at all.** They're behind the Arcade hub block — plus `/hcm arcade` and
`/hcm quests`, which are **player-facing commands with no permission check**. §3.2 says
"`/hcm ...` commands are **admin-only**" and players "never type market commands", and §3.9
wants the Arcade to be a *destination* that makes a big pull an event. A command that opens
it from anywhere undercuts both. `/hcm auction`, `/hcm museum`, `/hcm balance` and
`/hcm tokens` are in the same position.

---

## 8. Part 6 — Statistic verification, and two gaps

**Verified against Paper's `org.bukkit.Statistic`** (fetched from source, since this
environment cannot compile — see §10). **All fourteen constants in your table exist:**

`WALK_ONE_CM` · `SPRINT_ONE_CM` · `CROUCH_ONE_CM` · `SWIM_ONE_CM` · `WALK_ON_WATER_ONE_CM` ·
`WALK_UNDER_WATER_ONE_CM` · `CLIMB_ONE_CM` · `HORSE_ONE_CM` · `STRIDER_ONE_CM` ·
`PIG_ONE_CM` · `BOAT_ONE_CM` · `MINECART_ONE_CM` · `AVIATE_ONE_CM` · `FLY_ONE_CM` ✅

They are also **mutually exclusive** in vanilla's movement accounting (swim → water → climb →
ground{sprint/crouch/walk} → aviate → fly), so the weighted blend has no double-counting.

**Two movement statistics are missing from your table:**

- **`HAPPY_GHAST_ONE_CM`** — the Happy Ghast mount. **This one matters.** A player who flies
  the whole route on a Happy Ghast accumulates cm in *no group*, so their tracked movement is
  ~0, they trip the 70% teleport floor, and they get 20% — for using a legitimate vanilla
  mount. Needs an explicit multiplier; I'd put it with Elytra (0.45) or below.
- **`NAUTILUS_ONE_CM`** — same shape, lower stakes.

`FALL_ONE_CM` also exists and should stay ungrouped (falling isn't travel).

**Consequent design point:** the denominator for the weighted blend must be **the sum of the
grouped statistics only**, not every movement stat — otherwise any future ungrouped stat
silently dilutes every multiplier. Worth writing into DESIGN.md so it can't drift.

### Re-deriving `base` and `perBlock`

Your bands and caps give, per day, all on foot:

| Band | Jobs | Typical distance | Subtotal |
|---|---|---|---|
| Local 200–600 | 2 | ~400 | 800 |
| Regional 600–1,800 | 2 | ~1,200 | 2,400 |
| Long haul 1,800–4,000 | 1 | ~2,900 | 2,900 |
| | **5** | | **6,100 blocks** |

With `travelFee = (base + perBlock · distance) · mult` and mult = 1.00:

> **daily total = 5·base + 6,100·perBlock**

Setting `base = 5` (a small "you showed up" component) and solving for a target `T`:

| Realistic day's income | 15% target `T` | Implied `perBlock` |
|---|---|---|
| $300 | $45 | **0.0033** |
| $500 | $75 | 0.0115 |
| $1,000 | $150 | 0.0205 |
| $2,000 | $300 | **0.0451** |

**Your old constant was `perBlock = 0.045`** — which, read backwards, assumes a realistic day
of about **$2,000**. Your earlier note said a session earns about $200. Those two are a
factor of ten apart, and the answer changes every number in Part 6.

**This is the one input I cannot measure and the thing I most need from you: what does a
realistic day actually earn on the server now?** If it's ~$300, deliveries should pay ~$45/day
and a long haul is worth about $15 — which I'd argue is too small to be worth the walk, and
the honest conclusion would be that 10–15% is the wrong target rather than that the walk is
overpaid.

### Other Part 6 notes

- **"Reuse the existing daily-reset scheduler"** — **there isn't one.** `DailySellDao`,
  `DailyBuyDao` and `QuestService.periodKey` all key rows by **UTC epoch-day computed at read
  time**. That's better than a scheduler (nothing to miss, nothing to restart) and Deliveries
  should reuse *that pattern*. One wart to avoid inheriting: `QuestService` weeklies key on
  `epochDay / 7`, and epoch day 0 was a Thursday — so weekly quests roll over **Thursday
  00:00 UTC**.
- **The name `deliveries` is taken.** `marketplace/DeliveryService`, `storage/DeliveryDao` and
  the SQL table `deliveries` (schema v12) are the Mailbox/Locker queue. The new module needs a
  different package, service and table name. Suggestions: `haulage`, `freight`, `runs`.
  Needs your call since it becomes a config section and a command.
- **`ProtectionService` is ready** — reflection-based Towny + WorldGuard, degrades to allow,
  already has the claimed-land check waypoint generation needs.

---

## 9. Part 5 — where labour income comes from

Agreed on dropping the floor-price idea; §3.1's lean-market rule is explicit that
farmable/crafted goods stay out of the house catalog precisely to avoid money faucets.

**On "if bread isn't selling in the Marketplace, that's a discovery or fee problem" — it is a
discovery problem, and a concrete one.** The Marketplace GUI (`MarketplaceMenu`) has:

- department tabs ✅
- pagination ✅
- **no search**
- **no sort** — not by price, not by recency, not by seller

The Store has a sort button (`Departments.sortButton`). The Marketplace does not. So a buyer
who wants bread opens Food and pages through hoping to spot it, with listings in whatever
order `listActive` returns. That is survivable with a handful of listings and unusable at a
hundred — and it's worse for exactly the long-tail crafted goods §3.1 pushes into the
Marketplace.

The fee is **8% commission** (`marketplace.fee.commission_percent`), storage fee off. 8% is
not what's stopping bread from selling.

**Proposal for Part 5, cheapest first:**
1. **Sort + search in the Marketplace.** Mirror the Store's sort control; add a name filter
   via the existing `ChatPromptService`. Small, and it's the actual blocker.
2. **A "wanted" signal** — let a buyer register interest in a material so sellers can see
   demand. Larger; propose separately.
3. Deliveries (Part 6) as the controlled faucet, as you have it.

I do not see a third path that respects the lean-market rule without inventing a new faucet.

---

## 10. Constraints on this work

**This environment cannot compile the project.** No JDK 25 is installed, `api.foojay.io` is
blocked so Gradle cannot provision one, and `repo.papermc.io` is blocked by policy so
`paper-api` cannot be resolved. Every build must go through CI.

Practical consequence: **no local verification for any part of this brief.** Unit tests that
don't touch Bukkit can't run either, since the test classpath needs `paper-api`. I verified
the `Statistic` enum by reading Paper's source from GitHub rather than compiling against it.
CI will catch a bad constant as a compile error, so correctness is still provable — just only
after a push.

**Still missing: the live `config.yml`.** The pack pool (#8), your real catalog, your
`minis.loot.sources` drop rates, and the realistic-income figure all live there.

---

## 11. Decisions I need

1. **The Site launcher (§7).** Build it properly as its own PR before Deliveries, or overflow
   the Store nav row? This is the biggest unpriced item in the brief.
2. **What does a realistic day earn?** (§8) Everything in Part 6 keys off it, and your old
   constant implies $2,000/day against your earlier note of $200/session.
3. **Module name** to replace `deliveries`.
4. **Rewrite §3.9 as part of Part 1?** It contradicts §11 #9 and the code in three places and
   is where the previous bad brief came from.
5. **`/hcm arcade` and `/hcm quests` as player commands** — §3.2 says admin-only and §3.9
   wants the Arcade to be a destination. Retire them to admin, or keep the convenience?
6. **Can you attach the live `config.yml`?**

## 12. Proposed order

Unchanged from your brief, with one insertion:

Part 1 tokens (+ §3.9 rewrite) → Part 2 namespace & quests → Part 3 packs → Part 4 hunt
tuning → **Site launcher** → Part 6 Deliveries.

The launcher lands late because nothing before it needs one, and immediately before the first
feature that does.
