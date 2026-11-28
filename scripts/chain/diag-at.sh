#!/bin/bash
# Waits until 2026-11-29 02:00 (user decision: no game runs before then), then runs the 0B diagnosis runs (diag.sh
# forgeai gen0sample, in parallel) and afterwards the leaf-playout re-measure: SearchReplay of the 117 roots
# (gate3-step3-gen2 n0..n4 first games, 30 decisions each, recorded answers followed, gen2-c leaf, 12 per candidate, 1x,
# reproducible, search debug + timing) with leafPlayout=random, leafPlayout=forge-ai and plain (no playout), one JVM at a
# time, on the class snapshot in replay-build/. Outputs E:/magicdosa-runs/playout2-<variant>-x1.txt.
# Cancel: create chain/DIAG-STOP (while waiting it exits within a minute; during the runs no new unit or replay starts),
# or kill the bash process running chain/diag-at.sh. Progress: chain/diag.log.
C=/e/magicdosa-runs/chain
RUNS=/e/magicdosa-runs
FORGE_GUI=E:/workspace/magicdosa/forge/forge-gui
JAVA=/e/jdk/jdk17/bin/java
log() { echo "$(date '+%F %T') $*" >> $C/diag.log; }

log "diag-at: waiting for 2026-11-29 02:00 (pid $$)"
until [ "$(date +%Y%m%d%H%M)" -ge 202611290200 ]; do
  [ -f $C/DIAG-STOP ] && { log "diag-at: cancelled (DIAG-STOP) while waiting"; exit 0; }
  sleep 60
done
log "diag-at: starting the 0B runs"
bash $C/diag.sh forgeai gen0sample
LOGS=$(cat $RUNS/replay-build/logs.txt)
CP=$(cat $RUNS/replay-build/cp.txt)
for v in random forge-ai plain; do
  [ -f $C/DIAG-STOP ] && { log "diag-at: stopped (DIAG-STOP) before replay $v"; exit 0; }
  out=$RUNS/playout2-$v-x1.txt
  [ -s $out ] && grep -q "^exit 0" $out && continue
  P=""; [ $v != plain ] && P="-Dmagicdosa.search.leafPlayout=$v"
  log "diag-at: replay $v"
  ( cd $FORGE_GUI && $JAVA -Xmx1g -XX:ParallelGCThreads=2 -Dfile.encoding=UTF-8 -Dmagicdosa.reproducible=true -Dfollow=true \
      -Dmagicdosa.search.debug=true -Dmagicdosa.timing=true -Dmagicdosa.search.leafModel=E:/magicdosa-runs/gen2/c/gen2-c.onnx $P \
      -cp "$CP" magicdosa.agent.shadow.SearchReplay 12 1 30 $LOGS > $out 2>&1; echo "exit $?" >> $out )
  log "diag-at: replay $v $(tail -1 $out)"
done
#  sequential determinism harness on the same snapshot (the leafPlayout=random change touches search)
if [ ! -f $C/DIAG-STOP ] && ! grep -q "different=0" $RUNS/replay-build/determinism.txt 2>/dev/null; then
  log "diag-at: determinism harness"
  ( cd $FORGE_GUI && $JAVA -Xmx1g -Dfile.encoding=UTF-8 -cp "$CP" magicdosa.agent.determinism.ParallelDeterminismHarness \
      E:/workspace/magicdosa/.claude/worktrees/phase3-search-b28914/decks IzzetProwess.dck EsperBlink.dck 20 1 5000 \
      > $RUNS/replay-build/determinism.txt 2>&1 )
  log "diag-at: determinism $(grep RESULT $RUNS/replay-build/determinism.txt | tail -1)"
fi
log "diag-at: done"
