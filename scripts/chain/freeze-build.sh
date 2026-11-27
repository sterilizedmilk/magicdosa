#!/bin/bash
# Freezes the compiled classes of a built checkout (mvn install / verify done) as chain/builds/<commit> and makes it the
# build that the chain's next batch picks up (chain/builds/current.txt, written atomically). Usage: freeze-build.sh <repo>
set -e
REPO=${1:-/e/workspace/magicdosa}
C=/e/magicdosa-runs/chain
cd $REPO
COMMIT=$(git rev-parse --short HEAD)
if [ -n "$(git status --short --ignore-submodules=all -- agent-core adapter runner protocol proto)" ]; then
  echo "refused: $REPO has uncommitted changes in the Java modules"; exit 1
fi
D=$C/builds/$COMMIT
mkdir -p $D.tmp
for d in runner/forge/target/test-classes runner/forge/target/classes adapter/forge/target/classes agent-core/target/classes agent-core/target/test-classes protocol/target/classes; do
  mkdir -p $D.tmp/$d && cp -r $d/. $D.tmp/$d/
done
echo "$COMMIT $(git log -1 --format=%cI) frozen $(date '+%F %T') from $REPO" > $D.tmp/commit.txt
rm -rf $D && mv $D.tmp $D
echo "$COMMIT" > $C/builds/current.txt.tmp && mv $C/builds/current.txt.tmp $C/builds/current.txt
echo "frozen $D; current build $COMMIT"
