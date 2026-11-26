#!/bin/bash
# Detached run chain (2026-11-26, coordinator request): runs with no Claude session alive.
#   1x vs 1x self-play data run in batches (selfplay/bNNNN) with the build in chain/bulk-classes (2026-11-26 user
#   decision: self-play first; the gate 1 rerun is run separately when asked). Workers per batch: 5 minus the gate-2
#   workers still running. Leaf: gen-0 run 2 until chain/gen1-leaf.txt names an existing ONNX file, then that file from
#   the next batch on; each batch records its leaf (file and SHA-256 prefix) in leaf.txt and done.txt.
#   At 1,000 self-play games (finished units, all batches) it pauses and writes chain/selfplay-1000.done (the batch
#   folders), waits for chain/eval-model.txt (the trained ONNX path), runs the evaluation gen-0 run 2 vs that model
#   (eval-gen0-vs-trained: both 1x, sampling off, 5 units x 12 matches, eval seeds 7,000,000+, eval-summary.txt), and
#   then resumes self-play with the trained model as the leaf.
# Stop: create chain/STOP (it finishes the units under way, then exits). Status: chain/status.txt (now), status.log.
# Resumable: a unit whose folder has summary.txt is done and skipped; a unit that ended without one is moved to
# <unit>.crashed.N and run again (3 tries). Disk: checked before each batch; stops under 15 GB free on E:.
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

status "chain started (pid $$): self-play"
GEN0=E:/magicdosa-runs/gen0/run2/gen0-run2.onnx
S=$RUNS/selfplay
E=$RUNS/eval-gen0-vs-trained
mkdir -p $S
MATCHES=${MATCHES:-4}
model_from() { [ -s "$1" ] || return 1; local m; m=$(head -1 "$1" | tr -d '\r'); [ -f "$(cygpath -u "$m")" ] || return 1; echo "$m"; }
finished_games() { local n=0 u; for u in $S/b*/w*/summary.txt; do [ -f "$u" ] && n=$((n + $(ls $(dirname $u)/*.pb.gz 2>/dev/null | wc -l))); done; echo $n; }

evaluate() {
  local trained=$1
  mkdir -p $E
  if [ ! -f $E/units.txt ]; then
    for i in $(seq 0 4); do echo "n$i $((7000000+100*i)) 0 12 e$i-"; done > $E/units.txt
    { echo "A (gen-0 run 2): $GEN0 $(sha256sum "$(cygpath -u $GEN0)" | cut -c1-16)"; echo "B (trained): $trained $(sha256sum "$(cygpath -u $trained)" | cut -c1-16)"; } > $E/models.txt
    cp $C/eval-classes/commit.txt $E/commit.txt 2>/dev/null
  fi
  run_units $E $C/eval-classes -Dmagicdosa.agentPolicy=search -Dmagicdosa.agentBudgetPerCandidate=12 \
    -Dmagicdosa.agentBudgetMultiplierA=1 -Dmagicdosa.agentBudgetMultiplierB=1 \
    -Dmagicdosa.search.leafModelA=$GEN0 -Dmagicdosa.search.leafModelB=$trained || return 1
  python "$(cygpath -w $C/eval_summary.py)" "$(cygpath -w $E)" > $E/eval-summary.txt 2>&1
  [ "$(ls $E/n*/summary.txt 2>/dev/null | wc -l)" -ge 5 ] && touch $E/EVAL-DONE
  return 0
}

b=0
while [ -f $S/$(printf 'b%04d' $b)/units.txt ] && [ "$(ls $S/$(printf 'b%04d' $b)/w*/summary.txt 2>/dev/null | wc -l)" -ge "$(wc -l < $S/$(printf 'b%04d' $b)/units.txt)" ]; do b=$((b+1)); done
while true; do
  stopping && { status "stopped (STOP) before batch $b"; exit 0; }
  if [ $(free_gb) -lt 15 ]; then status "stopped: $(free_gb) GB free on E: (under 15) before batch $b"; exit 0; fi
  if [ ! -f $E/EVAL-DONE ]; then
    games=$(finished_games)
    if [ ! -f $C/selfplay-1000.done ] && [ $games -ge 1000 ]; then
      { echo "self-play games: $games ($(date '+%F %T'))"; for d in $S/b*/; do echo "$(cygpath -w ${d%/})"; done; } > $C/selfplay-1000.done.tmp
      mv $C/selfplay-1000.done.tmp $C/selfplay-1000.done
      status "self-play paused at $games games: wrote chain/selfplay-1000.done"
    fi
    if [ -f $C/selfplay-1000.done ]; then
      while ! trained=$(model_from $C/eval-model.txt); do
        stopping && { status "stopped (STOP) while waiting for chain/eval-model.txt"; exit 0; }
        status "waiting for chain/eval-model.txt (the trained model)"
        sleep 120
      done
      status "evaluation: gen-0 run 2 vs $trained"
      evaluate "$trained" || { status "stopped (STOP) during the evaluation"; exit 0; }
      status "evaluation done: $(head -1 $E/eval-summary.txt)"
      continue
    fi
  fi
  LEAF=$GEN0
  if [ -f $E/EVAL-DONE ] && m=$(model_from $C/eval-model.txt); then LEAF=$m
  elif m=$(model_from $C/gen1-leaf.txt); then LEAF=$m; fi
  SHA=$(sha256sum "$(cygpath -u "$LEAF")" | cut -c1-16)
  B=$S/$(printf 'b%04d' $b)
  mkdir -p $B
  if [ ! -f $B/units.txt ]; then
    g2=$(( 5 - $(wc -l < $RUNS/gate3-g2-net/done.txt 2>/dev/null || echo 5) ))
    [ $g2 -lt 0 ] && g2=0
    n=$(( WORKERS - g2 )); [ $n -lt 1 ] && n=1
    for w in $(seq 0 $((n-1))); do echo "w$w $((2000000+1000*b+100*w)) 0 $MATCHES s$(printf '%04d' $b)w$w-"; done > $B/units.txt
    cp $C/bulk-classes/commit.txt $B/commit.txt 2>/dev/null
    echo "$LEAF $SHA" > $B/leaf.txt
    echo "leaf $LEAF sha256 $SHA" >> $B/done.txt
  fi
  LEAF=$(cut -d' ' -f1 $B/leaf.txt)
  EXTRA=$(cat $C/bulk-props.txt 2>/dev/null | tr '\n' ' ')
  run_units $B $C/bulk-classes -Dmagicdosa.agentPolicy=search -Dmagicdosa.agentBudgetPerCandidate=12 \
    -Dmagicdosa.agentBudgetMultiplierA=1 -Dmagicdosa.agentBudgetMultiplierB=1 -Dmagicdosa.search.leafModel=$LEAF $EXTRA \
    || { status "stopped (STOP) during batch $b"; exit 0; }
  status "batch $b done: $(ls $B/w*/summary.txt 2>/dev/null | wc -l) of $(wc -l < $B/units.txt) units, $(ls $B/w*/*.pb.gz 2>/dev/null | wc -l) games, leaf $(cat $B/leaf.txt), total $(finished_games) games, $(free_gb) GB free"
  b=$((b+1))
done
