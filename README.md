# nginx manager

브라우저에서 nginx를 관리하는 도구입니다. nginx(오픈소스, 전체 모듈 빌드)와 관리 화면(Spring Boot)이 서로 다른 컨테이너에서 돌기 때문에, 관리 화면을 업데이트하거나 다시 띄워도 nginx는 트래픽을 계속 받습니다.

명세와 설계는 [nginx-manager/docs](https://github.com/nginx-manager/docs)의 `specs/001-nginx-web-manager/`에 있습니다(Spec Kit).

## 할 수 있는 일

- **사이트**: 리버스 프록시·정적 파일 사이트를 폼으로 만들고, 그 밖의 설정은 원문 편집기(nginx 문법 강조)로 고칩니다. 프록시는 백엔드 여러 개(부하 분산), 웹소켓, 타임아웃까지 폼에서 정합니다.
- **사이트 옵션**: 폼에서 IP 허용·차단, 비밀번호 보호(htpasswd), 요청 속도·동시 연결 제한, gzip 압축, 보안 헤더, HSTS, HTTP/3(QUIC), 실제 클라이언트 IP(프록시·CDN 뒤), 프록시 캐시, 디렉터리 목록, 최대 요청 크기를 켭니다. 다른 주소로 넘기기만 하는 리디렉션 사이트도 만듭니다.
- **스트림(TCP/UDP)**: DB·SSH·DNS처럼 HTTP가 아닌 포트를 백엔드로 넘기는 stream 프록시를 폼으로 만듭니다(부하 분산, 타임아웃).
- **원문 파일**: 원문 편집기에서 새 설정 파일(njs 스크립트, 공용 조각 등)을 만들고 지웁니다. 다른 변경과 같이 초안 → 검증 → 적용을 거칩니다.
- **안전한 적용**: 저장한 초안은 적용 화면에서 diff로 확인한 뒤 반영합니다. 반영은 `nginx -t` 검증 → reload → 상태 확인 순서로 하고, 어느 단계든 실패하면 직전 설정으로 돌아갑니다. 포트 충돌처럼 `nginx -t`가 못 잡고 reload에서야 드러나는 문제도 실패로 보고 되돌립니다.
- **이력과 되돌리기**: 적용할 때마다 버전이 남고, 두 버전을 비교하거나 이전 버전으로 되돌립니다(되돌리기도 같은 검증을 거칩니다). 서버에서 직접 바뀐 파일은 감지해 알려 줍니다.
- **HTTPS**: 사이트에서 HTTPS를 켜면 Let's Encrypt 인증서를 받아 설정까지 자동으로 적용하고, 만료 전에 갱신합니다. 갱신에 실패하면 지금 인증서로 계속 서비스하면서 메일·웹훅으로 알립니다. 와일드카드·사내 인증서는 업로드합니다.
- **대시보드와 로그**: nginx 상태·버전·모듈, 최근 적용, 인증서 경고를 보고, 사이트별 access/error 로그를 검색하거나 실시간으로 봅니다.
- **사용자와 감사 로그**: 관리자/편집자/조회자 역할, 모든 변경은 감사 로그에 남습니다.

## 실행

[docker/docker-compose.yml](docker/docker-compose.yml)을 받아 띄웁니다. 컨테이너 두 개와 볼륨, 에이전트 토큰이 모두 들어 있어 따로 정할 것은 없습니다.

```bash
curl -fsSLO https://raw.githubusercontent.com/nginx-manager/app/main/docker/docker-compose.yml
docker compose up -d
```

`http://<호스트>:9000`에 접속하면 최초 관리자 계정을 만드는 화면이 나옵니다. 관리 화면은 인터넷에 그대로 열지 말고 사내망·VPN에서 쓰거나 HTTPS 프록시 뒤에 두세요([보안 점검](docs/security.md)).

| 컨테이너 | 이미지 | 하는 일 |
|----------|--------|---------|
| `nginx` | `ghcr.io/nginx-manager/nginx` | nginx(80/443)와 에이전트. 에이전트는 관리 화면의 요청으로 `nginx -t`·reload만 실행합니다. |
| `manager` | `ghcr.io/nginx-manager/app` | 관리 화면(9000). 설정 파일은 볼륨으로 직접 쓰고, 검증·reload는 에이전트에 맡깁니다. |

| 볼륨 | 마운트 | 내용 |
|------|--------|------|
| `nm-conf` | 둘 다 `/etc/nginx` | nginx 설정, 인증서(`certs/`) |
| `nm-logs` | 둘 다 `/var/log/nginx` | 접근·오류 로그(사이트별 로그는 `sites/`) |
| `nm-shared` | 둘 다 `/var/lib/nginx-manager` | Let's Encrypt 챌린지 파일, 에이전트 토큰 |
| `nm-data` | manager `/data` | 관리도구 DB(SQLite), ACME 계정 키 |

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

nginx 컨테이너:

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `NGINX_AGENT_TOKEN` | (비움) | 비우면 처음 뜰 때 `/var/lib/nginx-manager/agent/token`에 무작위 토큰을 만들어 씁니다. |
| `NGINX_AGENT_LISTEN` | `:7070` | 에이전트가 듣는 주소 |

Let's Encrypt는 HTTP-01 방식으로 인증하므로 도메인이 nginx 컨테이너의 80 포트로 들어와야 합니다. 처음에는 화면의 설정에서 스테이징으로 시험해 보는 것을 권합니다.

## 업그레이드와 되돌리기

두 이미지는 **같은 버전**으로 맞춥니다. compose 파일은 `NGINX_MANAGER_VERSION` 하나로 두 이미지의 태그를 함께 정합니다(기본 `latest`).

```bash
NGINX_MANAGER_VERSION=1.0.0 docker compose pull
NGINX_MANAGER_VERSION=1.0.0 docker compose up -d
```

- 관리 화면만 바꾸면 nginx는 끊기지 않습니다(`docker compose up -d manager`).
- 버전이 다르거나 에이전트에 연결할 수 없으면 모든 화면 위에 경고가 뜹니다. 관리도구와 에이전트가 서로 알아들을 수 없는 판이면 에이전트가 요청을 거절하고 화면이 그 사실을 알립니다.
- nginx 이미지 태그는 `<관리도구 버전>-nginx<nginx 버전>`(예: `1.0.0-nginx1.31.6`), `<관리도구 버전>`, `latest`입니다. nginx 새 릴리스가 나오면 nginx 이미지만 자동으로 다시 빌드·테스트·게시되고, 이전 태그는 그대로 남습니다.
- 새 nginx 이미지는 시작할 때 nginx를 띄우기 전에 볼륨의 설정을 검증합니다. 새 nginx가 설정을 받아들이지 않으면 nginx는 띄우지 않고 에이전트만 뜹니다. 관리 화면이 이를 알아채 대시보드에 문제의 파일·줄과 이전/현재 버전을 보여 줍니다.
- 고치는 방법은 둘입니다. 화면에서 설정을 고쳐 적용한 뒤 대시보드의 "nginx 시작"을 누르거나, nginx 컨테이너를 이전 태그로 다시 띄웁니다(볼륨은 그대로 씁니다).
- 버전이 바뀌면 대시보드와 감사 로그에 남습니다.

설정 자체를 되돌릴 때는 이력 화면에서 원하는 버전의 "되돌리기"를 씁니다.

## 구성

- nginx 컨테이너 안에서 [s6-overlay](https://github.com/just-containers/s6-overlay)가 nginx와 에이전트를 각각 감시하고, 죽으면 다시 띄웁니다.
- 관리도구는 nginx를 에이전트 API(`agent/`)로만 다룹니다. 에이전트는 고정된 명령(`nginx -t`, `nginx -s reload`, `nginx -s reopen`, `nginx -V`, `s6-svc -u`)만 실행하고, 토큰 없는 요청은 거절합니다. 관리도구에 Docker 소켓을 넘기지 않습니다.
- 두 컨테이너의 프로세스는 모두 비루트 사용자 `nginxm`(UID 10101)으로 돕니다. nginx 실행 파일에만 80/443 포트를 열 권한을 줍니다.
- 인증서는 발급·갱신마다 `/etc/nginx/certs/<이름>/<시각>/`에 새로 쓰고 설정의 경로만 바꿔 적용합니다. 그래서 인증서 교체도 이력에 남고 되돌릴 수 있습니다.

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

원문 편집기 번들(`src/main/resources/static/js/editor.bundle.js`)은 저장소에 함께 둡니다. 바꿀 때만 다시 만듭니다.

```bash
cd frontend && npm ci && npm run build
```

데이터 접근 규칙(constitution):

- 동적 조건·조인·프로젝션 조회는 Querydsl로 작성합니다. 문자열 JPQL, 네이티브 쿼리, `@Query`는 쓰지 않습니다(`ArchitectureRulesTest`가 막습니다).
- 연관관계는 모두 `fetch = LAZY`, `open-in-view=false`입니다.
- 목록 조회마다 `QueryCounter.assertConstantQueries`로 데이터 건수와 상관없이 쿼리 수가 같은지 확인합니다.

릴리스: `v<버전>` 태그를 밀면 그 버전과 최신 nginx로 두 이미지를 게시합니다. 이후 nginx 릴리스는 매일 확인해 nginx 이미지만 자동으로 게시합니다. 에이전트 API를 바꾸면 `agent/server.go`의 `Protocol`과 `AgentNginxRuntime.PROTOCOL`을 함께 올립니다.
