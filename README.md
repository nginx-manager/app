# nginx manager

브라우저에서 nginx를 관리하는 도구입니다. nginx(오픈소스, 전체 모듈 빌드)와 관리도구(Spring Boot)가 Docker 이미지 하나에 들어 있습니다.

명세와 설계는 [nginx-manager/docs](https://github.com/nginx-manager/docs)의 `specs/001-nginx-web-manager/`에 있습니다(Spec Kit).

## 실행

```bash
docker build -f docker/Dockerfile -t nginx-manager/app .
docker run -d --name nginx-manager \
  -p 80:80 -p 443:443 -p 9000:9000 \
  -v nm-conf:/etc/nginx -v nm-logs:/var/log/nginx -v nm-data:/data \
  nginx-manager/app
```

`http://<호스트>:9000`에 접속하면 최초 관리자 계정을 만드는 화면이 나옵니다.

| 볼륨 | 내용 |
|------|------|
| `/etc/nginx` | nginx 설정, 인증서(`certs/`) |
| `/var/log/nginx` | 접근·오류 로그 |
| `/data` | 관리도구 DB(SQLite) |

이미지를 새 버전으로 바꿔도 볼륨의 데이터는 그대로 남습니다.

## 구성

- 컨테이너 안에서 [s6-overlay](https://github.com/just-containers/s6-overlay)가 nginx와 관리도구를 각각 감시하고, 죽으면 다시 띄웁니다. 관리도구가 멈춰도 nginx는 계속 서비스합니다.
- 두 프로세스 모두 비루트 사용자 `nginxm`으로 돕니다. nginx 실행 파일에만 80/443 포트를 열 권한을 줍니다.
- 관리도구는 nginx를 고정된 명령(`nginx -t`, `nginx -s reload`, `nginx -V`)으로만 다룹니다.

## 개발

Java 21, Maven 3.9가 필요합니다.

```bash
mvn verify                       # 단위·통합 테스트 (메모리 SQLite)
docker build -f docker/Dockerfile -t nginx-manager/app:dev .
mvn -Pimage-test test -Dimage.name=nginx-manager/app:dev   # 이미지 테스트
```

데이터 접근 규칙(constitution):

- 동적 조건·조인·프로젝션 조회는 Querydsl로 작성합니다. 문자열 JPQL, 네이티브 쿼리, `@Query`는 쓰지 않습니다(`ArchitectureRulesTest`가 막습니다).
- 연관관계는 모두 `fetch = LAZY`, `open-in-view=false`입니다.
- 목록 조회마다 `QueryCounter.assertConstantQueries`로 데이터 건수와 상관없이 쿼리 수가 같은지 확인합니다.
