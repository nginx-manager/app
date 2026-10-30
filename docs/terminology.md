# 화면 용어집

화면 문구(`messages*.properties`)와 사용 설명서(`src/main/resources/guide/ko/`)는 아래 용어를 씁니다. nginx·Git·Terraform을 쓰는 개발자와 운영자가 평소에 쓰는 말을 기준으로 했습니다. 새 문구를 쓸 때도 이 표를 따릅니다.

| 개념 | 한국어 | English | 日本語 | 中文 | 쓰지 않는 말 |
|---|---|---|---|---|---|
| 저장했지만 아직 nginx에 반영하지 않은 변경 | 적용 대기 (변경) | pending (change) | 適用待ち / 保留中の変更 | 待应用（的变更） | 초안, draft, 下書き, 草稿 |
| 저장 버튼 | 저장 | Save | 保存 | 保存 | 초안 저장 |
| 적용 대기 변경을 버림 | 변경 취소 | Discard | 変更を破棄 | 丢弃变更 | 초안 버리기 |
| 설정 반영 | 적용 | Apply | 適用 | 应用 | |
| 적용 버튼 | nginx -t 후 reload | Test & reload | nginx -t して reload | nginx -t 后 reload | 검증 후 적용 |
| 문법 검사 | nginx -t (설정 검사) | nginx -t (config test) | nginx -t (設定テスト) | nginx -t（配置测试） | 문법 검증 |
| reload 뒤 확인 | 헬스 체크 | health check | ヘルスチェック | 健康检查 | 상태 확인 |
| 이전 버전으로 돌림 | 롤백 | Rollback / Roll back | ロールバック | 回滚 | 되돌리기, 복구 |
| 버전 목록 | 버전 이력 | Version history | バージョン履歴 | 版本历史 | 이력, 변경 이력 |
| 두 버전 비교 | diff (base / compare) | Diff (Base / Compare) | diff (base / compare) | diff（base / compare） | 변경 전 / 변경 후 |
| 서버에서 직접 바뀐 설정 | 드리프트 | Drift | ドリフト | 配置漂移 | 외부 변경, 직접 변경 |
| 폼 없이 텍스트로 관리하는 설정 | Raw 설정 (Raw 편집, Raw 사이트) | Raw config | Raw 設定 | Raw 配置 | 원문, 원문 편집, 直接編集 |
| 인증서를 받음 | 발급 | Issue | 発行 | 签发 | 받기, 발급받기, 取得, 申请 |
| Let's Encrypt 도메인 확인 경로 | ACME 챌린지 경로 | ACME challenge path | ACME チャレンジのパス | ACME 质询路径 | 인증 경로, 발급 확인 경로 |
| 프록시 뒤 백엔드 주소 | 업스트림 | Upstream | アップストリーム | 上游 | 프록시 대상 |
| 백엔드에 나눠 보내는 방식 | 로드 밸런싱 (라운드 로빈, 최소 연결, IP 해시) | Load balancing (round robin, least connections, IP hash) | ロードバランシング | 负载均衡（轮询、最少连接、IP 哈希） | 부하 분산, 나누는 방식 |
| 다른 주소로 보냄 | 리다이렉트 | Redirect | リダイレクト | 重定向 | 리디렉션, 넘기기 |
| 로테이트로 밀려난 로그 파일 | 로테이트된 로그 | Rotated log | ローテート済みログ | 已轮转日志 | 지난 로그 |
| 가져오기의 원래 파일 | 소스 파일 | Source | ソースファイル | 源文件 | 원본 |
| 정적 사이트 경로 | document root | Document root | | | 문서 루트 |
| HTTP 기본 인증 | Basic 인증 | Basic auth | Basic 認証 | Basic 认证 | 비밀번호 보호 |
| 적용 메모 | 변경 메시지 | Change message | 変更メッセージ | 变更说明 | 변경 메모 |
| 화면의 누르는 것 | 버튼 | button | ボタン | 按钮 | 단추 |

nginx 지시어 이름(`server` 블록, `listen`, `proxy_pass`, `access_log` 등)은 번역하지 않고 그대로 씁니다.
