# nginx manager

**한국어** · [English](README.en.md) · [日本語](README.ja.md) · [简体中文](README.zh-CN.md)

브라우저에서 nginx를 관리하는 도구입니다. nginx(오픈소스, 전체 모듈 빌드)와 관리 화면(Spring Boot)이 서로 다른 컨테이너에서 돌기 때문에, 관리 화면을 업데이트하거나 다시 띄워도 nginx는 트래픽을 계속 받습니다.

**사용법은 관리 화면 안의 사용 설명서(`/guide`)에 있습니다.** 왼쪽 메뉴와 아래(footer)의 **사용 설명서**를 누르면 열립니다. 원문은 [src/main/resources/guide/ko](src/main/resources/guide/ko/README.md)에 있어 저장소에서도 읽을 수 있습니다.

명세와 설계는 [nginx-manager/docs](https://github.com/nginx-manager/docs)의 `specs/001-nginx-web-manager/`에 있습니다(Spec Kit).

## 할 수 있는 일

- **사이트**: 리버스 프록시·정적 파일 사이트를 폼으로 만들고, 그 밖의 설정은 Raw 편집기(nginx 문법 강조)로 고칩니다. 프록시는 업스트림 여러 개(로드 밸런싱), 백업 업스트림, 장애 판정(max_fails), keepalive, WebSocket, 타임아웃까지 폼에서 정합니다. 쓰지 않는 사이트는 지울 수 있습니다(삭제도 적용 대기 → 적용을 거칩니다). PHP 사이트(PHP-FPM)와 gRPC 프록시(`grpc://`)도 폼으로 만들고, `/api/`는 백엔드로, `/files/`는 디렉터리로 보내는 경로별 라우팅도 폼에서 정합니다.
- **사이트 옵션**: 폼에서 IP 허용·차단, Basic 인증(htpasswd), 외부 인증 서버(auth_request: oauth2-proxy·Authelia 같은 SSO), 요청 속도·동시 연결 제한, gzip 압축, 보안 헤더, HSTS, HTTP/3(QUIC), 실제 클라이언트 IP(프록시·CDN 뒤), 프록시 캐시, 디렉터리 리스팅, 최대 요청 크기를 켭니다. 배포 중에는 점검 모드로 안내 페이지(503)를 보여 주고 작업자 주소만 들여보냅니다. 다른 주소로 보내기만 하는 리다이렉트 사이트도 만듭니다.
- **스트림(TCP/UDP)**: DB·SSH·DNS처럼 HTTP가 아닌 포트를 백엔드로 넘기는 stream 프록시를 폼으로 만듭니다(로드 밸런싱, 타임아웃).
- **설정 파일 가져오기**: 이미 쓰던 nginx 설정 파일을 사이트로 등록합니다. `/home/*/nginx/*.conf` 같은 경로 패턴을 등록해 맞는 파일을 한꺼번에 찾거나, 내 PC의 파일을 업로드합니다. 폼으로 옮길 수 있는 파일은 폼 사이트로, 나머지는 Raw 사이트로 등록되고, 소스 파일이 바뀌면 사이트 화면에서 알려 다시 가져올 수 있습니다. 호스트 디렉터리는 manager 컨테이너에 마운트하고 `NGINX_MANAGER_IMPORT_ROOTS`에 적습니다.
- **설정 파일 직접 편집**: Raw 편집기에서 새 설정 파일(njs 스크립트, 공용 조각 등)을 만들고 지웁니다. 다른 변경과 같이 저장(적용 대기) → `nginx -t` → reload를 거칩니다.
- **안전한 적용**: 저장한 변경은 적용 대기로 남고, 적용 화면에서 diff로 확인한 뒤 반영합니다. 반영은 `nginx -t` → reload → 헬스 체크 순서로 하고, 어느 단계든 실패하면 직전 설정으로 자동 롤백합니다. 포트 충돌처럼 `nginx -t`가 못 잡고 reload에서야 드러나는 문제도 실패로 보고 롤백합니다.
- **버전 이력과 롤백**: 적용할 때마다 버전이 남고, 두 버전의 diff를 보거나 이전 버전으로 롤백합니다(롤백도 같은 `nginx -t`를 거칩니다). 서버에서 직접 바뀐 파일(드리프트)은 감지해 알려 줍니다. 사이트 화면의 **변경 이력**에서 그 사이트가 바뀐 버전만 모아 볼 수 있고, 멤버도 담당 사이트의 이력을 봅니다. 같은 내용은 한 번만 저장해 버전이 쌓여도 DB가 크게 늘지 않습니다.
- **HTTPS**: 사이트에서 HTTPS를 켜면 Let's Encrypt 인증서를 발급해 설정까지 자동으로 적용하고, 만료 전에 갱신합니다. 갱신에 실패하면 지금 인증서로 계속 서비스하면서 메일·웹훅으로 알립니다. 와일드카드·사내 인증서는 업로드합니다. 가져온 Raw 사이트도 인증서를 발급하고 443 server 블록과 80→443 리다이렉트를 추가할 수 있습니다.
- **대시보드와 로그**: nginx 상태·버전·모듈, 최근 적용, 인증서 경고를 보고, 사이트별 access/error 로그를 검색하거나 실시간으로 봅니다(가져온 Raw 사이트와 로테이트된 로그 포함).
- **사용자와 감사 로그**: 관리자/멤버 역할. 멤버는 담당한 사이트만 보고 고치고 적용하며, 관리자는 사이트마다 담당 멤버를 정합니다. 모든 변경은 감사 로그에 남습니다. 인증 앱(TOTP) 2단계 인증과 복구 코드를 켤 수 있습니다.

## 실행

서버에는 릴리스에 첨부된 설치 묶음을 씁니다. 명령 한 줄로 `/opt/nginx-manager`에 깔고 띄웁니다(Docker와 Compose v2 필요).

```bash
curl -fsSL https://github.com/nginx-manager/app/releases/latest/download/install.sh | sudo sh -s -- \
    --admin-domain nm.example.com --email ops@example.com
```

- 관리 화면은 기본으로 서버 안(`http://127.0.0.1:9000`)에서만 열립니다. `--admin-domain`(또는 `.env`의 `NGINX_MANAGER_ADMIN_DOMAIN`)을 주면 함께 든 nginx가 그 도메인으로 Let's Encrypt 인증서를 발급해 HTTPS로만 관리 화면을 엽니다(80은 443으로 넘김).
- 포트·도메인·버전은 설치 디렉터리의 `.env`에서 바꿉니다. 데이터는 `data/` 아래에 있습니다.
- 이미 깐 서버에서 설치 명령을 다시 실행하면 `.env`와 `data/`를 건드리지 않고 끝납니다. 새 버전은 `nm upgrade`로 올리며, `.env`는 그대로 두고 새로 생긴 키만 기본값으로 더합니다.
- 운영은 `nm` 명령으로 합니다: `nm status`, `nm upgrade`(manager만 올려 nginx는 끊기지 않음), `nm upgrade --nginx`, `nm backup`, `nm restore`, `nm migrate`(v1.1 이전 설치 옮기기), `nm import-root`(가져올 호스트 디렉터리를 같은 경로로 붙이고 읽기 권한 확인), `nm check-domain`(관리 도메인이 DNS에 있고 이 서버로 들어오는지 확인).
- 서버에 이미 있는 설정(예: `/home/*/nginx/*.conf`)을 가져오려면 설치할 때 `--import-root /home`을 붙입니다. 설치한 뒤에는 `nm import-root add /home`으로 붙이고, `nm import-root check`로 관리도구(UID 10101)가 읽을 수 있는지 확인합니다. 읽을 수 없으면 `nm import-root grant`가 ACL로 읽기 권한만 줍니다. 가져오기 화면도 권한이 없는 디렉터리를 만나면 멈추지 않고, 못 연 곳과 grant 명령을 보여 줍니다.
- 자세한 내용은 사용 설명서 [1. 설치와 실행](src/main/resources/guide/ko/01-install.md)에 있습니다. 묶음의 원본은 [deploy/](deploy/)입니다.

설치 디렉터리의 `.env`(설치 옵션으로 준 값이 들어갑니다). 바꾼 뒤에는 `nm restart`, nginx 포트를 바꿨으면 `nm restart nginx`.

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
| `NGINX_MANAGER_BACKUP_AT` | `03:30` | 매일 자동 백업(`nm backup --label auto`) 시각. `off`면 끕니다. 바꾼 뒤 `sudo nm backup-schedule` |
| `TZ` | `Asia/Seoul` | 서버 시간대가 다를 때 |
| `NGINX_MANAGER_IMPORT_ROOTS` | (비움) | `/etc/nginx` 밖의 설정 파일이나 그 설정이 쓰는 파일(예: `/home`, `/etc/letsencrypt`). 쉼표로 여럿. 고친 뒤 `nm up` |
| `SPRING_MAIL_*` | (비움) | 메일 알림을 보낼 때 SMTP 서버 |

개발·시험에는 저장소의 [docker/docker-compose.yml](docker/docker-compose.yml)을 씁니다(이름 붙은 볼륨, 관리 화면을 모든 주소에 엶).

```bash
NGINX_MANAGER_VERSION=1.2.0 docker compose -f docker/docker-compose.yml up -d
```

| 컨테이너 | 이미지 | 하는 일 |
|----------|--------|---------|
| `nginx` | `ghcr.io/nginx-manager/nginx` | nginx(80/443)와 에이전트. 에이전트는 관리 화면의 요청으로 `nginx -t`·reload만 실행합니다. |
| `manager` | `ghcr.io/nginx-manager/app` | 관리 화면(9000). 설정 파일은 볼륨으로 직접 쓰고, 검증·reload는 에이전트에 맡깁니다. |

| 볼륨 | 마운트 | 내용 |
|------|--------|------|
| `nm-conf` | 둘 다 `/etc/nginx` | nginx 설정, 인증서(`certs/`) |
| `nm-logs` | 둘 다 `/var/log/nginx` | 접근·오류 로그(사이트별 로그는 `sites/`) |
| `nm-shared` | 둘 다 `/var/lib/nginx-manager` | Let's Encrypt 챌린지 파일, 에이전트 토큰 |
| `nm-data` | manager `/data` | 관리도구 DB(SQLite), ACME 계정 키, 진행 중인 적용 기록 |

공유 볼륨은 두 컨테이너에서 **같은 경로**에 둡니다(`nginx -t` 오류의 파일 경로가 화면의 파일과 맞아야 합니다). 두 이미지의 프로세스는 같은 UID(10101)로 돌아 서로의 파일을 읽고 씁니다.

**스트림 포트와 HTTP/3**: 스트림 화면에서 만든 포트(예: TCP 5432)를 밖에서 쓰려면 compose 파일의 `nginx` 서비스 `ports`에 그 포트를 더하고(`"5432:5432"`, UDP는 `"53:53/udp"`) `docker compose up -d nginx`로 다시 띄웁니다. HTTP/3는 compose 파일이 이미 `443/udp`를 열고, 기본 `nginx.conf`의 443 기본 서버가 `listen 443 quic reuseport`를 한 번 선언해 둡니다. 예전 `nginx.conf`를 그대로 쓰는 설치라면 사이트 화면이 그 사실을 알려 주니, 새 이미지의 [nginx.conf](docker/nginx/nginx.conf)처럼 443 기본 서버를 더하세요.

처음 시작할 때 `/etc/nginx`에 이미 있는 설정을 가져와 첫 버전으로 남깁니다. 이미지를 바꿔도 볼륨의 데이터는 그대로입니다.

### 설정(환경 변수)

관리도구(`manager`) 컨테이너:

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `NGINX_RUNTIME_AGENT_URL` | `http://nginx:7070` | 에이전트 주소. compose의 nginx 서비스 이름을 바꾸면 함께 바꿉니다. |
| `NGINX_RUNTIME_AGENT_TOKEN` | (비움) | 비우면 공유 볼륨의 토큰 파일을 읽습니다. 직접 정하면 nginx 컨테이너의 `NGINX_AGENT_TOKEN`과 같게 둡니다. |
| `NGINX_MANAGER_ACME_DIRECTORY_URL` | (비움) | 지정하면 화면의 Let's Encrypt 운영/스테이징 선택 대신 이 ACME 서버를 씁니다. |
| `NGINX_MANAGER_CERT_CHECK_INTERVAL` | `PT6H` | 갱신할 인증서를 찾는 주기 |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | | 메일 알림을 쓸 때 SMTP 서버 |
| `SERVER_SERVLET_SESSION_COOKIE_SECURE` | `false` | 관리 화면을 HTTPS 뒤에 두면 `true` |
| `NGINX_MANAGER_IMPORT_ROOTS` | (비움) | 설정 파일 가져오기에서 `/etc/nginx` 말고 찾을 수 있는 디렉터리(쉼표로 여럿). 호스트 디렉터리를 manager 컨테이너에 같은 경로로 마운트한 뒤 적습니다. |

nginx 컨테이너:

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `NGINX_AGENT_TOKEN` | (비움) | 비우면 처음 뜰 때 `/var/lib/nginx-manager/agent/token`에 무작위 토큰을 만들어 씁니다. |
| `NGINX_AGENT_LISTEN` | `:7070` | 에이전트가 듣는 주소 |

Let's Encrypt는 HTTP-01 방식으로 인증하므로 도메인이 nginx 컨테이너의 80 포트로 들어와야 합니다. 처음에는 화면의 설정에서 스테이징으로 시험해 보는 것을 권합니다.

## 버전과 릴리스 노트

지금 쓰는 관리도구 버전은 관리 화면 아래(footer)에 보입니다. 버전은 git 태그(`vX.Y.Z`)가 기준이고, 태그 없이 빌드한 이미지는 `dev`로 보입니다. 버전별로 무엇이 바뀌었는지는 [릴리스 노트](docs/releases/README.md)에 화면과 함께 정리합니다. 버전 규칙과 릴리스 절차도 거기에 있습니다.

## 업그레이드와 롤백

manager와 nginx 이미지는 따로 올립니다. `.env`의 `MANAGER_VERSION`과 `NGINX_IMAGE`가 두 이미지의 태그입니다.

```bash
sudo /opt/nginx-manager/nm upgrade           # manager만. nginx는 계속 트래픽을 받는다
sudo /opt/nginx-manager/nm upgrade --nginx   # nginx 이미지도. 새 nginx로 설정을 검사한 뒤 바꾼다(몇 초 재시작)
```

- `nm upgrade`는 올리기 전에 백업하고, 새 manager가 healthy가 되지 않으면 이전 버전과 업그레이드 전 DB로 롤백합니다(DB 변경은 앞으로만 가므로).
- 두 컨테이너의 릴리스 버전이 달라도 정상입니다. 에이전트 API 판이 맞지 않을 때만 경고가 뜨고 nginx도 함께 올리라고 알립니다. 에이전트 API를 바꿀 때는 이전 판도 받게 해서 manager를 먼저 올릴 수 있게 합니다(`AgentNginxRuntime.MIN_AGENT_PROTOCOL`).
- nginx 이미지 태그는 `<관리도구 버전>-nginx<nginx 버전>`(예: `1.2.0-nginx1.31.8`), `<관리도구 버전>`, `latest`입니다. nginx 새 릴리스가 나오면 nginx 이미지만 자동으로 다시 빌드·테스트·게시되고, 그 릴리스의 설치 묶음도 새 nginx 태그로 바뀝니다. 이전 태그는 그대로 남습니다.
- 새 nginx 이미지는 시작할 때 nginx를 띄우기 전에 볼륨의 설정을 검증합니다. 받아들이지 않으면 nginx는 띄우지 않고 에이전트만 뜨며, 대시보드에 문제의 파일·줄과 이전/현재 버전을 보여 줍니다.
- 버전이 바뀌면 대시보드와 감사 로그에 남습니다.

설정 자체를 롤백할 때는 버전 이력 화면에서 원하는 버전의 "이 버전으로 롤백"을 씁니다.

## 구성

- nginx 컨테이너 안에서 [s6-overlay](https://github.com/just-containers/s6-overlay)가 nginx와 에이전트를 각각 감시하고, 죽으면 다시 띄웁니다.
- 관리도구는 nginx를 에이전트 API(`agent/`)로만 다룹니다. 에이전트는 고정된 명령(`nginx -t`, `nginx -s reload`, `nginx -s reopen`, `nginx -V`, `s6-svc -u`)만 실행하고, 토큰 없는 요청은 거절합니다. 관리도구에 Docker 소켓을 넘기지 않습니다.
- 두 컨테이너의 프로세스는 모두 비루트 사용자 `nginxm`(UID 10101)으로 돕니다. nginx 실행 파일에만 80/443 포트를 열 권한을 줍니다.
- 인증서는 발급·갱신마다 `/etc/nginx/certs/<이름>/<시각>/`에 새로 쓰고 설정의 경로만 바꿔 적용합니다. 그래서 인증서 교체도 버전 이력에 남고 롤백할 수 있습니다.

## 개발

Java 21, Maven 3.9, Go 1.24, Docker가 필요합니다.

```bash
mvn verify                       # 단위·통합 테스트(실제 ACME 발급 테스트는 Docker로 Pebble을 띄움)
mvn spotless:apply               # 포맷 맞추기(verify가 검사함). 컴파일 때는 Error Prone이 흔한 실수를 막습니다
(cd agent && go test ./...)      # 에이전트 테스트
docker build -f docker/nginx/Dockerfile -t nginx-manager/nginx:dev .
docker build -f docker/manager/Dockerfile -t nginx-manager/app:dev .
# 이미지 테스트: 두 컨테이너를 compose와 같은 구성으로 띄워 적용·HTTPS·업그레이드 시나리오를 실제 nginx로 확인
mvn -Pimage-test test -Dimage.name=nginx-manager/app:dev -Dnginx.image.name=nginx-manager/nginx:dev
```

Raw 편집기 번들(`src/main/resources/static/js/editor.bundle.js`)은 저장소에 함께 둡니다. 바꿀 때만 다시 만듭니다.

```bash
cd frontend && npm ci && npm run build
```

데이터 접근 규칙(constitution):

- 동적 조건·조인·프로젝션 조회는 Querydsl로 작성합니다. 문자열 JPQL, 네이티브 쿼리, `@Query`는 쓰지 않습니다(`ArchitectureRulesTest`가 막습니다).
- 연관관계는 모두 `fetch = LAZY`, `open-in-view=false`입니다.
- 목록 조회마다 `QueryCounter.assertConstantQueries`로 데이터 건수와 상관없이 쿼리 수가 같은지 확인합니다.

사용 설명서: 사용자가 보는 기능·화면·설정을 바꾸는 PR은 같은 PR에서 [사용 설명서](src/main/resources/guide/ko/README.md)의 해당 장도 고칩니다. 방법과 그림 다시 찍기는 [설명서 관리](docs/guide.md)에 있습니다.

릴리스: [릴리스 노트](docs/releases/README.md)를 쓰고 `v<버전>` 태그를 밀면 그 버전과 최신 nginx로 두 이미지를 게시합니다. 이후 nginx 릴리스는 매일 확인해 nginx 이미지만 자동으로 게시합니다. 에이전트 API를 바꾸면 `agent/server.go`의 `Protocol`과 `AgentNginxRuntime.PROTOCOL`을 함께 올립니다.
