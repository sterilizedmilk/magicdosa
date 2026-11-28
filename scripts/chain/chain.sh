#!/bin/bash
# Detached run chain, cycle 3 (2026-11-28 coordinator request), each phase resumable:
#   1. 1x vs 1x self-play on gen3-d from b0042 on, until 1,000 games on the gen3-d leaf in batches that ran a protocol-17+
#      build (only b0042+ count; b0041 is left out by request), then chain/selfplay-gen3-1000.done (the batches) and a pause
#   2. wait for chain/eval-model-gen4.txt (gen-4 ONNX path, completed-Q targets)
#   3. baseline: gen-4 network alone (B, policy argmax) vs random (A), Izzet mirror, seats alternating, fresh seeds
#      40,000-40,419 (5 units x 20 matches, bases 40000+100*i), in gate1-gen4-net-vs-random. If the 95% Wilson lower
#      bound of B is not above 50%, the chain stops there and step 4 is skipped
#   4. gate 1: 1x search (B) vs gen-4 network alone (A), gen-4 leaf both sides, fresh seeds 41,000-41,439 (5 units x 40
#      matches, bases 41000+100*i, at least 400 games), in gate1-gen4-search-vs-net. Pass: search wins 55%+
#   5. after steps 3 and 4: BehaviorMetrics on the run (metrics/<run>.md) and 3 LogText samples (log-samples/<run>/)
#   6. stop: "cycle 3 done, waiting for the user" (no more self-play)
# Gate settings: reproducible, sampling off, shards on. Builds: new batches and runs use chain/builds/<current.txt>;
# a batch or run keeps the build it started with. Stop: chain/STOP. Status: chain/status.txt, status.log.
# Resumable: a unit with summary.txt is skipped; one without is moved to <unit>.crashed.N and rerun (3 tries).
C=/e/magicdosa-runs/chain
RUNS=/e/magicdosa-runs
DECKS=E:/workspace/magicdosa/.claude/worktrees/phase3-search-b28914/decks
FORGE_GUI=E:/workspace/magicdosa/forge/forge-gui
JAVA=/e/jdk/jdk17/bin/java
WORKERS=${WORKERS:-5}
MATCHES=${MATCHES:-4}
TAB=$(printf '\t')
cd $C

status() { echo "$(date '+%F %T') $*" | tee -a $C/status.log > $C/status.txt; }
stopping() { [ -f $C/STOP ]; }
free_gb() { df -BG --output=avail /e | tail -1 | tr -dc 0-9; }
classpath() { local win cp; win=$(cygpath -w $1); cp=$(cat $C/cp-template.txt); echo "${cp//__CLASSES__/$win}"; }
current_build() { local b; b=$(head -1 $C/builds/current.txt 2>/dev/null | tr -d '\r'); [ -n "$b" ] && [ -d $C/builds/$b ] && echo $b; }
build_dir() { if [ -n "$1" ] && [ -d $C/builds/$1 ]; then echo $C/builds/$1; else echo $C/bulk-classes; fi; }
model_from() { [ -s "$1" ] || return 1; local m; m=$(head -1 "$1" | tr -d '\r'); [ -f "$(cygpath -u "$m")" ] || return 1; echo "$m"; }
sha_of() { sha256sum "$(cygpath -u "$1")" | cut -c1-16; }
# protocol version of a frozen build (cached in builds/<b>/protocol.txt); 0 if unknown
build_protocol() {
  local d=$C/builds/$1 p
  [ -n "$1" ] && [ -d $d ] || { echo 0; return; }
  [ -s $d/protocol.txt ] || "${JAVA%/java}/javap" -constants -cp "$(cygpath -w $d/protocol/target/classes)" magicdosa.protocol.Versions \
    | grep -o 'PROTOCOL = [0-9]*' | tr -dc 0-9 > $d/protocol.txt
  p=$(cat $d/protocol.txt); echo ${p:-0}
}

# one unit: $1 out dir, $2 classes dir, $3 seed base, $4 first match, $5 matches, $6 prefix, rest: -D props
run_unit() {
  local out=$1 classes=$2 seed=$3 first=$4 matches=$5 prefix=$6 cp; shift 6
  cp=$(classpath $classes)
  mkdir -p $out
  ( cd $FORGE_GUI && $JAVA -Xmx1g -XX:ParallelGCThreads=2 -XX:ConcGCThreads=1 -Dfile.encoding=UTF-8 -Dmagicdosa.reproducible=true \
      "-Dmagicdosa.agentJvmArgs=-XX:ParallelGCThreads=2 -XX:ConcGCThreads=1" -Dmagicdosa.agentXmx=2g -Dmagicdosa.timing=true \
      -Dmagicdosa.seedBase=$seed -Dmagicdosa.firstMatch=$first "$@" \
      -cp "$cp" magicdosa.runner.forge.harness.HeadToHead $DECKS IzzetProwess.dck IzzetProwess.dck $matches "$(cygpath -w $out)" $prefix \
      > $out.log 2>&1 )
  echo "$(basename $out) exit $? $(date '+%T')" >> $(dirname $out)/done.txt
}

# runs the units listed in $dir/units.txt ("name seed first matches prefix") in parallel, with retries
run_units() {
  local dir=$1 classes=$2; shift 2
  local props=("$@") try
  for try in 1 2 3; do
    local pending=()
    while read -r name seed first matches prefix; do
      [ -z "$name" ] && continue
      [ -f $dir/$name/summary.txt ] && continue
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

# a two-sided run (5 units n0..n4): $1 dir, $2 seed base, $3 matches per unit, $4 prefix, $5 leaf, $6 notes; rest: -D props
two_sided() {
  local dir=$1 seed=$2 matches=$3 prefix=$4 leaf=$5 notes=$6 b; shift 6
  mkdir -p $dir
  if [ ! -f $dir/units.txt ]; then
    for i in $(seq 0 4); do echo "n$i $((seed+100*i)) 0 $matches $prefix$i-"; done > $dir/units.txt
    b=$(current_build)
    echo "$leaf $(sha_of $leaf)" > $dir/leaf.txt
    echo "${b:-legacy}" > $dir/build.txt
    cp $(build_dir $b)/commit.txt $dir/commit.txt 2>/dev/null
    echo "seeds $seed-$((seed+400+matches-1)) (bases $seed+100*i, matches 0-$((matches-1))); $notes; leaf $leaf $(sha_of $leaf); build ${b:-legacy}" > $dir/seeds.txt
  fi
  run_units $dir "$(build_dir "$(head -1 $dir/build.txt | tr -d '\r')")" -Dmagicdosa.agentBudgetPerCandidate=12 \
    -Dmagicdosa.search.leafModel=$leaf "$@"
}

# step 5: behaviour metrics and 3 LogText samples of a finished run ($1 dir, $2 side-B label in shard folder names)
observe() {
  local dir=$1 label=$2 name cp; name=$(basename $1)
  cp=$(classpath "$(build_dir "$(current_build)")")  # the newest build: the tools only read logs, older ones may lack them
  mkdir -p $RUNS/metrics $RUNS/log-samples/$name
  [ -s $RUNS/metrics/$name.md ] || ( cd $FORGE_GUI && $JAVA -Xmx1g -Dfile.encoding=UTF-8 -cp "$cp" magicdosa.agent.tools.BehaviorMetrics \
      --label $name --out "$(cygpath -w $RUNS/metrics/$name.md)" "$(cygpath -w $dir)" > $dir/metrics.log 2>&1 )
  python "$(cygpath -w $C/samples.py)" "$(cygpath -w $dir)" $label 3 | tr -d '\r' | while IFS="$TAB" read -r log out; do
    [ -s $RUNS/log-samples/$name/$out ] && continue
    ( cd $FORGE_GUI && $JAVA -Xmx1g -Dfile.encoding=UTF-8 -cp "$cp" magicdosa.agent.tools.LogText "$log" \
        "$(cygpath -w $RUNS/log-samples/$name/$out)" >> $dir/metrics.log 2>&1 )
  done
  status "observed $name: metrics/$name.md, $(ls $RUNS/log-samples/$name | wc -l) log samples"
}
observed() { [ -s $RUNS/metrics/$(basename $1).md ] && [ "$(ls $RUNS/log-samples/$(basename $1) 2>/dev/null | wc -l)" -ge 3 ]; }

status "chain started (pid $$): cycle 3"
G3=E:/magicdosa-runs/gen3/d/gen3-d.onnx
G3SHA=$(sha_of $G3)
S=$RUNS/selfplay
FIRST=42
MARK=$C/selfplay-gen3-1000.done
EVALF=$C/eval-model-gen4.txt
BASE=$RUNS/gate1-gen4-net-vs-random
NG=$RUNS/gate1-gen4-search-vs-net

# self-play batches from b0042 on with the gen3-d leaf and a protocol-17+ build
counted_batches() {
  local d n
  for d in $S/b*/; do
    n=$(basename $d); n=$((10#${n#b})); [ $n -ge $FIRST ] || continue
    grep -q " $G3SHA" $d/leaf.txt 2>/dev/null || continue
    [ "$(build_protocol "$(cut -d' ' -s -f3 $d/leaf.txt)")" -ge 17 ] && echo ${d%/}
  done
}
counted_games() {
  local n=0 d u
  for d in $(counted_batches); do
    for u in $d/w*/summary.txt; do [ -f "$u" ] && n=$((n + $(ls $(dirname $u)/*.pb.gz 2>/dev/null | wc -l))); done
  done
  echo $n
}

# 1. self-play on gen3-d to 1,000 counted games
if [ ! -f $MARK ]; then
  b=$FIRST
  while [ -f $S/$(printf 'b%04d' $b)/units.txt ] && [ "$(ls $S/$(printf 'b%04d' $b)/w*/summary.txt 2>/dev/null | wc -l)" -ge "$(wc -l < $S/$(printf 'b%04d' $b)/units.txt)" ]; do b=$((b+1)); done
  while true; do
    games=$(counted_games)
    if [ $games -ge 1000 ]; then
      { echo "gen3-d self-play games (protocol 17+, b$FIRST+): $games ($(date '+%F %T')), leaf $G3 $G3SHA"; for d in $(counted_batches); do cygpath -w $d; done; } > $MARK.tmp
      mv $MARK.tmp $MARK
      status "self-play paused at $games gen3-d games: wrote chain/selfplay-gen3-1000.done"
      break
    fi
    stopping && { status "stopped (STOP) before batch $b"; exit 0; }
    if [ $(free_gb) -lt 15 ]; then status "stopped: $(free_gb) GB free on E: (under 15) before batch $b"; exit 0; fi
    B=$S/$(printf 'b%04d' $b)
    mkdir -p $B
    if [ ! -f $B/units.txt ]; then
      for w in $(seq 0 $((WORKERS-1))); do echo "w$w $((2000000+1000*b+100*w)) 0 $MATCHES s$(printf '%04d' $b)w$w-"; done > $B/units.txt
      BUILD=$(current_build)
      cp $(build_dir $BUILD)/commit.txt $B/commit.txt 2>/dev/null
      echo "$G3 $G3SHA ${BUILD:-legacy}" > $B/leaf.txt
      echo "leaf $G3 sha256 $G3SHA build ${BUILD:-legacy}" >> $B/done.txt
    fi
    LEAF=$(cut -d' ' -f1 $B/leaf.txt)
    EXTRA=$(cat $C/bulk-props.txt 2>/dev/null | tr '\n' ' ')
    run_units $B "$(build_dir "$(cut -d' ' -s -f3 $B/leaf.txt)")" -Dmagicdosa.agentPolicy=search -Dmagicdosa.agentBudgetPerCandidate=12 \
      -Dmagicdosa.agentBudgetMultiplierA=1 -Dmagicdosa.agentBudgetMultiplierB=1 -Dmagicdosa.search.leafModel=$LEAF $EXTRA \
      || { status "stopped (STOP) during batch $b"; exit 0; }
    status "batch $b done: $(ls $B/w*/summary.txt 2>/dev/null | wc -l) of $(wc -l < $B/units.txt) units, $(ls $B/w*/*.pb.gz 2>/dev/null | wc -l) games, leaf $(cat $B/leaf.txt), counted gen3-d games $(counted_games), $(free_gb) GB free"
    b=$((b+1))
  done
fi

# 2. the gen-4 model
while ! G4=$(model_from $EVALF); do
  stopping && { status "stopped (STOP) while waiting for chain/eval-model-gen4.txt"; exit 0; }
  status "waiting for chain/eval-model-gen4.txt (the gen-4 model)"
  sleep 120
done

# 3. baseline: gen-4 network alone (B) vs random (A)
if [ ! -f $BASE/BASE-DONE ]; then
  stopping && { status "stopped (STOP) before the baseline"; exit 0; }
  if [ $(free_gb) -lt 15 ]; then status "stopped: under 15 GB free on E: before the baseline"; exit 0; fi
  status "baseline (gen-4 network alone vs random): $(basename $BASE)"
  two_sided $BASE 40000 20 r $G4 "A random (uniform over candidates), B network alone (policy argmax)" \
    -Dmagicdosa.agentPolicyA=random -Dmagicdosa.agentPolicyB=network || { status "stopped (STOP) during the baseline"; exit 0; }
  python "$(cygpath -w $C/eval_summary.py)" "$(cygpath -w $BASE)" | sed 's/trained (B)/network alone (B)/; s/trained won/network won/' > $BASE/gate-summary.txt 2>&1
  [ "$(ls $BASE/n*/summary.txt 2>/dev/null | wc -l)" -ge 5 ] || { status "stopped: the baseline has fewer than 5 unit summaries"; exit 0; }
  touch $BASE/BASE-DONE
  status "baseline done: $(head -1 $BASE/gate-summary.txt)"
fi
observed $BASE || observe $BASE network
LO=$(grep -o 'Wilson [0-9.]*' $BASE/gate-summary.txt | head -1 | tr -dc 0-9.)
if ! python -c "import sys; sys.exit(0 if float(sys.argv[1]) > 50 else 1)" "${LO:-0}"; then
  status "stopped: gen-4 network alone is not above random (95% Wilson lower bound ${LO:-?}%, not above 50%); gate 1 skipped, waiting for the user"
  exit 0
fi

# 4. gate 1: 1x search (B) vs gen-4 network alone (A)
if [ ! -f $NG/GATE-DONE ]; then
  stopping && { status "stopped (STOP) before gate 1"; exit 0; }
  if [ $(free_gb) -lt 15 ]; then status "stopped: under 15 GB free on E: before gate 1"; exit 0; fi
  status "gate 1 (search 1x vs gen-4 network alone): $(basename $NG)"
  two_sided $NG 41000 40 g $G4 "A network alone, B search 1x" \
    -Dmagicdosa.agentPolicyA=network -Dmagicdosa.agentPolicyB=search -Dmagicdosa.agentBudgetMultiplierB=1 \
    || { status "stopped (STOP) during gate 1"; exit 0; }
  python "$(cygpath -w $C/eval_summary.py)" "$(cygpath -w $NG)" | sed 's/trained (B)/search x1 (B)/; s/trained won/search won/' > $NG/gate-summary.txt 2>&1
  [ "$(ls $NG/n*/summary.txt 2>/dev/null | wc -l)" -ge 5 ] || { status "stopped: gate 1 has fewer than 5 unit summaries"; exit 0; }
  touch $NG/GATE-DONE
  status "gate 1 done: $(head -1 $NG/gate-summary.txt)"
fi
observed $NG || observe $NG x1

status "cycle 3 done, waiting for the user"
