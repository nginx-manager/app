package net.nginxmanager.cert;

public enum CertificateStatus {
    /** 아직 발급 전. 사이트는 HTTP로만 서비스한다. */
    PENDING,
    /** 인증서 파일이 있고 사이트에 쓸 수 있다. */
    ACTIVE
}
