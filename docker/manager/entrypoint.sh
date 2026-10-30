#!/bin/sh
# 루트로 시작하면 바인드 마운트한 디렉터리의 소유자를 맞춘 뒤 nginxm으로 내려가 실행한다.
# nginx 볼륨(/etc/nginx 등)은 nginx 컨테이너도 시작할 때 맞추지만, manager만 다시 띄울 때도 맞춘다.
# 서버에서 sudo로 만든 디렉터리·파일(root 소유)에 관리도구가 쓰지 못하면 적용이 실패한다.
# -xdev: 그 안에 따로 붙인 디렉터리(읽기 전용 가져오기 경로 등)는 건드리지 않는다
set -e
if [ "$(id -u)" = 0 ]; then
    for d in /data /etc/nginx /var/log/nginx /var/lib/nginx-manager; do
        [ -d "$d" ] && find "$d" -xdev ! -user nginxm -exec chown nginxm:nginxm {} + 2>/dev/null || true
    done
    # DB와 키 파일을 다른 사용자가 읽지 못하게 한다
    umask 027
    exec setpriv --reuid=nginxm --regid=nginxm --init-groups java -jar /app/app.jar "$@"
fi
umask 027
exec java -jar /app/app.jar "$@"
