package net.nginxmanager.site;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import net.nginxmanager.common.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 추가 지시어 요청(035). 담당 멤버가 관리자 전용 지시어가 든 추가 지시어를 요청하면 관리자가 사이트 화면에서
 * 반영(사이트 값으로 바꾸고 적용 대기)하거나 거절한다. 요청·반영·거절은 감사 로그에 남고, 요청은 알림 채널로도 간다.
 */
@Service
public class DirectiveRequestService {

    private final DirectiveRequestRepository requests;
    private final SiteRepository sites;
    private final SiteService siteService;
    private final ApplicationEventPublisher events;

    public DirectiveRequestService(DirectiveRequestRepository requests, SiteRepository sites, SiteService siteService,
                                   ApplicationEventPublisher events) {
        this.requests = requests;
        this.sites = sites;
        this.siteService = siteService;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Optional<DirectiveRequest> find(long siteId) {
        return requests.findBySiteId(siteId);
    }

    /** 검토할 요청과 그 사이트 도메인(관리자의 사이트 목록에 보인다). */
    public record Pending(long siteId, String domain, String requestedBy) {
    }

    @Transactional(readOnly = true)
    public List<Pending> pending() {
        return requests.findAllByOrderByIdAsc().stream()
                .flatMap(r -> sites.findById(r.getSiteId()).stream()
                        .map(site -> new Pending(site.getId(), site.getPrimaryDomain(), r.getRequestedBy())))
                .toList();
    }

    /** 요청을 남긴다. 같은 사이트에 검토 중인 요청이 있으면 새 값으로 바꾼다. */
    @Transactional
    public DirectiveRequest request(Site site, String directives, String actor) {
        DirectiveRequest request = requests.findBySiteId(site.getId())
                .map(existing -> {
                    existing.replace(actor, directives);
                    return existing;
                })
                .orElseGet(() -> requests.save(new DirectiveRequest(site.getId(), actor, directives)));
        List<String> names = ServerDirectivePolicy.names(ServerDirectivePolicy.adminOnly(directives));
        events.publishEvent(new AuditEvent(actor, AuditAction.DIRECTIVE_REQUEST, "site:" + site.getPrimaryDomain(),
                net.nginxmanager.audit.AuditResult.SUCCESS, "requested: " + String.join(", ", names)));
        return request;
    }

    /**
     * 관리자가 반영한다. 사이트의 추가 지시어를 요청 값으로 바꾸는 초안을 만들고 요청을 지운다.
     *
     * @return 초안을 만들었는지(이미 같은 값이면 false)
     */
    public boolean approve(long siteId, String actor) {
        DirectiveRequest request = requests.findBySiteId(siteId).orElseThrow(() -> new NotFoundException("request " + siteId));
        Site site = sites.findById(siteId).orElseThrow(() -> new NotFoundException("site " + siteId));
        boolean changed = siteService.setServerDirectives(siteId, request.getDirectives(), actor);
        requests.delete(request);
        events.publishEvent(new AuditEvent(actor, AuditAction.DIRECTIVE_REQUEST, "site:" + site.getPrimaryDomain(),
                net.nginxmanager.audit.AuditResult.SUCCESS, "approved: " + request.getRequestedBy()));
        return changed;
    }

    @Transactional
    public void reject(long siteId, String actor) {
        DirectiveRequest request = requests.findBySiteId(siteId).orElseThrow(() -> new NotFoundException("request " + siteId));
        Site site = sites.findById(siteId).orElseThrow(() -> new NotFoundException("site " + siteId));
        requests.delete(request);
        events.publishEvent(new AuditEvent(actor, AuditAction.DIRECTIVE_REQUEST, "site:" + site.getPrimaryDomain(),
                net.nginxmanager.audit.AuditResult.SUCCESS, "rejected: " + request.getRequestedBy()));
    }
}
