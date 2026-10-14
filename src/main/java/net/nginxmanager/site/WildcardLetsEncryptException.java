package net.nginxmanager.site;

/** 와일드카드 도메인(*.example.com)은 HTTP 인증으로 받을 수 없어 Let's Encrypt로 발급하지 않는다. */
public class WildcardLetsEncryptException extends IllegalStateException {

    public WildcardLetsEncryptException() {
        super("wildcard domains need an uploaded certificate");
    }
}
