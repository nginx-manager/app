#!/bin/sh
# nginx manager 설치 스크립트.
#
#   curl -fsSL https://github.com/nginx-manager/app/releases/latest/download/install.sh | sh
#   curl -fsSL https://github.com/nginx-manager/app/releases/latest/download/install.sh | sh -s -- \
#       --admin-domain nm.example.com --email ops@example.com
#
# 옵션
#   --dir DIR              설치 디렉터리(기본 /opt/nginx-manager)
#   --version X.Y.Z        설치할 버전(기본 최신 릴리스)
#   --admin-domain DOMAIN  관리 화면을 HTTPS로 열 도메인
#   --email EMAIL          Let's Encrypt 연락 이메일
#   --http-port N          nginx HTTP 포트(기본 80)
#   --https-port N         nginx HTTPS 포트(기본 443)
#   --manager-port N       관리 화면 포트(기본 9000, 이 서버에서만 열림)
#   --bundle FILE          미리 받은 묶음(nginx-manager.tar.gz)으로 설치(인터넷이 막힌 서버)
#   --no-start             파일만 깔고 띄우지 않는다
set -eu

REPO="${NGINX_MANAGER_REPO:-nginx-manager/app}"
DIR=/opt/nginx-manager
VERSION=""
ADMIN_DOMAIN=""
EMAIL=""
HTTP_PORT=80
HTTPS_PORT=443
MANAGER_PORT=9000
BUNDLE=""
START=1

say() { printf '%s\n' "$*"; }
die() { printf '오류: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --dir) shift; DIR="$1" ;;
        --version) shift; VERSION="${1#v}" ;;
        --admin-domain) shift; ADMIN_DOMAIN="$1" ;;
        --email) shift; EMAIL="$1" ;;
        --http-port) shift; HTTP_PORT="$1" ;;
        --https-port) shift; HTTPS_PORT="$1" ;;
        --manager-port) shift; MANAGER_PORT="$1" ;;
        --bundle) shift; BUNDLE="$1" ;;
        --no-start) START=0 ;;
        -h|--help) say "옵션: --dir --version --admin-domain --email --http-port --https-port --manager-port --bundle --no-start"; exit 0 ;;
        *) die "알 수 없는 옵션: $1" ;;
    esac
    shift
done

for port in "$HTTP_PORT" "$HTTPS_PORT" "$MANAGER_PORT"; do
    case "$port" in
        ''|*[!0-9]*) die "포트는 숫자여야 합니다: $port" ;;
    esac
done

# ---------------------------------------------------------------- 점검

if [ -f "$DIR/.env" ]; then
    say "이미 $DIR 에 설치되어 있습니다. .env와 data/는 그대로 둡니다."
    say "업그레이드: $DIR/nm upgrade    상태: $DIR/nm status"
    exit 0
fi

command -v docker >/dev/null 2>&1 \
    || die "Docker가 없습니다. https://docs.docker.com/engine/install/ 를 보고 Docker Engine을 설치한 뒤 다시 실행하세요"
docker compose version >/dev/null 2>&1 \
    || die "Docker Compose v2(docker compose)가 없습니다. https://docs.docker.com/compose/install/linux/ 를 보고 설치하세요"
docker info >/dev/null 2>&1 \
    || die "Docker에 연결할 수 없습니다. Docker가 돌고 있는지, 이 사용자가 docker 그룹에 있는지(또는 sudo로 실행) 확인하세요"
command -v curl >/dev/null 2>&1 || command -v wget >/dev/null 2>&1 || [ -n "$BUNDLE" ] \
    || die "curl이나 wget이 필요합니다"

port_in_use() {
    if command -v ss >/dev/null 2>&1; then
        ss -Hltn "sport = :$1" 2>/dev/null | grep -q .
    elif command -v netstat >/dev/null 2>&1; then
        netstat -ltn 2>/dev/null | awk '{ print $4 }' | grep -Eq "[:.]$1\$"
    else
        return 1
    fi
}
# v1.0·v1.1(compose 파일 하나와 이름 붙은 볼륨)으로 깐 서버
if [ "$START" = 1 ] && docker volume ls -q --filter name=nm-conf 2>/dev/null | grep -q '_nm-conf$'; then
    die "v1.0·v1.1로 깐 nginx manager 볼륨이 있습니다. --no-start로 깐 뒤 $DIR/nm migrate 로 데이터를 옮기세요(사용 설명서 1장 'v1.1 이전 설치 옮기기')"
fi

for port in "$HTTP_PORT" "$HTTPS_PORT" "$MANAGER_PORT"; do
    if [ "$START" = 1 ] && port_in_use "$port"; then
        who="$( (ss -Hltnp "sport = :$port" 2>/dev/null || true) | sed -n 's/.*users:((\("[^"]*"\).*/\1/p' | head -n1)"
        die "포트 $port 를 이미 ${who:-다른 프로그램}이 쓰고 있습니다. 그 프로그램을 멈추거나 --http-port/--https-port/--manager-port로 다른 포트를 고르세요"
    fi
done

parent="$(dirname "$DIR")"
mkdir -p "$DIR" 2>/dev/null || die "$DIR 을 만들 수 없습니다. sudo로 실행하거나 --dir로 쓸 수 있는 곳을 고르세요"
[ -w "$DIR" ] || die "$DIR 에 쓸 수 없습니다. sudo로 실행하세요"
free_kb="$(df -Pk "$parent" | awk 'NR == 2 { print $4 }')"
[ "${free_kb:-0}" -gt 2097152 ] || say "주의: $parent 에 남은 공간이 2GB보다 적습니다(이미지와 로그, 백업에 공간이 필요합니다)"

# ---------------------------------------------------------------- 묶음 풀기

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
if [ -n "$BUNDLE" ]; then
    cp "$BUNDLE" "$work/bundle.tar.gz"
else
    if [ -n "$VERSION" ]; then
        url="https://github.com/$REPO/releases/download/v$VERSION/nginx-manager.tar.gz"
    else
        url="https://github.com/$REPO/releases/latest/download/nginx-manager.tar.gz"
    fi
    say "묶음 받기: $url"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$url" -o "$work/bundle.tar.gz" || die "묶음을 받지 못했습니다: $url"
    else
        wget -qO "$work/bundle.tar.gz" "$url" || die "묶음을 받지 못했습니다: $url"
    fi
fi
tar -xzf "$work/bundle.tar.gz" -C "$DIR" --strip-components=1
chmod 755 "$DIR/nm"
cd "$DIR"
. ./bundle.env

umask 077
cp env.example .env
set_env() {
    awk -v k="$1" -v v="$2" 'BEGIN { FS = OFS = "=" } $1 == k { print k "=" v; next } { print }' .env > .env.tmp
    cat .env.tmp > .env && rm -f .env.tmp
}
set_env NGINX_MANAGER_ADMIN_DOMAIN "$ADMIN_DOMAIN"
set_env NGINX_MANAGER_ADMIN_EMAIL "$EMAIL"
set_env NGINX_HTTP_PORT "$HTTP_PORT"
set_env NGINX_HTTPS_PORT "$HTTPS_PORT"
set_env NGINX_MANAGER_PORT "$MANAGER_PORT"
umask 022
mkdir -p data/conf data/logs data/shared data/manager backups

say "nginx manager v$BUNDLE_VERSION (nginx 이미지 $BUNDLE_NGINX_IMAGE) 를 $DIR 에 깔았습니다"
if [ "$HTTP_PORT" != 80 ]; then
    say "주의: HTTP 포트가 80이 아니라서 Let's Encrypt 발급은 앞단이 80의 /.well-known/acme-challenge/ 를 이 서버의 $HTTP_PORT 로 넘겨 줄 때만 됩니다"
fi
[ "$START" = 1 ] || { say "띄우려면: $DIR/nm up"; exit 0; }

say "이미지를 받아 띄웁니다(처음에는 몇 분 걸릴 수 있습니다)"
[ "${NM_SKIP_PULL:-0}" = 1 ] || docker compose pull -q
"$DIR/nm" up

say ""
say "설치를 마쳤습니다."
say "  관리 화면(이 서버에서): http://127.0.0.1:$MANAGER_PORT"
say "  다른 컴퓨터에서:       ssh -L $MANAGER_PORT:127.0.0.1:$MANAGER_PORT <서버> 후 http://127.0.0.1:$MANAGER_PORT"
if [ -n "$ADMIN_DOMAIN" ]; then
    say "  관리 도메인:           https://$ADMIN_DOMAIN (인증서를 받으면 1~2분 안에 열립니다)"
else
    say "  인터넷에서 HTTPS로 열려면 $DIR/.env 에 NGINX_MANAGER_ADMIN_DOMAIN과 NGINX_MANAGER_ADMIN_EMAIL을 적고 $DIR/nm restart"
fi
say "  처음 접속하면 관리자 계정을 만드는 화면이 나옵니다."
say "  운영 명령: $DIR/nm help"
