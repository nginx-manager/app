# 5. 설정 파일 가져오기

이미 운영하던 nginx 설정 파일을 nginx manager의 사이트로 옮겨 옵니다(v1.1.0부터, 관리자). 사이트 목록의 **설정 파일 가져오기**에서 시작합니다.

가져온 파일은 이렇게 등록됩니다.

- 사이트 폼(프록시·정적·리디렉션, 백엔드 여러 개·부하 분산·웹소켓·타임아웃, gzip, 디렉터리 목록, 최대 요청 크기)으로 빠짐없이 옮길 수 있으면 **폼 사이트**가 됩니다.
- location이 여럿이거나 HTTPS server처럼 폼에 없는 설정이 하나라도 있으면 원문 그대로 **원문 사이트**가 됩니다. 설정이 조용히 빠지거나 바뀌지 않습니다.
- 어느 쪽이든 `conf.d/sites/<도메인>.conf` 초안이 되고, 다른 변경과 같이 [적용](07-apply.md) 화면에서 확인한 뒤 반영됩니다.

## 파일을 지정하는 세 가지 방법

<p align="center"><img src="images/import.png" alt="가져오기 위치와 검사 결과" width="860"></p>

### 가져오기 위치 (경로 패턴)

여러 파일을 한꺼번에 가져오거나, 원본이 바뀌는지 계속 지켜볼 때 씁니다.

1. **경로 패턴**에 패턴을 적고 **위치 등록**을 누릅니다.
   - `*`는 디렉터리 한 단계, `**`는 여러 단계와 맞습니다. `?`, `[]`, `{}`도 씁니다.
   - 예: `/home/*/nginx/*.conf`(사용자마다 둔 설정), `conf.d/*.conf`(설정 루트 `/etc/nginx` 기준 상대 경로)
2. **등록한 위치 모두 검사**를 누르면 맞는 파일이 표로 나옵니다(최대 200개).
3. 파일마다 등록 방식과 상태가 보입니다.

| 표시 | 뜻 |
| --- | --- |
| 폼 사이트 / 원문 사이트 | 새 파일. 등록하면 이 방식으로 들어옵니다. |
| 등록됨 | 이미 이 파일에서 가져온 사이트가 있습니다. |
| 원본 바뀜 | 가져온 뒤 원본이 바뀌었습니다. 사이트 화면에서 다시 가져옵니다. |
| 도메인 겹침 | 같은 도메인을 쓰는 사이트가 이미 있습니다. |
| 등록 불가 | 문법 오류, server 블록 없음 같은 이유로 가져올 수 없습니다. 이유가 함께 보입니다. |

4. 가져올 파일을 고르고 **고른 파일 등록(초안)**을 누르면 한꺼번에 사이트 초안이 만들어집니다. 대표 도메인은 첫 `server_name`입니다. 대표 도메인이나 등록 방식을 다르게 정하려면 **미리 보기**에서 하나씩 등록합니다.

등록한 위치는 남아 있어서, 나중에 다시 검사하면 새로 생긴 파일과 원본이 바뀐 사이트를 볼 수 있습니다. 필요 없어진 위치는 **빼기**로 지웁니다.

### 한 번만 찾기

위치를 등록하지 않고 파일 하나의 경로나 패턴을 바로 검사합니다.

### 내 PC의 파일 올리기

브라우저에서 파일을 골라 올립니다. 1MB 이하의 UTF-8 텍스트 파일만 됩니다.

## 찾을 수 있는 디렉터리

관리도구는 컨테이너 안에서 돌기 때문에, 컨테이너가 볼 수 있는 경로만 찾습니다. 설정 루트(`/etc/nginx`) 안의 파일은 따로 설정하지 않아도 됩니다. 화면의 **찾을 수 있는 디렉터리**에 지금 허용된 경로가 보입니다.

### 호스트의 다른 디렉터리 붙이기

`/home/*/nginx/*.conf`처럼 호스트의 다른 디렉터리에 있는 파일은 서버에서 그 디렉터리를 붙입니다(설치할 때 `--import-root /home`을 주어도 됩니다).

```bash
sudo /opt/nginx-manager/nm import-root add /home
```

- 두 컨테이너(manager와 nginx)에 **호스트와 같은 경로**로 읽기 전용으로 붙입니다. 그래서 화면의 경로와 서버의 경로가 같고, 패턴을 바꿔 적을 필요가 없습니다.
- nginx에도 붙이는 까닭: 가져온 설정의 `root /home/alice/www`, `ssl_certificate /home/...`, `include /home/...`를 nginx가 읽어야 적용할 때 `nginx -t`가 통과합니다.
- 붙이는 동안 nginx가 몇 초 다시 시작합니다. 붙인 것은 `compose.import.yml`과 `.env`의 `NGINX_MANAGER_IMPORT_ROOTS`에 남아 업그레이드해도 그대로입니다.
- 떼려면 `nm import-root remove /home`, 지금 붙인 것은 `nm import-root`로 봅니다.

### 읽기 권한

두 컨테이너는 UID 10101로 돕니다. 홈 디렉터리가 `700`이거나 파일이 `600`이면 붙여도 읽지 못합니다.

- 화면에서 검사하면 읽은 파일은 그대로 보이고, 권한이 없어 열지 못한 디렉터리·파일은 따로 **권한이 없어 열지 못한 곳이 있습니다**로 알려 줍니다. 한 사용자의 디렉터리를 못 열어도 나머지는 계속 찾습니다.
- 서버에서 미리 확인할 수 있습니다. 패턴에 맞는 파일과 그 안의 `root`·`alias`·`ssl_certificate`·`include` 경로까지 UID 10101로 읽어 보고, 못 읽는 것과 붙인 디렉터리 밖이라 보이지 않는 것을 알려 줍니다.

```bash
sudo /opt/nginx-manager/nm import-root check '/home/*/nginx/*.conf'
```

- 읽기 권한은 ACL로 UID 10101에게만 줍니다. 홈 디렉터리의 다른 권한(`700` 등)은 그대로이고, 위쪽 디렉터리에는 지나가기(`x`)만 줍니다. 디렉터리에 주면 나중에 생기는 파일도 읽을 수 있습니다.

```bash
sudo /opt/nginx-manager/nm import-root grant /home/alice/nginx /home/alice/www
```

- `setfacl`이 없으면 `acl` 패키지를 설치합니다(`apt install acl`). ACL을 지원하지 않는 파일시스템이면 `chmod o+rX`로 대신합니다.
- 권한을 바꿀 수 없으면 **내 PC의 파일 올리기**로 내용만 가져옵니다. 이때는 원본이 바뀌어도 알 수 없고, 설정이 가리키는 파일(`root` 등)은 nginx가 읽을 수 있는 곳에 따로 두어야 합니다.

### Certbot 인증서를 그대로 쓰기

서버의 Certbot이 받은 인증서(`/etc/letsencrypt`)를 그대로 쓰는 설정도 가져올 수 있습니다. 관리도구가 인증서를 새로 받게 하는 편(80 server만 가져온 뒤 사이트 화면에서 HTTPS 켜기)이 더 간단하지만, Certbot을 계속 쓰려면 이렇게 합니다.

1. `.env`의 가져오기 디렉터리에 `/etc/letsencrypt`를 더하고(쉼표로 여럿) 다시 띄웁니다. `nm import-root add /etc/letsencrypt`와 같습니다.
   ```
   NGINX_MANAGER_IMPORT_ROOTS=/home,/etc/letsencrypt
   ```
   ```bash
   sudo /opt/nginx-manager/nm up
   ```
2. Certbot은 `live/`·`archive/`를 `700`, 개인 키를 `600`으로 둡니다. UID 10101에게 읽기만 줍니다(`live/`의 링크가 가리키는 `archive/`까지).
   ```bash
   sudo /opt/nginx-manager/nm import-root grant /etc/letsencrypt/live /etc/letsencrypt/archive
   sudo /opt/nginx-manager/nm import-root check '/home/*/nginx/*.conf'
   ```
3. Certbot이 인증서를 갱신하면 nginx가 새 파일을 읽도록 갱신 후 훅을 둡니다. `nm reload`는 설정을 검사하고 통과하면 끊김 없이 다시 읽힙니다.
   ```bash
   printf '#!/bin/sh\n/opt/nginx-manager/nm reload\n' | sudo tee /etc/letsencrypt/renewal-hooks/deploy/nginx-manager.sh
   sudo chmod 755 /etc/letsencrypt/renewal-hooks/deploy/nginx-manager.sh
   ```

갱신된 개인 키는 Certbot이 이전 키의 권한을 따라 만들기 때문에 2에서 준 읽기 권한이 이어집니다. 갱신 뒤 `nm import-root check`로 한 번 확인해 두세요. `include /etc/letsencrypt/options-ssl-nginx.conf`, `ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem`도 같은 디렉터리라 함께 보입니다.

## 미리 보기

검사 결과에서 **미리 보기**를 누르면 파일 하나를 자세히 봅니다.

<p align="center"><img src="images/import-preview.png" alt="폼 사이트로 가져오기 미리 보기" width="860"></p>

- **해석 결과**: server 블록 수와 등록 방식
- **폼으로 옮기면 바뀌는 점**: 예를 들어 로그는 사이트별 로그 파일(`/var/log/nginx/sites/`)에 남기고, IPv6 listen은 빠지며, 백엔드가 하나면 upstream 없이 바로 넘깁니다.
- **폼으로 옮길 수 없는 설정**: 원문 사이트가 되는 이유(location 여럿, HTTPS server, 폼에 없는 지시어 등)
- root와 `location ~ \.php$`의 `fastcgi_pass` 하나로 된 PHP 설정은 [PHP 폼 사이트](04-sites.md#php-php-fpm)로 가져옵니다(v1.4.0부터). `.php` location에 캐시 같은 다른 지시어가 있으면 원문 사이트가 됩니다.
- **대표 도메인**과 **등록 방식**을 정합니다. 폼으로 옮길 수 없는 파일은 원문 사이트로만 등록됩니다.
- **원본**과 **폼 사이트로 만들 설정**을 나란히 비교합니다.

**사이트로 등록(초안)**을 누르면 사이트 화면으로 넘어갑니다.

## 원본 파일은 어떻게 되나

- 설정 루트(`/etc/nginx`) **안**의 원본: 그대로 두면 nginx가 같은 server를 두 번 읽을 수 있어서, **원본 지우기(사이트 파일로 옮김)**가 기본으로 켜져 있습니다. 사이트 초안과 원본 삭제 초안은 함께 적용되고, 둘 중 하나를 버리면 함께 버려집니다.
- 설정 루트 **밖**의 원본(예: `/home/alice/nginx/blog.conf`): 읽기만 하고 그대로 둡니다.

HTTPS server가 들어 있는 파일은 원문 사이트로 들어옵니다. 폼 사이트로 옮겨 HTTPS를 쓰려면 HTTP server만 가져온 뒤 관리도구에서 [HTTPS](04-sites.md#https)를 켭니다.

## 원문 사이트에 HTTPS 켜기

원문 사이트도 관리도구에서 Let's Encrypt 인증서를 받을 수 있습니다(관리자만).

1. 사이트 화면의 **HTTPS 인증서**에서 **Let's Encrypt 인증서 발급받기**를 누릅니다. 80 server 블록에 인증 경로(`/.well-known/acme-challenge/`)를 더한 초안이 생깁니다. 9000처럼 다른 포트를 듣는 server 블록에는 더하지 않습니다(인증 요청은 80으로만 옵니다). 이미 있으면 바로 발급합니다.
2. [적용](07-apply.md)하면 발급을 시작합니다. 인증서는 80 server 블록의 server_name 도메인(와일드카드 제외)으로 받습니다.
3. 발급되면 같은 칸에 인증서 경로와 **443 server 블록 초안에 더하기**가 보입니다. 누르면 80 server 블록을 본떠 `listen 443 ssl`과 인증서 경로를 넣은 블록을 더한 초안이 생깁니다.
4. **HTTP(80) 요청을 HTTPS로 넘기기**(기본 켬)를 켜 두면 80 server 블록은 listen·server_name·인증 경로와 `return 301 https://$host$request_uri;`만 남깁니다. 원래 내용은 443 블록으로 옮겨지고, 인증 경로는 넘기지 않아 갱신이 계속 됩니다. 80도 그대로 서비스하려면 끄고 더합니다.
5. 원문을 확인하고 [적용](07-apply.md)합니다. 이미 HTTPS server 블록이 있으면 버튼이 보이지 않습니다.
6. 인증서는 만료 전에 저절로 갱신되고, 원문의 인증서 경로도 새 경로로 바뀝니다.

원문 편집기에서 443 server 블록을 지우면 그 사이트의 HTTPS도 꺼지고 인증서를 더 갱신하지 않습니다. 쓰는 사이트가 없어진 인증서는 [인증서 화면](09-certs.md)에서 지울 수 있습니다.

## 원본이 바뀌었을 때

가져온 뒤 원본 파일이 바뀌면 검사 결과에 **원본 바뀜**이 보이고, 사이트 화면에 **원본에서 다시 가져오기**가 나타납니다. 누르면 바뀐 내용으로 새 초안을 만듭니다. HTTPS처럼 관리도구에서만 정한 설정은 그대로 둡니다. 원문 사이트에 관리도구가 더한 인증 경로와 443 server 블록(80→443 넘기기 포함)도 새 초안에 다시 넣으므로, 다시 가져와도 HTTPS와 인증서 갱신이 계속 됩니다.
