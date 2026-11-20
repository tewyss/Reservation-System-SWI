#!/usr/bin/env bash
# C09 - the same checks as .github/workflows/ci.yml, for a machine without CI.
# Usage:  scripts/verify.sh            (H2 only)
#         scripts/verify.sh --postgres (also the HTTP integration tests on PostgreSQL;
#                                       needs the swi-pg container from the README)
# Exit code 0 = every executed check passed. Log: target/verify.log
set -euo pipefail
cd "$(dirname "$0")/.."
MVN="${MVN:-mvn}"
LOG=target/verify.log
mkdir -p target
{
  echo "commit: $(git rev-parse HEAD) $(git status --porcelain | grep -q . && echo '(DIRTY working tree)')"
  echo "date:   $(date -Iseconds)"
  echo "java:   $(java -version 2>&1 | head -1)"
} | tee "$LOG"

"$MVN" -B clean verify | tee -a "$LOG"
sha256sum target/SWIReservationSystem-*.jar | tee -a "$LOG"

if [ "${1:-}" = "--postgres" ]; then
  docker exec swi-pg psql -U swi -d reservations -tAc "select 1 from pg_database where datname='swi_test'" | grep -q 1 \
    || docker exec swi-pg psql -U swi -d reservations -c "create database swi_test"
  "$MVN" -B test -Dtest=C08CancellationBaselineTest,C09CancellationNoticeTest,CP1WalkingSkeletonTest \
    -Dspring.datasource.url=jdbc:postgresql://localhost:5432/swi_test \
    -Dspring.datasource.driver-class-name=org.postgresql.Driver \
    -Dspring.datasource.username=swi -Dspring.datasource.password=swi | tee -a "$LOG"
fi
echo "VERIFY: OK" | tee -a "$LOG"
