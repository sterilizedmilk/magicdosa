#!/bin/bash
# New gate 1 with Gumbel search (2026-11-28 coordinator request; user rule: no game runs before 2026-11-29 02:00).
# Waits until 02:00, until diag-at.sh is done and until checks-at.sh logged PASS for the build named in
# chain/gate-build.txt (it stops and logs why on FAIL, or when that build lacks the per-side HeadToHead), then:
#   1. gate1-gumbel-vs-greedy: B = 1x Gumbel search (protocol 18 defaults: gumbelNoise off, logits 0, tree reuse off),
#      A = one-ply value greedy as diag 3 (ucb1, 1 simulation per root candidate, widening off, tree reuse off); gen3-d
#      leaf on both sides, Izzet mirror, seats alternating, reproducible, shards on; 5 units x 40 matches, seeds
#      60,000-60,439 (bases 60000+100*i). Pass: B wins 55%+ (Wilson 95% reported).
#   2. gate1-gumbel-4x-vs-1x (reference): Gumbel 4x (B) vs 1x (A), gen3-d; 5 units x 20 matches, seeds 61,000-61,419.
#   3. SearchReplay of the 117 roots (gate3-step3-gen2 first games), gen3-d leaf: Gumbel 1x, Gumbel 4x, UCB1 1x
#      (same-state 1x/4x agreement, ms per searched decision): E:/magicdosa-runs/gumbel-replay-{g1,g4,u1}.txt.
#   After 1 and 2: eval summary, gate_stats (root top-visit share, ms per searched decision), BehaviorMetrics
#   (metrics/<run>.md) and 3 LogText samples (log-samples/<run>/).
# Build: chain/builds/<chain/gate-build.txt>, the coordinator's frozen merge (Gumbel v_mix + Q normalization, the
# library-identity fix, the per-side HeadToHead 1a786e7); written when the hash is known.
# Cancel: create chain/GATE-STOP (or chain/DIAG-STOP), or kill the bash process running chain/gate-at.sh.
C=/e/magicdosa-runs/chain
RUNS=/e/magicdosa-runs
DECKS=E:/workspace/magicdosa/.claude/worktrees/phase3-search-b28914/decks
FORGE_GUI=E:/workspace/magicdosa/forge/forge-gui
JAVA=/e/jdk/jdk17/bin/java
G3=E:/magicdosa-runs/gen3/d/gen3-d.onnx
TAB=$(printf '\t')
log() { echo "$(date '+%F %T') $*" >> $C/diag.log; }
stop() { [ -f $C/GATE-STOP ] || [ -f $C/DIAG-STOP ]; }
log "gate-at: waiting for 2026-11-29 02:00, diag-at and a checks-at verdict (pid $$)"
until [ "$(date +%Y%m%d%H%M)" -ge 202611290200 ] && grep -q "diag-at: \(done\|stopped\|cancelled\)" $C/diag.log \
    && grep -q "checks-at: \(PASS\|FAIL\|stopped\|cancelled\)" $C/diag.log; do
  stop && { log "gate-at: cancelled while waiting"; exit 0; }
  sleep 60
done
V=$(grep "checks-at: \(PASS\|FAIL\|stopped\|cancelled\)" $C/diag.log | tail -1)
B=$(echo "$V" | sed -n 's/.*checks-at: PASS \([0-9a-f]*\).*/\1/p')
if [ -z "$B" ] || [ "$B" != "$(head -1 $C/gate-build.txt | tr -d '\r ')" ]; then
  log "gate-at: stopped, no gate: the checks did not pass on the build in chain/gate-build.txt ($V)"; exit 0
fi
BUILD=$C/builds/$B
if ! grep -aq "magicdosa.side" $BUILD/runner/forge/target/test-classes/magicdosa/runner/forge/harness/HeadToHead.class; then
  log "gate-at: stopped, no gate: build $B has no per-side HeadToHead (1a786e7 not merged)"; exit 0
fi
CP=$(cat $C/cp-template.txt); CP=${CP//__CLASSES__/$(cygpath -w $BUILD)}
log "gate-at: checks passed on $B, starting the gates"

run_unit() {
  local out=$1 seed=$2 matches=$3 prefix=$4; shift 4
  mkdir -p $out
  ( cd $FORGE_GUI && $JAVA -Xmx1g -XX:ParallelGCThreads=2 -XX:ConcGCThreads=1 -Dfile.encoding=UTF-8 -Dmagicdosa.reproducible=true \
      "-Dmagicdosa.agentJvmArgs=-XX:ParallelGCThreads=2 -XX:ConcGCThreads=1" -Dmagicdosa.agentXmx=2g -Dmagicdosa.timing=true \
      -Dmagicdosa.seedBase=$seed -Dmagicdosa.firstMatch=0 "$@" \
      -cp "$CP" magicdosa.runner.forge.harness.HeadToHead $DECKS IzzetProwess.dck IzzetProwess.dck $matches "$(cygpath -w $out)" $prefix \
      > $out.log 2>&1 )
  echo "$(basename $out) exit $? $(date '+%T')" >> $(dirname $out)/done.txt
}

# $1 dir, $2 seed base, $3 matches per unit, $4 prefix, $5 side-B label, $6 notes; rest: -D props
gate_run() {
  local dir=$1 seed=$2 matches=$3 prefix=$4 label=$5 notes=$6 name try i started; shift 6
  name=$(basename $dir)
  mkdir -p $dir
  [ -f $dir/seeds.txt ] || echo "seeds $seed-$((seed+400+matches-1)) (bases $seed+100*i, i 0-4, matches 0-$((matches-1))); $notes; leaf $G3 $(sha256sum "$(cygpath -u $G3)" | cut -c1-16); build $B" > $dir/seeds.txt
  for try in 1 2 3; do
    started=0
    for i in 0 1 2 3 4; do
      [ -f $dir/n$i/summary.txt ] && continue
      stop && { log "$name: stopped"; return 1; }
      [ -d $dir/n$i ] && { mv $dir/n$i $dir/n$i.crashed.$try; mv $dir/n$i.log $dir/n$i.crashed.$try.log 2>/dev/null; }
      run_unit $dir/n$i $((seed+100*i)) $matches $prefix$i- "$@" &
      started=$((started+1))
    done
    [ $started -eq 0 ] && break
    log "$name: running $started units (try $try)"
    wait
  done
  python "$(cygpath -w $C/eval_summary.py)" "$(cygpath -w $dir)" | sed "s/trained (B)/$label (B)/; s/trained won/$label won/" > $dir/gate-summary.txt 2>&1
  python "$(cygpath -w $C/gate_stats.py)" "$(cygpath -w $dir)" $label >> $dir/gate-summary.txt 2>&1
  python "$(cygpath -w $C/gate_stats.py)" "$(cygpath -w $dir)" $(ls -d $dir/n0/shards-* | xargs -n1 basename | sed 's/^shards-//' | grep -v "^$label$" | head -1) >> $dir/gate-summary.txt 2>&1
  mkdir -p $RUNS/metrics $RUNS/log-samples/$name
  ( cd $FORGE_GUI && $JAVA -Xmx1g -Dfile.encoding=UTF-8 -cp "$CP" magicdosa.agent.tools.BehaviorMetrics \
      --label $name --out "$(cygpath -w $RUNS/metrics/$name.md)" "$(cygpath -w $dir)" > $dir/metrics.log 2>&1 )
  python "$(cygpath -w $C/samples.py)" "$(cygpath -w $dir)" $label 3 | tr -d '\r' | while IFS="$TAB" read -r lg out; do
    ( cd $FORGE_GUI && $JAVA -Xmx1g -Dfile.encoding=UTF-8 -cp "$CP" magicdosa.agent.tools.LogText "$lg" \
        "$(cygpath -w $RUNS/log-samples/$name/$out)" >> $dir/metrics.log 2>&1 )
  done
  touch $dir/GATE-DONE
  log "$name done: $(head -2 $dir/gate-summary.txt | tr '\n' ' ')"
}

COMMON="-Dmagicdosa.agentPolicy=search -Dmagicdosa.agentBudgetPerCandidate=12 -Dmagicdosa.search.leafModel=$G3"
[ -f $RUNS/gate1-gumbel-vs-greedy/GATE-DONE ] || gate_run $RUNS/gate1-gumbel-vs-greedy 60000 40 gg x1-B \
  "A one-ply value greedy (ucb1, 1 simulation per root candidate, widening off, tree reuse off), B Gumbel 1x (gumbelNoise off, logits 0, tree reuse off)" \
  $COMMON -Dmagicdosa.agentBudgetMultiplierA=1 -Dmagicdosa.agentBudgetMultiplierB=1 \
  -Dmagicdosa.sideA.magicdosa.search.algorithm=ucb1 -Dmagicdosa.sideA.magicdosa.agentBudgetPerCandidate=1 \
  -Dmagicdosa.sideA.magicdosa.search.wideningMin=0 -Dmagicdosa.sideA.magicdosa.search.treeReuse=false \
  -Dmagicdosa.sideB.magicdosa.search.algorithm=gumbel || exit 0
[ -f $RUNS/gate1-gumbel-4x-vs-1x/GATE-DONE ] || gate_run $RUNS/gate1-gumbel-4x-vs-1x 61000 20 g4 x4 \
  "A Gumbel 1x, B Gumbel 4x (reference)" \
  $COMMON -Dmagicdosa.agentBudgetMultiplierA=1 -Dmagicdosa.agentBudgetMultiplierB=4 -Dmagicdosa.search.algorithm=gumbel || exit 0

# 3. replays of the 117 roots
LOGS=$(cat $RUNS/replay-build/logs.txt)
for v in g1 g4 u1; do
  stop && { log "gate-at: stopped before replay $v"; exit 0; }
  out=$RUNS/gumbel-replay-$v.txt
  [ -s $out ] && grep -q "^exit 0" $out && continue
  case $v in g1) A=gumbel; M=1 ;; g4) A=gumbel; M=4 ;; u1) A=ucb1; M=1 ;; esac
  log "gate-at: replay $v"
  ( cd $FORGE_GUI && $JAVA -Xmx1g -XX:ParallelGCThreads=2 -Dfile.encoding=UTF-8 -Dmagicdosa.reproducible=true -Dfollow=true \
      -Dmagicdosa.search.debug=true -Dmagicdosa.timing=true -Dmagicdosa.search.leafModel=$G3 -Dmagicdosa.search.algorithm=$A \
      -cp "$CP" magicdosa.agent.shadow.SearchReplay 12 $M 30 $LOGS > $out 2>&1; echo "exit $?" >> $out )
done
{ echo "same-state answers, Gumbel 1x vs 4x: $(python "$(cygpath -w $C/same_state.py)" "$(cygpath -w $RUNS/gumbel-replay-g1.txt)" "$(cygpath -w $RUNS/gumbel-replay-g4.txt)")"
  echo "same-state answers, Gumbel 1x vs UCB1 1x: $(python "$(cygpath -w $C/same_state.py)" "$(cygpath -w $RUNS/gumbel-replay-g1.txt)" "$(cygpath -w $RUNS/gumbel-replay-u1.txt)")"
  for v in g1 g4 u1; do echo "$v: $(grep -o 'search\.decision=[0-9]*ms/[0-9]*' $RUNS/gumbel-replay-$v.txt | tail -1)"; done
} > $RUNS/gumbel-replay-summary.txt 2>&1
log "gate-at: done ($(tr '\n' ' ' < $RUNS/gumbel-replay-summary.txt))"
