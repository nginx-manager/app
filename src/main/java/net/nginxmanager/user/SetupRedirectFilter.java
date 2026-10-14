package net.nginxmanager.user;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 사용자가 한 명도 없으면 모든 화면 요청을 최초 관리자 생성 화면으로 보낸다. */
public class SetupRedirectFilter extends OncePerRequestFilter {

    private final SetupState setupState;

    public SetupRedirectFilter(SetupState setupState) {
        this.setupState = setupState;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!setupState.isCompleted() && !isAllowedBeforeSetup(request.getRequestURI())) {
            response.sendRedirect(request.getContextPath() + "/setup");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isAllowedBeforeSetup(String uri) {
        return uri.equals("/setup")
                || uri.startsWith("/css/")
                || uri.startsWith("/js/")
                || uri.startsWith("/webjars/")
                || uri.startsWith("/actuator/health")
                || uri.equals("/error");
    }
}
