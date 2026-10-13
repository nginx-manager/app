package net.nginxmanager.user;

public class DuplicateUsernameException extends RuntimeException {

    public DuplicateUsernameException(String username) {
        super("username already exists: " + username);
    }
}
