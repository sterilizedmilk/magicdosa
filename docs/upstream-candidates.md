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
- **Our handling:** none. It is a known cause in shadow verification, and the shadow skips search for the rest of
  that turn.
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

