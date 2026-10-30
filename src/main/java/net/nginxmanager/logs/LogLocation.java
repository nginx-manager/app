package net.nginxmanager.logs;

import net.nginxmanager.site.RawLogs;

import java.nio.file.Path;

/**
 * 한 사이트·한 종류의 로그 위치.
 *
 * @param file       읽을 파일. status가 FILE이 아니면 null
 * @param configPath nginx 설정에 적힌 경로(화면 표시용). 모르면 null
 * @param status     원문 사이트에서 읽을 수 없는 이유. 폼 사이트는 늘 FILE
 * @param raw        원문 사이트인지
 * @param pending    반영된 설정에는 없지만 적용을 기다리는 초안에 사이트별 로그가 있다
 */
public record LogLocation(Path file, String configPath, RawLogs.Status status, boolean raw, boolean pending) {

    public boolean readable() {
        return file != null;
    }

    /** 관리자가 "사이트별 로그로 남기기"를 할 수 있다. */
    public boolean canAddSiteLogs() {
        return raw && !readable() && !pending;
    }
}
