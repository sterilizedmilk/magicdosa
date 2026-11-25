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
  agent seat's tracked requests by search where the request is a search root (`ForgeSearchGame.ROOTS`,
  `INNER_ROOTS`); other requests are answered by the shadow's Forge AI (an intended delegation). One priority
  candidate: no search (`SEARCH_SKIP_REASON_SINGLE_CANDIDATE`). Every simulation failed: the network alone (uniform
  random), `SEARCH_SKIP_REASON_SEARCH_FAILED`. Recovery ③: `SHADOW_RECOVERY_FAILED`; a stopped shadow (the hang
  safeguard): `SHADOW_STOPPED` (protocol 13), both counted as "search skipped: <cause>".
- Roots (2026-11-25):
  - **Priority:** the copy resumes at the priority point (priority state copied from the shadow).
  - **Attack and block declarations:** the shadow waits inside the step's turn-based action, which a copy cannot
    resume; the copy goes back to the step before with first priority on the other seat and none given, and Forge's
    loop starts the declaration step again (`PhaseState.rewindToDeclaration`; the combat is copied).
  - **Inside an action** (targets, modes / X / costs, card choices such as scry, surveil, top X): at each viewer
    priority decision the shadow is copied once (the anchor, `search.anchorCopy` in the timing). A simulation copies
    the anchor and replays the viewer's recorded answers since then by candidate index; the other seat may only pass
    (anything else fails that simulation, counted). Then the engine asks the root again and the tree takes over. The
    anchor is dropped after any recovery and holds only in the turn and step it was taken in (the viewer may not
    have been asked since: auto passes, mana-only stops; an older anchor replays into another step and never meets
    the root); without an anchor the request is delegated, counted as "delegated: no anchor in this step: TYPE".
    Cards the viewer sees at the root (a scry's) keep their identity when the copy is dealt. The root's candidates
    are matched by name in the searched order: cards from a hidden zone (a library search) come in that zone's
    order, which differs between the shadow and a copy.
  - A multi-select root is read back as the most visited path through its steps: one protocol step per pick with
    that node's visit ratios; a selection the tree did not finish is completed with the first fitting candidates
    (steps without visit ratios).
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
- Out of the tree (`ROLLOUT`) the rollout policy plays (`SearchConfig.rollout`, `-Dmagicdosa.search.rollout`,
  recorded in `SearchRun.rollout` and the shards' `search_rollout` column, protocol 14):
  - **`plain-forge-ai` (default; a temporary early-stage choice, 2026-11-26 user decision):** at the first loop step
    after the walk leaves the tree, both seats get plain Forge `PlayerControllerAi` controllers and the copy's
    session stops listening to the game. Forge plays the rest of the simulation its own way (auto-tapping, its own
    candidates), much cheaper, but not as the real game's decision layer plays. Allowed for phase 3 and early phase 4
    only: once the network evaluates leaves, the rollout goes and this choice is reviewed. Data from the two
    policies is told apart by `search_rollout`.
  - **`layer`:** the decision layer stays and delegates each request: Forge AI answers inside the copy and its
    choice is mapped onto the layer's candidates (mana made as priority actions). A multi-select that leaves the
    tree after its first step is finished at random.
  - In both, the decisions of the loop step in which the walk left the tree still go through the layer (delegated);
    always delegated in the tree (item 2): mana payment, damage assignment, trigger order, pre-game decisions.
- Horizon (item 4): the start of the root seat's next turn, or the game end. Value: 1 / 0 / 0.5 for a result, else
  `1 / (1 + exp(-(score(me) - score(them)) / 400))` with Forge's `GameStateEvaluator` (`-Dmagicdosa.search.evalScale`).

## Not done yet

- Root mismatches (2026-11-25, IzzetProwess mirror, 12 per candidate x1, reproducible, seed base 3000): backed-up
  simulations 89.8% (5832 / 6492) before the root-order, plotted-copy and anchor-step fixes, 99.1% (6660 / 6720)
  after. A scry root after a surveil trigger milled the top card (60 simulations in that run) failed because the
  anchor (taken at the cast) still had the milled card on top and the copy put the scry's cards there; since
  2026-11-26 the cards that left a library between the anchor and the root (drawn, milled) and that the viewer knows
  now go back on top of the copy's library in the anchor's order, and the cards seen now sit below them
  (`SearchReplay` of that game: 36 / 36 backed up, was 0). In the same run the shadow lost step once (a triggered-ability
  answer that was not a shadow candidate: two decisions "search skipped"); the same command with logs did not
  repeat it (games identical up to that decision), so it depends on timing and is not diagnosed yet.
- Inner roots whose replay crosses an action of the other seat (it cast or activated something since the anchor)
  fail every simulation and are answered at random (search failed); the rate is to be measured.
- Scry, surveil and "top X" choices are tree steps like other multi-selects; the >24-combination rule (one card at a
  time) holds because every multi-select is stepped one card at a time; trigger order stays delegated.
- The root seat's library is dealt anew apart from cards Forge shows it; positions the shadow session knows (after
  a scry) are not used yet.
- A copy shares the shadow's lobby players (mind control inside a simulation would bind to the shadow session).
- Confirmed-kill checker (item 7, `KillCheck`): verifying approximate cases on a copy, and recording differences as
  regression tests, are not done.
- A stuck Forge thread that cannot be ended marks agent-core for a restart: it refuses further games, and the
  harness (RandomGames, AllPairings workers) then starts a new agent-core and plays that match again (once; counted
  in the report). Rebuilding the game under way in a new agent-core from the turn-start snapshot is not done (its
  remaining decisions are search skipped, cause shadow stop). The restart path has not been run end to end.

## Hang safeguard (2026-11-24 directive)

`magicdosa.shadowHangMs` stays a wall-clock safeguard that never picks a decision. When it fires: the decision is
search skipped (`SHADOW_STOPPED`, uniform random), every thread running Forge code is dumped (stderr, shadowStats
`hangDump=`), the threads are ended (`Thread.stop`, JDK 17) and, when all are gone, a new shadow replays the record
from the game start at the next decision. The game is marked outside the reproducibility guarantee (shadowStats
`reproducible=false`, shard metadata `reproducible`). `HangScenarioTest` forces a hang (`magicdosa.shadowHangTestAt`).

## Measurement (`-Dmagicdosa.timing`, never read by a decision)

`search.decision` and `search.simulation` (histograms), `search.copy`, `search.anchorCopy`, `search.leafValue`, and
`search.forgeAi.<TYPE>` (histogram with max: each Forge AI answer inside a simulation, the rollout decision-time
distribution the directive asks for). `CombatProbe` (agent-core bench) times Forge AI's combat declarations by
board size: at most about 1.9 s for 18 attackers, no blowup.

## First wiring run (2026-11-24, `SearchScenarioTest`, budget 6, two turns of a small burn scenario)

21 searches, 126 simulations, 126 backed up, 145 tree nodes, every simulation reached the horizon, every shadow
decision matched. No games or gates were run (the phase-2 final gate held the machine).
