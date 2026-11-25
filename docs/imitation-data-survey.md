# Survey: pretraining on Forge AI's choices from existing logs (2026-11-25)

Question (user, 2026-11-25): can existing logs give imitation data (Forge AI's decisions with full candidate lists)?
Survey only: nothing was built; whether to use imitation at all is a planning question.

## 1. Logs with Forge AI decisions over our candidate lists

Gate logs are in `E:\magicdosa-runs` (one `.pb.gz` per game, protocol 10, encoder 4). Counted with a throwaway
reader (scratchpad, not committed). "Decision" = a `DecisionRecord` of the agent seat: the full request (observation
and candidates) and the response.

| Run | What decided the agent seat | Games | Decisions | Protocol / encoder |
| --- | --- | --- | --- | --- |
| fin4-layer | the real game's Forge AI through our decision layer (`real-forge-ai`, sees only its own hidden cards, as Forge AI does) | 2273 | 720,290 | 10 / 4 |
| fin3-layer | same | 2273 | 720,260 | 10 / 4 |
| fin4-shadow | the shadow game's Forge AI (`shadow-forge-ai`: hidden cards are belief samples) | 2256 | 713,640 | 10 / 4 |
| fin3-shadow | same | 2255 | 716,151 | 10 / 4 |
| fin4-plain / fin3-plain | plain Forge AI both seats, no decision layer | 2229 each | 0 | 10 / 4 |
| fin4-random | random policy (not imitation data) | 2012 | | 10 / 4 |

Per decision type, fin4-layer (fin4-shadow within a few percent): PRIORITY 602,535; MANA_PAYMENT 33,832;
CHOOSE_CARDS 25,053; TARGET 14,255; DECLARE_ATTACKERS 8,163; CHOOSE_OPTION 5,980; MULLIGAN 5,784; BOTTOM_CARDS 3,511;
TRIGGER_ORDER 2,853; SIDEBOARD 1,313; DECLARE_BLOCKERS 1,256; PLAY_OR_DRAW 1,127; DAMAGE_ASSIGNMENT 229; plus 14,399
records without a request (delegations noted inside other answers, e.g. cost parts). All are recorded as intended
delegations; every game has its privileged records (about 345 per game) and its game over.

Notes:
- Two different "teachers": layer runs record the real Forge AI (its own information); shadow runs record Forge AI
  deciding on a belief sample of the hidden cards (a weaker, noisier teacher). The layer runs are the cleaner source.
- About 35% of priority requests offer only pass and mana abilities (65% "with an action"); since the mana-only
  filter (phase 3) many of those stops no longer exist, so old logs over-represent them.
- One decision layer quirk is baked into the old logs: the Urza's Saga / Shifting Woodland key (fixed in protocol 13
  data, old logs keep the old key).

## 2. Do the Arrow shards hold intended-delegation decisions?

No usable ones. The fin gates wrote no shards at all (only the phase-1 `gate60` run has shards, random policy).
`ShardWriter` keeps rows of every decider (the decider column lets training leave them out), but a delegated answer
carries a step with only the decider (no step candidates, no chosen index), so it cannot become a row as it is.
To emit them: build the response's steps from the chosen candidates (one step per pick, "done" where it stops, like
the random policy does) when the answer is a delegation, and keep `decider = INTENDED_DELEGATION` so policy training
does not mix them with search targets by accident (`shards.policy_target_rows` filters on decider and visit ratios;
an imitation loader would filter on the delegation decider instead). Outcome and privileged labels are already
joined per game.

## 3. Re-encoding the protocol-10 logs with encoder 5

No game needs to be replayed: a log holds every request (observation and candidates), every response, the
privileged records and the game over, which is exactly what `ShardWriter.write` takes. Protocol 10 -> 13 only added
fields (copy retargeting context, search run fields, skip reasons), so protocol-13 classes read the old records.
Effort: a small offline tool (read a log, rebuild the delegated steps as in 2, call the encoder and `ShardWriter`),
about half a day including a check against shards written live. CPU: encoding plus Arrow writing is well under a
millisecond per decision; about 720k decisions per gate run (2,250 games) should take minutes to tens of minutes on
one core, embarrassingly parallel by file. LogReplay and ShadowCheck replay games through Forge and are not needed
(they would cost CPU-hours).

## 4. The opponent seat's Forge AI

Not usable as it is: the logs hold its actions (events) and `OpponentDecision` records (engine method and a text
description, about 74k per run) but not the candidate lists it chose from. Getting them needs a replay that rebuilds
the state at each of its decisions (LiveReplay / ShadowCheck with the real privileged identities) and builds its
candidates with our decision layer there; that is several CPU-hours per run (the shadow loop runs at about 6-10 s
per game) and new code to ask the layer for the other seat's candidates without answering them. It would roughly
double the data.

## Converter (2026-11-25 planning decision: phase-4 generation-0 initialisation only)

`magicdosa.agent.tools.ImitationShards` (agent-core test sources, a main program; nothing about training):

```powershell
mvn -pl agent-core exec:exec "-Djvm.args=-Xmx2g --add-opens=java.base/java.nio=ALL-UNNAMED" "-Dexec.mainClass=magicdosa.agent.tools.ImitationShards" "-Dexec.args=<abs log root> <abs out dir> <threads>"
```

- Reads each game log (decision-layer gates: fin3-layer, fin4-layer), rebuilds the steps of every answer recorded as
  Forge AI's from its chosen candidates (one step per pick, then "done" where the selection may stop), keeps the
  delegation as each step's decider, joins the privileged records and the game end, and writes one shard per game
  with the current encoder (5) and vocabulary (3).
- Schema metadata: `source=imitation:forge-ai`, `teacher` (the log's model version, `real-forge-ai`),
  `policy=forge-ai (decision layer)`, `model_version=none`, `log_protocol_version`, `log_encoder_version`, `log`.
  `shards.policy_target_rows` gives none of these rows (no visit ratios, not decided by the model), so they cannot
  enter the search policy targets by accident; an imitation loader selects them by the source tag and the decider.
- Trial on fin4-layer IzzetProwess mirror (150 games): 23,455 decisions, every answer's steps rebuilt, 636 records
  left out (no request body), 5.3 s on two threads, no vocabulary miss; the Python reader takes the shards.
- Size: about 1.6 MB per game (247 MB for 150 games), so about 3.7 GB per gate run. E: has 28 GB free (95% used):
  converting fin3-layer and fin4-layer (about 7.5 GB) is left for the user's go-ahead.
