package net.nginxmanager.site;

import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 사이트 단위 권한(007). 관리자는 모든 사이트를, 멤버는 본인이 담당한 사이트만 다룬다.
 * 남의 사이트는 있는지도 알리지 않도록 404로 거절한다.
 */
@Component
public class SiteAccess {

    /** 어느 사용자와도 맞지 않는 id. DB에 없는 멤버는 아무 사이트도 담당하지 않는다. */
    private static final long NOBODY = -1L;

    private final SiteRepository siteRepository;
    private final SiteQueryRepository siteQueryRepository;
    private final UserRepository userRepository;

    public SiteAccess(SiteRepository siteRepository, SiteQueryRepository siteQueryRepository,
                      UserRepository userRepository) {
        this.siteRepository = siteRepository;
        this.siteQueryRepository = siteQueryRepository;
        this.userRepository = userRepository;
    }

    public static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> Role.ADMIN.authority().equals(a.getAuthority()));
    }

    /** 멤버면 본인 사용자 id, 관리자면 비어 있다(제한 없음). */
    @Transactional(readOnly = true)
    public Optional<Long> memberId(Authentication authentication) {
        if (isAdmin(authentication)) {
            return Optional.empty();
        }
        return Optional.of(userRepository.findByUsername(authentication.getName())
                .map(user -> user.getId())
                .orElse(NOBODY));
    }

    /** 새 사이트의 담당자: 관리자가 만들면 없음(null), 멤버가 만들면 본인. */
    @Transactional(readOnly = true)
    public Long ownerOfNewSite(Authentication authentication) {
        Long id = memberId(authentication).orElse(null);
        if (id != null && id == NOBODY) {
            throw new AccessDeniedException("unknown member " + authentication.getName());
        }
        return id;
    }

    /** 이 사용자가 다룰 수 있는 사이트를 돌려준다. 아니면 404. */
    @Transactional(readOnly = true)
    public Site require(long siteId, Authentication authentication) {
        Site site = siteRepository.findById(siteId).orElseThrow(() -> new NotFoundException("site " + siteId));
        check(site, authentication);
        return site;
    }

    public void check(Site site, Authentication authentication) {
        memberId(authentication).ifPresent(id -> {
            if (!id.equals(site.getOwnerId())) {
                throw new NotFoundException("site " + site.getId());
            }
        });
    }

    /**
     * 이 사용자가 보고 적용하고 버릴 수 있는 설정 파일 경로. 관리자는 모든 파일, 멤버는 담당 사이트의 파일
     * (켜기/끄기 양쪽 사이트 파일, 비밀번호 파일, 가져오기 원본 짝)이다. 대표 도메인은 바뀌지 않으므로 경로로 정해진다.
     */
    @Transactional(readOnly = true)
    public Predicate<String> files(Authentication authentication) {
        Optional<Long> member = memberId(authentication);
        if (member.isEmpty()) {
            return path -> true;
        }
        Set<String> paths = new HashSet<>();
        for (SiteQueryRepository.OwnedSite site : siteQueryRepository.findOwned(member.get())) {
            paths.add(SitePaths.of(site.primaryDomain(), true));
            paths.add(SitePaths.of(site.primaryDomain(), false));
            paths.add(Htpasswd.path(site.primaryDomain()));
            if (site.importedFrom() != null) {
                paths.add(site.importedFrom());
            }
        }
        return paths::contains;
    }
}
