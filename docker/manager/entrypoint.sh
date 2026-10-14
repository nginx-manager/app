#!/bin/sh
# 루트로 시작하면 빈 디렉터리를 바인드 마운트한 /data의 소유자를 맞춘 뒤 nginxm으로 내려가 실행한다.
# nginx 볼륨(/etc/nginx 등)의 소유자는 nginx 컨테이너가 맞춘다.
set -e
if [ "$(id -u)" = 0 ]; then
    find /data ! -user nginxm -exec chown nginxm:nginxm {} + 2>/dev/null || true
    # DB와 키 파일을 다른 사용자가 읽지 못하게 한다
    umask 027
    exec setpriv --reuid=nginxm --regid=nginxm --init-groups java -jar /app/app.jar "$@"
fi
umask 027
exec java -jar /app/app.jar "$@"
