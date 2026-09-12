# SongsServer

A backend server for songs site. Frontend repository: [github.com/titovtima/songsSite](https://github.com/titovtima/songsSite).

Two independent instances are running on [songs.istokspb.org](https://songs.istokspb.org) and [test.songs.titovtima.ru](https://test.songs.titovtima.ru).

File [sqlScripts/songs.sql](https://github.com/titovtima/songsServer/blob/main/sqlScripts/songs.sql) contains database structure used by server.  
PostgreSQL is being used as Database Management System.

The server is running on `localhost:2403` and use proxy server (caddy) to establish TLS and separate from other services.

### Environment

Env variables that are needed for the project works properly:

* `AWS_ACCESS_KEY_ID` & `AWS_SECRET_ACCESS_KEY` - key for S3 storage access
* `CACHE_PATH` - path to cache files got from S3 storage
* `HOST` - external host where the app instance is running. Used in emails
* `JWT_SECRET` - secret string for jwt authorization (though main auth strategy is by bearer token)
* `POSTGRES_PASSWORD` - database password for user `songsserver`

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
