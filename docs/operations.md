# Operations

Deployment on a VPS and the reverse proxy are in [deployment.md](deployment.md). This page covers backups.

## Backups

The `backup` service in `docker-compose.yml` runs `pg_dump` once a day and writes the result into the `backups` volume.

- A file is named `<database>-YYYYMMDD-HHMMSS.dump` (UTC) and is in PostgreSQL's custom format.
- The service starts with a backup, then repeats every `BACKUP_INTERVAL_SECONDS` (default 86400, one day).
- A dump is written under a temporary name and checked by reading it back (`pg_restore --list`) before it gets its final name, so a half-written or damaged file never looks like a backup. A failed backup is logged and tried again at the next interval; the service keeps running.
- Files older than `BACKUP_KEEP_DAYS` (default 14) are deleted. Only files this service made are ever deleted.
- Look at the log with `docker compose logs backup`.

The volume lives on the same machine as the database. A backup you keep only there does not protect you from losing the machine: copy files off the host now and then (see below). The app can also export a full JSON backup from the Export screen, which is independent of PostgreSQL.

### List and copy backups

```sh
docker compose run --rm --no-deps --entrypoint ls backup -l /backups
docker compose cp backup:/backups/vacancy_tracker-20261101-030000.dump .
```

### Restore

Restoring replaces the current data with the contents of the backup. Stop the app first so nothing writes while the restore runs.

```sh
docker compose stop app
docker compose run --rm --no-deps --entrypoint sh backup -c \
  'pg_restore -h postgres -U "$PGUSER" -d "$PGDATABASE" --clean --if-exists --no-owner /backups/vacancy_tracker-20261101-030000.dump'
docker compose start app
```

Use the file name from the list. `--clean --if-exists` drops the existing tables before it recreates them. The app's schema migrations are part of the dump, so a restored database starts as it was.

To restore a file from another machine, copy it into the volume first:

```sh
docker compose cp ./vacancy_tracker-20261101-030000.dump backup:/backups/
```

### Check that it works

`ops/backup/test-backup.sh` starts a throwaway PostgreSQL in Docker and checks that a backup appears, that it restores into a fresh database with the same rows, that a failed dump leaves no file, and that only old backup files are deleted. It needs Docker and takes about half a minute.

### Settings

| Variable | Default | Meaning |
|---|---|---|
| `BACKUP_INTERVAL_SECONDS` | 86400 | Time between backups |
| `BACKUP_KEEP_DAYS` | 14 | Backups older than this are deleted |
