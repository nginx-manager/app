package net.nginxmanager.site;

/** 사이트의 HTTP 포트를 다른 기능(스트림 등)이 이미 쓴다. */
public class PortInUseException extends RuntimeException {

    private final int port;
    private final String usedBy;

    public PortInUseException(int port, String usedBy) {
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
