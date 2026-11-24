# Search (phase 3) implementation notes

Design: shadow/search tab of the design doc; the decisions are in the phase-3 planning issues doc (items 1, 2, 4, 5).
This file records how the code does it and what is not done yet. It changes no design.

## Pieces (agent-core `magicdosa.agent.search`)

| Class | What |
| --- | --- |
| `IsMcts` | SO-ISMCTS: one tree per decision, a new determinization per simulation, UCB1 with availability counts, uniform prior, one new node per simulation, progressive widening |
| `SearchConfig` | budget (simulations), UCB constant, `opponentInTree` switch, widening; `-Dmagicdosa.search.*` overrides |
| `SearchGame` | what is searched: root seat, root candidate keys, `determinize(rng)` -> a simulation that asks a `Chooser` at every decision |
| `SearchResult` | visits per root candidate, visit ratios, chosen (most visited, first of equals), `SearchRun` for the decision step |
| `ForgeSearchGame` | the Forge binding on the live shadow game |

## How a decision is searched

- `--policy search --budget N` on agent-core (no default budget; item 5 measures it). The live shadow answers the
  agent seat's tracked **priority** requests by search; requests inside an action (targets, modes, payment while
  casting) are still answered by the shadow's Forge AI (an intended delegation). One candidate: no search
  (`SEARCH_SKIP_REASON_SINGLE_CANDIDATE`). Every simulation failed: the network alone (random policy),
  `SEARCH_SKIP_REASON_SEARCH_FAILED`. Recovery ③ (search skipped) is unchanged.
- Budget = simulations; `SearchRun.nodes` = tree nodes (the root plus at most one per simulation). The seed is the
  request's seed, so the same game seed and budget give the same search (`SearchScenarioTest.sameSeedSameSearches`).
- Each simulation: `GameCopier` copy of the shadow (stack included); the phase handler's priority state is copied
  too (`devModeSet` resets it to the active player); the cards the root seat cannot see (by Forge's view of the
  shadow card) are dealt anew among themselves: the other seat's hand and library together, the root seat's
  library. The copy gets a fresh `ForgeGameSession` (`attachToCopy`, both seats agent seats) whose client is the
  tree-lookup controller; both players get `AgentPlayerController`, so candidates are built exactly as in the real
  game. Forge's random stream is seeded per simulation and the shadow thread's stream is put back afterwards.
- In the tree, a request becomes one tree decision per step (remaining candidates plus "done"); pair limits and
  required attackers are kept (`fitting`, `complete`). The information-set key of a decision is the hash of the
  normalised request without candidates (the deciding seat's observation: public state plus its own cards) plus the
  steps chosen so far in sorted order (order-free multi-selects share nodes). Candidate keys are the candidates'
  text with the copy's entity IDs; the root's are named by Forge card numbers, which a copy keeps.
- Out of the tree (`ROLLOUT`), the request is delegated: Forge AI answers inside the copy. A multi-select that
  leaves the tree after its first step is finished at random. Always delegated (item 2): mana payment, damage
  assignment, trigger order, pre-game decisions.
- Horizon (item 4): the start of the root seat's next turn, or the game end. Value: 1 / 0 / 0.5 for a result, else
  `1 / (1 + exp(-(score(me) - score(them)) / 400))` with Forge's `GameStateEvaluator` (`-Dmagicdosa.search.evalScale`).

## Not done yet

- Roots inside an action (design: copy at the point before the action, replay the choices made so far).
- Scry, surveil and "top X" choices are tree steps like other multi-selects; the >24-combination rule (one card at a
  time) holds because every multi-select is stepped one card at a time; trigger order stays delegated.
- The root seat's library is dealt anew apart from cards Forge shows it; positions the shadow session knows (after
  a scry) are not used yet.
- A copy shares the shadow's lobby players (mind control inside a simulation would bind to the shadow session).
- Rollout decisions go through the decision layer (an observation each); first wiring run: about 24 rollout
  priority requests per simulation. Plain Forge AI controllers for the rollout are an optimisation to measure.
- The confirmed-kill checker (item 7) and the privileged library order (item 10) are not part of this.

## First wiring run (2026-11-24, `SearchScenarioTest`, budget 6, two turns of a small burn scenario)

21 searches, 126 simulations, 126 backed up, 145 tree nodes, every simulation reached the horizon, every shadow
decision matched. No games or gates were run (the phase-2 final gate held the machine).
