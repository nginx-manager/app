package net.nginxmanager.user;

/** 마지막 활성 관리자를 강등하거나 비활성화하려 했다. */
public class LastAdminException extends RuntimeException {

    public LastAdminException() {
        super("at least one enabled ADMIN must remain");
    }
}
