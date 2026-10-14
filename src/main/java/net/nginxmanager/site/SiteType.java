package net.nginxmanager.site;

public enum SiteType {
    /** 정적 파일 서빙. 폼으로 관리한다. */
    STATIC,
    /** 리버스 프록시. 폼으로 관리한다. */
    PROXY,
    /** 다른 주소로 넘기기(rewrite 모듈의 return). 폼으로 관리한다. */
    REDIRECT,
    /** 원문 편집 전용. 폼이 다시 덮어쓰지 않는다. */
    RAW
}
