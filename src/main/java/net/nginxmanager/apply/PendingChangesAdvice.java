package net.nginxmanager.apply;

import net.nginxmanager.config.ConfigFileRepository;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** 메뉴에 적용 대기 중인 파일 수를 보여 준다. */
@ControllerAdvice
public class PendingChangesAdvice {

    private final ConfigFileRepository configFileRepository;

    public PendingChangesAdvice(ConfigFileRepository configFileRepository) {
        this.configFileRepository = configFileRepository;
    }

    @ModelAttribute("pendingCount")
    public long pendingCount(Authentication authentication) {
        if (authentication == null) {
            return 0;
        }
        return configFileRepository.countPending();
    }
}
