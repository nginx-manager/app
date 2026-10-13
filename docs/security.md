# 보안 점검 (T081)

2026-10-13, 007-upgrade-safety 기준으로 확인한 내용.

## 프로세스 실행

- 외부 프로세스는 `runtime/CommandRunner` 한 곳에서만 만든다(`ProcessBuilder`, 셸을 거치지 않음). 다른 곳에서 `ProcessBuilder`·`Runtime.exec`를 쓰지 않는다.
- `CommandRunner`를 부르는 곳은 `LocalNginxRuntime`뿐이고, 인자는 상수다: `nginx -t`, `nginx -s reload`, `nginx -V`, `s6-svc -u <서비스 디렉터리>`. 실행 파일·서비스 경로는 서버 설정(`nginx.runtime.*`)에서만 온다. 사용자 입력은 명령 인자로 들어가지 않는다.
- 컨테이너 안의 nginx와 관리도구는 비루트 `nginxm`으로 돈다. 관리도구가 nginx에 할 수 있는 일은 같은 사용자로서의 reload 신호와, s6 제어 권한(`s6-svperms -G nginxm`)으로 nginx 서비스를 올리는 것뿐이다.

## 파일 경로

- 설정 파일은 설정 루트 아래 상대 경로만 받는다(`ConfigFileStore`가 정규화 후 루트 밖이면 거절).
- 인증서 디렉터리 이름은 `CertificateFiles.safeName`(소문자·숫자·`.`·`-`, `.`으로 시작 불가)으로만 만든다.
- 로그는 사이트 도메인(폼 검증을 거친 값)으로만 경로를 만들고, `LogFiles`가 로그 디렉터리 밖이면 거절한다.

## 비밀 값

- 비밀번호는 해시로만 저장하고 로그·감사 로그에 남기지 않는다.
- 개인 키(업로드·ACME 계정·발급)는 파일로만 저장하고 권한을 600으로 둔다. 화면에 다시 보이지 않고, 업로드 오류 메시지에 키 내용을 넣지 않는다.
- 웹훅 URL은 토큰을 품는 경우가 많아, 발송 실패 로그에는 스킴과 호스트만 남긴다. 화면(ADMIN 전용 알림 설정)에는 전체가 보인다.
- 애플리케이션 로그를 훑어 비밀번호·키·토큰을 남기는 곳이 없음을 확인했다.

## 웹

- 모든 화면은 로그인 필요, 변경 요청은 CSRF 토큰 필요. 역할별 접근은 `RoleAccessTest`가 경로 표(contracts/http-routes.md)대로 검증한다.
- 로그 내용처럼 외부에서 들어온 문자열은 Thymeleaf가 이스케이프하고, 실시간 로그는 `textContent`로만 붙인다.
- actuator는 `health`만 열고 상세를 숨긴다.
- 로그인 실패가 이어지면 계정을 잠근다(감사 로그 `ACCOUNT_LOCKED`).

## 운영할 때 알아 둘 것

- 관리 화면(9000)은 HTTP로 뜬다. 인터넷에 그대로 열지 말고 사내망·VPN에서 쓰거나 HTTPS 프록시 뒤에 둔다. HTTPS 뒤에 두면 `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`로 세션 쿠키를 HTTPS 전용으로 만든다.
- 알림 웹훅은 관리자가 정한 URL로 서버가 요청을 보낸다(내부망 주소도 가능). 관리자 계정을 신뢰할 수 있는 사람에게만 준다.
- 화면에 인라인 이벤트 처리기(`onchange`)가 있어 Content-Security-Policy는 아직 걸지 않았다. 기본 보안 헤더(X-Frame-Options, X-Content-Type-Options 등)는 Spring Security 기본값을 쓴다.
