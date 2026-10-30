package net.nginxmanager.user;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 로그인 두 번째 단계(017). 비밀번호가 맞은 세션에 "코드 대기" 표시를 남기고, 표시가 있는 동안은 코드 화면과 로그아웃만 연다.
 */
public final class TwoFactorLogin {

    /** 세션 속성: 코드를 기다리는 아이디. */
    static final String PENDING = TwoFactorLogin.class.getName() + ".PENDING";
    static final String CODE_PAGE = "/login/code";

    private TwoFactorLogin() {
    }

    static String pendingUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (String) session.getAttribute(PENDING);
    }

    /** 비밀번호가 맞으면 2단계 인증이 켜진 사용자는 코드 화면으로, 아니면 첫 화면으로 보낸다. */
    static final class SuccessHandler implements AuthenticationSuccessHandler {

        private final TwoFactorService twoFactor;

        SuccessHandler(TwoFactorService twoFactor) {
            this.twoFactor = twoFactor;
        }

        @Override
        public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                            Authentication authentication) throws IOException {
            HttpSession session = request.getSession();
            if (twoFactor.isEnabled(authentication.getName())) {
                session.setAttribute(PENDING, authentication.getName());
                response.sendRedirect(request.getContextPath() + CODE_PAGE);
            } else {
                session.removeAttribute(PENDING);
                response.sendRedirect(request.getContextPath() + "/");
            }
        }
    }

    /** 코드를 넣기 전에는 다른 화면을 열지 못하게 코드 화면으로 돌려보낸다. */
    static final class PendingFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (pendingUser(request) != null && !isAllowed(request.getRequestURI().substring(
                    request.getContextPath().length()))) {
                response.sendRedirect(request.getContextPath() + CODE_PAGE);
                return;
            }
            chain.doFilter(request, response);
        }

        private static boolean isAllowed(String uri) {
            return uri.equals(CODE_PAGE)
                    || uri.equals("/login")
                    || uri.equals("/logout")
                    || uri.startsWith("/css/")
                    || uri.startsWith("/js/")
                    || uri.startsWith("/webjars/")
                    || uri.equals("/favicon.ico") || uri.equals("/favicon.svg")
                    || uri.equals("/favicon-32.png") || uri.equals("/apple-touch-icon.png")
                    || uri.startsWith("/actuator/health")
                    || uri.equals("/error");
        }
    }
}
