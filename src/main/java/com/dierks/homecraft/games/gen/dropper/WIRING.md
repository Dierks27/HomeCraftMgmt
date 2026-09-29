# The Dropper: wiring the pure package (WP-D)

This package (`games/gen/dropper`) is the Dropper's pure half: the physics, the planner, the proof and
the course encoding (EVENTS-DROPPER-SPEC §B.1.3-B.1.6 and the pure parts of §B.1.7-B.1.8). It has no
Bukkit types and registers nothing. Nothing outside it knows it exists yet. This note lists what WP-D
changes outside the package to switch it on, in the order that keeps every step shippable.

**Order matters.** Do not register the planner before `TrialKind.DROPPER` exists. Until then the
planner writes its rows with `TrialKind.PARKOUR` as a stand-in (`DropperPlanner.KIND`), and
Time Trials would play a dropper as parkour.

## 1. Contracts (C1)

- **`gen/api/Palette`**
  - Add `minecraft:glass`, the stained glass `red`, `orange`, `yellow`, `blue`, `purple`, `pink` and
    `light_blue`, and `minecraft:sea_lantern` to `ALLOWED`. This is exactly `DropBlocks.PENDING_C1`.
  - Add `POOL_WATER = Set.of("minecraft:water[level=0]")` as a separate set, never inside `ALLOWED`.
  - Then point `DropBlocks.PENDING_C1` and `DropBlocks.POOL_WATER` at Palette's sets, or delete
    them and use Palette's. `DropBlocksTest` keeps checking the rule either way.
- **`trial/TrialKind`**
  - Add `DROPPER("dropper", "Dropper", 80, 2.5, GAMES_DROPPER, rules)`.
  - `DropperPlanner.KIND` then picks it up by itself through `TrialKind.of("dropper")`.
  - The course kind is part of the plan hash. **Re-pin `DropperPlannerTest`'s golden hashes** in the
    same commit. Keep `ALGO = 1`, because no dropper has been built yet.

## 2. Slots and config

- **`gen/api/Slots`**: move the three `Def`s from `DropperSlots` into `Slots`, then delete
  `DropperSlots` or make it an alias.
  - `EASY` and `DROPPER_SLOT` go in `ALL`, and `CLASSIC` goes in `CLASSICS`.
  - Add the generator id `DROPPER = "dropper"`.
  - Add `Def.dropper()`. `tierProblem` gives a dropper the mix rule `DropRules.mixProblem` (1-5 of
    E, M and H). `normalise` upper-cases a dropper's mix, as it does golf's.
  - `classicFor` maps a dropper to `fresh_classic_dropper`, and `classicByWord("dropper")` does too.
  - The halves are 64 × 64 × 16 at x 5376, y 160, z 4096 / 4160 / 4224. That is inside the keep plot
    size, so `KeepArea` doesn't move.
- **`gen/api/CourseCode`**: add `EDROP` (`fresh_dropper_easy`) and `DROP` (`fresh_dropper`).
- **`gen/api/GenCopy`**: the slot names and "This week's courses" lines.
- **`gen/{DailyCourses, DailySettings}`** and `config.yml` (`games.fresh`):
  - Add the slot rows, shipped **off**.
  - The first-finish tokens are daily 1 / 2 and weekly 2 / 3 (`DropperSlots`' `dailyClear` and
    `weeklyClear`).
  - Register `new DropperPlanner()` in `DailyCourses.planners()`.
- **`gen/engine/GenService`**: add `WORK.put("dropper", 300_000L)`, which is
  `DropperPlanner.WORK_BUDGET`. The planner splits the budget evenly across the levels and counts
  simulated ticks, so every host makes the same plan. A typical plan uses 4k-17k.
- **Admin mix**: `/hcm games gen mix fresh_dropper EEMHH` goes through `tierProblem`, which is
  `DropRules.mixProblem`.

## 3. The engine

- **`PlanCheck`**: it lints with `Palette.problems`, so glass and water fail there until C1.
  - After C1, admit `POOL_WATER` **only when `def.generator()` is `dropper`**.
  - `PaletteTest` pins that every other generator still refuses water.
  - A moved dropper plan (recall or keep) gets `DropperValidator.problems(plan)` as well. That call
    reads the mix back from the plan's own pools (`DropMarks.mix`), so it needs no slot.
- **`BuildJob`**: write fluids last and remove them first, in three global stages per pass (§B.1.9).
  - Fluid ops are the palette entries where `DropBlocks.isWater` is true.
  - Every pool is sealed (validator rule 4), so water written with physics off never moves.
- **`GenRegionGuard`**: add `FLOW_OUT`, which cancels a `BlockFromToEvent` whose **source** is in an
  area.
- **`LiveProof.structure`**: for a `DROPPER` course, check `DropMarks.probes(course)`. Each probe is
  either a solid block under a ledge or water at a pool's surface centre.

## 4. Time Trials (`trial/*`)

- **`trial/DropperLayout`** is a thin face on `DropMarks`, so the planner, the validator and the
  game read one encoding:
  - `levels`, `levelOf`, `isPool`, `backTo`, `currentLevel`, `poolBox`, `inPool`, `facing`;
  - `problems(course)`, which joins `Course.problems` for a `DROPPER` row.
- **`trial/DropperRules`** holds the bonk and splash rules (pure, WP-D). A splash is the first move
  segment that enters `DropMarks.poolBox(mark)`. The validator's pool-box rule makes every block of
  that box water.
- **`FairPlay`**: the course already carries `minSeconds = DropRules.minSeconds(mix)` (90% of the
  walk-off falls, rounded down).
- **Stars**: `PlannedTrial.refMs` is `DropRules.refMs(mix)`. The row's tier is `DropRules.tier(mix)`
  (the rounded mean), so `Stars.threshold(refMs, factor)` uses that tier's factor. EEE gives a
  9.8 s reference (gold 19.6 s, shown as 20 s), and EEMMH gives 17.4 s (gold 26.1 s, shown as 27 s).
- **The hop** to the next ledge faces `DropMarks.facing(ledge, pool)`, because a checkpoint mark
  carries no yaw.

## 5. The practice drop (owner decision D3)

The owner asked for "one untimed drop before the timed drop, skippable" (EVENTS-OWNER-DECISIONS D3;
EVENTS-RECONCILED §4). The judged spec had cut it, and the reconciled plan puts it back. It is wiring
only: the pure package already has every piece it needs.

- **Offer**
  - Starting a dropper offers two buttons, with the words in the item NAMES for Bedrock:
    "Practice drop (not timed)" and "Go straight to the timed run".
  - It shows only when `games.trials.warmup_seconds > 0`, the same switch as the other warm-ups
    (0 turns it off).
  - A run gets at most one practice drop.
- **The drop**
  - The player goes to level 1's ledge, `DropMarks.ledgeOf(course, 0)`, facing
    `DropMarks.facing(ledge, pool)`. There is no countdown.
  - The action bar reads "Practice drop - not counted".
- **The end**
  - The practice ends on the first splash into `DropMarks.poolOf(course, 0)` (by
    `DropMarks.inPool`), on the first bonk, or on the kit item "Start timed run".
  - Then the player goes back to level 1's ledge, and the normal on-foot 3-2-1 countdown starts
    the timed run.
- **Never counted.** A practice drop is never timed, submitted, paid or counted for the Weekly Cup.
  Its bonks don't count toward the result's "Bonks: n", and it can't earn `game_dropper_clean`.
- **Tests (WP-D):**
  - the practice drop never submits or pays;
  - the timed run after it counts normally, with the clock starting at Go;
  - "Go straight" skips it;
  - it is offered at most once per run;
  - with `warmup_seconds: 0` it isn't offered.

## 6. What this package already guarantees (the tests that pin it)

| Guarantee | Test |
|---|---|
| Vanilla's air physics, every number of §B.1.4 | `DropSimTest` |
| The tier table and its relations (the opening fits the hitbox + 2r, r is more than a tick of drift) | `DropRulesTest` |
| 2,000 seeds each of EEE, EEMMH and HHHHH plan and pass the validator; golden hashes; ops ≤ 20k inside the half | `DropperPlannerTest` |
| Every rule of §B.1.6 fails a hand-made bad plan | `DropperValidatorTest` |
| The pilots: a straight stack passes, a plate fails, reaction delays, swept corner clips | `DropPilotTest` |
| The course encoding: alternation, `backTo`, the pool box, the rim never reaches a pool mark | `DropMarksTest` |
