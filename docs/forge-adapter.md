# forge-adapter implementation notes

Implementation notes for `adapter/forge-adapter` (phase 1). Design decisions live in the design doc and in
"1단계 프로토콜 설계 질문" (https://claude.ai/artifact/REeQ22B7Pbths95ensfD8D, Q1-Q16); this file only records how
the adapter implements them and what is still approximate.

## Pieces

| Class | Role |
| --- | --- |
| `ForgeMatchRunner` | Bo3 match: creates each game through `GameCreation`, installs the controllers, runs the game, sends `GameOver` |
| `ForgeGameSession` | One game as protocol: subscribes to Forge's event bus, per-seat event streams, turn-start snapshots, decision requests, the game log |
| `EntityIds` | Per-game uint64 IDs. A new ID on every zone entry, found by comparing each card's zone with the last one seen (also catches moves Forge makes without an event); moves found together are issued by ascending previous ID (Q4) |
| `CardKeys` | Card -> oracle_id (`oracle-ids.tsv`), face numbers, spell keys, ability keys. Printed abilities are matched to oracle-text paragraphs by word overlap; misses go to `ability-overrides.tsv` |
| `ObservationBuilder` | `Observation` for one seat, `Privileged` for the log |
| `ObservedAiController` | Forge AI that reports what it does: the opponent's actions for the agent seats' streams, and for agent seats the few methods left as intended delegations. Overrides every `PlayerController` decision method; unclassified ones are counted |
| `RlPlayerController` | Agent seats, played the way a human plays (Forge's `PlaySpellAbility`) with agent-core answering every choice: see "Decisions" below and `docs/player-controller-methods.md` |
| `AgentCostDecision` | The cards non-mana costs use (sacrifice, discard, exile, tap, return), asked of agent-core |
| `ManaPayments` | Pool-only castability and payment checks (no auto-tap); they never change the pool |
| `AgentLobbyPlayer` | Lobby player of every seat: when one player controls another (Emrakul), gives the controller that decides through agent-core |
| `CandidateCheck` | Sampled check of the priority candidates against Forge's own pool payment on a game copy (`-Dmagicdosa.candidateCheck=N`) |
| `DecisionClient` | The connection to agent-core: `GrpcDecisionClient` (Hello + one bidirectional stream per game; a failed stream fails every later call of that game, recorded as an unintended fallback) |
| `ForgeRandom` | Per-thread seeded `MyRandom` (inheritable: Forge AI spawns a thread per decision) |

## Tables

- `src/main/resources/magicdosa/adapter/forge/oracle-ids.tsv` is generated: run
  `magicdosa.adapter.forge.tools.OracleTableBuilder <oracle-cards.jsonl.gz> <decks dir> forge/forge-gui/res <out>`
  with Scryfall's "Oracle Cards" bulk file. Tokens are resolved through Forge's edition `[tokens]` sections and one
  Scryfall API call per token print. Rebuild when decks change.
- `ability-overrides.tsv` is hand-written: card, start of Forge's description, `keyword:<Name>`,
  `printed:<face>:<index>`, or `rule:<name>`: a rule key (Q17, a keyword key with the rule's name and no parameters)
  for a permission a game rule grants (adventure; prepared would be keyed the same way, not built).
- A delayed trigger without text of its own (Emrakul's extra turn) is keyed by the ability that created it.
- An ability a copy effect brought (Shifting Woodland as a copy of Urza's Saga) is matched against the copied card's
  text, also after the copy effect ended.

## Harnesses (test sources, not run by mvn install; working directory forge/forge-gui)

Each harness takes an optional last argument for reaching agent-core: `spawn` (default: a separate agent-core JVM on
a free localhost port, over gRPC), `host:port` (a running agent-core) or `local` (agent-core's game handling
in-process, no gRPC; `LocalDecisionClient`).

- `harness.RandomGames` (default exec main): random policy in seat 0 vs Forge AI. PASS needs zero unintended
  fallbacks, protocol errors, unmapped names and (with a log dir) malformed logs.
- `harness.AdapterDeterminism`: the same matches twice in one JVM with the same seeds; every game log must be
  byte-identical.
- `harness.LogReplay`: plays matches with logs, then replays each from its logs alone (`ReplayDecisionClient`
  answers with agent-core's logged responses, same seed); every request and every game log must match.
- `harness.ControlTurnCheck` (deck item 4): the opponent's third turn of every game is handed to the agent seat with
  `Player.addController`, as Emrakul's effect does; the requests for it must carry `acting_seat`, and the games must
  finish without fallbacks or log problems. It hands the turn over directly instead of building a `GameState` right
  before Emrakul is cast, so it does not depend on the random policy managing to cast Emrakul.
- `harness.DamageAssignmentCheck` (deck item 3): at the start of the agent seat's third turn the agent deck's
  tramplers (Emrakul, Sire of Seven Deaths) are put onto its battlefield from library, hand or graveyard, ready to
  attack; there must be DAMAGE_ASSIGNMENT requests and no fallbacks or log problems. The random policy alone did not
  reach one in the 16 x 60 all-pairings run.
- `harness.CopyCheck <decks dir> <n>`: every pairing, n matches, copies the whole game with agent-core's
  `GameCopier` at every priority decision of the agent seat (`-Dmagicdosa.copyCheck=1`) and maps every card of every
  zone back; PASS needs 0 copy failures. RandomGames and AllPairings count the copies the candidate checks make and
  fail on a copy failure.
- `harness.LogDump <log> [seat] [from] [to] [decisions]` prints a log as text; with `decisions`, decision records too.
- The random policy is agent-core's `RandomPolicy` (uniform, multi-selects step by step, honours the Q15 combination
  rules); agent-core's `GameSession` checks sequence numbers, snapshots and `applied_seq` and closes the game's
  stream on a protocol error.
- RandomGames prints unclassified decisions, refusals by kind (an ignored constraint fails the run: deck item 1) and
  the sampled candidate check (on by default, every 20th priority decision). Its summary (2026-11-06 plan, section 4)
  also has match and game wins, end reasons and average turns; the Q14 priority stops (requests per game, auto
  passes, share of requests with an action other than pass and mana abilities); decisions, intended delegations and
  refusals per decision type; throughput (seconds per game, time waiting for agent-core per decision, one match on
  one thread, heap); safety caps. Every run also writes it to `runs/<date>-RandomGames-<n>/summary.txt` beside the
  decks directory (`runs/` is ignored by git). With `-Djvm.args="... -Dmagicdosa.shards=<dir>"` the spawned
  agent-core writes training shards there, and the run fails on any shard error or vocabulary miss.
- `harness.AllPairings <decks dir> <n> <log dir>`: RandomGames for every (agent deck, Forge AI deck) pairing,
  mirrors included, in `runs/<date>-AllPairings-<n>/summary.txt`. First a gate table over all pairings (the phase-1
  final gate): fallbacks, protocol errors, unmapped names, unclassified decisions, ignored-constraint refusals, other
  refusals by cause, every protocol decision type with its counts and the types never seen, the watched intended
  delegations (`chooseNewTargetsFor`, `cost:*`, `chooseTargetsFor(divided)`, `orderCosts`), priority stops and
  throughput; then one line per pairing and every pairing's report. `--threads N` (2026-11-10) runs N pairings at once against one agent-core
  (each game on its own stream; every match seeds its own thread's Forge random; results are read back in pairing
  order); with threads a pairing's own unmapped list may name what another pairing met first, the gate line counts the
  whole run. `-Dmagicdosa.agentXmx=6g` gives the spawned agent-core more heap (it keeps each open game's decisions
  for its shard). Determinism checks and LogReplay stay sequential.
- `harness.RevealCheck`: self-play (both seats agent seats); a hand card shown to the opponent (Thoughtseize) must
  reach the owner's stream as "the opponent saw it" (Q10), with no reveal leaks.
- Runaway-repeat safety cap: the same priority action chosen more than 200 times in one turn and phase is not played;
  the seat passes and the decision log records `safety_cap:chooseSpellAbilityToPlay`. A "you may" trigger accepted
  more than 200 times there is declined. A test guard only; loop shortcuts (CR 732) are not modelled.

## Event order and trigger IDs

- Triggered abilities get their ID when they trigger (`AbilityTriggered`), in Q4 order (APNAP, source ID, ability
  key). Forge fires no event for this: triggers wait in `MagicStack`'s private simultaneous-entry list, which the
  session reads (reflection, read-only) at every look, and in the entries a controller is handed for ordering (Forge
  takes them out of the list first). Put on the stack: `TriggeredAbilityStacked` with the same ID; never put on it:
  `TriggeredAbilityRemoved`. Static triggers (resolved at once, no stack) get no ID.
- Actions come before the changes they cause: while a controller plays something (`playChosenSpellAbility`,
  `playSaFromPlayEffect`, `orderAndPlaySimultaneousSa`), and while an action event is translated, events are built
  at once but numbered and sent only after the action event. A decision asked in the middle (targets) has
  `applied_seq` before the held-back events; its observation already shows the card on the stack (601.2a).
- Copies of spells and abilities are `ObjectCreated` (`SPELL_COPY`, `ABILITY_COPY`), not `CastSpell`.
- A card found in no zone is between zones (Forge fires events in the middle of a move) and keeps its ID; only tokens
  cease to exist. Before this, such a move showed up as the card leaving to `ZONE_NONE` and a new object appearing,
  so `card_id` of `CastSpell` / `PlayLand` was often 0.
- `LogCheck` checks per seat: `CastSpell` before the spell's move to the stack, `PlayLand` before the land's move, and
  every stacked or removed triggered ability announced by `AbilityTriggered` first, once. Per log it checks step
  bundles (Q18): steps numbered from 1 without gaps, the first step's decision ID as the bundle ID, and a `BundleEnd`
  with the step count before the game ends (bundles may nest). `LogDump` prints a log as
  text for debugging.

## Decisions (2026-11-06 plan, section 2)

Every choice of an agent seat goes to agent-core except a few recorded intended delegations; the full list of
Forge's decision methods and their handling is `docs/player-controller-methods.md`.

- Casting and activating go through Forge's human play path (`PlaySpellAbility`, 601.2): modes, X and optional or
  alternative costs, targets, then costs. A choice with one possible outcome is made without asking.
- Mana (2026-11-06): no auto-tap. Mana abilities are priority actions (they count for the Q14 stop rule); a spell or
  ability is a priority candidate only when the pool and its non-mana costs can pay it (`ManaPayments`, X at its
  smallest, cost reductions at their largest). Payment is one MANA_PAYMENT request per step (Q18: a step bundle, below): which pool mana (grouped
  by color, source and restriction), or 2 life for a Phyrexian shard; a step with one possible outcome is paid without
  asking. A cost paid while something resolves (605.3a: Spell Pierce, ward) may also activate mana abilities and may
  stop unpaid. The request context carries the spell being cast (targets, X) and the mana paid so far. Spells the pool
  cannot pay but the seat's sources could are listed as `Observation.potential_casts` (approximate).
- Forge may put the same `Mana` object into the pool twice ({C}{C} from Eldrazi Temple); the checks remove one
  occurrence per use.
- Triggers: the order (TRIGGER_ORDER over the IDs issued when they triggered, first picked resolves first), "you may"
  triggers (yes/no when they resolve), and their targets and costs (like a cast). Copies keep Forge's order.
- Choices while resolving (cards, entities, yes/no, numbers, modes, counters, replacement effects, opening-hand
  actions): CHOOSE_CARDS / CHOOSE_OPTION. Cards the chooser cannot see yet (a search, the top of the library) are first
  shown to it in its own stream (`CardRevealed`, LOOKED_AT). Scry and surveil: which cards leave the top, then the order
  of each pile (top to bottom).
- Sideboarding: one (in, out) swap per SIDEBOARD request, "done" being `PassCandidate`, at most 15; each request lists
  what the changed deck allows (Q18). Forge AI opponents keep their main deck. Each match plays copies of the decks,
  since Forge changes the registered deck in place. The match context lists the opponents' cards seen in earlier games.
- The order cost reductions apply in (601.2f; Forge `chooseSingleStaticAbility`, called by `CostAdjustment.adjust`
  once per reduction with the ones left; 2026-11-10 decision). A call is on a real payment only between
  `RlPlayerController.payManaCost` (outside the adapter's own checks, `checkingCosts` false) and the start of
  `applyManaToCost`: that is where Forge's `PlaySpellAbility.payManaCost` adjusts the cost it then pays. Every other
  call (the castability and look-ahead checks, Forge's test payments, Forge AI estimates on the agent seat's behalf)
  only computes a cost: it gets Forge AI's pick (the first), is counted, and is neither a decision nor a delegation.
  Before a real payment the adapter runs Forge's cost adjustment in test mode once per order of the reductions, with
  the order scripted through the same method: one reduction, or the same final cost for every order, needs no choice;
  different final costs are a CHOOSE_OPTION over the distinct final costs (orders with the same result are one
  candidate), and Forge is then answered with an order that gives the chosen cost. No proto change.
- Step bundles (Q18, 2026-11-07): a multi-choice whose candidates change with earlier picks (mana payment,
  sideboarding) is one request per step, each with `DecisionRequest.bundle` (ID = the first step's decision ID, step
  from 1); the log closes it with `BundleEnd` (step count, `completed`: false only when it was cut short, by an
  unintended fallback, or a cast payment with no way on). A cost paid during resolution that nothing left can finish is
  completed (the cost is not paid). A multi-choice with a fixed candidate list stays one request with `steps[]` (Q9).
  Shards carry `bundle_id` and `bundle_step`.
- Naming (deck item 6): NAME requests; card names from both players' 75, creature types from the seat's 75.
- Combat damage among several recipients (deck item 3): DAMAGE_ASSIGNMENT, one request per recipient in entity ID
  order, 0 .. min(lethal, left), the rest automatic; the context carries lethal damage and Forge AI's own division.
- Control of another player (deck item 4): `acting_seat` on the requests made for the controlled player;
  `PlayerState.controlled_by_seat`. An opponent controlling an agent seat plays it with Forge AI and nothing is asked.
- Refusals (deck item 1) are recorded with the engine's reason and a kind: ignored constraint (fails RandomGames),
  missing constraint (a pair rule the request could not state, or a priority candidate that turned out not
  playable), engine bug. A refused priority action is asked again with the refusal (Q15, a safety net); after three refusals, an
  unintended fallback and a pass. Since 2026-11-10 every refusal counts as an adapter defect (target 0); the
  2026-11-08 workaround that left a refused action out of the re-ask was removed (candidates must not depend on
  history: fingerprint L3).

## Not done / approximate

- Intended delegations kept in phase 1 (2026-11-07, counted by AllPairings, not moved): the order cost parts are paid
  in (`orderCosts`), new targets for a copy (`chooseNewTargetsFor`), the uncommon
  forms of non-mana costs (`cost:*`), dividing damage among targets as a spell is cast (`chooseTargetsFor(divided)`).
- Castability is a greedy check of the pool and does not run the payment on a copy; the sampled check found no
  omission so far. X and the other values announced while casting (601.2b; March of Otherworldly
  Light: the number of white cards to exile, then X) are offered only where the whole cost can still be paid with
  the values announced so far and some value of each announcement still to come (non-mana costs payable, the pool
  pays the adjusted mana cost), whichever order Forge asks in. The cards an exile-from-hand cost uses, also in the
  "other than this card" form (Card.White+Other: March, Solitude's evoke; Card.Blue+Other: Force of Negation), are a
  CHOOSE_CARDS request with the announced count (2026-11-10).
- Reveals come from Forge's card visibility (`CardView.canBeShownTo`) and from what a seat is shown (its controller's
  `reveal`, or a choice among hidden cards); a card Forge shows only in a dialog without changing its visibility is
  covered only through those two paths.
- Effect durations are mapped from the creating ability's `Duration` parameter (default: until end of turn); a shield
  is an effect card with a replacement effect. An effect a game rule makes (the adventure exile) has no ability key.
- Library knowledge: a card put into a library is located from the end it went to (upper half: top); several put on
  the bottom in an order the seat did not choose are a group. Positions are counted from the real library at each
  observation, so cards put above or below a known card shift it as the seat would expect.
- Log replay (Q19, decided 2026-11-07): in phase 1 replay means the agent seats are scripted from the log and the
  Forge AI opponent reruns on the same seed; the Forge AI baseline keeps its own play path. Replaying the opponent
  comes in phase 2 as event-log replay, reusing the shadow game's re-apply of the turn's event stream.

## Fields and events (2026-11-06 plan, section 3)

- Chosen modes (0-based, oracle order) in `CastSpell`, `ActivateAbility`, `TriggeredAbilityStacked` and stack items.
- Mana (Q6): the pool in the observation (color, source, restriction in the card's words, persistent);
  `ManaAdded` / `ManaSpent` / `ManaPoolEmptied` from a diff of each pool at every look (Forge's pool event does not
  say which mana); `ActivateManaAbility` for mana made as a priority action or while paying during resolution.
- Effects (Q5, `GameFacts`): Forge's effect cards (lasting effects, shields) and registered delayed triggers (read from
  `TriggerHandler`'s private list) get IDs; `EffectCreated` / `EffectEnded`; `effects_provided = true`, so turn-start
  snapshots carry the effects that outlive the turn; a delayed trigger that fires carries `effect_id`.
- `GameEndReason` from the loser's loss reason (life, poison, decked, card effect, concede) or a card that wins.
- Designations (Q13): monarch, initiative, city's blessing, Ring tempted, dungeons completed; `DesignationsChanged`.
  Energy and the like are player counters.
- Library positions (E3): `GameObject.library_position` (from top, from bottom, bottom group) for cards a seat knows,
  with the card named; `LibraryArranged` for scry and surveil (counts for every stream, where each card went for the
  seat that arranged them); a shuffle ends the knowledge.
- Reveal targets (Q10): when a seat sees a hidden card, every stream hears of it; the card is named only in streams
  of seats that saw it. `LogCheck` fails a stream that names a card its seat did not see, and checks every stated
  library position against the real order recorded with the request. `harness.RevealCheck` (self-play) requires the
  fact "the opponent saw this card of yours" to appear.
- Tokens (Q3): every token the four decks make maps to a Scryfall token oracle_id (the table builder now also knows
  Empower's Jace token). A token that does not gets `token_fallback` (the ability that made it + its place in that
  ability's token list) and is listed by RandomGames instead of counting as unmapped.
- Hello carries match_id and game number (checked).

## Results (2026-11-02, IzzetProwess random vs EsperBlink Forge AI)

In-process stand-in for agent-core (before the gRPC server):

- RandomGames 1000 matches / 2198 games, 53 min: unintended fallbacks 0, refusals 0, protocol errors 0, unmapped 0.
- AdapterDeterminism 20 matches: identical 20, different 0.
- LogReplay 10 matches: identical 10, 967 decisions replayed.

Over gRPC with a spawned agent-core JVM (protocol 4):

- RandomGames 1000 matches / 2203 games, 53 min: unintended fallbacks 0, refusals 0, protocol errors seen by agent-core 0,
  unmapped 0; agent-core answered 112021 decisions.
- AdapterDeterminism 20 matches (a fresh agent-core per run): identical 20, different 0.
- LogReplay 10 matches: identical 10, 967 decisions replayed.

Protocol 5 (trigger IDs at trigger time, actions before their state changes, LogCheck order rules), 2026-11-06:

- RandomGames 100 matches / 220 games with logs, 6 min: unintended fallbacks 0, protocol errors 0, unmapped 0, log
  problems 0.
- AdapterDeterminism 20 matches: identical 20, different 0.
- LogReplay 10 matches: identical 10, 967 decisions replayed.

Protocol 6 (section 2: every choice to agent-core, pool-only mana, sideboarding, naming, damage division, control of
another player), 2026-11-06:

- RandomGames 100 matches / 202 games with logs, 4 min: unclassified decisions 0, unintended fallbacks 0, refusals 0,
  protocol errors 0, candidate omissions 0 in 48 sampled checks, unmapped 0, log problems 0. agent-core decided 15734
  times (priority 10399, choose cards 1315, sideboard 1509, mana payment 260, ...).
- AdapterDeterminism 20 matches: identical 20. LogReplay 10 matches: identical 10, 1669 decisions replayed.
- ControlTurnCheck 10 matches (Broodscale vs EsperBlink): 173 decisions with acting_seat, fallbacks 0, log problems 0.
- All 16 deck pairings x 5 matches: PASS except the Azorius pairings that make the Jace planeswalker token (unmapped
  token, section 3).

Protocol 7 (section 3: modes, mana pool events, effects, end reasons, designations, library positions, reveal targets,
token fallback), 2026-11-07:

- RandomGames 100 matches / 202 games with logs (incl. the reveal and library-position log checks): unclassified 0,
  fallbacks 0, refusals 0, protocol errors 0, candidate omissions 0 in 48 checks, token fallbacks none, unmapped 0, log
  problems 0. Also PASS: IzzetProwess vs EsperBlink 20, AzoriousControl vs EsperBlink 20, AzoriousControl vs Broodscale 10.
- RevealCheck (EsperBlink self-play) 20 matches: 726 own hand cards reported as seen by the opponent, log problems 0.
- AdapterDeterminism 20 matches: identical 20. LogReplay 10 matches: identical 10, 1669 decisions replayed.

Sections 4 and 5 (summaries and runs/, safety cap, all pairings, encoder 2 and shards), 2026-11-07:

- AllPairings 16 pairings x 5 matches with logs: all PASS (runs/2026-11-07-AllPairings-5/summary.txt).
- RandomGames 100 matches / 202 games with logs and shards: PASS; 51.5 priority requests per game (51% with an action
  other than pass and mana abilities), 1.44 s/game, 0.37 ms/decision waiting for agent-core, safety caps 0; 202 shards,
  16003 rows, vocabulary misses 0; every shard reads and batches in Python.
- AdapterDeterminism 20/20 identical, LogReplay 10/10 (1669 decisions). agent-core tests 9, Python tests 5: pass.

Phase-1 final gate, protocol 8 / encoder 3 / vocabulary 2 (Q17 rule keys, Q18 step bundles), 2026-11-08:

- AllPairings 16 pairings x 60 matches with logs and shards (runs/2026-11-08-AllPairings-60/summary.txt): PASS.
  960 matches / 2007 games, 0 match errors. Unintended fallbacks 0, protocol errors 0, unmapped names 0, unclassified
  decisions 0, ignored-constraint refusals 0, adapter errors 0, log problems 0 (incl. the bundle rule), safety caps 0.
  Other refusals: 77, all `chooseSpellAbilityToPlay` MISSING_CONSTRAINT for March of Otherworldly Light (asked again).
  Shards: 2007 files for 2007 games, 0 errors, vocabulary misses 0.
- agent-core decided 194299 times: priority 140863, sideboard 15305, choose cards 13151, mana payment 5862, choose
  option 4984, target 4951, mulligan 4026, bottom cards 2019, play/draw 1394, trigger order 1038, declare attackers
  508, declare blockers 108, name 90. DAMAGE_ASSIGNMENT never came up with the random policy; DamageAssignmentCheck
  (Broodscale vs EsperBlink, 20 matches) made 19, PASS.
- Watched intended delegations: `chooseNewTargetsFor` 8, `cost:*` 1090 (CostExile white 954, blue 136),
  `chooseTargetsFor(divided)` 0, `orderCosts` 6466; also `chooseSingleStaticAbility` (order cost changes apply in)
  37427. The run also counted 8 `chooseModeForAbility` delegations that were mode choices with one possible outcome
  (Kozilek's Command, Warping Wail); since fixed (made without asking), Broodscale vs IzzetProwess 60 rerun PASS.
- Priority stops: 140940 requests (70.2 per game), 373490 auto passes, 44% with an action other than pass and mana
  abilities. Throughput: 1.70 s/game, 0.29 ms per decision waiting for agent-core, one match at a time.
- Sampled candidate checks: 1290 run, 0 omissions; 1098 more could not copy the game (agent-core `GameCopier`
  "Couldn't map", e.g. Thief of Existence, Basking Broodscale) and were skipped.
- AdapterDeterminism 20/20 identical, LogReplay 10/10 (1669 decisions), DamageAssignmentCheck PASS. mvn install with
  tests and Python tests (5) pass.
- Fixed on the way (first two gate runs failed): a refused priority action is left out of the re-ask (a refused March
  was chosen again three times: 10 fallbacks); Emrakul's delayed trigger keyed by its creating ability; vocabulary 2;
  match IDs prefixed with the pairing so shard files no longer overwrite each other, and ShardWriter refuses to
  overwrite.

GameCopier copy failures (final-gate finding), fixed 2026-11-10:

- Causes: (1) devoid is a static ability that works in every zone, so devoid cards in the sideboard (Thief of
  Existence, Sowing Mycospawn, Basking Broodscale) register static effects whose source GameCopier did not copy: the
  copier now copies the sideboard zone too. (2) a planeswalker token attacked and then gone (Jace token) stays among
  Combat's attackable defenders, which Forge's Combat copy maps: GameCopier's combat map now gives such a card the
  same id -1 stand-in Forge uses for the attacked-by entries. Both gaps are in Forge's own GameCopier / Combat too
  (upstream PR candidates).
- CopyCheck 16 pairings x 3 matches: 24345 game copies, 0 failed (before: 14683 of 14701 failed in Broodscale vs
  AzoriousControl alone). AllPairings 16 x 5: 235 copies, 0 failed; candidate omissions 0 in 113 checks; PASS.
  agent-core sequential determinism 40/40, AdapterDeterminism 20/20.

No refusals, exile costs and cost-change order (2026-11-10 decisions), AllPairings 16 x 60 on 8 threads with logs
and shards (runs/2026-11-10-AllPairings-60/summary.txt): PASS.

- 960 matches / 2008 games. Refusals 0 (before: 77, all March of Otherworldly Light), unintended fallbacks 0,
  unmapped 0, unclassified 0, log problems 0, protocol errors 0, game copies 0 failed of 2761, candidate omissions 0
  in 1275 checks; shards 2008 files for 2008 games, 0 errors, vocabulary misses 0 (vocabulary 3).
- Intended delegations left: `orderCosts` 6444, `chooseNewTargetsFor` 6; `cost:*` 0 (before: CostExile white 954,
  blue 136); `chooseSingleStaticAbility` no longer a delegation.
- chooseSingleStaticAbility: 32775 cost-computation calls (not decisions), 33 calls in real payments (each with one
  reduction), 0 real payments with two or more reductions, so no order changed a final cost: the decks' reductions
  (March, Emrakul, Boseiju) only reduce their own spell. The planned-order path is in place but did not run.
- Throughput: wall 919 s, 131.2 games/min with 8 threads (sequential: about 38), 3.07 s per game per thread.
- AdapterDeterminism 20/20, LogReplay 10/10, agent-core sequential determinism 40/40, DamageAssignmentCheck PASS
  (DAMAGE_ASSIGNMENT did not come up in this gate run; it did in the previous parallel run).
- Fixed on the way: Thief of Existence's granted "leaves the battlefield" trigger keyed by the paragraph that grants it
  (override); the Eldrazi Scion token ability added to the vocabulary (version 3).
