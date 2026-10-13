package net.nginxmanager.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Set;
import java.util.stream.Collectors;

/** 화면에서 역할별로 메뉴·버튼을 숨길 수 있게 현재 사용자 정보를 모델에 넣는다. */
@ControllerAdvice
public class CurrentUserAdvice {

    public record CurrentUser(String username, Set<String> roles) {

        public boolean canEdit() {
            return roles.contains("ROLE_EDITOR") || isAdmin();
        }

        public boolean isAdmin() {
            return roles.contains("ROLE_ADMIN");
        }
    }

    @ModelAttribute("currentUser")
    public CurrentUser currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Set<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
        return new CurrentUser(authentication.getName(), roles);
    }
}
