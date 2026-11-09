# Encoder and training shards

Implementation notes for agent-core's observation encoder (encoder version 3) and its Arrow shards, following
"관측 인코더 텐서 구성안" (https://claude.ai/artifact/WPT2PSq7RihqH7K3egDptU, E1-E7, decided 2026-11-06). The encoder is
only in agent-core; Python reads the shards and batches them without computing features.

## Pieces

| Where | Role |
| --- | --- |
| `agent-core/.../encoder/Vocab` | Vocabulary (E1): `vocab.tsv` in agent-core's resources, `kind<TAB>value`, indices in file order per kind from 3; 0 padding, 1 hidden card, 2 not in the vocabulary (counted). `#version` header = vocabulary version, separate from the encoder version (3 since 2026-11-10: ward and the adventure rule key from the 2026-11-08 run, the Eldrazi Scion token ability from the 2026-11-10 run) |
| `agent-core/.../encoder/Encoder` | One decision request as tensors: global vector, tokens (players, objects, stack items, effects), relations, deck tokens, candidates |
| `agent-core/.../encoder/ShardWriter` | One Arrow IPC file per game (`<game_id>.arrow`), one row per decision step, written when GameOver arrives |
| `adapter/.../tools/VocabBuilder` | Builds or extends `vocab.tsv` (append-only) from the decks, the oracle table and game logs |
| `python/magicdosa/shards.py` | `read_shard` (version check), `make_batch` (padding and masks), `policy_rows` |

agent-core writes shards when started with `--shards <dir>` (the JVM needs `--add-opens=java.base/java.nio=ALL-UNNAMED`
for Arrow; the harnesses' spawned agent-core has it). Its summary line reports shards written, shard errors and
vocabulary misses.

## Tensors

- Every value is from the deciding seat's point of view; entity IDs are never inputs, only token positions.
- Feature names, in order, are in the shard metadata (`global_features`, `token_features`, `candidate_features`), so
  the widths travel with the data. The names are the constants `Encoder.GLOBAL`, `Encoder.TOKEN`, `Encoder.CANDIDATE`.
- Tokens: kind (1 player, 2 object, 3 stack item, 4 effect), card index, key index (ability key of a stack item or
  effect; a spell's cast method), features. A spell on the stack is its stack item's token only.
- E3: a library card whose identity the seat does not know is not a token. Known library cards are object tokens with
  their position (from top, from bottom, or the size of a bottom group). Deck tokens (card, count, group): my current
  60, my sideboard, the opponent's registered main and sideboard, the opponent's cards seen in earlier games, my
  remaining composition (my 60 minus my cards whose identity I see), and, in game 1 only, the opponent's remaining
  composition (its main minus its cards seen).
- Relations (E2): attached, attacks, blocks, targets, source of a stack item, affected by an effect.
- Candidates: kind (Candidate field number; 31 = done), references to tokens (-1 none), card / key / string indices,
  features (X, option index, mana ability, keep, play, pay life, pool mana color and amount, done).
- E7: optional fields have a "known" column (`power_known`, `can_attack_known`, `spells_cast_known`, `start_known`);
  hiding them for training is done in Python.
- Not in this version: event history (E4), derived features, belief, strategy labels, refusal counts, granted ability
  lists.

## Shard rows

One row per decision step (E5): identity (`game_id`, `match_id`, `game_number`, `decision_id`, `step`, `steps`,
`seat`, `acting_seat`, `decision_type`, `bundle_id` and `bundle_step`: the Q18 step bundle a mana payment or
sideboarding request belongs to, 0 otherwise; encoder 3), decision record (`decider`, `prior`, `search_ran`, `search_skip_reason`,
`search_nodes`), inputs (lists as above; a multi-select step lists its open candidates plus "done" and marks the
tokens chosen in earlier steps as `selected`), labels (`chosen`, `visit_ratio`, `result`, `is_draw`, `end_reason`,
`turns`), training-only columns (`priv_opp_hand`, `priv_my_library`, `priv_opp_library` from the privileged record of
that decision), and `oov`. Metadata: protocol, encoder, vocabulary, model and engine versions, feature names, `oov`.
Rows of the phase-1 random policy have a uniform prior and no search: `policy_rows` leaves them out.

## Commands

```powershell
# vocabulary: extend agent-core's vocab.tsv from the decks and some game logs (working directory forge/forge-gui)
mvn -pl adapter/forge-adapter exec:exec "-Dexec.mainClass=magicdosa.adapter.forge.tools.VocabBuilder" "-Dexec.args=<abs decks> <abs vocab.tsv> <abs vocab.tsv> 1 <abs log dir>..."
# RandomGames with shards (fails on a vocabulary miss)
mvn -pl adapter/forge-adapter exec:exec "-Djvm.args=-Xmx3g -Dmagicdosa.shards=<abs dir>" "-Dexec.args=<abs decks> IzzetProwess.dck EsperBlink.dck 20 -"
cd python; pytest tests
```

`python/tests/data/sample-shard.arrow` is the first rows of a real shard; rebuild it when a version changes. The tests
here ran on Python 3.9 with pyarrow 21 (the project targets 3.11; the code avoids newer syntax).
