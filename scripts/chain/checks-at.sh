#!/bin/bash
# Waits until 2026-11-29 02:00 and until diag-at.sh is done (one game workload at a time), then runs the checks of
# 90734c4 (library cards shown for a choice carry their identity) on its class snapshot replay-build-90734c4/: the
# sequential determinism harness (20 matches, 1 thread) and the adapter determinism check (20 matches, agent-core
# spawned). Outputs replay-build-90734c4/{determinism,adapter-determinism}.txt. Cancel: chain/DIAG-STOP, or kill it.
C=/e/magicdosa-runs/chain
R=/e/magicdosa-runs/replay-build-90734c4
FORGE_GUI=E:/workspace/magicdosa/forge/forge-gui
JAVA=/e/jdk/jdk17/bin/java
DECKS=E:/workspace/magicdosa/.claude/worktrees/phase3-search-b28914/decks
log() { echo "$(date '+%F %T') $*" >> $C/diag.log; }
log "checks-at: waiting for 2026-11-29 02:00 and diag-at done (pid $$)"
until [ "$(date +%Y%m%d%H%M)" -ge 202611290200 ] && grep -q "diag-at: \(done\|stopped\|cancelled\)" $C/diag.log; do
  [ -f $C/DIAG-STOP ] && { log "checks-at: cancelled (DIAG-STOP)"; exit 0; }
  sleep 60
done
CP=$(cat $R/cp.txt)
log "checks-at: determinism harness"
( cd $FORGE_GUI && $JAVA -Xmx1g -Dfile.encoding=UTF-8 -cp "$CP" magicdosa.agent.determinism.ParallelDeterminismHarness \
    $DECKS IzzetProwess.dck EsperBlink.dck 20 1 5000 > $R/determinism.txt 2>&1 )
log "checks-at: determinism $(grep RESULT $R/determinism.txt | tail -1)"
[ -f $C/DIAG-STOP ] && { log "checks-at: stopped (DIAG-STOP)"; exit 0; }
log "checks-at: adapter determinism"
( cd $FORGE_GUI && $JAVA -Xmx1g -Dfile.encoding=UTF-8 -Dmagicdosa.agentXmx=1g -cp "$CP" magicdosa.runner.forge.harness.AdapterDeterminism \
    $DECKS IzzetProwess.dck EsperBlink.dck 20 > $R/adapter-determinism.txt 2>&1 )
log "checks-at: adapter determinism $(grep -i "RESULT\|different" $R/adapter-determinism.txt | tail -1)"
log "checks-at: done"
