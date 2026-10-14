package net.nginxmanager.site;

/** 원문 편집 사이트는 폼으로 고치지 않는다. 폼을 연 사이 원문 편집기로 고쳐 원문 사이트가 된 경우 등. */
public class RawSiteFormException extends IllegalStateException {

    public RawSiteFormException() {
        super("raw sites are edited in the file editor");
    }
}
