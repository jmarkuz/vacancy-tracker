#!/bin/sh
# Runs the smoke test on this machine: a throwaway PostgreSQL in Docker, the packaged app, Playwright, cleanup.
# Needs Docker, Maven, Node and Google Chrome (PW_CHANNEL=chrome; set PW_CHANNEL= to use Playwright's own browser).
set -u
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
port="${E2E_PORT:-18080}"
db="vt-e2e-db"
pidfile="$(mktemp)"
cleanup() {
    [ -s "$pidfile" ] && kill "$(cat "$pidfile")" 2> /dev/null
    docker rm -f "$db" > /dev/null 2>&1
    rm -f "$pidfile"
}
trap cleanup EXIT

if [ ! -f "$root/backend/target/vacancy-tracker-0.1.0-SNAPSHOT.jar" ]; then
    echo "Build first: (cd frontend && npm ci && npm run build) && (cd backend && mvn -DskipTests package)"
    exit 2
fi
# A jar older than the UI sources would test yesterday's screens.
if [ -n "$(find "$root/frontend/src" -newer "$root/backend/target/vacancy-tracker-0.1.0-SNAPSHOT.jar" -type f -not -name "*.test.*" | head -n 1)" ]; then
    echo "The UI changed after the jar was built. Rebuild: (cd frontend && npm run build) && (cd backend && mvn -DskipTests package)"
    exit 2
fi
docker rm -f "$db" > /dev/null 2>&1
if ! docker run -d --name "$db" -e POSTGRES_PASSWORD=pw -e POSTGRES_USER=vacancy -e POSTGRES_DB=vacancy_tracker -p 15433:5432 postgres:16 > /dev/null; then
    echo "could not start PostgreSQL in Docker (is Docker running, is port 15433 free?)"; exit 1
fi
i=0
until docker exec "$db" psql -U vacancy -d vacancy_tracker -c "select 1" > /dev/null 2>&1; do
    i=$((i + 1)); [ "$i" -gt 60 ] && { echo "PostgreSQL did not become ready"; exit 1; }
    sleep 1
done

# APP_AI_PROVIDER=fake: the full-cycle test drafts documents with the fake AI provider; nothing is sent to Claude.
SERVER_PORT="$port" APP_RATES_REFRESH_ON_STARTUP=false APP_AI_PROVIDER=fake \
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:15433/vacancy_tracker SPRING_DATASOURCE_USERNAME=vacancy SPRING_DATASOURCE_PASSWORD=pw \
    java -jar "$root/backend/target/vacancy-tracker-0.1.0-SNAPSHOT.jar" > "$here/app.log" 2>&1 &
echo $! > "$pidfile"
i=0
until curl -sf "http://127.0.0.1:$port/actuator/health" > /dev/null; do
    i=$((i + 1)); [ "$i" -gt 60 ] && { echo "the app did not start; see e2e/app.log"; exit 1; }
    sleep 2
done

cd "$here" && BASE_URL="http://127.0.0.1:$port" PW_CHANNEL="${PW_CHANNEL-chrome}" npx playwright test
