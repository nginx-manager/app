# 설명서 관리

사용 설명서는 늘 최신 기능까지 담습니다. 관리 화면 footer의 **사용 설명서** 링크가 `main`의 이 문서를 열기 때문에, 설명서가 늦으면 사용자가 바로 알아챕니다.

## 언제 고치나

사용자가 보는 것이 바뀌는 PR은 **같은 PR에서** 설명서도 고칩니다.

- 새 화면, 새 메뉴, 새 단추나 입력 칸
- 폼 항목·검증 규칙·기본값이 바뀜
- 새 환경 변수, compose 파일·포트·볼륨 변경
- 동작이 바뀌어 지금 설명이 틀리게 되는 경우(예: 적용 순서, 권한)

내부 구현만 바뀌거나 화면 문구를 조금 다듬은 것은 고치지 않아도 됩니다. PR 템플릿의 체크 항목에서 둘 중 하나를 고릅니다.

## 어떻게 고치나

1. 바뀐 기능이 속한 장을 고칩니다. 새 기능이 어느 장에도 맞지 않으면 새 장(`NN-이름.md`)을 만들고 [차례](README.md)와 앞뒤 장의 이동 링크에 더합니다.
2. 버튼·메뉴 이름은 화면 문구(`src/main/resources/messages.properties`)와 똑같이 씁니다.
3. 새 버전에서 생긴 기능이면 그 문단에 `(vX.Y.Z부터)`를 붙이고, [차례](README.md)의 기준 버전을 릴리스 때 올립니다.
4. 화면이 바뀌었으면 그림을 다시 찍습니다(아래).

문체는 릴리스 노트와 같습니다. 사용자가 화면에서 무엇을 하는지를 먼저, 짧은 문장으로 씁니다.

## 그림 다시 찍기

[scripts/manual-screenshots.mjs](../../scripts/manual-screenshots.mjs)가 빈 볼륨으로 띄운 관리도구에 예시 사이트·스트림·인증서·사용자를 만들며 설명서의 모든 그림을 `docs/manual/images/`에 찍습니다. 새 화면을 설명서에 넣으면 이 스크립트에도 찍는 단계를 더합니다.

```bash
# 이미지 빌드(릴리스 노트 절차와 같음)
docker build -f docker/nginx/Dockerfile --build-arg APP_VERSION=X.Y.Z -t ghcr.io/nginx-manager/nginx:X.Y.Z .
docker build -f docker/manager/Dockerfile --build-arg APP_VERSION=X.Y.Z -t ghcr.io/nginx-manager/app:X.Y.Z .

# 설정 파일 가져오기 화면용 예시 파일: 호스트 디렉터리를 manager에 /home으로 마운트한다
mkdir -p /tmp/nm-home/alice/nginx /tmp/nm-home/bob/nginx
# alice/nginx/blog.conf: 폼으로 옮길 수 있는 프록시 server 하나
# bob/nginx/shop.conf: location이 둘이라 원문 사이트가 되는 server
cat > /tmp/nm-override.yml <<'YAML'
services:
  manager:
    volumes: ["/tmp/nm-home:/home:ro"]
    environment: { NGINX_MANAGER_IMPORT_ROOTS: /home }
YAML

NGINX_MANAGER_VERSION=X.Y.Z docker compose -f docker/docker-compose.yml -f /tmp/nm-override.yml -p nm-manual up -d --wait
TRAFFIC_URL=http://localhost IMPORT_PATTERN='/home/*/nginx/*.conf' \
  node scripts/manual-screenshots.mjs http://localhost:9000 docs/manual/images
docker compose -f docker/docker-compose.yml -f /tmp/nm-override.yml -p nm-manual down -v
```

스크립트에는 [playwright](https://playwright.dev) 패키지(`npm i -g playwright`, 전역 설치면 `NODE_PATH` 지정)와 `openssl`이 필요합니다. 그림은 2배 해상도로 찍히고, 설명서에서는 `width`로 1배 크기에 맞춰 보여 줍니다.

## 릴리스할 때

[릴리스 절차](../releases/README.md#릴리스-절차)의 노트를 쓸 때, 이번 버전의 기능이 설명서에 모두 들어갔는지 확인하고 [차례](README.md)의 기준 버전을 올립니다.
