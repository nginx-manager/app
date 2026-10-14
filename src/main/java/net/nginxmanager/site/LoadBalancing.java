package net.nginxmanager.site;

/** 백엔드가 여럿일 때 요청을 나누는 방식(nginx upstream). */
public enum LoadBalancing {

    ROUND_ROBIN("차례대로 (기본)", null),
    LEAST_CONN("연결이 가장 적은 백엔드로", "least_conn"),
    IP_HASH("같은 클라이언트 IP는 같은 백엔드로", "ip_hash");

    private final String label;
    private final String directive;

    LoadBalancing(String label, String directive) {
        this.label = label;
        this.directive = directive;
    }

    public String getLabel() {
        return label;
    }

    /** upstream 블록에 넣을 지시어. 기본 방식이면 null. */
    String directive() {
        return directive;
    }
}
