package net.nginxmanager.runtime;

import java.util.List;

/**
 * @param output {@code nginx -t} 표준오류 원문
 * @param errors 파일·줄을 읽어낸 오류·경고. 위치가 없는 메시지는 file과 line이 null
 */
public record ValidationResult(boolean ok, String output, List<ValidationMessage> errors) {

    public record ValidationMessage(String level, String message, String file, Integer line) {
    }
}
