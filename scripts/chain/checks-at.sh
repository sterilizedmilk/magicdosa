#!/bin/bash
# Checks of the merged build for the Gumbel gate (2026-11-28 coordinator request; no game runs before 2026-11-29 02:00).
# Waits until 02:00, until diag-at.sh is done (one game workload at a time) and until chain/gate-build.txt names a frozen
# build in chain/builds/ (the build with Gumbel v_mix + Q normalization and the library-identity fix, frozen by the
# coordinator), then runs on it the sequential determinism harness (20 matches, 1 thread) and the adapter determinism
# check (20 matches, agent-core spawned). Writes chain/checks-<build>.txt and logs "checks-at: PASS <build>" or
# "checks-at: FAIL <build>"; gate-at.sh starts the gate only after a PASS. Cancel: chain/DIAG-STOP, or kill it.
C=/e/magicdosa-runs/chain
FORGE_GUI=E:/workspace/magicdosa/forge/forge-gui
JAVA=/e/jdk/jdk17/bin/java
DECKS=E:/workspace/magicdosa/.claude/worktrees/phase3-search-b28914/decks
log() { echo "$(date '+%F %T') $*" >> $C/diag.log; }
build() { local b; b=$(head -1 $C/gate-build.txt 2>/dev/null | tr -d '\r '); [ -n "$b" ] && [ -d $C/builds/$b ] && echo $b; }
log "checks-at: waiting for 02:00, diag-at done and chain/gate-build.txt (pid $$)"
until [ "$(date +%Y%m%d%H%M)" -ge 202611290200 ] && grep -q "diag-at: \(done\|stopped\|cancelled\)" $C/diag.log && build >/dev/null; do
  [ -f $C/DIAG-STOP ] && { log "checks-at: cancelled (DIAG-STOP)"; exit 0; }
  sleep 60
done
B=$(build)
OUT=$C/checks-$B.txt
CP=$(cat $C/cp-template.txt); CP=${CP//__CLASSES__/$(cygpath -w $C/builds/$B)}
log "checks-at: determinism harness on $B"
( cd $FORGE_GUI && $JAVA -Xmx1g -Dfile.encoding=UTF-8 -cp "$CP" magicdosa.agent.determinism.ParallelDeterminismHarness \
    $DECKS IzzetProwess.dck EsperBlink.dck 20 1 5000 > $C/checks-$B-determinism.log 2>&1 )
D=$(grep "^RESULT" $C/checks-$B-determinism.log | tail -1)
[ -f $C/DIAG-STOP ] && { log "checks-at: stopped (DIAG-STOP)"; exit 0; }
log "checks-at: adapter determinism on $B"
( cd $FORGE_GUI && $JAVA -Xmx1g -Dfile.encoding=UTF-8 -Dmagicdosa.agentXmx=1g -cp "$CP" magicdosa.runner.forge.harness.AdapterDeterminism \
    $DECKS IzzetProwess.dck EsperBlink.dck 20 > $C/checks-$B-adapter.log 2>&1 )
A=$(grep "^RESULT" $C/checks-$B-adapter.log | tail -1)
{ echo "build $B"; echo "determinism harness: ${D:-no RESULT line}"; echo "adapter determinism: ${A:-no RESULT line}"; } > $OUT
if echo "$D" | grep -q "different=0 errors=0" && echo "$A" | grep -q "different=0"; then
  log "checks-at: PASS $B (determinism: $D; adapter: $A)"
else
  log "checks-at: FAIL $B (determinism: ${D:-none}; adapter: ${A:-none})"
fi
