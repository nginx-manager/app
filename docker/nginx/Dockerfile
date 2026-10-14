# syntax=docker/dockerfile:1
# nginx 컨테이너: nginx(오픈소스, 전체 모듈) + 에이전트(agent/, 관리도구가 부르는 nginx -t·reload 등).
# 관리 화면(Spring Boot)은 docker/manager/Dockerfile의 별도 이미지다. 두 컨테이너는 설정·로그 볼륨을 함께 쓴다.
# 프로세스는 s6-overlay가 따로 감시·재시작한다.
# docker build -f docker/nginx/Dockerfile -t nginx-manager/nginx:dev .

# 베이스 이미지는 미러·사내 레지스트리로 바꿀 수 있게 인자로 둔다
ARG BUILD_BASE_IMAGE=ubuntu:24.04
ARG GO_IMAGE=golang:1.24
ARG RUNTIME_BASE_IMAGE=ubuntu:24.04
# 관리도구 릴리스 버전. 에이전트가 관리도구와 버전을 맞춰 보는 데 쓴다
ARG APP_VERSION=dev
# 관리도구 이미지와 같은 값이어야 두 컨테이너가 서로의 파일을 읽고 쓸 수 있다
ARG NGINXM_UID=10101
ARG NGINX_VERSION=1.31.6
ARG NJS_VERSION=1.0.1
ARG S6_OVERLAY_VERSION=3.2.3.2

############################################################
# 1. nginx 소스 빌드
############################################################
FROM ${BUILD_BASE_IMAGE} AS nginx-build
ARG NGINX_VERSION
ARG NJS_VERSION

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        build-essential ca-certificates curl git \
        libpcre2-dev zlib1g-dev libssl-dev \
        libxml2-dev libxslt1-dev libgd-dev libgeoip-dev libperl-dev libgoogle-perftools-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
RUN curl -fsSL "https://github.com/nginx/nginx/releases/download/release-${NGINX_VERSION}/nginx-${NGINX_VERSION}.tar.gz" | tar xz \
    && git clone --quiet --depth 1 --branch "${NJS_VERSION}" https://github.com/nginx/njs "njs-${NJS_VERSION}"

WORKDIR /build/nginx-${NGINX_VERSION}
# 오픈소스 nginx에서 켤 수 있는 모듈을 모두 켠다. 외부 라이브러리가 필요한 모듈은 동적 모듈로 둔다.
RUN ./configure \
        --prefix=/etc/nginx \
        --sbin-path=/usr/sbin/nginx \
        --modules-path=/usr/lib/nginx/modules \
        --conf-path=/etc/nginx/nginx.conf \
        --error-log-path=/var/log/nginx/error.log \
        --http-log-path=/var/log/nginx/access.log \
        --pid-path=/run/nginx/nginx.pid \
        --lock-path=/run/nginx/nginx.lock \
        --http-client-body-temp-path=/var/cache/nginx/client_temp \
        --http-proxy-temp-path=/var/cache/nginx/proxy_temp \
        --http-fastcgi-temp-path=/var/cache/nginx/fastcgi_temp \
        --http-uwsgi-temp-path=/var/cache/nginx/uwsgi_temp \
        --http-scgi-temp-path=/var/cache/nginx/scgi_temp \
        --with-compat \
        --with-file-aio \
        --with-threads \
        --with-pcre-jit \
        --with-http_addition_module \
        --with-http_auth_request_module \
        --with-http_dav_module \
        --with-http_degradation_module \
        --with-http_flv_module \
        --with-http_gunzip_module \
        --with-http_gzip_static_module \
        --with-http_mp4_module \
        --with-http_random_index_module \
        --with-http_realip_module \
        --with-http_secure_link_module \
        --with-http_slice_module \
        --with-http_ssl_module \
        --with-http_stub_status_module \
        --with-http_sub_module \
        --with-http_v2_module \
        --with-http_v3_module \
        --with-google_perftools_module \
        --with-mail \
        --with-mail_ssl_module \
        --with-stream \
        --with-stream_realip_module \
        --with-stream_ssl_module \
        --with-stream_ssl_preread_module \
        --with-http_xslt_module=dynamic \
        --with-http_image_filter_module=dynamic \
        --with-http_geoip_module=dynamic \
        --with-stream_geoip_module=dynamic \
        --with-http_perl_module=dynamic \
        --add-dynamic-module=/build/njs-${NJS_VERSION}/nginx \
    && make -j"$(nproc)" \
    && make install DESTDIR=/out \
    && rm -rf /out/etc/nginx/*.default /out/usr/local/man /out/usr/local/share/man

############################################################
# 2. 에이전트 빌드
############################################################
FROM ${GO_IMAGE} AS agent-build
ARG APP_VERSION
WORKDIR /src
COPY agent/ ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=${APP_VERSION}" -o /out/nginx-agent .

############################################################
# 3. 런타임
############################################################
FROM ${RUNTIME_BASE_IMAGE}
ARG S6_OVERLAY_VERSION
ARG NGINX_VERSION
ARG APP_VERSION
ARG NGINXM_UID

LABEL org.opencontainers.image.title="nginx-manager nginx" \
      org.opencontainers.image.source="https://github.com/nginx-manager/app" \
      org.opencontainers.image.version="${APP_VERSION}" \
      net.nginxmanager.nginx-version="${NGINX_VERSION}"

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates curl xz-utils libcap2-bin procps \
        libpcre2-8-0 zlib1g libssl3t64 \
        libxml2 libxslt1.1 libgd3 libgeoip1t64 libperl5.38t64 perl libgoogle-perftools4t64 \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://github.com/just-containers/s6-overlay/releases/download/v${S6_OVERLAY_VERSION}/s6-overlay-noarch.tar.xz" | tar -C / -Jxp \
    && curl -fsSL "https://github.com/just-containers/s6-overlay/releases/download/v${S6_OVERLAY_VERSION}/s6-overlay-$(uname -m).tar.xz" | tar -C / -Jxp

COPY --from=nginx-build /out/ /

# nginx와 에이전트는 같은 비루트 사용자로 돈다. 그래야 에이전트가 nginx 마스터에 reload 신호를 보낼 수 있다.
# nginx 실행 파일에만 1024 미만 포트를 열 권한을 준다.
RUN groupadd --system --gid ${NGINXM_UID} nginxm \
    && useradd --system --uid ${NGINXM_UID} --gid nginxm --home-dir /var/lib/nginx-manager --shell /usr/sbin/nologin nginxm \
    && setcap cap_net_bind_service=+ep /usr/sbin/nginx \
    && mkdir -p /etc/nginx/conf.d/sites /etc/nginx/sites-disabled /etc/nginx/stream.d /etc/nginx/certs \
                /var/log/nginx/sites /var/cache/nginx /var/lib/nginx-manager/acme-webroot /var/lib/nginx-manager/agent \
    && chown -R nginxm:nginxm /etc/nginx /var/log/nginx /var/cache/nginx /var/lib/nginx-manager

COPY --chown=nginxm:nginxm docker/nginx/nginx.conf /etc/nginx/nginx.conf
# 빈 디렉터리를 /etc/nginx에 바인드 마운트해도 시작할 때 기본 설정을 채울 수 있도록 사본을 둔다
RUN mkdir -p /usr/share/nginx-manager && cp -a /etc/nginx /usr/share/nginx-manager/defaults
COPY docker/nginx/s6-overlay/ /etc/s6-overlay/
COPY --from=agent-build /out/nginx-agent /usr/local/bin/nginx-agent

# 멈출 때 진행 중인 reload가 끝나고 nginx가 연결을 정리할(SIGQUIT) 시간을 준다
ENV S6_KEEP_ENV=1 \
    S6_BEHAVIOUR_IF_STAGE2_FAILS=2 \
    S6_SERVICES_GRACETIME=35000

# /var/lib/nginx-manager: 관리도구 컨테이너와 함께 쓰는 ACME 챌린지 파일과 에이전트 토큰
VOLUME ["/etc/nginx", "/var/log/nginx", "/var/lib/nginx-manager"]
# 7070은 에이전트. 같은 Docker 네트워크의 관리도구만 부르며 호스트에 열지 않는다
EXPOSE 80 443 7070

# 에이전트가 응답하면 정상으로 본다. 설정이 틀려 nginx가 내려가 있어도 관리도구가 붙어 고칠 수 있어야 한다
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s \
    CMD curl --noproxy '*' -fsS http://127.0.0.1:7070/healthz || exit 1

ENTRYPOINT ["/init"]
