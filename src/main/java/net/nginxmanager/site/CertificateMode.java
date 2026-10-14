package net.nginxmanager.site;

/** 사이트의 HTTPS 인증서를 어떻게 마련할지. 사이트를 만들 때 고르고, 만든 뒤에도 바꿀 수 있다. */
public enum CertificateMode {
    /** HTTPS를 쓰지 않는다(HTTP만). 나중에 사이트 화면에서 Let's Encrypt로 받을 수 있다. */
    NONE,
    /** Let's Encrypt로 받아 자동으로 갱신한다. */
    LETS_ENCRYPT,
    /** 인증서 화면에서 올린 인증서를 쓴다. */
    UPLOADED
}
