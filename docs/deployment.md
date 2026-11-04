# Deployment

Two ways to run Vacancy Tracker, both with Docker Compose: on your own computer (no login, reachable only from that computer), and on a small VPS (HTTPS and an access token). The data lives in PostgreSQL in a Docker volume. Secrets live only in `.env`, which is never committed.

## What you need

- Docker Engine with the Compose plugin (Compose v2). Nothing else: the images build the UI and the app themselves.
- About 1 GB of memory and 5 GB of disk is enough for one user.
- For the VPS: a host name that points at the server (an `A` record, and `AAAA` if you use IPv6), and ports 80 and 443 open to the internet. Caddy needs port 80 as well, to get the certificate.

## Run on your own computer

```sh
git clone <this repository> vacancy-tracker
cd vacancy-tracker
cp .env.example .env
```

Edit `.env` and set at least `POSTGRES_PASSWORD` to a long random value (`openssl rand -base64 24`). Then:

```sh
docker compose up -d --build
```

The first build takes a few minutes. Open <http://127.0.0.1:8080>. The port is bound to `127.0.0.1` only, so other machines cannot reach it. There is no login in this mode. On the first visit the app asks you to set up your first search profile.

Check it is healthy:

```sh
docker compose ps
curl -s http://127.0.0.1:8080/actuator/health        # {"status":"UP"}
```

Four containers' worth of work happens in three: `postgres` (data), `app` (UI, API, collection) and `backup` (daily database dump, see [operations.md](operations.md)).

### Optional settings for the full application cycle (M6)

Set these in `.env` (or as environment variables when you run the jar). All are off or empty unless you set them.

| Setting | What it does |
|---|---|
| `APP_AI_PROVIDER` | `off` (default), `cli` or `api`. Even when set, AI stays off until you switch it on in **Settings** (ADR-041). |
| `APP_AI_CLI_COMMAND`, `APP_AI_CLI_MODEL` | For `cli`: the Claude Code command (default `claude`) and an optional model alias (`sonnet`, `opus`). The CLI must be installed and signed in **on the machine that runs the app**, so `cli` works when you run the jar directly (`java -jar backend/target/vacancy-tracker-0.1.0-SNAPSHOT.jar`), not inside the Docker image. |
| `ANTHROPIC_API_KEY`, `APP_AI_API_MODEL` | For `api`: your Claude API key, and the model (default `claude-opus-5-5`). Works in Docker too. |
| `APP_MAIL_IMAP_ENABLED`, `APP_MAIL_IMAP_HOST`, `APP_MAIL_IMAP_USERNAME`, `APP_MAIL_IMAP_PASSWORD` | Your mailbox over IMAP (an app password, not your main password). Read-only. |
| `APP_MAIL_IMAP_FOLDER` | The folder with job alert emails (see `APP_MAIL_IMAP_FOLDER` in `.env.example` for the default). Related limits: `APP_MAIL_IMAP_MAX_PER_RUN` (messages read per run) and `APP_MAIL_IMAP_MAX_AGE_DAYS` (how old a message may be). |
| `APP_MAIL_REPLIES_FOLDERS` | Folders where employers' answers and LinkedIn mail arrive, comma-separated (default `INBOX`). Only job-related mail is kept. |
| `APP_SOURCES_DJINNI_ENABLED`, `APP_SOURCES_DJINNI_URL` | The Djinni feed (on by default); add the list filters you use, for example `https://djinni.co/jobs/rss/?primary_keyword=Java&employment=remote`. |
| `APP_SOURCES_REMOTEOK_ENABLED`, `APP_SOURCES_WORKINGNOMADS_ENABLED` | The Remote OK and Working Nomads APIs (on by default). |
| `APP_SOURCES_TELEGRAM_FORWARD_ENABLED` | Collect the posts you forward to your own Telegram bot. Uses the digest's `APP_NOTIFICATIONS_TELEGRAM_BOT_TOKEN` and `..._CHAT_ID` unless `APP_SOURCES_TELEGRAM_FORWARD_BOT_TOKEN` and `..._OWNER_CHAT_ID` are set. Create the bot with @BotFather, send it any message once, and forward vacancy posts to it. Runs at least daily: Telegram keeps a bot's messages for 24 hours. |
| `APP_ALLOWED_HOSTS` | Extra host names the app answers to besides `localhost` and `127.0.0.1` (the VPS compose file sets it to `DOMAIN`). |

## Run on a VPS with HTTPS

### 1. Prepare the server

- Install Docker Engine and the Compose plugin from Docker's documentation for your system.
- Open only ports 22 (SSH), 80 and 443 in the firewall. Do not open 8080: the app must be reached only through the proxy, because the proxy is what the login limit trusts to tell it the client's address.
- Point your host name at the server and wait until `dig +short your.host.name` shows its address.

### 2. Configure

```sh
git clone <this repository> vacancy-tracker
cd vacancy-tracker
cp .env.example .env
chmod 600 .env
```

Set in `.env`:

| Variable | Value |
|---|---|
| `POSTGRES_PASSWORD` | a long random password (`openssl rand -base64 24`) |
| `APP_ACCESS_TOKEN` | the token you will type to sign in, at least 16 characters (`openssl rand -base64 32`); keep it in a password manager |
| `DOMAIN` | your host name, for example `jobs.example.com` |

`SPRING_PROFILES_ACTIVE` is set to `vps` by `docker-compose.vps.yml`; you do not have to change it. The other settings (sources, IMAP, Telegram) are optional and described in `.env.example`.

### 3. Start

```sh
docker compose -f docker-compose.yml -f docker-compose.vps.yml up -d --build
```

Caddy gets a certificate for `DOMAIN` on its first request (it can take a minute) and renews it by itself. Check:

```sh
docker compose -f docker-compose.yml -f docker-compose.vps.yml ps
curl -sI https://your.host.name/ | head -1                                   # HTTP/2 200
curl -s -o /dev/null -w '%{http_code}\n' https://your.host.name/api/vacancies   # 401: the API needs the token
curl -s -H "Authorization: Bearer $APP_ACCESS_TOKEN" https://your.host.name/api/vacancies | head -c 80
```

Open `https://your.host.name/` in a browser and enter the access token. After five wrong tokens from one address the address is blocked for ten minutes.

To save typing, make an alias: `alias vt='docker compose -f docker-compose.yml -f docker-compose.vps.yml'`.

### What the setup does

- `caddy` is the only container with published ports (80 and 443). It terminates HTTPS and passes requests to `app:8080` with `X-Forwarded-For` and `X-Forwarded-Proto`.
- The app, in the `vps` profile, refuses to start without a token of at least 16 characters, requires it on every `/api` request, and logs one JSON object per line with secrets masked.
- The sign-in cookie is HTTP-only, `SameSite=Strict` and `Secure`. `/actuator/health` stays open so you can monitor the server; it shows only UP or DOWN.

## Daily use

```sh
vt logs -f app                 # follow the app log (JSON lines in vps mode)
vt logs --since 1h backup      # backups
vt ps                          # state of the containers
```

### Update to a new version

```sh
git pull
vt up -d --build
```

Database changes are applied by the app on start. Take a backup first if you want a restore point: `vt exec backup sh -c 'BACKUP_ONCE=1 sh /backup.sh'`.

### Change the access token

Edit `APP_ACCESS_TOKEN` in `.env` and run `vt up -d`. Everyone is signed out and must enter the new token.

### Stop, and remove

```sh
vt down            # stops the containers, keeps the data
vt down -v         # also deletes the volumes: the database, the backups and the certificates
```

## Backups

The `backup` container dumps the database once a day into the `backups` volume and keeps 14 days. That volume is on the same server as the database, so copy dumps somewhere else from time to time, and learn the restore steps before you need them: [operations.md](operations.md).

## Troubleshooting

| Symptom | Check |
|---|---|
| `docker compose` says `set DOMAIN` or `set APP_ACCESS_TOKEN` | The variable is missing in `.env`. |
| The browser warns about the certificate | The host name does not yet point at this server, or ports 80 and 443 are blocked. Look at `vt logs caddy`. |
| `app` keeps restarting | `vt logs app`. In the vps profile it stops if `APP_ACCESS_TOKEN` is shorter than 16 characters. |
| Sign-in says too many wrong tokens | Wait for the time shown; the block ends by itself. Restarting the app also clears it. |
| The Refresh progress does not move | A proxy in front of Caddy may buffer event streams. Caddy is configured not to. |
| A source shows an error | Open Sources & Runs. The error text never contains keys. |
