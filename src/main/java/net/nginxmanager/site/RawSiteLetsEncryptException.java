package net.nginxmanager.site;

/** 원문 편집 사이트는 TLS를 자기 설정 파일에서 다루므로 Let's Encrypt를 폼으로 켜지 않는다. */
public class RawSiteLetsEncryptException extends IllegalStateException {

    public RawSiteLetsEncryptException() {
        super("raw sites manage TLS in their own config");
    }
}
