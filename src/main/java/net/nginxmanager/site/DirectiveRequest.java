package net.nginxmanager.site;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import net.nginxmanager.common.BaseEntity;

/**
 * 담당 멤버가 관리자에게 보낸 추가 지시어 요청(035). 관리자 전용 지시어가 섞여 멤버가 바로 저장할 수 없을 때 만든다.
 * 관리자가 반영하면 사이트의 추가 지시어가 이 값으로 바뀌고(적용 대기), 반영하거나 거절하면 요청은 없어진다.
 */
@Entity
@Table(name = "directive_request")
public class DirectiveRequest extends BaseEntity {

    @Column(name = "site_id", nullable = false, unique = true)
    private Long siteId;

    @Column(name = "requested_by", nullable = false, length = 50)
    private String requestedBy;

    /** 요청한 추가 지시어 전체(반영하면 이 값이 그대로 사이트 값이 된다). */
    @Column(nullable = false)
    private String directives;

    protected DirectiveRequest() {
    }

    DirectiveRequest(Long siteId, String requestedBy, String directives) {
        this.siteId = siteId;
        this.requestedBy = requestedBy;
        this.directives = directives;
    }

    void replace(String requestedBy, String directives) {
        this.requestedBy = requestedBy;
        this.directives = directives;
    }

    public Long getSiteId() {
        return siteId;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public String getDirectives() {
        return directives;
    }

    /** 요청 가운데 관리자 전용 지시어(검토할 곳). */
    public java.util.List<String> getAdminOnly() {
        return ServerDirectivePolicy.adminOnly(directives);
    }
}
