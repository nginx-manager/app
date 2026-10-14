package net.nginxmanager.history;

/** 되돌리기를 시작할 수 없다(적용 대기 중인 초안이 있거나, 대상이 실패한 버전). */
public class RollbackRejectedException extends RuntimeException {

    public enum Reason { PENDING_CHANGES, NOT_A_SNAPSHOT }

    private final Reason reason;

    public RollbackRejectedException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
