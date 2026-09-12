# SongsServer

A backend server for songs site. Frontend repository: [github.com/titovtima/songsSite](https://github.com/titovtima/songsSite).

Two independent instances are running on [songs.istokspb.org](https://songs.istokspb.org) and [test.songs.titovtima.ru](https://test.songs.titovtima.ru).

File [sqlScripts/songs.sql](https://github.com/titovtima/songsServer/blob/main/sqlScripts/songs.sql) contains database structure used by server.  
PostgreSQL is being used as Database Management System.

The server is running on `localhost:2403` and use proxy server (caddy) to establish TLS and separate from other services.

### Environment

Env variables that are needed for the project works properly:

* `AWS_ACCESS_KEY_ID` & `AWS_SECRET_ACCESS_KEY` - key for S3 storage access
* `S3_BUCKET`, `S3_ENDPOINT_URL`, `S3_REGION` - S3-compatible storage location for audio files
* `CACHE_PATH` - path to cache files got from S3 storage
* `HOST` - external host where the app instance is running. Used in emails
* `JWT_SECRET` - secret string for jwt authorization (though main auth strategy is by bearer token)
* `POSTGRES_PASSWORD` - database password for user `songsserver`
* `EMAIL_SERVICE_URL`, `EMAIL_SENDER`, `EMAIL_SERVICE_TOKEN` - outgoing email service (password recovery, etc.); the service is a DRF endpoint requiring `Authorization: Token <EMAIL_SERVICE_TOKEN>`

Optional, with defaults matching the original single-database setup:

* `POSTGRES_HOST` (default `localhost`), `POSTGRES_PORT` (default `5432`), `POSTGRES_DB` (default `songsserver`), `POSTGRES_USER` (default `songsserver`)
* `POSTGRES_SCHEMA` - set this when the project's tables live in their own schema inside a shared database (e.g. a managed cluster with database `maindb` and schema `songsserver`) instead of a dedicated database. It's applied as `?currentSchema=` on the JDBC URL, so the app's existing unqualified SQL resolves against that schema.
* `SERVER_HOST` (default `127.0.0.1`) - interface the server binds to; use `0.0.0.0` when running in Docker

### Database migration

Apply `sqlScripts/songs.sql` to create the schema (tables, views, functions). Run from a machine with `psql` and network access to the database, using the same connection settings as in `.env`:

```bash
set -a && source .env && set +a

PGSSLMODE=verify-full \
PGOPTIONS="-c search_path=${POSTGRES_SCHEMA:-public}" \
PGPASSWORD="$POSTGRES_PASSWORD" \
psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
     -v ON_ERROR_STOP=1 -f sqlScripts/songs.sql
```

Notes:
* `PGSSLMODE=verify-full` is required by managed clusters (e.g. Yandex Cloud) that enforce SSL; it needs the provider's root CA certificate at `~/.postgresql/root.crt` (for Yandex Cloud: `curl -o ~/.postgresql/root.crt https://storage.yandexcloud.net/cloud-certs/CA.pem`). Drop it for a local/unmanaged instance that doesn't require SSL.
* `PGOPTIONS` sets `search_path` so objects are created in `POSTGRES_SCHEMA` rather than `public`; omit it if the project uses a dedicated database instead of a shared one.

### Nginx & TLS setup

Two separate servers are involved:

* **Proxy server** — public-facing, owns the DNS record for `worship.wolrus.ru`, terminates TLS. This is the only server that needs Nginx.
* **App server** — runs this backend and the [frontend](https://github.com/titovtima/songsSite) as Docker containers, reachable from the proxy server only over its public IP (see `docker-compose.yml`, which publishes `0.0.0.0:2403:2403`, and the frontend's own compose file, which must publish its `3000` the same way).

**App server:** no Nginx needed. Just make sure the app server's firewall (`ufw`/`iptables`/cloud security group) allows inbound `2403` and `3000` **only from the proxy server's IP** — since TLS terminates on the proxy, this hop is plain HTTP and must not be open to the internet.

**Proxy server:** Nginx serves the single public domain and routes by path — config in [`nginx/worship.wolrus.ru.conf`](nginx/worship.wolrus.ru.conf):

* `/api/` → backend on the app server (`APP_SERVER_IP:2403`)
* everything else → frontend on the app server (`APP_SERVER_IP:3000`)

Before enabling, replace `worship.wolrus.ru` with the real domain and the placeholder `203.0.113.10` with the app server's real public IP. A single public domain means the frontend's browser-side requests (which go to `window.location.origin`) and its API calls land on the same origin with no CORS configuration needed.

Set `HOST` in the backend's `.env` (on the app server) to that domain (`https://worship.wolrus.ru`) — it's used to build links inside emails that a user opens in the browser. The frontend's `API_HOST`, read only server-side for SSR (which never touches the browser or either Nginx), can skip both Nginx hops by staying on the app server and reaching the backend directly over the `songs-shared` Docker network defined in this repo's `docker-compose.yml` — join it from the frontend's own compose file and set `API_HOST=http://app:2403`.

1. On the proxy server, symlink the config into `sites-enabled` and reload Nginx:

   ```bash
   ln -s /path/to/songsServer/nginx/worship.wolrus.ru.conf /etc/nginx/sites-enabled/worship.wolrus.ru.conf
   nginx -t && systemctl reload nginx
   ```

2. Issue a TLS certificate with Certbot on the proxy server (requires DNS for the domain already pointing at it):

   ```bash
   sudo apt install certbot python3-certbot-nginx   # if not already installed
   sudo certbot --nginx -d worship.wolrus.ru
   ```

   Certbot edits the symlinked config in place to add the `listen 443 ssl` block and sets up auto-renewal (`certbot renew` via a systemd timer/cron, installed automatically).
