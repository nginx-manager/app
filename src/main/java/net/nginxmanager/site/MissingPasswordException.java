package net.nginxmanager.site;

/** 비밀번호 보호에 새로 넣은 사용자에게 비밀번호가 없다. */
public class MissingPasswordException extends RuntimeException {

    private final String username;

    public MissingPasswordException(String username) {
        super("password required for new user " + username);
        this.username = username;
    }

    public String getUsername() {
        return username;
    }
}
