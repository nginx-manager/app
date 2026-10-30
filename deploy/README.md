# nginx manager 설치 디렉터리

| 파일 | 내용 | 업그레이드할 때 |
| --- | --- | --- |
| `.env` | 버전, 관리 도메인, 포트 등 이 서버의 설정 | 그대로(버전만 `nm upgrade`가 바꿈) |
| `compose.override.yml` | 직접 더한 설정(스트림 포트, 마운트). 없으면 만들어 씁니다 | 그대로 |
| `compose.yml`, `nm`, `env.example`, `bundle.env` | 이 버전의 묶음 | 새 버전으로 바뀜 |
| `data/` | nginx 설정·인증서·로그, 관리도구 DB | 그대로 |
| `backups/` | `nm backup`이 만든 백업 | 그대로 |

```sh
./nm status                 # 상태와 버전
./nm upgrade                # 최신 릴리스로(manager만, nginx는 끊기지 않음)
./nm upgrade --nginx        # nginx 이미지도(몇 초 재시작)
./nm backup                 # 백업
./nm restore backups/<파일>  # 복원
./nm help
```

스트림(TCP/UDP) 포트를 열 때는 `compose.override.yml`에 적고 `./nm restart nginx`:

```yaml
services:
  nginx:
    ports:
      - "5432:5432"
      - "53:53/udp"
```

자세한 설명: 관리 화면의 **사용 설명서 → 1. 설치와 실행**
