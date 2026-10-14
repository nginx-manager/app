package net.nginxmanager.audit;

/**
 * 감사 로그로 남길 사건. 서비스는 이 이벤트만 발행하고, 저장은 {@link AuditLogRecorder}가 맡는다.
 *
 * @param username 행위자. 시스템 작업이면 null
 */
public record AuditEvent(String username, AuditAction action, String target, AuditResult result, String detail) {

    public static AuditEvent success(String username, AuditAction action, String target) {
        return new AuditEvent(username, action, target, AuditResult.SUCCESS, null);
    }

    public static AuditEvent failure(String username, AuditAction action, String target, String detail) {
        return new AuditEvent(username, action, target, AuditResult.FAILURE, detail);
    }
}
