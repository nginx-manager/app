package net.nginxmanager.user;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.context.ApplicationEventPublisher;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SetupState setupState,
                                                   SessionRegistry sessionRegistry) throws Exception {
        http
                .addFilterBefore(new SetupRedirectFilter(setupState), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/setup", "/login", "/css/**", "/js/**", "/webjars/**", "/favicon.ico", "/favicon.svg", "/favicon-32.png", "/apple-touch-icon.png", "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/error").permitAll()
                        // 여러 사이트에 걸치거나 서버 전체에 영향을 주는 기능은 관리자만 쓴다(007)
                        .requestMatchers("/users/**", "/audit/**", "/settings/**", "/runtime/**", "/files/**",
                                "/streams/**", "/versions/**", "/drift/**", "/sites/import", "/sites/import/**",
                                "/sites/*/owner", "/sites/*/reimport", "/logs/*/per-site").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/certs").hasRole("ADMIN")
                        .requestMatchers("/certs/upload", "/certs/*/delete").hasRole("ADMIN")
                        // 나머지(사이트, 적용, 로그, 사이트 화면의 인증서 발급·갱신)는 멤버부터. 사이트 단위 권한은 SiteAccess가 본다
                        .anyRequest().hasRole("MEMBER"))
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .permitAll())
                .sessionManagement(session -> session
                        .maximumSessions(-1)
                        .sessionRegistry(sessionRegistry)
                        .expiredUrl("/login?expired"))
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .permitAll());
        return http.build();
    }

    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role("ADMIN").implies("MEMBER")
                .build();
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** 세션 만료를 SessionRegistry에 알린다. */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationEventPublisher authenticationEventPublisher(ApplicationEventPublisher publisher) {
        return new DefaultAuthenticationEventPublisher(publisher);
    }
}
