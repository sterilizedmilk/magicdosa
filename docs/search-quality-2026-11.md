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
