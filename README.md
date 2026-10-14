# nginx manager

브라우저에서 nginx를 관리하는 도구입니다. nginx(오픈소스, 전체 모듈 빌드)와 관리도구(Spring Boot)가 Docker 이미지 하나에 들어 있습니다.

명세와 설계는 [nginx-manager/docs](https://github.com/nginx-manager/docs)의 `specs/001-nginx-web-manager/`에 있습니다(Spec Kit).

## 할 수 있는 일

- **사이트**: 리버스 프록시·정적 파일 사이트를 폼으로 만들고, 그 밖의 설정은 원문 편집기(nginx 문법 강조)로 고칩니다. 프록시는 백엔드 여러 개(부하 분산), 웹소켓, 타임아웃까지 폼에서 정합니다.
- **안전한 적용**: 저장한 초안은 적용 화면에서 diff로 확인한 뒤 반영합니다. 반영은 `nginx -t` 검증 → reload → 상태 확인 순서로 하고, 어느 단계든 실패하면 직전 설정으로 돌아갑니다. 포트 충돌처럼 `nginx -t`가 못 잡고 reload에서야 드러나는 문제도 실패로 보고 되돌립니다.
- **이력과 되돌리기**: 적용할 때마다 버전이 남고, 두 버전을 비교하거나 이전 버전으로 되돌립니다(되돌리기도 같은 검증을 거칩니다). 서버에서 직접 바뀐 파일은 감지해 알려 줍니다.
- **HTTPS**: 사이트에서 HTTPS를 켜면 Let's Encrypt 인증서를 받아 설정까지 자동으로 적용하고, 만료 전에 갱신합니다. 갱신에 실패하면 지금 인증서로 계속 서비스하면서 메일·웹훅으로 알립니다. 와일드카드·사내 인증서는 업로드합니다.
- **대시보드와 로그**: nginx 상태·버전·모듈, 최근 적용, 인증서 경고를 보고, 사이트별 access/error 로그를 검색하거나 실시간으로 봅니다.
- **사용자와 감사 로그**: 관리자/편집자/조회자 역할, 모든 변경은 감사 로그에 남습니다.

## 실행

```bash
docker run -d --name nginx-manager \
  -p 80:80 -p 443:443 -p 9000:9000 \
  -v nm-conf:/etc/nginx -v nm-logs:/var/log/nginx -v nm-data:/data \
  ghcr.io/nginx-manager/app:latest
```

Compose로 띄우려면 [docker/docker-compose.yml](docker/docker-compose.yml)을 씁니다.

`http://<호스트>:9000`에 접속하면 최초 관리자 계정을 만드는 화면이 나옵니다. 관리 화면은 인터넷에 그대로 열지 말고 사내망·VPN에서 쓰거나 HTTPS 프록시 뒤에 두세요([보안 점검](docs/security.md)).

| 볼륨 | 내용 |
|------|------|
| `/etc/nginx` | nginx 설정, 인증서(`certs/`) |
| `/var/log/nginx` | 접근·오류 로그(사이트별 로그는 `sites/`) |
| `/data` | 관리도구 DB(SQLite), ACME 계정 키 |

처음 시작할 때 `/etc/nginx`에 이미 있는 설정을 가져와 첫 버전으로 남깁니다. 이미지를 바꿔도 볼륨의 데이터는 그대로입니다.

### 설정(환경 변수)

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `NGINX_MANAGER_ACME_DIRECTORY_URL` | (비움) | 지정하면 화면의 Let's Encrypt 운영/스테이징 선택 대신 이 ACME 서버를 씁니다. |
| `NGINX_MANAGER_CERT_CHECK_INTERVAL` | `PT6H` | 갱신할 인증서를 찾는 주기 |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | | 메일 알림을 쓸 때 SMTP 서버 |
| `SERVER_SERVLET_SESSION_COOKIE_SECURE` | `false` | 관리 화면을 HTTPS 뒤에 두면 `true` |

Let's Encrypt는 HTTP-01 방식으로 인증하므로 도메인이 이 컨테이너의 80 포트로 들어와야 합니다. 처음에는 화면의 설정에서 스테이징으로 시험해 보는 것을 권합니다.

## 업그레이드와 되돌리기

이미지 태그는 `<관리도구 버전>-nginx<nginx 버전>`(예: `0.1.0-nginx1.31.6`)과 `latest`입니다. nginx 새 릴리스가 나오면 이미지가 자동으로 다시 빌드·테스트·게시되고, 이전 태그는 그대로 남습니다.

```bash
docker pull ghcr.io/nginx-manager/app:latest
docker rm -f nginx-manager
docker run -d --name nginx-manager ... ghcr.io/nginx-manager/app:latest   # 같은 볼륨으로
```

- 새 이미지는 시작할 때 nginx를 띄우기 전에 볼륨의 설정을 검증합니다. 새 nginx가 설정을 받아들이지 않으면 nginx는 띄우지 않고 관리 화면만 뜹니다. 대시보드가 문제의 파일·줄과 이전/현재 버전을 보여 줍니다.
- 고치는 방법은 둘입니다. 화면에서 설정을 고쳐 적용한 뒤 대시보드의 "nginx 시작"을 누르거나, 이전 태그로 컨테이너를 다시 띄웁니다(볼륨은 그대로 씁니다).
- 버전이 바뀌면 대시보드와 감사 로그에 남습니다.

설정 자체를 되돌릴 때는 이력 화면에서 원하는 버전의 "되돌리기"를 씁니다.

## 구성

- 컨테이너 안에서 [s6-overlay](https://github.com/just-containers/s6-overlay)가 nginx와 관리도구를 각각 감시하고, 죽으면 다시 띄웁니다. 관리도구가 멈춰도 nginx는 계속 서비스합니다.
- 두 프로세스 모두 비루트 사용자 `nginxm`으로 돕니다. nginx 실행 파일에만 80/443 포트를 열 권한을 줍니다.
- 관리도구는 nginx를 고정된 명령(`nginx -t`, `nginx -s reload`, `nginx -V`, `s6-svc -u`)으로만 다룹니다.
- 인증서는 발급·갱신마다 `/etc/nginx/certs/<이름>/<시각>/`에 새로 쓰고 설정의 경로만 바꿔 적용합니다. 그래서 인증서 교체도 이력에 남고 되돌릴 수 있습니다.

## 개발

Java 21, Maven 3.9, Docker가 필요합니다.

```bash
mvn verify                       # 단위·통합 테스트(실제 ACME 발급 테스트는 Docker로 Pebble을 띄움)
mvn spotless:apply               # 포맷 맞추기(verify가 검사함). 컴파일 때는 Error Prone이 흔한 실수를 막습니다
docker build -f docker/Dockerfile -t nginx-manager/app:dev .
mvn -Pimage-test test -Dimage.name=nginx-manager/app:dev   # 이미지 테스트(실제 nginx로 적용·HTTPS·업그레이드 시나리오)
```

원문 편집기 번들(`src/main/resources/static/js/editor.bundle.js`)은 저장소에 함께 둡니다. 바꿀 때만 다시 만듭니다.

```bash
cd frontend && npm ci && npm run build
```

데이터 접근 규칙(constitution):

- 동적 조건·조인·프로젝션 조회는 Querydsl로 작성합니다. 문자열 JPQL, 네이티브 쿼리, `@Query`는 쓰지 않습니다(`ArchitectureRulesTest`가 막습니다).
- 연관관계는 모두 `fetch = LAZY`, `open-in-view=false`입니다.
- 목록 조회마다 `QueryCounter.assertConstantQueries`로 데이터 건수와 상관없이 쿼리 수가 같은지 확인합니다.

릴리스: `v<버전>` 태그를 밀면 그 버전과 최신 nginx로 이미지를 게시합니다. 이후 nginx 릴리스는 매일 확인해 자동으로 게시합니다.
