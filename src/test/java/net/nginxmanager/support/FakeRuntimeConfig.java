package net.nginxmanager.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 실제 nginx 대신 {@link FakeNginxRuntime}을 쓴다. */
@TestConfiguration
public class FakeRuntimeConfig {

    @Bean
    @Primary
    FakeNginxRuntime fakeNginxRuntime() {
        return new FakeNginxRuntime();
    }
}
