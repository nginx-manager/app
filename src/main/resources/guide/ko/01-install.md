# 1. 설치와 실행

nginx manager는 컨테이너 두 개로 돕니다. nginx가 도는 컨테이너와 관리 화면이 도는 컨테이너가 나뉘어 있어서, 관리 화면을 업데이트하거나 다시 띄워도 nginx는 트래픽을 계속 받습니다.

| 컨테이너 | 이미지 | 하는 일 |
| --- | --- | --- |
| `nginx` | `ghcr.io/nginx-manager/nginx` | nginx(80/443)와 에이전트. 에이전트는 관리 화면의 요청으로 `nginx -t`·reload만 실행합니다. |
| `manager` | `ghcr.io/nginx-manager/app` | 관리 화면(9000). 설정 파일을 쓰고, 검증·reload는 에이전트에 맡깁니다. |

## 준비물

- 리눅스 서버(x86_64, arm64)에 Docker Engine과 Docker Compose v2(`docker compose` 명령)
- 서버의 80, 443(TCP·UDP) 포트. 관리 화면 포트(기본 9000)는 서버 안에서만 엽니다.
- 관리 화면을 인터넷에서 HTTPS로 쓰려면 관리용 도메인(예: `nm.example.com`)이 이 서버를 가리키고, 인터넷에서 80 포트로 들어올 수 있어야 합니다(Let's Encrypt).

## 서버에 설치하기

명령 한 줄로 최신 릴리스를 `/opt/nginx-manager`에 깔고 띄웁니다(v1.2.0부터).

```bash
curl -fsSL https://github.com/nginx-manager/app/releases/latest/download/install.sh | sudo sh -s -- \
    --admin-domain nm.example.com --email ops@example.com
```

- 설치 스크립트는 Docker·Compose v2, 포트 사용 여부, 남은 공간을 먼저 확인합니다. 빠진 것이 있으면 아무것도 만들지 않고 이유를 보여 줍니다.
- 릴리스에 첨부된 설치 묶음(`compose.yml`, `.env`, `nm` 명령)만 받습니다. 소스(git clone)는 필요 없습니다.
- 이미 설치된 디렉터리(`.env`가 있음)에서 다시 실행하면 아무것도 바꾸지 않고 끝납니다. 새 버전으로 올릴 때는 `nm upgrade`를 씁니다([업그레이드](#업그레이드)).
- v1.0·v1.1로 깐 서버(이름 붙은 볼륨)에서는 설치를 멈추고 [옮기는 방법](#v11-이전-설치-옮기기)을 알려 줍니다.
- `--admin-domain`, `--email`을 빼면 관리 화면은 서버 안(`http://127.0.0.1:9000`)에서만 열립니다. 나중에 `.env`에 적어도 됩니다([관리 도메인](#관리-도메인으로-https-열기)).

| 옵션 | 뜻 |
| --- | --- |
| `--dir DIR` | 설치 디렉터리(기본 `/opt/nginx-manager`) |
| `--version X.Y.Z` | 설치할 버전(기본 최신) |
| `--admin-domain`, `--email` | 관리 도메인과 Let's Encrypt 연락 이메일 |
| `--http-port`, `--https-port`, `--manager-port` | 서버에 여는 포트(기본 80, 443, 9000) |
| `--import-root DIR` | [설정 파일 가져오기](05-import.md#호스트의-다른-디렉터리-붙이기)에서 찾을 호스트 디렉터리(예: `/home`). 여럿이면 여러 번 |
| `--bundle FILE` | 인터넷이 막힌 서버에서 미리 받은 묶음(`nginx-manager.tar.gz`)으로 설치 |

설치가 끝나면 접속 주소가 나옵니다. 처음 접속하면 관리자 계정을 만드는 화면이 나옵니다([2장](02-getting-started.md)). 관리 도메인이 없을 때 다른 컴퓨터에서 들어가려면 SSH 터널을 씁니다.

```bash
ssh -L 9000:127.0.0.1:9000 <서버>
# 브라우저에서 http://127.0.0.1:9000
```

처음 시작할 때 `/etc/nginx`에 이미 있는 설정을 가져와 첫 버전(**처음 가져오기**)으로 남깁니다.

### .env 설정

설치하면 설치 디렉터리에 `.env`가 생기고, 위 옵션으로 준 값이 들어갑니다. 대부분은 기본값 그대로 두면 되고, 바꾼 뒤에는 `nm restart`(nginx 포트를 바꿨으면 `nm restart nginx`)로 다시 띄웁니다.

| 변수 | 기본 | 언제 바꾸나 |
| --- | --- | --- |
| `MANAGER_VERSION`, `NGINX_IMAGE` | 설치한 버전 | 직접 고치지 않습니다. `nm upgrade`가 바꿉니다 |
| `NGINX_MANAGER_ADMIN_DOMAIN` | (비움) | 관리 화면을 인터넷에서 `https://도메인`으로 열 때. DNS가 이 서버를 가리키고 80으로 들어올 수 있어야 합니다 |
| `NGINX_MANAGER_ADMIN_EMAIL` | (비움) | 관리 도메인을 쓸 때 Let's Encrypt 연락 이메일(만료 안내) |
| `NGINX_MANAGER_ADMIN_ALLOW` | (비움, 모두 허용) | 관리 도메인에 들어올 주소를 막을 때(CIDR, 쉼표로 여럿) |
| `NGINX_MANAGER_PORT` | `9000` | 9000을 다른 프로그램이 쓸 때 |
| `NGINX_MANAGER_BIND_ADDRESS` | `127.0.0.1` | 관리 도메인 없이 사내망에서 바로 열 때 `0.0.0.0`이나 사내 IP |
| `NGINX_HTTP_PORT`, `NGINX_HTTPS_PORT` | `80`, `443` | 앞단에 다른 프록시가 있어 80/443을 못 쓸 때 |
| `NGINX_BIND_ADDRESS` | `0.0.0.0` | nginx를 특정 IP에만 열 때 |
| `NGINX_MANAGER_BACKUP_KEEP` | `14` | `nm backup`이 남길 백업 수 |
| `NGINX_MANAGER_BACKUP_AT` | `03:30` | 매일 자동 백업 시각(HH:MM). `off`면 끕니다. 바꾼 뒤 `sudo nm backup-schedule` |
| `TZ` | `Asia/Seoul` | 서버 시간대가 다를 때 |
| `NGINX_MANAGER_IMPORT_ROOTS` | (비움) | `/etc/nginx` 밖의 설정 파일이나 그 설정이 쓰는 파일(예: `/home`, `/etc/letsencrypt`). 쉼표로 여럿. 고친 뒤 `nm up`(또는 `nm import-root add`) |
| `SPRING_MAIL_*` | (비움) | 메일 알림을 보낼 때 SMTP 서버 |

자세한 내용은 [관리 도메인](#관리-도메인으로-https-열기), [포트](#포트), [환경 변수](#환경-변수)에 있습니다.

### 설치 디렉터리

| 파일 | 내용 | 업그레이드할 때 |
| --- | --- | --- |
| `.env` | 버전, 관리 도메인, 포트 등 이 서버의 설정 | 그대로(버전만 `nm upgrade`가 바꿈) |
| `compose.override.yml` | 직접 더한 설정(스트림 포트, 마운트). 필요할 때 만듭니다 | 그대로 |
| `compose.yml`, `nm`, `env.example` | 그 버전의 설치 묶음 | 새 버전으로 바뀜 |
| `data/` | nginx 설정·인증서·로그, 관리도구 DB | 그대로 |
| `backups/` | `nm backup`이 만든 백업 | 그대로 |

`compose.yml`은 고치지 않습니다. 바꿀 값은 `.env`에, 더할 설정은 `compose.override.yml`에 적어야 업그레이드해도 남습니다.

## 관리 도메인으로 HTTPS 열기

`.env`에 관리 도메인과 이메일을 적고 `nm restart`로 관리 화면만 다시 띄웁니다. nginx는 끊기지 않습니다.

```bash
NGINX_MANAGER_ADMIN_DOMAIN=nm.example.com
NGINX_MANAGER_ADMIN_EMAIL=ops@example.com
```

관리 화면이 시작할 때 다음을 저절로 합니다.

1. nginx에 그 도메인의 설정(`conf.d/sites/_nginx-manager-admin.conf`)을 만들어 적용합니다. 이때는 Let's Encrypt 인증만 받고, 관리 화면은 아직 열지 않습니다("준비 중" 응답).
2. Let's Encrypt로 인증서를 발급합니다. 실패하면 이유를 대시보드에 보이고 다시 시도합니다.
3. 인증서가 발급되면 1~2분 안에 HTTPS로 관리 화면을 프록시하는 설정으로 바꿉니다. `http://` 요청은 모두 `https://`로 넘어갑니다(ACME 챌린지 경로만 빼고). 세션 쿠키는 HTTPS 전용이 됩니다.
4. 인증서는 다른 인증서처럼 자동으로 갱신됩니다.

- 이 설정 파일은 관리도구가 관리합니다. [설정 파일](10-files.md)에서 볼 수 있지만 고치거나 지울 수 없고, 같은 도메인으로 사이트를 만들 수 없습니다.
- 대시보드의 **관리 화면 도메인**에서 상태(HTTPS, 인증서를 기다리는 중 등)를 봅니다.
- 관리 도메인에 들어올 수 있는 주소를 좁히려면 `NGINX_MANAGER_ADMIN_ALLOW=203.0.113.0/24,10.0.0.0/8`처럼 적습니다. 나머지는 403입니다.
- 도메인을 비우고 `nm restart` 하면 관리 화면 설정을 거둡니다.

## 포트

포트는 `.env`에서 바꿉니다. 바뀌는 것은 서버 쪽 포트이고, 컨테이너 안(80/443/9000)은 그대로라 사이트 설정을 고칠 필요가 없습니다.

| `.env` | 기본 | 용도 |
| --- | --- | --- |
| `NGINX_HTTP_PORT` | 80 | HTTP 사이트, Let's Encrypt 인증(HTTP-01) |
| `NGINX_HTTPS_PORT` | 443 | HTTPS 사이트(TCP), HTTP/3(UDP) |
| `NGINX_BIND_ADDRESS` | `0.0.0.0` | nginx 포트를 열 주소. 특정 IP에만 열 때 |
| `NGINX_MANAGER_PORT` | 9000 | 관리 화면 |
| `NGINX_MANAGER_BIND_ADDRESS` | `127.0.0.1` | 관리 화면을 열 주소. 사내망에서 바로 쓰려면 `0.0.0.0` |

- HTTP 포트를 80이 아닌 값으로 바꾸면 Let's Encrypt 발급은 앞단 프록시가 80의 `/.well-known/acme-challenge/`를 넘겨 줄 때만 됩니다.
- 에이전트(7070)와 상태 확인(8081)은 서버에 열지 않고 컨테이너끼리만 씁니다.
- 포트를 바꾼 뒤에는 nginx 포트면 `nm restart nginx`(몇 초 끊김), 관리 화면 포트면 `nm restart`.

[스트림](06-streams.md)으로 만든 포트(예: TCP 5432)를 밖에서 쓰려면 `compose.override.yml`에 적고 `nm restart nginx`로 다시 띄웁니다.

```yaml
services:
  nginx:
    ports:
      - "5432:5432"
      - "53:53/udp"
```

## 환경 변수

`.env`에 적고 `nm restart`로 관리 화면만 다시 띄웁니다. 대부분은 비워 두어도 됩니다.

| 변수 | 언제 쓰나 |
| --- | --- |
| `MANAGER_VERSION`, `NGINX_IMAGE` | 두 이미지의 버전. `nm upgrade`가 바꿉니다 |
| `NGINX_MANAGER_ADMIN_DOMAIN`, `NGINX_MANAGER_ADMIN_EMAIL`, `NGINX_MANAGER_ADMIN_ALLOW` | [관리 도메인](#관리-도메인으로-https-열기) |
| `NGINX_MANAGER_IMPORT_ROOTS` | 호스트의 다른 디렉터리(예: `/home`)에서 [설정 파일을 가져올](05-import.md#호스트의-다른-디렉터리-붙이기) 때. `nm import-root add`가 적고 두 컨테이너에 같은 경로로 붙입니다 |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | [메일 알림](12-admin.md#알림-설정)을 보낼 SMTP 서버 |
| `NGINX_MANAGER_BACKUP_KEEP` | `nm backup`이 남길 백업 수(기본 14) |
| `NGINX_MANAGER_BACKUP_AT` | 매일 자동 백업 시각(기본 03:30, `off`면 끔). 바꾼 뒤 `sudo nm backup-schedule` |
| `TZ` | 시간대(기본 `Asia/Seoul`) |

그 밖에 `compose.override.yml`의 manager `environment`로 줄 수 있는 값: `NGINX_MANAGER_ACME_DIRECTORY_URL`(다른 ACME 서버), `NGINX_MANAGER_CERT_CHECK_INTERVAL`(갱신 확인 주기, 기본 `PT6H`), `NGINX_RUNTIME_AGENT_TOKEN`(에이전트 토큰을 직접 정할 때. nginx의 `NGINX_AGENT_TOKEN`과 같게).

## 운영 명령(nm)

설치 디렉터리의 `nm`으로 운영합니다. `sudo /opt/nginx-manager/nm <명령>` 또는 `docker` 그룹 사용자로 실행합니다.

| 명령 | 하는 일 |
| --- | --- |
| `nm status` | 컨테이너 상태와 버전, 관리 화면 주소, 관리 도메인 점검 |
| `nm check-domain` | 관리 도메인이 DNS에 있고 이 서버의 nginx로 들어오는지 확인([13장](13-troubleshooting.md#관리-도메인이-열리지-않을-때)) |
| `nm reload` | nginx 설정을 검사하고 끊김 없이 다시 읽기(호스트 Certbot이 인증서를 갱신한 뒤 등, [5장](05-import.md#certbot-인증서를-그대로-쓰기)) |
| `nm version` | 설치된 버전과 최신 릴리스 |
| `nm logs [manager\|nginx]` | 로그 보기 |
| `nm restart [manager\|nginx]` | 다시 띄우기(기본 manager). `.env`를 바꾼 뒤 |
| `nm upgrade [버전] [--nginx]` | [업그레이드](#업그레이드) |
| `nm backup`, `nm restore <파일>` | [백업과 복원](#백업과-복원) |
| `nm reset-2fa 아이디` | 인증 앱과 복구 코드를 모두 잃은 사용자의 [2단계 인증](02-getting-started.md#2단계-인증)을 끈다(manager를 다시 띄움) |
| `nm migrate` | [v1.1 이전 설치 옮기기](#v11-이전-설치-옮기기) |
| `nm import-root add\|remove\|check\|grant` | [가져올 호스트 디렉터리](05-import.md#호스트의-다른-디렉터리-붙이기) 붙이기·떼기, UID 10101 읽기 권한 확인·부여 |

## 업그레이드

관리 화면(manager)과 nginx 이미지는 따로 올립니다. 평소 릴리스는 manager만 올리고, 그동안 nginx는 끊기지 않습니다.

```bash
sudo /opt/nginx-manager/nm upgrade          # 최신 릴리스로
sudo /opt/nginx-manager/nm upgrade 1.2.1    # 버전을 정해서
```

1. 그 버전의 설치 묶음을 받아 `compose.yml`·`nm`을 바꿉니다. `.env`와 `compose.override.yml`은 그대로이고, `.env`에서는 `MANAGER_VERSION`만 바뀝니다. 새 버전에 새 설정 키가 생겼으면 기본값으로 `.env` 끝에 더하고 무엇을 더했는지 보여 줍니다(이미 있는 값은 건드리지 않습니다). 새 버전의 설명이 붙은 `.env`는 `.env.example`에 있습니다.
2. 올리기 전에 백업합니다(`backups/…-before-v1.2.1.tar.gz`).
3. manager만 새 이미지로 다시 띄우고 healthy를 기다립니다. DB 변경은 관리 화면이 시작할 때 자동으로 적용됩니다.
4. 정해진 시간(기본 180초) 안에 healthy가 되지 않으면 이전 버전과 업그레이드 전 DB로 롤백하고 이유를 보여 줍니다.

새 묶음이 권하는 nginx 이미지가 지금과 다르면 끝에 "nginx 이미지 업데이트가 있습니다"라고 알립니다. nginx는 몇 초 다시 시작하므로 한가한 시간에 올립니다.

```bash
sudo /opt/nginx-manager/nm upgrade --nginx
```

- 새 nginx 이미지를 먼저 받아 두고, 새 nginx로 지금 설정을 검사합니다. 받아들이지 않으면 바꾸지 않고 문제의 파일·줄을 보여 줍니다.
- 바꾸는 동안 새 연결이 몇 초 끊기고, 진행 중인 요청은 40초 안에 마무리됩니다.
- 두 컨테이너의 릴리스 버전이 달라도 정상입니다. 대시보드와 화면 아래에 두 버전이 따로 보입니다. 서로 말이 통하지 않는 판(에이전트 API)일 때만 경고 띠가 뜨고, 그때는 `nm upgrade --nginx`로 nginx도 함께 올립니다.
- 버전이 바뀐 사실은 대시보드와 감사 로그에 남습니다.

설정 자체를 롤백할 때는 [버전 이력](08-versions.md)의 롤백을 씁니다.

## 백업과 복원

```bash
sudo /opt/nginx-manager/nm backup                 # backups/nginx-manager-<시각>-v<버전>.tar.gz
sudo /opt/nginx-manager/nm backup --with-logs     # 로그까지
sudo /opt/nginx-manager/nm restore backups/<파일>
```

- 백업에는 nginx 설정·인증서, 관리도구 DB(SQLite)·ACME 계정 키, `.env`가 들어갑니다. 파일 권한은 600입니다.
- DB를 일관되게 복사하려고 관리 화면만 몇 초 멈춥니다. nginx는 계속 돕니다.
- 오래된 백업은 `NGINX_MANAGER_BACKUP_KEEP`(기본 14) 개만 남깁니다(업그레이드 전 백업 포함).
- 복원은 지금 데이터를 먼저 `backups/…-before-restore`로 남긴 뒤 바꿉니다. nginx가 몇 초 다시 시작합니다. 백업보다 낮은 manager 버전으로는 복원하지 않습니다(DB를 낮은 버전으로 되돌릴 수 없습니다). 백업 당시의 `.env`는 `.env.restored`에 둡니다.
- 새 서버로 옮길 때는 같은 버전을 설치하고 백업 파일을 복사해 `nm restore`합니다.

### 자동 백업

설치와 `nm upgrade`가 매일 03:30(서버 시간)에 `nm backup --label auto`를 돌리는 스케줄을 깝니다(`/etc/cron.d/nginx-manager-backup`, cron이 없으면 systemd timer `nginx-manager-backup.timer`). 자동 백업도 `NGINX_MANAGER_BACKUP_KEEP` 개수에 같이 셉니다.

- 시각을 바꾸려면 `.env`의 `NGINX_MANAGER_BACKUP_AT`(예: `04:15`)을 고치고 `sudo /opt/nginx-manager/nm backup-schedule`을 실행합니다. `off`로 두고 실행하면 끕니다.
- 스케줄은 호스트의 `/etc`에 쓰므로 root(`sudo`)로 실행해야 합니다.
- 마지막 백업의 시각·크기와 실패 여부는 [대시보드](03-dashboard.md)에 보입니다.

## v1.1 이전 설치 옮기기

v1.0·v1.1은 compose 파일 하나와 이름 붙은 볼륨(`nm-conf`, `nm-logs`, `nm-shared`, `nm-data`)을 썼습니다. 새 방식으로 설치한 뒤 데이터를 옮깁니다.

```bash
curl -fsSL https://github.com/nginx-manager/app/releases/latest/download/install.sh | sudo sh -s -- --no-start
sudo /opt/nginx-manager/nm migrate              # 옛 볼륨을 찾아 data/로 복사하고 띄운다
sudo /opt/nginx-manager/nm migrate --from docker  # 볼륨 이름 앞부분(compose 프로젝트 이름)을 정할 때
```

옛 컨테이너를 멈추고 네 볼륨을 복사합니다. 옛 볼륨은 지우지 않으니, 확인한 뒤 안내대로 지웁니다. `--no-start`를 빼고 설치하면 80/443 포트가 옛 컨테이너와 겹쳐 설치가 멈춥니다.

## 개발용으로 띄우기

저장소의 `docker/docker-compose.yml`은 개발·시험용입니다(이름 붙은 볼륨, 관리 화면 9000을 모든 주소에 엶). 서버에는 위의 설치 묶음을 씁니다.

```bash
git clone https://github.com/nginx-manager/app.git && cd app
NGINX_MANAGER_VERSION=1.2.0 docker compose -f docker/docker-compose.yml up -d
```
