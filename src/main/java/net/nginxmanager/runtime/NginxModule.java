package net.nginxmanager.runtime;

/** @param dynamic {@code load_module}로 불러오는 동적 모듈이면 true */
public record NginxModule(String name, boolean dynamic) {
}
