# vX.Y.Z — 한 줄 요약: 이번 버전에서 가장 눈에 띄는 변화 두세 가지

- 날짜: YYYY-MM-DD
- 이미지: `ghcr.io/nginx-manager/app:X.Y.Z-nginxN.N.N`
- nginx: N.N.N

이번 버전을 한두 문장으로 소개합니다. 누가 무엇을 새로 할 수 있게 됐는지를 씁니다.

## 바뀐 기능 하나에 제목 하나

사용자가 화면에서 무엇을 할 수 있게 됐는지 씁니다. 내부 구현보다 쓰는 사람이 보는 변화를 먼저 씁니다.

- 세부 동작은 목록으로 짧게 씁니다.

<p align="center"><img src="images/vX.Y.Z/화면-이름.png" alt="화면 설명" width="860"></p>

## 고친 문제

- 어떤 상황에서 무엇이 잘못됐고, 이제 어떻게 동작하는지 한 줄씩 씁니다.

## 업그레이드할 때

- `nm upgrade`로 manager만 올리면 되는지, nginx 이미지도 함께 올려야 하는지(`nm upgrade --nginx`) 씁니다. 에이전트 API 판(`AgentNginxRuntime.PROTOCOL`)을 바꾼 릴리스는 `MIN_AGENT_PROTOCOL`로 이전 nginx 이미지도 계속 쓸 수 있는지 확인하고, 그렇지 않으면 "nginx도 함께 올려야 함"을 적습니다.
- `.env`·설치 묶음·환경 변수에서 손볼 것이 있으면 씁니다. 없으면 이 절을 지웁니다.
