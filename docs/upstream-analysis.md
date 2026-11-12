# Forge upstream candidates: root-cause analysis

Analysis behind each entry of `docs/upstream-candidates.md`, as material for writing Forge issues and PRs. Written
2026-11-12. Nothing here is a patch. Fix directions are described in words only.

- **Pinned Forge commit:** `87cb532e9f088958ff367d293dc98076a091f9cb` (2026-10-29, "Keep each player's reveal history
  in their own GUI (#12503)").
- **Forge `master` at time of writing:** 682 commits ahead of the pin.
- **Line numbers:** `file:line` refers to the pinned commit unless the text says "master".
- **Method:** source reading in `forge/`, `git blame`, and GitHub (REST API, web search). Statements marked
  **unverified** need a run to confirm.
- **Probes (added 2026-11-12):** candidates 1 and 4 were then checked with standalone, read-only probe programs
  (`UpstreamProbe.java`, `BlindSpotProbe.java`, `KarnProbe.java`). It is compiled with `javac` against Forge's prebuilt `forge-gui-desktop` jar of the pinned
  commit and run from `forge/forge-gui`. Nothing in `forge/` or the repository was changed, and no Maven was run. Copies
  of the probes are in the side-session notes folder.

| # | Candidate | Root cause confirmed in source? | Upstream status |
| --- | --- | --- | --- |
| 1 | Global ability ID counters | Yes, and the dropped trigger is **reproduced** on one thread. The main mechanism is the global `Trigger.resetIDs()` at every match start, not only the `++maxId` race | Open issue #11260 (per-game counters), no PR. Unchanged on master |
| 2 | Private game-start steps | Yes | Nothing found. Still private on master |
| 3 | GameMemento capture cost | Yes (full graph walk per capture) | Memory reworked on master (#12603, #12753); capture time not addressed |
| 4 | Stale ability host after restore | Yes, **reproduced**. GameMemento treats Guava `MultimapBuilder` multimaps as leaves, so no card's keyword collection (or the abilities under it) is ever restored | Nothing found. Same rule on master |
| 5 | GameCopier / Combat copy gaps | Yes, both | Sideboard half worked around on master (commit `9c77436427`); Combat half unchanged |
| 6 | `orderPlaySa` mutates its input | Yes | Nothing found. Unchanged on master |
| 7 | `getManaSourcesToPayCost` pays the given cost | Yes, and Forge relies on it in one place | Known upstream (#11487 text), caller fixed, method unchanged |

---

## 1. Global ability ID counters

### Root cause

**The counters.** Each of these classes has a plain `private static int maxId` and `nextId() { return ++maxId; }`.
Nothing is volatile, atomic or synchronized.

| Class | Counter | Assigned at |
| --- | --- | --- |
| `SpellAbility` | `spellability/SpellAbility.java:78-79` | constructor `:202`; `copy(host, activ, lki=false)` `:1250` |
| `Trigger` | `trigger/Trigger.java:55-56` | constructor `:91`; non-LKI `copy` `:570` |
| `StaticAbility` | `staticability/StaticAbility.java:54-55` | constructor `:248` |
| `ReplacementEffect` | `replacement/ReplacementEffect.java:47-48` | constructor `:101`; copy `:203` |
| `SpellAbilityStackInstance` | `spellability/SpellAbilityStackInstance.java:41-42` (public) | `:63`; `zone/MagicStack.java:201` |
| `Game` | `Game.java:68-69` | `Game` constructor `:349` (we already serialize this through `GameCreation`) |
| `CombatView` (view only) | `combat/CombatView.java:24`, `nextId--` | constructor `:27` |

All paths are under `forge-game/src/main/java/forge/game/`. Card IDs are already per game (`Game.nextCardId()`,
`Game.java:1163-1165`), so cards are not affected.

**Identity is the ID.** Two abilities with the same ID are treated as the same ability:
- `SpellAbility.equals` and `hashCode` use only `id` (`SpellAbility.java:221-229`).
- `Trigger.equals` and `hashCode` use only the id (`Trigger.java:466-478`).
- `StaticAbility` does the same (`StaticAbility.java:74-82`), and so does `ReplacementEffect` (`:299-310`).

**Where a duplicate ID makes Forge lose an ability.** There are three places.

1. **`TriggerHandler.isTriggerActive`** (`trigger/TriggerHandler.java:370`, loop at `:389-393`, "If an ID that matches
   this ID is already active, don't add it"):
   - A trigger is not registered when *any* active trigger in the game, on any card, has the same ID.
   - `registerOneTrigger` (`:250`) and `resetActiveTriggers` (`:203-219`) both go through this check.
   - Result: the trigger silently never fires. This matches both logged symptoms:
     - an evoked Solitude entered with neither its ETB trigger nor its evoke sacrifice trigger;
     - Ugin's Labyrinth entered without its imprint trigger.
2. **`FCollection.add`** (`forge-core/.../util/collect/FCollection.java:345-353`) refuses an element that is already
   `equals`-contained.
   - The per-card trait lists are `FCollection`s: `CardState.java:84-87`.
   - The merged views are built into new `FCollection`s on every read: `getTriggers` `:697-707`,
     `getSpellAbilities` `:467-473`, and statics and replacements at `:763` and `:860`.
   - So two traits of one card with the same ID merge into one.
3. **Hash-keyed maps and sets.** About 18 `HashMap`/`HashSet` instances keyed by `SpellAbility`, `Trigger`,
   `StaticAbility` or `ReplacementEffect` exist in forge-game and forge-ai. One example is
   `Card.mayPlay` / `chosenModes*` (`card/Card.java:177, 374-377`).
   - A collision overwrites an entry.
   - Even without collisions, their iteration order depends on the ID values.

**How a duplicate ID arises: mechanism A, the global reset.** This is the main one.
- `Trigger.resetIDs()` (`Trigger.java:63-65`) sets the *global* `Trigger.maxId = 50000`.
- Call sites:
  - `Match.prepareAllZones` (`Match.java:232-234`, comment "need this code here, otherwise observables fail"), which
    `Match.startGame` calls (`:81`). So it runs at the start of **every** game.
  - `RestartGameEffect` (`ability/effects/RestartGameEffect.java:40`, Karn Liberated).
  - Forge's own test `GameWrapper` (`forge-gui-desktop/src/test/.../GameWrapper.java:81`).
- With several games in one JVM:
  1. Game C starts on thread 3 and resets the counter while game B is running on thread 2.
  2. Every trigger that game B creates afterwards gets an ID from 50001 upwards. Game B's own triggers created at its
     start already hold those IDs.
  3. New triggers are created whenever a card changes zone (except onto the battlefield), because Forge builds a new
     card object then: `GameAction.java:207`, `new CardCopyService(c).copyCard(false)`. Casting Solitude moves it from
     hand to stack and so gives it fresh trigger IDs.
  4. If one of those IDs belongs to a trigger already active in game B, `isTriggerActive` drops the new trigger
     (path 1 above).
- The reset happens once per game start, so with 8 threads it hits running games constantly. This alone explains the
  high divergence rate (47 of 160 matches; 29 of 40 in `ParallelDeterminismHarness`).
- The reset is also why the **sequential** runs are reproducible: each game's trigger IDs restart at 50001.
- `SpellAbility`, `StaticAbility` and `ReplacementEffect` are never reset. Their IDs keep growing across games in the
  JVM.

**How a duplicate ID arises: mechanism B, the `++maxId` data race.**
- `++maxId` is a separate read, add and write.
- Two threads that interleave can both return the same value. This is a plain lost update, and it is harmless when the
  two IDs belong to different games.
- The harmful case is a thread that reads, is descheduled, and writes back a stale value later. That rolls the counter
  *back* and re-issues IDs the other thread already handed out *inside its own game*.
- This is rarer than mechanism A, but it exists for every counter, not only `Trigger`.

**Concurrency inside one game.** This matters upstream because it is not only about running several games in one JVM.
- `AiAttackController` runs a fixed thread pool of `availableProcessors()` threads over the attackers inside one
  decision (`forge-ai/.../AiAttackController.java:895-901`, added 2026-10-16, `89e02b88da34`).
- `AiController` runs AI evaluation on a "Game AI Eval" thread (`AiController.java:2501`). On a timeout it keeps
  running next to the game thread for up to 2 s (`:2505-2525`).
- **Probe result** (12 AI-vs-AI games, 597 turns, a stack sampler on every non-game thread):
  - The "Game AI Eval" thread was caught inside ability construction 72 times: `Trigger.<init>` (`TriggerDamageDone`),
    `SpellAbility.<init>` (`AbilitySub`, `AbilityActivated`, `Spell`), `StaticAbility.create`, `ReplacementEffect.<init>`
    (`ReplaceMoved`).
  - The `AiAttackController` pool threads were never caught there.
- **What this means:**
  - Normally the game thread waits on the eval thread's future, so the two do not allocate at the same time.
  - After a timeout the eval thread keeps running beside the game thread for up to 2 s, so mechanism B can happen inside
    one game.
  - This is rare, and it does not happen when the AI time limit is not hit (our reproducible runs use `AI_TIMEOUT 600`).

**Iteration order.** Even with no collisions, the ID values a game receives depend on what other threads did. Hash
iteration order of the maps in path 3 then differs from run to run. So the parallel runs would not be reproducible even
if every ID were unique. Atomic counters do not fix this; per-game counters do.

### Minimal reproduction

1. **Single thread, deterministic, no threads at all.** This is the best one for an issue.
   - Use Forge's own test setup (`GameWrapper` or a `GameState` puzzle): a game with permanent A on the battlefield
     that has a registered trigger, plus a creature card B with an ETB trigger in hand.
   - Record the set of active trigger IDs (`TriggerHandler` active list, or `t.getId()` over
     `game.getCardsIn(Battlefield)`).
   - Call `Trigger.resetIDs()`. It is public; in a real JVM this is "another match started".
   - Cast B.
   - Expected: B's ETB triggers. Actual: B's new trigger receives the ID that A's trigger already holds and is not
     registered.
   - **Probe result (reproduced):**
     - Setup: Soul Warden on the battlefield and Elvish Visionary in the library (`GameState`), with
       `Trigger.resetIDs()` before setup as `prepareAllZones` does. Soul Warden's trigger got ID 50002.
     - Control run (no second reset): Elvish Visionary moved hand → battlefield got trigger ID 50009 and was registered.
     - Reset run: after a second `Trigger.resetIDs()`, the rebuilt Elvish Visionary got 50001, then 50002. When it
       entered with 50002, it was not registered as active, either at the zone change or after a full
       `TriggerHandler.resetActiveTriggers()`.
     - In the probe, triggered abilities did not reach the stack in either run (zone moves without a cause), so the
       evidence is the registration, not a missing stack entry. The missing stack entries are seen in the real games
       (item 3).
2. **Single thread, real game.**
   - Karn Liberated restarts the game: `RestartGameEffect` calls `resetIDs()`.
   - The cards exiled with Karn come back onto the battlefield as the *same* card objects. A move onto the battlefield
     does not copy the card (`GameAction.java:171-172`), so those cards keep their old trigger IDs.
   - All other cards are rebuilt and draw IDs from 50001 again.
   - Exiled permanents with triggers can therefore lose them, or make the restarted game's cards lose theirs.
   - **Probe result (reproduced, one thread, real AI game):**
     - Setup: Karn Liberated with 14 loyalty; Soul Warden, Kor Skyfisher and Elvish Visionary exiled with Karn (trigger
       IDs 50013-50015); libraries with more copies of those creatures. The ultimate is resolved at the first main
       phase, and the AI then plays the restarted game.
     - After the restart, the Kor Skyfisher returned from exile and a Kor Skyfisher from the new game both hold trigger
       ID 50014. Only one of them is registered as active. The other was "not active; the active trigger with that id
       belongs to Kor Skyfisher (113)" at every turn start, 32 such checks in the first game.
     - 175 duplicate-ID groups among all cards over that game.
     - This is a single-thread, no-reflection demonstration of the drop. It is the best repro to attach to #11260.
3. **Two threads, our evidence.**
   - Real AI-vs-AI games on 8 threads: 47 of 160 matches do not replay identically on one thread with the same seeds.
   - Taking out the AI time limit does not change that.
   - Concrete symptoms: Solitude without ETB or evoke sacrifice trigger (AzoriousControl mirror m7 g2); Ugin's
     Labyrinth without imprint (Broodscale vs AzoriousControl m2 g2). Both trigger correctly in the single-thread
     replay.
   - Harnesses: runner `ReplayCheck` / `HostWatch`; agent-core `ParallelDeterminismHarness` (29 of 40 different on
     8 threads). Details are in `docs/upstream-candidates.md`.
4. **The race on its own** (mechanism B), as a micro-test.
   - N threads each call `sa.copy(host, false)` (or `new Trigger` copies) about 10^6 times and keep the IDs they
     receive.
   - Duplicates within one thread's own list prove counter rollback.
   - Duplicates across threads prove lost updates.
   - This needs no game loop.

### Scope (same pattern elsewhere)

**Same counter pattern:**
- `SpellAbility`, `Trigger`, `StaticAbility`, `ReplacementEffect`, `SpellAbilityStackInstance`, `Game`
  (`maxId`/`nextId`).
- `CombatView.nextId` (view side, negative IDs).

**Global resets:** `Trigger.resetIDs` only. Its callers are `Match.java:234`, `RestartGameEffect.java:40` and the test
`GameWrapper`.

**Other process-global mutable state that games share:**
- `MyRandom.random` (`forge-core/.../util/MyRandom.java:36`): one `Random` for every game. We already replace it with
  a per-thread delegate (`adapter/forge/ForgeRandom.java`).
- `MyRandom.calls` (`:35`): a global draw counter that undo uses "to detect that randomness was consumed". With
  parallel games, another game's draws make undo refuse for no reason.
- Lazily filled static `HashMap`s with unsynchronized `put`. Concurrent first use can corrupt them:
  - `CounterCustomType.sMap` (`card/CounterCustomType.java:10-16`)
  - `CounterKeywordType.sMap` (`card/CounterKeywordType.java:20-26`)
  - `Keyword.cardKeywordSetLookup` (`keyword/Keyword.java:295-299`)
  - `TrackableTypes.enumTypes` (`trackable/TrackableTypes.java:139-143`, `computeIfAbsent` on a plain `HashMap`)
  - Low risk after warm-up, but real.

### Fix options

1. **Per-game counters.** This is the direction of open issue #11260 (Hanmac, a Forge maintainer).
   - IDs come from the game: one counter per type, or one shared counter per game.
   - Fixes both mechanisms and the iteration-order issue.
   - Makes IDs depend only on the game's own construction order, which is what deterministic replay needs.
   - `Trigger.resetIDs()` becomes unnecessary.
   - Risks:
     - Constructors need a `Game`. Cards built for the UI via `getCardForUi` have no game. #11260 discusses a static
       negative counter as the fallback for those.
     - `GameCopier` / `GameSnapshot` preserve IDs since #11203, so the copy must carry the counter (set it to at least
       the source's value). Otherwise copied games re-issue IDs.
     - `GameMemento` restores `Game` fields, so a counter stored on `Game` rolls back with the game. That is correct
       for undo.
     - Views send IDs to network clients (`StaticAbilityView` uses `st.getId()`). This is fine within one game per
       client, but the network code should be checked.
   - Large diff: every `new SpellAbility/Trigger/...` site already has a host card, so the game is reachable in most
     places.
2. **`AtomicInteger` counters.** Small and low risk.
   - Fixes only mechanism B.
   - Does **not** fix the `resetIDs` collisions, which are the main cause, and does not fix iteration order.
   - Useful only together with the next option.
3. **Stop resetting the global trigger counter.** Drop the `resetIDs()` calls, or make the reset per game.
   - Fixes mechanism A directly.
   - Unknowns:
     - What the comment "otherwise observables fail" refers to (the code dates from "the great move", `2bc61a9e685`;
       its original reason is lost).
     - Why the reset value is 50000 rather than 0. Possibly to keep clear of IDs given to cards built at startup for
       the card database or UI.
   - Single-thread replay determinism across games in one JVM would change: trigger IDs would no longer restart per
     game. Our sequential harness already lives with that for `SpellAbility` IDs, so it is probably harmless, but
     Forge's simulation tests that compare IDs should be checked.
4. **`ThreadLocal` counters.** Not acceptable.
   - AI helper threads (the `AiAttackController` pool, "Game AI Eval") create objects for the game thread. They would
     draw from different counters and collide inside one game.

**Most acceptable upstream:** comment on #11260 with the `resetIDs` finding and the single-thread repro (1 or 2 above).
Then either:
- implement option 1 as the issue proposes; or
- if that is too large for a first PR, a small PR with options 2 and 3 together. This fixes the dropped triggers today
  and keeps #11260 as the follow-up.

Leading with the reset is stronger than leading with "thread safety": it can be shown on one thread (Karn).

### Upstream status

- **Issue #11260** "Move nextId statics into Game methods" (open, Hanmac, 2026-07-17, labels Game Mechanics / keep /
  Feature). Six comments:
  - Tyrathalis lists the same classes, notes that deterministic replay needs counters reset "currently handled via
    reflection", and that GameCopier preserves IDs since #11203.
  - Hanmac raises the `getCardForUi` cards that have no game.
  - Jetz72 asks whether IDs need to be per class.
  - No PR is linked.
  - Neither the `resetIDs` reset nor the `isTriggerActive` drop is mentioned.
- **master:** all counters and `Trigger.resetIDs()` are unchanged (master `Trigger.java:56-65`).
- **No issue found** about dropped triggers from duplicate IDs. Searches: "maxId", "resetIDs", "thread-safe",
  "thread ID", "Trigger id duplicate".

### Open questions for the user

- Raise this as a comment on #11260 (maintainer-owned) rather than a new issue? #11260 already has the design
  discussion.
- Should the first PR be the small one (options 2 + 3) or the full per-game counters? The small one is easier to get
  merged; the full one is what we need to go back to threads in one JVM (iteration-order determinism).
- Should we confirm the in-game race through the `AiAttackController` thread pool? If it is real, our one-game-per-JVM
  runs are also exposed, through Forge AI's own threads. A probe that records thread names at `nextId()` would settle it.
  That needs a run, and I have not built one.

---

## 2. Two private steps of the game start

### Root cause

**What `startGame` does.** `GameAction.startGame(GameOutcome, Runnable)` (`GameAction.java:2341-2397`) runs, in one
method:
1. pick the first player (`determineFirstTurnPlayer`, `:2399`);
2. `runPreOpeningHandActions(first)` (`:2352` → private `:2466`): Emissary's Ploy, Cryptic Spires;
3. draw the opening hands (filtered hands, `BackupPlanService`);
4. `MulliganService.perform()`;
5. Planechase setup;
6. `runOpeningHandActions(first)` (`:2384` → private `:2503`): Leylines, Gemstone Caverns, and other
   `MayEffectFromOpeningHand` effects; it can change the first player;
7. `checkStateEffects(true)`;
8. the `NewGame` trigger;
9. `PhaseHandler.startFirstTurn`, which also runs the main loop.

**Why we need the private steps.** The shadow game rebuilt at the game-start snapshot has to run steps 2-8 itself:
- the first player is already known from the log;
- we drive the loop ourselves through `PhaseHandler.setupFirstTurn` and `mainLoopStep`.

There is no public entry point for "the rest of the game start, given the first player, without running the loop". So
`ShadowGame.pregame()` (`agent-core/.../shadow/ShadowGame.java:905-926`) copies steps 2-8 and calls the two private
methods by reflection.

- The two methods have been private since 2017 (`836ebdc5e61b`, Sol).
- `PhaseHandler` already has the split we need for the turn loop: `setupFirstTurn` / `startFirstTurn` / `mainLoopStep`
  (`phase/PhaseHandler.java:1014-1045`, public since 2025-07-25, `052e72d8eac9`). `GameAction.startGame` has no
  equivalent.

### Minimal reproduction

Not a bug, an API gap. The issue text: "an embedder that resumes a game from a saved pregame state (known first player,
own loop) has to duplicate `startGame` and reach two private methods by reflection".

The duplication already drifts: our copy omits filtered starting hands, `BackupPlanService` and Planechase. These do
not matter for our Modern Bo3, but they show the risk.

### Scope

Other private Forge members we read by reflection (read only, never written):
- `MagicStack.simultaneousStackEntryList` (`zone/MagicStack.java:68`).
  - Triggered abilities waiting for priority; Forge fires no event for them.
  - Only `hasSimultaneousStackEntries()` is public.
  - Our reader: `adapter/forge/ForgeGameSession.java:209`.
- `TriggerHandler.delayedTriggers` (`trigger/TriggerHandler.java:48`).
  - Only register and clear methods are public; there is no getter.
  - Our reader: `adapter/forge/GameFacts.java:41`.
- `GameState.idToCard` (`GameState.java:76`).
  - The mapping from the snapshot's card IDs to the created cards.
  - Our reader: `adapter/forge/SnapshotBuilder.java:56`.
- `PhaseHandler.playerTurn`, `pPlayerPriority`, `pFirstPriority` (`phase/PhaseHandler.java:83-89`).
  - We **write** `null` to these by reflection (`SnapshotBuilder.java:246-249`) to represent "before the first turn".
  - `setPlayerTurn(null)` would call `game.updatePlayerTurnForView()` and `resetPriority()`.
  - This is the one place where we write private state.

### Fix options

1. **Make the two methods public.** One-line change, trivially reviewable, no behaviour change. It leaves embedders
   duplicating the rest of `startGame`.
2. **Split `startGame` into public phases** with no behaviour change. For example:
   - a public method that runs steps 2-8 for a given first player (call it "pregame from first player");
   - `startGame` = choose the first player + that method + `startFirstTurn`;
   - embedders call that method and then `setupFirstTurn` and their own loop.
   - Matches the existing `setupFirstTurn` / `startFirstTurn` split in `PhaseHandler`.
   - Slightly larger, but removes the duplication.
   - The `do { … } while (RestartedByKarn)` loop has to stay in `startGame`.
3. **Read-only getters** for the other members: `getDelayedTriggers()` (unmodifiable view),
   `getSimultaneousStackEntries()`, `GameState.getCardById(int)`. These are cheap and useful for UI and network code
   too. The `PhaseHandler` "no active player yet" state needs a real setter, or permission for `setPlayerTurn(null)`.

**Most acceptable upstream:** option 2, framed around testability and resuming from `GameState`. Option 1 is the
fallback if reviewers want the smallest change. Option 3 can go in the same or a separate small PR.

### Upstream status

- **master:** both methods are still private (master `GameAction.java:2490, 2527`).
- **No issue or PR found.** Searches: `runOpeningHandActions`, "opening hand actions", "startGame".

### Open questions for the user

- Is one combined PR ("expose pregame steps and read-only accessors for embedders") wanted, or the narrow one for the
  two methods first?

---

## 3. GameMemento capture cost

### Root cause

**How a capture works** (`forge-game/.../undo/GameMemento.java`):
- `Recorder.capture(root)` (`:367-399`) walks **every object reachable from `Game`** on every capture. This is a
  breadth-first walk with an `IdentityHashMap` registry (`:347`, `enqueue` `:401-412`).
- For each object it reads every non-static field through `java.lang.reflect.Field.get` / `getInt` / ...
  (`snapObject` `:473-507`, `readPrim` `:309-320`).
- It iterates every collection, map, multimap and table (`snapSequence` `:429-458`).

**Why it costs 25-40 ms.** The cost is proportional to the size of the whole reachable graph, not to what changed.
- The `Recorder` sharing (`:452-455`, `:499-503`) saves *allocation* for unchanged objects, but every object is still
  visited, read and compared.
- The per-class reflection plan is **already cached** (`INFOS`, `ClassInfo`, `:37, :60-87, :291-295`). So "a cached
  per-class plan", suggested in `upstream-candidates.md`, is already done.
- The walk includes `forge.trackable.*` (`WALK`, `:22`): the `CardView`/`TrackableObject` property maps. They
  duplicate much of the game state, roughly doubling the graph.
- Libraries hold most of the cards and rarely change, but they are walked in full every time.

**Measured:** 12,901 captures at 38.4 ms each on 8 threads, from `docs/upstream-candidates.md`. Single-thread figures
are 25-40 ms capture and about 12 ms restore.

### Minimal reproduction

Any mid-game Modern board.
1. Time `new GameMemento.Recorder()` followed by `capture(game)` in a loop of 100 at a turn start.
2. Report the mean and `objectCount()`.
3. Repeat with the `forge.trackable.` entry removed from `WALK` (a local experiment, not a fix) to show how much of the
   cost the views take.

Our numbers come from the shadow game's per-turn checkpoints (`agent-core` `ShadowGame.checkpoint`, `result.mementoNanos`).

### Scope

Same pattern: `GameSnapshot` / `GameCopier` (forge-ai simulation) also copy the whole game per call, but they make
real copies rather than in-place snapshots. Upstream turned the AI's memento-based rollback off by default to avoid OOM
on phones (#12529), so the memento is mainly the player undo now (#12147, "Undo for a player's recent decisions").

### Fix options

1. **Dirty tracking** (only objects written since the last capture are walked).
   - Needs a write barrier on every mutable field: setters, or bytecode instrumentation.
   - Invasive across forge-game; reviewers will likely refuse.
2. **Generation pruning.** Skip the subgraph of zones whose contents did not change (library, sideboard) unless their
   `FCollection` version changed.
   - Cheap to try.
   - Unsafe if a card in such a zone changes through a field rather than through the zone collection: counters,
     perpetual effects, keyword caches. Correctness risk for undo.
3. **Do not walk views.**
   - Rebuild `TrackableObject`s from the model after restore (`updateView` calls) instead of snapshotting them.
   - Probably the largest single win.
   - Risk: views of objects that are not reachable from the model; the GUI and network sync (`Tracker`, deltas) must
     see the restored values.
4. **Faster field access:** `VarHandle` / `MethodHandle` instead of `Field`.
   - Forge targets Java 17 (`forge/pom.xml:27`), where `Field` uses `Unsafe`-based accessors after inflation.
   - The gain is modest. The bulk is the traversal and the `IdentityHashMap`.

**Most acceptable upstream:** a measured PR for option 3, or option 2 limited to libraries, presented as an undo
latency improvement (player-visible: one capture per prompt). It needs a benchmark that upstream can run. A general
"make the memento faster" request with no patch is unlikely to move.

### Upstream status

- **#12603 "Delta Memento"** (kevlahnota, merged 2026-11-03) and **#12753** (compact reverse deltas, merged
  2026-11-08) rewrote the history to reverse deltas.
  - This cut memory 5-8x.
  - The PR states "Undo speed is about the same" and "The recorder still holds one full copy of the current state".
  - Capture time was not the goal.
- **Restore API on master:** `Recorder.restoreTo(...)`, last-in-first-out, and it refuses mementos that are not in the
  chain.
  - Our `ShadowGame` calls `GameMemento.restore()` on a memento that the recorder created (`ShadowGame.java:991`).
  - **This will need adapting when we move the pin.**
- **master** still walks the full graph per capture and still treats anonymous classes as leaves (master
  `GameMemento.java:483`).
- **No issue found** about capture time.

### Open questions for the user

- Is an upstream PR here worth it for us? We take one capture per turn (about 2 per turn with the opponent's). At
  roughly 40 ms that costs about 5% of shadow time.
- Should we first measure how much of the graph is views? This needs a run with an instrumented local copy of the
  walker, which I have not done.

---

## 4. GameMemento restore leaves a stale ability host

### Root cause (confirmed and reproduced)

**The classification rule.** `GameMemento.computeKind` (`forge-game/.../undo/GameMemento.java:273-279`) treats any
`java.util.*` or `com.google.common.*` collection whose class name contains `$` (and not "Synchronized") as a **leaf**.
The comment there says: "JDK/Guava view classes (keySet(), subList(), ...) would write through to their backing
store".

**The mistake.** Multimaps built with Guava's `MultimapBuilder` are standalone collections, not views, but their runtime
classes are nested (`Multimaps$CustomSetMultimap`, `Multimaps$CustomListMultimap`). The rule therefore also makes them
leaves. A leaf is neither walked nor restored, and neither is anything reachable only through it.

**Where it hits.** `KeywordCollection.map` (`keyword/KeywordCollection.java:21-22`, built with
`MultimapBuilder.hashKeys().linkedHashSetValues().build()`) is one of them.
- Checked at runtime: its class is `com.google.common.collect.Multimaps$CustomSetMultimap`, and `GameMemento.kindOf`
  returns `LEAF` for it (`OBJECT` for `KeywordCollection` itself).
- Every card's keywords live in `KeywordCollection`s: `CardState.intrinsicKeywords` and `cachedKeywords`
  (`card/CardState.java:81, 99`), and the keyword changes.
- So no `KeywordInstance` and none of its traits (`abilities`, `triggers`, `replacements`, `staticAbilities`,
  `keyword/KeywordInstance.java:33-36`) is in any memento, unless something else reaches it.

**Why it shows up as a stale host.** Keyword-granted alternative costs (Evoke, Warp, and similar) are `SpellAbility`s
owned by a `KeywordInstance` (`card/CardFactoryUtil.java:2941-2960` for Evoke).
1. When the card is cast with that ability during the turn that is later undone, Forge sets the ability's host to the
   stack copy, and later to the card object in the graveyard or exile.
2. `GameMemento.restore()` brings the hand card back, but not the ability's `hostCard` field, because the ability was
   never captured.
3. The restored hand card offers an alternative-cost spell hosted by another object. `Spell.canPlayFromHost` refuses
   it, so the card can no longer be cast for that cost.

### Reproduction (done)

Probe, 12 AI-vs-AI games (AzoriousControl against itself and against EsperBlink), with a host check at every turn
start: every spell ability, trigger and static of every card must be hosted by that card.

| Mode | Turns | Stale hosts |
| --- | --- | --- |
| Plain play, no memento | 597 | 0 |
| Capture, then restore the same memento at once | 597 | 0 |
| **One-turn rollback** every 5th turn start (restore the previous turn start's memento) | 728 events, 159 rollbacks | **13** right after a rollback, 42 at later turn starts, in 4 of 12 games |

Every case found is a keyword alternative-cost spell, hosted by the card's object from the undone turn:
- Solitude in hand: the Evoke spell is hosted by Solitude's graveyard object. It was evoked and sacrificed in the undone
  turn.
- Quantum Riddler in hand: the Warp spell is hosted by its exile object.

So the defect needs a real rollback past a cast. It is not a Forge play bug, and not caused by our shadow code.

**Minimal upstream repro:**
1. `GameState` with Solitude and a white card in hand.
2. `GameMemento.Recorder.capture(game)`.
3. Cast Solitude for its evoke cost and let it be sacrificed.
4. `restore()` the memento.
5. Assert that every spell ability of the hand Solitude has `getHostCard()` equal to that card. It fails for the Evoke
   spell.
6. Equivalent player-visible version through Forge's own "Undo Last Decision" (#12147), which uses the memento: undo
   back past an evoke cast, and Solitude can no longer be evoked.

### Scope

Every field built with `MultimapBuilder` is a leaf for the memento. The persistent ones in forge-game are:

| Field | What is not restored after a rollback |
| --- | --- |
| `KeywordCollection.map` (`keyword/KeywordCollection.java:21`) | all keyword instances and their spells, triggers, replacements and statics (this candidate) |
| `Card.cantHaveKeywords` (`card/Card.java:184`) | "can't have [keyword]" effects |
| `Phase.untilMap`, `untilEndMap`, `registerMap` (`phase/Phase.java:51-53`) | "until end of turn" / "until your next turn" cleanup commands registered during the undone turn |
| `Zone.cardsAddedThisTurn`, `cardsAddedLastTurn` (`zone/Zone.java:53-54`) | "entered this turn" history |
| `KeywordCollectionView.map` (`keyword/KeywordCollectionView.java:15`) | the view side of keywords |

**Measured: everything a one-turn rollback fails to restore** (probe `BlindSpotProbe`).
- The probe fingerprints every object reachable from the `Game` at a turn start, walking further than the memento does:
  into leaf collections, anonymous classes, `forge.ai` and the controllers.
- It then captures, plays one turn, restores, and lists every slot whose content differs from the capture-time
  fingerprint.
- Run: 12 games over all 4 decks, 107 rollbacks. Checked at turn starts only.

| Slot that differs after the rollback | Objects | Why the memento missed it |
| --- | --- | --- |
| `Zone.cardsAddedLastTurn` (contents) | 666 | `MultimapBuilder` leaf |
| `SpellAbility.activatingPlayer`, `targetChosen`, `hostCard`, `triggeringObjects`, `changedTextColors/Types`, `paidLists`, `payingMana`, `paidAbilities`, restriction `zone` of keyword spells | 175 / 6 / 3 / 3 / 3 / 3 / 1 / 1 / 6 | reachable only through `KeywordInstance.abilities`, under the `KeywordCollection` leaf |
| `Phase.untilMap` (contents) | 29 | `MultimapBuilder` leaf |
| `AiController.skipped`, `lastAttackAggression` | 214 / 11 | `forge.ai` is not walked (by design; AI memory) |
| `TrackableObject.props`, `ManaCost.compareWeight` | 3 / 2 | view and cached value; harmless |

Nothing else differed. Apart from Forge AI's own memory, every game-state blind spot found in these decks comes from
the `MultimapBuilder` leaf rule, so fix option 1 below would cover all of them.

Limits of this measurement:
- Checked at turn starts only. `cardsAddedThisTurn` is empty at both points, so it does not show here, but it is
  covered by the same rule.
- Only the four Modern decks, so other cards may reach other leaves (anonymous ability classes did not show up).

The `Phase` and `Zone` rows probably matter to our shadow game too. They are plausible causes for some of the remaining
"effects after a rebuild / triggers at a different point" divergence classes. **Unverified.**

`Collections.synchronizedList` fields (`ExtraPhase`, `ExtraTurn`) contain "Synchronized" and are walked.
`GameMemento.Recorder.debugBlindSpots` lists leaf-held fields and would have shown all of the above.

### Fix options

1. **Narrow the leaf rule in `GameMemento.computeKind`.** Walk Guava's standalone multimap implementations
   (`Multimaps$Custom*`) as `MULTIMAP`, and keep only real view classes as leaves. One rule fixes every row of the scope
   table.
   - Restore already does `clear()` + `put` for `MULTIMAP` (`GameMemento.java:158-164`), which is safe for these classes.
   - Cost: more objects per capture (every keyword instance and its traits), so capture gets somewhat slower
     (candidate 3).
   - Risk: low. These multimaps are owned by their fields, not views.
2. **Use named Guava classes in Forge** (for example `LinkedHashMultimap.create()` / `ArrayListMultimap.create()`) for
   these fields.
   - Fixes it without touching the memento.
   - It is a per-field change, and the next `MultimapBuilder` field breaks it again.
   - The `KeywordCollection` comment warns about enum keys being slow, so the replacement must keep hash keys.
3. **Explicitly restore trait hosts after a restore.** This is what our `ShadowGame.repairHosts()` does. It is a
   symptom fix: the other fields in the scope table stay unrestored.

**Most acceptable upstream:** option 1, with the minimal repro above as a test. Frame it around the player-facing undo
(#12147), since that is Forge's own use of the memento. Mention `debugBlindSpots` as the way to check for other leaves.

### Upstream status

- Nothing found. Searches: "memento", "undo host card".
- master keeps the same rule (master `GameMemento.java:474-476`) after the delta-memento rewrite (#12603, #12753).
- master `KeywordCollection` still uses `MultimapBuilder`, so master has the same defect. **Unverified by a run on
  master.**

### Open questions for the user

- Should our own workaround be widened beyond `repairHosts()`? Restoring the `Phase` and `Zone` multimaps and the
  keyword collections ourselves after a restore is in our code, so it is allowed. That is for the phase 2 shadow work to
  decide; I did not change it.
- Is this worth an upstream PR now? It is a clean, small, player-visible fix and a good first contribution.

---

## 5. GameCopier / Combat copy gaps

### Root cause

**(a) Sideboard sources of static effects.**
- `GameAction.checkStaticAbilities` collects static abilities with `game.forEachCardInGame(..., true)`
  (`GameAction.java:1122-1139`; the `true` at `:1139` includes the sideboard, `Game.java:859-881`).
- So a sideboard card with an `EffectZone$ All` static becomes the source of a live `StaticEffect`. Devoid is
  `K:Devoid`, which becomes a CDA static with `EffectZone$ All`. Examples: Thief of Existence, Sowing Mycospawn,
  Basking Broodscale.
- `GameCopier` copies only Battlefield, Hand, Graveyard, Library, Exile, Stack and Command
  (`forge-ai/.../simulation/GameCopier.java:39-47`, loop `:283-288`).
- `copyGame` then calls `effect.removeMapped(gameObjectMap)` for every static effect (`:185-187`).
- `StaticEffect.makeMappedCopy` maps the source (`StaticEffect.java:62-70`), and `GameCopier.find` throws
  "Couldn't map" for the uncopied sideboard card (`:589-606`).

**(b) Attacked planeswalker that leaves combat.**
- `Combat.removeFromCombat(c)` (`combat/Combat.java:636-670`) replaces a planeswalker or battle that left in
  `attackedByBands` with an id -1 stand-in (rule 506.4c).
- It does **not** remove `c` from `attackableEntries`: the loop at `:654` only reads it.
- The copy constructor `Combat(Combat, IEntityMap)` maps every `attackableEntries` element (`:84-86`). The departed
  card is in no zone, so `map.map` throws.
- The same constructor already handles the stand-in for `attackedByBands` (`:102-108`), but not for
  `attackableEntries`.

### Minimal reproduction

**(a) Sideboard:**
1. Any game where a player's sideboard holds a card with `K:Devoid`.
2. Call `checkStaticAbilities()`, then `new GameCopier(game).makeCopy()`.
3. Throws "Couldn't map" on the pinned commit.
4. Evidence: 14,683 of 14,701 copies failed in Broodscale vs AzoriousControl, 10 matches.

**(b) Combat:**
1. Attack an opponent's planeswalker token (the Jace token).
2. Remove the planeswalker during combat, before damage (bounce it, or kill it with an instant after blockers).
3. Copy the game while combat is still on.
4. Throws "Couldn't map".
5. Evidence: AzoriousControl vs EsperBlink, the `CopyCheck` harness (`report-gamecopier.md` in the shared notes).

### Scope

**Other zones the copier skips:** Ante, Junkyard, SchemeDeck, PlanarDeck, AttractionDeck, ContraptionDeck,
StickerSheets.
- `forEachCardInGame` visits `PART_OF_COMMAND_ZONE` (`ZoneType.java:39`), which includes most of these.
- The copier copies only `Command` from that set.
- Static sources in Junkyard or the Attraction/Contraption decks would fail the same way. Not seen in Modern.

**Combat:**
- `blockedBands`, `attackersOrderedForDamageAssignment`, `blockersOrderedForDamageAssignment` and `damageMap` map cards
  that are normally still in a zone.
- A blocker removed from combat is unregistered (`:648-651`), so these look safe.
- `attackableEntries` is the outlier.

### Fix options

**(a) Sideboard.** Two options:
- **Copy the sideboard zone.** Cheap. The copy then matches what `checkStaticAbilities` sees.
- **Skip effects whose source is not in a copied zone.** This is what master did (below). Safe for simulation, since a
  sideboard card's static has no effect on the board.

**(b) Combat.** Two options; both are a few lines:
- **In `removeFromCombat`,** also remove the departed planeswalker or battle from `attackableEntries`.
  - It is no longer attackable.
  - Check `getDefenders()` callers and `AttackConstraints` for anything that expects it.
- **In the copy constructor,** skip, or substitute the id -1 stand-in for, entries that cannot be mapped. Mirrors the
  existing `attackedByBands` handling.

**Most acceptable upstream:**
- (b): fix `removeFromCombat`, because it corrects state rather than papering over the copy, plus a GameCopier test in
  the style of master's `GameCopierOrphanEffectTest`.
- (a): already handled on master. Copying the sideboard is optional; mention it in the same PR or skip it.

### Upstream status

- **(a)** Commit `9c77436427` "GameCopier: an unmappable static effect source no longer fails the whole copy"
  (kevlahnota, 2026-10-31, after our pin):
  - master `GameCopier.java:200-209` maps effect sources leniently (by id too) and skips effects whose source is not in
    the game;
  - adds `forge-gui-desktop/src/test/.../GameCopierOrphanEffectTest.java`.
  - The sideboard is still not copied (master `GameCopier.java:36-44`).
  - The stated motivation is LKI copies left by zone changes, not the sideboard. It probably also covers our case;
    **unverified**.
- **(b)** Unchanged on master: master `Combat.java:84-86` still maps `attackableEntries`. No issue found. Searches:
  "Couldn't map", "removeFromCombat", "attackableEntries".
  - Related, not the same: #12590 (steps skipped when all attackers left combat).

### Open questions for the user

- File (b) alone, now that (a) is mostly done upstream?
- Should our own `GameCopier` workaround be compared with master's lenient mapping when we move the pin?

---

## 6. `AiController.orderPlaySa` changes the list it is given

### Root cause

**What the method does to its input.**
- `orderPlaySa(activePlayerSAs)` (`forge-ai/.../AiController.java:3074-3123`) calls `filterListByApi(activePlayerSAs, …)`
  seven times (`:3081-3093`).
- `filterList` (`:3126-3130`) returns the matching items **and** does `input.removeAll(filtered)` on the caller's list.
- The result is built as a copy of what is left, then the removed groups are appended (`:3095-3121`).
- So the caller's list loses every Discard / Draw / PutCounter / PutCounterAll / Token / Pump / PumpAll ability.
- Inconsistent contract: with fewer than 2 entries the method returns the **same list object** (`:3076-3078`); otherwise
  it returns a new list.
- The `removeAll` has been there since 2016 (`0c2b9e744551`, Hanmac).

**Why stock Forge never notices.**
- The only caller, `MagicStack.chooseOrderOfSimultaneousStackEntry` (`zone/MagicStack.java:945-976`), passes its private
  scratch list `activePlayerSAs` and clears it right after.
- `PlayerControllerAi.orderAndPlaySimultaneousSa` (`PlayerControllerAi.java:1441-1442`) iterates only the returned list.
- An embedder that keeps using its own list after calling `PlayerController.orderSimultaneousSa` gets a corrupted list.
- The method's contract is not documented (`PlayerController.java:103`).

### Minimal reproduction

1. Pure unit test: a list of two triggered abilities, one Draw and one non-Draw.
2. `List copy = new ArrayList(list); controller.orderSimultaneousSa(list);`
3. Assert `list.equals(copy)`. It fails: the Draw ability is gone from `list`.
4. Our evidence: 72 unmapped trigger-order answers in one 16x10 gate run before our copy workaround
   (`AgentPlayerController.orderSimultaneousSa`).

### Scope

Public AI or controller entry points that mutate a list argument (found by scanning forge-ai for methods that call
`remove`/`removeAll`/`sort`/`reverse` on a parameter):

| Entry point | Mutation |
| --- | --- |
| `PlayerControllerAi.orderBlockers` → `AiBlockController.orderBlockers` | sorts the caller's `blockers` in place (`AiBlockController.java:1247-1250`) |
| `PlayerControllerAi.orderAttackers` → `AiBlockController.orderAttackers` | sorts in place (`:1317-1320`) |
| `PlayerControllerAi.chooseCardsToDelve` → `AiController.chooseCardsToDelve` | removes chosen cards from the caller's `grave` (`AiController.java:2571`) |
| `PlayerControllerAi.chooseCardsForSplice` | sorts the caller's `cards` (`PlayerControllerAi.java:1807-1809`) |
| `PlayerControllerAi.chooseCardsToDiscardFrom` → `AiController.getCardsToDiscard` → `chooseCardsToDiscard` | removes from and sorts `validCards` (`AiController.java:1245-1327`); the caller's collection is passed straight through (`PlayerControllerAi.java:739-741`) |
| `CharmAi.chooseOptionsAi` | reverses and removes from `choices` (`ability/CharmAi.java:115-167`) |
| `VentureAi.chooseCardFace` | `removeIf` on `faces` (`ability/VentureAi.java:85`) |

Internal helpers with the same habit, safe today because their callers pass fresh lists:
- `ComputerUtil.chooseExileFromList`, `filterCreaturesThatWillDieThisTurn`;
- `ComputerUtilCombat.totalDamageOfBlockers`;
- `AttachAi.attachToPlayerAIPreferences`;
- `TapAiBase.tapTargetList`;
- `ComputerUtilMana.getConvokeOrImproviseFromList`;
- `AiBlockController.makeChumpBlocks`.

Callers in forge-game mostly pass fresh or scratch lists. Each row above should be checked against its caller before it
is listed as a bug.

### Fix options

1. **In `orderPlaySa`, work on a copy** (`new ArrayList<>(activePlayerSAs)` at the top, and return a new list in the
   size < 2 case too).
   - One line, no behaviour change: the result is the same ordering.
   - Zero risk to AI behaviour.
2. **Make `filterList` non-destructive** (return both partitions).
   - Changes every caller of `filterList` / `filterListByApi` / `filterListByAiLogic`. One external use is
     `ComputerUtilAbility.java:476`, which passes a fresh copy.
   - More churn for the same effect.
3. **Document `PlayerController` methods** as "must not modify arguments", and fix the controller-boundary rows of the
   scope table the same way as option 1.

**Most acceptable upstream:** option 1 as a tiny PR with a unit test. Option 3 for the other controller-boundary methods
(orderBlockers, orderAttackers, chooseCardsToDelve, chooseCardsForSplice, chooseCardsToDiscardFrom), each a defensive
copy, either in the same PR or a follow-up "AI controller methods no longer modify their arguments".

### Upstream status

- **master:** unchanged (master `AiController.java:3558-3562` still `input.removeAll(filtered)`).
- The only search hit for `orderPlaySa` / `orderSimultaneousSa` is #12677 (open, planeswalker and wheel AI). It did not
  show the method's input handling.
- No issue found.

### Open questions for the user

- One PR for `orderPlaySa` only, or the broader "controller methods don't mutate arguments" sweep? The sweep needs each
  caller checked; I listed candidates but did not verify every caller.

---

## 7. `ComputerUtilMana.getManaSourcesToPayCost` pays the cost it is given

### Root cause

**What the method does to its argument.**
- `getManaSourcesToPayCost(cost, sa, ai, effect)` (`forge-ai/.../ComputerUtilMana.java:99-105`) calls the private
  `payManaCost(cost, sa, ai, test=true, checkPlayable=true, effect)` (`:619-874`).
- In test mode, that method still mutates the `ManaCostBeingPaid` argument:
  - `adjustManaCostToAvoidNegEffects(cost, …)` adds shards (`:631`; `cost.increaseShard` at `:1374`);
  - pays from the pool (`manapool.payManaCostFromPool(cost, …)` `:655`; `tryPayCostWithColor` `:676`);
  - `cost.payPhyrexian()` / `decreaseShard` (`:767-773`);
  - `payMultipleMana(cost, …)` and `cost.addManaCost(…)` for every planned source (`:815-818`).
- Afterwards the caller's cost reads as paid.

**Other side effects in test mode:**
- `AiCardMemory.clearMemorySet(PAYS_TAP_COST / PAYS_SAC_COST)` (`:627-630`).
- `manapool.restoreColorReplacements()` and mana conversions applied to the real pool (`:639-652`).
- On failure, the caller's `cost` object itself is stored in AI memory `UNPAID_COSTS` (`:860-864`). If the caller later
  mutates or reuses it, AI memory changes with it.

**Forge relies on the mutation in one place.** `getConvergeCount` (`:83-89`) calls `payManaCost(cost, …, test=true)`
and then reads `cost.getSunburst()`. So "work on a copy inside" must keep that path working.

### Minimal reproduction

1. AI with untapped lands, a spell with a mana cost.
2. `ManaCostBeingPaid c = ComputerUtilMana.calculateManaCost(sa.getPayCosts(), sa, ai, true, 0, false);`
3. Record `c.toString()` / `c.getConvertedManaCost()`.
4. Call `getManaSourcesToPayCost(c, sa, ai, false)`.
5. Compare: `c` is now paid (CMC 0).

Upstream already observed this (below). Our evidence: Forge AI through our decision layer won 32.6% against 51.7% for
plain Forge AI (16x10); 46.0% once the planner got a copy (`AgentPlayerController.priorityFor`).

### Scope

**Same API, other callers:**
- `AiController.reserveManaSources` (`AiController.java:858-868`) passes freshly calculated costs. Safe.
- `InputPayMana` (`forge-gui/.../InputPayMana.java:458-462`) already makes `new ManaCostBeingPaid(manaCost)` before
  calling it. Upstream code that knows about the side effect.

**Related test-mode payment entry points with the same side effects:**
- `canPayManaCost` / `payManaCost(Cost, …)` with `test=true` build their own `ManaCostBeingPaid` (`:74-77`). Safe for
  the cost, but they still touch AI memory and the pool's conversion matrix.

**The copy constructor is shallow in one field:** `ManaCostBeingPaid(ManaCostBeingPaid)`
(`forge-game/.../mana/ManaCostBeingPaid.java:128-137`) copies `sunburstMap` by value (it is a `byte`, `:121`), so the
copy is complete. `xManaCostPaidByColor` is copied with `Maps.newHashMap`.

### Fix options

1. **Copy inside `getManaSourcesToPayCost`** (`new ManaCostBeingPaid(cost)` before planning).
   - Smallest change.
   - The other callers are unaffected: `reserveManaSources` passes fresh costs, and `InputPayMana` copies anyway.
   - `getConvergeCount` does not go through this method, so it is unaffected.
   - Recommended.
2. **Copy inside the private `payManaCost` whenever `test` is true.**
   - Breaks `getConvergeCount`, which reads the sunburst from the mutated cost. That would need to return the copy or
     the count.
   - Broader effect; more review.
3. **Document the side effect** (javadoc "plans by paying `cost`; pass a copy").
   - Zero risk, but leaves the trap.
   - Acceptable as part of option 1's PR.

**Most acceptable upstream:** option 1 plus the javadoc, with a unit test that asserts the argument is unchanged.
Mention that `InputPayMana` already copies and that #11487 found the drained cost.

### Upstream status

- **#11487** "Don't count mana sources against the cost when reserving them" (omraj21, merged 2026-08-02).
  - It states that planning a payment drains the `ManaCostBeingPaid` passed in, so a CMC check after it always passed.
  - It fixed the caller (`reserveManaSources`), not the method.
- **master:** `getManaSourcesToPayCost` is unchanged (master `ComputerUtilMana.java:99-104`). It was touched by later
  commits (#12557, #12727, the FlatMC series), but not in this respect.

### Open questions for the user

- Include the AI-memory side effect (the `UNPAID_COSTS` aliasing) in the same PR, or keep the PR to the cost argument?
