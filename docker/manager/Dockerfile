# syntax=docker/dockerfile:1
# 관리도구 컨테이너: 관리 화면(Spring Boot). nginx는 docker/nginx/Dockerfile의 별도 컨테이너에서 돈다.
# nginx 설정·로그는 공유 볼륨으로 직접 읽고 쓰며, 검증·reload는 nginx 컨테이너의 에이전트에 요청한다.
# docker build -f docker/manager/Dockerfile -t nginx-manager/app:dev .

# 베이스 이미지는 미러·사내 레지스트리로 바꿀 수 있게 인자로 둔다
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG RUNTIME_BASE_IMAGE=eclipse-temurin:21-jre-noble
# nginx 이미지와 같은 값이어야 두 컨테이너가 서로의 파일을 읽고 쓸 수 있다
ARG NGINXM_UID=10101

FROM ${MAVEN_IMAGE} AS app-build
WORKDIR /src
COPY pom.xml .
COPY .mvn ./.mvn
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests \
    && cp target/nginx-manager-*.jar /src/app.jar

FROM ${RUNTIME_BASE_IMAGE}
ARG NGINXM_UID

LABEL org.opencontainers.image.title="nginx-manager" \
      org.opencontainers.image.source="https://github.com/nginx-manager/app"

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid ${NGINXM_UID} nginxm \
    && useradd --system --uid ${NGINXM_UID} --gid nginxm --home-dir /data --shell /usr/sbin/nologin nginxm \
    && mkdir -p /data /app /etc/nginx /var/log/nginx /var/lib/nginx-manager \
    && chown nginxm:nginxm /data /etc/nginx /var/log/nginx /var/lib/nginx-manager

COPY --from=app-build /src/app.jar /app/app.jar
COPY --chmod=755 docker/manager/entrypoint.sh /usr/local/bin/entrypoint.sh

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" \
    NGINX_RUNTIME_AGENT_URL=http://nginx:7070

VOLUME ["/data"]
EXPOSE 9000

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s \
    CMD curl --noproxy '*' -fsS http://127.0.0.1:9000/actuator/health/liveness || exit 1

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
