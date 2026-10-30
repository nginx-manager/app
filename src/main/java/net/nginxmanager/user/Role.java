package net.nginxmanager.user;

/** 관리자는 모든 사이트와 기능을, 멤버는 본인이 담당한 사이트만 다룬다(006). */
public enum Role {
    ADMIN,
    MEMBER;

    public String authority() {
        return "ROLE_" + name();
    }
}
