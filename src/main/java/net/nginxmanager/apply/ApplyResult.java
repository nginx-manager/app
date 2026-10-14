package net.nginxmanager.apply;

import net.nginxmanager.config.VersionResult;
import net.nginxmanager.runtime.ValidationResult.ValidationMessage;

import java.util.List;

/**
 * 적용 한 번의 결과.
 *
 * @param result 변경이 없었으면 null
 * @param versionId 기록된 버전. 변경이 없었으면 null
 * @param detail 검증 출력이나 복구 사유
 */
public record ApplyResult(VersionResult result, Long versionId, List<String> changedPaths,
                          List<ValidationMessage> messages, String detail) {

    public static ApplyResult nothingToApply() {
        return new ApplyResult(null, null, List.of(), List.of(), null);
    }

    public boolean succeeded() {
        return result == VersionResult.SUCCESS;
    }

    /** 검증은 통과했지만 nginx가 멈춰 있어 reload하지 않았다. */
    public boolean isReloadSkipped() {
        return succeeded() && detail() != null && detail().contains(ApplyService.RELOAD_SKIPPED);
    }

    public boolean nothingChanged() {
        return result == null;
    }
}
