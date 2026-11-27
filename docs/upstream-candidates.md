# Forge upstream PR candidates

Problems in Forge that we work around in our code. `forge/` is never modified (CLAUDE.md). Each entry: what, where,
evidence, our workaround, the fix we would propose upstream. Newest first.

## 1. Global ability ID counters are not thread-safe (2026-11-11)

- **Where:** `SpellAbility.maxId` / `nextId()` is `++maxId` on a plain `static int`. `Trigger`, `StaticAbility`,
  `ReplacementEffect` and `SpellAbilityStackInstance` work the same way. `Game.maxId` too; we already route game
  creation through `magicdosa.adapter.forge.GameCreation` for that one.
- **Why it matters:** `SpellAbility.equals` / `hashCode` compare only `id`, and `Trigger.hashCode` uses only `id` too.
  Two threads that run `++maxId` at the same time can lose an increment, so later IDs are handed out twice. When both
  abilities end up in one game, two different abilities are "equal". Lists and maps that hold abilities then drop one
  or swap them.
- **Evidence:**
  - Of the 160 matches of an AllPairings 16 x 10 run on 8 threads in one JVM, 47 do not replay identically on one
    thread with the same seeds (`runner/forge` harness `ReplayCheck`).
  - Taking Forge AI's wall-clock limit out (AI_TIMEOUT 600 on every game) does not make the run reproducible.
  - Symptoms in the logged real games:
    - AzoriousControl mirror m7 game 2: an evoked Solitude entered with neither its evoke sacrifice trigger nor its
      ETB trigger, and stayed on the battlefield.
    - Broodscale vs AzoriousControl m2 game 2: Ugin's Labyrinth entered without its imprint trigger.
  - The single-thread replay of the same match does trigger them, and so does the shadow game.
  - The agent-core sequential determinism harness also shows this. Its informational 8-thread line reports 29 of 40
    matches different from the sequential run.
- **Our side (decision 2026-11-11):** run parallel Forge games as separate JVM processes, one game at a time per JVM.
  This applies to the runner, the gates, agent-core and ShadowCheck. We go back to threads once the fix below is in
  the Forge commit we pin.
- **Fix upstream:** `AtomicInteger` counters, or per-game counters.

## 2. Two private steps of the game start (2026-11-11)

- **Where:** `GameAction.runPreOpeningHandActions(Player)` and `GameAction.runOpeningHandActions(Player)` are
  private.
- **Our use:** the shadow game rebuilt at the game-start snapshot plays the rest of `GameAction.startGame` itself. It
  calls these two methods by reflection, read-only.
- **Fix upstream:** make them public, or expose a "continue the game start from here" entry point.

## 3. GameMemento cost (2026-11-11)

- **Measured:**
  - Capture takes 25-40 ms per turn start, because of the reflective walk over the game.
  - Restore takes about 12 ms.
  - 16 pairings x 10: 12,901 captures, 38.4 ms each on 8 threads.
- **Our use:** the shadow game takes one capture per turn start.
- **Fix upstream:** a cheaper capture, such as dirty tracking or a cached per-class plan.

## 4. GameMemento restore leaves a stale ability host (2026-11-11, minimal repro still to write)

- **What:** after `GameMemento.restore()`, Solitude's lazily rebuilt evoke `SpellAbility` was hosted by a battlefield
  copy of the card. `Spell.canPlayFromHost` then refused to cast it from hand.
- **Not caused by item 1:** it is also seen in a single-thread run.
- **Our workaround:** `ShadowGame.repairHosts()` gives every ability, trigger and static ability of every card its
  own card back as host. It made 4 repairs in 336 games.

## 5. GameCopier / Combat copy gaps (2026-11-10)

- **Sideboard statics:** devoid is a static ability that works in every zone. Devoid cards in the sideboard register
  static effects whose source card the copier does not copy.
- **Combat:** a planeswalker token that attacked and then left (the Jace token) stays among Combat's attackable
  defenders, and the copier fails to map it.
- **Our workaround:** our `GameCopier` copies the sideboard zone, and it gives such a card the same id -1 stand-in
  Forge uses. Details are in `docs/forge-adapter.md`.

## 6. `AiController.orderPlaySa` changes the list it is given (2026-11-11)

- **What:** `orderPlaySa(activePlayerSAs)` sorts by moving entries out of its input. `filterList` calls
  `input.removeAll(...)` on the caller's list, so after the call that list is missing the abilities that were moved.
- **How we hit it:** our decision layer asks Forge AI for its trigger order (`orderSimultaneousSa`), then maps the
  answer back to the list it passed in. The mapping failed (72 unmapped answers in one 16x10 gate run), and the
  caller's list was corrupted on top of that.
- **Our workaround:** Forge AI gets a copy of the list (`AgentPlayerController.orderSimultaneousSa`).
- **Fix upstream:** work on a copy inside `orderPlaySa`.

## 7. `ComputerUtilMana.getManaSourcesToPayCost` pays the cost it is given (2026-11-11)

- **What:** the method plans which sources pay a cost by paying the `ManaCostBeingPaid` passed in. After the call that
  object reads as paid.
- **How we hit it:** our decision layer kept using the same cost object to choose which colour of mana to make next.
  It then saw nothing left to pay and tapped arbitrary lands. Forge AI through our layer won 32.6% of games against
  51.7% for plain Forge AI (16x10); with a copy, 46.0%.
- **Our workaround:** the planner gets a copy (`AgentPlayerController.priorityFor`).
- **Fix upstream:** plan on a copy, or document the side effect.

## 8. A Forge AI cast that fails partway keeps the costs it paid (2026-11-13)

- **What:** `ComputerUtil.playStack` checks `ComputerUtilCost.canPayCost`, moves the spell to the stack, and then
  pays with `payComputerCosts`. When the payment fails partway, it calls `GameActionUtil.rollbackAbility`. That puts
  the card back and refunds the mana, but costs that are not mana stay paid. Unless the game-state snapshot
  (`restoreGameState`) is on, sacrificed permanents stay gone and the triggers they caused still fire. The mana they
  made floats. Rule 732.2 asks for a full rewind.
- **How we hit it:** in the Broodscale mirror, Forge AI starts Kozilek's Command or Emrakul, the Promised End and
  sacrifices Eldrazi Spawn tokens for mana. The cast then fails and the card returns to hand with no cast event. The
  shadow game cannot replay a cast that did not happen, so it diverges: the tokens are still there and the mana pool
  differs. In the 16x60 logs, 2 of 147 mirror games had it, 8 decisions in verification ①.
- **Also abilities (2026-11-14):** an activated ability whose payment fails partway leaves no move at all (it was
  never on the stack): an Eldrazi Spawn sacrificed for its mana and the mana floating, no event of an action
  (IzzetProwess-vs-Broodscale, the Spawn targeted by a spell, then sacrificed while Forge AI paid for an ability it
  did not activate; the stack trace of the sacrifice runs through PlayerControllerAi.playChosenSpellAbility and
  ComputerUtilMana.payManaCost).
- **Our handling (2026-11-14):** the Forge AI seat takes Forge's undo mark (`UndoManager.markForRollback`) and a
  session checkpoint before an action that can leave a paid cost behind, and takes the action back when it was
  abandoned (`ObservedAiController.playChosenSpellAbility`, commit 7398386): the game keeps what it paid, nothing of
  the attempt reaches the event stream, and Forge AI does not retry it this turn. Several cases first filed here were
  not abandoned actions but a mana ability Forge AI played as its action without a reported event (fixed in
  37a2235).
- **Fix upstream:** check the whole cost, mana included, before paying anything that cannot be undone, or rewind
  fully (snapshot) when the payment fails.

Root-cause analysis of every entry (Forge file:line, minimal repro, scope, fix options, upstream status): `docs/upstream-analysis.md`.

## 9. Undoing a mana ability untaps its source without a tap event (2026-11-14)

- **What:** when a payment is not made, `ManaRefundService.refundManaPaid` undoes the mana abilities that paid
  (`SpellAbility.undo`), and `CostTap.refund` untaps the source with `Card.setTapped(false)`. That fires no
  `GameEventCardTapped`; Forge then fires only a battlefield `GameEventZone` ComplexUpdate. A listener that follows
  tap events misses the untap.
- **How we hit it:** a player declines (or cannot finish) paying No More Lies' {3}; the lands it tapped untap again
  with no event, and the shadow game kept them tapped.
- **Our workaround:** the session keeps each permanent's last reported tapped state and, on a battlefield
  ComplexUpdate, reports the changes that came without a tap event (`ForgeGameSession.tapsWithoutEvent`).
- **Fix upstream:** untap through the path that fires `GameEventCardTapped` (or fire it in `setTapped`).

## 10. Refunding an unpaid "unless" cost undoes the spell's casting mana (2026-11-14)

- **What:** a spell keeps the mana abilities that paid for casting it in `getPayingManaAbilities()`: Forge clears the
  list before a payment (PlaySpellAbility, "reset is also done early here"), not after. When the spell asks its
  target's controller to pay a cost while it resolves (No More Lies: "unless its controller pays {3}"),
  `payCostDuringAbilityResolve` pays against the same spell ability and, when the payment is not made, refunds with
  `ManaRefundService(sourceAbility)`. That undoes every listed mana ability, the caster's own casting mana included:
  the caster's lands untap (the payer's opponent's), and the mana they made is taken back.
- **How we hit it:** EsperBlink cast No More Lies with Watery Grave; the AzoriousControl player could not pay {3};
  Watery Grave untapped (shown by our report of untaps without a tap event, candidate 9). The shadow game, which made
  the caster's mana before paying, did not list it and kept it tapped.
- **Our workaround:** before a payment while something resolves, our controllers clear the stale list
  (`ObservedAiController.forgetCastPayment`), so a refund undoes only that payment's own sources.
- **Fix upstream:** clear `getPayingManaAbilities()` in `payCostDuringAbilityResolve` before paying (as
  `clearManaPaid()` does for the mana), or refund only the abilities this payment added.


## 11. Forge AI leaves the X it weighed on the ability, and a Charm reuses it (2026-11-16)

- **What:** while Forge AI weighs a spell or ability with X, `ComputerUtilCost.setMaxXValue` sets
  `root.setXManaCostPaid(x)` on the card's own ability object, and nothing clears it if the spell is not cast. For a
  Charm, a later call returns that value ("another mode already decided": `root.getXManaCostPaid() != null`), so an X
  weighed in an earlier priority window, with more or less mana, comes back. Forge AI's target and mode logic run
  during a cast (`doTrigger`, `CharmAi.canPlayWithSubs`) also rewrite the X the cast already announced.
  `ComputerUtilCost.canPayCost` counts whatever X is there.
- **How we hit it:** Kozilek's Command ({X}{C}{C}, Charm). The ability carried X=2 from an earlier weighing, so the
  cost check said it could not be paid while the same check on a fresh ability said it could. During a cast that
  announced X=0, Forge AI's target logic set X=5 and the payment failed ("could not be played", 104 refusals in one
  pairing of the layer gate, and casts the live shadow refused).
- **Our workaround:** the potential-cast check runs with no X (`AgentPlayerController.payableWithMoreMana`); target
  and mode delegations during a cast put the announced X back (`keepingX`).
- **Fix upstream:** weigh X on a copy of the ability, or clear it when the AI decides not to play the spell; never
  overwrite the X of a cast in progress.

## 12. Forge AI decisions have no work budget, only a wall-clock limit on some (2026-11-24)

- **What:** `Game.AI_TIMEOUT` bounds only `AiController.chooseSpellAbilityToPlay` (a `FutureTask` waited on by wall
  clock). Attack and block declarations (`AiAttackController`, `AiBlockController`) and the other answers have no
  limit at all, and a wall-clock limit makes a decision depend on machine load (not reproducible).
- **How we hit it:** phase-2 gate fin4, Broodscale mirror m9-g2: the shadow's Forge AI did not answer an attack
  declaration with about 18 possible attackers within 10 minutes under the gate's load. Not reproduced: CombatProbe
  (agent-core bench) measured at most about 1.9 s for 18 attackers and 0.9 s for blocks on copies, growing about
  quadratically, and LiveReplay of the log answered at once.
- **Our workaround:** agent-core's hang safeguard (`magicdosa.shadowHangMs`, never a decision input) ends the stuck
  threads and restarts the shadow; the decision is search skipped and the game marked outside the reproducibility
  guarantee. A budget inside Forge AI needs a Forge change, deferred (2026-11-24 directive).
- **Fix upstream:** a deterministic work budget (nodes or steps) for Forge AI's searches and combat weighing, with
  the wall-clock timeout as an outer safeguard only.

## 13. GameCopier puts the original Mana objects into the copy's pool (2026-11-25)

- **What:** `forge.ai.simulation.GameCopier` copies each player's mana pool with
  `newPlayer.getManaPool().addManaNoEvent(m)` for the original `Mana` objects. Their player and source card stay the
  original game's. Spending that mana in the copy counts it on the original player: `MagicStack.add` ->
  `Player.addExpentThisTurn` runs a `ManaExpend` trigger on the original game's `TriggerHandler`, so the original game
  holds the copy (and the copy's spending changes the original's state).
- **How we hit it:** agent-core's search (thousands of copies of the shadow game) ran out of heap: copies stayed
  reachable from the shadow's waiting triggers until it ran them.
- **Our workaround:** our `GameCopier` copies the pools after the cards, as new `Mana` of the copied player with the
  copied source card and mana ability; search counts any simulation that still changes the source's waiting triggers.
- **Fix upstream:** the same in Forge's `GameCopier` (copy the pool once the cards are mapped).

## 14. GameCopier drops a card's plotted state and the turn it entered its zone (2026-11-25)

- **What:** `forge.ai.simulation.GameCopier` builds each card anew and adds it to its zone; adding stamps the copy's
  current turn as the card's turn in zone, and the plotted flag is not copied. A plotted card in exile is therefore
  neither plotted nor "entered before this turn" in the copy, so the copy never offers casting it by plot
  (`GameActionUtil`: `isPlotted() && !enteredThisTurn()`). Any other "entered this turn" check reads the copy's turn.
- **How we hit it:** search roots where the real game offered `cast_method plot` (Slickshot Show-Off) failed every
  simulation: the copy's priority candidates lacked the plot cast.
- **Our workaround:** our `GameCopier` copies `turnInZone` for every card and sets the `plotted` field directly
  (`setPlotted(true)` runs a BecomesPlotted trigger).
- **Fix upstream:** copy `turnInZone` and the plotted flag (without the trigger) in Forge's `GameCopier`, as
  `CardCopyService` does for LKI copies.

## 15. Where a search simulation's time goes inside Forge (2026-11-25, measurement only)

- **What:** after our own overhead was removed (`docs/search-perf.md`), most of an agent-core search simulation (a
  copy of the shadow game played to the start of the next own turn) is Forge's work. JFR shares of the search
  thread (R30 root set, 5857 samples):
  - `Card.getAllPossibleAbilities` calls `SpellAbility.canPlay` for every ability on every priority check, from both
    `ComputerUtilAbility.getSpellAbilities` callers (our candidate building and `AiController.getSpellAbilityToPlay`):
    8.1%; the whole of `getSpellAbilities` 15.5%.
  - `GameAction.checkStaticAbilities` on every state-based check: 8.9%; within it `Card.getHiddenStaticAbilities`
    builds a new `FCollection` for every card on every pass (samples land on the inlined `isSuspected`): 4.3%.
  - `forge.ai.simulation.GameCopier` / our `GameCopier`: `CardFactory.getCard` rebuilds each card from its script
    (`readCard`, `buildAbilities`) for every card of every copy, libraries included: 9.3%.
  - `GameStateEvaluator.getScoreForGameState` copies the game and plays the coming combat on every call (one call per
    seat per leaf): 3.9%.
- **How we hit it:** search costs about 50 simulations per decision at the 1x budget; each simulation's time is the
  sum above.
- **Our workaround:** none (no Forge patches); the parts on our side are done.
- **Fix upstream:** cache the abilities a card can play between state changes; avoid the per-card allocation in
  `getHiddenStaticAbilities` (return an empty view when there is nothing); a card copy that clones the built
  abilities instead of reading the script again.

## 16. GameCopier's stack copy casts the spells again (2026-11-26)

- **What:** `forge.ai.simulation.GameCopier.copyStack` puts each spell on the copy's stack with `MagicStack.add`, which
  treats it as a new cast: it runs SpellCast / SpellAbilityCast triggers (prowess triggers a second time), adds the
  spell to the turn's cast list and counts it for its caster. The copy's cast list is otherwise left empty
  (`// TODO update thisTurnCast`), so "spells cast this turn" in a copy counts only the spells on its stack.
- **How we hit it:** search priority roots with only a spell on the stack (its prowess triggers already resolved)
  failed every simulation: the copy first asked to order two new prowess triggers.
- **Our workaround:** our `GameCopier` suppresses all triggers while it puts the stack back. The cast list is not
  rebuilt yet (spells cast earlier in the turn are missing in a copy).
- **Fix upstream:** add the copied stack entries without the cast bookkeeping (or suppress triggers around it), and
  copy the turn's cast list.

## 17. Where a search simulation's time goes, late-game boards (2026-11-26, measurement only)

- **What:** the gate-1 step-1 run (Izzet mirror, plain Forge AI rollout, 1x budget) measured 111 ms per simulation
  under load: copy 9.8, leaf value 15.9, and about 83 ms of Forge game loop to the horizon. A JFR profile of one of
  its games replayed in full (`SearchReplay`, `w21-g1`, 62 searches, 1896 simulations, 10447 samples of the search
  thread plus Forge AI's evaluation threads) puts that time in Forge:

  | Part (share of the simulation samples) | Share |
  | --- | --- |
  | Forge AI's own play in the rollout | 42.5% |
  | of which on `Game AI Eval` threads (`AiController.chooseSpellAbilitiesToPlayFromList` starts a new thread for every priority decision and waits on it) | 22.6% |
  | Game rules (stack resolution, state-based checks, phase begin) | 34.2% |
  | Our decision layer (the tree part of a simulation: candidates, events, IDs) | 8.6% |
  | Copy (our `GameCopier`; `CardFactory.getCard` per card 6.9%) | 7.4% |
  | Leaf value (`GameStateEvaluator`: a copy and a combat simulation per seat) | 7.2% |

  Hot spots across those parts (a sample counts for every frame in its stack):
  - `Game.copyLastState`: an LKI copy of every card on the battlefield and in the graveyards (`CardCopyService.getLKICopy`
    with `CardView` updates) on every stack resolution and loop step: 11.8%.
  - `GameAction.checkStaticAbilities` on every state-based check: 13.3%; `Card.getHiddenStaticAbilities` (a new
    collection per card per pass) 5.1%.
  - `ComputerUtilAbility.getSpellAbilities` / `Card.getAllPossibleAbilities` (every ability's `canPlay` on every
    priority check): 11.8%.
  - View upkeep in a headless copy: `CardView.updateState` 6.5%, `Player.updateZoneForView` (re-collects the whole zone
    and the flashback view after every card added or removed) 2.2%.
  - Forge AI: `ComputerUtil.aiLifeInDanger` 6.5%, `AiBlockController.assignBlockersForCombat` 6.2%.
- **How we hit it:** a 1x search costs about 36 simulations per decision; 4x four times that.
- **Our workaround:** our `GameCopier` fills each library in one call instead of a `Zone.add` per card (identical
  searches, -3% thread CPU per simulation). Nothing else on our side is large (the decision layer's share is the
  tree part, which the walk needs). Options that change what a search evaluates (horizon, leaf value) are measured
  in `docs/search-perf.md` for the user to decide.
- **Fix upstream:** a pooled or same-thread evaluation for Forge AI's priority decision when no time limit applies;
  LKI copies made only for the cards an effect reads (or copy-on-write); a way to turn off view upkeep for simulation
  copies (`markAsSimulationCopy` games have no UI); the items of 15.

## 18. No public way to score a board without GameStateEvaluator's combat simulation (2026-11-26)

- **What:** `GameStateEvaluator.getScoreForGameState` always copies the game and plays the coming combat
  (`simulateUpcomingCombatThisTurn`) when the active player has creatures; the board score alone
  (`getScoreForGameStateImpl`) is private.
- **How we hit it:** the search's leaf value is Forge's score of the board as it stands since 2026-11-26 (the combat
  simulation cost 2-49 ms per leaf, item 17); agent-core calls the private method by reflection, so a Forge change
  there breaks agent-core at class load.
- **Our workaround:** reflection (`ForgeSearchGame.SCORE_IMPL`), checked when the class loads.
- **Fix upstream:** a public overload or flag that skips the combat simulation.


## 19. Forge AI's attack declaration takes minutes on large boards (2026-11-27, measurement)

- **What:** `AiAttackController.declareAttackers` on boards with dozens of creatures runs for minutes:
  `notNeededAsBlockers` predicts the next combats (`ComputerUtil.predictNextCombatsRemainingLife`), each running the full
  `AiBlockController.assignBlockers` (trade blocks, `canDestroyAttacker`, per-blocker trigger scans) over every pair.
- **How we hit it:** the phase-2 fin4 shadow stop (Broodscale mirror m9-g2, decision 786, turn 23): the shadow's Forge AI
  did not answer within the 10-minute safeguard. `AttackTiming` (agent-core test tools) rebuilds that game's turn-start
  states from the log and times the attack declaration, one JVM, no other load:

  | Turn | Attacking creatures | Possible blockers | Time | Attackers declared |
  | --- | --- | --- | --- | --- |
  | 21 | 46 | 80 | 141 s | 0 |
  | 22 | 80 | 46 | 223 s | 0 |
  | 23 | 45 | 83 | 502 s | 0 |
  | 24 | 172 | 2 | 922 s | 0 |

  Turn 23 took 8.4 minutes alone; with five workers on the machine it passed 10. Not a fault of the shadow.
- **Our workaround:** the hang safeguard (search skipped, thread dump, the shadow started again; agent-core restarted
  when a thread cannot be ended). Forge AI answers inside the shadow only where the search delegates (and in rollouts
  under the plain-Forge-AI rollout; the network leaf does not roll out).
- **Fix upstream:** a cheaper `notNeededAsBlockers` on large boards (cap the predicted combats, or reuse one block
  assignment), or a step budget for the AI's combat prediction.

## 20. A game copy rebuilds every card from its script (2026-11-28, measurement)

- **What:** `forge.ai.simulation.GameCopier` (and our copy of it) makes each card of the copy with
  `CardFactory.getCard`, which reads the card's script again and builds its abilities, triggers and statics
  (`readCard`), its keyword traits (`setupKeywordedAbilities`), and then the card's view (`updateStateForView`:
  keywords and mana colours, which also create the land-play, permanent-spell and basic-land mana abilities on first
  use). For a search that copies the game once per simulation this is 75% of the copy; the copy is 20-23% of the
  search thread with the network leaf (docs/search-perf.md, "Copy cost").
- **How we hit it:** agent-core's search copies the shadow game for every simulation (8-9 ms per copy on Izzet-mirror
  boards, about 120 cards after leaving out the sideboard cards that do nothing there).
- **Our workaround:** copy fewer cards (sideboard cards that do nothing from the sideboard). The rest cannot be cut
  from outside: the view update has side effects (lazily created abilities with IDs from the global counter), so a
  copy that skips or reorders it plays differently.
- **Fix upstream:** a card copy that clones the built state of an existing card (or of a per-name prototype) instead of
  reading the script; or, for a no-GUI game (`DummyCardView`), skip the view's keyword and mana-colour work and create
  the lazily built abilities where the rules first need them, independent of the view.
