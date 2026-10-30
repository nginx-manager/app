package net.nginxmanager.site;

public enum SiteType {
    /** 정적 파일 서빙. 폼으로 관리한다. */
    STATIC,
    /** 리버스 프록시. 폼으로 관리한다. */
    PROXY,
    /** 다른 주소로 넘기기(rewrite 모듈의 return). 폼으로 관리한다. */
    REDIRECT,
    /** PHP-FPM(fastcgi)으로 넘기는 PHP 사이트. 문서 루트와 PHP-FPM 주소를 폼으로 관리한다(016). */
    PHP,
    /** 원문 편집 전용. 폼이 다시 덮어쓰지 않는다. */
    RAW
}
