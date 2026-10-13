package net.nginxmanager.cert;

/** nginx 설정에 넣을 인증서·키 파일 경로. */
public record CertificatePaths(String certPath, String keyPath) {
}
