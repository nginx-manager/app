package net.nginxmanager.config;

/** 원문 편집기에서 파일 초안을 저장했다. 같은 트랜잭션 안에서 동기로 전달된다. */
public record RawEditedEvent(String path) {
}
