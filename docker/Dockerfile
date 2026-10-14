# syntax=docker/dockerfile:1
# nginx(오픈소스, 전체 모듈) + nginx-manager(Spring Boot)를 하나의 이미지로 만든다.
# 프로세스는 s6-overlay가 따로 감시·재시작한다.

# 베이스 이미지는 미러·사내 레지스트리로 바꿀 수 있게 인자로 둔다
ARG BUILD_BASE_IMAGE=ubuntu:24.04
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG RUNTIME_BASE_IMAGE=eclipse-temurin:21-jre-noble
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
# 2. 관리도구 빌드
############################################################
FROM ${MAVEN_IMAGE} AS app-build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests \
    && cp target/nginx-manager-*.jar /src/app.jar

############################################################
# 3. 런타임
############################################################
FROM ${RUNTIME_BASE_IMAGE}
ARG S6_OVERLAY_VERSION
ARG NGINX_VERSION

LABEL org.opencontainers.image.title="nginx-manager" \
      org.opencontainers.image.source="https://github.com/nginx-manager/app" \
      net.nginxmanager.nginx-version="${NGINX_VERSION}"

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates curl xz-utils libcap2-bin \
        libpcre2-8-0 zlib1g libssl3t64 \
        libxml2 libxslt1.1 libgd3 libgeoip1t64 libperl5.38t64 perl libgoogle-perftools4t64 \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://github.com/just-containers/s6-overlay/releases/download/v${S6_OVERLAY_VERSION}/s6-overlay-noarch.tar.xz" | tar -C / -Jxp \
    && curl -fsSL "https://github.com/just-containers/s6-overlay/releases/download/v${S6_OVERLAY_VERSION}/s6-overlay-$(uname -m).tar.xz" | tar -C / -Jxp

COPY --from=nginx-build /out/ /

# nginx와 관리도구는 같은 비루트 사용자로 돈다. 그래야 관리도구가 nginx 마스터에 reload 신호를 보낼 수 있다.
# nginx 실행 파일에만 1024 미만 포트를 열 권한을 준다.
RUN groupadd --system nginxm \
    && useradd --system --gid nginxm --home-dir /data --shell /usr/sbin/nologin nginxm \
    && setcap cap_net_bind_service=+ep /usr/sbin/nginx \
    && mkdir -p /etc/nginx/conf.d/sites /etc/nginx/sites-disabled /etc/nginx/stream.d /etc/nginx/certs \
                /var/log/nginx/sites /var/cache/nginx /var/lib/nginx-manager/acme-webroot /data /app \
    && chown -R nginxm:nginxm /etc/nginx /var/log/nginx /var/cache/nginx /var/lib/nginx-manager /data

COPY --chown=nginxm:nginxm docker/nginx/nginx.conf /etc/nginx/nginx.conf
COPY docker/s6-overlay/ /etc/s6-overlay/
COPY --from=app-build /src/app.jar /app/app.jar

ENV S6_KEEP_ENV=1 \
    S6_BEHAVIOUR_IF_STAGE2_FAILS=2 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50"

VOLUME ["/etc/nginx", "/var/log/nginx", "/data"]
EXPOSE 80 443 9000

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s \
    CMD curl -fsS http://127.0.0.1:9000/actuator/health || exit 1

ENTRYPOINT ["/init"]
