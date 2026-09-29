# The Dropper: wiring the pure package (WP-D)

This package (`games/gen/dropper`) is the Dropper's pure half: the physics, the planner, the proof and
the course encoding (EVENTS-DROPPER-SPEC §B.1.3-B.1.6 and the pure parts of §B.1.7-B.1.8). It has no
Bukkit types and registers nothing. Nothing outside it knows it exists yet. This note lists what WP-D
changes outside the package to switch it on, in the order that keeps every step shippable.

**Order matters.** Do not register the planner before `TrialKind.DROPPER` exists. Until then the
planner writes its rows with `TrialKind.PARKOUR` as a stand-in (`DropperPlanner.KIND`), and
Time Trials would play a dropper as parkour.

## 1. Contracts (C1)

**Done in C1 (games/ev):** everything in this section. Glass, the stained glass colours and the sea
lantern are in `Palette.ALLOWED` (`Palette.GLASS_AND_LIGHTS`), `Palette.POOL_WATER` is its own set,
and `DropBlocks.PENDING_C1` / `DropBlocks.POOL_WATER` now point at them. `TrialKind.DROPPER` exists
(refused by `/hcm games course create`, icon WATER_BUCKET), so `DropperPlanner.KIND` is DROPPER and
the golden hashes are re-pinned (ALGO stays 1). `LiveProof.structure(course, solid, water)` already
checks `DropMarks.probes` for a dropper; WP-D only has to pass it a water predicate from
`GenService.structure`. `TrialRun` has the warm-up flags the practice drop uses (§5), and
`games.trials.warmup_seconds` (180) is parsed (`TimeTrialsSettings.warmupSeconds()`, `warmupsOn()`).
The slot rows (§2) are still WP-D's: they come with their config rows and the planner's registration.

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
  simulated ticks, so every host makes the same plan. A typical plan uses about 18k (EEE) to 45k
  (HHHHH), most of it the pilots (§7).
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

- **`FairPlay`: skip the ledge legs, first** (§B.1.7, the `DROPPER` kind only). Without this,
  every run of two or more levels is voided:
  - The hop from pool i to ledge i+1 is the run's own teleport, 5 ticks after the splash. The gap
    between the two spheres is about 31 blocks on Easy and about 46 on Hard, covered in about
    0.3 s. That is over 100 blocks a second, and `TrialKind.DROPPER`'s `maxSpeed` is 80.
  - So `tooFast` skips every leg that **ends at a ledge mark** (a target where
    `DropMarks.isPool(index)` is false). The legs that end at a pool are real falls and stay
    checked.
  - `fallY` is always the course's own.
  - `minSeconds` is already on the course: `DropRules.minSeconds(mix)` (90% of the walk-off falls,
    rounded down).
  - `movement()` is unchanged: a changed gravity or safe-fall attribute voids the run, and so does
    any potion, slow falling included.
  - `FairPlayTest`: dropper teleport legs are skipped, `minSeconds` holds, and the other kinds are
    unchanged.
- **Collisions** (§B.1.7): `setCollidable(false)` for the run's player, so two fallers in one shaft
  can't push each other.
  - Restore it on **every** end path: `end()` (finish, leave, void, stale) and session end (quit,
    kick, shutdown). It isn't saved with the player, so a crash can't leave it stuck.
  - TimeTrials test: `setCollidable` is restored on every end path.
- **`trial/DropperLayout`** is a thin face on `DropMarks`, so the planner, the validator and the
  game read one encoding:
  - `levels`, `levelOf`, `isPool`, `backTo`, `currentLevel`, `poolBox`, `inPool`, `facing`;
  - `problems(course)`, which joins `Course.problems` for a `DROPPER` row.
- **`trial/DropperRules`** holds the bonk and splash rules (pure, WP-D). A splash is the first move
  segment that enters `DropMarks.poolBox(mark)`. The validator's pool-box rule makes every block of
  that box water. Bonks are ignored during the hop.
- **`TimeTrials` and `TrialRun`**: the splash title, the hop (`setVelocity(0)`,
  `setFallDistance(0)`, `progress.jump(ledge)`), the bonk's `sendBack`, the countdown on level 1
  only, and the clock line "&e0:12.4 &7· level 2 of 5 · 1 bonk". A run keeps its `Course` snapshot,
  so a run in progress at the weekly flip still counts (still standing).
- **`CourseAdmin`** (§B.1.2): the create refusal.
  - `/hcm games course create <id> dropper` is refused with "Droppers are made by Fresh Courses;
    keep one to make it permanent."
  - A kept dropper is an ordinary trials row: `info`, `tp`, `test`, `name`, `tier`, `enable` and
    `feature` work on it, and geometry edits are refused.
  - `CourseAdminTest`: a hand-made dropper is refused; a kept dropper can be renamed; geometry edits
    are refused.
- **`TrialText` and the menus** (§B.1.1, §B.1.8), the wording:
  - the rules lines and the WATER_BUCKET icon;
  - CourseMenu, ResultMenu and CourseListMenu say "levels" for a dropper;
  - the result reads "No bonks - perfect drop!" or "Bonks: 2";
  - the FreshMenu tiles, and the course code in the item NAME ("Course code DROP-12") for Bedrock;
  - `GenCopyTest`/`TrialTextTest`: "level 2 of 5"; sign lines at most 15 ASCII.
- **E4**: a counted finish is `FINISH_COURSE` and its stars `EARN_STARS`, through the trials path as
  for any trial. Add the achievement `game_dropper_clean`, "Reach the bottom of a Dropper with no
  bonks" (20), with E4's backfill caution. Test, void and stale runs never count, and neither does a
  practice drop (§5).
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
| Vanilla's physics: the air, and a walk-off's two ledge ticks (a jump-off's one); every number of §B.1.4, the coast corrected (§7) | `DropSimTest` |
| The tier table and its relations (the opening fits the hitbox + 2r, r is more than a tick of drift) | `DropRulesTest` |
| 2,000 seeds each of EEE, EEMMH and HHHHH plan and pass the validator; golden hashes; ops ≤ 20k inside the half | `DropperPlannerTest` |
| Pilots leaving between the sampled starts (halfway exits, both ends and the middles of the step) still reach the water with r/4 to spare | `DropperPlannerTest` |
| Every rule of §B.1.6 fails a hand-made bad plan | `DropperValidatorTest` |
| The pilots: every 0.3 blocks of the edge and the whole walking step, a straight stack passes, a plate fails, reaction delays, they settle over a target instead of swinging across it, swept corner clips | `DropPilotTest` |
| The course encoding: alternation, `backTo`, the pool box, the rim never reaches a pool mark | `DropMarksTest` |

## 7. Where this differs from the spec (for the owner and the docs)

- **The coast row of §B.1.4 is wrong.** Vanilla decides "on the ground" at the end of each move, so a
  walk-off is two ground ticks (the last tick over the ledge and the edge tick after it) with ground
  acceleration (0.098) and ground drag (0.546). A walker leaves the edge with 0.118 blocks a tick,
  not 0.198, and drifts about **1.28** blocks once it lets go, not 2.19. A jump-off is one ground
  tick with the jump's 0.42. The fall times (32/37/40 and 38/42/46 ticks), the top air speeds, the
  stop and the reaches are unchanged. `DropSim` models the ledge ticks and `DropSimTest` pins them.
- **More pilots than 33.** A child leaves from anywhere along the edge and at any moment of a walking
  step, and a proof flown from one moment passed levels that bonked a pilot stepping off 0.02 blocks
  later. The pilots now leave every 0.3 blocks along the edge (9 exits) and at three moments of the
  step (0.072 blocks apart), walking or jumping, at each of the tier's 3 delays, plus the 3 sloppy
  runs at each moment: **171 a level**.
- **The pilot settles over its target** (its deadband is now 0.12; it was 0.1). One push moves
  where its drift ends by 0.218. With a band under half of that, a pilot could swing across its
  target every tick, and which key it held while passing a layer was a coin toss. At 0.12 it
  settles.
- **What the sampling leaves.** Flown from a 17 × 20 grid of starts on 200 plans of each mix, no
  pilot's hitbox grown by r/4 touches a block. A few graze the r/2 clearance between the sampled
  starts (about 3% of Medium and Hard levels). The robustness proof is still a sampled one, as
  §B.1.6 says.
