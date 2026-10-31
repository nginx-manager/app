#!/bin/sh
# 설치 묶음 스모크 테스트(008): 설치 → 다시 설치 → manager만 업그레이드(nginx 무중단) → 실패한 업그레이드 되돌림
# → nginx 이미지 업그레이드(새 nginx가 설정을 거부하면 바꾸지 않음) → 백업·복원.
#
# 이미지는 미리 만들어 둔다(CI는 방금 빌드한 이미지에 태그를 붙인다). 받지 않고 로컬 이미지를 쓴다.
#   OLD=1.2.0 NEW=1.2.1 BROKEN=1.2.2 OLD_NGINX=1.2.0-nginx1.31.8 NEW_NGINX=1.2.1-nginx1.31.9 deploy/test/smoke.sh
# BROKEN은 시작하자마자 죽는 manager 이미지의 태그다.
set -eu

: "${OLD:?}" "${NEW:?}" "${BROKEN:?}" "${OLD_NGINX:?}" "${NEW_NGINX:?}"
here="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
dir="$work/nm"
export NM_SKIP_PULL=1 NM_WAIT_TIMEOUT="${NM_WAIT_TIMEOUT:-120}"
# 자동 백업 스케줄(015)은 호스트의 /etc 대신 여기에 쓴다
export NM_CRON_DIR="$work/cron"
mkdir -p "$NM_CRON_DIR"
HTTP=18080
failures=0

step() { printf '\n== %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; failures=$((failures + 1)); }
check() { # 설명 명령...
    what="$1"; shift
    if "$@"; then printf 'ok: %s\n' "$what"; else fail "$what"; fi
}
started_at() { docker inspect -f '{{.State.StartedAt}}' "$(cd "$dir" && docker compose ps -q "$1")"; }
env_of() { sed -n "s/^$1=//p" "$dir/.env"; }

cleanup() {
    if [ -d "$dir" ]; then
        (cd "$dir" && docker compose down -v --remove-orphans >/dev/null 2>&1) || true
    fi
    rm -rf "$work" 2>/dev/null || docker run --rm -v "$work:/w" alpine:3.20 rm -rf /w/nm >/dev/null 2>&1 || true
}
trap cleanup EXIT

for v in "$OLD:$OLD_NGINX" "$NEW:$NEW_NGINX" "$BROKEN:$NEW_NGINX"; do
    "$here/build-bundle.sh" "${v%%:*}" "${v#*:}" "$work/b-${v%%:*}" >/dev/null
done

step "설치"
sh "$work/b-$OLD/install.sh" --dir "$dir" --bundle "$work/b-$OLD/nginx-manager.tar.gz" \
    --http-port "$HTTP" --https-port 18443 --manager-port 19000
check "두 컨테이너가 healthy" sh -c "cd '$dir' && [ \"\$(docker compose ps --format '{{.Health}}' | grep -c healthy)\" = 2 ]"
check ".env에 버전과 포트" sh -c "grep -q '^MANAGER_VERSION=$OLD\$' '$dir/.env' && grep -q '^NGINX_MANAGER_PORT=19000\$' '$dir/.env'"
check ".env는 다른 사용자가 읽지 못한다" sh -c "[ \"\$(stat -c %a '$dir/.env')\" = 600 ]"
check "관리 화면은 127.0.0.1에만" sh -c "cd '$dir' && docker compose port manager 9000 | grep -q '^127.0.0.1:19000\$'"
check "nginx가 응답" sh -c "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:$HTTP/ | grep -qv '^000\$'"
check "매일 03:30 자동 백업 스케줄" grep -q "^30 3 \* \* \* root $dir/nm backup --label auto" "$NM_CRON_DIR/nginx-manager-backup"

step "다시 설치하면 덮어쓰지 않는다"
echo "# keep" >> "$dir/.env"
sh "$work/b-$OLD/install.sh" --dir "$dir" --bundle "$work/b-$OLD/nginx-manager.tar.gz" >/dev/null
check ".env 유지" grep -q '^# keep$' "$dir/.env"

step "manager만 업그레이드: nginx는 끊기지 않는다"
sed -i '/^NGINX_MANAGER_BACKUP_KEEP=/d' "$dir/.env"   # 새 버전에서 생긴 키인 셈
nginx_before="$(started_at nginx)"
: > "$work/codes"
( while [ ! -f "$work/stop" ]; do curl -s -o /dev/null -m 2 -w '%{http_code}\n' "http://127.0.0.1:$HTTP/" >> "$work/codes"; done ) &
loader=$!
"$dir/nm" upgrade --bundle "$work/b-$NEW/nginx-manager.tar.gz"
touch "$work/stop"; wait "$loader"
check "MANAGER_VERSION=$NEW" [ "$(env_of MANAGER_VERSION)" = "$NEW" ]
check "nginx 이미지는 그대로" [ "$(env_of NGINX_IMAGE)" = "$OLD_NGINX" ]
check "nginx 컨테이너가 다시 시작하지 않음" [ "$(started_at nginx)" = "$nginx_before" ]
check "업그레이드 중 요청 실패 0 ($(wc -l < "$work/codes")개 요청)" sh -c "! grep -q '^000\$' '$work/codes'"
check "빠진 .env 키를 기본값으로 더함" grep -q '^NGINX_MANAGER_BACKUP_KEEP=14$' "$dir/.env"
check "이미 있던 .env 값은 그대로" grep -q '^# keep$' "$dir/.env"
check "업그레이드 전 백업" sh -c "ls '$dir'/backups/*before-v$NEW* >/dev/null"

step "새 manager가 뜨지 않으면 되돌린다"
if "$dir/nm" upgrade --bundle "$work/b-$BROKEN/nginx-manager.tar.gz"; then
    fail "망가진 업그레이드가 성공으로 끝남"
fi
check "MANAGER_VERSION이 $NEW 로 돌아옴" [ "$(env_of MANAGER_VERSION)" = "$NEW" ]
check "manager가 다시 healthy" sh -c "cd '$dir' && docker compose ps manager --format '{{.Health}}' | grep -q healthy"
check "nginx 컨테이너는 그대로" [ "$(started_at nginx)" = "$nginx_before" ]

step "nginx 이미지 업그레이드: 새 nginx가 설정을 거부하면 바꾸지 않는다"
"$dir/nm" upgrade --bundle "$work/b-$NEW/nginx-manager.tar.gz" >/dev/null 2>&1 || true
printf 'this is not nginx config;\n' > "$work/bad.conf"
docker run --rm -v "$dir/data/conf:/c" -v "$work/bad.conf:/bad.conf:ro" alpine:3.20 \
    sh -c 'touch /c/BAD && mkdir -p /c/conf.d/sites && cp /bad.conf /c/conf.d/sites/zz-bad.conf'
if "$dir/nm" upgrade --nginx --bundle "$work/b-$NEW/nginx-manager.tar.gz"; then
    fail "틀린 설정인데 nginx 이미지를 바꿈"
fi
check "nginx 이미지는 그대로" [ "$(env_of NGINX_IMAGE)" = "$OLD_NGINX" ]
docker run --rm -v "$dir/data/conf:/c" alpine:3.20 rm -f /c/BAD /c/conf.d/sites/zz-bad.conf
# 사전 검사는 지금 nginx와 똑같이 이름을 푼다: compose.override.yml의 extra_hosts로만 풀리는
# upstream 이름(v1.9.2에서 고침)이 있어도 거부하지 않는다
cat > "$dir/compose.override.yml" <<'YML'
services:
  nginx:
    extra_hosts:
      - "minio1.internal:127.0.0.1"
YML
(cd "$dir" && docker compose up -d --wait nginx >/dev/null)
printf 'upstream zz_internal {\n    server minio1.internal:9000;\n}\n' > "$work/internal.conf"
docker run --rm -v "$dir/data/conf:/c" -v "$work/internal.conf:/i.conf:ro" alpine:3.20 \
    sh -c 'mkdir -p /c/conf.d/sites && cp /i.conf /c/conf.d/sites/zz-internal.conf'
"$dir/nm" upgrade --nginx --bundle "$work/b-$NEW/nginx-manager.tar.gz"
check "nginx 이미지가 $NEW_NGINX" [ "$(env_of NGINX_IMAGE)" = "$NEW_NGINX" ]
docker run --rm -v "$dir/data/conf:/c" alpine:3.20 rm -f /c/conf.d/sites/zz-internal.conf
rm -f "$dir/compose.override.yml"
check "nginx가 healthy" sh -c "cd '$dir' && docker compose ps nginx --format '{{.Health}}' | grep -q healthy"

step "가져오기 디렉터리: 두 컨테이너에 같은 경로로 붙이고 읽기 권한을 확인한다"
home="$work/home"
mkdir -p "$home/alice/nginx" "$home/bob/nginx" "$home/alice/www"
printf 'server { listen 80; server_name a.example.com; root %s; }\n' "$home/alice/www" > "$home/alice/nginx/a.conf"
printf 'server { listen 80; server_name b.example.com; }\n' > "$home/bob/nginx/b.conf"
chmod 700 "$home/bob" "$home/alice"
chmod 755 "$work"
"$dir/nm" import-root add "$home" || true
check "compose.import.yml이 생김" [ -f "$dir/compose.import.yml" ]
check ".env에 가져오기 디렉터리" grep -q "^NGINX_MANAGER_IMPORT_ROOTS=$home\$" "$dir/.env"
for svc in manager nginx; do
    check "$svc 에 같은 경로로 붙음" sh -c "docker inspect -f '{{range .Mounts}}{{.Destination}} {{end}}' \"\$(cd '$dir' && docker compose ps -q $svc)\" | grep -q '$home'"
done
if "$dir/nm" import-root check "$home/*/nginx/*.conf" > "$work/check.out" 2>&1; then
    fail "UID 10101이 못 읽는 홈 디렉터리를 알리지 않음"
fi
check "못 읽는 파일을 알림" grep -q "$home/bob/nginx/b.conf" "$work/check.out"
check "가리키는 root 경로도 확인" grep -q "$home/alice/www" "$work/check.out"
if command -v setfacl >/dev/null 2>&1 && { [ "$(id -u)" = 0 ] || sudo -n true 2>/dev/null; }; then
    sudo_="$( [ "$(id -u)" = 0 ] || echo sudo -n )"
    $sudo_ "$dir/nm" import-root grant "$home/alice/nginx" "$home/alice/www" "$home/bob/nginx"
    check "권한을 준 뒤에는 모두 읽힘" "$dir/nm" import-root check "$home/*/nginx/*.conf"
    check "다른 사용자에게는 열지 않음" sh -c "stat -c %A '$home/bob' | grep -q -- '---\$'"
else
    printf 'skip: setfacl이나 sudo가 없어 grant는 건너뜀\n'
fi

step "Certbot 인증서 디렉터리를 .env로 붙이고, 링크와 600 키를 확인한다"
le="$work/letsencrypt"
mkdir -p "$le/archive/c.example.com" "$le/live/c.example.com"
echo cert > "$le/archive/c.example.com/fullchain1.pem"
echo key > "$le/archive/c.example.com/privkey1.pem"
ln -s ../../archive/c.example.com/fullchain1.pem "$le/live/c.example.com/fullchain.pem"
ln -s ../../archive/c.example.com/privkey1.pem "$le/live/c.example.com/privkey.pem"
chmod 700 "$le/archive" "$le/live"
chmod 600 "$le/archive/c.example.com/privkey1.pem"
printf 'server { listen 443 ssl; server_name c.example.com;\n  ssl_certificate %s;\n  ssl_certificate_key %s;\n}\n' \
    "$le/live/c.example.com/fullchain.pem" "$le/live/c.example.com/privkey.pem" > "$home/alice/nginx/c.conf"
sed -i "s|^NGINX_MANAGER_IMPORT_ROOTS=.*|NGINX_MANAGER_IMPORT_ROOTS=$home,$le|" "$dir/.env"
"$dir/nm" up >/dev/null 2>&1 || true
check ".env만 고쳐도 compose.import.yml에 들어감" grep -q "$le:$le:ro" "$dir/compose.import.yml"
check "nginx에 인증서 디렉터리가 붙음" sh -c "docker inspect -f '{{range .Mounts}}{{.Destination}} {{end}}' \"\$(cd '$dir' && docker compose ps -q nginx)\" | grep -q '$le'"
"$dir/nm" import-root check "$home/alice/nginx/c.conf" > "$work/le.out" 2>&1 || true
check "링크 너머 600 키를 못 읽는다고 알림" grep -q "$le/live/c.example.com/privkey.pem" "$work/le.out"
if command -v setfacl >/dev/null 2>&1 && { [ "$(id -u)" = 0 ] || sudo -n true 2>/dev/null; }; then
    sudo_="$( [ "$(id -u)" = 0 ] || echo sudo -n )"
    $sudo_ "$dir/nm" import-root grant "$le/live" "$le/archive"
    check "권한을 준 뒤 인증서와 키가 읽힘" "$dir/nm" import-root check "$home/alice/nginx/c.conf"
fi
check "nm reload" "$dir/nm" reload
rm -f "$home/alice/nginx/c.conf"

step "관리 도메인 점검: DNS에 없는 도메인을 알린다"
cp "$dir/.env" "$work/env.bak"
sed -i 's/^NGINX_MANAGER_ADMIN_DOMAIN=.*/NGINX_MANAGER_ADMIN_DOMAIN=nm-typo.example.invalid/' "$dir/.env"
if "$dir/nm" check-domain > "$work/domain.out" 2>&1; then
    fail "없는 도메인을 통과시킴"
fi
check "DNS에서 찾지 못했다고 알림" grep -q "DNS에서 찾지 못했습니다" "$work/domain.out"
cat "$work/env.bak" > "$dir/.env"

step "백업과 복원"
docker run --rm -v "$dir/data/conf:/c" alpine:3.20 sh -c 'mkdir -p /c/conf.d/sites && echo "# marker" > /c/conf.d/sites/zz-marker.conf'
"$dir/nm" backup --keep 3
backup="$(ls -1t "$dir"/backups/nginx-manager-*.tar.gz | head -n1)"
check "백업 파일 권한 600" [ "$(stat -c %a "$backup")" = 600 ]
check "보관 수 3" [ "$(ls -1 "$dir"/backups/nginx-manager-*.tar.gz | wc -l)" -le 3 ]
check "백업 상태를 관리 화면에 남김" sh -c "grep -qx 'result=ok' '$dir/data/manager/backup-status' && grep -qx \"file=\$(basename '$backup')\" '$dir/data/manager/backup-status'"
docker run --rm -v "$dir/data/conf:/c" alpine:3.20 rm -f /c/conf.d/sites/zz-marker.conf
"$dir/nm" restore "$backup" -y
check "복원한 파일이 돌아옴" [ -f "$dir/data/conf/conf.d/sites/zz-marker.conf" ]
check "백업에 compose.import.yml" sh -c "tar -tzf '$backup' | grep -qx compose.import.yml"
check "복원 전 데이터도 남김" sh -c "ls '$dir'/backups/*before-restore* >/dev/null"
check "두 컨테이너가 healthy" sh -c "cd '$dir' && [ \"\$(docker compose ps --format '{{.Health}}' | grep -c healthy)\" = 2 ]"

if [ "$failures" -gt 0 ]; then
    printf '\n%s개 실패\n' "$failures" >&2
    exit 1
fi
printf '\n모두 통과\n'
