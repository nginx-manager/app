# nginx manager

[한국어](README.md) · [English](README.en.md) · [日本語](README.ja.md) · **简体中文**

在浏览器中管理 nginx 的工具。nginx（开源版，包含全部模块的构建）与管理界面（Spring Boot）运行在不同的容器中，因此即使更新或重启管理界面，nginx 也会继续接收流量。

**使用方法请参阅管理界面内的用户手册（`/guide`）。** 点击左侧菜单或页面底部（footer）的 **用户手册** 即可打开。原文位于 [src/main/resources/guide/ko](src/main/resources/guide/ko/README.md)（韩语），也可以直接在仓库中阅读。

规格与设计文档位于 [nginx-manager/docs](https://github.com/nginx-manager/docs) 的 `specs/001-nginx-web-manager/`（Spec Kit）。

## 功能

- **站点**：通过表单创建反向代理和静态文件站点，其他配置可在 Raw 编辑器（支持 nginx 语法高亮）中修改。代理的多个上游（负载均衡）、备用上游、故障判定（max_fails）、keepalive、WebSocket 和超时也都可以在表单中设置。不再使用的站点可以删除（删除同样经过待应用 → 应用）。PHP 站点（PHP-FPM）和 gRPC 代理（`grpc://`）也可以用表单创建，把 `/api/` 发往后端、`/files/` 指向目录的按路径路由也能在表单中设置。相似的站点可用**复制**沿用设置，只需填写新域名；站点多时可按域名或后端地址搜索。
- **站点选项**：在表单中开启 IP 允许/拒绝、密码保护（htpasswd）、外部认证服务器（auth_request：oauth2-proxy、Authelia 等 SSO）、请求速率与并发连接限制、gzip 压缩、安全响应头、HSTS、HTTP/3（QUIC）、真实客户端 IP（位于代理/CDN 之后时）、代理缓存、目录列表以及最大请求大小。可按行填写 CORS、CSP 等响应头和发往后端的头部（包括改写 Host），并把 `www` 等附加域名以 301 汇集到主域名。后端宕机时显示提示页而不是 nginx 默认的 502。部署期间可用维护模式显示提示页（503），只放行维护人员的地址。还可以创建仅跳转到其他地址的重定向站点。
- **流（TCP/UDP）**：通过表单创建 stream 代理，将 DB、SSH、DNS 等非 HTTP 端口转发到后端（负载均衡、超时、允许/拒绝地址，以及传递真实客户端 IP 的 PROXY protocol）。
- **导入配置文件**：将已在使用的 nginx 配置文件注册为站点。可以注册 `/home/*/nginx/*.conf` 这样的路径模式来批量查找匹配的文件，也可以上传本机上的文件。能够转换为表单的文件注册为表单站点，其余文件按原样注册为 Raw 站点；源文件发生变化时，站点页面会给出提示，可以重新导入。宿主机目录需挂载到 manager 容器，并写入 `NGINX_MANAGER_IMPORT_ROOTS`。
- **Raw 配置文件**：在 Raw 编辑器中新建或删除配置文件（njs 脚本、公共片段等）。与其他变更一样，需要经过保存（待应用）→ `nginx -t` → reload 的流程。
- **安全应用**：保存的变更进入待应用状态，在应用页面通过 diff 确认后再生效。应用按 `nginx -t` → reload → 健康检查的顺序进行，任一步骤失败都会自动回滚到之前的配置。端口冲突这类 `nginx -t` 检测不到、直到 reload 时才暴露的问题，同样视为失败并回滚。
- **历史与回滚**：每次应用都会保留一个版本，可以比较两个版本或回滚到之前的版本（回滚同样要经过相同的校验）。会检测并提示在服务器上被直接修改的文件（配置漂移）。在站点页面的**变更历史**中可以只查看改动过该站点的版本，成员也能查看自己负责站点的历史。相同内容只保存一次，版本增多时数据库也不会明显变大。
- **HTTPS**：在站点中开启 HTTPS 后，会自动签发 Let's Encrypt 证书并完成配置，在到期前自动续期。续期失败时继续使用当前证书提供服务，并通过邮件和 Webhook 发出通知。通配符证书和内部证书可以上传。导入的 Raw 站点也可以签发证书，并加入 443 server 块和 80→443 重定向。
- **仪表盘与日志**：查看 nginx 状态、版本、模块、最近的应用记录和证书警告，并可按站点搜索或实时查看 access/error 日志（包括导入的 Raw 站点和已轮转日志）。
- **用户与审计日志**：管理员/成员两种角色。成员只能查看、修改和应用自己负责的站点，管理员为每个站点指定负责的成员。所有变更都会记录在审计日志中，筛选后的记录可下载为 CSV。可开启基于验证器应用(TOTP)的两步验证和恢复码。

## 运行

服务器上使用发布版本附带的安装包。一条命令即可安装到 `/opt/nginx-manager` 并启动（需要 Docker 和 Compose v2）。

```bash
curl -fsSL https://github.com/nginx-manager/app/releases/latest/download/install.sh | sudo sh -s -- \
    --admin-domain nm.example.com --email ops@example.com
```

- 管理界面默认只能在服务器内部（`http://127.0.0.1:9000`）访问。指定 `--admin-domain`（或 `.env` 中的 `NGINX_MANAGER_ADMIN_DOMAIN`）后，一同安装的 nginx 会为该域名签发 Let's Encrypt 证书，并仅通过 HTTPS 提供管理界面（80 跳转到 443）。
- 端口、域名和版本在安装目录的 `.env` 中修改。数据位于 `data/` 下。
- 在已安装的服务器上再次运行安装命令时，不会改动 `.env` 和 `data/`，直接结束。新版本通过 `nm upgrade` 升级，`.env` 保持不变，只会以默认值追加新增的键。
- 运维使用 `nm` 命令：`nm status`、`nm upgrade`（只升级 manager，nginx 不中断）、`nm upgrade --nginx`、`nm backup`、`nm restore`、`nm migrate`（迁移 v1.1 之前的安装）、`nm import-root`（以相同路径挂载要导入的宿主机目录并检查读取权限）、`nm check-domain`（检查管理域名是否在 DNS 中并指向本服务器）。
- 若要导入服务器上已有的配置（例如 `/home/*/nginx/*.conf`），安装时加上 `--import-root /home`。安装后用 `nm import-root add /home` 挂载，再用 `nm import-root check` 确认管理工具（UID 10101）能否读取。无法读取时，`nm import-root grant` 会通过 ACL 只授予读取权限。导入页面遇到没有权限的目录时也不会中止，而是列出无法打开的位置和 grant 命令。
- 详细内容见用户手册 [1. 安装与运行](src/main/resources/guide/ko/01-install.md)（韩语）。安装包的源文件在 [deploy/](deploy/)。

安装目录的 `.env`（包含通过安装选项指定的值）。修改后执行 `nm restart`；如果改了 nginx 端口，执行 `nm restart nginx`。

| 变量 | 默认值 | 何时修改 |
| --- | --- | --- |
| `MANAGER_VERSION`, `NGINX_IMAGE` | 已安装的版本 | 不要手动修改，由 `nm upgrade` 更新 |
| `NGINX_MANAGER_ADMIN_DOMAIN` | （空） | 需要从互联网通过 `https://域名` 访问管理界面时。DNS 必须指向本服务器，且 80 端口可以访问 |
| `NGINX_MANAGER_ADMIN_EMAIL` | （空） | 使用管理域名时 Let's Encrypt 的联系邮箱（到期提醒） |
| `NGINX_MANAGER_ADMIN_ALLOW` | （空，全部允许） | 需要限制可访问管理域名的地址时（CIDR，多个用逗号分隔） |
| `NGINX_MANAGER_PORT` | `9000` | 9000 被其他程序占用时 |
| `NGINX_MANAGER_BIND_ADDRESS` | `127.0.0.1` | 不使用管理域名、直接在内网开放时，设为 `0.0.0.0` 或内网 IP |
| `NGINX_HTTP_PORT`, `NGINX_HTTPS_PORT` | `80`, `443` | 前端已有其他代理、无法使用 80/443 时 |
| `NGINX_BIND_ADDRESS` | `0.0.0.0` | 只在特定 IP 上开放 nginx 时 |
| `NGINX_MANAGER_BACKUP_KEEP` | `14` | `nm backup` 保留的备份数量 |
| `NGINX_MANAGER_BACKUP_AT` | `03:30` | 每日自动备份（`nm backup --label auto`）的时间。`off` 表示关闭。修改后运行 `sudo nm backup-schedule` |
| `TZ` | `Asia/Seoul` | 服务器时区不同时 |
| `NGINX_MANAGER_IMPORT_ROOTS` | （空） | 需要导入 `/etc/nginx` 之外的配置文件时。不要手动修改，使用 `nm import-root add /home` |
| `SPRING_MAIL_*` | （空） | 发送邮件通知时的 SMTP 服务器 |

开发和测试使用仓库中的 [docker/docker-compose.yml](docker/docker-compose.yml)（使用具名卷，管理界面对所有地址开放）。

```bash
NGINX_MANAGER_VERSION=1.2.0 docker compose -f docker/docker-compose.yml up -d
```

| 容器 | 镜像 | 作用 |
|----------|--------|---------|
| `nginx` | `ghcr.io/nginx-manager/nginx` | nginx（80/443）和代理程序（agent）。agent 只根据管理界面的请求执行 `nginx -t` 和 reload。 |
| `manager` | `ghcr.io/nginx-manager/app` | 管理界面（9000）。配置文件通过卷直接写入，校验和 reload 交给 agent。 |

| 卷 | 挂载 | 内容 |
|------|--------|------|
| `nm-conf` | 两者均为 `/etc/nginx` | nginx 配置、证书（`certs/`） |
| `nm-logs` | 两者均为 `/var/log/nginx` | 访问/错误日志（站点日志在 `sites/`） |
| `nm-shared` | 两者均为 `/var/lib/nginx-manager` | Let's Encrypt 验证文件、agent 令牌 |
| `nm-data` | manager `/data` | 管理工具数据库（SQLite）、ACME 账户密钥、进行中的应用记录 |

共享卷在两个容器中要挂载到**相同的路径**（`nginx -t` 错误中的文件路径必须与界面上的文件一致）。两个镜像的进程以相同的 UID（10101）运行，可以互相读写对方的文件。

**流端口与 HTTP/3**：要从外部使用在流页面创建的端口（例如 TCP 5432），需要在 compose 文件 `nginx` 服务的 `ports` 中添加该端口（`"5432:5432"`，UDP 为 `"53:53/udp"`），然后用 `docker compose up -d nginx` 重新启动。对于 HTTP/3，compose 文件已经开放了 `443/udp`，默认 `nginx.conf` 中的 443 默认 server 也已声明了一次 `listen 443 quic reuseport`。如果安装仍在使用旧的 `nginx.conf`，站点页面会给出提示，请参照新镜像的 [nginx.conf](docker/nginx/nginx.conf) 添加 443 默认 server。

首次启动时，会导入 `/etc/nginx` 中已有的配置并保存为第一个版本。更换镜像后，卷中的数据保持不变。

### 配置（环境变量）

管理工具（`manager`）容器：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `NGINX_RUNTIME_AGENT_URL` | `http://nginx:7070` | agent 地址。修改 compose 中 nginx 服务名时需一并修改。 |
| `NGINX_RUNTIME_AGENT_TOKEN` | （空） | 留空时读取共享卷中的令牌文件。若自行指定，需与 nginx 容器的 `NGINX_AGENT_TOKEN` 保持一致。 |
| `NGINX_MANAGER_ACME_DIRECTORY_URL` | （空） | 指定后将使用该 ACME 服务器，而不是界面中的 Let's Encrypt 生产/测试环境选项。 |
| `NGINX_MANAGER_CERT_CHECK_INTERVAL` | `PT6H` | 查找需续期证书的周期 |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | | 使用邮件通知时的 SMTP 服务器 |
| `SERVER_SERVLET_SESSION_COOKIE_SECURE` | `false` | 管理界面部署在 HTTPS 之后时设为 `true` |
| `NGINX_MANAGER_IMPORT_ROOTS` | （空） | 导入配置文件时，除 `/etc/nginx` 外可以搜索的目录（多个用逗号分隔）。先将宿主机目录以相同路径挂载到 manager 容器，再填写此项。 |

nginx 容器：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `NGINX_AGENT_TOKEN` | （空） | 留空时，首次启动会在 `/var/lib/nginx-manager/agent/token` 生成随机令牌并使用。 |
| `NGINX_AGENT_LISTEN` | `:7070` | agent 监听的地址 |

Let's Encrypt 使用 HTTP-01 方式验证，因此域名必须能访问到 nginx 容器的 80 端口。建议首次使用时先在界面的设置中用测试环境（staging）试一试。

## 版本与发布说明

当前使用的管理工具版本显示在管理界面底部（footer）。版本以 git 标签（`vX.Y.Z`）为准，未打标签构建的镜像显示为 `dev`。各版本的变更内容附带截图整理在 [发布说明](docs/releases/README.md)（韩语）中，版本规则和发布流程也在那里。

## 升级与回滚

manager 和 nginx 镜像分别升级。`.env` 中的 `MANAGER_VERSION` 和 `NGINX_IMAGE` 是两个镜像的标签。

```bash
sudo /opt/nginx-manager/nm upgrade           # 仅 manager。nginx 继续接收流量
sudo /opt/nginx-manager/nm upgrade --nginx   # 同时升级 nginx 镜像。先用新 nginx 检查配置再替换（重启几秒）
```

- `nm upgrade` 在升级前会先备份，如果新的 manager 没有变为 healthy，则回退到之前的版本和升级前的数据库（因为数据库变更只能向前）。
- 两个容器的发布版本不同也属正常。只有 agent API 版本不匹配时才会出现警告，并提示同时升级 nginx。修改 agent API 时也要兼容旧版本，以便可以先升级 manager（`AgentNginxRuntime.MIN_AGENT_PROTOCOL`）。
- nginx 镜像标签为 `<管理工具版本>-nginx<nginx 版本>`（例如 `1.2.0-nginx1.31.8`）、`<管理工具版本>` 和 `latest`。nginx 发布新版本时，只有 nginx 镜像会被自动重新构建、测试和发布，该发布版本的安装包也会改用新的 nginx 标签。旧标签会保留。
- 新的 nginx 镜像在启动时、启动 nginx 之前会校验卷中的配置。如果校验不通过，则不启动 nginx，只启动 agent，并在仪表盘上显示出问题的文件和行号以及之前/当前的版本。
- 版本变化会记录在仪表盘和审计日志中。

要回滚配置本身，请在历史页面中对目标版本使用“回滚”。

## 架构

- 在 nginx 容器内，[s6-overlay](https://github.com/just-containers/s6-overlay) 分别监控 nginx 和 agent，进程退出时会重新拉起。
- 管理工具只通过 agent API（`agent/`）操作 nginx。agent 只执行固定的命令（`nginx -t`、`nginx -s reload`、`nginx -s reopen`、`nginx -V`、`s6-svc -u`），拒绝不带令牌的请求。不会把 Docker 套接字交给管理工具。
- 两个容器的进程都以非 root 用户 `nginxm`（UID 10101）运行。只有 nginx 可执行文件被授予绑定 80/443 端口的权限。
- 每次签发或续期证书时，都会写入新的 `/etc/nginx/certs/<名称>/<时间>/`，只修改配置中的路径来生效。因此证书更换也会记录在历史中，并且可以回滚。

## 开发

需要 Java 21、Maven 3.9、Go 1.24 和 Docker。

```bash
mvn verify                       # 单元与集成测试（真实 ACME 签发测试会用 Docker 启动 Pebble）
mvn spotless:apply               # 格式化代码（verify 会检查）。编译时由 Error Prone 拦截常见错误
(cd agent && go test ./...)      # agent 测试
docker build -f docker/nginx/Dockerfile -t nginx-manager/nginx:dev .
docker build -f docker/manager/Dockerfile -t nginx-manager/app:dev .
# 镜像测试：以与 compose 相同的结构启动两个容器，用真实 nginx 验证应用、HTTPS 和升级场景
mvn -Pimage-test test -Dimage.name=nginx-manager/app:dev -Dnginx.image.name=nginx-manager/nginx:dev
```

Raw 编辑器的打包文件（`src/main/resources/static/js/editor.bundle.js`）一并保存在仓库中。只有修改时才需要重新构建。

```bash
cd frontend && npm ci && npm run build
```

数据访问规则（constitution）：

- 动态条件、连接和投影查询使用 Querydsl 编写。不使用字符串 JPQL、原生查询和 `@Query`（由 `ArchitectureRulesTest` 拦截）。
- 所有关联关系均为 `fetch = LAZY`，`open-in-view=false`。
- 每个列表查询都用 `QueryCounter.assertConstantQueries` 确认查询次数与数据量无关、保持不变。

用户手册：修改用户可见的功能、界面或设置的 PR，需要在同一个 PR 中同时修改 [用户手册](src/main/resources/guide/ko/README.md) 的相应章节。具体方法和截图重拍见 [手册维护](docs/guide.md)（韩语）。

发布：编写 [发布说明](docs/releases/README.md) 并推送 `v<版本>` 标签后，会用该版本和最新的 nginx 发布两个镜像。之后每天检查 nginx 的新发布，并只自动发布 nginx 镜像。修改 agent API 时，需要同时提升 `agent/server.go` 中的 `Protocol` 和 `AgentNginxRuntime.PROTOCOL`。
