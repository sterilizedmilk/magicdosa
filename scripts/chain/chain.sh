#!/bin/bash
# Detached run chain (2026-11-26, coordinator request): runs with no Claude session alive.
#   Cycle 2 (2026-11-27 user decision), each phase resumable:
#   1. gate 1 rerun with the gen2-c leaf: 1x vs 4x, gate settings (sampling off), the seeds of gate3-step3-net (paired),
#      in gate3-step3-gen2 (5 units x 10 matches); GATE-DONE when all five have a summary
#   2. 1x vs 1x self-play on gen2-c until 1,000 games on the gen2-c leaf (batches whose leaf.txt carries its SHA; the
#      b0021+ batches count), then chain/selfplay-gen2-1000.done (the batches) and a pause
#   3. wait for chain/eval-model-gen3.txt (gen-3 ONNX path), evaluate gen2-c (A) vs gen-3 (B) in eval-gen2-vs-gen3
#      (both 1x, sampling off, eval seeds 7,100,000+), then self-play on gen-3 unless it is significantly worse
#      (Wilson upper bound of its win rate under 50%), else on gen2-c; the choice goes to chain/selfplay-leaf.txt
# Stop: create chain/STOP (it finishes the units under way, then exits). Status: chain/status.txt (now), status.log.
# Resumable: a unit whose folder has summary.txt is done and skipped; a unit that ended without one is moved to
# <unit>.crashed.N and run again (3 tries). Disk: checked before each phase and batch; stops under 15 GB free on E:.
C=/e/magicdosa-runs/chain
RUNS=/e/magicdosa-runs
DECKS=E:/workspace/magicdosa/.claude/worktrees/phase3-search-b28914/decks
FORGE_GUI=E:/workspace/magicdosa/forge/forge-gui
JAVA=/e/jdk/jdk17/bin/java
WORKERS=${WORKERS:-5}
cd $C

status() { echo "$(date '+%F %T') $*" | tee -a $C/status.log > $C/status.txt; }
stopping() { [ -f $C/STOP ]; }
free_gb() { df -BG --output=avail /e | tail -1 | tr -dc 0-9; }

# one unit: $1 out dir (absolute, unix), $2 classes dir, $3 seed base, $4 first match, $5 matches, $6 prefix, rest: -D props
run_unit() {
  local out=$1 classes=$2 seed=$3 first=$4 matches=$5 prefix=$6; shift 6
  local win cp; win=$(cygpath -w $classes); cp=$(cat $C/cp-template.txt); cp=${cp//__CLASSES__/$win}
  mkdir -p $out
  ( cd $FORGE_GUI && $JAVA -Xmx1g -XX:ParallelGCThreads=2 -XX:ConcGCThreads=1 -Dfile.encoding=UTF-8 -Dmagicdosa.reproducible=true \
      "-Dmagicdosa.agentJvmArgs=-XX:ParallelGCThreads=2 -XX:ConcGCThreads=1" -Dmagicdosa.agentXmx=2g -Dmagicdosa.timing=true \
      -Dmagicdosa.seedBase=$seed -Dmagicdosa.firstMatch=$first "$@" \
      -cp "$cp" magicdosa.runner.forge.harness.HeadToHead $DECKS IzzetProwess.dck IzzetProwess.dck $matches "$(cygpath -w $out)" $prefix \
      > $out.log 2>&1 )
  echo "$(basename $out) exit $? $(date '+%T')" >> $(dirname $out)/done.txt
}

# runs the units given as "name seed first matches prefix" lines from stdin in parallel, with retries
run_units() {
  local dir=$1 classes=$2; shift 2
  local props=("$@")
  local try
  for try in 1 2 3; do
    local pending=()
    while read -r name seed first matches prefix; do
      [ -z "$name" ] && continue
      if [ -f $dir/$name/summary.txt ]; then continue; fi
      if [ -d $dir/$name ]; then mv $dir/$name $dir/$name.crashed.$try; mv $dir/$name.log $dir/$name.crashed.$try.log 2>/dev/null; fi
      pending+=("$name $seed $first $matches $prefix")
    done < $dir/units.txt
    [ ${#pending[@]} -eq 0 ] && return 0
    stopping && return 1
    status "$(basename $dir): running ${#pending[@]} units (try $try)"
    for u in "${pending[@]}"; do
      set -- $u
      run_unit $dir/$1 $classes $2 $3 $4 $5 "${props[@]}" &
    done
    wait
  done
  return 0
}

status "chain started (pid $$): cycle 2"
CUR=E:/magicdosa-runs/gen2/c/gen2-c.onnx
CURSHA=$(sha256sum "$(cygpath -u $CUR)" | cut -c1-16)
S=$RUNS/selfplay
GATE=$RUNS/gate3-step3-gen2
E=$RUNS/eval-gen2-vs-gen3
EVALF=$C/eval-model-gen3.txt
MARK=$C/selfplay-gen2-1000.done
CLASSES=$C/cycle2-classes
MATCHES=${MATCHES:-4}
model_from() { [ -s "$1" ] || return 1; local m; m=$(head -1 "$1" | tr -d '\r'); [ -f "$(cygpath -u "$m")" ] || return 1; echo "$m"; }
leaf_games() { local n=0 b; for b in $S/b*/; do grep -q " $1" $b/leaf.txt 2>/dev/null || continue; for u in $b/w*/summary.txt; do [ -f "$u" ] && n=$((n + $(ls $(dirname $u)/*.pb.gz 2>/dev/null | wc -l))); done; done; echo $n; }

# 1. gate 1 rerun with gen2-c
if [ ! -f $GATE/GATE-DONE ]; then
  mkdir -p $GATE
  if [ ! -f $GATE/units.txt ]; then
    for i in $(seq 0 4); do echo "n$i $((8000+100*i)) 0 10 n$i"; done > $GATE/units.txt
    echo "$CUR $CURSHA" > $GATE/leaf.txt
    cp $CLASSES/commit.txt $GATE/commit.txt
  fi
  if [ $(free_gb) -lt 15 ]; then status "stopped: under 15 GB free on E: before the gate rerun"; exit 0; fi
  status "gate 1 rerun with gen2-c (gate3-step3-gen2)"
  run_units $GATE $CLASSES -Dmagicdosa.agentPolicy=search -Dmagicdosa.agentBudgetPerCandidate=12 \
    -Dmagicdosa.agentBudgetMultiplierA=1 -Dmagicdosa.agentBudgetMultiplierB=4 -Dmagicdosa.search.leafModel=$CUR \
    || { status "stopped (STOP) during the gate rerun"; exit 0; }
  python "$(cygpath -w $C/eval_summary.py)" "$(cygpath -w $GATE)" > $GATE/gate-summary.txt 2>&1
  [ "$(ls $GATE/n*/summary.txt 2>/dev/null | wc -l)" -ge 5 ] && touch $GATE/GATE-DONE
  status "gate 1 rerun done: $(head -1 $GATE/gate-summary.txt | sed 's/trained (B)/4x/')"
fi

# 2-3. self-play on gen2-c to 1,000 games, evaluation gen2-c vs gen-3, self-play on the choice
b=0
while [ -f $S/$(printf 'b%04d' $b)/units.txt ] && [ "$(ls $S/$(printf 'b%04d' $b)/w*/summary.txt 2>/dev/null | wc -l)" -ge "$(wc -l < $S/$(printf 'b%04d' $b)/units.txt)" ]; do b=$((b+1)); done
while true; do
  stopping && { status "stopped (STOP) before batch $b"; exit 0; }
  if [ $(free_gb) -lt 15 ]; then status "stopped: $(free_gb) GB free on E: (under 15) before batch $b"; exit 0; fi
  if [ ! -f $E/EVAL-DONE ]; then
    games=$(leaf_games $CURSHA)
    if [ ! -f $MARK ] && [ $games -ge 1000 ]; then
      { echo "gen2-c self-play games: $games ($(date '+%F %T')), leaf $CUR $CURSHA"; for d in $S/b*/; do grep -q " $CURSHA" $d/leaf.txt 2>/dev/null && echo "$(cygpath -w ${d%/})"; done; } > $MARK.tmp
      mv $MARK.tmp $MARK
      status "self-play paused at $games gen2-c games: wrote chain/selfplay-gen2-1000.done"
    fi
    if [ -f $MARK ]; then
      while ! NEW=$(model_from $EVALF); do
        stopping && { status "stopped (STOP) while waiting for chain/eval-model-gen3.txt"; exit 0; }
        status "waiting for chain/eval-model-gen3.txt (the gen-3 model)"
        sleep 120
      done
      mkdir -p $E
      if [ ! -f $E/units.txt ]; then
        for i in $(seq 0 4); do echo "n$i $((7100000+100*i)) 0 12 f$i-"; done > $E/units.txt
        { echo "A (gen2-c): $CUR $CURSHA"; echo "B (gen-3): $NEW $(sha256sum "$(cygpath -u $NEW)" | cut -c1-16)"; } > $E/models.txt
        cp $CLASSES/commit.txt $E/commit.txt
      fi
      status "evaluation: gen2-c vs $NEW"
      run_units $E $CLASSES -Dmagicdosa.agentPolicy=search -Dmagicdosa.agentBudgetPerCandidate=12 \
        -Dmagicdosa.agentBudgetMultiplierA=1 -Dmagicdosa.agentBudgetMultiplierB=1 \
        -Dmagicdosa.search.leafModelA=$CUR -Dmagicdosa.search.leafModelB=$NEW || { status "stopped (STOP) during the evaluation"; exit 0; }
      python "$(cygpath -w $C/eval_summary.py)" "$(cygpath -w $E)" > $E/eval-summary.txt 2>&1
      if [ "$(ls $E/n*/summary.txt 2>/dev/null | wc -l)" -ge 5 ]; then
        # gen-3 unless significantly worse: the upper end of its 95% Wilson interval under 50%
        if python -c "import re,sys; t=open(sys.argv[1]).read(); m=re.search(r'Wilson [\d.]+-([\d.]+)%',t); sys.exit(0 if m and float(m.group(1))<50 else 1)" "$(cygpath -w $E/eval-summary.txt)"; then
          echo "$CUR" > $C/selfplay-leaf.txt
        else
          echo "$NEW" > $C/selfplay-leaf.txt
        fi
        touch $E/EVAL-DONE
      fi
      status "evaluation done: $(head -1 $E/eval-summary.txt); self-play leaf $(cat $C/selfplay-leaf.txt 2>/dev/null)"
      continue
    fi
  fi
  LEAF=$CUR
  if [ -f $E/EVAL-DONE ] && m=$(model_from $C/selfplay-leaf.txt); then LEAF=$m; fi
  SHA=$(sha256sum "$(cygpath -u "$LEAF")" | cut -c1-16)
  B=$S/$(printf 'b%04d' $b)
  mkdir -p $B
  if [ ! -f $B/units.txt ]; then
    for w in $(seq 0 $((WORKERS-1))); do echo "w$w $((2000000+1000*b+100*w)) 0 $MATCHES s$(printf '%04d' $b)w$w-"; done > $B/units.txt
    cp $C/bulk-classes/commit.txt $B/commit.txt 2>/dev/null
    echo "$LEAF $SHA" > $B/leaf.txt
    echo "leaf $LEAF sha256 $SHA" >> $B/done.txt
  fi
  LEAF=$(cut -d' ' -f1 $B/leaf.txt)
  EXTRA=$(cat $C/bulk-props.txt 2>/dev/null | tr '\n' ' ')
  run_units $B $C/bulk-classes -Dmagicdosa.agentPolicy=search -Dmagicdosa.agentBudgetPerCandidate=12 \
    -Dmagicdosa.agentBudgetMultiplierA=1 -Dmagicdosa.agentBudgetMultiplierB=1 -Dmagicdosa.search.leafModel=$LEAF $EXTRA \
    || { status "stopped (STOP) during batch $b"; exit 0; }
  status "batch $b done: $(ls $B/w*/summary.txt 2>/dev/null | wc -l) of $(wc -l < $B/units.txt) units, $(ls $B/w*/*.pb.gz 2>/dev/null | wc -l) games, leaf $(cat $B/leaf.txt), gen2-c games $(leaf_games $CURSHA), $(free_gb) GB free"
  b=$((b+1))
done
