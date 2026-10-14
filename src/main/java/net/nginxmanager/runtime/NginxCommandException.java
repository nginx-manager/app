package net.nginxmanager.runtime;

public class NginxCommandException extends RuntimeException {

    public NginxCommandException(String message) {
        super(message);
    }

    public NginxCommandException(String message, Throwable cause) {
        super(message, cause);
    }
}
