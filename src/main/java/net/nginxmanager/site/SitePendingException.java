package net.nginxmanager.site;

/** 적용하지 않은 변경이 있어 사이트를 지울 수 없다(012). 먼저 적용하거나 버린다. */
public class SitePendingException extends IllegalStateException {

    public SitePendingException(String domain) {
        super("site has pending changes: " + domain);
    }
}
