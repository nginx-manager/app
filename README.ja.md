# nginx manager

[한국어](README.md) · [English](README.en.md) · **日本語** · [简体中文](README.zh-CN.md)

ブラウザから nginx を管理するツールです。nginx(オープンソース、全モジュールビルド)と管理画面(Spring Boot)は別々のコンテナで動くため、管理画面を更新したり再起動したりしても nginx はトラフィックを受け続けます。

**使い方は管理画面内のユーザーガイド(`/guide`)にあります。** 左メニューとページ下部(footer)の **ユーザーガイド** を押すと開きます。原文は [src/main/resources/guide/ko](src/main/resources/guide/ko/README.md)(韓国語)にあり、リポジトリ上でも読めます。

仕様と設計は [nginx-manager/docs](https://github.com/nginx-manager/docs) の `specs/001-nginx-web-manager/` にあります(Spec Kit)。

## できること

- **サイト**: リバースプロキシ・静的ファイルのサイトをフォームで作成し、それ以外の設定は原文エディタ(nginx 構文ハイライト付き)で編集します。プロキシは複数のバックエンド(負荷分散)、WebSocket、タイムアウトまでフォームで設定できます。
- **サイトオプション**: フォームから IP の許可・拒否、パスワード保護(htpasswd)、リクエストレート・同時接続数の制限、gzip 圧縮、セキュリティヘッダー、HSTS、HTTP/3(QUIC)、実クライアント IP(プロキシ・CDN の背後)、プロキシキャッシュ、ディレクトリ一覧、最大リクエストサイズを有効にします。別のアドレスへ転送するだけのリダイレクトサイトも作成できます。
- **ストリーム(TCP/UDP)**: DB・SSH・DNS のような HTTP 以外のポートをバックエンドへ転送する stream プロキシをフォームで作成します(負荷分散、タイムアウト)。
- **設定ファイルのインポート**: すでに使っている nginx 設定ファイルをサイトとして登録します。`/home/*/nginx/*.conf` のようなパスパターンを登録して該当ファイルをまとめて探すか、手元の PC のファイルをアップロードします。フォームに移せるファイルはフォームサイトとして、それ以外は原文のまま原文サイトとして登録され、元のファイルが変更されるとサイト画面で通知され、再インポートできます。ホストのディレクトリは manager コンテナにマウントし、`NGINX_MANAGER_IMPORT_ROOTS` に記入します。
- **原文ファイル**: 原文エディタで新しい設定ファイル(njs スクリプト、共通スニペットなど)を作成・削除します。他の変更と同じく 下書き → 検証 → 適用 を経ます。
- **安全な適用**: 保存した下書きは適用画面で diff を確認してから反映します。反映は `nginx -t` による検証 → reload → 状態確認の順で行い、どの段階で失敗しても直前の設定に戻ります。ポート競合のように `nginx -t` では検出できず reload で初めて表面化する問題も失敗とみなして元に戻します。
- **履歴とロールバック**: 適用するたびにバージョンが残り、2 つのバージョンを比較したり以前のバージョンへ戻したりできます(ロールバックも同じ検証を経ます)。サーバー上で直接変更されたファイルは検知して通知します。
- **HTTPS**: サイトで HTTPS を有効にすると Let's Encrypt の証明書を取得して設定まで自動で適用し、期限切れ前に更新します。更新に失敗した場合は現在の証明書でサービスを続けながら、メール・Webhook で通知します。ワイルドカード証明書や社内証明書はアップロードします。取り込んだ直接編集サイトも証明書を取得し、443 の server ブロックと 80→443 リダイレクトを下書きに加えられます。
- **ダッシュボードとログ**: nginx の状態・バージョン・モジュール、最近の適用、証明書の警告を確認し、サイトごとの access/error ログを検索したりリアルタイムで表示したりできます(取り込んだ原文サイトとローテーションされた過去のログを含む)。
- **ユーザーと監査ログ**: 管理者/メンバーのロール。メンバーは担当するサイトのみ閲覧・編集・適用でき、管理者はサイトごとに担当メンバーを決めます。すべての変更は監査ログに記録されます。

## 実行

サーバーではリリースに添付されたインストールバンドルを使います。コマンド 1 行で `/opt/nginx-manager` にインストールして起動します(Docker と Compose v2 が必要です)。

```bash
curl -fsSL https://github.com/nginx-manager/app/releases/latest/download/install.sh | sudo sh -s -- \
    --admin-domain nm.example.com --email ops@example.com
```

- 管理画面はデフォルトではサーバー内(`http://127.0.0.1:9000`)からのみ開けます。`--admin-domain`(または `.env` の `NGINX_MANAGER_ADMIN_DOMAIN`)を指定すると、同梱の nginx がそのドメインで Let's Encrypt 証明書を取得し、HTTPS でのみ管理画面を公開します(80 は 443 へリダイレクト)。
- ポート・ドメイン・バージョンはインストールディレクトリの `.env` で変更します。データは `data/` 以下にあります。
- インストール済みのサーバーでインストールコマンドを再実行すると、`.env` と `data/` には手を触れずに終了します。新しいバージョンへは `nm upgrade` で上げ、`.env` はそのままに新しく追加されたキーだけをデフォルト値で追記します。
- 運用は `nm` コマンドで行います: `nm status`、`nm upgrade`(manager だけを上げるので nginx は途切れません)、`nm upgrade --nginx`、`nm backup`、`nm restore`、`nm migrate`(v1.1 より前のインストールの移行)、`nm import-root`(インポートするホストディレクトリを同じパスでマウントし、読み取り権限を確認)、`nm check-domain`(管理ドメインが DNS にあり、このサーバーに届くかを確認)。
- サーバーに既存の設定(例: `/home/*/nginx/*.conf`)をインポートするには、インストール時に `--import-root /home` を付けます。インストール後は `nm import-root add /home` でマウントし、`nm import-root check` で管理ツール(UID 10101)が読めるか確認します。読めない場合は `nm import-root grant` が ACL で読み取り権限だけを付与します。インポート画面も権限のないディレクトリに出会っても止まらず、開けなかった場所と grant コマンドを表示します。
- 詳しくはユーザーガイド [1. インストールと実行](src/main/resources/guide/ko/01-install.md)(韓国語)にあります。バンドルの元ファイルは [deploy/](deploy/) です。

インストールディレクトリの `.env`(インストールオプションで指定した値が入ります)。変更後は `nm restart`、nginx のポートを変えた場合は `nm restart nginx` を実行します。

| 変数 | デフォルト | 変更するとき |
| --- | --- | --- |
| `MANAGER_VERSION`, `NGINX_IMAGE` | インストールしたバージョン | 直接編集しません。`nm upgrade` が変更します |
| `NGINX_MANAGER_ADMIN_DOMAIN` | (空) | 管理画面をインターネットから `https://ドメイン` で開くとき。DNS がこのサーバーを指し、80 番で到達できる必要があります |
| `NGINX_MANAGER_ADMIN_EMAIL` | (空) | 管理ドメインを使うときの Let's Encrypt 連絡先メール(期限切れの案内) |
| `NGINX_MANAGER_ADMIN_ALLOW` | (空、すべて許可) | 管理ドメインにアクセスできるアドレスを制限するとき(CIDR、カンマ区切りで複数) |
| `NGINX_MANAGER_PORT` | `9000` | 9000 番を他のプログラムが使っているとき |
| `NGINX_MANAGER_BIND_ADDRESS` | `127.0.0.1` | 管理ドメインなしで社内ネットワークから直接開くときは `0.0.0.0` または社内 IP |
| `NGINX_HTTP_PORT`, `NGINX_HTTPS_PORT` | `80`, `443` | 前段に別のプロキシがあり 80/443 を使えないとき |
| `NGINX_BIND_ADDRESS` | `0.0.0.0` | nginx を特定の IP でのみ公開するとき |
| `NGINX_MANAGER_BACKUP_KEEP` | `14` | `nm backup` が残すバックアップの数 |
| `TZ` | `Asia/Seoul` | サーバーのタイムゾーンが異なるとき |
| `NGINX_MANAGER_IMPORT_ROOTS` | (空) | `/etc/nginx` 以外の設定ファイルをインポートするとき。直接編集せず `nm import-root add /home` を使います |
| `SPRING_MAIL_*` | (空) | メール通知を送るときの SMTP サーバー |

開発・テストにはリポジトリの [docker/docker-compose.yml](docker/docker-compose.yml) を使います(名前付きボリューム、管理画面をすべてのアドレスで公開)。

```bash
NGINX_MANAGER_VERSION=1.2.0 docker compose -f docker/docker-compose.yml up -d
```

| コンテナ | イメージ | 役割 |
|----------|--------|---------|
| `nginx` | `ghcr.io/nginx-manager/nginx` | nginx(80/443)とエージェント。エージェントは管理画面からの要求で `nginx -t`・reload のみを実行します。 |
| `manager` | `ghcr.io/nginx-manager/app` | 管理画面(9000)。設定ファイルはボリュームに直接書き込み、検証・reload はエージェントに任せます。 |

| ボリューム | マウント | 内容 |
|------|--------|------|
| `nm-conf` | 両方 `/etc/nginx` | nginx 設定、証明書(`certs/`) |
| `nm-logs` | 両方 `/var/log/nginx` | アクセス・エラーログ(サイトごとのログは `sites/`) |
| `nm-shared` | 両方 `/var/lib/nginx-manager` | Let's Encrypt のチャレンジファイル、エージェントトークン |
| `nm-data` | manager `/data` | 管理ツールの DB(SQLite)、ACME アカウントキー、進行中の適用記録 |

共有ボリュームは 2 つのコンテナで **同じパス** に置きます(`nginx -t` のエラーに出るファイルパスが画面上のファイルと一致する必要があるためです)。2 つのイメージのプロセスは同じ UID(10101)で動き、互いのファイルを読み書きします。

**ストリームポートと HTTP/3**: ストリーム画面で作ったポート(例: TCP 5432)を外部から使うには、compose ファイルの `nginx` サービスの `ports` にそのポートを追加し(`"5432:5432"`、UDP は `"53:53/udp"`)、`docker compose up -d nginx` で再起動します。HTTP/3 については、compose ファイルがすでに `443/udp` を開けており、デフォルトの `nginx.conf` の 443 デフォルトサーバーが `listen 443 quic reuseport` を一度宣言しています。古い `nginx.conf` をそのまま使っているインストールではサイト画面がその旨を通知するので、新しいイメージの [nginx.conf](docker/nginx/nginx.conf) のように 443 デフォルトサーバーを追加してください。

初回起動時に `/etc/nginx` にある既存の設定を取り込み、最初のバージョンとして残します。イメージを入れ替えてもボリュームのデータはそのままです。

### 設定(環境変数)

管理ツール(`manager`)コンテナ:

| 変数 | デフォルト値 | 説明 |
|------|--------|------|
| `NGINX_RUNTIME_AGENT_URL` | `http://nginx:7070` | エージェントのアドレス。compose の nginx サービス名を変更したら合わせて変更します。 |
| `NGINX_RUNTIME_AGENT_TOKEN` | (空) | 空の場合は共有ボリュームのトークンファイルを読みます。自分で指定する場合は nginx コンテナの `NGINX_AGENT_TOKEN` と同じ値にします。 |
| `NGINX_MANAGER_ACME_DIRECTORY_URL` | (空) | 指定すると、画面での Let's Encrypt 本番/ステージングの選択の代わりにこの ACME サーバーを使います。 |
| `NGINX_MANAGER_CERT_CHECK_INTERVAL` | `PT6H` | 更新すべき証明書を探す間隔 |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | | メール通知を使うときの SMTP サーバー |
| `SERVER_SERVLET_SESSION_COOKIE_SECURE` | `false` | 管理画面を HTTPS の背後に置く場合は `true` |
| `NGINX_MANAGER_IMPORT_ROOTS` | (空) | 設定ファイルのインポートで `/etc/nginx` 以外に探索できるディレクトリ(カンマ区切りで複数)。ホストのディレクトリを manager コンテナに同じパスでマウントしてから記入します。 |

nginx コンテナ:

| 変数 | デフォルト値 | 説明 |
|------|--------|------|
| `NGINX_AGENT_TOKEN` | (空) | 空の場合は初回起動時に `/var/lib/nginx-manager/agent/token` にランダムなトークンを生成して使います。 |
| `NGINX_AGENT_LISTEN` | `:7070` | エージェントが待ち受けるアドレス |

Let's Encrypt は HTTP-01 方式で認証するため、ドメインへのアクセスが nginx コンテナの 80 番ポートに届く必要があります。最初は画面の設定でステージングを使って試すことをおすすめします。

## バージョンとリリースノート

使用中の管理ツールのバージョンは管理画面の下部(footer)に表示されます。バージョンは git タグ(`vX.Y.Z`)が基準で、タグなしでビルドしたイメージは `dev` と表示されます。バージョンごとの変更内容は [リリースノート](docs/releases/README.md)(韓国語)に画面とともにまとめています。バージョン規則とリリース手順もそこにあります。

## アップグレードとロールバック

manager と nginx のイメージは別々にアップグレードします。`.env` の `MANAGER_VERSION` と `NGINX_IMAGE` が 2 つのイメージのタグです。

```bash
sudo /opt/nginx-manager/nm upgrade           # manager のみ。nginx はトラフィックを受け続ける
sudo /opt/nginx-manager/nm upgrade --nginx   # nginx イメージも。新しい nginx で設定を検査してから切り替える(数秒の再起動)
```

- `nm upgrade` はアップグレード前にバックアップを取り、新しい manager が healthy にならなければ以前のバージョンとアップグレード前の DB に戻します(DB の変更は前進のみのため)。
- 2 つのコンテナのリリースバージョンが異なっていても正常です。エージェント API の版が合わないときだけ警告が表示され、nginx も一緒にアップグレードするよう案内します。エージェント API を変更する際は以前の版も受け付けるようにし、manager を先にアップグレードできるようにします(`AgentNginxRuntime.MIN_AGENT_PROTOCOL`)。
- nginx イメージのタグは `<管理ツールのバージョン>-nginx<nginx のバージョン>`(例: `1.2.0-nginx1.31.8`)、`<管理ツールのバージョン>`、`latest` です。nginx の新しいリリースが出ると nginx イメージだけが自動で再ビルド・テスト・公開され、そのリリースのインストールバンドルも新しい nginx タグに切り替わります。以前のタグはそのまま残ります。
- 新しい nginx イメージは起動時、nginx を立ち上げる前にボリュームの設定を検証します。受け入れられない場合は nginx を起動せずエージェントだけが起動し、ダッシュボードに問題のファイル・行と以前/現在のバージョンを表示します。
- バージョンが変わるとダッシュボードと監査ログに記録されます。

設定そのものを戻すときは、履歴画面で目的のバージョンの「ロールバック」を使います。

## 構成

- nginx コンテナ内では [s6-overlay](https://github.com/just-containers/s6-overlay) が nginx とエージェントをそれぞれ監視し、停止したら再起動します。
- 管理ツールは nginx をエージェント API(`agent/`)経由でのみ操作します。エージェントは決められたコマンド(`nginx -t`、`nginx -s reload`、`nginx -s reopen`、`nginx -V`、`s6-svc -u`)のみを実行し、トークンのないリクエストは拒否します。管理ツールに Docker ソケットは渡しません。
- 2 つのコンテナのプロセスはどちらも非 root ユーザー `nginxm`(UID 10101)で動きます。80/443 ポートを開く権限は nginx の実行ファイルにだけ与えます。
- 証明書は発行・更新のたびに `/etc/nginx/certs/<名前>/<時刻>/` に新しく書き込み、設定内のパスだけを変えて適用します。そのため証明書の差し替えも履歴に残り、元に戻せます。

## 開発

Java 21、Maven 3.9、Go 1.24、Docker が必要です。

```bash
mvn verify                       # 単体・結合テスト(実際の ACME 発行テストは Docker で Pebble を起動)
mvn spotless:apply               # フォーマットを整える(verify がチェックする)。コンパイル時は Error Prone がよくあるミスを防ぎます
(cd agent && go test ./...)      # エージェントのテスト
docker build -f docker/nginx/Dockerfile -t nginx-manager/nginx:dev .
docker build -f docker/manager/Dockerfile -t nginx-manager/app:dev .
# イメージテスト: 2 つのコンテナを compose と同じ構成で起動し、適用・HTTPS・アップグレードのシナリオを実際の nginx で確認
mvn -Pimage-test test -Dimage.name=nginx-manager/app:dev -Dnginx.image.name=nginx-manager/nginx:dev
```

原文エディタのバンドル(`src/main/resources/static/js/editor.bundle.js`)はリポジトリに含めています。変更するときだけ再ビルドします。

```bash
cd frontend && npm ci && npm run build
```

データアクセスのルール(constitution):

- 動的な条件・結合・プロジェクションを伴う照会は Querydsl で書きます。文字列の JPQL、ネイティブクエリ、`@Query` は使いません(`ArchitectureRulesTest` が禁止します)。
- 関連はすべて `fetch = LAZY`、`open-in-view=false` です。
- 一覧照会ごとに `QueryCounter.assertConstantQueries` で、データ件数に関係なくクエリ数が一定であることを確認します。

ユーザーガイド: ユーザーが目にする機能・画面・設定を変更する PR では、同じ PR で [ユーザーガイド](src/main/resources/guide/ko/README.md) の該当章も更新します。方法とスクリーンショットの撮り直しは [ガイドの管理](docs/guide.md)(韓国語)にあります。

リリース: [リリースノート](docs/releases/README.md) を書いて `v<バージョン>` タグをプッシュすると、そのバージョンと最新の nginx で 2 つのイメージを公開します。その後の nginx リリースは毎日確認し、nginx イメージだけを自動で公開します。エージェント API を変更したら `agent/server.go` の `Protocol` と `AgentNginxRuntime.PROTOCOL` を一緒に上げます。
