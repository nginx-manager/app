package net.nginxmanager.stream;

/** 받을 포트를 다른 스트림이나 사이트가 이미 쓴다. */
public class PortTakenException extends RuntimeException {

    private final int port;
    private final String usedBy;

    public PortTakenException(int port, String usedBy) {
        super("port " + port + " is used by " + usedBy);
        this.port = port;
        this.usedBy = usedBy;
    }

    public int getPort() {
        return port;
    }

    public String getUsedBy() {
        return usedBy;
    }
}
