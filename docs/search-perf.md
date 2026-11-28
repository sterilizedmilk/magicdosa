# Search simulation cost (phase 3, 2026-11-25)

Question (planning, 2026-11-25): at about 65 ms per simulation, the 1x budget (12 per candidate) costs about 3 s per
decision and about 8 min per Izzet-mirror game, 4x more than 30 min. Where does a simulation's time go, and what can be
made cheaper without changing what a search computes? Nothing here changes a search result: every change keeps the
same visits and root values for the same root, seed and budget.

## Method

- **Fixed root set:** the six B=12 calibration logs (Izzet mirror, search policy vs Forge AI, reproducible mode, main
  `fa7e160`). `SearchReplay` (agent-core test sources, package `magicdosa.agent.shadow`) plays a log into a
  `LiveShadow` with the search policy, as `LiveReplay` does for the shadow Forge AI policy, so each recorded agent-seat
  request is searched again from the shadow's state. The shadow follows the recorded events whatever the search
  answers, so the same code, logs and budget give the same searches. Root set "R30": the first 30 agent-seat
  decisions of each of the six logs, 12 per candidate x1.
- **Identical-search check:** `-Dmagicdosa.search.debug=true` prints every search's budget, visits and root value
  (the mean backed-up value, a double: it differs if any rollout plays differently). Before and after a change the
  lines must be equal.
- **Timing:** `-Dmagicdosa.timing=true` (the `search.*` parts and histograms), GC time from the JVM's collectors.
- **Profile:** JFR (`settings=profile`), execution samples of the search thread attributed by stack: copy
  (`ForgeSearchGame.determinize`), leaf evaluation (`ForgeSearchGame.value`), and inside the rollout by the innermost
  marker frame: Forge AI (`AiController`, `PlayerControllerAi`, `AiAttackController`, `AiBlockController`,
  `forge.ai.ability`, `ComputerUtil`), decision layer (any `magicdosa.*` frame), else game rules
  (`PhaseHandler.mainLoopStep`).

Command (forge-gui as the working directory; the classpath is agent-core's test classpath):

```
java -Xmx2g -Dmagicdosa.reproducible=true -Dmagicdosa.timing=true -Dmagicdosa.search.debug=true \
  -cp <agent-core test classpath> magicdosa.agent.shadow.SearchReplay 12 1 30 <logs>/calib12*-g*.pb.gz
```

## Breakdown before any change (first profile, CPU shared with the pilot run)

Profile of 12 searches, 504 simulations (two logs, first 25 decisions each). Shares of the search thread's samples
(2399); the ms column is the `-Dmagicdosa.timing` measurement of the same run (CPU shared, not final).

| Part | Share | Notes |
| --- | --- | --- |
| Copy (`GameCopier` + deal) | 8.4% | 11.7 ms/sim; almost all `CardFactory.getCard` (card script read + abilities built) per card |
| Rollout: decision layer | ~52% | see below |
| Rollout: Forge AI thinking | ~9% | priority 1.25 ms per answer, ~24 answers per simulation; combat declarations ~3 ms |
| Rollout: game rules | ~21% | state-based checks, stack resolution, phase begin; `CardType.toMixedCase` via `CardState.replacementFlags` -> `isSaga` alone 6.2% of all search samples |
| Leaf evaluation | 7.7% | 11.7 ms per leaf: `GameStateEvaluator` is called for each seat, and each call copies the game (Forge's `GameCopier`, hidden cards pruned for that seat) and plays the coming combat |
| GC | ~0.7% of wall time | 537 ms of 78 s |

Decision layer, of all search samples:

| Piece | Share | What it is for |
| --- | --- | --- |
| `legalActions` (candidates) | 18.9% | Forge AI's answer is mapped onto these candidates, and an empty list passes without asking Forge AI: needed |
| of which `payableWithMoreMana` | 7.1% | potential casts: the observation, and whether the mana-only filter applies |
| `syncZones` (IDs, events, reveals, facts) | 16.9% | IDs (`EntityIds.sync`, 3.4%) are needed; the rest (reveal loop, `revealFacts` 7.6%, facts) feeds only the event stream and observations, which a copy's client discards |
| request observation (`ObservationBuilder`) | 4.4% | read only by a tree lookup (information key) |
| search client: information key and action keys | 3.0% | read only by a tree lookup |
| turn-start snapshots, privileged records | ~1% | discarded |

Out of the tree (after the walk's one expansion) every decision of the simulation is answered by Forge AI, and
nearly all decisions of a simulation are out of the tree.

## Changes (each keeps every search of R30 identical: same visits and root values)

ms per simulation on R30 (43 searches, 1560 simulations). The CPU was shared with the phase-3 pilot and later with
another search game, so only runs made back to back compare; the final idle-CPU numbers are below.

| Commit | Change | Before | After |
| --- | --- | --- | --- |
| `f98cb36` | The copy's session goes quiet once the walk leaves the tree (`ForgeGameSession.quiet()`, `Chooser.outOfTree()`): no events, reveals, facts, snapshots, observations, privileged records, information or action keys; entity, ability and trigger IDs as before | 112.0 | 84.6 (paired run) |
| `51607eb` | A quiet session stops the potential-cast check at the first one found (only "is there one" is read: the mana-only filter) | 75.2 | 72.5 / 73.5 (two runs) |

Tried and dropped: a `BitSet` instead of a `HashSet` of present card IDs in `EntityIds.sync`. Identical results, but
slower (Forge card IDs are large, so each sync allocated a large bit set).

## Breakdown after the changes (JFR, R30, 5857 search samples)

| Part | Share |
| --- | --- |
| Candidates (`legalActions`: Forge's `getSpellAbilities`, `canPlay`, cost checks) | 25.9% |
| Game rules (state-based checks incl. `checkStaticAbilities` 8.9%, stack resolution, phase begin, LKI copies) | ~25% |
| Casting and activating (`PlaySpellAbility`, Forge AI's own play) | ~11% |
| Copy (`CardFactory.getCard` per card: 9.3%) | 9.5% |
| Forge AI thinking (priority, combat) | 8.3% |
| `syncZones` (entity IDs on every event) | 6.6% |
| Leaf evaluation (two Forge copies + two combat simulations) | 3.3-3.9% |
| Request building and the search client | 2.7% |

What is left is mostly Forge's own work. The parts that could still go would change results, so they are design
questions, not done here:

- **Plain Forge AI controllers in the rollout.** The decision layer makes mana as priority actions (no auto-tap) and
  maps Forge AI's choice onto its candidates; a plain `PlayerControllerAi` taps while it pays and asks itself, so
  rollouts would play differently. Removes the candidate building (about a quarter of a simulation) and the rest of
  the layer. Held until the user decides (2026-11-25).
- **One evaluation copy instead of two.** `GameStateEvaluator` copies the game per seat with that seat's hidden cards
  pruned (`PRUNE_HIDDEN_INFO`) and plays the coming combat on each; one shared copy gives different scores. Kept as
  it is (2026-11-25).

## Final numbers (2026-11-25 evening, 12 logical CPUs at about 8% load: one other search game running)

R30, original code (`97dcbc1`) and this branch run alternately, two runs each. All four print the same 43 searches
(visits and root values) as the first baseline. ms per simulation, all in, is `search.decision` / simulations (copy,
play and evaluation).

| | Run 1 | Run 2 | All in (ms/sim) | Play only (`search.simulation`) | Copy | Leaf |
| --- | --- | --- | --- | --- | --- | --- |
| Before | 116.6 s | 116.8 s | 70.4 | 63.1 | 7.2 | 2.6 |
| After | 95.0 s | 94.6 s | 56.4 | 49.1 | 7.3 | 2.6 |

20% less per simulation. Projection for an Izzet-mirror game (about 147 searched decisions, about 50 simulations per
decision at 1x): 1x about 6.9 min of search per game (8.6 before), 4x about 28 min (34.5 before). The design question
above (plain Forge AI controllers in the rollout) is the remaining large lever: the candidate building and the rest of
the layer are about 35% of what is left (estimate from the profile, not measured).

Sequential determinism harness on this branch (`ParallelDeterminismHarness`, IzzetProwess vs EsperBlink, 10
matches, seed 5000): `different=0`. Quiet mode is reached only from a search copy, so real games and the shadow are
unchanged.

After rebasing on main `e0e4631` (a copy deals the library cards the root seat sees where the shadow has them now):
main plus the bench and this branch again print the same 43 searches (two runs each); all in 71.8 -> 56.3 ms per
simulation. agent-core tests: 36 run, 0 failures.

## Plain Forge AI in the rollout (2026-11-26, user decision: early stage only)

`-Dmagicdosa.search.rollout=plain-forge-ai` (the default) against `layer` (the decision layer delegating, as before),
on the same R30 root set and code. With `layer` the 43 searches are identical to main `93ce0c9` (visits and root
values). Each policy repeats exactly: three runs of `plain-forge-ai` print the same 47 searches, three of `layer` the
same 43.

The two policies search different root sets (43 and 47): `SearchReplay`'s shadow skips search for the rest of a turn
once the search's answer leads it away from the log, and the answers differ. They are compared on the 28 roots both
searched. The CPU was shared with other sessions' games (load 80-100%), so only the paired runs compare.

| | ms per simulation (28 common roots, 1128 simulations, all in) |
| --- | --- |
| `layer` | 98.2 |
| `plain-forge-ai` | 73.3 (-25%) |

Forge AI answers mapped by the layer drop from 30918 to 2049 per run. The rest of the decision layer (13% of a plain
simulation's samples) is the tree part and the rest of the loop step in which the walk leaves the tree, which still
goes through the layer; the swap waits for the loop step to end, where no controller call is under way. Plain
simulation profile (JFR, search thread): rules 39%, Forge AI's own play 27%, copy 15%, decision layer 13%, leaf
evaluation 6%.

Chosen moves: 9 of the 28 common roots (32%) get another answer. At 1x the visits are nearly flat (12 per candidate,
plus or minus 2), so many of these are near-ties that any change in the rollouts flips; the number says how much a
1x answer depends on the rollout, not which rollout plays better.

## Gate step-1 cost and the options that change what a search evaluates (2026-11-26)

The gate-1 step-1 run (`E:\magicdosa-runs\gate3-step1`, main `00be112`) measured 110.9 ms per simulation under five
workers' load: copy 9.8, leaf value 15.9, Forge AI answers through the decision layer 1.7, the rest of the Forge
loop to the horizon about 83. Plain Forge AI's own rollout answers are not timed separately (they run inside
`mainLoopStep`), so the 83 ms is Forge's rules and Forge AI's play together.

**Where it goes.** JFR of `w21-g1` replayed in full (`SearchReplay`, 62 searches, 1896 simulations, 10447 samples of
the search thread and of Forge AI's `Game AI Eval` threads, which earlier profiles here left out): Forge AI's play
42.5% (22.6% on the evaluation threads Forge AI starts for every priority decision), rules 34.2%, our decision
layer 8.6% (the tree part of a simulation), copy 7.4%, leaf value 7.2%. Forge-side detail and fix ideas:
docs/upstream-candidates.md 17. On our side only the copy had a cheap fix: `GameCopier` fills each library in one
call (`8470c99`): 24 searches identical, thread CPU per simulation 96.1 -> 93.1 ms (-3%).

**Method for the options.** Root set: the gate's `w0/w00-g1` and `w1/w10-g1`, every agent decision (149 searches,
4680 simulations at 1x). `SearchReplay -Dfollow=true` (`bc4d8eb`): the shadow searches each root and then plays the
recorded answer, so every setting searches the same 149 roots (without it, a setting that answers differently leads
the shadow away from the log and loses most roots: the network leaf kept 21 of 149). The default with follow prints
the same 149 searches as main without it, and as the gate itself (reproducible mode: 0 answers differ from the log).
All settings ran at the same time, 10-12 JVMs on the 6 cores (12 logical), so wall time is inflated (about 400 ms per
simulation); the comparison uses CPU time from the search debug line: the search thread's, and the process's (which
adds Forge AI's evaluation threads, GC and JIT).

Answers changed are counted against the default 1x on all 149 roots, and against a 4x default run on the 62 roots it
had reached (mostly the first half of each game; the 1x vs 4x gate was running on the same machine). The seed row is
the default with another seed (`-Dmagicdosa.search.seedSalt`, `d542922`): how many 1x answers depend on the seed
alone.

| Setting (measurement switch) | Thread CPU ms/sim | Process CPU ms/sim | Answers changed vs 1x default (149) | Differ from 4x (62) |
| --- | --- | --- | --- | --- |
| Default: horizon at the root seat's next turn, Forge evaluator with combat simulation | 116.7 | 231.8 | - | 11 of 61 |
| Default, another seed | 119.0 | 225.5 | 40 (27%) | 13 |
| Horizon at the next turn (`horizon=turn`) | 77.3 (-34%) | 160.7 (-31%) | 30 (20%) | 10 |
| Static leaf score, no combat simulation (`leafEval=static`) | 102.4 (-12%) | 209.0 (-10%) | 10 (7%) | 12 |
| Both | 59.1 (-49%) | 136.4 (-41%) | 34 (23%) | 10 |
| gen-0 network at the expanded node (`leafModel=gen0-run1.onnx`) | 28.3 (-76%) | 85.4 (-63%) | 94 (63%) | 34 |

Per game, the leaf value's cost varies most: Forge's evaluator took 49.0 ms per leaf in `w00-g1` and 2.0 in
`w10-g1` (it copies the game and plays the coming combat once per seat; big boards are expensive); with the horizon
at the next turn the leaf is the other seat's turn start and costs 25-44 ms; the static score costs 0.4-0.7 ms. With
the network leaf the copy (14-21 ms) becomes the largest part. The network run's process CPU is three times its
thread CPU with almost no rollouts; not explained yet.

Reading: at 1x, the seed alone changes 27% of the answers, so the shorter horizon and the static score change no
more answers than a reseed does, and agree with the 4x answers as often as the default (10-12 of 62 differ, small
sample, early turns). The gen-0 network changes far more (63%; 34 of 62 differ from 4x). None of the defaults is
switched: horizon, leaf value and network leaf are the user's decision. These switches are not recorded in
`SearchRun`; a setting adopted for gates or data needs a protocol field.

**Decision (2026-11-26, user):** horizon at the next turn and the static leaf score become temporary defaults for
speed, to be switched off later (they weaken play; nothing is to be built on them), recorded in
`SearchRun.horizon` / `leaf_evaluation` (protocol 15; the switches are now `next-turn`/`own-turn` and
`forge-static`/`forge-combat`). The network leaf stays off. With the new defaults the two games' 149 searches equal
the "Both" row's (same visits and root values).

## Horizon valued at the turn boundary (2026-11-28 directive, option 2; protocol 17)

Before: a walk that reached the next-turn horizon inside the tree played on to the new turn's first request, built its
legal actions and observation, and ran the network on it (protocol 16). Now (`-Dmagicdosa.search.boundary=on`, the
default) the boundary is valued at once: the session's existing `reask` builds the observation of the seat asked first
in the new turn (the active player), in the header the first request would have (PRIORITY, upkeep, priority with the
active player, one of one) with a single pass candidate, and `Evaluator.evaluate` runs; only the value is read. No
encoder or model change. `first-request` is the protocol-16 way, `measure` does it and also computes the boundary value
for comparison. Recorded as `LEAF_EVALUATION_NETWORK_BOUNDARY` (protocol 17). `-Dmagicdosa.search.boundaryView=root`
(measurement only) takes the root seat's observation instead.

Root set as "Copy cost" (52 searches, gen2-c leaf, follow, reproducible), main = this branch with
`treeReuse=false boundary=first-request` (its searches are main's). 1x: two alternating runs each; 4x: one run each.
The machine ran a 15-JVM chain.

| | 1x main | 1x boundary | 4x main | 4x boundary |
| --- | --- | --- | --- | --- |
| ms per simulation (`search.simulation`) | 28.7 | 21.9 (-24%) | 25.0 | 18.8 (-25%) |
| ms per decision | 1183 | 974 (-18%) | 4119 | 3363 (-18%) |
| Answers that differ from main | - | 8 of 52 | - | 14 of 52 |
| With tree reuse too (the new defaults): ms per decision | | 777 (-34%) | | 2591 (-37%) |
| ... answers that differ from main | | 16 of 52 | | 16 of 52 |

At 1x, 581 of 1644 simulations reach the horizon in the tree; a boundary value costs 2.4 ms (the observation and one
network run).

**Boundary value against the first request's value on the same states** (`measure`, 1x, 583 horizons): mean absolute
difference 0.081 with the active player's observation (the default), 0.122 with the root seat's. `measure` prints the
same 52 searches as `first-request`. Values are P(win) in [0, 1] for the root seat.
