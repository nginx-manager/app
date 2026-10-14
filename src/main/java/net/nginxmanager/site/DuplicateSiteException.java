package net.nginxmanager.site;

public class DuplicateSiteException extends RuntimeException {

    private final String domain;

    public DuplicateSiteException(String domain) {
        super("site already exists: " + domain);
        this.domain = domain;
    }

    /** 이미 다른 사이트가 쓰는 도메인. */
    public String getDomain() {
        return domain;
    }
}
