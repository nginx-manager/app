package net.nginxmanager.history;

/** 관리도구가 마지막으로 알던 내용과 디스크가 다른 파일. */
public record DriftItem(String path, Kind kind) {

    public enum Kind { MODIFIED, ADDED, DELETED }
}
