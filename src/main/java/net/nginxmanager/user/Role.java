package net.nginxmanager.user;

public enum Role {
    ADMIN,
    EDITOR,
    VIEWER;

    public String authority() {
        return "ROLE_" + name();
    }
}
