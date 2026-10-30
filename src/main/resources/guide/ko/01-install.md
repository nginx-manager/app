# 1. 설치와 실행

nginx manager는 컨테이너 두 개로 돕니다. nginx가 도는 컨테이너와 관리 화면이 도는 컨테이너가 나뉘어 있어서, 관리 화면을 업데이트하거나 다시 띄워도 nginx는 트래픽을 계속 받습니다.

| 컨테이너 | 이미지 | 하는 일 |
| --- | --- | --- |
| `nginx` | `ghcr.io/nginx-manager/nginx` | nginx(80/443)와 에이전트. 에이전트는 관리 화면의 요청으로 `nginx -t`·reload만 실행합니다. |
| `manager` | `ghcr.io/nginx-manager/app` | 관리 화면(9000). 설정 파일을 쓰고, 검증·reload는 에이전트에 맡깁니다. |

## 준비물

- Docker와 Docker Compose(v2, `docker compose` 명령)
- 서버의 80, 443(TCP·UDP), 9000 포트
- Let's Encrypt 인증서를 받으려면 도메인이 이 서버를 가리키고 인터넷에서 80 포트로 들어올 수 있어야 합니다.

## 띄우기

compose 파일을 받아 띄웁니다. 컨테이너 두 개와 볼륨, 에이전트 토큰이 모두 들어 있어 따로 정할 것은 없습니다.

```bash
curl -fsSLO https://raw.githubusercontent.com/nginx-manager/app/main/docker/docker-compose.yml
docker compose up -d
```

`docker compose ps`에서 두 컨테이너가 `running`(nginx는 `healthy`)이면 브라우저로 `http://<서버 주소>:9000`에 접속합니다. 처음 접속하면 관리자 계정을 만드는 화면이 나옵니다([2장](02-getting-started.md)).

> **관리 화면을 인터넷에 그대로 열지 마세요.** 관리 화면은 HTTP로 뜹니다. 사내망·VPN에서만 쓰거나, compose의 포트를 `127.0.0.1:9000:9000`으로 묶고 HTTPS 프록시 뒤에 두세요. HTTPS 뒤에 두면 `SERVER_SERVLET_SESSION_COOKIE_SECURE: "true"`를 함께 줍니다.

처음 시작할 때 `/etc/nginx`에 이미 있는 설정을 가져와 첫 버전(**처음 가져오기**)으로 남깁니다.

## 포트

| 포트 | 컨테이너 | 용도 |
| --- | --- | --- |
| 80 | nginx | HTTP 사이트, Let's Encrypt 인증(HTTP-01) |
| 443 (TCP) | nginx | HTTPS 사이트 |
| 443 (UDP) | nginx | HTTP/3(QUIC) |
| 9000 | manager | 관리 화면 |
| 7070, 8081 | nginx | 에이전트, 상태 확인. 호스트에 열지 않고 컨테이너끼리만 씁니다. |

[스트림](06-streams.md)으로 만든 포트(예: TCP 5432)를 밖에서 쓰려면 compose 파일의 `nginx` 서비스 `ports`에 그 포트를 더하고(`"5432:5432"`, UDP는 `"53:53/udp"`) `docker compose up -d nginx`로 다시 띄웁니다.

## 볼륨

| 볼륨 | 마운트 | 내용 |
| --- | --- | --- |
| `nm-conf` | 둘 다 `/etc/nginx` | nginx 설정, 인증서(`certs/`) |
| `nm-logs` | 둘 다 `/var/log/nginx` | 접근·오류 로그(사이트별 로그는 `sites/`) |
| `nm-shared` | 둘 다 `/var/lib/nginx-manager` | Let's Encrypt 챌린지 파일, 에이전트 토큰 |
| `nm-data` | manager `/data` | 관리도구 DB(SQLite), ACME 계정 키 |

공유 볼륨은 두 컨테이너에서 같은 경로에 둡니다. 이미지를 바꿔도 볼륨의 데이터는 그대로입니다. 백업은 네 볼륨을 함께 받아 두면 됩니다.

## 환경 변수

compose 파일의 `environment`에 적습니다. 대부분은 비워 두어도 됩니다.

| 변수 (manager) | 언제 쓰나 |
| --- | --- |
| `NGINX_MANAGER_IMPORT_ROOTS` | 호스트의 다른 디렉터리(예: `/home`)에서 [설정 파일을 가져올](05-import.md) 때. 그 디렉터리를 같은 경로로 manager에 읽기 전용 마운트(`- /home:/home:ro`)하고 여기에 적습니다. 여럿이면 쉼표로 구분합니다. |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | [메일 알림](12-admin.md#알림-설정)을 보낼 SMTP 서버 |
| `SERVER_SERVLET_SESSION_COOKIE_SECURE` | 관리 화면을 HTTPS 프록시 뒤에 둘 때 `true` |
| `NGINX_MANAGER_ACME_DIRECTORY_URL` | Let's Encrypt 대신 다른 ACME 서버를 쓸 때 |
| `NGINX_MANAGER_CERT_CHECK_INTERVAL` | 갱신할 인증서를 찾는 주기(기본 `PT6H`, 6시간) |
| `NGINX_RUNTIME_AGENT_URL` | 에이전트 주소(기본 `http://nginx:7070`). compose의 nginx 서비스 이름을 바꾸면 함께 바꿉니다. |
| `NGINX_RUNTIME_AGENT_TOKEN` | 비우면 공유 볼륨의 토큰 파일을 읽습니다. 직접 정하면 nginx 컨테이너의 `NGINX_AGENT_TOKEN`과 같게 둡니다. |

| 변수 (nginx) | 언제 쓰나 |
| --- | --- |
| `NGINX_AGENT_TOKEN` | 에이전트 토큰을 직접 정할 때. 비우면 처음 뜰 때 `/var/lib/nginx-manager/agent/token`에 무작위 토큰을 만들어 씁니다. |
| `NGINX_AGENT_LISTEN` | 에이전트가 듣는 주소(기본 `:7070`) |

compose 파일을 고친 뒤에는 `docker compose up -d manager`로 관리 화면만 다시 띄우면 됩니다. nginx는 끊기지 않습니다.

## 업그레이드

두 이미지는 같은 버전으로 맞춥니다. compose 파일의 `NGINX_MANAGER_VERSION` 하나가 두 이미지의 태그를 함께 정합니다(기본 `latest`).

```bash
NGINX_MANAGER_VERSION=1.1.0 docker compose pull
NGINX_MANAGER_VERSION=1.1.0 docker compose up -d
```

- 두 컨테이너의 버전이 다르거나 에이전트에 연결할 수 없으면 모든 화면 위에 경고 띠가 뜹니다.
- 새 nginx 이미지는 nginx를 띄우기 전에 볼륨의 설정을 검증합니다. 새 nginx가 설정을 받아들이지 않으면 nginx를 띄우지 않고, 대시보드에 문제의 파일·줄과 고치는 방법을 보여 줍니다([13장](13-troubleshooting.md#업그레이드-뒤-nginx가-뜨지-않을-때)).
- 버전이 바뀐 사실은 대시보드와 감사 로그에 남습니다.
- 데이터베이스 변경은 관리 화면이 시작할 때 자동으로 적용됩니다.

이전 버전으로 돌아가려면 `NGINX_MANAGER_VERSION`을 이전 값으로 주고 같은 명령을 다시 실행합니다. 설정 자체를 되돌릴 때는 [이력](08-versions.md)의 되돌리기를 씁니다.
