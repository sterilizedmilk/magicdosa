# Search quality at the 1x calibration budget (2026-11-25)

Question (user, 2026-11-25): at budget 11/12, is search close to random, and is it biased toward pass or the first
candidate? Analysis only; nothing in the code changed for it.

## Data

The B=12 calibration (Izzet mirror, search policy vs Forge AI, reproducible mode, 3 matches / 6 games) ran without a
log folder, so it was run again with logs at main `fa7e160`. Reproducible mode gives the same games: 6 games, 131.0
s/game (127.2 before; the kill checker measures only). Per searched decision the log holds the root step's candidates,
visit ratios and `SearchRun.determinizations` (simulations backed up); visits = ratio x backed up. Tool: a throwaway
reader in the session scratchpad (not committed).

## Results: 879 searched root steps

| Type | Steps | Mean candidates | Mean expanded | Tie (gap 0) | Gap 1 | Gap >= 2 | Ties broken to candidate 0 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| PRIORITY | 865 | 4.06 | 4.06 | 824 (95.3%) | 26 (3.0%) | 15 (1.7%) | 778 |
| TARGET | 7 | 1.86 | 1.86 | 5 (71%) | 0 | 2 | 5 |
| CHOOSE_CARDS | 6 | 7.17 | 7.17 | 6 (100%) | 0 | 0 | 5 |
| CHOOSE_OPTION | 1 | 2 | 2 | 0 | 0 | 1 | 0 |

- **Every candidate is expanded** (mean expanded = mean candidates): selection tries every unvisited candidate first,
  and 12 simulations over about 4 candidates leaves about 3 each.
- **Margins:** the chosen candidate's visits are never 2x a uniform share (879 of 879); 95% of priority decisions
  are exact ties.
- **Tie-break:** the answer is the most visited candidate, the first of equals in candidate order, and priority
  candidate 0 is always pass. 778 of the 824 priority ties went to pass.
- **What was chosen (priority, 865):** pass 796 (92%; pass is 1 of about 4 candidates), land 17 of 126 offered,
  spell 11 of 228, non-mana ability 7 of 571, mana ability 34 of 1723.
- Candidate counts (priority): 2: 225, 3: 154, 4: 253, 5: 44, 6-10: 185, >10: 4.
- Re-searching recorded root states at 44 and 176 (optional item 5) was not done: it needs a tool that replays a log
  to a decision and searches there (LiveReplay plus a search hook, about half a day). At 44 over about 4 candidates
  the visits are still about 11 each, so ties would stay common; 176 is the first budget where UCB can separate.

## Verdict

At budget 12 the search is not random but almost always undecided: about 3 visits per candidate, 95% exact ties,
and the tie-break by candidate order turns that into a systematic choice of pass (92% of priority decisions). The
agent therefore plays far more passively than a uniform policy would, which fits it losing all 6 games to Forge AI.
The budget is too small for UCB to separate candidates, and the tie-break carries a bias toward candidate 0. Options
for planning: break ties by mean value (the visit counts stay the policy target), randomise the tie-break from the
seed, and/or measure the 1x/2x/4x gate with a larger 1x than the 2-minute rule allows. None of these is applied.

## Evaluation signal baseline (gate 1 step 1, 2026-11-26)

User decision (2026-11-26): early turns are hard to predict by nature, so there is no fixed threshold for the root
value. The step-1 numbers below are the baseline; a later leaf-evaluation change (longer rollout, calibration, value
network) is judged by whether it beats them on the same read-out.

Run: main `00be112`, 1x search (12 simulations per root candidate, plain Forge AI rollout, protocol 14) in seat 0
against default Forge AI, Izzet mirror, reproducible mode, 60 matches / 142 games (agent won 48 = 33.8%). Read-out:
`GateSignal` (runner/forge test sources) over the game logs; root value = the searched root's mean value, the agent's
expected win rate; label = whether the agent won that game. Run folder: `E:\magicdosa-runs\gate3-step1`.

| Turns | Decisions | AUC | Brier | Mean value, won / lost |
| --- | --- | --- | --- | --- |
| all | 12,152 | 0.754 | 0.180 | 0.484 / 0.253 |
| 1-2 | 1,053 | 0.562 | 0.241 | 0.428 / 0.396 |
| 3-4 | 1,566 | 0.578 | 0.231 | 0.379 / 0.334 |
| 5-6 | 1,893 | 0.667 | 0.210 | 0.383 / 0.279 |
| 7-8 | 1,967 | 0.697 | 0.217 | 0.409 / 0.256 |
| 9-10 | 1,732 | 0.778 | 0.170 | 0.486 / 0.226 |
| 11-14 | 2,397 | 0.855 | 0.136 | 0.592 / 0.198 |
| 15+ | 1,544 | 0.954 | 0.083 | 0.724 / 0.167 |

Compare a change by AUC per turn bucket (the threshold-free measure: the agent loses most games against Forge AI, so
accuracy at 0.5 mostly rewards predicting a loss), on the same deck pairing and opponent.

## Tree depth and the information key (2026-11-27)

Measured with `SearchReplay` (recorded answers followed, `-Dmagicdosa.search.depthStats=true`) on the first game of
each unit of `gate3-step3-gen2` (5 logs, 30 decisions each, 117 searched roots, gen2-c leaf, 12 per candidate,
next-turn horizon, static Forge leaf for what reaches it). "Decisions" counts tree steps from the root, the root's
own step included; "phase steps" counts Forge phase positions from the root to where the simulation stopped.

| | simulations | nodes | network leaf at decisions (share) | max | ended at the horizon |
| --- | --- | --- | --- | --- | --- |
| 1x, before | 3,972 | ~ simulations | 2 (100%) | 3 | 11% |
| 4x, before | 15,888 | ~ simulations | 2 (100%) | 3 | 11% |
| 1x, after | 3,972 | 2,882 | 2: 28%, 3: 34%, 4: 32%, 5: 6% | 5 | 30% |
| 4x, after | 15,888 | 9,128 | 2: 23%, 3: 21%, 4: 24%, 5: 23%, 6+: 9% | 8 | 43% |

- Before: every network leaf was the first decision after the root's step, at any budget, and 85% of those were the
  root seat's own next decision. The tree key hashed the request's observation, which lists the cards a seat cannot
  identify (a hand, a library) by entity ID; a copy keeps Forge's card numbers, so those IDs followed the cards each
  determinization dealt, and the same information set got a new key in every simulation. `ForgeSearchGame.infoKey`
  now leaves those objects out (the zone sizes stay in the player states).
- Game time stays short either way: network leaves are in the root's turn (0 turns), median 1 phase step after; the
  simulations that cross into the next turn stop at the temporary next-turn horizon with Forge's static score.
- Same roots, 1x against 4x answers: 27 of 117 differ (23.1%) after the fix; before it, 1 of 46 at the gate's
  same-state check.

## Leaf horizon experiment (2026-11-28, `-Dmagicdosa.search.leafPlayout`)

Same 117 roots, 1x, after the key fix. With a playout, a simulation that leaves the tree plays on (the policy head's
most probable action at each step, or Forge AI on the decision layer) until the opponent's next turn has ended, and
the value network values the first request there; the in-tree walk stops at that boundary too, in place of the
next-turn horizon.

| leaf | search time, 117 roots | per simulation | ends 2 turns ahead | answers differ from plain 1x |
| --- | --- | --- | --- | --- |
| network at the tree exit (now) | 79 s | 13.5 ms | 0% (30% at the horizon, static score) | - |
| plain 4x, for cost | 415 s | 18.5 ms | 0% (43% at the horizon) | 27 / 117 |
| playout, policy head | 471 s | 110 ms (49,399 policy calls, 108 s) | 95% (5% 3 turns, 11 game ends) | 71 / 117 |
| playout, Forge AI | 423 s | 98 ms | 95% (15 game ends) | 58 / 117 |

No simulation failed. A playout costs about what 4x costs; whether it plays better is for games (after gate 1).
