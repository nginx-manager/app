package net.nginxmanager.history;

import net.nginxmanager.config.ConfigVersion;
import net.nginxmanager.config.ConfigVersionFile;
import net.nginxmanager.config.UnifiedDiff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** 두 버전의 파일별 차이. 바뀌지 않은 파일은 빠진다. */
public final class VersionDiff {

    public enum Change { ADDED, REMOVED, MODIFIED }

    public record FileDiff(String path, Change change, String diff) {
    }

    private VersionDiff() {
    }

    /**
     * 성공한 버전(스냅샷)은 그 시점의 전체 파일이므로 한쪽에 없는 파일은 추가·삭제로 본다.
     * 실패한 버전은 시도한 파일만 담고 있어, 거기에 없는 파일은 비교하지 않는다.
     */
    public static List<FileDiff> between(ConfigVersion from, ConfigVersion to) {
        Map<String, String> before = contents(from);
        Map<String, String> after = contents(to);
        TreeSet<String> paths = new TreeSet<>(before.keySet());
        paths.addAll(after.keySet());
        List<FileDiff> diffs = new ArrayList<>();
        for (String path : paths) {
            boolean inBefore = before.containsKey(path);
            boolean inAfter = after.containsKey(path);
            if ((!inBefore && !from.isSnapshot()) || (!inAfter && !to.isSnapshot())) {
                continue;
            }
            String a = before.get(path);
            String b = after.get(path);
            if (Objects.equals(a, b)) {
                continue;
            }
            Change change = a == null ? Change.ADDED : b == null ? Change.REMOVED : Change.MODIFIED;
            diffs.add(new FileDiff(path, change, UnifiedDiff.of(path, a, b)));
        }
        return diffs;
    }

    private static Map<String, String> contents(ConfigVersion version) {
        Map<String, String> map = new HashMap<>();
        for (ConfigVersionFile file : version.getFiles()) {
            map.put(file.getPath(), file.getContent());
        }
        return map;
    }
}
