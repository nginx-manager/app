#!/bin/sh
# 설치 묶음을 만든다. 릴리스 워크플로가 부르고, 스모크 테스트도 쓴다.
#   deploy/build-bundle.sh <manager 버전> <nginx 이미지 태그> <출력 디렉터리>
# 출력: <출력>/nginx-manager.tar.gz, <출력>/install.sh
set -eu

version="${1:?manager 버전}"
nginx_image="${2:?nginx 이미지 태그(예: 1.2.0-nginx1.31.8)}"
out="${3:?출력 디렉터리}"
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"

protocol="$(sed -n 's/.*public static final int PROTOCOL = \([0-9]*\);.*/\1/p' \
    "$root/src/main/java/net/nginxmanager/runtime/AgentNginxRuntime.java")"
min_protocol="$(sed -n 's/.*public static final int MIN_AGENT_PROTOCOL = \([0-9]*\);.*/\1/p' \
    "$root/src/main/java/net/nginxmanager/runtime/AgentNginxRuntime.java")"
[ -n "$protocol" ] && [ -n "$min_protocol" ] || { echo "에이전트 API 판을 읽지 못했습니다" >&2; exit 1; }

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
b="$stage/nginx-manager-$version"
mkdir -p "$b"
cp "$here/compose.yml" "$here/nm" "$here/README.md" "$b/"
sed -e "s/__MANAGER_VERSION__/$version/" -e "s/__NGINX_IMAGE__/$nginx_image/" "$here/env.example" > "$b/env.example"
cat > "$b/bundle.env" <<ENV
BUNDLE_VERSION=$version
BUNDLE_NGINX_IMAGE=$nginx_image
BUNDLE_PROTOCOL=$protocol
BUNDLE_MIN_AGENT_PROTOCOL=$min_protocol
ENV
chmod 755 "$b/nm"
mkdir -p "$out"
tar -czf "$out/nginx-manager.tar.gz" -C "$stage" "nginx-manager-$version"
cp "$here/install.sh" "$out/install.sh"
echo "$out/nginx-manager.tar.gz (v$version, nginx $nginx_image, API $min_protocol..$protocol)"
