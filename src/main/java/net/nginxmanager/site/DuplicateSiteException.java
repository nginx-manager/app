package net.nginxmanager.site;

public class DuplicateSiteException extends RuntimeException {

    public DuplicateSiteException(String domain) {
        super("site already exists: " + domain);
    }
}
