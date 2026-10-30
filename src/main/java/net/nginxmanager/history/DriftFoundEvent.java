package net.nginxmanager.history;

import java.util.List;

/** 예약 확인에서 서버에서 직접 바뀐 설정 파일을 새로 찾았다(028). 목록이 같으면 다시 내지 않는다. */
public record DriftFoundEvent(List<DriftItem> items) {
}
