package net.nginxmanager.config;

/**
 * 설정 파일 초안을 버렸다. 같은 트랜잭션 안에서 동기로 전달된다.
 *
 * @param removed 디스크에 한 번도 쓰지 않은 새 파일이라 파일 행째 지웠으면 true
 */
public record ConfigFileDiscardedEvent(String path, boolean removed) {
}
