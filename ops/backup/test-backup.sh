#!/bin/sh
# Checks backup.sh against a throwaway PostgreSQL in Docker: a backup appears, it restores into a fresh database
# with the same data, a failed dump leaves no file, and only old backup files are deleted. Needs Docker.
set -u
here="$(cd "$(dirname "$0")" && pwd)"
net="vt-backup-test-net"
db="vt-backup-test-db"
dir="$(mktemp -d)"
fail=0
check() { if [ "$1" = "0" ]; then echo "PASS  $2"; else echo "FAIL  $2"; fail=1; fi; }
cleanup() { docker rm -f "$db" > /dev/null 2>&1; docker network rm "$net" > /dev/null 2>&1; rm -rf "$dir"; }
trap cleanup EXIT

docker network create "$net" > /dev/null
docker run -d --name "$db" --network "$net" -e POSTGRES_PASSWORD=pw -e POSTGRES_USER=vacancy -e POSTGRES_DB=vacancy_tracker postgres:16 > /dev/null
until docker exec "$db" psql -U vacancy -d vacancy_tracker -c "select 1" > /dev/null 2>&1; do sleep 1; done
docker exec "$db" psql -U vacancy -d vacancy_tracker -qc "create table t (id int primary key, v text); insert into t values (1, 'one'), (2, 'two');"

run_backup() {
    docker run --rm --network "$net" -v "$here/backup.sh:/backup.sh:ro" -v "$dir:/backups" \
        -e PGHOST="$db" -e PGUSER=vacancy -e PGPASSWORD="$1" -e PGDATABASE=vacancy_tracker -e BACKUP_ONCE=1 \
        -e BACKUP_KEEP_DAYS=14 --entrypoint sh postgres:16 /backup.sh
}

run_backup pw > /dev/null 2>&1
check $? "one run exits successfully"
count="$(ls "$dir" | grep -c '^vacancy_tracker-.*\.dump$')"
[ "$count" = "1" ]; check $? "exactly one dump file appears"
[ -z "$(ls "$dir" | grep partial)" ]; check $? "no partial file is left behind"

# Restore into a fresh database and compare the data.
docker exec "$db" psql -U vacancy -d vacancy_tracker -qc "create database restored;" > /dev/null
file="$(ls "$dir"/vacancy_tracker-*.dump | head -1)"
docker exec -i "$db" pg_restore -U vacancy -d restored --no-owner < "$file"
rows="$(docker exec "$db" psql -U vacancy -d restored -tAc "select string_agg(v, ',' order by id) from t")"
[ "$rows" = "one,two" ]; check $? "the dump restores into a fresh database with the same rows"

# A failing dump (wrong password) keeps nothing and exits non-zero.
before="$(ls "$dir" | wc -l)"
run_backup wrong-password > /dev/null 2>&1
[ "$?" != "0" ]; check $? "a failed dump exits non-zero"
[ "$(ls "$dir" | wc -l)" = "$before" ]; check $? "a failed dump leaves no file"

# Retention: an old backup goes, a recent one and an unrelated file stay.
touch -d '30 days ago' "$dir/vacancy_tracker-20000101-000000.dump"
touch -d '2 days ago' "$dir/vacancy_tracker-20000102-000000.dump"
echo keep > "$dir/notes.txt"; touch -d '90 days ago' "$dir/notes.txt"
run_backup pw > /dev/null 2>&1
[ ! -e "$dir/vacancy_tracker-20000101-000000.dump" ]; check $? "a backup older than the retention is deleted"
[ -e "$dir/vacancy_tracker-20000102-000000.dump" ]; check $? "a recent backup is kept"
[ -e "$dir/notes.txt" ]; check $? "a file the script did not make is never deleted"

[ "$fail" = "0" ] && echo "ALL CHECKS PASSED" || echo "SOME CHECKS FAILED"
exit "$fail"
