# nginx manager

[한국어](README.md) · **English** · [日本語](README.ja.md) · [简体中文](README.zh-CN.md)

A tool for managing nginx from your browser. nginx (open source, built with all modules) and the admin console (Spring Boot) run in separate containers, so nginx keeps serving traffic while you update or restart the admin console.

**Usage instructions live in the user guide inside the admin console (`/guide`).** Open it with **User guide** in the left menu or the footer. The source is in [src/main/resources/guide/ko](src/main/resources/guide/ko/README.md) (Korean), so you can also read it in the repository.

Specs and design documents are in `specs/001-nginx-web-manager/` of [nginx-manager/docs](https://github.com/nginx-manager/docs) (Spec Kit).

## Features

- **Sites**: Create reverse proxy and static file sites with a form, and edit everything else in the raw editor (with nginx syntax highlighting). For proxies, the form covers multiple backends (load balancing), WebSockets and timeouts. Delete sites you no longer need (deletion is also a pending change you apply). PHP sites (PHP-FPM) and gRPC proxies (`grpc://`) can be created with the form too, and path routing (send `/api/` to a backend and `/files/` to a directory) is set in the form as well.
- **Site options**: Turn on IP allow/deny, password protection (htpasswd), an external auth server (auth_request, for SSO such as oauth2-proxy or Authelia), request rate and concurrent connection limits, gzip compression, security headers, HSTS, HTTP/3 (QUIC), real client IP (behind a proxy or CDN), proxy cache, directory listing and maximum request size from the form. You can also create redirect sites that only forward to another address.
- **Streams (TCP/UDP)**: Create stream proxies with a form to forward non-HTTP ports such as databases, SSH or DNS to backends (load balancing, timeouts).
- **Import config files**: Register nginx config files you already use as sites. Register a path pattern such as `/home/*/nginx/*.conf` to find all matching files at once, or upload a file from your PC. Files that can be converted to a form become form sites; the rest are registered verbatim as raw sites. When the original changes, the site screen tells you so you can import it again. Mount host directories into the manager container and list them in `NGINX_MANAGER_IMPORT_ROOTS`.
- **Raw config files**: Create and delete config files (njs scripts, shared snippets, etc.) in the raw editor. Like any other change, they go through save (pending) → `nginx -t` → reload.
- **Safe apply**: Review pending changes as a diff on the apply screen before applying them. Applying runs `nginx -t` → reload → health check, and automatically rolls back to the previous config if any step fails. Problems that `nginx -t` misses and only show up on reload, such as port conflicts, are also treated as failures and rolled back.
- **History and rollback**: Every apply creates a version. Compare two versions or roll back to an earlier one (rollbacks go through the same validation). Files changed directly on the server (drift) are detected and reported. A site's **Change history** shows only the versions that changed that site, and members can see the history of their own sites. Identical file contents are stored once, so the database stays small as versions pile up.
- **HTTPS**: Turn on HTTPS for a site and it issues a Let's Encrypt certificate, applies the config automatically, and renews it before it expires. If renewal fails, it keeps serving with the current certificate and alerts you by email and webhook. Upload wildcard and internal certificates. Imported raw config sites can be issued a certificate too, and get a 443 server block and an 80→443 redirect added.
- **Dashboard and logs**: See nginx status, version and modules, recent applies and certificate warnings, and search or tail per-site access/error logs (including imported raw sites and rotated logs).
- **Users and audit log**: Admin and member roles. Members can only view, edit and apply the sites assigned to them; admins assign members to each site. Every change is recorded in the audit log. Users can turn on two-step verification with an authenticator app (TOTP) and recovery codes.

## Running

On servers, use the install bundle attached to each release. One command installs and starts it in `/opt/nginx-manager` (requires Docker and Compose v2).

```bash
curl -fsSL https://github.com/nginx-manager/app/releases/latest/download/install.sh | sudo sh -s -- \
    --admin-domain nm.example.com --email ops@example.com
```

- By default the admin console is only reachable from the server itself (`http://127.0.0.1:9000`). With `--admin-domain` (or `NGINX_MANAGER_ADMIN_DOMAIN` in `.env`), the bundled nginx gets a Let's Encrypt certificate for that domain and serves the admin console over HTTPS only (port 80 redirects to 443).
- Change ports, domains and versions in `.env` in the install directory. Data lives under `data/`.
- Running the install command again on a server that already has it installed exits without touching `.env` or `data/`. Upgrade to a new version with `nm upgrade`, which leaves `.env` as is and only adds new keys with their defaults.
- Operate it with the `nm` command: `nm status`, `nm upgrade` (upgrades only the manager, so nginx is not interrupted), `nm upgrade --nginx`, `nm backup`, `nm restore`, `nm migrate` (moves an install from before v1.1), `nm import-root` (mounts host directories to import from at the same path and checks read access), `nm check-domain` (checks that the admin domain is in DNS and reaches this server).
- To import config that already exists on the server (e.g. `/home/*/nginx/*.conf`), add `--import-root /home` when installing. After installation, mount it with `nm import-root add /home` and check that the manager (UID 10101) can read it with `nm import-root check`. If it can't, `nm import-root grant` grants read-only access with ACLs. The import screen also doesn't stop when it hits a directory it can't read; it shows the locations it couldn't open and the grant command.
- See [1. Installation and running](src/main/resources/guide/ko/01-install.md) (Korean) in the user guide for details. The bundle's source is in [deploy/](deploy/).

The `.env` in the install directory (contains the values you passed as install options). After changing it, run `nm restart`, or `nm restart nginx` if you changed nginx ports.

| Variable | Default | When to change |
| --- | --- | --- |
| `MANAGER_VERSION`, `NGINX_IMAGE` | Installed version | Don't edit these by hand. `nm upgrade` changes them |
| `NGINX_MANAGER_ADMIN_DOMAIN` | (empty) | To open the admin console on the internet at `https://domain`. DNS must point to this server and port 80 must be reachable |
| `NGINX_MANAGER_ADMIN_EMAIL` | (empty) | Let's Encrypt contact email when using an admin domain (expiry notices) |
| `NGINX_MANAGER_ADMIN_ALLOW` | (empty, allow all) | To restrict which addresses can reach the admin domain (CIDR, comma-separated) |
| `NGINX_MANAGER_PORT` | `9000` | When another program uses 9000 |
| `NGINX_MANAGER_BIND_ADDRESS` | `127.0.0.1` | `0.0.0.0` or an internal IP to open it directly on an internal network without an admin domain |
| `NGINX_HTTP_PORT`, `NGINX_HTTPS_PORT` | `80`, `443` | When another proxy in front takes 80/443 |
| `NGINX_BIND_ADDRESS` | `0.0.0.0` | To expose nginx only on a specific IP |
| `NGINX_MANAGER_BACKUP_KEEP` | `14` | Number of backups `nm backup` keeps |
| `NGINX_MANAGER_BACKUP_AT` | `03:30` | Time of the daily automatic backup (`nm backup --label auto`). `off` turns it off. Run `sudo nm backup-schedule` after changing it |
| `TZ` | `Asia/Seoul` | When the server is in a different time zone |
| `NGINX_MANAGER_IMPORT_ROOTS` | (empty) | To import config files outside `/etc/nginx`. Don't edit by hand; use `nm import-root add /home` |
| `SPRING_MAIL_*` | (empty) | SMTP server for email alerts |

For development and testing, use [docker/docker-compose.yml](docker/docker-compose.yml) in the repository (named volumes, admin console open on all addresses).

```bash
NGINX_MANAGER_VERSION=1.2.0 docker compose -f docker/docker-compose.yml up -d
```

| Container | Image | Role |
|----------|--------|---------|
| `nginx` | `ghcr.io/nginx-manager/nginx` | nginx (80/443) and the agent. The agent only runs `nginx -t` and reload at the admin console's request. |
| `manager` | `ghcr.io/nginx-manager/app` | Admin console (9000). Writes config files directly to the volume and leaves validation and reload to the agent. |

| Volume | Mount | Contents |
|------|--------|------|
| `nm-conf` | Both, `/etc/nginx` | nginx config, certificates (`certs/`) |
| `nm-logs` | Both, `/var/log/nginx` | Access and error logs (per-site logs in `sites/`) |
| `nm-shared` | Both, `/var/lib/nginx-manager` | Let's Encrypt challenge files, agent token |
| `nm-data` | manager `/data` | Manager DB (SQLite), ACME account key, records of in-progress applies |

Mount shared volumes at **the same path** in both containers (file paths in `nginx -t` errors must match the files shown on screen). Processes in both images run as the same UID (10101), so they can read and write each other's files.

**Stream ports and HTTP/3**: To use a port created on the streams screen (e.g. TCP 5432) from outside, add it to `ports` of the `nginx` service in the compose file (`"5432:5432"`, or `"53:53/udp"` for UDP) and restart with `docker compose up -d nginx`. For HTTP/3, the compose file already opens `443/udp`, and the default server on 443 in the default `nginx.conf` declares `listen 443 quic reuseport` once. If your install still uses an old `nginx.conf`, the site screen will tell you; add a default server on 443 like the one in the new image's [nginx.conf](docker/nginx/nginx.conf).

On first start, it imports the existing config in `/etc/nginx` and saves it as the first version. Data in the volumes is kept when you change images.

### Configuration (environment variables)

Manager (`manager`) container:

| Variable | Default | Description |
|------|--------|------|
| `NGINX_RUNTIME_AGENT_URL` | `http://nginx:7070` | Agent address. Change it if you rename the nginx service in compose. |
| `NGINX_RUNTIME_AGENT_TOKEN` | (empty) | If empty, reads the token file from the shared volume. If you set it, make it match `NGINX_AGENT_TOKEN` in the nginx container. |
| `NGINX_MANAGER_ACME_DIRECTORY_URL` | (empty) | If set, this ACME server is used instead of the Let's Encrypt production/staging choice on screen. |
| `NGINX_MANAGER_CERT_CHECK_INTERVAL` | `PT6H` | How often to look for certificates to renew |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | | SMTP server for email alerts |
| `SERVER_SERVLET_SESSION_COOKIE_SECURE` | `false` | `true` when the admin console is behind HTTPS |
| `NGINX_MANAGER_IMPORT_ROOTS` | (empty) | Directories other than `/etc/nginx` that config file import can search (comma-separated). Mount the host directory into the manager container at the same path, then list it here. |

nginx container:

| Variable | Default | Description |
|------|--------|------|
| `NGINX_AGENT_TOKEN` | (empty) | If empty, a random token is generated at `/var/lib/nginx-manager/agent/token` on first start and used. |
| `NGINX_AGENT_LISTEN` | `:7070` | Address the agent listens on |

Let's Encrypt validates with HTTP-01, so the domain must reach port 80 of the nginx container. We recommend testing with staging in the on-screen settings first.

## Versions and release notes

The manager version you're running is shown in the admin console footer. Versions come from git tags (`vX.Y.Z`); images built without a tag show `dev`. What changed in each version is documented with screenshots in the [release notes](docs/releases/README.md) (Korean), along with the versioning rules and release process.

## Upgrading and rolling back

The manager and nginx images are upgraded separately. `MANAGER_VERSION` and `NGINX_IMAGE` in `.env` are the tags of the two images.

```bash
sudo /opt/nginx-manager/nm upgrade           # manager only. nginx keeps serving traffic
sudo /opt/nginx-manager/nm upgrade --nginx   # nginx image too. Checks the config with the new nginx before switching (restarts for a few seconds)
```

- `nm upgrade` takes a backup before upgrading, and if the new manager doesn't become healthy, it rolls back to the previous version and the pre-upgrade DB (since DB changes only go forward).
- It's fine for the two containers to be on different release versions. A warning appears only when the agent API versions don't match, telling you to upgrade nginx as well. When the agent API changes, the previous version is still accepted so the manager can be upgraded first (`AgentNginxRuntime.MIN_AGENT_PROTOCOL`).
- nginx image tags are `<manager version>-nginx<nginx version>` (e.g. `1.2.0-nginx1.31.8`), `<manager version>` and `latest`. When a new nginx release comes out, only the nginx image is automatically rebuilt, tested and published, and that release's install bundle is updated to the new nginx tag. Older tags are kept.
- On startup, the new nginx image validates the config in the volume before starting nginx. If the config is rejected, nginx is not started and only the agent runs; the dashboard shows the offending file and line along with the previous and current versions.
- Version changes are recorded on the dashboard and in the audit log.

To roll back the config itself, use "Roll back" on the version you want in the history screen.

## Architecture

- Inside the nginx container, [s6-overlay](https://github.com/just-containers/s6-overlay) supervises nginx and the agent separately and restarts them if they die.
- The manager controls nginx only through the agent API (`agent/`). The agent only runs a fixed set of commands (`nginx -t`, `nginx -s reload`, `nginx -s reopen`, `nginx -V`, `s6-svc -u`) and rejects requests without a token. The Docker socket is not passed to the manager.
- Processes in both containers run as the non-root user `nginxm` (UID 10101). Only the nginx binary is given permission to bind ports 80/443.
- Each time a certificate is issued or renewed, it is written fresh to `/etc/nginx/certs/<name>/<timestamp>/` and only the path in the config is changed and applied. That way, certificate swaps are also recorded in history and can be rolled back.

## Development

Requires Java 21, Maven 3.9, Go 1.24 and Docker.

```bash
mvn verify                       # unit and integration tests (the real ACME issuance test starts Pebble with Docker)
mvn spotless:apply               # apply formatting (checked by verify). At compile time, Error Prone catches common mistakes
(cd agent && go test ./...)      # agent tests
docker build -f docker/nginx/Dockerfile -t nginx-manager/nginx:dev .
docker build -f docker/manager/Dockerfile -t nginx-manager/app:dev .
# image tests: start both containers in the same setup as compose and verify apply, HTTPS and upgrade scenarios against a real nginx
mvn -Pimage-test test -Dimage.name=nginx-manager/app:dev -Dnginx.image.name=nginx-manager/nginx:dev
```

The raw editor bundle (`src/main/resources/static/js/editor.bundle.js`) is committed to the repository. Rebuild it only when you change it.

```bash
cd frontend && npm ci && npm run build
```

Data access rules (constitution):

- Write queries with dynamic conditions, joins or projections in Querydsl. Don't use string JPQL, native queries or `@Query` (`ArchitectureRulesTest` blocks them).
- All associations are `fetch = LAZY`, with `open-in-view=false`.
- Every list query uses `QueryCounter.assertConstantQueries` to check that the query count stays the same regardless of the number of rows.

User guide: a PR that changes user-facing features, screens or settings also updates the relevant chapter of the [user guide](src/main/resources/guide/ko/README.md) in the same PR. How to do this and how to retake screenshots is described in [Maintaining the guide](docs/guide.md) (Korean).

Releases: write the [release notes](docs/releases/README.md) and push a `v<version>` tag to publish both images with that version and the latest nginx. After that, new nginx releases are checked daily and only the nginx image is published automatically. When you change the agent API, bump both `Protocol` in `agent/server.go` and `AgentNginxRuntime.PROTOCOL`.
