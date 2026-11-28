# Behaviour metrics (monitoring)

User directive 2026-11-28. **Observation only: these numbers never feed a training signal, reward or target.** They
watch how the agent plays across generations. The code and these definitions are the one source:
`agent-core/src/test/java/magicdosa/agent/tools/BehaviorMetrics.java` (test: `BehaviorMetricsTest`).

```powershell
mvn -pl agent-core exec:exec "-Djvm.args=-Xmx1g" "-Dexec.mainClass=magicdosa.agent.tools.BehaviorMetrics" "-Dexec.args=--label <run> --out <absolute table.md> <absolute log dir>..."
```

`--cards DIR` names Forge's `res/cardsfolder` (default: relative to the working directory, which is `forge/forge-gui`
under exec). It reads only logs and card scripts as text, so no Forge game is started.

## Definitions

Each metric is counted per agent seat and game, then grouped by matchup (own deck vs opponent deck) and seat mode. Every
judgement uses only what the deciding seat knew from its own request: the public state plus its own hand. Privileged
records are never read.

- **Seat mode:** `search` if any of the seat's decision steps ran search; else `network` if a step's prior was the
  learned policy head (the network alone); else `random` if the steps were decided without search on the uniform
  prior (the random policy); else `delegated`. The log header does not name the policy, so the mode comes from the
  steps (`Method.prior`). Before 2026-11-28 a random seat was counted as `network`.
- **① Confirmed kill not closed:** this covers the seat's turns in which an attack declaration got the kill checker's
  KILL verdict (`DecisionResponse.kill_check = 2`, on public information). The metric is the share of those turns in
  which the seat did not win that same turn. `approx N` counts the turns whose verdict was approximate
  (`kill_check_approximate`). This needs protocol 17. Older logs (protocol 15 and 16) show `n/a`. The offline kill-verify
  replay (`-Dmagicdosa.killVerify`) needs a live shadow game for each decision, so it is not used here.
- **② Free damage missed:** this covers the seat's attack declarations (DECLARE_ATTACKERS requests) that had at least
  one attack candidate (the engine's legal attacks) while the opponent controlled no untapped creature on the
  battlefield. The metric is the share in which the seat declared no attacker. Creature types come from token
  characteristics, or else from the Forge card script of the card's oracle id. Face-down objects and cards of unknown
  type count as possible blockers. Effects such as "can't block" and creature lands that are not yet animated are not
  modelled.
- **③ Land not played:** this covers the seat's own turns in which a PRIORITY request offered a land play (so a land
  was in hand and no land had been played yet). The metric is the share in which the seat played no land that turn.
  The game's last turn is left out, because it did not end.
- **④ Pass share:** this covers PRIORITY requests with a real alternative, meaning at least one candidate other than
  pass and mana abilities. The metric is the share answered with pass.
- **⑤ Game length:** the mean of `GameOver.turns` (both seats' turns counted).
- **⑤ Turns from value ≥ 0.9 to end:** for games in which the seat's value reached 0.9, the mean number of turns from
  the first such decision to the game's end, with the game count and the win rate of those games. The value is the
  search root value for search steps, or the value head (`Diagnostics.value`) for network-alone steps when it is
  logged (it is not logged in gate-1 logs, so network rows show `-` there).

Shares print as `percent (count/denominator)`, and `-` means the denominator is 0.

## Trend by generation

One row for each run and seat mode, over all matchups. The per-matchup tables are in `E:/magicdosa-runs/metrics/<run>.md`.

| run (generation, date) | mode | games | win % | ① kill not closed | ② free damage missed | ③ land not played | ④ pass share | ⑤ mean turns | ⑤ turns from value ≥ 0.9 to end (games, won %) |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| gate1-search-vs-net (gen2-c, 2026-11-28, protocol 15, Izzet mirror, 1× search vs network alone) | search | 388 | 92.0 | n/a (protocol < 17) | 25.1% (678/2701) | 48.4% (1695/3504) | 58.2% (12938/22223) | 25.7 | 13.2 (358, 98%) |
| gate1-search-vs-net (same games) | network | 388 | 8.0 | n/a (protocol < 17) | 2.9% (4/136) | 84.6% (3736/4415) | 89.8% (26279/29278) | 25.7 | - |
