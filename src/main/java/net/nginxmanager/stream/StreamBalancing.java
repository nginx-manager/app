package net.nginxmanager.stream;

/** 백엔드가 여럿일 때 연결을 나누는 방식(stream upstream). */
public enum StreamBalancing {

    ROUND_ROBIN(null),
    LEAST_CONN("least_conn"),
    /** 같은 클라이언트 IP는 같은 백엔드로. 스트림에는 ip_hash가 없어 hash로 같은 효과를 낸다. */
    IP_HASH("hash $remote_addr consistent");

    private final String directive;

    StreamBalancing(String directive) {
        this.directive = directive;
    }

    /** upstream 블록에 넣을 지시어. 기본 방식이면 null. */
    String directive() {
        return directive;
    }
}
