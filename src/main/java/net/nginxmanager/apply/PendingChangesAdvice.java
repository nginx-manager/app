package net.nginxmanager.apply;

import net.nginxmanager.config.ConfigFileRepository;
import net.nginxmanager.site.SiteAccess;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.function.Predicate;

/** 메뉴에 적용 대기 중인 파일 수를 보여 준다. */
@ControllerAdvice
public class PendingChangesAdvice {

    private final ConfigFileRepository configFileRepository;
    private final SiteAccess access;

    public PendingChangesAdvice(ConfigFileRepository configFileRepository, SiteAccess access) {
        this.configFileRepository = configFileRepository;
        this.access = access;
    }

    @ModelAttribute("pendingCount")
    public long pendingCount(Authentication authentication) {
        if (authentication == null) {
            return 0;
        }
        if (SiteAccess.isAdmin(authentication)) {
            return configFileRepository.countPending();
        }
        // 멤버는 본인 사이트의 초안만 센다
        Predicate<String> mine = access.files(authentication);
        return configFileRepository.findByDraftContentIsNotNullOrDraftDeleteTrue().stream()
                .filter(f -> mine.test(f.getPath()))
                .count();
    }
}
