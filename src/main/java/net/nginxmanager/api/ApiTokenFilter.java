package net.nginxmanager.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** /api/v1 요청의 Bearer 토큰을 관리자 권한으로 바꾼다(033). 토큰이 없거나 틀리면 인증 없이 넘겨 401이 된다. */
public class ApiTokenFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final ApiTokenService tokens;

    public ApiTokenFilter(ApiTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            tokens.authenticate(header.substring(BEARER.length()).strip()).ifPresent(token ->
                    SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                            token.actor(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))));
        }
        chain.doFilter(request, response);
    }
}
