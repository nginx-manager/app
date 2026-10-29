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
                        .requestMatchers("/setup", "/login", "/css/**", "/js/**", "/webjars/**", "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/error").permitAll()
                        .requestMatchers("/users/**", "/audit/**", "/settings/**", "/runtime/**").hasRole("ADMIN")
                        // 본인 비밀번호 변경은 역할과 관계없이 허용한다
                        .requestMatchers("/account").authenticated()
                        // 적용 화면은 diff 미리보기도 편집자부터 본다
                        .requestMatchers("/apply/**").hasRole("EDITOR")
                        // 가져오기는 허용한 디렉터리의 파일 내용을 보여 주므로 편집자부터 쓴다
                        .requestMatchers("/sites/import", "/sites/import/**").hasRole("EDITOR")
                        // 조회는 VIEWER부터, 변경 요청은 EDITOR부터 허용한다
                        .requestMatchers(HttpMethod.GET, "/**").hasRole("VIEWER")
                        .anyRequest().hasRole("EDITOR"))
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
                .role("ADMIN").implies("EDITOR")
                .role("EDITOR").implies("VIEWER")
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
