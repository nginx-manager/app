package net.nginxmanager.common;

import jakarta.servlet.http.HttpServletRequest;
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

    /** 메뉴에서 지금 보고 있는 항목을 표시하려고 경로의 첫 마디를 넣는다(예: /sites/3 → sites). */
    @ModelAttribute("navSection")
    public String navSection(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String[] parts = path.split("/", 3);
        return parts.length > 1 ? parts[1] : "";
    }
}
