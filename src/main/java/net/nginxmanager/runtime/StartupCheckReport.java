package net.nginxmanager.runtime;

import java.time.Instant;

/**
 * nginx 컨테이너가 시작될 때 nginx를 띄우기 전에 한 {@code nginx -t} 결과(init-config-check).
 *
 * @param checkedAt 검사한 때. 모르면 null
 */
public record StartupCheckReport(boolean ok, String output, Instant checkedAt) {
}
