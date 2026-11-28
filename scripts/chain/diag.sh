#!/bin/bash
# Diagnosis runs (2026-11-28 user directive addendum): why the network alone loses to random. Separate from the main
# chain (chain/STOP stays set for it); stop these with chain/DIAG-STOP. Each run: B vs random (A), Izzet mirror, seats
# alternating, reproducible, shards on, the build in chain/builds/current.txt at the run's start; then eval_summary
# (win rate, Wilson 95%, by seat), BehaviorMetrics (metrics/<run>.md) and 3 LogText samples (log-samples/<run>/).
# Usage: diag.sh <run>...   runs: gen0 (1), sample (2), greedy (3); runs given together go in parallel.
#   1 diag-gen0-net-vs-random       gen0-run2 network alone (argmax); 3 units x 33 matches, seeds 50,000-50,232
#   2 diag-gen2c-sample-vs-random   gen2-c network alone sampled at temperature 1; 3 units x 33, seeds 52,000-52,232
#   3 diag-gen2c-greedy-vs-random   gen2-c one-ply value greedy: search at 1 simulation per root candidate, widening
#                                   off, tree reuse off; 2 units x 50 matches, seeds 51,000-51,149
#   4 diag-forgeai-vs-random        plain Forge AI vs the random agent (RandomGames: agent fixed in seat 0, so no seat
#                                   split; metrics cover the random seat only); 2 units x 50, seeds 53,000-53,149
#   5 diag-gen0-sample-vs-random    gen0-run2 network alone sampled at temperature 1; 3 units x 33, seeds 54,000-54,232
# Usage names: gen0, sample, greedy, forgeai, gen0sample.
C=/e/magicdosa-runs/chain
RUNS=/e/magicdosa-runs
DECKS=E:/workspace/magicdosa/.claude/worktrees/phase3-search-b28914/decks
FORGE_GUI=E:/workspace/magicdosa/forge/forge-gui
JAVA=/e/jdk/jdk17/bin/java
TAB=$(printf '\t')
GEN0=E:/magicdosa-runs/gen0/run2/gen0-run2.onnx
GEN2C=E:/magicdosa-runs/gen2/c/gen2-c.onnx
cd $C

log() { echo "$(date '+%F %T') $*" | tee -a $C/diag.log; }
classpath() { local win cp; win=$(cygpath -w $1); cp=$(cat $C/cp-template.txt); echo "${cp//__CLASSES__/$win}"; }
current_build() { echo d957f4b; }  # pinned 2026-11-28: the models here are encoder 5, a later encoder-6 build cannot load them
sha_of() { sha256sum "$(cygpath -u "$1")" | cut -c1-16; }

run_unit() {
  local out=$1 classes=$2 seed=$3 matches=$4 prefix=$5 cp; shift 5
  cp=$(classpath $classes)
  mkdir -p $out
  ( cd $FORGE_GUI && $JAVA -Xmx1g -XX:ParallelGCThreads=2 -XX:ConcGCThreads=1 -Dfile.encoding=UTF-8 -Dmagicdosa.reproducible=true \
      "-Dmagicdosa.agentJvmArgs=-XX:ParallelGCThreads=2 -XX:ConcGCThreads=1" -Dmagicdosa.agentXmx=2g -Dmagicdosa.timing=true \
      -Dmagicdosa.seedBase=$seed -Dmagicdosa.firstMatch=0 "$@" \
      -cp "$cp" magicdosa.runner.forge.harness.${MAIN:-HeadToHead} $DECKS IzzetProwess.dck IzzetProwess.dck $matches "$(cygpath -w $out)" \
      $([ "${MAIN:-HeadToHead}" = RandomGames ] && echo spawn) $prefix \
      > $out.log 2>&1 )
  echo "$(basename $out) exit $? $(date '+%T')" >> $(dirname $out)/done.txt
}

# $1 run dir, $2 units, $3 matches per unit, $4 seed base, $5 prefix, $6 leaf, $7 side-B label, $8 notes; rest: -D props
diag_run() {
  local dir=$1 units=$2 matches=$3 seed=$4 prefix=$5 leaf=$6 label=$7 notes=$8 name try i; shift 8
  name=$(basename $dir)
  mkdir -p $dir
  if [ ! -f $dir/build.txt ]; then
    current_build > $dir/build.txt
    cp $C/builds/$(cat $dir/build.txt)/commit.txt $dir/commit.txt
    echo "$leaf $(sha_of $leaf)" > $dir/leaf.txt
    echo "seeds $seed-$((seed+100*(units-1)+matches-1)) (bases $seed+100*i, i 0-$((units-1)), matches 0-$((matches-1))); A random (uniform over candidates), B $notes; leaf $leaf $(sha_of $leaf); build $(cat $dir/build.txt)" > $dir/seeds.txt
  fi
  local classes=$C/builds/$(cat $dir/build.txt)
  for try in 1 2 3; do
    local started=0
    for i in $(seq 0 $((units-1))); do
      [ -f $dir/n$i/summary.txt ] && continue
      [ -f $C/DIAG-STOP ] && { log "$name: stopped (DIAG-STOP)"; return 1; }
      [ -d $dir/n$i ] && { mv $dir/n$i $dir/n$i.crashed.$try; mv $dir/n$i.log $dir/n$i.crashed.$try.log 2>/dev/null; }
      run_unit $dir/n$i $classes $((seed+100*i)) $matches $prefix$i- -Dmagicdosa.agentBudgetPerCandidate=12 \
        -Dmagicdosa.search.leafModel=$leaf -Dmagicdosa.agentPolicyA=random "$@" &
      started=$((started+1))
    done
    [ $started -eq 0 ] && break
    log "$name: running $started units (try $try)"
    wait
  done
  if [ "${MAIN:-HeadToHead}" = RandomGames ]; then
    python "$(cygpath -w $C/forgeai_summary.py)" "$(cygpath -w $dir)" > $dir/diag-summary.txt 2>&1
  else
    python "$(cygpath -w $C/eval_summary.py)" "$(cygpath -w $dir)" | sed "s/trained (B)/$label (B)/; s/trained won/$label won/; s/ of 5\$/ of $units/" > $dir/diag-summary.txt 2>&1
  fi
  # behaviour metrics and log samples, on the newest build (older builds may lack the tools)
  cp=$(classpath $C/builds/$(current_build))
  mkdir -p $RUNS/metrics $RUNS/log-samples/$name
  ( cd $FORGE_GUI && $JAVA -Xmx1g -Dfile.encoding=UTF-8 -cp "$cp" magicdosa.agent.tools.BehaviorMetrics \
      --label $name --out "$(cygpath -w $RUNS/metrics/$name.md)" "$(cygpath -w $dir)" > $dir/metrics.log 2>&1 )
  python "$(cygpath -w $C/samples.py)" "$(cygpath -w $dir)" $label 3 | tr -d '\r' | while IFS="$TAB" read -r lg out; do
    ( cd $FORGE_GUI && $JAVA -Xmx1g -Dfile.encoding=UTF-8 -cp "$cp" magicdosa.agent.tools.LogText "$lg" \
        "$(cygpath -w $RUNS/log-samples/$name/$out)" >> $dir/metrics.log 2>&1 )
  done
  touch $dir/DIAG-DONE
  log "$name done: $(head -2 $dir/diag-summary.txt | tr '\n' ' ')"
}

for r in "$@"; do
  case $r in
    gen0) diag_run $RUNS/diag-gen0-net-vs-random 3 33 50000 d0u $GEN0 network "gen0-run2 network alone (policy argmax)" \
            -Dmagicdosa.agentPolicyB=network & ;;
    sample) diag_run $RUNS/diag-gen2c-sample-vs-random 3 33 52000 d2u $GEN2C network "gen2-c network alone sampled at temperature 1" \
            -Dmagicdosa.agentPolicyB=network -Dmagicdosa.network.temperature=1 & ;;
    greedy) diag_run $RUNS/diag-gen2c-greedy-vs-random 2 50 51000 d3u $GEN2C x1 "gen2-c one-ply value greedy: search at 1 simulation per root candidate (each root candidate visited once, ties on visits broken by mean value = the value net right after it; one hidden-info sample per candidate; later steps of a multi-step decision follow that single line), widening off, tree reuse off" \
            -Dmagicdosa.agentPolicyB=search -Dmagicdosa.agentBudgetPerCandidate=1 -Dmagicdosa.agentBudgetMultiplierB=1 \
            -Dmagicdosa.search.wideningMin=0 -Dmagicdosa.search.treeReuse=false & ;;
    forgeai) MAIN=RandomGames diag_run $RUNS/diag-forgeai-vs-random 2 50 53000 d4u $GEN2C random "plain Forge AI (no agent; RandomGames: the random agent always in seat 0, Forge AI in seat 1)" \
            -Dmagicdosa.agentPolicy=random & ;;
    gen0sample) diag_run $RUNS/diag-gen0-sample-vs-random 3 33 54000 d5u $GEN0 network "gen0-run2 network alone sampled at temperature 1" \
            -Dmagicdosa.agentPolicyB=network -Dmagicdosa.network.temperature=1 & ;;
    *) echo "unknown run $r"; exit 2 ;;
  esac
done
wait
