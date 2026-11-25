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
