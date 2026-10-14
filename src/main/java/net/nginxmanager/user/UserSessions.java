package net.nginxmanager.user;

import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/** 역할이 바뀌거나 비활성화된 사용자의 로그인 세션을 끊어, 다음 요청부터 바로 반영되게 한다. */
@Component
public class UserSessions {

    private final SessionRegistry sessionRegistry;

    public UserSessions(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    public void expire(String username) {
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (principal instanceof UserDetails details && details.getUsername().equals(username)) {
                sessionRegistry.getAllSessions(principal, false).forEach(SessionInformation::expireNow);
            }
        }
    }
}
