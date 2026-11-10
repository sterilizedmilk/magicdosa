# magicdosa

A reinforcement-learning agent for Magic: The Gathering (Modern, fixed decks, Bo3 with sideboards),
trained on Forge and meant to play through any engine that speaks its protocol.

| Path | What |
| --- | --- |
| `pom.xml` | Maven parent (`magicdosa-parent`); open the repository root in IntelliJ |
| `proto/magicdosa/v1/` | protocol (proto3, JSON mapping for outside engines) |
| `protocol/` | Java code generated from the protocol |
| `agent-core/` | search, inference, shadow game (embedded Forge), encoder |
| `agent-core/src/test/java/magicdosa/agent/{determinism,bench}` | reproduction harness and benchmarks (main programs, not run by `mvn install`) |
| `adapter/forge/` | Forge <-> protocol translation library (used by runner/forge and agent-core) |
| `runner/forge/` | real Forge games for training and gates |
| `python/magicdosa` | training, evaluation, ONNX export (a separate project: open `python/` on its own) |
| `decks/` | test decks |
| `forge/` | Forge as a git submodule, pinned to the commit everything is built against |
| `scripts/` | setup scripts |

## Setup (Windows)

```powershell
git clone --recursive https://github.com/sterilizedmilk/magicdosa.git
cd magicdosa

# 1. Install the pinned Forge into the local Maven repository (again whenever the submodule moves)
.\scripts\install-forge.ps1

# 2. Build magicdosa (from the repository root)
mvn -U install   # -U the first time, if an earlier failed lookup was cached

# 3. Reproduction harness (default args: decks/ IzzetProwess.dck EsperBlink.dck 40 matches 8 threads seed 5000)
mvn -pl agent-core exec:exec
#    other runs: "-Dexec.args=<absolute deckDir> deckA deckB nMatches threads baseSeed"
#    other entry points: "-Dexec.mainClass=magicdosa.agent.bench.ShadowBench" "-Dexec.args=<absolute deckDir> 200"

# 4. Python
cd python
python -m venv .venv; .venv\Scripts\activate
pip install -e ".[dev]"
```

The harness and benches run with `forge-gui` as working directory because Forge reads `res/` from there
(`forge.home` in `agent-core/pom.xml`, default the `forge` submodule).

## Reproducibility

Forge is used unmodified: nothing in this repository replaces Forge classes. Forge keeps JVM-wide state
(static id counters, the AI cache) that matches running side by side in one JVM share, so concurrent
matches are not reproducible from their seeds. We work with that instead of patching it:

- **Training** runs matches in parallel without reproducibility and writes the full protocol log of every game.
- **Debugging** replays a game from its log.
- **Regression tests** run single-threaded and sequential with a seed; the harness above plays the same seeds
  twice in a row and must report `different=0`.

Forge numbers games with an unsynchronized counter, so every Forge game is created through
`magicdosa.agent.shadow.GameCreation`, which serializes creation.
