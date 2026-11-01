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
| `ObservedAiController` | Forge AI that reports what it does: the opponent's actions for the agent seats' streams, and for agent seats every wrapped method it answers as an intended delegation |
| `RlPlayerController` | Agent seats. Asks agent-core for priority, targets of cast spells / activated abilities, attackers, blockers, mulligan, bottom cards, play/draw |
| `DecisionClient` | The connection to agent-core: `GrpcDecisionClient` (Hello + one bidirectional stream per game; a failed stream fails every later call of that game, recorded as an unintended fallback) |
| `ForgeRandom` | Per-thread seeded `MyRandom` (inheritable: Forge AI spawns a thread per decision) |

## Tables

- `src/main/resources/magicdosa/adapter/forge/oracle-ids.tsv` is generated: run
  `magicdosa.adapter.forge.tools.OracleTableBuilder <oracle-cards.jsonl.gz> <decks dir> forge/forge-gui/res <out>`
  with Scryfall's "Oracle Cards" bulk file. Tokens are resolved through Forge's edition `[tokens]` sections and one
  Scryfall API call per token print. Rebuild when decks change.
- `ability-overrides.tsv` is hand-written: card, start of Forge's description, `keyword:<Name>` or
  `printed:<face>:<index>`.

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
- The random policy is agent-core's `RandomPolicy` (uniform, multi-selects step by step, honours the Q15 combination
  rules); agent-core's `GameSession` checks sequence numbers, snapshots and `applied_seq` and closes the game's
  stream on a protocol error.

## Not done / approximate in this slice

- Delegated to Forge AI (recorded as intended delegation): mana payment, trigger order, optional triggers and the
  targets of triggered abilities (`playTrigger`), modes, X, card choices during resolution, scry/surveil,
  sideboarding. 37 Forge methods are wrapped; the rest are not recorded yet.
- Triggered abilities get their ID when put on the stack, not at the moment they trigger.
- `CastSpell` / `ActivateAbility` come after the `ZoneMove` that put the card on the stack.
- Not filled: chosen modes, mana-pool events, effects (`effects_provided=false`), `GameEndReason` beyond life/draw.
- Reveals are inferred from Forge's card visibility (`CardView.canBeShownTo`).
- Legal priority actions use Forge AI's legality helpers (`ComputerUtilAbility`, `ComputerUtilCost.canPayCost`).
- Log replay scripts only the agent seats; the Forge AI opponent replays because the match seed is the same. The
  design's replay also scripts the opponent from the log, so that it survives Forge AI changes: not built yet.

## Results (2026-11-02, IzzetProwess random vs EsperBlink Forge AI)

In-process stand-in for agent-core (before the gRPC server):

- RandomGames 1000 matches / 2198 games, 53 min: unintended fallbacks 0, refusals 0, protocol errors 0, unmapped 0.
- AdapterDeterminism 20 matches: identical 20, different 0.
- LogReplay 10 matches: identical 10, 967 decisions replayed.
