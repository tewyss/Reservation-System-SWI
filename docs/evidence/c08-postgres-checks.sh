#!/usr/bin/env bash
# R-01 treatment: REST checks from the C06 assessment, run against the postgres profile.
# Usage: c08-postgres-checks.sh <repo dir> <app log>  (app running: profiles postgres,seed, port 8082;
#        database: the swi-pg container from application-postgres.properties)
set -u
REPO="$1"
B=http://localhost:8082/reservations
D=2026-11-05

call() {  # call <label> <actor> <method> <path> [json]
  local label="$1" actor="$2" method="$3" path="$4" body="${5:-}"
  local args=(-s -o /tmp/pg-resp -w '%{http_code}' -X "$method" -H "X-Actor-Id: $actor")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  local code; code=$(curl "${args[@]}" "$B$path")
  printf '%-58s -> %s  %s\n' "$label" "$code" "$(cat /tmp/pg-resp)"
}
id_of() { sed -E 's/.*"id":([0-9]+).*/\1/' /tmp/pg-resp; }

echo "# R-01: REST checks on PostgreSQL"
echo "date:     $(date -Iseconds)"
echo "commit:   $(git -C "$REPO" rev-parse HEAD) ($(git -C "$REPO" describe --tags --always))"
echo "database: $(docker exec swi-pg psql -U swi -d reservations -tAc 'select version()' | cut -d, -f1)"
echo "app:      mvn spring-boot:run -Dspring-boot.run.profiles=postgres,seed (port 8082)"
echo "datasource: $(grep -o 'PgConnection@[0-9a-f]*' "$2" | head -1) (HikariPool, from the app log)"
echo

echo "## 1. Availability, create, confirm, conflict (court 1, Kurt 1)"
call "availability [13:00,14:00)" 1 GET "/availability?courtId=1&start=${D}T13:00:00&end=${D}T14:00:00"
call "create as Alice [13:00,14:00)" 1 POST "" "{\"courtId\":1,\"start\":\"${D}T13:00:00\",\"end\":\"${D}T14:00:00\"}"; R1=$(id_of)
call "confirm $R1 as Alice" 1 POST "/$R1/confirm"
call "availability overlapping [13:30,14:30)" 1 GET "/availability?courtId=1&start=${D}T13:30:00&end=${D}T14:30:00"
call "availability adjacent [14:00,15:00)" 1 GET "/availability?courtId=1&start=${D}T14:00:00&end=${D}T15:00:00"
call "create as Bob overlapping [13:30,14:30)" 2 POST "" "{\"courtId\":1,\"start\":\"${D}T13:30:00\",\"end\":\"${D}T14:30:00\"}"; R2=$(id_of)
call "confirm $R2 as Bob (expect 409 CONFLICT)" 2 POST "/$R2/confirm"
echo

echo "## 2. Approval path (court 3, requires approval)"
call "create as Alice on court 3" 1 POST "" "{\"courtId\":3,\"start\":\"${D}T10:00:00\",\"end\":\"${D}T11:00:00\"}"; R3=$(id_of)
call "confirm $R3 as Alice (expect PENDING_APPROVAL)" 1 POST "/$R3/confirm"
call "approve $R3 as Dana (STAFF)" 3 POST "/$R3/approve"
echo

echo "## 3. Inactive court, cancel, invalid interval"
call "availability on inactive court 4" 1 GET "/availability?courtId=4&start=${D}T10:00:00&end=${D}T11:00:00"
call "create as Alice on court 2" 1 POST "" "{\"courtId\":2,\"start\":\"${D}T18:00:00\",\"end\":\"${D}T19:00:00\"}"; R4=$(id_of)
call "cancel $R4 as Alice" 1 POST "/$R4/cancel"
call "availability after cancel" 1 GET "/availability?courtId=2&start=${D}T18:00:00&end=${D}T19:00:00"
call "create with start == end (expect 400)" 1 POST "" "{\"courtId\":2,\"start\":\"${D}T12:00:00\",\"end\":\"${D}T12:00:00\"}"
echo

echo "## 4. BR-02 under concurrency on PostgreSQL (V-03.9 shape, court 2, [20:00,21:00))"
IDS=()
for i in 1 2 3 4 5 6 7 8; do
  actor=$(( (i % 2) + 1 ))
  curl -s -o /tmp/pg-resp -X POST -H "X-Actor-Id: $actor" -H 'Content-Type: application/json' \
       -d "{\"courtId\":2,\"start\":\"${D}T20:00:00\",\"end\":\"${D}T21:00:00\"}" "$B"
  IDS+=("$(id_of):$actor")
done
echo "8 DRAFTs created for the same slot: ${IDS[*]}"
for pair in "${IDS[@]}"; do
  ( id=${pair%%:*}; actor=${pair##*:}
    code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "X-Actor-Id: $actor" "$B/$id/confirm")
    echo "confirm $id -> $code" ) &
done | sort
wait
echo "result in the database:"
docker exec swi-pg psql -U swi -d reservations -tAc \
  "select state, count(*) from reservation where court_id=2 and start_time='${D} 20:00:00' group by state order by state"
