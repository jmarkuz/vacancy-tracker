#!/bin/sh
# Daily PostgreSQL backup (T-070). Runs in the backup service of docker-compose.yml, which uses the postgres:16
# image for pg_dump. Settings come from the environment:
#   PGHOST, PGUSER, PGPASSWORD, PGDATABASE   the database to dump
#   BACKUP_DIR              where files go (default /backups)
#   BACKUP_INTERVAL_SECONDS time between backups (default 86400, one day)
#   BACKUP_KEEP_DAYS        files older than this are deleted (default 14)
#   BACKUP_ONCE=1           make one backup and exit (used by the test)
# A backup is written to a temporary name, checked with pg_restore --list, and only then renamed, so a half
# written or damaged file never looks like a backup. A failure is logged and retried at the next interval.

BACKUP_DIR="${BACKUP_DIR:-/backups}"
INTERVAL="${BACKUP_INTERVAL_SECONDS:-86400}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
PREFIX="${PGDATABASE:-vacancy_tracker}"

log() {
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup: $*"
}

backup_once() {
    stamp="$(date -u +%Y%m%d-%H%M%S)"
    final="$BACKUP_DIR/$PREFIX-$stamp.dump"
    temp="$final.partial"
    if ! pg_dump --format=custom --no-owner --file="$temp"; then
        log "pg_dump failed; nothing was kept"
        rm -f "$temp"
        return 1
    fi
    if ! pg_restore --list "$temp" > /dev/null 2>&1; then
        log "the dump could not be read back; nothing was kept"
        rm -f "$temp"
        return 1
    fi
    mv "$temp" "$final"
    log "wrote $final ($(wc -c < "$final") bytes)"
    # Only files this script made are ever deleted.
    find "$BACKUP_DIR" -maxdepth 1 -type f -name "$PREFIX-*.dump" -mtime "+$KEEP_DAYS" -print -delete |
        while read -r old; do log "deleted old backup $old"; done
    find "$BACKUP_DIR" -maxdepth 1 -type f -name "$PREFIX-*.dump.partial" -mmin +60 -delete
    return 0
}

mkdir -p "$BACKUP_DIR" || exit 1

until pg_isready -q; do
    log "waiting for the database"
    sleep 2
done

if [ "${BACKUP_ONCE:-0}" = "1" ]; then
    backup_once
    exit $?
fi

while true; do
    backup_once
    sleep "$INTERVAL"
done
