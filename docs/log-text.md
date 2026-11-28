# LogText: a game log as readable text

`magicdosa.agent.tools.LogText` (agent-core test sources) turns one game log (`*.pb.gz`, the protocol log runner/forge
writes per game) into a plain-text file, one entry per action in game order. It reads only the log: no Forge game, no
agent-core server.

```powershell
mvn -pl agent-core exec:exec "-Djvm.args=-Xmx1g" "-Dexec.mainClass=magicdosa.agent.tools.LogText" "-Dexec.args=<absolute log.pb.gz> <absolute out.txt>"
```

Arguments: `<log.pb.gz> [out.txt] [--viewer SEAT] [--privileged]`. Without `out.txt` the text goes to stdout. Paths
must be absolute (the exec working directory is `forge/forge-gui`).

## What it shows

- Header: match and game id, game number, seed, protocol / encoder / model versions, adapter, engine commit, mode,
  each seat's deck and kind, the result and end reason.
- Turn markers (`=== Turn 5 (P1) ===`, the active seat in brackets) and step markers (`-- declare attackers --`), printed
  only when something happens in them. At each turn start, from the viewer's snapshot: life, hand and library sizes,
  each battlefield and the viewer's hand.
- Events from one seat's stream: plays, casts (targets, modes, X, mana paid), activations, triggers, resolution choices,
  zone moves (draws, deaths, exile, ...), tokens, damage, life, counters, shuffles, effects, attacks and blocks. Priority
  passes, mana abilities, pool changes, taps, ended effects and removed triggers are left out; the action lines and
  decision lines already carry them.
- Every decision (`> D42 P0 priority ... [model]`): deciding seat, type, context (source, cost, targets, the engine's
  prompt), selection limits, the final decider (model / intended delegation with the engine method / unintended
  fallback with its reason), rejections. Then the candidates in readable form, the chosen one marked `*`. A step that
  ran search prints every set scalar field of `SearchRun` (budget, simulations, nodes, root value, horizon, leaf
  evaluation, ...); a step without search prints "net only (reason)". Per-candidate columns: `visits` (visit ratio),
  `policy` (policy probability), and any other repeated numeric field of the step, its diagnostics or its search run
  that has one entry per candidate. The columns are found by reflection, so per-candidate statistics a later protocol
  adds (Q, prior) appear without changing the tool. Multi-step decisions (attackers, card choices) list the candidates
  once and then each step with its own table.
- Engine choices made without a request (`> engine orderCosts [intended delegation]`) and opponent decisions of
  non-agent seats (`> P1 (engine) ...`).
- At the end, decision counts by decider and how many ran search.

## Viewer and hidden information

Events come from one seat's stream: the lowest agent seat by default, `--viewer SEAT` for another. Hidden cards show
as that seat saw them (`card#123`). A decision lists its candidates as the deciding seat saw them (its own request and
event stream), so the other agent seat's decisions name the cards in its hand.

The `privileged` records (training only) are left out. `--privileged` prints them (hidden-identity count, top five
cards of each library) and uses the hidden identities to name cards in the event lines.

## Ordering

The log writes a decision record once the decision is done, after the events it caused. LogText moves each decision
(and the privileged record of its request) back to just after the event its request had seen last (the deciding
seat's stream, `applied_seq`), so a decision comes before its cast or attack line.

## Card names

From the adapter's oracle-id table (`adapter/forge/.../oracle-ids.tsv`, built by `OracleTableBuilder` from Scryfall
data). A face index picks the face name. An oracle id missing from the table prints as `oracle:<first 8 digits>`.
Printed abilities print as `ability <index>` (the oracle text order), keywords by name and parameters.

## Example

`E:/magicdosa-runs/log-text-sample.txt` is `gate1-search-vs-net/n0/g0-0-g1.pb.gz` (protocol 15, Izzet mirror, P0 net
only, P1 search).
