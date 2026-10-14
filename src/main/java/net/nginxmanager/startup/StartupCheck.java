package net.nginxmanager.startup;

import net.nginxmanager.runtime.ValidationResult;

import java.time.Instant;
import java.util.List;

/**
 * 컨테이너 시작 때 nginx를 띄우기 전에 한 설정 검증 결과(init-config-check).
 *
 * @param output   {@code nginx -t} 출력 원문
 * @param messages 출력에서 읽은 파일·줄·메시지
 */
public record StartupCheck(boolean ok, Instant checkedAt, String output,
                           List<ValidationResult.ValidationMessage> messages) {
}
