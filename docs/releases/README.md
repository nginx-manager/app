# 릴리스 노트

nginx manager의 버전별 변경 내용입니다. 최신 버전이 위에 있습니다. 지금 쓰는 버전은 관리 화면 아래(footer)에 보이고, 누르면 그 버전의 노트가 열립니다.

| 버전 | 날짜 | 주요 내용 | 릴리스 노트 |
| --- | --- | --- | --- |
| v1.2.1 | 2026-10-30 | 관리 도메인 점검(`nm check-domain`), 인증서를 받으면 바로 HTTPS, 적용 실패 이유를 화면에 표시, Certbot 인증서를 그대로 쓰기(`/etc/letsencrypt` 붙이기, `nm reload`) | [v1.2.1](v1.2.1.md) |
| v1.2.0 | 2026-10-30 | 서버에 한 줄로 설치(`install.sh`, `nm` 명령, 관리 도메인 HTTPS, `nm import-root`), 사용자별 사이트 관리(관리자·멤버 역할, 사이트 담당자), 새 로그인·최초 설정 화면, 관리 화면 안에서 보는 사용 설명서 13장, 브라우저 탭 아이콘 | [v1.2.0](v1.2.0.md) |
| v1.1.0 | 2026-10-29 | 쓰던 nginx 설정 파일을 사이트로 가져오기: 경로 패턴(`/home/*/nginx/*.conf`)으로 찾아 한꺼번에 등록, 폼/원문 자동 판단, 원본 바뀜 알림과 다시 가져오기. 화면 다듬기: 고른 유형만 보이는 사이트 폼, 놓치지 않는 미리보기, 대상이 보이는 사이트 목록, 접히는 적용 diff, 휴대폰 메뉴 버튼 | [v1.1.0](v1.1.0.md) |
| v1.0.0 | 2026-10-29 | 첫 정식 릴리스: 폼으로 사이트·TCP/UDP 스트림 만들기, 검증 후 적용과 자동 복구, 이력·비교·되돌리기, Let's Encrypt 자동 발급·갱신, 대시보드와 로그, 4개 언어·다크 모드, 사용자·감사 로그, 컨테이너 두 개와 업그레이드 안전장치 | [v1.0.0](v1.0.0.md) |

## 버전 규칙

[유의적 버전](https://semver.org/lang/ko/)(`MAJOR.MINOR.PATCH`)을 씁니다.

- 버전 관리는 1.0.0부터 시작합니다.
- 기존 설정·볼륨을 그대로 못 쓰게 바뀌면 MAJOR, 기능을 더하면 MINOR, 고치기만 하면 PATCH를 올립니다.
- **버전의 기준은 git 태그(`vX.Y.Z`)** 입니다. 소스에는 버전 번호를 적지 않습니다. 태그를 밀면 릴리스 워크플로가 태그에서 버전을 읽어 두 이미지를 빌드하고(`APP_VERSION` 빌드 인자 → 관리도구 jar 빌드 정보, nginx 에이전트, 이미지 라벨), 태그 없이 빌드한 이미지는 `dev`입니다.
- 관리 화면 footer는 빌드에 들어간 이 버전을 보여 줍니다. 정식 버전은 그 버전의 노트로, `dev`나 시험 태그(`v1.1.0-rc1`)는 이 목록으로 연결됩니다.
- 관리 화면 이미지(`ghcr.io/nginx-manager/app`)와 nginx 이미지(`ghcr.io/nginx-manager/nginx`)는 같은 버전으로 게시합니다. nginx 이미지에는 `<관리도구 버전>-nginx<nginx 버전>`(예: `1.0.0-nginx1.31.6`) 태그도 붙습니다. nginx만 바뀌어 다시 게시할 때는 관리도구 버전을 올리지 않습니다.

## 릴리스 절차

1. **README 고치기**: 이번 버전에서 바뀐 설치 방법, `.env` 설정, 기능, 화면을 이 저장소의 [README.md](../../README.md)와 조직 소개(nginx-manager/.github의 `profile/README.md`)에 반영합니다. 소개 페이지의 그림(`profile/images/`)도 새 화면으로 바꿉니다. 바뀐 것이 없어도 두 README를 읽어 보고 맞는지 확인합니다.
1. **노트 쓰기**: [TEMPLATE.md](TEMPLATE.md)를 복사해 `vX.Y.Z.md`를 쓰고, 이 표 맨 위에 한 줄을 더합니다. 이번 버전의 기능이 [사용 설명서](../../src/main/resources/guide/ko/README.md)(관리 화면의 `/guide`)에 모두 들어갔는지 확인하고, 설명서 첫 화면의 기준 버전을 올립니다.
2. **화면 찍기**: 릴리스할 커밋으로 두 이미지를 빌드해 빈 볼륨으로 띄우고 스크립트로 찍습니다. 그림은 2배 해상도로 찍히고, 노트에서는 `width`로 1배 크기에 맞춰 보여 줍니다.
   ```bash
   docker build -f docker/nginx/Dockerfile --build-arg APP_VERSION=X.Y.Z -t ghcr.io/nginx-manager/nginx:X.Y.Z .
   docker build -f docker/manager/Dockerfile --build-arg APP_VERSION=X.Y.Z -t ghcr.io/nginx-manager/app:X.Y.Z .
   NGINX_MANAGER_VERSION=X.Y.Z docker compose -f docker/docker-compose.yml -p nm-shots up -d --wait
   TRAFFIC_URL=http://localhost node scripts/release-screenshots.mjs http://localhost:9000 docs/releases/images/vX.Y.Z
   docker compose -f docker/docker-compose.yml -p nm-shots down -v
   ```
   스크립트에는 [playwright](https://playwright.dev) 패키지가 필요합니다(`npm i -g playwright`, 전역 설치면 `NODE_PATH`를 지정). `APP_VERSION`을 주고 빌드해야 화면에 `vX.Y.Z`가 보입니다. 설정 파일 가져오기 화면도 찍으려면 예시 설정 파일을 둔 호스트 디렉터리를 compose의 `manager`에 `/home`으로 읽기 전용 마운트하고 `NGINX_MANAGER_IMPORT_ROOTS: /home`을 준 뒤, 스크립트에 `IMPORT_PATTERN='/home/*/nginx/*.conf'`를 줍니다. 마지막에 휴대폰 폭(390px) 화면도 찍습니다.
3. **태그 달기**: 노트를 main에 합친 뒤 그 커밋에 `vX.Y.Z` 태그를 밀면 [이미지 릴리스 워크플로](../../.github/workflows/nginx-release.yml)가 버전을 `X.Y.Z`로 정해 빌드·테스트·게시합니다.
   ```bash
   git tag -a vX.Y.Z -m "vX.Y.Z" && git push origin vX.Y.Z
   ```
   태그를 밀 수 없으면 Actions의 `Release images`에서 Run workflow를 main으로 고르고 `release`에 `vX.Y.Z`를 넣어 돌립니다. 워크플로가 main의 마지막 커밋에 태그를 달고 이어서 게시합니다.
4. **소개 페이지**: 조직 소개([nginx-manager/.github](https://github.com/nginx-manager/.github)의 `profile/README.md`)의 릴리스 표와 release·nginx 배지는 그 저장소의 `Sync releases` 워크플로가 이 표(날짜가 적힌 줄)와 최신 노트의 nginx 버전을 읽어 한 시간 안에 맞춥니다. 바로 반영하려면 그 저장소 Actions에서 Run workflow를 누릅니다.
