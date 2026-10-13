package net.nginxmanager.apply;

import net.nginxmanager.config.VersionSource;

import java.util.List;

/** 설정이 nginx에 반영됐다(트랜잭션 커밋 뒤에 발행). */
public record ConfigAppliedEvent(Long versionId, VersionSource source, List<String> paths) {
}
